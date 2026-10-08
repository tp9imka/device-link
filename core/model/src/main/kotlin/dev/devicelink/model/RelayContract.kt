package dev.devicelink.model

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
data class RelayKeyBundle(val identityPublicKey: String, val encryptionPublicKey: String, val signature: String) {
    val id: String get() = TrustProof.fingerprint(identityPublicKey)
}

@Serializable
enum class RelayPayloadKind { TEXT, CLIP_TEXT, FILE, CLIP_IMAGE, RECEIPT }

/** Every field here is encrypted. Base64 avoids JSON integer-array expansion for binary content. */
@Serializable
data class RelayPayload(
    val kind: RelayPayloadKind,
    val text: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val contentBase64: String? = null,
    val receiptFor: String? = null,
    val receiptCopied: Boolean? = null,
) {
    fun contentBytes(): ByteArray? = contentBase64?.let { RelayCodec.decodeContent(it) }
    companion object {
        fun encodeContent(bytes: ByteArray): String {
            require(bytes.size <= RelayCodec.MAX_CONTENT_BYTES)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}

/** Public routing metadata is bound to HPKE context and the sender's identity signature. */
@Serializable
data class RelayEnvelope(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val sequence: Long,
    val ciphertext: String,
    val signature: String,
    @Required val version: Int = 1,
)

/**
 * Explicit ordering policy, intended for clipboard events only after payload authentication.
 * Ordinary files may be accepted after newer messages: use durable envelope-ID deduplication for
 * all kinds, and a no-high-water guard during decryption when kinds are mixed. The application
 * serializes check/application/record and persists clipboard counters across restarts.
 */
class RelayReplayGuard(
    private val loadLastSequence: (senderId: String) -> Long?,
    private val storeLastSequence: (senderId: String, sequence: Long) -> Unit,
) {
    @Synchronized
    fun check(senderId: String, sequence: Long) {
        require(senderId.matches(Regex("[a-f0-9]{64}")) && sequence > 0) { "Invalid relay sequence" }
        require(sequence > (loadLastSequence(senderId) ?: 0)) { "Replayed or out-of-order relay envelope" }
    }

    /** Call only after decrypt succeeds and the application reaches its durable delivery boundary. */
    @Synchronized
    fun record(senderId: String, sequence: Long) {
        check(senderId, sequence)
        storeLastSequence(senderId, sequence)
    }
}
