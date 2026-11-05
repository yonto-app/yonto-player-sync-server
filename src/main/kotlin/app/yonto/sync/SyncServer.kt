package app.yonto.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A refusal, answered as `{"error": code}`. */
class Refused(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    Exception(code, null, false, false)

class SyncServer(
    private val config: Config,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    val store = Store(config.database)
    private val licensing = config.licensingUrl?.let { url ->
        val keys = config.licenceKeys ?: error("YONTO_LICENSING_URL needs YONTO_LICENCE_KEYS, the keys licence tokens are signed with")
        Licensing(
            baseUrl = url,
            tokens = LicenceTokens(PRODUCT, LicenceTokens.parseKeys(keys)),
            clock = clock,
            allowMillis = config.licenceAllowSeconds * 1000,
            refuseMillis = config.licenceRefuseSeconds * 1000,
            staleMillis = config.licenceStaleSeconds * 1000,
        )
    }
    private val byAccount = RateLimiter(config.accountRequestsPerMinute, clock)
    private val byAddress = RateLimiter(config.addressRequestsPerMinute, clock)
    private val resetAsks = RateLimiter(RESET_ASKS_PER_DAY, clock, RateLimiter.DAY_MILLIS)

    private val bodies = BodyBudget.forHeap(config.maxBodyBytes)
    private val inFlight = InFlight(MAX_IN_FLIGHT_PER_CLIENT)

    /** One virtual thread per exchange, so a client sending slowly parks only its own, never a pool everyone shares. */
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val http: HttpServer = run {
        limitConnections()
        HttpServer.create(InetSocketAddress(config.port), 0).apply {
            createContext("/") { exchange ->
                val call = Call(exchange, config, bodies)
                if (!inFlight.enter(call.client)) call.dropBusy()
                try {
                    handle(call)
                } finally {
                    exchange.close()
                    inFlight.leave(call.client)
                }
            }
            executor = this@SyncServer.executor
        }
    }
    private val sweeper = Executors.newSingleThreadScheduledExecutor { Thread(it, "sweep").apply { isDaemon = true } }

    val port: Int get() = http.address.port

    fun start() = apply {
        http.start()
        sweeper.scheduleWithFixedDelay(::sweepSafely, 0, SWEEP_EVERY_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        http.stop(0)
        sweeper.shutdownNow()
        executor.shutdown()
        store.close()
    }

    /** Prunes old tombstones and joins, and devices a join added that never came; run hourly off the request path, so no request touches the database unchecked. */
    fun sweep() {
        val now = clock()
        store.pruneTombstones(now, TOMBSTONE_MILLIS)
        store.dropJoinsBefore(now - JOIN_KEPT_MILLIS)
        store.dropUnseenBefore(now - JOIN_KEPT_MILLIS)
        store.dueResets(now).forEach { (account, reset) -> store.applyReset(account, reset, now) }
        store.pruneResets(asked = now - RESET_KEPT_MILLIS, ended = now - RESET_KEPT_MILLIS)
    }

    private fun sweepSafely() {
        runCatching(::sweep).onFailure { System.err.println("Sweep failed: $it") }
    }

    private fun handle(call: Call) {
        val exchange = call.exchange
        exchange.responseHeaders.set(REVISION_HEADER, REVISION.toString())
        try {
            byAddress.take(call.client)?.let { throw Refused(429, "rate_limited", it) }
            // A client that names another revision is refused before anything is done; one that names none is served.
            call.header(REVISION_HEADER)?.let { if (it.trim() != REVISION.toString()) throw Refused(409, "revision") }
            route(call)
        } catch (refused: Refused) {
            call.refuse(refused)
        } catch (_: IOException) {
            // The client went away, or the request time limit cut it off: nobody is left to answer.
        } catch (e: Exception) {
            System.err.println("${exchange.requestMethod} ${loggedPath(exchange.requestURI.rawPath)} failed")
            e.printStackTrace()
            call.respond(500, ErrorAnswer("internal"))
        }
    }

    private fun route(call: Call) {
        val path = call.exchange.requestURI.rawPath
        if (!path.startsWith("/v1/")) throw Refused(404, "not_found")
        val parts = path.removePrefix("/v1/").split('/')
        when (call.method) {
            "GET" -> when {
                parts == listOf("account") -> getAccount(call)
                parts == listOf("account", "reset") -> resetState(call)
                parts == listOf("devices") -> devices(call)
                parts == listOf("wrap") -> getWrap(call)
                parts == listOf("records") -> pull(call)
                parts == listOf("joins") -> joins(call)
                parts.size == 2 && parts[0] == "joins" -> joinState(call, parts[1])
                else -> throw Refused(404, "not_found")
            }
            "PUT" -> when (parts) {
                listOf("account") -> createAccount(call)
                else -> throw Refused(404, "not_found")
            }
            "DELETE" -> when (parts) {
                listOf("account") -> deleteAccount(call)
                listOf("account", "reset") -> withdrawReset(call)
                listOf("wrap") -> deleteWrap(call)
                else -> throw Refused(404, "not_found")
            }
            "POST" -> when {
                parts == listOf("account", "generation") -> startOver(call)
                parts == listOf("account", "reset") -> askReset(call)
                parts == listOf("records") -> push(call)
                parts == listOf("forget") -> forget(call)
                parts == listOf("joins") -> askToJoin(call)
                parts == listOf("devices") -> addDevice(call)
                parts.size == 3 && parts[0] == "devices" && parts[2] == "remove" -> removeDevice(call, parts[1])
                parts.size == 3 && parts[0] == "joins" -> when (parts[2]) {
                    "open" -> openJoin(call, parts[1])
                    "reveal" -> revealJoin(call, parts[1])
                    "allow" -> allowJoin(call, parts[1])
                    "refuse" -> refuseJoin(call, parts[1])
                    else -> throw Refused(404, "not_found")
                }
                else -> throw Refused(404, "not_found")
            }
            else -> throw Refused(404, "not_found")
        }
    }

    // Who is asking

    /**
     * The account, licensed where the server checks a licence. Its rate is counted only once something proves the
     * caller is the account's (here the licence, or the key in [holdsKey]), so a stranger naming it cannot spend it.
     */
    private fun licensed(call: Call, counted: Boolean = true): String = licensee(call, counted).account

    /**
     * Who asks: the account, and where the server checks a licence the token, the install id that proves it, and the
     * install it names. A token is taken only with its install id, so one that left its device on its own opens nothing.
     */
    private class Licensee(val account: String, val token: String?, val installId: String?, val install: String?) {
        /** Whom a per-asker limit counts: the install where there is one, else the client. */
        fun asker(call: Call): String = install?.let { "$INSTALL_ASKER$it" } ?: "client:${call.client}"
    }

    // The install a join's asker names, which its device is listed with once allowed.
    private fun installOf(asker: String?): String? = asker?.takeIf { it.startsWith(INSTALL_ASKER) }?.removePrefix(INSTALL_ASKER)

    private fun licensee(call: Call, counted: Boolean = true): Licensee {
        val account = call.header("Yonto-Account")?.takeIf(Codec::isAccountId) ?: throw Refused(400, "bad_request")
        if (licensing == null) return Licensee(account, null, null, null)
        val token = call.header("Yonto-Licence")?.takeIf { it.isNotEmpty() && it.length <= 4096 }
            ?: throw Refused(403, "not_allowed")
        val installId = call.header("Yonto-Install")?.takeIf { it.isNotEmpty() && it.length <= 128 }
            ?: throw Refused(403, "not_allowed")
        val install = when (val answer = licensing.check(token, installId, account)) {
            is LicenceAnswer.Allowed -> if (answer.licence != account) throw Refused(403, "not_allowed") else answer.install
            is LicenceAnswer.Busy -> throw Refused(429, "rate_limited", answer.retryAfterSeconds)
            is LicenceAnswer.Unavailable -> throw Refused(503, "unavailable", answer.retryAfterSeconds)
            LicenceAnswer.Refused -> throw Refused(403, "not_allowed")
        }
        if (counted) countRequest(account)
        return Licensee(account, token, installId, install)
    }

    /** The account as it stands now: a reset waiting out its days that fell due is made first. */
    private fun accountNow(id: String): Account? {
        val account = store.account(id) ?: return null
        if (account.resetDue == null || !store.makeDueReset(id, clock())) return account
        return store.account(id)
    }

    private fun countRequest(account: String) {
        byAccount.take(account)?.let { throw Refused(429, "rate_limited", it) }
    }

    private class Keyed(val account: Account, val device: String)

    private fun keyed(call: Call, previousAccepted: Boolean = false): Keyed {
        val licensee = licensee(call, counted = false)
        val account = holdsKey(call, previousAccepted, licensee)
        return Keyed(account, provenDevice(call, account.id, licensee))
    }

    /** The listed, not removed device [call] names, proven by its own secret; the install it proves is one it is listed with. */
    private fun provenDevice(call: Call, account: String, licensee: Licensee): String {
        val device = call.header("Yonto-Device")?.takeIf(Codec::isDeviceId) ?: throw Refused(400, "bad_request")
        val known = store.device(account, device) ?: throw Refused(403, "unknown_device")
        if (known.removed) throw Refused(403, "removed")
        val secret = call.header("Yonto-Device-Secret")?.let(Codec::base64Url)?.takeIf { it.isNotEmpty() }
            ?: throw Refused(401, "unauthorized")
        if (!Codec.sameHex(Codec.hashOf(secret), known.secretHash)) throw Refused(401, "unauthorized")
        if (known.unseen) store.seen(account, device)
        if (licensee.install != null && licensee.install !in known.installs) store.proved(account, device, licensee.install)
        return device
    }

    /** The licensed account, whose sync key the caller proves it holds. */
    private fun holdsKey(call: Call, previousAccepted: Boolean, licensee: Licensee = licensee(call, counted = false)): Account {
        val account = accountNow(licensee.account) ?: throw Refused(404, "no_account")
        when (keyProof(call, account)) {
            KeyProof.CURRENT -> Unit
            KeyProof.PREVIOUS -> if (!previousAccepted) throw Refused(401, "generation")
            null -> throw Refused(401, "unauthorized")
        }
        keyProved(account)
        countRequest(account.id)
        return account
    }

    private enum class KeyProof { CURRENT, PREVIOUS }

    // Which of the account's keys the caller's `auth` proves, if any.
    private fun keyProof(call: Call, account: Account): KeyProof? {
        val auth = call.header("Authorization")
            ?.takeIf { it.startsWith(AUTH_SCHEME) }
            ?.let { Codec.base64Url(it.removePrefix(AUTH_SCHEME).trim()) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val verifier = Codec.hashOf(auth)
        return when {
            Codec.sameHex(verifier, account.verifier) -> KeyProof.CURRENT
            account.previousVerifier?.let { Codec.sameHex(verifier, it) } == true -> KeyProof.PREVIOUS
            else -> null
        }
    }

    // Proof of the key, so a device that still holds it is here: a reset asked without it stops waiting.
    private fun keyProved(account: Account) {
        if (account.resetDue != null) store.cancelWaitingReset(account.id, clock())
    }

    // Accounts

    private fun getAccount(call: Call) {
        val account = accountNow(licensed(call)) ?: throw Refused(404, "no_account")
        call.respond(200, GenerationAnswer(account.generation))
    }

    private fun createAccount(call: Call) {
        val licensee = licensee(call)
        val account = licensee.account
        if (config.accounts != null && account !in config.accounts) throw Refused(403, "not_allowed")
        val body = call.body<CreateAccount>()
        if (!Codec.isHash(body.verifier) || body.generation < 1 || !body.device.valid()) {
            throw Refused(400, "bad_request")
        }
        if (!store.createAccount(account, body.verifier, body.generation, body.device, licensee.install, clock())) {
            throw Refused(409, "exists")
        }
        call.respondEmpty(201)
    }

    private fun startOver(call: Call) {
        val keyed = keyed(call)
        val body = call.body<StartOver>()
        val wrapsRead = body.wraps.all { Codec.isDeviceId(it.device) && it.wrap.valid(MAX_WRAP_CHARS) }
        if (!Codec.isHash(body.verifier) || !wrapsRead) throw Refused(400, "bad_request")
        if (body.generation != keyed.account.generation + 1 || !store.startOver(keyed.account.id, keyed.account.generation, body)) {
            throw Refused(409, "state")
        }
        call.respondEmpty()
    }

    private fun deleteAccount(call: Call) {
        store.deleteAccount(keyed(call).account.id)
        call.respondEmpty()
    }

    // Resets

    private fun askReset(call: Call) {
        val licensee = licensee(call)
        val account = accountNow(licensee.account) ?: throw Refused(404, "no_account")
        val body = call.body<CreateAccount>()
        if (!Codec.isHash(body.verifier) || body.generation < 1 || !body.device.valid()) throw Refused(400, "bad_request")
        resetAsks.take(licensee.asker(call))?.let { throw Refused(429, "rate_limited", it) }
        val id = Codec.randomHex(16)
        val code = Codec.randomDigits(6)
        val now = clock()
        val email = when (licensing) {
            null -> false
            else -> when (val answer = licensing.askReset(licensee.token!!, licensee.installId!!, id, code, body.device.platform)) {
                Licensing.ResetAnswer.Email -> true
                Licensing.ResetAnswer.Wait -> false
                Licensing.ResetAnswer.Refused -> throw Refused(403, "not_allowed")
                is Licensing.ResetAnswer.Busy -> throw Refused(429, "rate_limited", answer.retryAfterSeconds)
                // Never a wait in its place: the email is what a token that holds no key cannot get past.
                Licensing.ResetAnswer.Down -> throw Refused(503, "unavailable", RESET_RETRY_SECONDS)
            }
        }
        val secret = Codec.randomBase64Url(32)
        val reset = Reset(
            account = account.id,
            id = id,
            secretHash = secretHash(secret),
            verifier = body.verifier,
            generation = body.generation,
            device = body.device,
            state = if (email) Reset.EMAILED else Reset.WAITING,
            code = code,
            asked = now,
            after = if (email) null else now + RESET_WAIT_MILLIS,
            asker = licensee.install,
        )
        if (!store.askReset(reset)) throw Refused(404, "no_account")
        call.respond(200, ResetMade(id, secret, if (email) "email" else "wait", code.takeIf { email }, reset.after))
    }

    private fun resetState(call: Call) {
        val licensee = licensee(call)
        var reset = askerReset(call, licensee)
        if (reset.state == Reset.WAITING) {
            store.makeDueReset(reset.account, clock())
        } else if (reset.state == Reset.EMAILED && licensing != null) {
            checkEmail(licensee, reset)
        }
        reset = askerReset(call, licensee)
        call.respond(200, ResetState(reset.state, reset.after))
    }

    // At most every few seconds, however often the asking device polls.
    private fun checkEmail(licensee: Licensee, reset: Reset) {
        val now = clock()
        if (reset.checked != null && now - reset.checked < RESET_CHECK_MILLIS) return
        store.checkedReset(reset.account, reset.id, now)
        when (licensing!!.resetConfirmed(licensee.token!!, licensee.installId!!, reset.id)) {
            Licensing.Confirmation.Confirmed -> store.applyReset(reset.account, reset.id, now)
            Licensing.Confirmation.Gone -> store.endReset(reset.account, reset.id, Reset.LAPSED, now)
            Licensing.Confirmation.Refused -> throw Refused(403, "not_allowed")
            Licensing.Confirmation.NotYet, Licensing.Confirmation.Unknown -> Unit
        }
    }

    private fun withdrawReset(call: Call) {
        val licensee = licensee(call)
        val reset = askerReset(call, licensee)
        if (!store.withdrawReset(licensee.account, reset.id)) throw Refused(409, "state")
        call.respondEmpty()
    }

    /**
     * The account's reset, when [call] carries its secret and, on a server that checks a licence, comes from the install
     * that asked, so no other install of the licence can poll it into being made; a reset another replaced reads as none.
     */
    private fun askerReset(call: Call, licensee: Licensee): Reset {
        val secret = call.header(RESET_SECRET) ?: throw Refused(401, "unauthorized")
        val reset = store.reset(licensee.account) ?: throw Refused(404, "not_found")
        if (!Codec.sameHex(secretHash(secret), reset.secretHash)) throw Refused(404, "not_found")
        if (reset.asker != null && reset.asker != licensee.install) throw Refused(404, "not_found")
        return reset
    }

    /** What a device that syncs is told of a reset: one waiting for its email, or one its syncing cancelled lately. */
    private fun resetNotice(account: String): ResetNotice? {
        val reset = store.reset(account) ?: return null
        val shown = reset.state == Reset.EMAILED ||
            (reset.state == Reset.CANCELLED && reset.ended != null && clock() - reset.ended < RESET_KEPT_MILLIS)
        return ResetNotice(reset.asked, reset.device.platform, reset.state).takeIf { shown }
    }

    // Devices

    private fun devices(call: Call) {
        val keyed = keyed(call)
        val devices = store.devices(keyed.account.id).map { DeviceView(it.id, it.platform, it.publicKey, it.signingKey, it.removed, it.added, it.installs) }
        call.respond(200, DevicesAnswer(devices))
    }

    private fun addDevice(call: Call) {
        val licensee = licensee(call, counted = false)
        val account = holdsKey(call, previousAccepted = false, licensee)
        val body = call.body<NewDevice>()
        if (!body.valid()) throw Refused(400, "bad_request")
        when (store.addDevice(account.id, body, licensee.install, config.maxDevices, clock())) {
            null -> call.respondEmpty(201)
            "removed" -> throw Refused(403, "removed")
            "exists" -> throw Refused(409, "exists")
            else -> throw Refused(409, "full")
        }
    }

    private fun removeDevice(call: Call, id: String) {
        val keyed = keyed(call)
        val installs = if (Codec.isDeviceId(id)) store.device(keyed.account.id, id)?.installs.orEmpty() else emptyList()
        if (!Codec.isDeviceId(id) || !store.removeDevice(keyed.account.id, id)) throw Refused(404, "unknown_device")
        // Every install this server saw the device prove, which the remover deactivated first: each token is asked about again.
        installs.forEach { install -> licensing?.forget(keyed.account.id, install) }
        call.respondEmpty()
    }

    /**
     * A device's own wrap, proven by its own secret whatever key it holds: one any number of Start overs behind can still
     * take the key it was wrapped, which only it can open.
     */
    private fun getWrap(call: Call) {
        val licensee = licensee(call, counted = false)
        val account = accountNow(licensee.account) ?: throw Refused(404, "no_account")
        val device = provenDevice(call, account.id, licensee)
        if (keyProof(call, account) != null) keyProved(account)
        countRequest(account.id)
        val wrap = store.wrap(account.id, device) ?: throw Refused(404, "not_found")
        call.respond(200, WrapAnswer(wrap))
    }

    private fun deleteWrap(call: Call) {
        val keyed = keyed(call, previousAccepted = true)
        store.deleteWrap(keyed.account.id, keyed.device)
        call.respondEmpty()
    }

    // Records

    private fun push(call: Call) {
        val keyed = keyed(call)
        val body = call.body<Push>()
        if (body.records.size > config.maxBatchRecords) throw Refused(413, "too_large")
        val sizes = body.records.map { record ->
            if (!record.valid()) throw Refused(400, "bad_request")
            sealedSize(record.sealed) ?: throw Refused(400, "bad_request")
        }
        val tooLarge = body.records.zip(sizes).filter { (_, size) -> size > config.maxRecordBytes }.map { it.first }.toSet()
        val now = clock()
        val outcome = store.push(
            account = keyed.account.id,
            records = body.records,
            refuse = { record, generation ->
                when {
                    record in tooLarge -> "too_large"
                    record.gen != generation -> "generation"
                    record.stamp.millis > now + AHEAD_MILLIS -> "ahead"
                    else -> null
                }
            },
            maxAccountBytes = config.maxAccountBytes,
            maxAccountRecords = config.maxAccountRecords,
            now = now,
        )
        when (outcome) {
            is PushOutcome.Done -> call.respond(200, PushAnswer(outcome.taken, outcome.refused, now))
            PushOutcome.AccountFull -> throw Refused(413, "too_large")
            PushOutcome.NoAccount -> throw Refused(404, "no_account")
        }
    }

    /** The decoded size, or [Int.MAX_VALUE] for one too long to bother decoding; null when it does not read. */
    private fun sealedSize(sealed: String): Int? =
        if (sealed.length.toLong() * 3 / 4 > config.maxRecordBytes + 3L) Int.MAX_VALUE else Codec.sealedSize(sealed)

    private fun pull(call: Call) {
        val keyed = keyed(call)
        val since = call.query("since")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw Refused(400, "bad_request") } ?: 0
        val limit = call.query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw Refused(400, "bad_request") }
            ?.coerceAtMost(MAX_PULL) ?: DEFAULT_PULL
        val (rows, more) = store.pull(keyed.account.id, since, limit)
        val cursor = rows.lastOrNull()?.first ?: since
        call.respond(200, PullAnswer(store.replica, rows.map { it.second }, cursor, more, resetNotice(keyed.account.id)))
    }

    private fun forget(call: Call) {
        val keyed = keyed(call)
        val body = call.body<Forget>()
        if (body.ids.size > config.maxBatchRecords) throw Refused(413, "too_large")
        if (!body.ids.all(Codec::isRecordId)) throw Refused(400, "bad_request")
        store.forget(keyed.account.id, body.ids)
        call.respondEmpty()
    }

    // Joining

    private fun askToJoin(call: Call) {
        val licensee = licensee(call)
        val account = accountNow(licensee.account) ?: throw Refused(404, "no_account")
        val asker = licensee.asker(call)
        val body = call.body<JoinAsk>()
        val reads = Codec.isDeviceId(body.device) && body.platform.validPlatform() &&
            Codec.isHash(body.commitment) && Codec.isHash(body.secretHash)
        if (!reads) throw Refused(400, "bad_request")
        val listed = store.device(account.id, body.device)
        if (listed?.removed == true) throw Refused(403, "removed")
        if (listed != null) throw Refused(409, "exists")
        if (store.activeDevices(account.id) >= config.maxDevices) throw Refused(409, "full")
        val now = clock()
        val open = store.joins(account.id).filter { stateOf(it, now) in OPEN_STATES }
        // Per asker too, so one token cannot take the account's whole room for requests.
        if (open.size >= MAX_OPEN_JOINS || open.count { it.asker == asker } >= MAX_OPEN_JOINS_PER_ASKER) {
            throw Refused(429, "rate_limited", JOIN_LAPSE_MILLIS / 1000)
        }
        val secret = Codec.randomBase64Url(32)
        val join = Join(
            id = Codec.randomHex(16),
            account = account.id,
            device = body.device,
            platform = body.platform,
            commitment = body.commitment,
            publicKey = null,
            signingKey = null,
            nonce = null,
            deviceSecretHash = body.secretHash,
            joinSecretHash = secretHash(secret),
            state = "waiting",
            created = now,
            openedBy = null,
            openerKey = null,
            openerNonce = null,
            wrap = null,
            addedDevice = false,
            asker = asker,
        )
        store.createJoin(join)
        call.respond(200, JoinMade(join.id, secret))
    }

    private fun joins(call: Call) {
        val keyed = keyed(call)
        val now = clock()
        val open = store.joins(keyed.account.id)
            .map { it to stateOf(it, now) }
            .filter { (_, state) -> state in OPEN_STATES }
            .map { (join, state) ->
                JoinView(join.id, join.device, join.platform, join.commitment, join.publicKey, join.signingKey, join.nonce, state, join.created, join.openedBy)
            }
        call.respond(200, JoinsAnswer(open))
    }

    private fun openJoin(call: Call, id: String) {
        val keyed = keyed(call)
        val join = accountJoin(keyed.account.id, id)
        val body = call.body<JoinKey>()
        if (!body.valid()) throw Refused(400, "bad_request")
        if (stateOf(join, clock()) != "waiting" || !store.openJoin(join.id, keyed.device, body)) {
            throw Refused(409, "state")
        }
        call.respondEmpty()
    }

    private fun revealJoin(call: Call, id: String) {
        val join = secretJoin(call, id)
        val body = call.body<JoinReveal>()
        if (!body.valid()) throw Refused(400, "bad_request")
        if (stateOf(join, clock()) != "opened") throw Refused(409, "state")
        if (!Codec.sameHex(Codec.commitmentOf(body.publicKey, body.signingKey, body.nonce), join.commitment)) throw Refused(400, "bad_request")
        if (!store.revealJoin(join.id, body)) throw Refused(409, "state")
        call.respondEmpty()
    }

    private fun allowJoin(call: Call, id: String) {
        val keyed = keyed(call)
        val join = accountJoin(keyed.account.id, id)
        val body = call.body<JoinAllow>()
        if (!body.wrap.valid(MAX_JOIN_WRAP_CHARS)) throw Refused(400, "bad_request")
        // The new device opens a wrap only from the key its code was read from, so only the opener can allow.
        val fromOpener = join.openedBy == keyed.device && join.openerKey == body.wrap.from
        if (stateOf(join, clock()) != "revealed" || !fromOpener) throw Refused(409, "state")
        when (store.allowJoin(join, body.wrap, installOf(join.asker), config.maxDevices, clock())) {
            Store.Allowing.Done -> call.respondEmpty()
            Store.Allowing.NotOpen -> throw Refused(409, "state")
            Store.Allowing.Full -> throw Refused(409, "full")
            Store.Allowing.Exists -> throw Refused(409, "exists")
        }
    }

    private fun refuseJoin(call: Call, id: String) {
        val bySecret = call.header(JOIN_SECRET) != null
        val join = if (bySecret) secretJoin(call, id) else accountJoin(keyed(call).account.id, id)
        // The join secret outlives the join by a day; once the device it added made a keyed request, only a keyed removal takes it off.
        when (store.refuseJoin(join.id, untilSeen = bySecret)) {
            Store.Refusing.Done -> call.respondEmpty()
            Store.Refusing.NotOpen -> throw Refused(409, "state")
            Store.Refusing.Taken -> throw Refused(409, "taken")
        }
    }

    private fun joinState(call: Call, id: String) {
        val join = secretJoin(call, id)
        val state = stateOf(join, clock())
        val opened = state in setOf("opened", "revealed", "allowed")
        val wrap = if (state == "allowed") store.readJoinWrap(join.id) else null
        // Refused between the two reads: the refuse landed first, and no key goes.
        val shown = if (state == "allowed" && wrap == null) "refused" else state
        call.respond(200, JoinState(shown, join.openerKey?.takeIf { opened }, join.openerNonce?.takeIf { opened }, wrap))
    }

    private fun accountJoin(account: String, id: String): Join =
        store.join(id)?.takeIf { it.account == account } ?: throw Refused(404, "not_found")

    /** A join read with its secret; where the server checks a licence, the join's account must be the licence's. */
    private fun secretJoin(call: Call, id: String): Join {
        val account = if (licensing != null) licensed(call) else null
        val secret = call.header(JOIN_SECRET) ?: throw Refused(401, "unauthorized")
        val join = store.join(id)?.takeIf { account == null || it.account == account } ?: throw Refused(404, "not_found")
        if (!Codec.sameHex(secretHash(secret), join.joinSecretHash)) throw Refused(401, "unauthorized")
        return join
    }

    private fun stateOf(join: Join, now: Long): String =
        if (join.state in OPEN_STATES && now >= join.created + JOIN_LAPSE_MILLIS) "lapsed" else join.state

    private fun secretHash(secret: String) = Codec.hex(Codec.sha256(secret.toByteArray()))

    private fun NewDevice.valid() =
        Codec.isDeviceId(id) && platform.validPlatform() && Codec.isPublicKey(publicKey) && Codec.isPublicKey(signingKey) && Codec.isHash(secretHash)

    private fun JoinKey.valid() = Codec.isPublicKey(publicKey) && Codec.isNonce(nonce)

    private fun JoinReveal.valid() = Codec.isPublicKey(publicKey) && Codec.isPublicKey(signingKey) && Codec.isNonce(nonce)

    private fun String.validPlatform() = isNotBlank() && length <= 32

    private fun Wrap.valid(maxChars: Int) =
        Codec.isPublicKey(from) && sealed.length <= maxChars && Codec.sealedSize(sealed) != null

    private fun WireRecord.valid() =
        Codec.isRecordId(id) && gen >= 1 && Codec.isDeviceId(stamp.device) &&
            stamp.millis >= 0 && stamp.counter >= 0 && (deleted || !kept)

    companion object {
        const val PRODUCT = "yonto"

        /** The revision of `contracts/sync-v1.md` this server speaks (*Revisions*). */
        const val REVISION = 2
        const val REVISION_HEADER = "Yonto-Sync-Revision"
        const val AUTH_SCHEME = "Yonto-Sync "
        const val JOIN_SECRET = "Yonto-Join-Secret"
        const val AHEAD_MILLIS = 5 * 60 * 1000L
        const val TOMBSTONE_MILLIS = 90L * 24 * 60 * 60 * 1000
        const val JOIN_LAPSE_MILLIS = 10 * 60 * 1000L
        const val JOIN_KEPT_MILLIS = 24 * 60 * 60 * 1000L
        const val SWEEP_EVERY_MILLIS = 60 * 60 * 1000L
        const val DEFAULT_PULL = 500
        const val MAX_PULL = 1000
        const val MAX_OPEN_JOINS = 10
        const val MAX_OPEN_JOINS_PER_ASKER = 2
        const val RESET_SECRET = "Yonto-Reset-Secret"
        const val RESET_WAIT_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val RESET_KEPT_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val RESET_CHECK_MILLIS = 10_000L
        const val RESET_ASKS_PER_DAY = 3
        const val RESET_RETRY_SECONDS = 60L
        const val MAX_WRAP_CHARS = 4096

        // A join's wrap carries the account's signed device records too; a Start over's never does.
        const val MAX_JOIN_WRAP_CHARS = 256 * 1024
        private const val INSTALL_ASKER = "install:"
        const val MAX_CONNECTIONS = 2000

        /** Far more than a household syncing every device at once behind one address, or a carrier's shared one. */
        const val MAX_IN_FLIGHT_PER_CLIENT = 64
        const val BUSY_RETRY_SECONDS = 5L
        val OPEN_STATES = setOf("waiting", "opened", "revealed")

        /**
         * The JDK's server reads these once, when the first one is made; one already set in `JAVA_OPTS` stands.
         * 30 seconds is as long as the apps wait for an answer anyway, and [MAX_CONNECTIONS] slow clients fit the
         * container's memory (`sync-server/README.md`, *Put it behind TLS*).
         */
        private fun limitConnections() {
            mapOf(
                "sun.net.httpserver.maxReqTime" to "30",
                "sun.net.httpserver.maxRspTime" to "30",
                "jdk.httpserver.maxConnections" to MAX_CONNECTIONS.toString(),
            ).forEach { (name, value) -> if (System.getProperty(name) == null) System.setProperty(name, value) }
        }

        /** [path] with a device's or a join's id left out, since the log keeps no id. */
        fun loggedPath(path: String): String {
            val parts = path.split('/')
            return parts.mapIndexed { i, part -> if (i == 3 && parts[2] in setOf("devices", "joins")) "{id}" else part }
                .joinToString("/")
        }
    }
}

private class Call(val exchange: HttpExchange, private val config: Config, private val bodies: BodyBudget) {
    val method: String = exchange.requestMethod

    val address: String = run {
        val forwarded = exchange.requestHeaders.getFirst("X-Forwarded-For")
        if (config.behindProxy && forwarded != null) {
            forwarded.split(',').last().trim()
        } else {
            exchange.remoteAddress.address.hostAddress
        }
    }

    /** Whom [address] counts as for the per-client limits (`clientOf`). */
    val client: String = clientOf(address)

    fun header(name: String): String? = exchange.requestHeaders.getFirst(name)

    val hasBody: Boolean get() = (header("Content-Length")?.toLongOrNull() ?: 0) > 0 || header("Transfer-Encoding") != null

    /**
     * Answers 429 and drops the connection without reading or draining what the client still sends, since a client
     * over its share would otherwise hold the connection until the time limit; the JDK's server closes a connection
     * whose handler throws.
     */
    fun dropBusy(): Nothing {
        exchange.responseHeaders.set("Connection", "close")
        refuse(Refused(429, "rate_limited", SyncServer.BUSY_RETRY_SECONDS))
        exchange.responseBody.flush()
        throw IOException("Over the client's requests in flight")
    }

    fun query(name: String): String? = exchange.requestURI.rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == name }
        ?.let { URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }

    inline fun <reified T> body(): T = body(serializer<T>())

    fun <T> body(serializer: KSerializer<T>): T {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0
        if (declared > config.maxBodyBytes) throw Refused(413, "too_large")
        return bodies.read(exchange.requestBody, config.maxBodyBytes, client) { bytes ->
            try {
                WireJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw Refused(400, "bad_request")
            } catch (e: IllegalArgumentException) {
                throw Refused(400, "bad_request")
            }
        }
    }

    inline fun <reified T> respond(status: Int, body: T) = respond(status, serializer<T>(), body)

    fun <T> respond(status: Int, serializer: KSerializer<T>, body: T) {
        val bytes = WireJson.encodeToString(serializer, body).toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    fun respondEmpty(status: Int = 204) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, -1)
    }

    fun refuse(refused: Refused) {
        refused.retryAfterSeconds?.let { exchange.responseHeaders.set("Retry-After", it.toString()) }
        respond(refused.status, ErrorAnswer(refused.code))
    }
}
