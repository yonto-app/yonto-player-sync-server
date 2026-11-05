package app.yonto.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun sealedOf(fill: Int, bytes: Int = 40): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { fill.toByte() })

private const val FIVE_MINUTES = 5 * 60 * 1000L
private const val DAY = 24 * 60 * 60 * 1000L

class RecordsTest {
    private fun Harness.standing(): JsonObject = pulled().single()

    private fun Harness.refusals(answer: Answer): Map<String, String> =
        answer.json["refused"]!!.jsonArray.associate { it.jsonObject.string("id")!! to it.jsonObject.string("reason")!! }

    @Test
    fun `the greater stamp stands, by millis, then counter, then device`() = Harness().use { h ->
        h.createAccount()
        h.push(record(millis = START - 1000, sealed = sealedOf(1)))
        h.push(record(millis = START, sealed = sealedOf(2)))
        assertEquals(sealedOf(2), h.standing().string("sealed"))
        h.push(record(millis = START, counter = 1, sealed = sealedOf(3)))
        assertEquals(sealedOf(3), h.standing().string("sealed"))
        h.push(record(millis = START, counter = 1, device = "ffffffffffff", sealed = sealedOf(4)))
        assertEquals(sealedOf(4), h.standing().string("sealed"))
    }

    @Test
    fun `a copy that loses is still taken, and the one standing is unchanged`() = Harness().use { h ->
        h.createAccount()
        h.push(record(millis = START, counter = 2, sealed = sealedOf(1)))
        val cursor = h.pull().json.long("cursor")
        for (older in listOf(record(millis = START - 1, sealed = sealedOf(2)), record(millis = START, counter = 1, sealed = sealedOf(3)))) {
            assertEquals(listOf(older.id), h.push(older).json.strings("taken"))
        }
        assertEquals(sealedOf(1), h.standing().string("sealed"))
        assertTrue(h.pulled(since = cursor).isEmpty())
    }

    @Test
    fun `under one stamp the tombstone stands, whichever arrives first`() = Harness().use { h ->
        h.createAccount()
        h.push(record(sealed = sealedOf(1)))
        h.push(record(deleted = true, sealed = sealedOf(2)))
        assertEquals(true, h.standing().flag("deleted"))
        h.push(record(sealed = sealedOf(3)))
        assertEquals(sealedOf(2), h.standing().string("sealed"))

        h.push(record(id = idOf(9), deleted = true, sealed = sealedOf(4)))
        h.push(record(id = idOf(9), sealed = sealedOf(5)))
        assertEquals(sealedOf(4), h.pulled().single { it.id == idOf(9) }.string("sealed"))
    }

    @Test
    fun `an edit newer than a tombstone stands`() = Harness().use { h ->
        h.createAccount()
        h.push(record(deleted = true, sealed = sealedOf(1)))
        h.push(record(millis = START + 1, sealed = sealedOf(2)))
        assertEquals(false, h.standing().flag("deleted"))
    }

    @Test
    fun `a stamp more than five minutes ahead of the server's clock is refused`() = Harness().use { h ->
        h.createAccount()
        val atLimit = record(id = idOf(1), millis = START + FIVE_MINUTES)
        val past = record(id = idOf(2), millis = START + FIVE_MINUTES + 1)
        val answer = h.push(atLimit, past)
        assertEquals(listOf(idOf(1)), answer.json.strings("taken"))
        assertEquals(mapOf(idOf(2) to "ahead"), h.refusals(answer))

        h.now += 1
        assertEquals(listOf(idOf(2)), h.push(past).json.strings("taken"))
        assertEquals(START + FIVE_MINUTES + 1, h.pulled().single { it.id == idOf(2) }.millis)
    }

    @Test
    fun `a record past the size limit is refused alone, and a batch or an account past theirs is refused whole`() {
        var accountCap = 350L
        Harness({ it.copy(maxRecordBytes = 100, maxBatchRecords = 3, maxAccountBytes = accountCap) }).use { h ->
            h.createAccount()
            val answer = h.push(record(id = idOf(1), sealed = sealedOf(1, 100)), record(id = idOf(2), sealed = sealedOf(1, 101)))
            assertEquals(listOf(idOf(1)), answer.json.strings("taken"))
            assertEquals(mapOf(idOf(2) to "too_large"), h.refusals(answer))

            val batch = h.push(*(10..13).map { record(id = idOf(it)) }.toTypedArray())
            assertEquals(413, batch.status)
            assertEquals("too_large", batch.error)

            assertEquals(200, h.push(record(id = idOf(3), sealed = sealedOf(1, 100)), record(id = idOf(4), sealed = sealedOf(1, 100))).status)
            val full = h.push(record(id = idOf(5), sealed = sealedOf(1, 100)))
            assertEquals("too_large", full.error)
            assertEquals(setOf(idOf(1), idOf(3), idOf(4)), h.pulled().map { it.id }.toSet())

            accountCap = 200
            h.restart()
            val shrinking = h.push(record(id = idOf(3), millis = START + 1, deleted = true, sealed = sealedOf(1, 28)))
            assertEquals(200, shrinking.status, "an account past its cap may still shrink")
            assertEquals("too_large", h.push(record(id = idOf(6), sealed = sealedOf(1, 28))).error)
        }
    }

    @Test
    fun `a record that does not read is a bad request`() = Harness().use { h ->
        h.createAccount()
        assertEquals(400, h.push(record(id = "not-hex")).status)
        assertEquals(400, h.push(record(device = "XYZ")).status)
        assertEquals(400, h.push(record(sealed = "not base64!")).status)
        assertEquals(400, h.push(record(sealed = sealedOf(1, 27))).status)
        assertEquals(400, h.push(record(kept = true)).status)
        assertEquals(400, h.push(record(gen = 0)).status)
        assertTrue(h.pulled().isEmpty())
    }

    @Test
    fun `a pull pages by cursor in the order records were taken, the newest copy of each`() = Harness().use { h ->
        h.createAccount()
        (1..5).forEach { h.push(record(id = idOf(it), sealed = sealedOf(it))) }
        h.push(record(id = idOf(2), millis = START + 1, sealed = sealedOf(20)))

        val first = h.pull(limit = 2).json
        assertEquals(listOf(idOf(1), idOf(3)), first["records"]!!.jsonArray.map { it.jsonObject.id })
        assertEquals(true, first["more"]!!.jsonPrimitive.content.toBoolean())
        val second = h.pull(since = first.long("cursor"), limit = 2).json
        assertEquals(listOf(idOf(4), idOf(5)), second["records"]!!.jsonArray.map { it.jsonObject.id })
        assertEquals(true, second["more"]!!.jsonPrimitive.content.toBoolean())
        val third = h.pull(since = second.long("cursor"), limit = 2).json
        assertEquals(listOf(idOf(2)), third["records"]!!.jsonArray.map { it.jsonObject.id })
        assertEquals(sealedOf(20), third["records"]!!.jsonArray.single().jsonObject.string("sealed"))
        assertEquals(false, third["more"]!!.jsonPrimitive.content.toBoolean())

        val done = h.pull(since = third.long("cursor")).json
        assertTrue(done["records"]!!.jsonArray.isEmpty())
        assertEquals(third.long("cursor"), done.long("cursor"))
        assertEquals(400, h.pull(since = -1).status)
        assertEquals(400, h.request("GET", "/v1/records?since=1.5", headers = h.headers()).status)
    }

    @Test
    fun `one account never sees another's records`() = Harness().use { h ->
        h.createAccount()
        h.createAccount(account = "lic_other")
        h.push(record(id = idOf(1)))
        h.push(record(id = idOf(2)), headers = h.headers(account = "lic_other"))
        assertEquals(listOf(idOf(1)), h.pulled().map { it.id })
        assertEquals(listOf(idOf(2)), h.pulled(headers = h.headers(account = "lic_other")).map { it.id })
    }

    @Test
    fun `forget drops records with no tombstone`() = Harness().use { h ->
        h.createAccount()
        h.push(record(id = idOf(1)), record(id = idOf(2)), record(id = idOf(3), deleted = true))
        val forgot = h.request(
            "POST", "/v1/forget",
            WireJson.parseToJsonElement("""{"ids":["${idOf(1)}","${idOf(3)}"]}"""),
            h.headers(),
        )
        assertEquals(204, forgot.status)
        assertEquals(listOf(idOf(2)), h.pulled().map { it.id })
    }

    @Test
    fun `a tombstone is pruned 90 days after the server took it, unless kept`() = Harness().use { h ->
        h.createAccount()
        val longAgo = START - 400 * DAY
        h.push(
            record(id = idOf(1), millis = longAgo, deleted = true),
            record(id = idOf(2), millis = longAgo, deleted = true, kept = true),
            record(id = idOf(3), millis = longAgo),
        )
        assertEquals(3, h.pulled().size)

        h.now = START + 90 * DAY - 1
        h.server.sweep()
        assertEquals(3, h.pulled().size)
        h.now = START + 90 * DAY + 1
        h.server.sweep()
        assertEquals(listOf(idOf(2), idOf(3)), h.pulled().map { it.id })
    }

    @Test
    fun `the replica id holds across a restart and is new for a new database`() {
        Harness().use { h ->
            h.createAccount()
            h.push(record())
            val replica = h.pull().json.string("replica")!!
            assertEquals(32, replica.length)
            h.restart()
            assertEquals(replica, h.pull().json.string("replica"))
            assertEquals(1, h.pulled().size)

            Harness().use { other ->
                other.createAccount()
                assertNotEquals(replica, other.pull().json.string("replica"))
            }
        }
    }
}
