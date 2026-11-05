package app.yonto.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartOverTest {
    private val next = Key(2)

    @Test
    fun `start over replaces the verifier and drops the older generation's records`() = Harness().use { h ->
        h.createAccount()
        h.push(*Vectors.records.toTypedArray())
        assertEquals(204, h.startOver(next).status)

        assertEquals(2, h.request("GET", "/v1/account", headers = mapOf("Yonto-Account" to ACCOUNT)).json["generation"]!!.jsonPrimitive.int)
        assertEquals("generation", h.pull().error)
        val pulled = h.pull(headers = h.headers(auth = next.auth))
        assertEquals(200, pulled.status)
        assertTrue(pulled.json["records"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a push sealed under the older generation is refused from then on`() = Harness().use { h ->
        h.createAccount()
        h.startOver(next)
        val headers = h.headers(auth = next.auth)
        val pushed = h.push(record(gen = 1), record(id = idOf(2), gen = 2), headers = headers).json
        assertEquals(listOf(idOf(2)), pushed.strings("taken"))
        val refused = pushed["refused"]!!.jsonArray.single().jsonObject
        assertEquals(Vectors.records[0].id, refused.string("id"))
        assertEquals("generation", refused.string("reason"))
        assertEquals(listOf(idOf(2)), h.pulled(headers = headers).map { it.id })
    }

    @Test
    fun `a device a generation behind is told so and takes its wrap`() = Harness().use { h ->
        h.createAccount()
        h.join()
        assertEquals(204, h.startOver(next, wrapsFor = listOf(DEVICE_B)).status)

        val old = h.headers(DEVICE_B)
        assertEquals("generation", h.pull(headers = old).error)
        val wrap = h.request("GET", "/v1/wrap", headers = old)
        assertEquals(200, wrap.status, wrap.text)
        assertEquals(Vectors.wrapWire, wrap.json["wrap"]!!.jsonObject)
        assertEquals(401, h.request("GET", "/v1/wrap", headers = old + ("Yonto-Device-Secret" to Vectors.secret)).status)
        assertEquals(204, h.request("DELETE", "/v1/wrap", headers = old).status)
        assertEquals("not_found", h.request("GET", "/v1/wrap", headers = old).error)
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B, auth = next.auth)).status)

        assertEquals(204, h.startOver(Key(3), generation = 3, headers = h.headers(auth = next.auth)).status)
        assertEquals("not_found", h.request("GET", "/v1/wrap", headers = old).error)
    }

    @Test
    fun `a device any number of generations behind reads its own wrap by its secret, and nothing more`() = Harness().use { h ->
        h.createAccount()
        h.join()
        h.startOver(next, wrapsFor = listOf(DEVICE_B))
        val third = Key(3)
        assertEquals(204, h.startOver(third, generation = 3, wrapsFor = listOf(DEVICE_B), headers = h.headers(auth = next.auth)).status)

        val old = h.headers(DEVICE_B)
        assertEquals("unauthorized", h.pull(headers = old).error)
        val wrap = h.request("GET", "/v1/wrap", headers = old)
        assertEquals(200, wrap.status, wrap.text)
        assertEquals(200, h.request("GET", "/v1/wrap", headers = h.headers(DEVICE_B, auth = null)).status)
        assertEquals("unauthorized", h.request("GET", "/v1/wrap", headers = old + ("Yonto-Device-Secret" to Vectors.secret)).error)
        assertEquals("unauthorized", h.request("GET", "/v1/wrap", headers = old - "Yonto-Device-Secret").error)
        assertEquals("unknown_device", h.request("GET", "/v1/wrap", headers = h.headers("aaaaaaaaaaaa")).error)
        assertEquals("unauthorized", h.request("DELETE", "/v1/wrap", headers = old).error)
        assertEquals(204, h.request("DELETE", "/v1/wrap", headers = h.headers(DEVICE_B, auth = third.auth)).status)
    }

    @Test
    fun `a Start over's wrap is held to the small cap, which only a join's may pass`() = Harness().use { h ->
        h.createAccount()
        h.join()
        fun startOverWith(chars: Int): Answer {
            val sealed = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(chars * 3 / 4) { it.toByte() })
            val body = buildJsonObject {
                put("generation", 2)
                put("verifier", next.verifier)
                put("wraps", JsonArray(listOf(buildJsonObject {
                    put("device", DEVICE_B)
                    put("wrap", buildJsonObject { put("from", Vectors.allowingKey); put("sealed", sealed) })
                })))
            }
            return h.request("POST", "/v1/account/generation", body, h.headers())
        }
        assertEquals("bad_request", startOverWith(8 * 1024).error)
        assertEquals(204, startOverWith(4 * 1024).status)
    }

    @Test
    fun `wraps are kept only for devices still on the list`() = Harness().use { h ->
        h.createAccount()
        h.join()
        h.request("POST", "/v1/devices/$DEVICE_B/remove", headers = h.headers())
        h.startOver(next, wrapsFor = listOf(DEVICE_A, DEVICE_B))
        assertEquals(200, h.request("GET", "/v1/wrap", headers = h.headers(auth = next.auth)).status)
        assertEquals("removed", h.request("GET", "/v1/wrap", headers = h.headers(DEVICE_B)).error)
    }

    @Test
    fun `a wrap for a device not on the list is not kept for it to find later`() = Harness().use { h ->
        h.createAccount()
        h.startOver(next, wrapsFor = listOf(DEVICE_B))
        h.join(headers = h.headers(auth = next.auth))
        assertEquals("not_found", h.request("GET", "/v1/wrap", headers = h.headers(DEVICE_B, auth = next.auth)).error)
    }

    @Test
    fun `a push sealed under a generation the account has not reached is refused`() = Harness().use { h ->
        h.createAccount()
        val refused = h.push(record(gen = 2)).json["refused"]!!.jsonArray.single().jsonObject
        assertEquals("generation", refused.string("reason"))
        assertTrue(h.pulled().isEmpty())
    }

    @Test
    fun `a generation other than the next one is refused`() = Harness().use { h ->
        h.createAccount()
        assertEquals("state", h.startOver(next, generation = 3).error)
        assertEquals("state", h.startOver(next, generation = 1).error)
        assertEquals(200, h.pull().status)
        assertEquals(204, h.startOver(next).status)
        assertEquals("generation", h.startOver(Key(3), generation = 2).error)
    }
}
