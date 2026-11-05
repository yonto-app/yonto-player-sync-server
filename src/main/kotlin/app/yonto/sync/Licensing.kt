package app.yonto.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

sealed interface LicenceAnswer {
    data class Allowed(val licence: String, val install: String) : LicenceAnswer
    data object Refused : LicenceAnswer
    data class Busy(val retryAfterSeconds: Long) : LicenceAnswer
    data class Unavailable(val retryAfterSeconds: Long) : LicenceAnswer
}

/**
 * Whether a licence token may sync: its signature is checked here, offline, and the licensing service is asked
 * only about a licence it has no fresh answer for, so Sync stays up while that service is down.
 */
class Licensing(
    private val baseUrl: String,
    private val tokens: LicenceTokens,
    private val clock: () -> Long,
    private val allowMillis: Long,
    private val refuseMillis: Long,
    private val staleMillis: Long,
) {
    private class Known(val allowed: Boolean, val at: Long)

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val known = ConcurrentHashMap<String, Known>()

    /**
     * Whether [token] lets [account] sync. A token for another licence, or one whose [installId] does not hash to its
     * `dev`, is refused without asking anyone: a token that left its device on its own carries no install id.
     */
    fun check(token: String, installId: String, account: String): LicenceAnswer {
        val read = tokens.read(token)?.takeIf { it.licence == account && proves(installId, it.install) } ?: return LicenceAnswer.Refused
        val licence = read.licence
        // The service answers per install, so each install of a licence has an answer of its own.
        val key = "$licence|${read.install}"
        val now = clock()
        val last = known[key]
        if (last != null && last.allowed && now - last.at < allowMillis) return LicenceAnswer.Allowed(licence, read.install)
        if (last != null && !last.allowed && now - last.at < refuseMillis) return LicenceAnswer.Refused
        val stale = last?.takeIf { it.allowed && now - it.at < staleMillis }?.let { LicenceAnswer.Allowed(licence, read.install) }
        return when (val answer = ask(token)) {
            is Asked.Licence -> {
                val allowed = answer.licence == licence
                remember(key, Known(allowed, now))
                if (allowed) LicenceAnswer.Allowed(licence, read.install) else LicenceAnswer.Refused
            }
            Asked.No -> {
                remember(key, Known(false, now))
                LicenceAnswer.Refused
            }
            is Asked.Busy -> stale ?: LicenceAnswer.Busy(answer.retryAfterSeconds)
            Asked.Down -> stale ?: LicenceAnswer.Unavailable(RETRY_WHEN_DOWN_SECONDS)
        }
    }

    /** Forgets what the service said about [install], so its token is checked again at its next request. */
    fun forget(licence: String, install: String) {
        known.remove("$licence|$install")
    }

    /** What the licensing service says a reset of the licence waits for. */
    sealed interface ResetAnswer {
        data object Email : ResetAnswer
        data object Wait : ResetAnswer
        data object Refused : ResetAnswer
        data class Busy(val retryAfterSeconds: Long) : ResetAnswer
        data object Down : ResetAnswer
    }

    /** Asks the service to email the licence's owner about reset [reset], showing [code], or to say there is nobody to email. */
    fun askReset(token: String, installId: String, reset: String, code: String, platform: String): ResetAnswer {
        val body = buildJsonObject {
            put("reset", reset)
            put("code", code)
            put("platform", platform)
            put("install_id", installId)
        }
        val response = post("/api/v1/sync/resets", token, body.toString()) ?: return ResetAnswer.Down
        val status = response.statusCode()
        return when {
            status == 429 -> ResetAnswer.Busy(retryAfter(response))
            status >= 500 -> ResetAnswer.Down
            status !in 200..299 -> ResetAnswer.Refused
            else -> when (field(response, "confirm")) {
                "email" -> ResetAnswer.Email
                "wait" -> ResetAnswer.Wait
                else -> ResetAnswer.Down
            }
        }
    }

    /** Whether the owner confirmed reset [reset]'s email. */
    sealed interface Confirmation {
        data object Confirmed : Confirmation
        data object NotYet : Confirmation

        /** Its link lapsed unconfirmed, or the service holds no such reset. */
        data object Gone : Confirmation
        data object Refused : Confirmation

        /** Busy or down: asked again at the next poll. */
        data object Unknown : Confirmation
    }

    fun resetConfirmed(token: String, installId: String, reset: String): Confirmation {
        val body = buildJsonObject { put("install_id", installId) }
        val response = post("/api/v1/sync/resets/$reset/status", token, body.toString()) ?: return Confirmation.Unknown
        val status = response.statusCode()
        return when {
            status == 404 -> Confirmation.Gone
            status == 429 || status >= 500 -> Confirmation.Unknown
            status != 200 -> Confirmation.Refused
            else -> {
                val confirmed = runCatching { (WireJson.parseToJsonElement(response.body()) as JsonObject)["confirmed"] as? JsonPrimitive }
                    .getOrNull()?.booleanOrNull
                when (confirmed) {
                    true -> Confirmation.Confirmed
                    false -> Confirmation.NotYet
                    null -> Confirmation.Unknown
                }
            }
        }
    }

    private fun post(path: String, token: String, body: String): HttpResponse<String>? {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrElse {
            System.err.println("Licensing service unreachable: ${it::class.simpleName}")
            null
        }
    }

    private fun field(response: HttpResponse<String>, name: String): String? =
        runCatching { ((WireJson.parseToJsonElement(response.body()) as JsonObject)[name] as? JsonPrimitive)?.content }.getOrNull()

    private fun retryAfter(response: HttpResponse<String>): Long =
        response.headers().firstValue("Retry-After").map { it.toLongOrNull() }.orElse(null) ?: 60

    private fun proves(installId: String, install: String): Boolean =
        installId.isNotEmpty() && Codec.sameHex(Codec.hex(Codec.sha256(installId.toByteArray())), install)

    private fun remember(key: String, answer: Known) {
        if (known.size > MAX_KNOWN) {
            val now = clock()
            known.entries.removeIf { now - it.value.at >= maxOf(staleMillis, refuseMillis) }
        }
        known[key] = answer
    }

    private sealed interface Asked {
        data class Licence(val licence: String) : Asked
        data object No : Asked
        data class Busy(val retryAfterSeconds: Long) : Asked
        data object Down : Asked
    }

    private fun ask(token: String): Asked {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/sync/check"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrElse {
            System.err.println("Licence check failed: $it")
            return Asked.Down
        }
        val status = response.statusCode()
        return when {
            status == 429 -> Asked.Busy(
                response.headers().firstValue("Retry-After").map { it.toLongOrNull() }.orElse(null) ?: 60,
            )
            status >= 500 -> Asked.Down
            status != 200 -> Asked.No
            else -> {
                val body = runCatching { WireJson.parseToJsonElement(response.body()) as JsonObject }.getOrNull()
                val licence = (body?.get("licence") as? JsonPrimitive)?.content
                val yes = (body?.get("allowed") as? JsonPrimitive)?.booleanOrNull == true
                if (yes && !licence.isNullOrEmpty()) Asked.Licence(licence) else Asked.No
            }
        }
    }

    private companion object {
        const val RETRY_WHEN_DOWN_SECONDS = 60L
        const val MAX_KNOWN = 100_000
    }
}
