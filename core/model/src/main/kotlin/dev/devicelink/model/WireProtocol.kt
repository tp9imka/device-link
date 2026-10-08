package dev.devicelink.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded control messages; file content travels separately with an accepted payload binding. */
@Serializable
sealed interface WireMessage {
    @Serializable @SerialName("commitment") data class Commitment(val digest: String) : WireMessage
    @Serializable @SerialName("hello") data class Hello(val publicKey: String, val nonce: String, val ephemeralKey: String = "", val displayName: String = "Android") : WireMessage
    @Serializable @SerialName("proof") data class Proof(val signature: String) : WireMessage
    @Serializable @SerialName("pair_approved") data object PairApproved : WireMessage
    @Serializable @SerialName("pair_rejected") data object PairRejected : WireMessage
    @Serializable @SerialName("offer") data class Offer(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val kind: TransferKind,
        val payloadId: Long? = null,
    ) : WireMessage
    @Serializable @SerialName("accept") data class Accept(val id: String) : WireMessage
    @Serializable @SerialName("reject") data class Reject(val id: String) : WireMessage
    @Serializable @SerialName("cancel") data class Cancel(val id: String) : WireMessage
    @Serializable @SerialName("text") data class Text(val id: String, val text: String) : WireMessage
    @Serializable @SerialName("clip") data class Clip(val id: String, val text: String) : WireMessage
    @Serializable @SerialName("clip_result") data class ClipResult(val id: String, val copied: Boolean) : WireMessage
    @Serializable @SerialName("receipt") data class Receipt(val id: String) : WireMessage
}

@Serializable
private data class Envelope(val version: Int, val message: WireMessage)

object WireCodec {
    const val VERSION = 1
    const val MAX_WIRE_BYTES = 32_768
    const val MAX_TEXT_BYTES = 8_192
    const val MAX_FILE_BYTES = 10L * 1024 * 1024 * 1024
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val mimePattern = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    /** Throws IllegalArgumentException; callers must disconnect/reject malformed peer messages. */
    fun encode(message: WireMessage): ByteArray {
        validate(message)
        return json.encodeToString(Envelope(VERSION, message)).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_WIRE_BYTES) { "Control message exceeds wire limit" }
        }
    }

    fun decode(bytes: ByteArray): WireMessage {
        require(bytes.isNotEmpty() && bytes.size <= MAX_WIRE_BYTES) { "Invalid control message size" }
        val envelope = try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            validateNesting(text)
            json.decodeFromString<Envelope>(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed control message", error)
        }
        require(envelope.version == VERSION) { "Unsupported protocol version" }
        validate(envelope.message)
        return envelope.message
    }

    fun validate(message: WireMessage) {
        when (message) {
            is WireMessage.Commitment -> require(message.digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid handshake commitment" }
            is WireMessage.Hello -> {
                require(message.displayName.isNotBlank() && message.displayName.length <= 48 && message.displayName.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Invalid device name" }
                require(decoded(message.publicKey, 256).size in 64..256) { "Invalid identity key length" }
                require(decoded(message.nonce, 32).size == 32) { "Invalid nonce length" }
                if (message.ephemeralKey.isNotEmpty()) require(decoded(message.ephemeralKey, 256).size in 64..256) { "Invalid agreement key length" }
            }
            is WireMessage.Proof -> require(decoded(message.signature, 128).size in 64..128) { "Invalid proof length" }
            is WireMessage.Offer -> {
                validId(message.id)
                require(message.name.isNotBlank() && message.name.length <= 255) { "Invalid offered name" }
                require(message.mime.length <= 127 && mimePattern.matches(message.mime)) { "Invalid MIME type" }
                when (message.kind) {
                    TransferKind.FILE -> {
                        require(message.size in 0..MAX_FILE_BYTES) { "Invalid file size" }
                        require(message.payloadId != null) { "File offer requires payload binding" }
                    }
                    TransferKind.TEXT -> {
                        require(message.size in 1..MAX_TEXT_BYTES.toLong()) { "Invalid text size" }
                        require(message.payloadId == null && message.mime == "text/plain") { "Invalid text metadata" }
                    }
                }
            }
            is WireMessage.Text -> {
                validId(message.id)
                require(message.text.toByteArray(Charsets.UTF_8).size in 1..MAX_TEXT_BYTES) { "Invalid text size" }
            }
            is WireMessage.Clip -> {
                validId(message.id)
                require(message.text.toByteArray(Charsets.UTF_8).size in 1..MAX_TEXT_BYTES) { "Invalid clip size" }
            }
            is WireMessage.ClipResult -> validId(message.id)
            is WireMessage.Accept -> validId(message.id)
            is WireMessage.Reject -> validId(message.id)
            is WireMessage.Cancel -> validId(message.id)
            is WireMessage.Receipt -> validId(message.id)
            WireMessage.PairApproved, WireMessage.PairRejected -> Unit
        }
    }

    private fun validateNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 4) { "Control message nesting exceeds limit" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid control message nesting" } }
            }
        }
        require(depth == 0 && !quoted) { "Incomplete control message" }
    }

    private fun validId(id: String) = require(idPattern.matches(id)) { "Invalid transfer ID" }
    private fun decoded(value: String, maxBytes: Int): ByteArray {
        require(value.length <= (maxBytes + 2) / 3 * 4) { "Encoded value exceeds limit" }
        return Base64.getDecoder().decode(value).also { require(it.size <= maxBytes) }
    }
}

object FileNames {
    /** Display/storage basename only. Store under a generated transfer directory to avoid collisions. */
    fun sanitize(raw: String): String {
        val basename = raw.replace('\\', '/').substringAfterLast('/')
        return basename.filter { char ->
            !char.isISOControl() && Character.getType(char) != Character.FORMAT.toInt() &&
                char !in "<>:\"|?*" && !Character.isSurrogate(char)
        }.trim().trim('.').take(120).trim().ifBlank { "download" }
    }
}
