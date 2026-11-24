package app.yonto.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminTest {
    @Test
    fun `delete-account drops the account with its records, devices, wraps and joins`() = Harness().use { h ->
        h.createAccount()
        h.join()
        h.askToJoin(device = "0f1e2d3c4b5b")
        val next = Key(2)
        assertEquals(204, h.startOver(next, wrapsFor = listOf(DEVICE_B)).status)
        assertEquals(200, h.push(record(gen = 2), headers = h.headers(auth = next.auth)).status)
        h.createAccount(account = OTHER, device = "aaaaaaaaaaaa")
        val tables = listOf("record", "device", "wrap", "join_request")
        assertEquals(listOf(true, true, true, true), tables.map { h.rows(it, ACCOUNT) > 0 })

        val out = mutableListOf<String>()
        val code = Store(h.database).use { admin(listOf("delete-account", ACCOUNT), it, out::add) }

        assertEquals(0, code)
        assertEquals(listOf("Deleted the account, with its records, devices, wraps, joins and any reset waiting."), out)
        assertEquals(listOf(0, 0, 0, 0, 0), (tables + "account").map { h.rows(it, ACCOUNT) })
        assertEquals("no_account", h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).error)
        assertEquals(1, h.rows("account", OTHER))
        assertEquals(1, h.rows("device", OTHER))
    }

    @Test
    fun `delete-account says when there is no such account`() = Harness().use { h ->
        val out = mutableListOf<String>()
        val code = Store(h.database).use { admin(listOf("delete-account", ACCOUNT), it, out::add) }
        assertEquals(1, code)
        assertEquals(listOf("No such account."), out)
    }

    @Test
    fun `accounts lists every account's id`() = Harness().use { h ->
        h.createAccount()
        h.now += 1
        h.createAccount(account = OTHER, device = "aaaaaaaaaaaa")
        val out = mutableListOf<String>()
        assertEquals(0, Store(h.database).use { admin(listOf("accounts"), it, out::add) })
        assertEquals(listOf(ACCOUNT, OTHER), out)
    }

    @Test
    fun `new-replica gives a restored database a replica its devices have not seen, keeping what it holds`() = Harness().use { h ->
        h.createAccount()
        h.push(record())
        val restored = h.pull().json
        val out = mutableListOf<String>()

        val code = Store(h.database).use { admin(listOf("new-replica"), it, out::add) }
        assertEquals(restored.string("replica"), h.pull().json.string("replica"))
        h.restart()

        assertEquals(0, code)
        val replica = h.pull().json.string("replica")!!
        assertNotEquals(restored.string("replica"), replica)
        assertEquals(32, replica.length)
        assertEquals(listOf("Replica $replica: devices push what they hold again once the server starts on it."), out)
        assertEquals(listOf(record().id), h.pulled().map { it.id })
        h.push(record(id = idOf(2)))
        assertEquals(listOf(idOf(2)), h.pulled(since = restored.long("cursor")).map { it.id })
    }

    @Test
    fun `import-sqlite copies a SQLite server's accounts, devices, records and replica, and its devices carry on`() {
        val sqlite = Files.createTempDirectory("sync-server").toFile()
        val file = File(sqlite, "sync.db")
        // Only its server stops: closing the harness would delete the file being copied.
        val old = Harness(dir = sqlite, database = Database.Sqlite(file.path))
        old.createAccount()
        old.join()
        old.push(*Vectors.records.toTypedArray())
        old.createAccount(account = OTHER, device = "aaaaaaaaaaaa")
        // A row in every table, NULLs among them: a wrap waiting to be read, a join waiting, a reset waiting, an install.
        assertEquals(204, old.startOver(Key(3), wrapsFor = listOf("aaaaaaaaaaaa"), headers = old.headers(account = OTHER, device = "aaaaaaaaaaaa")).status)
        old.askToJoin(device = "0f1e2d3c4b5b")
        val reset = old.request(
            "POST", "/v1/account/reset",
            buildJsonObject {
                put("verifier", Key(4).verifier)
                put("generation", 1)
                put("device", buildJsonObject {
                    put("id", "c0ffee000001"); put("platform", "ios"); put("publicKey", Vectors.joiningKey)
                    put("signingKey", Vectors.signingKey); put("secretHash", SECRET_B.verifier)
                })
            },
            mapOf("Yonto-Account" to ACCOUNT),
        )
        assertEquals(200, reset.status, reset.text)
        old.server.store.proved(ACCOUNT, DEVICE_A, "install-a")
        val before = old.pull().json
        val devices = old.request("GET", "/v1/devices", headers = old.headers()).text
        assertTrue("install-a" in devices, devices)
        old.server.close()
        val tables = listOf("account", "device", "device_install", "record", "wrap", "join_request", "account_reset")
        val held = tables.associateWith { old.database.rowsOf(it) }
        assertEquals(tables, tables.filter { held.getValue(it).isNotEmpty() }, "every table has a row to copy")
        Harness().use { h ->
            val out = mutableListOf<String>()
            val code = Store(h.database).use { admin(listOf("import-sqlite", file.path), it, out::add) }
            h.restart()

            assertEquals(0, code)
            tables.forEach { assertEquals(held.getValue(it), h.database.rowsOf(it), "$it is copied as it was") }
            assertEquals(1, out.size)
            assertTrue(out.single().startsWith("Copied "), out.single())
            assertEquals(listOf(ACCOUNT, OTHER), Store(h.database).use { it.accountIds() })
            val after = h.pull().json
            assertEquals(before.string("replica"), after.string("replica"))
            assertEquals(before.long("cursor"), after.long("cursor"))
            assertEquals(Vectors.records.map { it.string("sealed") }, h.pulled().map { it.string("sealed") })
            assertEquals(emptyList(), h.pulled(since = before.long("cursor")), "a device that had pulled everything finds nothing new")
            assertEquals(devices, h.request("GET", "/v1/devices", headers = h.headers()).text)
            h.push(record(id = idOf(9)))
            assertEquals(listOf(idOf(9)), h.pulled(since = before.long("cursor")).map { it.id }, "the sequence goes on from the copy's")
        }
        sqlite.deleteRecursively()
    }

    @Test
    fun `import-sqlite refuses a database that holds accounts, and a file that isn't there`() = Harness().use { h ->
        h.createAccount()
        val sqlite = Files.createTempDirectory("sync-server").toFile()
        val file = File(sqlite, "sync.db")
        Harness(dir = sqlite, database = Database.Sqlite(file.path)).apply { createAccount(account = OTHER) }.server.close()
        val out = mutableListOf<String>()

        assertEquals(1, Store(h.database).use { admin(listOf("import-sqlite", file.path), it, out::add) })
        assertEquals(1, Store(h.database).use { admin(listOf("import-sqlite", File(sqlite, "nothing.db").path), it, out::add) })

        assertEquals(listOf("This database already holds accounts: import only into an empty one.", "No SQLite database at ${File(sqlite, "nothing.db").path}."), out)
        assertEquals(listOf(ACCOUNT), Store(h.database).use { it.accountIds() })
        assertFalse(File(sqlite, "nothing.db").exists())
        sqlite.deleteRecursively()
        Unit
    }

    @Test
    fun `anything else prints the usage`() = Harness().use { h ->
        val out = mutableListOf<String>()
        assertEquals(2, Store(h.database).use { admin(listOf("delete-account"), it, out::add) })
        assertEquals(1, out.size)
    }


    private companion object {
        const val OTHER = "lic_other"
    }

    /** Every row of [table], each column by name with its value as text, in an order that doesn't depend on the database. */
    private fun Database.rowsOf(table: String): List<Map<String, String?>> = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $table").use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).associate { rows.metaData.getColumnName(it).lowercase() to rows.getString(it) })
                }
            }
        }
    }.sortedBy { it.toSortedMap().toString() }
}
