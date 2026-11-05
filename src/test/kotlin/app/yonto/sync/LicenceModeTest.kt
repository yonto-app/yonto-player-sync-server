package app.yonto.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.Closeable
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Signs licence tokens as the licensing service does, with a key of the test's own. */
class Signer {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    val publicKey: String = (pair.public as ECPublicKey).w.let { point ->
        fun coordinate(n: java.math.BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        Base64.getEncoder().encodeToString(byteArrayOf(4) + coordinate(point.affineX) + coordinate(point.affineY))
    }

    /** A token issued to the install whose id is [install]: its `dev` is that id's hash, as the service signs it. */
    fun token(licence: String, issuedAt: Long = 1_791_000_000, product: String = "yonto", install: String = "install-d"): String {
        val claims = """{"v":1,"prd":"$product","lic":"$licence","dev":"${devOf(install)}","iat":$issuedAt,"rck":${issuedAt + 1},"ftr":[{"n":"sync"}]}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload.toByteArray())
            sign()
        }
        return (payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)).also { installIds[it] = install }
    }

    companion object {
        /** Each token's install id, which [Harness.request] sends beside it unless a test names its own. */
        val installIds = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun devOf(install: String): String = Codec.hex(Codec.sha256(install.toByteArray()))
    }
}

/** The licensing service's `sync/check`, answering as [answer] says for the licence in the token. */
class FakeLicensing : Closeable {
    enum class Answer { YES, NO, BUSY, DOWN }

    @Volatile var answer = Answer.YES

    /** Installs answered no whatever [answer] says, as the service refuses a seat that was freed. */
    val refusedInstalls: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val calls = AtomicInteger()
    val bodies = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/sync/check") { exchange ->
            calls.incrementAndGet()
            synchronized(bodies) { bodies += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8) }
            val token = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
            val payload = Base64.getUrlDecoder().decode(token.substringBefore('.')).toString(Charsets.UTF_8)
            val claims = WireJson.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
            val licence = claims["lic"]!!.jsonPrimitive.content
            val refused = claims["dev"]!!.jsonPrimitive.content in refusedInstalls
            val (status, body) = when (if (refused && answer == Answer.YES) Answer.NO else answer) {
                Answer.YES -> 200 to """{"licence":"$licence","allowed":true}"""
                Answer.NO -> 403 to """{"error":"not_allowed"}"""
                Answer.BUSY -> 429 to """{"error":"rate_limited"}"""
                Answer.DOWN -> 503 to """{"error":"down"}"""
            }
            if (status == 429) exchange.responseHeaders.set("Retry-After", "17")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        createContext("/api/v1/sync/resets") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val path = exchange.requestURI.path.removePrefix("/api/v1/sync/resets")
            val (status, answer) = if (path.isEmpty()) {
                synchronized(resetAsks) { resetAsks += body }
                when (reset) {
                    Answer.YES -> 200 to """{"confirm":"${if (email) "email" else "wait"}"}"""
                    Answer.NO -> 403 to """{"error":"device_removed"}"""
                    Answer.BUSY -> 429 to """{"error":"rate_limited"}"""
                    Answer.DOWN -> 503 to """{"error":"down"}"""
                }
            } else {
                statusChecks.incrementAndGet()
                synchronized(statusBodies) { statusBodies += body }
                when (confirmed) {
                    null -> 404 to """{"error":"not_found"}"""
                    else -> 200 to """{"confirmed":$confirmed}"""
                }
            }
            if (status == 429) exchange.responseHeaders.set("Retry-After", "23")
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    /** How the service answers a reset asked: [email] when the licence has an address. */
    @Volatile var reset = Answer.YES

    @Volatile var email = true

    /** Whether the owner confirmed, as a status check is answered; null for a link that lapsed. */
    @Volatile var confirmed: Boolean? = false
    val resetAsks = mutableListOf<String>()
    val statusBodies = mutableListOf<String>()
    val statusChecks = AtomicInteger()

    override fun close() = server.stop(0)
}

private const val HOUR = 60 * 60 * 1000L

class LicenceModeTest {
    private val signer = Signer()
    private val pro = signer.token(ACCOUNT)

    private fun licensed(url: String? = null, test: (Harness, FakeLicensing) -> Unit) = FakeLicensing().use { licensing ->
        Harness({ it.copy(licensingUrl = url ?: licensing.url, licenceKeys = "${Signer().publicKey}, ${signer.publicKey}") })
            .use { test(it, licensing) }
    }

    private fun Harness.account(token: String?, account: String = ACCOUNT) =
        request("GET", "/v1/account", headers = mapOf("Yonto-Account" to account, "Yonto-Licence" to token))

    @Test
    fun `a licence that syncs is let through on every request`() = licensed { h, licensing ->
        val licence = mapOf("Yonto-Licence" to pro)
        assertEquals(201, h.createAccount(extra = licence).status)
        assertEquals(200, h.push(record(), headers = h.headers() + licence).status)
        assertEquals(1, h.pulled(headers = h.headers() + licence).size)
        assertEquals("not_allowed", h.pull(headers = h.headers()).error)
        assertEquals(listOf("{}"), licensing.bodies.distinct())
    }

    @Test
    fun `a token whose signature does not verify is refused without asking the service`() = licensed { h, licensing ->
        val forged = Signer().token(ACCOUNT)
        assertEquals("not_allowed", h.account(forged).error)
        val tampered = pro.substringBefore('.').dropLast(2) + "xx." + pro.substringAfter('.')
        assertEquals("not_allowed", h.account(tampered).error)
        assertEquals("not_allowed", h.account(signer.token(ACCOUNT, product = "other")).error)
        assertEquals("not_allowed", h.account("not a token").error)
        assertEquals(0, licensing.calls.get())
    }

    @Test
    fun `a valid token for one licence never reaches another account`() = licensed { h, licensing ->
        h.createAccount(account = "lic_someone_else", extra = mapOf("Yonto-Licence" to signer.token("lic_someone_else")))
        val calls = licensing.calls.get()
        assertEquals("not_allowed", h.account(pro, account = "lic_someone_else").error)
        assertEquals("not_allowed", h.request("DELETE", "/v1/account", headers = mapOf("Yonto-Account" to "lic_someone_else", "Yonto-Licence" to pro)).error)
        assertEquals(calls, licensing.calls.get())
        assertEquals(200, h.account(signer.token("lic_someone_else"), account = "lic_someone_else").status)
    }

    @Test
    fun `a yes is remembered for a day per licence, a refreshed token included`() = licensed { h, licensing ->
        h.createAccount(extra = mapOf("Yonto-Licence" to pro))
        h.account(pro)
        h.account(signer.token(ACCOUNT, issuedAt = 1_791_000_500))
        assertEquals(1, licensing.calls.get())
        h.now += 24 * HOUR - 1
        assertEquals(200, h.account(pro).status)
        assertEquals(1, licensing.calls.get())
        h.now += 1
        assertEquals(200, h.account(pro).status)
        assertEquals(2, licensing.calls.get())
    }

    @Test
    fun `each install of a licence has its own answer, fresh or stale`() = licensed { h, licensing ->
        val phone = signer.token(ACCOUNT, install = "phone")
        val tv = signer.token(ACCOUNT, install = "tv")
        h.createAccount(extra = mapOf("Yonto-Licence" to tv))
        licensing.refusedInstalls += Signer.devOf("phone")
        assertEquals("not_allowed", h.account(phone).error)
        assertEquals(200, h.account(tv).status, "one refused install does not lock the licence out")

        licensing.refusedInstalls.clear()
        licensing.refusedInstalls += Signer.devOf("tv")
        h.now += 24 * HOUR
        assertEquals("not_allowed", h.account(tv).error)
        assertEquals(200, h.account(phone).status)

        licensing.answer = FakeLicensing.Answer.DOWN
        h.now += 24 * HOUR
        assertEquals(200, h.account(phone).status)
        assertEquals("unavailable", h.account(tv).error, "a refused install does not ride on another's yes")
    }

    @Test
    fun `a stranger without the licence does not spend the account's rate`() = FakeLicensing().use { licensing ->
        Harness({ it.copy(licensingUrl = licensing.url, licenceKeys = signer.publicKey, accountRequestsPerMinute = 3) }).use { h ->
            h.createAccount(extra = mapOf("Yonto-Licence" to pro))
            repeat(10) { assertEquals("not_allowed", h.account(Signer().token(ACCOUNT)).error) }
            repeat(2) { assertEquals(200, h.account(pro).status) }
            assertEquals("rate_limited", h.account(pro).error)
        }
    }

    @Test
    fun `a no is remembered for ten minutes`() = licensed { h, licensing ->
        licensing.answer = FakeLicensing.Answer.NO
        assertEquals("not_allowed", h.account(pro).error)
        licensing.answer = FakeLicensing.Answer.YES
        h.now += 10 * 60 * 1000 - 1
        assertEquals("not_allowed", h.account(pro).error)
        assertEquals(1, licensing.calls.get())
        h.now += 1
        assertEquals("no_account", h.account(pro).error)
        assertEquals(2, licensing.calls.get())
    }

    @Test
    fun `while the service is down a yes up to a week old still counts, and none is unavailable`() = licensed { h, licensing ->
        h.createAccount(extra = mapOf("Yonto-Licence" to pro))
        licensing.answer = FakeLicensing.Answer.DOWN
        h.now += 7 * 24 * HOUR - 1
        assertEquals(200, h.account(pro).status)
        h.now += 1
        val down = h.account(pro)
        assertEquals(503 to "unavailable", down.status to down.error)
        assertEquals("60", down.header("Retry-After"))

        val stranger = signer.token("lic_new")
        assertEquals("unavailable", h.account(stranger, account = "lic_new").error)
    }

    @Test
    fun `an unreachable service counts as down`() {
        val closed = java.net.ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }
        licensed(url = closed) { h, _ ->
            assertEquals("unavailable", h.account(pro).error)
        }
    }

    @Test
    fun `the service's rate limit is passed on when there is no recent yes`() = licensed { h, licensing ->
        licensing.answer = FakeLicensing.Answer.BUSY
        val busy = h.account(pro)
        assertEquals("rate_limited", busy.error)
        assertEquals("17", busy.header("Retry-After"))
    }

    private val everyEndpoint = listOf(
        "GET" to "/v1/account",
        "PUT" to "/v1/account",
        "DELETE" to "/v1/account",
        "POST" to "/v1/account/generation",
        "GET" to "/v1/devices",
        "POST" to "/v1/devices",
        "POST" to "/v1/devices/$DEVICE_B/remove",
        "GET" to "/v1/wrap",
        "DELETE" to "/v1/wrap",
        "POST" to "/v1/records",
        "GET" to "/v1/records?since=0",
        "POST" to "/v1/forget",
        "POST" to "/v1/joins",
        "GET" to "/v1/joins",
        "GET" to "/v1/joins/${"0".repeat(32)}",
        "POST" to "/v1/joins/${"0".repeat(32)}/open",
        "POST" to "/v1/joins/${"0".repeat(32)}/reveal",
        "POST" to "/v1/joins/${"0".repeat(32)}/allow",
        "POST" to "/v1/joins/${"0".repeat(32)}/refuse",
        "POST" to "/v1/account/reset",
        "GET" to "/v1/account/reset",
        "DELETE" to "/v1/account/reset",
    )

    @Test
    fun `every endpoint checks the licence and its install id before it reads or writes the database`() = licensed { h, licensing ->
        h.createAccount(extra = mapOf("Yonto-Licence" to pro))
        // With the database gone, any read or write answers 500; a refusal first answers 403.
        h.server.store.close()
        val body = WireJson.parseToJsonElement("{}")
        val calls = licensing.calls.get()
        for ((method, path) in everyEndpoint) {
            val refused = listOf(
                mapOf("Yonto-Licence" to null),
                mapOf("Yonto-Licence" to Signer().token(ACCOUNT)),
                mapOf("Yonto-Licence" to signer.token("lic_other")),
                mapOf("Yonto-Licence" to pro, "Yonto-Install" to null),
                mapOf("Yonto-Licence" to pro, "Yonto-Install" to "install-e"),
                mapOf("Yonto-Licence" to pro, "Yonto-Install" to Signer.devOf("install-d")),
            )
            for (licence in refused) {
                val headers = h.headers() + licence + ("Yonto-Join-Secret" to "secret") + ("Yonto-Reset-Secret" to "secret")
                val answer = h.request(method, path, body.takeIf { method != "GET" }, headers)
                assertEquals(403 to "not_allowed", answer.status to answer.error, "$method $path with $licence")
            }
            val licensed = h.request(method, path, body.takeIf { method != "GET" }, h.headers() + ("Yonto-Licence" to pro))
            assertNotEquals(403, licensed.status, "$method $path with a licence that syncs")
        }
        assertEquals(calls, licensing.calls.get(), "a token without its install id is refused before the service is asked")
        assertEquals(500, h.account(pro).status)
    }

    @Test
    fun `an install id longer than any the apps make is refused, even one that matches the token`() = licensed { h, _ ->
        val longest = "i".repeat(128)
        assertEquals("no_account", h.account(signer.token(ACCOUNT, install = longest)).error)
        assertEquals("not_allowed", h.account(signer.token(ACCOUNT, install = longest + "i")).error)
    }

    @Test
    fun `dropping the account needs the key, the licence alone is not enough`() = licensed { h, _ ->
        h.createAccount(extra = mapOf("Yonto-Licence" to pro))
        val licenceOnly = mapOf("Yonto-Account" to ACCOUNT, "Yonto-Licence" to pro)
        assertEquals("unauthorized", h.request("DELETE", "/v1/account", headers = licenceOnly).error)
        assertEquals(200, h.account(pro).status)
        assertEquals(204, h.request("DELETE", "/v1/account", headers = h.headers() + ("Yonto-Licence" to pro)).status)
        assertEquals("no_account", h.account(pro).error)
    }

    @Test
    fun `a removal has the install the server saw the device prove asked about again, never one the remover names`() = licensed { h, licensing ->
        val tv = signer.token(ACCOUNT, install = "tv")
        val phone = signer.token(ACCOUNT, install = "phone")
        val asTv = h.headers() + ("Yonto-Licence" to tv)
        h.createAccount(extra = mapOf("Yonto-Licence" to tv))
        val (join, secret) = h.askToJoin(extra = mapOf("Yonto-Licence" to phone))
        assertEquals(204, h.open(join, asTv).status)
        val reveal = buildJsonObject { put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("nonce", Vectors.joiningNonce) }
        val asJoining = mapOf("Yonto-Join-Secret" to secret, "Yonto-Account" to ACCOUNT, "Yonto-Licence" to phone)
        assertEquals(204, h.request("POST", "/v1/joins/$join/reveal", reveal, asJoining).status)
        assertEquals(204, h.allow(join, asTv).status)
        val listed = h.request("GET", "/v1/devices", headers = asTv).json["devices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf(DEVICE_A to Signer.devOf("tv"), DEVICE_B to Signer.devOf("phone")), listed.map { it.string("id") to it.strings("installs").single() }.toSet())

        licensing.refusedInstalls += listOf(Signer.devOf("tv"), Signer.devOf("phone"))
        assertEquals(200, h.account(phone).status, "the day-long yes still stands")
        val remove = h.request(
            "POST", "/v1/devices/$DEVICE_B/remove",
            WireJson.parseToJsonElement("""{"install":"${Signer.devOf("tv")}"}"""),
            asTv,
        )
        assertEquals(204, remove.status)
        assertEquals("not_allowed", h.account(phone).error)
        assertEquals(200, h.account(tv).status, "the install the remover named keeps its answer")
    }

    @Test
    fun `a device is listed with every install it proved, and removing it forgets them all`() = licensed { h, licensing ->
        val tv = signer.token(ACCOUNT, install = "tv")
        val phone = signer.token(ACCOUNT, install = "phone")
        val spare = signer.token(ACCOUNT, install = "spare")
        h.createAccount(extra = mapOf("Yonto-Licence" to tv))
        val (join, secret) = h.askToJoin(extra = mapOf("Yonto-Licence" to phone))
        val asTv = h.headers() + ("Yonto-Licence" to tv)
        assertEquals(204, h.open(join, asTv).status)
        val reveal = buildJsonObject { put("publicKey", Vectors.joiningKey); put("signingKey", Vectors.signingKey); put("nonce", Vectors.joiningNonce) }
        assertEquals(204, h.request("POST", "/v1/joins/$join/reveal", reveal, mapOf("Yonto-Join-Secret" to secret, "Yonto-Account" to ACCOUNT, "Yonto-Licence" to phone)).status)
        assertEquals(204, h.allow(join, asTv).status)
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B) + ("Yonto-Licence" to spare)).status)
        assertEquals(200, h.pull(headers = h.headers(DEVICE_B) + ("Yonto-Licence" to phone)).status)
        val listed = h.request("GET", "/v1/devices", headers = asTv).json["devices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(Signer.devOf("phone"), Signer.devOf("spare")).sorted(), listed.single { it.string("id") == DEVICE_B }.strings("installs"))

        licensing.refusedInstalls += listOf(Signer.devOf("phone"), Signer.devOf("spare"))
        assertEquals(204, h.request("POST", "/v1/devices/$DEVICE_B/remove", headers = asTv).status)
        assertEquals("not_allowed", h.account(phone).error)
        assertEquals("not_allowed", h.account(spare).error)
    }

    @Test
    fun `one install holds at most two open join requests`() = licensed { h, _ ->
        val licence = mapOf("Yonto-Licence" to pro)
        h.createAccount(extra = licence)
        h.askToJoin(device = "0f1e2d3c4b5a", extra = licence)
        h.askToJoin(device = "0f1e2d3c4b5b", extra = licence)
        val third = h.request(
            "POST", "/v1/joins",
            WireJson.parseToJsonElement("""{"device":"0f1e2d3c4b5c","platform":"ios","commitment":"${Vectors.commitment}","secretHash":"${SECRET_B.verifier}"}"""),
            mapOf("Yonto-Account" to ACCOUNT) + licence,
        )
        assertEquals(429 to "rate_limited", third.status to third.error)
        h.askToJoin(device = "0f1e2d3c4b5c", extra = mapOf("Yonto-Licence" to signer.token(ACCOUNT, install = "phone")))
    }

    @Test
    fun `a join read with its secret also needs the licence`() = licensed { h, _ ->
        val licence = mapOf("Yonto-Licence" to pro)
        h.createAccount(extra = licence)
        val (join, secret) = h.askToJoin(extra = licence)
        val unlicensed = mapOf("Yonto-Join-Secret" to secret, "Yonto-Account" to ACCOUNT)
        assertEquals("not_allowed", h.request("GET", "/v1/joins/$join", headers = unlicensed).error)
        val read = h.request("GET", "/v1/joins/$join", headers = unlicensed + licence)
        assertEquals("waiting", read.json.string("state"))
    }

    @Test
    fun `the app's licence token vectors read the same here, but for the install, which a server cannot check`() {
        val file = contractFileOrNull("licence-token-v1.json")
        // The public copy of this server leaves Yonto's licence token vectors out; Yonto's repo, which has
        // contract-versions.json, must still have them.
        assumeTrue(file != null || contractFileOrNull("contract-versions.json") != null)
        val vectors = WireJson.parseToJsonElement(file!!.readText()) as kotlinx.serialization.json.JsonObject
        val tokens = LicenceTokens("yonto", LicenceTokens.parseKeys(vectors["public_key_x963_base64"]!!.jsonPrimitive.content))
        val cases = (vectors["cases"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
        assertEquals(13, cases.size)
        for (case in cases) {
            val name = case.string("name")!!
            val valid = case.flag("valid") || name == "other_device"
            val licence = tokens.read(case.string("token")!!)?.licence
            assertEquals(if (valid) "0199a0b4-6c2e-7d4f-9a51-3c8e2f1b7a60" else null, licence, name)
        }
    }
}
