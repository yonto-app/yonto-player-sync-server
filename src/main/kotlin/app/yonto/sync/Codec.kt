package app.yonto.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Codec {
    private val random = SecureRandom()
    private val base64Url = Regex("^[A-Za-z0-9_-]*$")
    private val deviceId = Regex("^[0-9a-f]{12}$")
    private val recordId = Regex("^[0-9a-f]{64}$")
    private val accountId = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun randomHex(bytes: Int): String = hex(ByteArray(bytes).also(random::nextBytes))

    fun randomDigits(count: Int): String = (1..count).joinToString("") { random.nextInt(10).toString() }

    fun randomBase64Url(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** base64url without padding, or null when it does not read. */
    fun base64Url(text: String): ByteArray? {
        if (!base64Url.matches(text) || text.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    /** What a server keeps of `auth` (the verifier) and of a device's secret: SHA-256 of the bytes, hex. */
    fun hashOf(secret: ByteArray): String = hex(sha256(secret))

    /** The commitment a joining device makes to its public key and nonce before it sees the other's. */
    fun commitmentOf(publicKey: String, signingKey: String, nonce: String): String =
        hex(sha256("yonto-sync-v1|commit|$publicKey|$signingKey|$nonce".toByteArray()))

    fun sameHex(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    fun isDeviceId(text: String) = deviceId.matches(text)

    fun isRecordId(text: String) = recordId.matches(text)

    fun isAccountId(text: String) = accountId.matches(text)

    fun isHash(text: String) = recordId.matches(text)

    /** An uncompressed P-256 point, base64url. */
    fun isPublicKey(text: String): Boolean = base64Url(text)?.let { it.size == 65 && it[0] == 4.toByte() } ?: false

    /** A join's nonce: 16 random bytes, base64url. */
    fun isNonce(text: String): Boolean = base64Url(text)?.size == 16

    /** iv ‖ ciphertext ‖ tag: at least a 12-byte iv and a 16-byte tag. */
    fun sealedSize(text: String): Int? = base64Url(text)?.size?.takeIf { it >= 28 }
}
