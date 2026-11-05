package app.yonto.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Stamp(val millis: Long, val counter: Long, val device: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, Stamp::millis, Stamp::counter, Stamp::device)
}

@Serializable
data class WireRecord(
    val id: String,
    val gen: Int,
    val stamp: Stamp,
    val deleted: Boolean,
    val kept: Boolean,
    val sealed: String,
)

@Serializable
data class Wrap(val from: String, val sealed: String)

@Serializable
data class NewDevice(val id: String, val platform: String, val publicKey: String, val signingKey: String, val secretHash: String)

@Serializable
data class CreateAccount(val verifier: String, val generation: Int, val device: NewDevice)

@Serializable
data class DeviceWrap(val device: String, val wrap: Wrap)

@Serializable
data class StartOver(val generation: Int, val verifier: String, val wraps: List<DeviceWrap> = emptyList())

@Serializable
data class GenerationAnswer(val generation: Int)

@Serializable
data class DeviceView(
    val id: String,
    val platform: String,
    val publicKey: String,
    val signingKey: String?,
    val removed: Boolean,
    val added: Long,
    val installs: List<String> = emptyList(),
)

@Serializable
data class DevicesAnswer(val devices: List<DeviceView>)

@Serializable
data class WrapAnswer(val wrap: Wrap)

@Serializable
data class Push(val records: List<WireRecord>)

@Serializable
data class Refusal(val id: String, val reason: String)

@Serializable
data class PushAnswer(val taken: List<String>, val refused: List<Refusal>, val now: Long)

@Serializable
data class PullAnswer(
    val replica: String,
    val records: List<WireRecord>,
    val cursor: Long,
    val more: Boolean,
    val reset: ResetNotice? = null,
)

@Serializable
data class ResetNotice(val asked: Long, val platform: String, val state: String)

@Serializable
data class ResetMade(val reset: String, val secret: String, val confirm: String, val code: String? = null, val after: Long? = null)

@Serializable
data class ResetState(val state: String, val after: Long? = null)

@Serializable
data class Forget(val ids: List<String>)

@Serializable
data class JoinAsk(val device: String, val platform: String, val commitment: String, val secretHash: String)

@Serializable
data class JoinMade(val join: String, val secret: String)

@Serializable
data class JoinView(
    val join: String,
    val device: String,
    val platform: String,
    val commitment: String,
    val publicKey: String?,
    val signingKey: String?,
    val nonce: String?,
    val state: String,
    val created: Long,
    val openedBy: String?,
)

@Serializable
data class JoinsAnswer(val joins: List<JoinView>)

@Serializable
data class JoinKey(val publicKey: String, val nonce: String)

/** What the new device reveals: its two public keys and its nonce, which its commitment covers. */
@Serializable
data class JoinReveal(val publicKey: String, val signingKey: String, val nonce: String)

@Serializable
data class JoinAllow(val wrap: Wrap)

@Serializable
data class JoinState(val state: String, val publicKey: String? = null, val nonce: String? = null, val wrap: Wrap? = null)

@Serializable
data class ErrorAnswer(val error: String)
