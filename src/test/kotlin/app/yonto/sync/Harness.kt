package app.yonto.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals

/** A contract file: the first `contracts/<name>` from the working directory up, so it reads the same here and in the mirror. */
fun contractFile(name: String): File = contractFileOrNull(name) ?: error("No contracts/$name above ${File("").absolutePath}")

fun contractFileOrNull(name: String): File? = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { File(it, "contracts/$name") }
    .firstOrNull { it.isFile }

object Vectors {
    /** The contract's own vectors: the first `contracts/sync-v1.json` from the working directory up. */
    val json: JsonObject = WireJson.parseToJsonElement(contractFile("sync-v1.json").readText()).jsonObject

    private val derived = json["derived"]!!.jsonObject
    val auth: String = derived["auth_base64url"]!!.jsonPrimitive.content
    val verifier: String = derived["verifier_hex"]!!.jsonPrimitive.content
    val records: List<JsonObject> = json["records"]!!.jsonArray.map { it.jsonObject["wire"]!!.jsonObject }
    val plaintexts: List<String> = json["records"]!!.jsonArray.map { it.jsonObject["plaintext"]!!.jsonPrimitive.content }
    private val wrap = json["wrap"]!!.jsonObject
    val allowingKey: String = wrap["allowing"]!!.jsonObject["public_base64url"]!!.jsonPrimitive.content
    val joiningKey: String = wrap["joining"]!!.jsonObject["public_base64url"]!!.jsonPrimitive.content
    val signingKey: String = json["signing"]!!.jsonObject["public_base64url"]!!.jsonPrimitive.content
    val wrapWire: JsonObject = wrap["wire"]!!.jsonObject
    val commitment: String = json["commitment"]!!.jsonPrimitive.content
    private val nonces = json["nonces"]!!.jsonObject
    val joiningNonce: String = nonces["joining_base64url"]!!.jsonPrimitive.content
    val allowingNonce: String = nonces["allowing_base64url"]!!.jsonPrimitive.content
    private val deviceSecret = json["device_secret"]!!.jsonObject
    val secret: String = deviceSecret["secret_base64url"]!!.jsonPrimitive.content
    val secretHash: String = deviceSecret["hash_hex"]!!.jsonPrimitive.content
}

/** A second key's `auth` and verifier, made the way the contract says, not through the server's own code. */
class Key(seed: Int) {
    private val bytes = ByteArray(32) { (seed * 31 + it).toByte() }
    val auth: String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val verifier: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class Answer(val status: Int, val text: String, val headers: Map<String, List<String>>) {
    val json: JsonObject get() = WireJson.parseToJsonElement(text).jsonObject
    val error: String? get() = runCatching { json["error"]?.jsonPrimitive?.content }.getOrNull()
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

const val ACCOUNT = "lic_01JABCDEF"
const val DEVICE_A = "a1b2c3d4e5f6"
const val DEVICE_B = "0f1e2d3c4b5a"
const val START = 1761700020000L
val SECRET_B = Key(7)

/**
 * A port free both where the server binds and at 127.0.0.1, where the tests ask. Port 0 alone lets the server's
 * wildcard bind share a port another process holds on IPv4 only (colima's forwards do), and the tests then reach it.
 */
private fun freeLoopbackPort(): Int = generateSequence { ServerSocket(0).use { it.localPort } }
    .first { port -> runCatching { ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close() }.isSuccess }

class Harness(
    private val tune: (Config) -> Config = { it },
    val dir: File = Files.createTempDirectory("sync-server").toFile(),
) : Closeable {
    var now = START

    /** Runs each time the server reads its clock, as something happening at that moment. */
    var onClock: () -> Unit = {}
    val database = File(dir, "sync.db")
    var server = start()
        private set
    private val client = HttpClient.newHttpClient()

    private fun start() = SyncServer(
        tune(
            Config(
                port = freeLoopbackPort(),
                database = database.path,
                accountRequestsPerMinute = 0,
                addressRequestsPerMinute = 0,
            ),
        ),
        clock = { onClock(); now },
    ).start()

    fun restart() {
        server.close()
        server = start()
    }

    fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String?> = emptyMap(),
    ): Answer {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
        // A token goes with its install id, as the apps send it, unless the test says otherwise (null for none).
        val proven = headers["Yonto-Licence"]?.takeIf { "Yonto-Install" !in headers }
            ?.let { headers + ("Yonto-Install" to Signer.installIds[it]) } ?: headers
        proven.forEach { (name, value) -> if (value != null) builder.header(name, value) }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body(), response.headers().map())
    }

    fun headers(
        device: String = DEVICE_A,
        auth: String? = Vectors.auth,
        account: String = ACCOUNT,
        secret: String? = if (device == DEVICE_B) SECRET_B.auth else Vectors.secret,
    ) = mapOf(
        "Yonto-Account" to account,
        "Yonto-Device" to device,
        "Authorization" to auth?.let { "Yonto-Sync $it" },
        "Yonto-Device-Secret" to secret,
    )

    fun createAccount(
        verifier: String = Vectors.verifier,
        device: String = DEVICE_A,
        publicKey: String = Vectors.allowingKey,
        secretHash: String = Vectors.secretHash,
        account: String = ACCOUNT,
        extra: Map<String, String> = emptyMap(),
    ): Answer = request(
        "PUT", "/v1/account",
        buildJsonObject {
            put("verifier", verifier)
            put("generation", 1)
            put("device", buildJsonObject {
                put("id", device); put("platform", "android-tv"); put("publicKey", publicKey); put("signingKey", Vectors.joiningKey)
                put("secretHash", secretHash)
            })
        },
        mapOf("Yonto-Account" to account) + extra,
    )

    fun push(vararg records: JsonObject, headers: Map<String, String?> = headers()): Answer =
        request("POST", "/v1/records", buildJsonObject { put("records", JsonArray(records.toList())) }, headers)

    fun pull(since: Long = 0, limit: Int? = null, headers: Map<String, String?> = headers()): Answer =
        request("GET", "/v1/records?since=$since" + (limit?.let { "&limit=$it" } ?: ""), headers = headers)

    fun pulled(since: Long = 0, headers: Map<String, String?> = headers()): List<JsonObject> =
        pull(since, headers = headers).json["records"]!!.jsonArray.map { it.jsonObject }

    fun startOver(
        next: Key,
        generation: Int = 2,
        wrapsFor: List<String> = emptyList(),
        headers: Map<String, String?> = headers(),
    ): Answer = request(
        "POST", "/v1/account/generation",
        buildJsonObject {
            put("generation", generation)
            put("verifier", next.verifier)
            put("wraps", JsonArray(wrapsFor.map { buildJsonObject { put("device", it); put("wrap", Vectors.wrapWire) } }))
        },
        headers,
    )

    /** The new device's request: its join id and the secret it reads it with. */
    fun askToJoin(device: String = DEVICE_B, extra: Map<String, String> = emptyMap()): Pair<String, String> {
        val made = request(
            "POST", "/v1/joins",
            buildJsonObject {
                put("device", device)
                put("platform", "ios")
                put("commitment", Vectors.commitment)
                put("secretHash", SECRET_B.verifier)
            },
            mapOf("Yonto-Account" to ACCOUNT) + extra,
        )
        assertEquals(200, made.status, made.text)
        return made.json["join"]!!.jsonPrimitive.content to made.json["secret"]!!.jsonPrimitive.content
    }

    fun open(join: String, headers: Map<String, String?> = headers()) =
        request(
            "POST", "/v1/joins/$join/open",
            buildJsonObject { put("publicKey", Vectors.allowingKey); put("nonce", Vectors.allowingNonce) },
            headers,
        )

    fun reveal(
        join: String,
        secret: String,
        publicKey: String = Vectors.joiningKey,
        nonce: String = Vectors.joiningNonce,
        signingKey: String = Vectors.signingKey,
    ) =
        request(
            "POST", "/v1/joins/$join/reveal",
            buildJsonObject { put("publicKey", publicKey); put("signingKey", signingKey); put("nonce", nonce) },
            mapOf("Yonto-Join-Secret" to secret),
        )

    fun allow(join: String, headers: Map<String, String?> = headers()) =
        request("POST", "/v1/joins/$join/allow", buildJsonObject { put("wrap", Vectors.wrapWire) }, headers)

    fun joinState(join: String, secret: String) =
        request("GET", "/v1/joins/$join", headers = mapOf("Yonto-Join-Secret" to secret))

    /** Joins device B through device A, ending allowed. */
    fun join(headers: Map<String, String?> = headers()): Pair<String, String> {
        val (join, secret) = askToJoin()
        assertEquals(204, open(join, headers).status)
        assertEquals(204, reveal(join, secret).status)
        assertEquals(204, allow(join, headers).status)
        return join to secret
    }

    override fun close() {
        server.close()
        dir.deleteRecursively()
    }
}

fun record(
    id: String = Vectors.records[0]["id"]!!.jsonPrimitive.content,
    millis: Long = START,
    counter: Int = 0,
    device: String = DEVICE_A,
    gen: Int = 1,
    deleted: Boolean = false,
    kept: Boolean = false,
    sealed: String = Vectors.records[0]["sealed"]!!.jsonPrimitive.content,
): JsonObject = buildJsonObject {
    put("id", id)
    put("gen", gen)
    put("stamp", buildJsonObject { put("millis", millis); put("counter", counter); put("device", device) })
    put("deleted", deleted)
    put("kept", kept)
    put("sealed", sealed)
}

fun idOf(n: Int): String = "%064x".format(n)

val JsonObject.id: String get() = this["id"]!!.jsonPrimitive.content
val JsonObject.millis: Long get() = this["stamp"]!!.jsonObject["millis"]!!.jsonPrimitive.long
val JsonObject.counter: Int get() = this["stamp"]!!.jsonObject["counter"]!!.jsonPrimitive.int
fun JsonObject.flag(name: String): Boolean = this[name]!!.jsonPrimitive.content == "true"
fun JsonObject.strings(name: String): List<String> = this[name]!!.jsonArray.map { it.jsonPrimitive.content }
fun JsonObject.long(name: String): Long = this[name]!!.jsonPrimitive.long
fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
