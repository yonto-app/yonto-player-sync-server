package app.yonto.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinTest {
    private companion object {
        const val DEVICE_C = "c0c0c0c0c0c0"
    }

    private fun Harness.listed() =
        request("GET", "/v1/joins", headers = headers()).json["joins"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `a join goes waiting, opened, revealed, allowed, and adds the device with its secret`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.askToJoin()
        assertEquals("unknown_device", h.pull(headers = h.headers(DEVICE_B)).error)

        val waiting = h.listed().single()
        assertEquals(join, waiting.string("join"))
        assertEquals(DEVICE_B, waiting.string("device"))
        assertEquals("ios", waiting.string("platform"))
        assertEquals(Vectors.commitment, waiting.string("commitment"))
        assertNull(waiting.string("publicKey"))
        assertEquals("waiting", waiting.string("state"))
        assertEquals(START, waiting.long("created"))
        assertNull(waiting.string("openedBy"))
        assertEquals("waiting", h.joinState(join, secret).json.string("state"))
        assertNull(h.joinState(join, secret).json.string("publicKey"))
        assertNull(h.joinState(join, secret).json.string("nonce"))

        assertEquals(204, h.open(join).status)
        val opened = h.joinState(join, secret).json
        assertEquals("opened", opened.string("state"))
        assertEquals(Vectors.allowingKey, opened.string("publicKey"))
        assertEquals(Vectors.allowingNonce, opened.string("nonce"))
        assertEquals("opened", h.listed().single().string("state"))
        assertEquals(DEVICE_A, h.listed().single().string("openedBy"))
        assertNull(h.listed().single().string("publicKey"))
        assertNull(h.listed().single().string("nonce"))

        assertEquals(204, h.reveal(join, secret).status)
        val revealed = h.listed().single()
        assertEquals("revealed", revealed.string("state"))
        assertEquals(Vectors.joiningKey, revealed.string("publicKey"))
        assertEquals(Vectors.signingKey, revealed.string("signingKey"))
        assertEquals(Vectors.joiningNonce, revealed.string("nonce"))

        assertEquals(204, h.allow(join).status)
        val allowed = h.joinState(join, secret).json
        assertEquals("allowed", allowed.string("state"))
        assertEquals(Vectors.wrapWire, allowed["wrap"]!!.jsonObject)
        assertTrue(h.listed().isEmpty())

        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
        assertEquals("unauthorized", h.pull(headers = h.headers(DEVICE_B, secret = Vectors.secret)).error)
        val device = h.request("GET", "/v1/devices", headers = h.headers()).json["devices"]!!.jsonArray
            .single { it.jsonObject.string("id") == DEVICE_B }.jsonObject
        assertEquals(Vectors.joiningKey, device.string("publicKey"))
        assertEquals(Vectors.signingKey, device.string("signingKey"))
    }

    @Test
    fun `a reveal must match the commitment in its keys and its nonce`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.askToJoin()
        h.open(join)
        assertEquals("bad_request", h.reveal(join, secret, publicKey = Vectors.allowingKey).error)
        assertEquals("bad_request", h.reveal(join, secret, signingKey = Vectors.allowingKey).error)
        assertEquals("bad_request", h.reveal(join, secret, nonce = Vectors.allowingNonce).error)
        assertEquals("bad_request", h.reveal(join, secret, nonce = "short").error)
        assertEquals("opened", h.joinState(join, secret).json.string("state"))
    }

    @Test
    fun `a step out of order is refused, and open is taken once`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.askToJoin()
        assertEquals("state", h.reveal(join, secret).error)
        assertEquals("state", h.allow(join).error)
        assertEquals(204, h.open(join).status)
        assertEquals("state", h.open(join).error)
        assertEquals("state", h.allow(join).error)
        assertEquals(204, h.reveal(join, secret).status)
        assertEquals("state", h.reveal(join, secret).error)
    }

    @Test
    fun `only the device that opened a join allows it, with a wrap from the key it opened with`() = Harness().use { h ->
        h.createAccount()
        h.join()
        val third = "abcdefabcdef"
        val (join, secret) = h.askToJoin(device = third)
        h.open(join)
        h.reveal(join, secret)
        assertEquals("state", h.allow(join, headers = h.headers(DEVICE_B)).error)
        val wrongFrom = buildJsonObject {
            put("wrap", buildJsonObject { put("from", Vectors.joiningKey); put("sealed", Vectors.wrapWire.string("sealed")) })
        }
        assertEquals("state", h.request("POST", "/v1/joins/$join/allow", wrongFrom, h.headers()).error)
        assertEquals(204, h.allow(join).status)
    }

    @Test
    fun `a join's wrap has room for the account's device records, and no more`() = Harness().use { h ->
        h.createAccount()
        fun sealedOf(bytes: Int) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { it.toByte() })
        fun allowWith(sealed: String): Answer {
            val (join, secret) = h.askToJoin()
            h.open(join)
            h.reveal(join, secret)
            val body = buildJsonObject { put("wrap", buildJsonObject { put("from", Vectors.allowingKey); put("sealed", sealed) }) }
            return h.request("POST", "/v1/joins/$join/allow", body, h.headers())
        }
        assertEquals("bad_request", allowWith(sealedOf(200 * 1024)).error)
        assertEquals(204, allowWith(sealedOf(150 * 1024)).status)
    }

    @Test
    fun `the secret reads the request and nothing else does`() = Harness().use { h ->
        h.createAccount()
        val (join, _) = h.askToJoin()
        assertEquals("unauthorized", h.joinState(join, "wrong").error)
        assertEquals("unauthorized", h.request("GET", "/v1/joins/$join").error)
        assertEquals("not_found", h.joinState("0".repeat(32), "wrong").error)
        assertEquals("unauthorized", h.reveal(join, "wrong").error)
    }

    @Test
    fun `a request lapses ten minutes after it was made`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.askToJoin()
        h.open(join)
        h.now = START + 10 * 60 * 1000 - 1
        assertEquals("opened", h.joinState(join, secret).json.string("state"))
        h.now = START + 10 * 60 * 1000
        assertEquals("lapsed", h.joinState(join, secret).json.string("state"))
        assertTrue(h.listed().isEmpty())
        assertEquals("state", h.reveal(join, secret).error)
    }

    @Test
    fun `either device can refuse, and a refusal after allowing takes the device off the list`() = Harness().use { h ->
        h.createAccount()
        val (first, firstSecret) = h.askToJoin()
        assertEquals(204, h.request("POST", "/v1/joins/$first/refuse", headers = mapOf("Yonto-Join-Secret" to firstSecret)).status)
        assertEquals("refused", h.joinState(first, firstSecret).json.string("state"))
        assertEquals("state", h.open(first).error)

        val (second, secondSecret) = h.join()
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
        assertEquals(204, h.request("POST", "/v1/joins/$second/refuse", headers = h.headers()).status)
        assertEquals("refused", h.joinState(second, secondSecret).json.string("state"))
        assertNull(h.joinState(second, secondSecret).json.string("wrap"))
        assertEquals("unknown_device", h.pull(headers = h.headers(DEVICE_B)).error)
    }

    @Test
    fun `a refuse after the allow but before the new device read its wrap takes the wrap and the device back`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.join()
        assertEquals(204, h.request("POST", "/v1/joins/$join/refuse", headers = h.headers()).status)
        val state = h.joinState(join, secret).json
        assertEquals("refused", state.string("state"))
        assertNull(state["wrap"])
        assertEquals("unknown_device", h.pull(headers = h.headers(DEVICE_B)).error)
    }

    @Test
    fun `a refuse after the new device read its wrap is answered taken and changes nothing`() = Harness().use { h ->
        h.createAccount()
        val (join, secret) = h.join()
        assertEquals(Vectors.wrapWire, h.joinState(join, secret).json["wrap"]!!.jsonObject)
        assertEquals("taken", h.request("POST", "/v1/joins/$join/refuse", headers = h.headers()).error)
        assertEquals("allowed", h.joinState(join, secret).json.string("state"))
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
    }

    @Test
    fun `the new device's secret refuses its allowed join only until the device it added made a keyed request`() = Harness().use { h ->
        h.createAccount()
        val (first, firstSecret) = h.join()
        assertEquals(204, h.request("POST", "/v1/joins/$first/refuse", headers = mapOf("Yonto-Join-Secret" to firstSecret)).status)
        assertEquals("unknown_device", h.pull(headers = h.headers(DEVICE_B)).error)

        val (second, secondSecret) = h.join()
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
        assertEquals("state", h.request("POST", "/v1/joins/$second/refuse", headers = mapOf("Yonto-Join-Secret" to secondSecret)).error)
        assertEquals("allowed", h.joinState(second, secondSecret).json.string("state"))
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
    }

    @Test
    fun `a removed device cannot ask again, and an unknown account has nothing to join`() = Harness().use { h ->
        h.createAccount()
        h.join()
        h.request("POST", "/v1/devices/$DEVICE_B/remove", headers = h.headers())
        val again = h.request(
            "POST", "/v1/joins",
            buildJsonObject {
                put("device", DEVICE_B); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier)
            },
            mapOf("Yonto-Account" to ACCOUNT),
        )
        assertEquals("removed", again.error)
        val elsewhere = h.request(
            "POST", "/v1/joins",
            buildJsonObject {
                put("device", DEVICE_B); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier)
            },
            mapOf("Yonto-Account" to "lic_nobody"),
        )
        assertEquals("no_account", elsewhere.error)
    }

    @Test
    fun `a join never takes over a device on the list, asked for it or added before the allow`() = Harness().use { h ->
        h.createAccount()
        val ask = buildJsonObject {
            put("device", DEVICE_A); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier)
        }
        val asked = h.request("POST", "/v1/joins", ask, mapOf("Yonto-Account" to ACCOUNT))
        assertEquals(409 to "exists", asked.status to asked.error)

        val (join, secret) = h.askToJoin()
        assertEquals(204, h.open(join).status)
        assertEquals(204, h.reveal(join, secret).status)
        val added = h.request(
            "POST", "/v1/devices",
            buildJsonObject { put("id", DEVICE_B); put("platform", "macos"); put("publicKey", Vectors.allowingKey); put("signingKey", Vectors.joiningKey); put("secretHash", Vectors.secretHash) },
            h.headers() - "Yonto-Device-Secret",
        )
        assertEquals(201, added.status)
        val allowed = h.allow(join)
        assertEquals(409 to "exists", allowed.status to allowed.error)
        assertEquals("revealed", h.joinState(join, secret).json.string("state"))
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B, secret = Vectors.secret)).status)
        assertEquals("unauthorized", h.pull(headers = h.headers(DEVICE_B)).error)
    }

    @Test
    fun `a device a join added that never syncs within a day leaves the list, and one that does stays`() =
        Harness({ it.copy(maxDevices = 3) }).use { h ->
            h.createAccount()
            h.join()
            val (join, secret) = h.askToJoin(device = DEVICE_C)
            assertEquals(204, h.open(join).status)
            assertEquals(204, h.reveal(join, secret).status)
            assertEquals(204, h.allow(join).status)
            assertEquals(200, h.pull(headers = h.headers(DEVICE_B)).status)
            assertEquals("full", h.request("POST", "/v1/joins", ask("aaaaaaaaaaaa"), mapOf("Yonto-Account" to ACCOUNT)).error)

            h.now += SyncServer.JOIN_KEPT_MILLIS
            h.server.sweep()
            assertEquals(setOf(DEVICE_A, DEVICE_B, DEVICE_C), h.deviceIds())

            h.now += 1
            h.server.sweep()
            assertEquals(setOf(DEVICE_A, DEVICE_B), h.deviceIds())
            assertEquals(200, h.request("POST", "/v1/joins", ask("aaaaaaaaaaaa"), mapOf("Yonto-Account" to ACCOUNT)).status)
        }

    private fun ask(device: String) = buildJsonObject {
        put("device", device); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier)
    }

    private fun Harness.deviceIds() =
        request("GET", "/v1/devices", headers = headers()).json["devices"]!!.jsonArray.map { it.jsonObject.string("id") }.toSet()

    @Test
    fun `more than ten open requests are too many until one ends`() = Harness({ it.copy(behindProxy = true) }).use { h ->
        h.createAccount()
        // Each from a client of its own, since one client holds at most two.
        fun from(n: Int) = mapOf("X-Forwarded-For" to "203.0.113.$n")
        val joins = (1..10).map { h.askToJoin(device = "%012x".format(it), extra = from(it)) }
        val eleventh = h.request(
            "POST", "/v1/joins",
            buildJsonObject {
                put("device", "%012x".format(11)); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier)
            },
            mapOf("Yonto-Account" to ACCOUNT) + from(11),
        )
        assertEquals(429 to "rate_limited", eleventh.status to eleventh.error)
        assertEquals("600", eleventh.header("Retry-After"))

        val (first, secret) = joins.first()
        h.request("POST", "/v1/joins/$first/refuse", headers = mapOf("Yonto-Join-Secret" to secret))
        val (join, joinSecret) = h.askToJoin(device = "%012x".format(11), extra = from(11))
        assertEquals("waiting", h.joinState(join, joinSecret).json.string("state"))
    }

    @Test
    fun `another account's join is not found`() = Harness().use { h ->
        h.createAccount()
        h.createAccount(account = "lic_other")
        val (join, _) = h.askToJoin()
        assertEquals("not_found", h.open(join, headers = h.headers(account = "lic_other")).error)
    }
}
