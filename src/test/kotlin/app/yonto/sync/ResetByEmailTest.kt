package app.yonto.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reset on a server that checks the licence: the licensing service says whether it waits for an email or for days. */
class ResetByEmailTest {
    private val signer = Signer()
    private val pro = signer.token(ACCOUNT)
    private val licence = mapOf("Yonto-Licence" to pro)
    private val fresh = Key(9)

    private fun licensed(test: (Harness, FakeLicensing) -> Unit) = FakeLicensing().use { licensing ->
        Harness({ it.copy(licensingUrl = licensing.url, licenceKeys = signer.publicKey) }).use { test(it, licensing) }
    }

    private fun Harness.ask(token: String = pro): Answer = request(
        "POST", "/v1/account/reset",
        buildJsonObject {
            put("verifier", fresh.verifier)
            put("generation", 1)
            put("device", buildJsonObject { put("id", "c0ffee000001"); put("platform", "ios"); put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("secretHash", SECRET_B.verifier) })
        },
        mapOf("Yonto-Account" to ACCOUNT, "Yonto-Licence" to token),
    )

    private fun Harness.poll(secret: String, token: String = pro): Answer =
        request("GET", "/v1/account/reset", headers = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Licence" to token, "Yonto-Reset-Secret" to secret))

    private val Answer.secret get() = json.string("secret")!!

    private fun Harness.newDevice() = mapOf(
        "Yonto-Account" to ACCOUNT, "Yonto-Device" to "c0ffee000001", "Authorization" to "Yonto-Sync ${fresh.auth}",
        "Yonto-Device-Secret" to SECRET_B.auth,
    ) + licence

    @Test
    fun `a licence with an email waits for the owner's confirmation, then the account is the asker's`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        h.push(record(), headers = h.headers() + licence)
        val asked = h.ask()
        assertEquals("email", asked.json.string("confirm"))
        val code = asked.json.string("code")!!
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertNull(asked.json.string("after"))
        val sent = WireJson.parseToJsonElement(licensing.resetAsks.single()).jsonObject
        assertEquals(asked.json.string("reset"), sent.string("reset"))
        assertEquals(code, sent.string("code"))
        assertEquals("ios", sent.string("platform"))
        assertEquals("install-d", sent.string("install_id"), "the token's install id is passed on")

        assertEquals("emailed", h.poll(asked.secret).json.string("state"))
        assertEquals(200, h.pull(headers = h.headers() + licence).status, "the device that syncs is not refused yet")
        h.now += 10_000
        licensing.confirmed = true
        assertEquals("done", h.poll(asked.secret).json.string("state"))
        assertEquals("install-d", WireJson.parseToJsonElement(licensing.statusBodies.last()).jsonObject.string("install_id"))

        assertEquals("unauthorized", h.pull(headers = h.headers() + licence).error, "the old key opens nothing")
        val pulled = h.pull(headers = h.newDevice()).json
        assertTrue(pulled["records"]!!.jsonArray.isEmpty())
        assertEquals(1, h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT) + licence).json["generation"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a device holding the key cannot cancel a reset waiting for the email, and every device that syncs is told`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        val asked = h.ask()
        h.push(record(), headers = h.headers() + licence)
        val notice = h.pull(headers = h.headers() + licence).json["reset"]!!.jsonObject
        assertEquals("emailed", notice.string("state"))
        assertEquals("ios", notice.string("platform"))
        assertEquals(START, notice.long("asked"))
        h.now += 6 * 24 * 60 * 60 * 1000L
        licensing.confirmed = true
        assertEquals("done", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `the store cancels only a reset waiting out its days`() = licensed { h, _ ->
        h.createAccount(extra = licence)
        h.ask()
        h.server.store.cancelWaitingReset(ACCOUNT, h.now)
        assertEquals(Reset.EMAILED, h.server.store.reset(ACCOUNT)!!.state)
    }

    @Test
    fun `the service is asked at most every ten seconds`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        val asked = h.ask()
        repeat(5) { h.poll(asked.secret) }
        assertEquals(1, licensing.statusChecks.get())
        h.now += 9_999
        h.poll(asked.secret)
        assertEquals(1, licensing.statusChecks.get())
        h.now += 1
        h.poll(asked.secret)
        assertEquals(2, licensing.statusChecks.get())
    }

    @Test
    fun `a link that lapsed ends the reset`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        val asked = h.ask()
        licensing.confirmed = null
        assertEquals("lapsed", h.poll(asked.secret).json.string("state"))
        assertEquals(200, h.pull(headers = h.headers() + licence).status)
    }

    @Test
    fun `a confirmation applies only the reset it was for`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        val first = h.ask()
        val phone = signer.token(ACCOUNT, install = "phone")
        val second = h.ask(phone)
        assertEquals("not_found", h.poll(first.secret).error, "a reset asked again replaces the one waiting")
        licensing.confirmed = true
        assertEquals("done", h.poll(second.secret, token = phone).json.string("state"))
        assertEquals(
            WireJson.parseToJsonElement(licensing.resetAsks.last()).jsonObject.string("reset"),
            second.json.string("reset"),
        )
    }

    @Test
    fun `a licence with no email waits seven days, and a device holding the key cancels it`() = licensed { h, licensing ->
        licensing.email = false
        h.createAccount(extra = licence)
        val asked = h.ask()
        assertEquals("wait", asked.json.string("confirm"))
        assertNull(asked.json.string("code"), "no email shows a code")
        assertEquals(START + 7 * 24 * 60 * 60 * 1000L, asked.json.long("after"))
        h.now += 60_000
        val pulled = h.pull(headers = h.headers() + licence).json["reset"]!!.jsonObject
        assertEquals("cancelled", pulled.string("state"))
        assertEquals("cancelled", h.poll(asked.secret).json.string("state"))
        assertEquals(0, licensing.statusChecks.get(), "a waiting reset never asks about an email")
    }

    @Test
    fun `the service down or refusing is never taken as a wait`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        licensing.reset = FakeLicensing.Answer.DOWN
        assertEquals(503 to "unavailable", h.ask().let { it.status to it.error })
        licensing.reset = FakeLicensing.Answer.NO
        assertEquals(403 to "not_allowed", h.ask().let { it.status to it.error })
        licensing.reset = FakeLicensing.Answer.BUSY
        val busy = h.ask()
        assertEquals("rate_limited", busy.error)
        assertEquals("23", busy.header("Retry-After"))
        assertNull(h.server.store.reset(ACCOUNT), "nothing waits")
        h.now += 8 * 24 * 60 * 60 * 1000L
        h.server.sweep()
        assertEquals(200, h.pull(headers = h.headers() + licence).status)
    }

    @Test
    fun `one install asks at most three resets a day`() = licensed { h, _ ->
        h.createAccount(extra = licence)
        repeat(3) { assertEquals(200, h.ask().status) }
        val fourth = h.ask()
        assertEquals("rate_limited", fourth.error)
        assertEquals(200, h.ask(signer.token(ACCOUNT, install = "phone")).status, "another install has its own")
        h.now += 24 * 60 * 60 * 1000L
        assertEquals(200, h.ask().status)
    }

    @Test
    fun `only the install that asked reads its reset, so no other install can have it made`() = licensed { h, licensing ->
        h.createAccount(extra = licence)
        val asked = h.ask()
        licensing.confirmed = true
        val other = signer.token(ACCOUNT, install = "phone")
        assertEquals("not_found", h.poll(asked.secret, token = other).error)
        val withdraw = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Licence" to other, "Yonto-Reset-Secret" to asked.secret)
        assertEquals("not_found", h.request("DELETE", "/v1/account/reset", headers = withdraw).error)
        assertEquals(0, licensing.statusChecks.get(), "the service is never asked for another install")
        assertEquals(200, h.pull(headers = h.headers() + licence).status, "nothing was made")
        assertEquals("done", h.poll(asked.secret).json.string("state"))
    }

    @Test
    fun `a reset's secret is what reads it`() = licensed { h, _ ->
        h.createAccount(extra = licence)
        h.ask()
        assertEquals("not_found", h.poll("not-the-secret").error)
        val unread = h.request("GET", "/v1/account/reset", headers = mapOf("Yonto-Account" to ACCOUNT) + licence)
        assertEquals("unauthorized", unread.error)
    }
}
