package app.yonto.sync

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun `the database holds ciphertext and hashes, never what the vectors seal or the keys`() {
        val h = Harness()
        try {
            h.createAccount()
            h.push(*Vectors.records.toTypedArray())
            h.join()
            h.server.close()
            val files = h.dir.listFiles()!!.filter { it.name.startsWith("sync.db") }
            val dump = files.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

            assertTrue(Vectors.records.all { dump.contains(it.string("sealed")!!) }, "the dump is the database the records went into")
            val sync = Vectors.json["sync_key_base64url"]!!.toString().trim('"')
            val derived = Vectors.json["derived"].toString()
            val never = listOf(
                "Big Buck Bunny", "3f9c2a7b1e04", "movie-42", "history", "positionMs", "payload",
                sync, Vectors.auth, Vectors.secret, SECRET_B.auth,
                Base64.getUrlDecoder().decode(sync).toString(Charsets.ISO_8859_1),
                Base64.getUrlDecoder().decode(Vectors.auth).toString(Charsets.ISO_8859_1),
                Base64.getUrlDecoder().decode(Vectors.secret).toString(Charsets.ISO_8859_1),
            ) + Regex("[0-9a-f]{64}").findAll(derived).map { it.value }.filter { it != Vectors.verifier }.toList()
            never.forEach { assertFalse(dump.contains(it), "the database holds $it") }
        } finally {
            h.dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed request is logged without a device's or a join's id`() {
        assertEquals("/v1/devices/{id}/remove", SyncServer.loggedPath("/v1/devices/d-7f3a/remove"))
        assertEquals("/v1/joins/{id}/allow", SyncServer.loggedPath("/v1/joins/j-91c2/allow"))
        assertEquals("/v1/joins/{id}", SyncServer.loggedPath("/v1/joins/j-91c2"))
        assertEquals("/v1/records", SyncServer.loggedPath("/v1/records"))
    }

    @Test
    fun `every answer names the revision, and a client naming another is refused before anything is done`() =
        Harness().use { h ->
            val made = h.createAccount(extra = mapOf("Yonto-Sync-Revision" to "2"))
            assertEquals(201, made.status)
            assertEquals("2", made.header("Yonto-Sync-Revision"))
            val unknown = h.request("GET", "/v1/nothing")
            assertEquals("not_found", unknown.error)
            assertEquals("2", unknown.header("Yonto-Sync-Revision"))

            listOf("1", "3", "two").forEach { other ->
                val refused = h.push(*Vectors.records.toTypedArray(), headers = h.headers() + ("Yonto-Sync-Revision" to other))
                assertEquals(409, refused.status)
                assertEquals("revision", refused.error)
                assertEquals("2", refused.header("Yonto-Sync-Revision"))
            }
            assertEquals(emptyList(), h.pulled())
            // One from before revisions named none: served.
            assertEquals(200, h.push(*Vectors.records.toTypedArray()).status)
        }

    @Test
    fun `an account past its rate is told when to ask again`() =
        Harness({ it.copy(accountRequestsPerMinute = 3) }).use { h ->
            h.createAccount()
            repeat(3) { assertEquals(200, h.pull().status) }
            val limited = h.pull()
            assertEquals(429, limited.status)
            assertEquals("rate_limited", limited.error)
            assertEquals("60", limited.header("Retry-After"))
            assertEquals("no_account", h.pull(headers = h.headers(account = "lic_other")).error)
            h.now += 60_000
            assertEquals(200, h.pull().status)
        }

    @Test
    fun `a stranger naming an account does not spend its rate`() =
        Harness({ it.copy(accountRequestsPerMinute = 3) }).use { h ->
            h.createAccount()
            repeat(10) {
                assertEquals("unauthorized", h.pull(headers = h.headers(auth = Key(1).auth)).error)
                h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT))
            }
            repeat(3) { assertEquals(200, h.pull().status) }
            assertEquals("rate_limited", h.pull().error)
        }

    @Test
    fun `a request of another revision still counts against its address before it is refused`() =
        Harness({ it.copy(addressRequestsPerMinute = 2) }).use { h ->
            val other = mapOf("Yonto-Sync-Revision" to "3")
            repeat(2) { assertEquals("revision", h.request("GET", "/v1/account", headers = other).error) }
            assertEquals("rate_limited", h.request("GET", "/v1/account", headers = other).error)
        }

    @Test
    fun `an address past its rate is refused whatever the account`() =
        Harness({ it.copy(addressRequestsPerMinute = 2) }).use { h ->
            h.createAccount()
            h.pull()
            assertEquals("rate_limited", h.pull(headers = h.headers(account = "lic_other")).error)
        }

    @Test
    fun `a body past the limit is too large`() = Harness({ it.copy(maxBodyBytes = 1000) }).use { h ->
        h.createAccount()
        assertEquals("too_large", h.push(*Vectors.records.toTypedArray(), *Vectors.records.toTypedArray()).error)
    }

    @Test
    fun `an unknown path is not found`() = Harness().use { h ->
        assertEquals(404, h.request("GET", "/v2/records").status)
        assertEquals(404, h.request("PATCH", "/v1/account").status)
    }

    @Test
    fun `the environment configures the server`() {
        val config = Config.fromEnvironment(
            mapOf(
                "PORT" to "9000",
                "SYNC_DB" to "/tmp/x.db",
                "SYNC_ACCOUNTS" to " lic_a, lic_b ,",
                "YONTO_LICENSING_URL" to "https://account.example/",
                "SYNC_MAX_RECORD_BYTES" to "10",
                "SYNC_BEHIND_PROXY" to "true",
            ),
        )
        assertEquals(9000, config.port)
        assertEquals("/tmp/x.db", config.database)
        assertEquals(setOf("lic_a", "lic_b"), config.accounts)
        assertEquals("https://account.example", config.licensingUrl)
        assertEquals(10, config.maxRecordBytes)
        assertTrue(config.behindProxy)
        val defaults = Config.fromEnvironment(emptyMap())
        assertEquals(Config(), defaults)
        assertEquals(null, defaults.accounts)
    }
}
