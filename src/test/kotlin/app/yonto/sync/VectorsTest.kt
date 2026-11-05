package app.yonto.sync

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorsTest {
    @Test
    fun `the verifier of the vectors' auth is their verifier`() {
        assertEquals(Vectors.verifier, Codec.hashOf(Base64.getUrlDecoder().decode(Vectors.auth)))
    }

    @Test
    fun `a device secret's hash is the vectors'`() {
        assertEquals(Vectors.secretHash, Codec.hashOf(Base64.getUrlDecoder().decode(Vectors.secret)))
    }

    @Test
    fun `the joining keys and nonce's commitment is the vectors'`() {
        assertEquals(Vectors.commitment, Codec.commitmentOf(Vectors.joiningKey, Vectors.signingKey, Vectors.joiningNonce))
    }

    @Test
    fun `the vectors' records are pulled back exactly as pushed`() = Harness().use { h ->
        assertEquals(201, h.createAccount().status)
        val pushed = h.push(*Vectors.records.toTypedArray())
        assertEquals(200, pushed.status, pushed.text)
        assertEquals(Vectors.records.map { it.id }, pushed.json.strings("taken"))
        assertTrue(pushed.json["refused"]!!.jsonArray.isEmpty())
        assertEquals(START, pushed.json.long("now"))

        assertEquals(Vectors.records, h.pulled())
    }

    @Test
    fun `the vectors' wire records parse the way the server reads them`() {
        Vectors.records.forEach { wire ->
            val record = WireJson.decodeFromJsonElement(WireRecord.serializer(), wire)
            assertEquals(wire, WireJson.encodeToJsonElement(WireRecord.serializer(), record).jsonObject)
        }
    }
}
