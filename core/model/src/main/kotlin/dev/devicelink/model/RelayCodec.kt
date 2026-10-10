package dev.devicelink.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RelayCodec {
    const val MAX_CONTENT_BYTES = 8 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 12 * 1024 * 1024
    const val MAX_CIPHERTEXT_BYTES = 16 * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = 24 * 1024 * 1024
    const val MAX_BUNDLE_BYTES = 16 * 1024
    const val MAX_TEXT_BYTES = 8192
    const val CLIP_TTL_MILLIS = 60_000L
    // Must not exceed the relay sandbox cap (server max_ttl_ms, default 10 minutes).
    const val MESSAGE_TTL_MILLIS = 600_000L
    const val MAX_CLOCK_SKEW_MILLIS = 30_000L
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private val mimePattern = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

    fun encodeEnvelope(envelope: RelayEnvelope): ByteArray {
        validateEnvelope(envelope)
        return json.encodeToString(envelope).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_ENVELOPE_BYTES) }
    }
    fun decodeEnvelope(bytes: ByteArray): RelayEnvelope = json.decodeFromString<RelayEnvelope>(strictText(bytes, MAX_ENVELOPE_BYTES)).also(::validateEnvelope)

    /** Preserve raw envelope JSON until strict decoding; generic parsers can erase duplicate fields. */
    fun decodeEnvelopes(bytes: ByteArray, maxCount: Int = 1): List<RelayEnvelope> {
        require(maxCount in 1..20)
        val text = utf8(bytes, MAX_ENVELOPE_BYTES + 2)
        var index = 0
        fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        whitespace()
        require(index < text.length && text[index++] == '[')
        whitespace()
        val envelopes = mutableListOf<RelayEnvelope>()
        if (index < text.length && text[index] != ']') while (true) {
            require(envelopes.size < maxCount && index < text.length && text[index] == '{')
            val start = index++
            var quoted = false; var escaped = false; var closed = false
            while (index < text.length) {
                val char = text[index++]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> throw IllegalArgumentException("Relay envelope fields must be scalar")
                    '}' -> { closed = true; break }
                }
            }
            require(closed)
            envelopes.add(decodeEnvelope(text.substring(start, index).toByteArray(Charsets.UTF_8)))
            whitespace()
            if (index < text.length && text[index] == ',') { index++; whitespace() } else break
        }
        require(index < text.length && text[index++] == ']')
        whitespace()
        require(index == text.length)
        return envelopes
    }

    fun encodeBundle(bundle: RelayKeyBundle): ByteArray {
        validateBundle(bundle)
        return json.encodeToString(bundle).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BUNDLE_BYTES) }
    }
    fun decodeBundle(bytes: ByteArray): RelayKeyBundle = json.decodeFromString<RelayKeyBundle>(strictText(bytes, MAX_BUNDLE_BYTES)).also(::validateBundle)
    fun encodePayload(payload: RelayPayload): ByteArray {
        validatePayload(payload)
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }
    fun decodePayload(bytes: ByteArray): RelayPayload = json.decodeFromString<RelayPayload>(strictText(bytes, MAX_PAYLOAD_BYTES)).also(::validatePayload)

    fun ttlMillis(kind: RelayPayloadKind): Long = when (kind) {
        RelayPayloadKind.TEXT, RelayPayloadKind.FILE -> MESSAGE_TTL_MILLIS
        RelayPayloadKind.CLIP_TEXT, RelayPayloadKind.CLIP_IMAGE, RelayPayloadKind.RECEIPT -> CLIP_TTL_MILLIS
    }

    fun validatePayload(payload: RelayPayload) {
        when (payload.kind) {
            RelayPayloadKind.TEXT, RelayPayloadKind.CLIP_TEXT -> {
                require(payload.text != null && payload.text.toByteArray(Charsets.UTF_8).size in 1..MAX_TEXT_BYTES) { "Invalid relay text" }
                require(payload.name == null && payload.mime == null && payload.contentBase64 == null && payload.receiptFor == null && payload.receiptCopied == null)
            }
            RelayPayloadKind.FILE, RelayPayloadKind.CLIP_IMAGE -> {
                require(payload.text == null && payload.receiptFor == null && payload.receiptCopied == null)
                val name = requireNotNull(payload.name)
                require(name.isNotBlank() && name.length <= 120 && !name.contains('/') && !name.contains('\\') && name != "." && name != "..")
                require(name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
                val mime = requireNotNull(payload.mime)
                require(mime.length <= 127 && mimePattern.matches(mime))
                val content = decodeContent(requireNotNull(payload.contentBase64))
                if (payload.kind == RelayPayloadKind.CLIP_IMAGE) require(mime.startsWith("image/") && content.isNotEmpty())
            }
            RelayPayloadKind.RECEIPT -> {
                validUuid(requireNotNull(payload.receiptFor))
                require(payload.text == null && payload.name == null && payload.mime == null && payload.contentBase64 == null)
            }
        }
    }

    fun validateEnvelope(envelope: RelayEnvelope) {
        require(envelope.version == 1) { "Unsupported relay protocol" }
        validUuid(envelope.id)
        require(envelope.senderId.matches(Regex("[a-f0-9]{64}")) && envelope.recipientId.matches(Regex("[a-f0-9]{64}")))
        require(envelope.senderId != envelope.recipientId)
        require(envelope.sequence > 0 && envelope.createdAt >= 0 && envelope.expiresAt > envelope.createdAt)
        require(envelope.expiresAt - envelope.createdAt <= MESSAGE_TTL_MILLIS) { "Invalid envelope lifetime" }
        require(decodeBase64(envelope.ciphertext, MAX_CIPHERTEXT_BYTES).isNotEmpty())
        validateSignature(envelope.signature)
    }

    fun validateBundle(bundle: RelayKeyBundle) {
        require(decodeBase64(bundle.identityPublicKey, 256).isNotEmpty())
        require(decodeBase64(bundle.encryptionPublicKey, 8192).isNotEmpty())
        validateSignature(bundle.signature)
    }

    internal fun validateSignature(signature: String) { require(decodeBase64(signature, 80).size in 8..80) }
    fun decodeContent(encoded: String): ByteArray = decodeBase64(encoded, MAX_CONTENT_BYTES)
    internal fun decodeBase64(encoded: String, maxBytes: Int): ByteArray {
        require(encoded.length <= ((maxBytes.toLong() + 2) / 3 * 4)) { "Encoded relay field exceeds limit" }
        return Base64.getDecoder().decode(encoded).also { require(it.size <= maxBytes) }
    }
    private fun validUuid(id: String) { require(id.length == 36 && UUID.fromString(id).toString() == id) { "Invalid relay message id" } }
    private fun utf8(bytes: ByteArray, maxBytes: Int): String {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Relay document exceeds limit" }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) { throw IllegalArgumentException("Invalid relay UTF-8", failure) }
    }
    private fun strictText(bytes: ByteArray, maxBytes: Int): String {
        val text = utf8(bytes, maxBytes)
        var depth = 0; var quoted = false; var escaped = false
        var keyStart = -1; var expectingKey = false
        val keys = mutableSetOf<String>()
        for ((index, char) in text.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') {
                    quoted = false
                    if (keyStart >= 0) {
                        val key = json.decodeFromString<String>(text.substring(keyStart, index + 1))
                        require(keys.add(key)) { "Duplicate relay field" }
                        keyStart = -1; expectingKey = false
                    }
                }
            } else when (char) {
                '"' -> { quoted = true; if (expectingKey) keyStart = index }
                '{' -> { depth++; require(depth == 1); expectingKey = true }
                '[' -> throw IllegalArgumentException("Relay fields must be scalar")
                '}', ']' -> { depth--; require(depth >= 0) }
                ',' -> if (depth == 1) expectingKey = true
            }
        }
        require(depth == 0 && !quoted)
        return text
    }
}
