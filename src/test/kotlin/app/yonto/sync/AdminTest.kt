package app.yonto.sync

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
        val code = Store(h.database.path).use { admin(listOf("delete-account", ACCOUNT), it, out::add) }

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
        val code = Store(h.database.path).use { admin(listOf("delete-account", ACCOUNT), it, out::add) }
        assertEquals(1, code)
        assertEquals(listOf("No such account."), out)
    }

    @Test
    fun `accounts lists every account's id`() = Harness().use { h ->
        h.createAccount()
        h.now += 1
        h.createAccount(account = OTHER, device = "aaaaaaaaaaaa")
        val out = mutableListOf<String>()
        assertEquals(0, Store(h.database.path).use { admin(listOf("accounts"), it, out::add) })
        assertEquals(listOf(ACCOUNT, OTHER), out)
    }

    @Test
    fun `new-replica gives a restored database a replica its devices have not seen, keeping what it holds`() = Harness().use { h ->
        h.createAccount()
        h.push(record())
        val restored = h.pull().json
        val out = mutableListOf<String>()

        val code = Store(h.database.path).use { admin(listOf("new-replica"), it, out::add) }
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
    fun `anything else prints the usage`() = Harness().use { h ->
        val out = mutableListOf<String>()
        assertEquals(2, Store(h.database.path).use { admin(listOf("delete-account"), it, out::add) })
        assertEquals(1, out.size)
    }

    private fun Harness.rows(table: String, account: String): Int {
        val column = if (table == "account") "id" else "account"
        return DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use { statement ->
                statement.setString(1, account)
                statement.executeQuery().use { it.next(); it.getInt(1) }
            }
        }
    }

    private companion object {
        const val OTHER = "lic_other"
    }
}
