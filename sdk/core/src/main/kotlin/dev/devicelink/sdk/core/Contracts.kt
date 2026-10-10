package dev.devicelink.sdk.core

import java.util.UUID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Limits shared with iOS and the relay. See docs/protocol/link-v2.md. */
object Limits {
    const val MAX_TEXT_BYTES = 64 * 1024
    const val MAX_CONTENT_BYTES = 10 * 1024 * 1024
    const val MAX_CIPHERTEXT_BYTES = 16 * 1024 * 1024
    const val MAX_ENVELOPE_LIFETIME_MILLIS = 3_600_000L
    const val DEFAULT_LIFETIME_MILLIS = 5 * 60_000L
    const val MAX_CLOCK_SKEW_MILLIS = 60_000L
    const val MAX_NAME_CHARS = 48
    const val MAX_FILE_NAME_CHARS = 120
    const val MAX_HTML_BYTES = 256 * 1024
    /** Files above [MAX_CONTENT_BYTES] are split into chunks of this size (one envelope each). */
    const val CHUNK_BYTES = 4 * 1024 * 1024
    const val MAX_PARTS = 10
    const val MAX_FILE_BYTES = CHUNK_BYTES * MAX_PARTS
    val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic")
}

@OptIn(ExperimentalSerializationApi::class)
internal val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Signed public keys a device shares with its peers during pairing. */
@Serializable
data class KeyBundle(
    val v: Int = 2,
    val identityKey: String,
    val encryptionKey: String,
    val name: String,
    val platform: String,
    val signature: String = "",
) {
    val id: String get() = Identity.deviceId(identityKeyDer())
    fun identityKeyDer(): ByteArray = Encoding.unb64(identityKey, 128)
    fun encryptionKeyRaw(): ByteArray = Encoding.unb64(encryptionKey, 32).also { require(it.size == 32) }

    fun signedBytes(): ByteArray = Encoding.canonical {
        field("DeviceLink/key-bundle/v2")
        field(identityKeyDer())
        field(encryptionKeyRaw())
        field(name)
        field(platform)
    }

    fun validate() {
        require(v == 2) { "Unsupported bundle version" }
        Identity.publicKey(identityKeyDer())
        encryptionKeyRaw()
        require(Encoding.validDisplayText(name, Limits.MAX_NAME_CHARS)) { "Invalid device name" }
        require(platform.matches(Regex("[a-z]{1,16}"))) { "Invalid platform" }
    }

    fun verified(): Boolean = try {
        validate()
        Identity.verify(identityKeyDer(), signedBytes(), Encoding.unb64(signature, 80))
    } catch (_: Exception) {
        false
    }

    fun encode(): ByteArray = wireJson.encodeToString(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun create(identity: DeviceIdentity, encryption: EncryptionKeyPair, name: String, platform: String): KeyBundle {
            val unsigned = KeyBundle(identityKey = Encoding.b64(identity.publicKeyDer),
                encryptionKey = Encoding.b64(encryption.publicKey), name = name.trim().take(Limits.MAX_NAME_CHARS), platform = platform)
            unsigned.validate()
            val signed = unsigned.copy(signature = Encoding.b64(identity.sign(unsigned.signedBytes())))
            require(signed.verified()) { "Signer does not match identity" }
            return signed
        }

        fun decode(bytes: ByteArray): KeyBundle {
            require(bytes.size <= 16 * 1024)
            val bundle = wireJson.decodeFromString<KeyBundle>(bytes.toString(Charsets.UTF_8))
            require(bundle.verified()) { "Invalid key bundle" }
            return bundle
        }
    }
}

/** Public routing metadata stored by the relay. Field set must match server validation exactly. */
@Serializable
data class Envelope(
    val version: Int = 2,
    val id: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val sequence: Long,
    val ciphertext: String,
    val signature: String,
) {
    fun context(): ByteArray = Encoding.canonical {
        field("DeviceLink/envelope-context/v2")
        u32(version)
        field(id); field(senderId); field(recipientId)
        i64(createdAt); i64(expiresAt); i64(sequence)
    }

    fun signedBytes(): ByteArray = Encoding.canonical {
        field("DeviceLink/envelope/v2")
        field(context())
        field(Encoding.unb64(ciphertext, Limits.MAX_CIPHERTEXT_BYTES))
    }

    fun validate() {
        require(version == 2) { "Unsupported envelope version" }
        require(UUID.fromString(id).toString() == id) { "Invalid envelope id" }
        require(senderId.matches(HEX64) && recipientId.matches(HEX64) && senderId != recipientId) { "Invalid routing" }
        require(sequence > 0 && createdAt >= 0 && expiresAt > createdAt) { "Invalid envelope metadata" }
        require(expiresAt - createdAt <= Limits.MAX_ENVELOPE_LIFETIME_MILLIS) { "Invalid envelope lifetime" }
        require(Encoding.unb64(ciphertext, Limits.MAX_CIPHERTEXT_BYTES).size >= 48) { "Invalid ciphertext" }
        require(Encoding.unb64(signature, 80).size >= 8) { "Invalid signature" }
    }

    fun encode(): ByteArray { validate(); return wireJson.encodeToString(this).toByteArray(Charsets.UTF_8) }

    companion object {
        internal val HEX64 = Regex("[0-9a-f]{64}")
        fun decode(text: String): Envelope = wireJson.decodeFromString<Envelope>(text).also { it.validate() }
        fun decodeList(bytes: ByteArray): List<Envelope> =
            wireJson.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(bytes.toString(Charsets.UTF_8))
                .map { decode(it.toString()) }
    }
}

@Serializable
enum class PayloadKind {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("file") FILE,
    @SerialName("receipt") RECEIPT,
    @SerialName("unlink") UNLINK,
}

@Serializable
enum class ReceiptStatus {
    /** Written to the receiver's clipboard. */
    @SerialName("copied") COPIED,
    /** Stored or announced, but not placed on the clipboard (for example a file). */
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
}

/** Every field is end-to-end encrypted. */
@Serializable
data class Payload(
    val kind: PayloadKind,
    val text: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val data: String? = null,
    val receiptFor: String? = null,
    val status: ReceiptStatus? = null,
    val sentAt: Long? = null,
    /** Rich-text alternative for [text] (text stays the plain fallback). */
    val html: String? = null,
    /** Hide previews and keep it off clipboard history / let it expire where the platform can. */
    val sensitive: Boolean? = null,
    /** Chunked file: all parts share [group] (also the item ID receipts refer to). */
    val group: String? = null,
    val part: Int? = null,
    val parts: Int? = null,
    val size: Long? = null,
) {
    fun dataBytes(): ByteArray? = data?.let { Encoding.unb64(it, Limits.MAX_CONTENT_BYTES) }
    val isChunk: Boolean get() = group != null

    fun validate() {
        when (kind) {
            PayloadKind.TEXT -> {
                require(text != null && text.toByteArray(Charsets.UTF_8).size in 1..Limits.MAX_TEXT_BYTES) { "Invalid text" }
                require(name == null && mime == null && data == null && receiptFor == null && status == null)
                require(html == null || html.toByteArray(Charsets.UTF_8).size in 1..Limits.MAX_HTML_BYTES) { "Invalid HTML" }
                require(group == null && part == null && parts == null && size == null)
            }
            PayloadKind.IMAGE, PayloadKind.FILE -> {
                require(text == null && receiptFor == null && status == null && html == null)
                if (group != null || part != null || parts != null || size != null) {
                    require(UUID.fromString(requireNotNull(group)).toString() == group) { "Invalid chunk group" }
                    require(requireNotNull(parts) in 2..Limits.MAX_PARTS && requireNotNull(part) in 0 until parts) { "Invalid chunk index" }
                    require(requireNotNull(size) in 1..Limits.MAX_FILE_BYTES.toLong()) { "Invalid file size" }
                    require(requireNotNull(dataBytes()).size <= Limits.CHUNK_BYTES) { "Chunk too large" }
                }
                require(validFileName(requireNotNull(name))) { "Invalid file name" }
                require(requireNotNull(mime).length <= 127 && mime.matches(MIME)) { "Invalid MIME type" }
                require(requireNotNull(dataBytes()).isNotEmpty()) { "Empty content" }
                if (kind == PayloadKind.IMAGE) require(mime in Limits.IMAGE_MIMES) { "Unsupported image type" }
            }
            PayloadKind.RECEIPT -> {
                require(UUID.fromString(requireNotNull(receiptFor)).toString() == receiptFor && status != null)
                require(text == null && name == null && mime == null && data == null && group == null)
            }
            PayloadKind.UNLINK -> require(text == null && name == null && mime == null && data == null && receiptFor == null && status == null)
        }
    }

    fun encode(): ByteArray { validate(); return wireJson.encodeToString(this).toByteArray(Charsets.UTF_8) }

    companion object {
        private val MIME = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        fun validFileName(name: String) = Encoding.validDisplayText(name, Limits.MAX_FILE_NAME_CHARS) &&
            '/' !in name && '\\' !in name && name != "." && name != ".."

        fun decode(bytes: ByteArray): Payload =
            wireJson.decodeFromString<Payload>(bytes.toString(Charsets.UTF_8)).also { it.validate() }

        fun text(text: String, now: Long, html: String? = null, sensitive: Boolean = false) =
            Payload(PayloadKind.TEXT, text = text, sentAt = now, html = html, sensitive = sensitive.takeIf { it })

        /** Splits large content into ordered chunk payloads sharing one group ID. */
        fun chunks(kind: PayloadKind, name: String, mime: String, bytes: ByteArray, now: Long, group: String = UUID.randomUUID().toString()): List<Payload> {
            require(kind == PayloadKind.IMAGE || kind == PayloadKind.FILE)
            require(bytes.size in 1..Limits.MAX_FILE_BYTES) { "File is larger than ${Limits.MAX_FILE_BYTES / (1024 * 1024)} MB" }
            val parts = (bytes.size + Limits.CHUNK_BYTES - 1) / Limits.CHUNK_BYTES
            return List(parts) { index ->
                val slice = bytes.copyOfRange(index * Limits.CHUNK_BYTES, minOf(bytes.size, (index + 1) * Limits.CHUNK_BYTES))
                Payload(kind, name = name, mime = mime, data = Encoding.b64(slice), sentAt = now, group = group,
                    part = index, parts = parts, size = bytes.size.toLong())
            }
        }
        fun image(name: String, mime: String, bytes: ByteArray, now: Long) =
            Payload(PayloadKind.IMAGE, name = name, mime = mime, data = Encoding.b64(bytes), sentAt = now)
        fun file(name: String, mime: String, bytes: ByteArray, now: Long) =
            Payload(PayloadKind.FILE, name = name, mime = mime, data = Encoding.b64(bytes), sentAt = now)
        fun receipt(id: String, status: ReceiptStatus) = Payload(PayloadKind.RECEIPT, receiptFor = id, status = status)
        fun unlink() = Payload(PayloadKind.UNLINK)
    }
}

/** Seals and opens envelopes between pinned key bundles. */
class EnvelopeCrypto(
    private val identity: DeviceIdentity,
    private val encryption: EncryptionKeyPair,
) {
    val deviceId: String = identity.deviceId

    fun seal(
        payload: Payload,
        recipient: KeyBundle,
        now: Long,
        sequence: Long,
        lifetimeMillis: Long = Limits.DEFAULT_LIFETIME_MILLIS,
        id: String = UUID.randomUUID().toString(),
    ): Envelope {
        require(recipient.verified()) { "Recipient bundle is not verified" }
        require(lifetimeMillis in 1..Limits.MAX_ENVELOPE_LIFETIME_MILLIS)
        val plaintext = payload.encode()
        val metadata = Envelope(id = id, senderId = deviceId, recipientId = recipient.id, createdAt = now,
            expiresAt = now + lifetimeMillis, sequence = sequence, ciphertext = "", signature = "")
        val ciphertext = Hpke.seal(recipient.encryptionKeyRaw(), metadata.context(), plaintext)
        val unsigned = metadata.copy(ciphertext = Encoding.b64(ciphertext))
        return unsigned.copy(signature = Encoding.b64(identity.sign(unsigned.signedBytes()))).also { it.validate() }
    }

    /** Authenticates against the pinned sender bundle before decrypting. Does not record replay state. */
    fun open(envelope: Envelope, sender: KeyBundle, now: Long): Payload {
        envelope.validate()
        require(envelope.senderId == sender.id && envelope.recipientId == deviceId) { "Envelope routing mismatch" }
        require(envelope.expiresAt > now) { "Envelope expired" }
        require(envelope.createdAt - now <= Limits.MAX_CLOCK_SKEW_MILLIS) { "Envelope is from the future" }
        require(Identity.verify(sender.identityKeyDer(), envelope.signedBytes(), Encoding.unb64(envelope.signature, 80))) {
            "Invalid envelope signature"
        }
        val plaintext = Hpke.open(encryption, envelope.context(), Encoding.unb64(envelope.ciphertext, Limits.MAX_CIPHERTEXT_BYTES))
        return Payload.decode(plaintext)
    }
}
