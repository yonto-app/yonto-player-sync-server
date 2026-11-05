package app.yonto.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DEVICE_C = "abcdefabcdef"

class CapsTest {
    private fun Harness.refusals(answer: Answer): Map<String, String> =
        answer.json["refused"]!!.jsonArray.associate { it.jsonObject.string("id")!! to it.jsonObject.string("reason")!! }

    private fun Harness.addDevice(
        id: String = DEVICE_C,
        auth: String = Vectors.auth,
        secretHash: String = Key(9).verifier,
    ) = request(
        "POST", "/v1/devices",
        buildJsonObject { put("id", id); put("platform", "macos"); put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("secretHash", secretHash) },
        mapOf("Yonto-Account" to ACCOUNT, "Authorization" to "Yonto-Sync $auth"),
    )

    @Test
    fun `an account full of records takes what fits, and a record it holds is never refused for it`() =
        Harness({ it.copy(maxAccountRecords = 3) }).use { h ->
            h.createAccount()
            val first = h.push(record(id = idOf(1)), record(id = idOf(2)), record(id = idOf(3), deleted = true), record(id = idOf(4)))
            assertEquals(listOf(idOf(1), idOf(2), idOf(3)), first.json.strings("taken"))
            assertEquals(mapOf(idOf(4) to "full"), h.refusals(first))

            val replacing = h.push(record(id = idOf(1), millis = START + 1), record(id = idOf(1), millis = START + 2), record(id = idOf(5)))
            assertEquals(listOf(idOf(1), idOf(1)), replacing.json.strings("taken"))
            assertEquals(mapOf(idOf(5) to "full"), h.refusals(replacing))

            h.request("POST", "/v1/forget", WireJson.parseToJsonElement("""{"ids":["${idOf(2)}"]}"""), h.headers())
            assertEquals(listOf(idOf(5)), h.push(record(id = idOf(5))).json.strings("taken"))
            assertEquals(setOf(idOf(1), idOf(3), idOf(5)), h.pulled().map { it.id }.toSet())
        }

    @Test
    fun `an account at its device cap refuses a join early and at allow, and a removed device does not count`() =
        Harness({ it.copy(maxDevices = 2) }).use { h ->
            h.createAccount()
            val (pending, pendingSecret) = h.askToJoin(device = DEVICE_C)
            h.join()
            val asking = h.request(
                "POST", "/v1/joins",
                buildJsonObject { put("device", "123456123456"); put("platform", "ios"); put("commitment", Vectors.commitment); put("secretHash", SECRET_B.verifier) },
                mapOf("Yonto-Account" to ACCOUNT),
            )
            assertEquals(409 to "full", asking.status to asking.error)

            assertEquals(204, h.open(pending).status)
            assertEquals(204, h.reveal(pending, pendingSecret).status)
            assertEquals(409 to "full", h.allow(pending).let { it.status to it.error })

            h.request("POST", "/v1/devices/$DEVICE_B/remove", headers = h.headers())
            assertEquals(204, h.allow(pending).status)
        }

    @Test
    fun `a device with the key adds itself, once, and then syncs with its own secret`() = Harness().use { h ->
        h.createAccount()
        assertEquals(201, h.addDevice().status)
        assertEquals(200, h.pull(headers = h.headers(DEVICE_C, secret = Key(9).auth)).status)
        assertEquals("unauthorized", h.pull(headers = h.headers(DEVICE_C)).error)
        assertEquals(409 to "exists", h.addDevice().let { it.status to it.error })
        assertEquals(409 to "exists", h.addDevice(id = DEVICE_A).let { it.status to it.error })
        assertEquals(200, h.pull(headers = h.headers(DEVICE_C, secret = Key(9).auth)).status)

        h.request("POST", "/v1/devices/$DEVICE_C/remove", headers = h.headers())
        assertEquals(403 to "removed", h.addDevice().let { it.status to it.error })
    }

    @Test
    fun `adding a device needs the current key and room on the list`() = Harness({ it.copy(maxDevices = 2) }).use { h ->
        h.createAccount()
        assertEquals("unauthorized", h.addDevice(auth = Key(1).auth).error)
        assertEquals(400, h.addDevice(secretHash = "nope").status)
        assertEquals(201, h.addDevice().status)
        assertEquals(409 to "full", h.addDevice(id = "123456123456").let { it.status to it.error })

        val next = Key(2)
        h.startOver(next)
        assertEquals("generation", h.addDevice(id = "654321654321").error)
        assertEquals(2, h.request("GET", "/v1/devices", headers = h.headers(auth = next.auth)).json["devices"]!!.jsonArray.size)
    }
}
