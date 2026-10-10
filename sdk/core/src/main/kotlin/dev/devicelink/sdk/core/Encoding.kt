package dev.devicelink.sdk.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Byte helpers shared by every v2 contract. Kept tiny so the Swift/Python ports stay obvious. */
object Encoding {
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Strict standard Base64 with padding; rejects non-canonical input and enforces [maxBytes]. */
    fun unb64(text: String, maxBytes: Int): ByteArray {
        require(text.length <= (maxBytes.toLong() + 2) / 3 * 4) { "Base64 field exceeds limit" }
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.size <= maxBytes && b64(bytes) == text) { "Invalid Base64 field" }
        return bytes
    }

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64url(text: String, expectedBytes: Int): ByteArray {
        require(text.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Base64url field" }
        val bytes = Base64.getUrlDecoder().decode(text)
        require(bytes.size == expectedBytes && b64url(bytes) == text) { "Invalid Base64url field" }
        return bytes
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.matches(Regex("[0-9a-f]*"))) { "Invalid hex" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** RFC 5869 HKDF-SHA256. An empty salt is replaced by 32 zero bytes, as the RFC specifies. */
    fun hkdf(ikm: ByteArray, info: String, length: Int, salt: ByteArray = ByteArray(0)): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Length-prefixed canonical byte strings; see `field(x)` in docs/protocol/link-v2.md. */
    fun canonical(write: Canonical.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { Canonical(it).write() }
        return buffer.toByteArray()
    }

    class Canonical internal constructor(private val out: DataOutputStream) {
        fun field(value: String) = field(value.toByteArray(Charsets.UTF_8))
        fun field(value: ByteArray) { out.writeInt(value.size); out.write(value) }
        fun u32(value: Int) = out.writeInt(value)
        fun i64(value: Long) = out.writeLong(value)
    }

    internal fun validDisplayText(value: String, maxChars: Int): Boolean =
        value.isNotBlank() && value.length <= maxChars &&
            value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}
