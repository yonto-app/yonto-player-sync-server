package app.yonto.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    @Test
    fun `a database from before devices signed their records takes their signing keys`() {
        val dir = java.nio.file.Files.createTempDirectory("sync-server").toFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${java.io.File(dir, "sync.db").path}").use { old ->
            old.createStatement().use {
                it.execute("CREATE TABLE account (id TEXT PRIMARY KEY, generation INTEGER NOT NULL, verifier TEXT NOT NULL, previous_verifier TEXT, created INTEGER NOT NULL)")
                it.execute(
                    """CREATE TABLE device (account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE, id TEXT NOT NULL,
                       platform TEXT NOT NULL, public_key TEXT NOT NULL, secret_hash TEXT NOT NULL, removed INTEGER NOT NULL DEFAULT 0,
                       added INTEGER NOT NULL, unseen INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (account, id))""",
                )
                it.execute(
                    """CREATE TABLE join_request (id TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                       device TEXT NOT NULL, platform TEXT NOT NULL, commitment TEXT NOT NULL, public_key TEXT, nonce TEXT,
                       device_secret_hash TEXT NOT NULL, join_secret_hash TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL,
                       opened_by TEXT, opener_key TEXT, opener_nonce TEXT, wrap_sender TEXT, wrap_sealed TEXT, added_device INTEGER NOT NULL DEFAULT 0)""",
                )
            }
        }
        Harness(dir = dir).use { h ->
            assertEquals(201, h.createAccount().status)
            h.join()
            val devices = h.request("GET", "/v1/devices", headers = h.headers()).json["devices"]!!.jsonArray.map { it.jsonObject }
            assertEquals<Map<String?, String?>>(mapOf(DEVICE_A to Vectors.joiningKey, DEVICE_B to Vectors.signingKey), devices.associate { it.string("id") to it.string("signingKey") })
        }
    }

    @Test
    fun `an account is made once, with its first device`() = Harness().use { h ->
        assertEquals("no_account", h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).error)
        val made = h.createAccount()
        assertEquals(201, made.status)
        assertEquals("", made.text)
        val again = h.createAccount(verifier = Key(1).verifier)
        assertEquals(409, again.status)
        assertEquals("exists", again.error)

        val account = h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT))
        assertEquals(1, account.json["generation"]!!.jsonPrimitive.int)
        val devices = h.request("GET", "/v1/devices", headers = h.headers()).json["devices"]!!.jsonArray
        assertEquals(1, devices.size)
        val device = devices[0].jsonObject
        assertEquals(DEVICE_A, device.string("id"))
        assertEquals("android-tv", device.string("platform"))
        assertEquals(Vectors.allowingKey, device.string("publicKey"))
        assertEquals(false, device.flag("removed"))
        assertEquals(START, device.long("added"))
    }

    @Test
    fun `a keyed request is refused for each thing it lacks`() = Harness().use { h ->
        assertEquals("no_account", h.pull().error)
        h.createAccount()
        assertEquals(200, h.pull().status)
        assertEquals("unauthorized", h.pull(headers = h.headers(auth = null)).error)
        assertEquals("unauthorized", h.pull(headers = h.headers(auth = Key(1).auth)).error)
        assertEquals("unauthorized", h.pull(headers = h.headers(secret = null)).error)
        assertEquals("unauthorized", h.pull(headers = h.headers(secret = SECRET_B.auth)).error)
        assertEquals("unknown_device", h.pull(headers = h.headers(device = "000000000000")).error)
        assertEquals(400, h.pull(headers = h.headers(device = "not-a-device")).status)
        assertEquals(400, h.pull(headers = h.headers() - "Yonto-Account").status)
        val wrongScheme = h.headers() + ("Authorization" to "Bearer ${Vectors.auth}")
        assertEquals("unauthorized", h.pull(headers = wrongScheme).error)
    }

    @Test
    fun `a server that checks no licence lists no install`() = Harness().use { h ->
        h.createAccount()
        h.join()
        val listed = h.request("GET", "/v1/devices", headers = h.headers()).json["devices"]!!.jsonArray.map { it.jsonObject }
        assertTrue(listed.all { it["installs"]!!.jsonArray.isEmpty() })
    }

    @Test
    fun `a removed device is refused under every generation`() = Harness().use { h ->
        h.createAccount()
        h.join()
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
        assertEquals(204, h.request("POST", "/v1/devices/$DEVICE_B/remove", headers = h.headers()).status)
        assertEquals("removed", h.pull(headers = h.headers(DEVICE_B)).error)
        val listed = h.request("GET", "/v1/devices", headers = h.headers()).json["devices"]!!.jsonArray
        assertTrue(listed.single { it.jsonObject.string("id") == DEVICE_B }.jsonObject.flag("removed"))

        val next = Key(2)
        assertEquals(204, h.startOver(next).status)
        assertEquals("removed", h.pull(headers = h.headers(DEVICE_B, auth = next.auth)).error)
        assertEquals(404, h.request("POST", "/v1/devices/000000000000/remove", headers = h.headers(auth = next.auth)).status)
    }

    @Test
    fun `start fresh drops the account and everything under it`() = Harness().use { h ->
        h.createAccount()
        h.push(*Vectors.records.toTypedArray())
        h.askToJoin()
        assertEquals(204, h.request("DELETE", "/v1/account", headers = h.headers()).status)
        assertEquals("no_account", h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).error)

        val fresh = Key(3)
        assertEquals(201, h.createAccount(verifier = fresh.verifier).status)
        val pulled = h.pull(headers = h.headers(auth = fresh.auth)).json
        assertTrue(pulled["records"]!!.jsonArray.isEmpty())
        assertTrue(h.request("GET", "/v1/joins", headers = h.headers(auth = fresh.auth)).json["joins"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `start fresh without the key is refused where no licence is checked`() = Harness().use { h ->
        h.createAccount()
        assertEquals("unauthorized", h.request("DELETE", "/v1/account", headers = h.headers(auth = null)).error)
        assertEquals(200, h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).status)
    }

    @Test
    fun `an allowlist decides who may make an account`() = Harness({ it.copy(accounts = setOf("lic_other")) }).use { h ->
        assertEquals("not_allowed", h.createAccount().error)
        assertEquals(201, h.createAccount(account = "lic_other").status)
    }

    @Test
    fun `an account that does not read is refused`() = Harness().use { h ->
        assertEquals(400, h.createAccount(verifier = "short").status)
        assertEquals(400, h.createAccount(publicKey = "AAAA").status)
        assertEquals(400, h.createAccount(secretHash = "").status)
        assertEquals(400, h.request("PUT", "/v1/account", body = null, headers = mapOf("Yonto-Account" to ACCOUNT)).status)
        assertEquals("no_account", h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).error)
    }
}
