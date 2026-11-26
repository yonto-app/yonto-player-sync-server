package app.yonto.sync

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The record count and bytes an account keeps beside it, held to what its records add up to after every kind of change. */
class StoreTotalsTest {
    private val database = TestDatabases.fresh(Files.createTempDirectory("totals").toFile())
    private val store = Store(database)
    private var clock = 1_000L

    @AfterTest
    fun close() = store.close()

    private fun record(id: String, bytes: Int = 300, deleted: Boolean = false, gen: Int = 1) =
        WireRecord(id, gen, Stamp(clock++, 0, "dev1"), deleted, false, "A".repeat(bytes * 4 / 3))

    private fun push(vararg records: WireRecord, maxBytes: Long = Long.MAX_VALUE, maxRecords: Int = Int.MAX_VALUE, now: Long = 0) =
        store.push(ACCOUNT, records.toList(), { record, generation -> "generation".takeIf { record.gen != generation } }, maxBytes, maxRecords, now)

    // What the records the account holds add up to, read the way a device reads them.
    private fun counted(): Pair<Int, Long> = store.pull(ACCOUNT, 0, 100_000).first.map { it.second }.let { held ->
        held.size to held.sumOf { it.sealed.length * 3L / 4 }
    }

    private fun assertTotals(expectedCount: Int) {
        assertEquals(counted(), store.totals(ACCOUNT))
        assertEquals(expectedCount, store.totals(ACCOUNT).first)
    }

    init {
        store.createAccount(ACCOUNT, "v", 1, NewDevice("dev1", "tv", "pk", "sk", "sh"), null, 0)
    }

    @Test
    fun `new records, edits that win and lose, and one id twice in a push`() {
        push(record("a"), record("b", bytes = 600))
        assertTotals(2)

        push(record("a", bytes = 900), record("b", bytes = 30))
        assertTotals(2)

        val stale = WireRecord("a", 1, Stamp(1, 0, "dev1"), false, false, "A".repeat(4000))
        push(stale, record("c"), record("c", bytes = 1200))
        assertTotals(3)
    }

    @Test
    fun `pushes and forgets past one query's worth of ids`() {
        val ids = (0 until 1_300).map { "r$it" }
        push(*ids.map { record(it, bytes = 30) }.toTypedArray())
        push(*ids.map { record(it, bytes = 60) }.toTypedArray())
        assertTotals(1_300)

        store.forget(ACCOUNT, ids.take(1_200))

        assertTotals(100)
    }

    @Test
    fun `a refused record changes nothing`() {
        push(record("a"))
        push(record("b", gen = 2), record("c"), maxRecords = 1)

        assertTotals(1)
    }

    @Test
    fun `a push past the account's bytes is refused whole`() {
        push(record("a"))

        assertIs<PushOutcome.AccountFull>(push(record("b", bytes = 3000), maxBytes = 1000))
        assertTotals(1)
    }

    @Test
    fun `forgetting records takes them off, and an id it does not hold changes nothing`() {
        push(record("a"), record("b"), record("c"))

        store.forget(ACCOUNT, listOf("a", "c", "nope", "a"))

        assertTotals(1)
    }

    @Test
    fun `pruned tombstones are taken off their own account alone`() {
        store.createAccount("other", "v", 1, NewDevice("dev2", "tv", "pk", "sk", "sh"), null, 0)
        push(record("a"), record("b"), now = 0)
        push(record("a", deleted = true), now = 0)
        store.push("other", listOf(record("x", deleted = true)), { _, _ -> null }, Long.MAX_VALUE, Int.MAX_VALUE, 50)

        store.pruneTombstones(now = 100, keepMillis = 60)

        assertTotals(1)
        assertEquals(1 to store.pull("other", 0, 10).first.single().second.sealed.length * 3L / 4, store.totals("other"))
    }

    @Test
    fun `starting over drops the old generation's records from the totals`() {
        push(record("a"), record("b"))

        store.startOver(ACCOUNT, 1, StartOver(2, "v2"))
        store.push(ACCOUNT, listOf(record("c", gen = 2)), { _, _ -> null }, Long.MAX_VALUE, Int.MAX_VALUE, 0)

        assertTotals(1)
    }

    @Test
    fun `a reset made empties them`() {
        push(record("a"), record("b"))
        val reset = Reset(ACCOUNT, "r1", "sh", "v2", 2, NewDevice("dev9", "tv", "pk", "sk", "sh"), Reset.WAITING, "code", 0, 0)
        store.askReset(reset)

        store.applyReset(ACCOUNT, "r1", 10)

        assertTotals(0)
    }

    @Test
    fun `a database from before the totals counts them as it opens`() {
        push(record("a"), record("b", bytes = 690))
        store.close()
        database.connect().use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE account DROP COLUMN records")
                it.execute("ALTER TABLE account DROP COLUMN bytes")
                it.execute("UPDATE meta SET value = '1' WHERE key = 'schema'")
            }
        }

        Store(database).use { reopened ->
            assertEquals(2 to 990L, reopened.totals(ACCOUNT))
        }
    }

    private companion object {
        const val ACCOUNT = "acct"
    }
}
