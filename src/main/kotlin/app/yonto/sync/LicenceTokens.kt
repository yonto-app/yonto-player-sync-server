package app.yonto.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Reads a Yonto licence token, `<payload>.<signature>` in base64url, offline: ECDSA P-256 over the payload
 * segment's bytes, DER-encoded strictly, against each of the product's public keys, as the apps read it.
 * A server has no install id, so the token's install is not checked;
 * the licensing service's own check is what ties a token to a live device.
 */
class LicenceTokens(private val product: String, private val keys: List<PublicKey>) {
    /** A token's licence and the install it was issued to (the hash of its install id). */
    data class Token(val licence: String, val install: String)

    /** The licence and install the token was signed for, or null when it is not a token for this product. */
    fun read(token: String): Token? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val (payload, encodedSignature) = parts
        val claimsBytes = canonical(payload) ?: return null
        val signature = canonical(encodedSignature)?.let(::derToP1363) ?: return null
        val signed = payload.toByteArray(Charsets.US_ASCII)
        if (keys.none { verifies(it, signed, signature) }) return null
        val claims = runCatching { WireJson.decodeFromString(Claims.serializer(), claimsBytes.toString(Charsets.UTF_8)) }
            .getOrNull() ?: return null
        if (claims.version != VERSION || claims.product != product || claims.licence.isEmpty()) return null
        return Token(claims.licence, claims.device)
    }

    private fun verifies(key: PublicKey, signed: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update(signed)
            verify(signature)
        }
    }.getOrDefault(false)

    @Serializable
    private data class Claims(
        @SerialName("v") val version: Int,
        @SerialName("prd") val product: String,
        @SerialName("lic") val licence: String,
        @SerialName("dev") val device: String,
    )

    companion object {
        const val VERSION = 1

        private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        /** Keys as the apps take them: uncompressed points in base64, comma-separated. */
        fun parseKeys(text: String): List<PublicKey> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val point = runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                ?: Codec.base64Url(it)
                ?: error("A licence key is not base64")
            require(point.size == 65 && point[0] == 4.toByte()) { "A licence key is not an uncompressed P-256 point" }
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256))
        }

        /** Only the one canonical spelling: no padding, no stray bits. */
        private fun canonical(text: String): ByteArray? {
            val bytes = Codec.base64Url(text) ?: return null
            return bytes.takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it) == text }
        }

        /** `SEQUENCE { INTEGER r, INTEGER s }` in DER, with nothing after it, as the 64 bytes r ‖ s. */
        private fun derToP1363(der: ByteArray): ByteArray? {
            if (der.size < 8 || der[0] != 0x30.toByte()) return null
            val length = der[1].toInt() and 0xff
            if (length >= 0x80 || length != der.size - 2) return null
            var at = 2
            fun integer(): ByteArray? {
                if (at + 2 > der.size || der[at] != 0x02.toByte()) return null
                val size = der[at + 1].toInt() and 0xff
                if (size == 0 || size >= 0x80 || at + 2 + size > der.size) return null
                val value = der.copyOfRange(at + 2, at + 2 + size)
                at += 2 + size
                if (value[0] < 0) return null
                if (value.size > 1 && value[0] == 0.toByte() && value[1] >= 0) return null
                val magnitude = if (value[0] == 0.toByte()) value.copyOfRange(1, value.size) else value
                if (magnitude.size > 32) return null
                return ByteArray(32 - magnitude.size) + magnitude
            }
            val r = integer() ?: return null
            val s = integer() ?: return null
            return if (at == der.size) r + s else null
        }
    }
}
