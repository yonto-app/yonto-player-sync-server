package app.yonto.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reset on a server that checks no licence: it always waits seven days, which any request proving the key cancels. */
class ResetTest {
    private val fresh = Key(9)
    private val week = 7 * 24 * 60 * 60 * 1000L

    private fun Harness.ask(device: String = "c0ffee000001", from: String? = null): Answer = request(
        "POST", "/v1/account/reset",
        buildJsonObject {
            put("verifier", fresh.verifier)
            put("generation", 1)
            put("device", buildJsonObject { put("id", device); put("platform", "ios"); put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("secretHash", SECRET_B.verifier) })
        },
        mapOf("Yonto-Account" to ACCOUNT, "X-Forwarded-For" to from),
    )

    private fun Harness.poll(secret: String): Answer =
        request("GET", "/v1/account/reset", headers = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Reset-Secret" to secret))

    private val Answer.secret get() = json.string("secret")!!

    private val newDevice = mapOf(
        "Yonto-Account" to ACCOUNT, "Yonto-Device" to "c0ffee000001", "Authorization" to "Yonto-Sync ${fresh.auth}",
        "Yonto-Device-Secret" to SECRET_B.auth,
    )

    @Test
    fun `seven days with no device holding the key, and the account is the asker's`() = Harness().use { h ->
        h.createAccount()
        h.push(*Vectors.records.toTypedArray())
        h.join()
        h.askToJoin(device = "0f1e2d3c4b5f")
        val asked = h.ask()
        assertEquals(200, asked.status, asked.text)
        assertEquals("wait", asked.json.string("confirm"))
        assertEquals(START + week, asked.json.long("after"))
        assertNull(asked.json.string("code"))
        assertEquals("waiting", h.poll(asked.secret).json.string("state"))
        assertEquals("unauthorized", h.pull(headers = newDevice).error, "nothing happens before the week is out")

        h.now += week - 1
        assertEquals("waiting", h.poll(asked.secret).json.string("state"))
        h.now += 1
        assertEquals("done", h.poll(asked.secret).json.string("state"))
        assertTrue(h.pull(headers = newDevice).json["records"]!!.jsonArray.isEmpty())
        assertEquals("unauthorized", h.pull().error)
        assertEquals("unauthorized", h.pull(headers = h.headers(DEVICE_B)).error)
        val devices = h.request("GET", "/v1/devices", headers = newDevice).json["devices"]!!.jsonArray
        assertEquals(listOf("c0ffee000001"), devices.map { it.jsonObject.string("id") })
        assertEquals(Vectors.signingKey, devices.single().jsonObject.string("signingKey"), "the new account's first device keeps its signing key")
        assertTrue(h.server.store.joins(ACCOUNT).isEmpty(), "every join, open or not, goes with the old account")
    }

    @Test
    fun `a request with the old key after the week finds the reset made`() = Harness().use { h ->
        h.createAccount()
        h.ask()
        h.now += week
        assertEquals("unauthorized", h.pull().error)
        assertEquals(200, h.pull(headers = newDevice).status)
    }

    @Test
    fun `the hourly sweep makes a reset that fell due`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        h.now += week
        h.server.sweep()
        assertEquals(Reset.DONE, h.server.store.reset(ACCOUNT)!!.state)
        assertEquals("done", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `any request that proves the key cancels it, and devices that sync are told for a week`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        h.now += week - 1
        val pulled = h.pull().json
        val notice = pulled["reset"]!!.jsonObject
        assertEquals("cancelled", notice.string("state"))
        assertEquals("ios", notice.string("platform"))
        assertEquals(START, notice.long("asked"))
        assertEquals("cancelled", h.poll(asked.secret).json.string("state"))
        h.now += 1
        assertEquals(200, h.pull().status, "the old account stands")
        assertEquals("unauthorized", h.pull(headers = newDevice).error)

        h.now += week - 2
        assertEquals("cancelled", h.pull().json["reset"]!!.jsonObject.string("state"))
        h.now += 1
        assertNull(h.pull().json["reset"], "told for a week, then no more")
    }

    @Test
    fun `adding a device with the key cancels it`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        val add = h.request(
            "POST", "/v1/devices",
            buildJsonObject { put("id", DEVICE_B); put("platform", "ios"); put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("secretHash", SECRET_B.verifier) },
            h.headers(DEVICE_B, secret = null),
        )
        assertEquals(201, add.status)
        assertEquals("cancelled", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `taking a wrap under the previous key cancels it`() = Harness().use { h ->
        h.createAccount()
        h.join()
        val next = Key(2)
        assertEquals(204, h.startOver(next, wrapsFor = listOf(DEVICE_B), headers = h.headers()).let { it.status })
        val asked = h.ask()
        assertEquals(200, h.request("GET", "/v1/wrap", headers = h.headers(DEVICE_B)).status)
        assertEquals("cancelled", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `a request without the key cancels nothing`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        assertEquals("unauthorized", h.pull(headers = h.headers(auth = Key(4).auth)).error)
        h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT))
        h.askToJoin()
        assertEquals("waiting", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `asking again replaces the reset waiting and starts its wait over`() = Harness().use { h ->
        h.createAccount()
        val first = h.ask()
        h.now += week - 1
        val second = h.ask(device = "c0ffee000002")
        assertEquals("not_found", h.poll(first.secret).error)
        h.now += 1
        assertEquals("waiting", h.poll(second.secret).json.string("state"))
    }

    @Test
    fun `the asker may withdraw it`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        val withdraw = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Reset-Secret" to asked.secret)
        assertEquals("not_found", h.request("DELETE", "/v1/account/reset", headers = withdraw + ("Yonto-Reset-Secret" to "nope")).error)
        assertEquals(204, h.request("DELETE", "/v1/account/reset", headers = withdraw).status)
        h.now += week
        assertEquals(200, h.pull().status)
        assertEquals("not_found", h.poll(asked.secret).error)
    }

    @Test
    fun `a reset needs an account to replace and a body that reads`() = Harness().use { h ->
        assertEquals("no_account", h.ask().error)
        h.createAccount()
        val bad = h.request("POST", "/v1/account/reset", buildJsonObject { put("verifier", "short") }, mapOf("Yonto-Account" to ACCOUNT))
        assertEquals(400, bad.status)
    }

    @Test
    fun `one client asks at most three resets a day`() = Harness({ it.copy(behindProxy = true) }).use { h ->
        h.createAccount()
        repeat(3) { assertEquals(200, h.ask(from = "203.0.113.5").status) }
        assertEquals("rate_limited", h.ask(from = "203.0.113.5").error)
        assertEquals(200, h.ask(from = "203.0.113.6").status)
    }

    @Test
    fun `one client holds at most two open join requests`() = Harness({ it.copy(behindProxy = true) }).use { h ->
        h.createAccount()
        val from = mapOf("X-Forwarded-For" to "203.0.113.5")
        h.askToJoin(device = "0f1e2d3c4b5a", extra = from)
        h.askToJoin(device = "0f1e2d3c4b5b", extra = from)
        val third = h.request(
            "POST", "/v1/joins",
            buildJsonObject { put("device", "0f1e2d3c4b5c"); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier) },
            mapOf("Yonto-Account" to ACCOUNT) + from,
        )
        assertEquals("rate_limited", third.error)
        val other = h.askToJoin(device = "0f1e2d3c4b5c", extra = mapOf("X-Forwarded-For" to "203.0.113.6"))
        assertEquals("waiting", h.joinState(other.first, other.second).json.string("state"))
    }

    @Test
    fun `a reset is made only under its own id, and only while open`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        assertEquals(false, h.server.store.applyReset(ACCOUNT, "0".repeat(32), h.now))
        assertEquals("waiting", h.poll(asked.secret).json.string("state"))
        val id = asked.json.string("reset")!!
        h.pull()
        assertEquals(false, h.server.store.applyReset(ACCOUNT, id, h.now), "a cancelled reset is never made")
        assertEquals(200, h.pull().status)
    }

    @Test
    fun `a reset asked just as the one waiting falls due waits its own days`() = Harness().use { h ->
        h.createAccount()
        h.ask()
        h.now += week
        val second = Reset(
            ACCOUNT, "f".repeat(32), "unused", Key(11).verifier, 1, NewDevice("c0ffee000009", "ios", Vectors.joiningKey, Vectors.signingKey, SECRET_B.verifier),
            Reset.WAITING, "000000", asked = h.now, after = h.now + week,
        )
        var asked = false
        // Asked between the server seeing the first reset fall due and making it.
        h.onClock = {
            if (!asked) {
                asked = true
                h.server.store.askReset(second)
            }
        }
        assertEquals(200, h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).status)
        h.onClock = {}
        assertTrue(asked)
        val held = h.server.store.reset(ACCOUNT)!!
        assertEquals(second.id to Reset.WAITING, held.id to held.state, "the second reset was not made in the first's place")
        assertEquals(200, h.request("GET", "/v1/devices", headers = h.headers()).status, "the account stands")
    }

    @Test
    fun `a second request that saw the same reset due doesn't make it again over what the new device pushed`() = Harness().use { h ->
        h.createAccount()
        h.ask()
        h.now += week
        // Two requests both saw the reset fall due; the first makes it, and the new device pushes before the second gets in.
        assertEquals(true, h.server.store.makeDueReset(ACCOUNT, h.now))
        assertEquals(200, h.push(record(device = "c0ffee000001"), headers = newDevice).status)
        assertEquals(false, h.server.store.makeDueReset(ACCOUNT, h.now))
        assertEquals(1, h.pulled(headers = newDevice).size, "what the new device pushed is still there")
    }

    @Test
    fun `a database from before resets knew who asked takes the column`() {
        val dir = java.nio.file.Files.createTempDirectory("sync-server").toFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${java.io.File(dir, "sync.db").path}").use { old ->
            old.createStatement().use {
                it.execute(
                    """CREATE TABLE account_reset (
                        account TEXT PRIMARY KEY, id TEXT NOT NULL, secret_hash TEXT NOT NULL, verifier TEXT NOT NULL,
                        generation INTEGER NOT NULL, device TEXT NOT NULL, platform TEXT NOT NULL, public_key TEXT NOT NULL,
                        device_secret_hash TEXT NOT NULL, state TEXT NOT NULL, code TEXT NOT NULL, asked INTEGER NOT NULL,
                        after INTEGER, ended INTEGER, checked INTEGER)""",
                )
            }
        }
        Harness(dir = dir).use { h ->
            h.createAccount()
            val asked = h.ask()
            assertEquals("waiting", h.poll(asked.secret).json.string("state"))
        }
    }

    @Test
    fun `a reset that ended can't be withdrawn, and stays to be read`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        h.pull()
        val withdraw = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Reset-Secret" to asked.secret)
        assertEquals(409 to "state", h.request("DELETE", "/v1/account/reset", headers = withdraw).let { it.status to it.error })
        assertEquals("cancelled", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `dropping the account takes a waiting reset with it`() = Harness().use { h ->
        h.createAccount()
        val asked = h.ask()
        assertEquals(204, h.request("DELETE", "/v1/account", headers = h.headers()).status)
        assertEquals("not_found", h.poll(asked.secret).error)
    }
}
