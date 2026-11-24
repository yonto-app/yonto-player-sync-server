package app.yonto.sync

import java.io.Closeable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

data class Account(
    val id: String,
    val generation: Int,
    val verifier: String,
    val previousVerifier: String?,
    /** When a reset waiting out its days is due, while one is. */
    val resetDue: Long? = null,
    /** The revision of the contract its devices speak (`contracts/sync-v1.md`, *Revisions*), which only goes up. */
    val revision: Int = SyncServer.OLDEST_REVISION,
)

/** A reset asked for without the key (`contracts/sync-v1.md`, *Resetting an account*): the account it would make, and where it stands. */
data class Reset(
    val account: String,
    val id: String,
    val secretHash: String,
    val verifier: String,
    val generation: Int,
    val device: NewDevice,
    val state: String,
    val code: String,
    val asked: Long,
    /** When a waiting reset is due; null for one waiting for its email. */
    val after: Long?,
    val ended: Long? = null,
    /** When the licensing service was last asked whether its email was confirmed. */
    val checked: Long? = null,
    /** The licence install that asked, on a server that checks one: the only one that reads it. */
    val asker: String? = null,
) {
    companion object {
        const val EMAILED = "emailed"
        const val WAITING = "waiting"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        const val LAPSED = "lapsed"
        val OPEN = setOf(EMAILED, WAITING)
    }
}

data class Device(
    val id: String,
    val platform: String,
    val publicKey: String,
    /** Null for a device listed before devices signed their records, which joins again. */
    val signingKey: String?,
    val secretHash: String,
    val removed: Boolean,
    val added: Long,
    /** Allowed through a join and not yet heard from with its own secret. */
    val unseen: Boolean,
    /** Every licence install it proved on a server that checks one, each a token's `dev`: what removing it forgets. */
    val installs: List<String> = emptyList(),
)

data class Join(
    val id: String,
    val account: String,
    val device: String,
    val platform: String,
    val commitment: String,
    /** The new device's keys and nonce, once revealed. */
    val publicKey: String?,
    val signingKey: String?,
    val nonce: String?,
    val deviceSecretHash: String,
    val joinSecretHash: String,
    val state: String,
    val created: Long,
    val openedBy: String?,
    val openerKey: String?,
    val openerNonce: String?,
    val wrap: Wrap?,
    val addedDevice: Boolean,
    /** Who asked: the licence install on a server that checks one, else the client. */
    val asker: String? = null,
)

sealed interface PushOutcome {
    data class Done(val taken: List<String>, val refused: List<Refusal>) : PushOutcome
    data object AccountFull : PushOutcome
    data object NoAccount : PushOutcome
}

/**
 * Everything the server keeps, in SQLite or PostgreSQL alike: the SQL here is written once, in the shape both take.
 * One connection, used by one thread at a time, so every step that reads and then writes is one transaction.
 */
class Store(private val database: Database) : Closeable {
    private var connection: Connection = database.connect()
    private var closed = false

    init {
        transaction { migrate() }
    }

    val replica: String = synchronized(this) { meta("replica")!! }

    private fun migrate() {
        update("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val held = meta("schema")?.toInt() ?: 0
        check(held <= SCHEMA.size) { "The database is at schema version $held, newer than this server's ${SCHEMA.size}: run a newer server." }
        SCHEMA.drop(held).forEach { version -> version.forEach { update(it) } }
        // A SQLite database from before the versions: the first one's tables were there, some without their newest columns.
        if (held == 0 && database is Database.Sqlite) addColumnsUnversionedSqliteLacks()
        setMeta("schema", SCHEMA.size.toString())
        if (meta("replica") == null) {
            setMeta("replica", Codec.randomHex(16))
            setMeta("seq", "0")
        }
    }

    private fun addColumnsUnversionedSqliteLacks() {
        if (query("SELECT 1 FROM pragma_table_info('device') WHERE name = 'unseen'") { true }.isEmpty()) {
            update("ALTER TABLE device ADD COLUMN unseen INTEGER NOT NULL DEFAULT 0")
        }
        // A database from before devices signed their records: a device listed then has none, and joins again.
        for (table in listOf("device", "join_request", "account_reset")) {
            if (query("SELECT 1 FROM pragma_table_info('$table') WHERE name = 'signing_key'") { true }.isEmpty()) {
                update("ALTER TABLE $table ADD COLUMN signing_key TEXT")
            }
        }
        if (query("SELECT 1 FROM pragma_table_info('join_request') WHERE name = 'asker'") { true }.isEmpty()) {
            update("ALTER TABLE join_request ADD COLUMN asker TEXT")
        }
        if (query("SELECT 1 FROM pragma_table_info('join_request') WHERE name = 'wrap_read'") { true }.isEmpty()) {
            update("ALTER TABLE join_request ADD COLUMN wrap_read INTEGER NOT NULL DEFAULT 0")
        }
        if (query("SELECT 1 FROM pragma_table_info('account_reset') WHERE name = 'asker'") { true }.isEmpty()) {
            update("ALTER TABLE account_reset ADD COLUMN asker TEXT")
        }
        if (query("SELECT 1 FROM pragma_table_info('account') WHERE name = 'revision'") { true }.isEmpty()) {
            update("ALTER TABLE account ADD COLUMN revision INTEGER NOT NULL DEFAULT ${SyncServer.OLDEST_REVISION}")
        }
    }

    // Accounts

    @Synchronized
    fun account(id: String): Account? =
        query(
            """SELECT a.id, a.generation, a.verifier, a.previous_verifier, r.after, a.revision FROM account a
               LEFT JOIN account_reset r ON r.account = a.id AND r.state = 'waiting' WHERE a.id = ?""",
            id,
        ) {
            Account(it.getString(1), it.getInt(2), it.getString(3), it.getString(4), it.longOrNull(5), it.getInt(6))
        }.firstOrNull()

    /** Moves the account up to [revision]; never down. */
    @Synchronized
    fun raise(id: String, revision: Int) {
        update("UPDATE account SET revision = ? WHERE id = ? AND revision < ?", revision, id, revision)
    }

    /** False when the account already exists. */
    @Synchronized
    fun createAccount(id: String, verifier: String, generation: Int, device: NewDevice, install: String?, now: Long): Boolean =
        transaction {
            if (account(id) != null) return@transaction false
            update("INSERT INTO account (id, generation, verifier, created) VALUES (?, ?, ?, ?)", id, generation, verifier, now)
            update(
                "INSERT INTO device (account, id, platform, public_key, signing_key, secret_hash, added) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, device.id, device.platform, device.publicKey, device.signingKey, device.secretHash, now,
            )
            install?.let { proved(id, device.id, it) }
            true
        }

    /** Drops the account and, by cascade, its records, devices, wraps, joins and reset; the rows deleted from `account`. */
    @Synchronized
    fun deleteAccount(id: String): Int = transaction {
        update("DELETE FROM account WHERE id = ?", id)
    }

    @Synchronized
    fun accountIds(): List<String> = query("SELECT id FROM account ORDER BY created") { it.getString(1) }

    /**
     * Gives the database a new replica id, so every device pushes what it holds again: a restored copy lacks what was
     * pushed since it was taken. A server already running answers with the one it read until it starts again.
     */
    @Synchronized
    fun newReplica(): String = Codec.randomHex(16).also { setMeta("replica", it) }

    /** False when the account's generation moved first. */
    @Synchronized
    fun startOver(account: String, from: Int, to: StartOver): Boolean = transaction {
        val changed = update(
            """UPDATE account SET generation = ?, previous_verifier = verifier, verifier = ?
               WHERE id = ? AND generation = ?""",
            to.generation, to.verifier, account, from,
        )
        if (changed == 0) return@transaction false
        update("DELETE FROM record WHERE account = ? AND gen < ?", account, to.generation)
        update("DELETE FROM wrap WHERE account = ?", account)
        val active = devices(account).filterNot { it.removed }.map { it.id }.toSet()
        to.wraps.filter { it.device in active }.forEach {
            update(
                """INSERT INTO wrap (account, device, sender, sealed) VALUES (?, ?, ?, ?)
                   ON CONFLICT (account, device) DO UPDATE SET sender = excluded.sender, sealed = excluded.sealed""",
                account, it.device, it.wrap.from, it.wrap.sealed,
            )
        }
        true
    }

    // Resets

    @Synchronized
    fun reset(account: String): Reset? = query(
        """SELECT account, id, secret_hash, verifier, generation, device, platform, public_key, device_secret_hash,
                  state, code, asked, after, ended, checked, asker, signing_key FROM account_reset WHERE account = ?""",
        account,
    ) {
        Reset(
            account = it.getString(1),
            id = it.getString(2),
            secretHash = it.getString(3),
            verifier = it.getString(4),
            generation = it.getInt(5),
            device = NewDevice(it.getString(6), it.getString(7), it.getString(8), it.getString(17).orEmpty(), it.getString(9)),
            state = it.getString(10),
            code = it.getString(11),
            asked = it.getLong(12),
            after = it.longOrNull(13),
            ended = it.longOrNull(14),
            checked = it.longOrNull(15),
            asker = it.getString(16),
        )
    }.firstOrNull()

    /** Keeps [reset] as the account's one reset, in place of any before it; false when the account is gone. */
    @Synchronized
    fun askReset(reset: Reset): Boolean = transaction {
        if (account(reset.account) == null) return@transaction false
        update(
            """INSERT INTO account_reset
               (account, id, secret_hash, verifier, generation, device, platform, public_key, device_secret_hash,
                state, code, asked, after, ended, checked, asker, signing_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
               ON CONFLICT (account) DO UPDATE SET id = excluded.id, secret_hash = excluded.secret_hash,
                verifier = excluded.verifier, generation = excluded.generation, device = excluded.device,
                platform = excluded.platform, public_key = excluded.public_key, device_secret_hash = excluded.device_secret_hash,
                state = excluded.state, code = excluded.code, asked = excluded.asked, after = excluded.after,
                ended = NULL, checked = NULL, asker = excluded.asker, signing_key = excluded.signing_key""",
            reset.account, reset.id, reset.secretHash, reset.verifier, reset.generation, reset.device.id,
            reset.device.platform, reset.device.publicKey, reset.device.secretHash, reset.state, reset.code, reset.asked,
            reset.after, reset.asker, reset.device.signingKey,
        )
        true
    }

    /** A request proved the account's key: a reset waiting out its days is cancelled, one waiting for its email is not. */
    @Synchronized
    fun cancelWaitingReset(account: String, now: Long) {
        update("UPDATE account_reset SET state = 'cancelled', ended = ? WHERE account = ? AND state = 'waiting'", now, account)
    }

    @Synchronized
    fun checkedReset(account: String, id: String, now: Long) {
        update("UPDATE account_reset SET checked = ? WHERE account = ? AND id = ?", now, account, id)
    }

    /** Ends reset [id] as [state] while it is still open; false when it was not. */
    @Synchronized
    fun endReset(account: String, id: String, state: String, now: Long): Boolean = update(
        "UPDATE account_reset SET state = ?, ended = ? WHERE account = ? AND id = ? AND state IN ('emailed', 'waiting')",
        state, now, account, id,
    ) > 0

    @Synchronized
    fun withdrawReset(account: String, id: String): Boolean =
        update("DELETE FROM account_reset WHERE account = ? AND id = ? AND state IN ('emailed', 'waiting')", account, id) > 0

    /**
     * Makes reset [id]: the account becomes the one it asked for, its records, devices, wraps and joins dropped, in one
     * step, so the account is never missing for anyone else to make. False when it is no longer open.
     */
    @Synchronized
    fun applyReset(account: String, id: String, now: Long): Boolean = transaction {
        val reset = reset(account)?.takeIf { it.id == id && it.state in Reset.OPEN } ?: return@transaction false
        make(reset, now)
        true
    }

    /**
     * Makes the account's reset if it is one waiting out its days whose time has come by [now], read and made in one
     * step, so a reset asked meanwhile in its place is never made in its stead; false when there is none.
     */
    @Synchronized
    fun makeDueReset(account: String, now: Long): Boolean = transaction {
        val reset = reset(account)?.takeIf { it.state == Reset.WAITING && it.after != null && it.after <= now } ?: return@transaction false
        make(reset, now)
        true
    }

    private fun make(reset: Reset, now: Long) {
        val account = reset.account
        update("DELETE FROM record WHERE account = ?", account)
        update("DELETE FROM wrap WHERE account = ?", account)
        update("DELETE FROM join_request WHERE account = ?", account)
        update("DELETE FROM device WHERE account = ?", account)
        update(
            "UPDATE account SET verifier = ?, generation = ?, previous_verifier = NULL WHERE id = ?",
            reset.verifier, reset.generation, account,
        )
        update(
            "INSERT INTO device (account, id, platform, public_key, signing_key, secret_hash, added) VALUES (?, ?, ?, ?, ?, ?, ?)",
            account, reset.device.id, reset.device.platform, reset.device.publicKey, reset.device.signingKey, reset.device.secretHash, now,
        )
        reset.asker?.let { proved(account, reset.device.id, it) }
        update("UPDATE account_reset SET state = 'done', ended = ? WHERE account = ?", now, account)
    }

    /** Every waiting reset due by [now], as account and reset id. */
    @Synchronized
    fun dueResets(now: Long): List<Pair<String, String>> =
        query("SELECT account, id FROM account_reset WHERE state = 'waiting' AND after <= ?", now) { it.getString(1) to it.getString(2) }

    /** Lapses resets left waiting for an email since before [asked], and forgets those that ended before [ended]. */
    @Synchronized
    fun pruneResets(asked: Long, ended: Long) = transaction {
        update("UPDATE account_reset SET state = 'lapsed', ended = ? WHERE state = 'emailed' AND asked < ?", asked, asked)
        update("DELETE FROM account_reset WHERE ended IS NOT NULL AND ended < ?", ended)
    }

    // Devices

    @Synchronized
    fun devices(account: String): List<Device> =
        query(
            "SELECT id, platform, public_key, signing_key, secret_hash, removed, added, unseen FROM device WHERE account = ? ORDER BY added, id",
            account,
        ) {
            Device(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getInt(6) != 0, it.getLong(7), it.getInt(8) != 0)
        }.let { devices ->
            val installs = query("SELECT device, install FROM device_install WHERE account = ? ORDER BY install", account) { it.getString(1) to it.getString(2) }
                .groupBy({ it.first }, { it.second })
            devices.map { it.copy(installs = installs[it.id].orEmpty()) }
        }

    @Synchronized
    fun device(account: String, id: String): Device? = devices(account).firstOrNull { it.id == id }

    @Synchronized
    fun activeDevices(account: String): Int = devices(account).count { !it.removed }

    /** Adds a device that holds the key; refuses one removed, one already listed, or one past [maxDevices]. */
    @Synchronized
    fun addDevice(account: String, device: NewDevice, install: String?, maxDevices: Int, now: Long): String? = transaction {
        val existing = device(account, device.id)
        when {
            existing?.removed == true -> "removed"
            existing != null -> "exists"
            activeDevices(account) >= maxDevices -> "full"
            else -> {
                update(
                    "INSERT INTO device (account, id, platform, public_key, signing_key, secret_hash, added) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    account, device.id, device.platform, device.publicKey, device.signingKey, device.secretHash, now,
                )
                install?.let { proved(account, device.id, it) }
                null
            }
        }
    }

    /** False when the device is not on the list. */
    @Synchronized
    fun removeDevice(account: String, id: String): Boolean = transaction {
        update("DELETE FROM wrap WHERE account = ? AND device = ?", account, id)
        update("UPDATE device SET removed = 1 WHERE account = ? AND id = ?", account, id) > 0
    }

    /** The device proved itself with its own secret: a join that was allowed for it finished. */
    @Synchronized
    fun seen(account: String, id: String) {
        update("UPDATE device SET unseen = 0 WHERE account = ? AND id = ?", account, id)
    }

    /** The device proved [install], so it is one of the installs removing it forgets; each is kept, not only the last. */
    @Synchronized
    fun proved(account: String, id: String, install: String) {
        update("INSERT INTO device_install (account, device, install) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", account, id, install)
    }

    /**
     * Takes off the list every device allowed through a join before [added] that never made a request with its own
     * secret, so a join that never finished does not hold a place under the device cap.
     */
    @Synchronized
    fun dropUnseenBefore(added: Long) = transaction {
        val unseen = "SELECT account, id FROM device WHERE unseen = 1 AND removed = 0 AND added < ?"
        update("DELETE FROM wrap WHERE (account, device) IN ($unseen)", added)
        update("DELETE FROM device WHERE unseen = 1 AND removed = 0 AND added < ?", added)
    }

    @Synchronized
    fun wrap(account: String, device: String): Wrap? =
        query("SELECT sender, sealed FROM wrap WHERE account = ? AND device = ?", account, device) {
            Wrap(it.getString(1), it.getString(2))
        }.firstOrNull()

    @Synchronized
    fun deleteWrap(account: String, device: String) {
        update("DELETE FROM wrap WHERE account = ? AND device = ?", account, device)
    }

    // Records

    @Synchronized
    fun push(
        account: String,
        records: List<WireRecord>,
        refuse: (record: WireRecord, generation: Int) -> String?,
        maxAccountBytes: Long,
        maxAccountRecords: Int,
        now: Long,
    ): PushOutcome = transaction {
        val generation = account(account)?.generation ?: return@transaction PushOutcome.NoAccount
        val before = accountBytes(account)
        var count = recordCount(account)
        val taken = mutableListOf<String>()
        val refused = mutableListOf<Refusal>()
        for (record in records) {
            val reason = refuse(record, generation)
            if (reason != null) {
                refused += Refusal(record.id, reason)
                continue
            }
            val held = heldStamp(account, record.id)
            if (held == null && count >= maxAccountRecords) {
                refused += Refusal(record.id, "full")
                continue
            }
            taken += record.id
            if (held == null) count++
            if (held == null || wins(record, held.first, held.second)) write(account, record, now)
        }
        val after = accountBytes(account)
        if (after > before && after > maxAccountBytes) {
            connection.rollback()
            PushOutcome.AccountFull
        } else {
            PushOutcome.Done(taken, refused)
        }
    }

    private fun wins(incoming: WireRecord, heldStamp: Stamp, heldDeleted: Boolean): Boolean {
        val order = incoming.stamp.compareTo(heldStamp)
        return order > 0 || (order == 0 && incoming.deleted && !heldDeleted)
    }

    private fun heldStamp(account: String, id: String): Pair<Stamp, Boolean>? =
        query("SELECT millis, counter, device, deleted FROM record WHERE account = ? AND id = ?", account, id) {
            Stamp(it.getLong(1), it.getLong(2), it.getString(3)) to (it.getInt(4) != 0)
        }.firstOrNull()

    private fun write(account: String, record: WireRecord, now: Long) {
        val seq = meta("seq")!!.toLong() + 1
        setMeta("seq", seq.toString())
        update(
            """INSERT INTO record (account, id, gen, millis, counter, device, deleted, kept, sealed, size, seq, taken)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (account, id) DO UPDATE SET gen = excluded.gen, millis = excluded.millis, counter = excluded.counter,
                device = excluded.device, deleted = excluded.deleted, kept = excluded.kept, sealed = excluded.sealed,
                size = excluded.size, seq = excluded.seq, taken = excluded.taken""",
            account, record.id, record.gen, record.stamp.millis, record.stamp.counter, record.stamp.device,
            record.deleted.bit, record.kept.bit, record.sealed, record.sealed.length * 3 / 4, seq, now,
        )
    }

    private fun recordCount(account: String): Int =
        query("SELECT COUNT(*) FROM record WHERE account = ?", account) { it.getInt(1) }.first()

    private fun accountBytes(account: String): Long =
        query("SELECT COALESCE(SUM(size), 0) FROM record WHERE account = ?", account) { it.getLong(1) }.first()

    /** Up to [limit] copies changed after [since], in the order they were taken, and whether more follow. */
    @Synchronized
    fun pull(account: String, since: Long, limit: Int): Pair<List<Pair<Long, WireRecord>>, Boolean> {
        val rows = query(
            """SELECT seq, id, gen, millis, counter, device, deleted, kept, sealed FROM record
               WHERE account = ? AND seq > ? ORDER BY seq LIMIT ?""",
            account, since, limit + 1,
        ) {
            it.getLong(1) to WireRecord(
                id = it.getString(2),
                gen = it.getInt(3),
                stamp = Stamp(it.getLong(4), it.getLong(5), it.getString(6)),
                deleted = it.getInt(7) != 0,
                kept = it.getInt(8) != 0,
                sealed = it.getString(9),
            )
        }
        return rows.take(limit) to (rows.size > limit)
    }

    @Synchronized
    fun forget(account: String, ids: List<String>) = transaction {
        ids.forEach { update("DELETE FROM record WHERE account = ? AND id = ?", account, it) }
    }

    /** Drops tombstones taken more than [keepMillis] before [now], except kept ones. */
    @Synchronized
    fun pruneTombstones(now: Long, keepMillis: Long): Int =
        update("DELETE FROM record WHERE deleted = 1 AND kept = 0 AND taken < ?", now - keepMillis)

    // Joins

    @Synchronized
    fun createJoin(join: Join) {
        update(
            """INSERT INTO join_request
               (id, account, device, platform, commitment, device_secret_hash, join_secret_hash, state, created, asker)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            join.id, join.account, join.device, join.platform, join.commitment, join.deviceSecretHash,
            join.joinSecretHash, join.state, join.created, join.asker,
        )
    }

    @Synchronized
    fun join(id: String): Join? = query(
        """SELECT id, account, device, platform, commitment, public_key, nonce, device_secret_hash, join_secret_hash,
                  state, created, opened_by, opener_key, opener_nonce, wrap_sender, wrap_sealed, added_device, signing_key, asker
           FROM join_request WHERE id = ?""",
        id,
    ) {
        Join(
            id = it.getString(1),
            account = it.getString(2),
            device = it.getString(3),
            platform = it.getString(4),
            commitment = it.getString(5),
            publicKey = it.getString(6),
            nonce = it.getString(7),
            deviceSecretHash = it.getString(8),
            joinSecretHash = it.getString(9),
            state = it.getString(10),
            created = it.getLong(11),
            openedBy = it.getString(12),
            openerKey = it.getString(13),
            openerNonce = it.getString(14),
            wrap = it.getString(15)?.let { sender -> Wrap(sender, it.getString(16)) },
            addedDevice = it.getInt(17) != 0,
            signingKey = it.getString(18),
            asker = it.getString(19),
        )
    }.firstOrNull()

    @Synchronized
    fun joins(account: String): List<Join> =
        query("SELECT id FROM join_request WHERE account = ? ORDER BY created, id", account) { it.getString(1) }
            .mapNotNull(::join)

    @Synchronized
    fun openJoin(id: String, openedBy: String, opener: JoinKey): Boolean = update(
        """UPDATE join_request SET state = 'opened', opened_by = ?, opener_key = ?, opener_nonce = ?
           WHERE id = ? AND state = 'waiting'""",
        openedBy, opener.publicKey, opener.nonce, id,
    ) > 0

    @Synchronized
    fun revealJoin(id: String, joining: JoinReveal): Boolean = update(
        "UPDATE join_request SET state = 'revealed', public_key = ?, signing_key = ?, nonce = ? WHERE id = ? AND state = 'opened'",
        joining.publicKey, joining.signingKey, joining.nonce, id,
    ) > 0

    sealed interface Allowing {
        data object Done : Allowing
        data object NotOpen : Allowing
        data object Full : Allowing

        /** The join names a device already on the list, whose key and secret a join never replaces. */
        data object Exists : Allowing
    }

    /** Allows the join and puts its device on the list. */
    @Synchronized
    fun allowJoin(join: Join, wrap: Wrap, install: String?, maxDevices: Int, now: Long): Allowing = transaction {
        val existing = device(join.account, join.device)
        if (existing?.removed == true) return@transaction Allowing.NotOpen
        if (existing != null) return@transaction Allowing.Exists
        if (activeDevices(join.account) >= maxDevices) return@transaction Allowing.Full
        val changed = update(
            """UPDATE join_request SET state = 'allowed', wrap_sender = ?, wrap_sealed = ?, added_device = 1
               WHERE id = ? AND state = 'revealed'""",
            wrap.from, wrap.sealed, join.id,
        )
        if (changed == 0) return@transaction Allowing.NotOpen
        update(
            "INSERT INTO device (account, id, platform, public_key, signing_key, secret_hash, added, unseen) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
            join.account, join.device, join.platform, checkNotNull(join.publicKey), checkNotNull(join.signingKey), join.deviceSecretHash, now,
        )
        install?.let { proved(join.account, join.device, it) }
        Allowing.Done
    }

    @Synchronized
    fun refuseJoin(id: String, untilSeen: Boolean): Refusing = transaction {
        val join = join(id) ?: return@transaction Refusing.Done
        val added = join.state == "allowed" && join.addedDevice
        if (untilSeen && added && device(join.account, join.device)?.unseen == false) return@transaction Refusing.NotOpen
        // The new device read its wrap: the key is out, so the device and its entry stay for a removal to take back.
        if (!untilSeen && added && wrapRead(id)) return@transaction Refusing.Taken
        update("UPDATE join_request SET state = 'refused', wrap_sender = NULL, wrap_sealed = NULL WHERE id = ?", id)
        if (added) update("DELETE FROM device WHERE account = ? AND id = ?", join.account, join.device)
        Refusing.Done
    }

    enum class Refusing { Done, NotOpen, Taken }

    /**
     * The wrap of an allowed join, marked read in the same transaction a refuse checks it in, so the two never cross:
     * either the new device gets the key and a refuse is answered `taken`, or the refuse lands first and it gets none.
     */
    @Synchronized
    fun readJoinWrap(id: String): Wrap? = transaction {
        val wrap = join(id)?.takeIf { it.state == "allowed" }?.wrap ?: return@transaction null
        update("UPDATE join_request SET wrap_read = 1 WHERE id = ?", id)
        wrap
    }

    private fun wrapRead(id: String): Boolean =
        query("SELECT wrap_read FROM join_request WHERE id = ?", id) { it.getInt(1) != 0 }.firstOrNull() == true

    @Synchronized
    fun dropJoinsBefore(created: Long) = update("DELETE FROM join_request WHERE created < ?", created)

    // Plumbing

    /**
     * Copies everything [source] holds into this database, which must hold no account yet, in one transaction; the
     * replica id and the sequence come too, so the devices carry on from where they were and push nothing again.
     */
    @Synchronized
    fun copyFrom(source: Store): Int = transaction {
        check(accountIds().isEmpty()) { "This database already holds accounts: copy only into an empty one." }
        val rows = COPIED_TABLES.sumOf { table -> copyTable(source, table) }
        setMeta("replica", source.replica)
        setMeta("seq", source.meta("seq")!!)
        rows
    }

    private fun copyTable(source: Store, table: String): Int = synchronized(source) {
        source.connection.prepareStatement("SELECT * FROM $table").use { select ->
            select.executeQuery().use { rows ->
                val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnName(it) }
                val insert = "INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})"
                connection.prepareStatement(insert).use { statement ->
                    var count = 0
                    while (rows.next()) {
                        columns.indices.forEach { statement.setObject(it + 1, rows.getObject(it + 1)) }
                        statement.addBatch()
                        // Written as it goes, so a table of full accounts never sits in memory whole.
                        if (++count % COPY_BATCH_ROWS == 0) statement.executeBatch()
                    }
                    if (count % COPY_BATCH_ROWS != 0) statement.executeBatch()
                    count
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        if (connection.isClosed) return
        if (database is Database.Sqlite) connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        connection.close()
    }

    private fun meta(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", key) { it.getString(1) }.firstOrNull()

    private fun setMeta(key: String, value: String) {
        update("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value)
    }

    private fun <T> transaction(block: () -> T): T = synchronized(this) {
        connection.autoCommit = false
        try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
    }

    private fun update(sql: String, vararg args: Any?): Int =
        reconnectingAfter { connection.prepareStatement(sql).use { bind(it, args); it.executeUpdate() } }

    private fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = reconnectingAfter {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(row(results)) }
            }
        }
    }

    /**
     * A PostgreSQL server that restarted leaves the connection dead: the request that finds it fails, and the next one
     * gets a new connection, rather than every request failing until this server restarts too.
     */
    private fun <T> reconnectingAfter(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (!closed && database is Database.Postgres && !connection.isValid(CONNECTION_CHECK_SECONDS)) {
            runCatching { connection.close() }
            connection = database.connect()
        }
        throw e
    }

    private val Boolean.bit get() = if (this) 1 else 0

    private fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

    private companion object {
        const val CONNECTION_CHECK_SECONDS = 2
        const val COPY_BATCH_ROWS = 500

        /** In the order a copy inserts them, each after the tables its rows refer to. */
        val COPIED_TABLES = listOf("account", "device", "device_install", "record", "wrap", "join_request", "account_reset")

        /**
         * Each version's statements, run once, in order, on a database below it; its number is kept in `meta` as `schema`.
         * A new version is a new entry at the end, written for SQLite and PostgreSQL alike; an old one never changes.
         * Times are BIGINT, which SQLite reads as its one INTEGER and PostgreSQL as 64 bits.
         */
        val SCHEMA: List<List<String>> = listOf(
            listOf(
                """CREATE TABLE IF NOT EXISTS account (
                    id TEXT PRIMARY KEY, generation INTEGER NOT NULL, verifier TEXT NOT NULL,
                    previous_verifier TEXT, created BIGINT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT ${SyncServer.OLDEST_REVISION})""",
                """CREATE TABLE IF NOT EXISTS device (
                    account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE, id TEXT NOT NULL,
                    platform TEXT NOT NULL, public_key TEXT NOT NULL, secret_hash TEXT NOT NULL,
                    removed INTEGER NOT NULL DEFAULT 0,
                    added BIGINT NOT NULL, unseen INTEGER NOT NULL DEFAULT 0, signing_key TEXT, PRIMARY KEY (account, id))""",
                """CREATE TABLE IF NOT EXISTS record (
                    account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE, id TEXT NOT NULL,
                    gen INTEGER NOT NULL, millis BIGINT NOT NULL, counter BIGINT NOT NULL, device TEXT NOT NULL,
                    deleted INTEGER NOT NULL, kept INTEGER NOT NULL, sealed TEXT NOT NULL, size BIGINT NOT NULL,
                    seq BIGINT NOT NULL, taken BIGINT NOT NULL, PRIMARY KEY (account, id))""",
                "CREATE INDEX IF NOT EXISTS record_by_seq ON record (account, seq)",
                "CREATE INDEX IF NOT EXISTS record_tombstones ON record (deleted, kept, taken)",
                """CREATE TABLE IF NOT EXISTS wrap (
                    account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE, device TEXT NOT NULL,
                    sender TEXT NOT NULL, sealed TEXT NOT NULL, PRIMARY KEY (account, device))""",
                """CREATE TABLE IF NOT EXISTS join_request (
                    id TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                    device TEXT NOT NULL, platform TEXT NOT NULL, commitment TEXT NOT NULL, public_key TEXT, nonce TEXT,
                    device_secret_hash TEXT NOT NULL, join_secret_hash TEXT NOT NULL,
                    state TEXT NOT NULL, created BIGINT NOT NULL, opened_by TEXT, opener_key TEXT, opener_nonce TEXT,
                    wrap_sender TEXT, wrap_sealed TEXT, added_device INTEGER NOT NULL DEFAULT 0, signing_key TEXT,
                    wrap_read INTEGER NOT NULL DEFAULT 0, asker TEXT)""",
                "CREATE INDEX IF NOT EXISTS join_by_account ON join_request (account, created)",
                """CREATE TABLE IF NOT EXISTS account_reset (
                    account TEXT PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE, id TEXT NOT NULL,
                    secret_hash TEXT NOT NULL, verifier TEXT NOT NULL, generation INTEGER NOT NULL,
                    device TEXT NOT NULL, platform TEXT NOT NULL, public_key TEXT NOT NULL, device_secret_hash TEXT NOT NULL,
                    state TEXT NOT NULL, code TEXT NOT NULL, asked BIGINT NOT NULL, after BIGINT, ended BIGINT, checked BIGINT,
                    asker TEXT, signing_key TEXT)""",
                """CREATE TABLE IF NOT EXISTS device_install (
                    account TEXT NOT NULL, device TEXT NOT NULL, install TEXT NOT NULL,
                    PRIMARY KEY (account, device, install),
                    FOREIGN KEY (account, device) REFERENCES device(account, id) ON DELETE CASCADE)""",
            ),
        )
    }
}
