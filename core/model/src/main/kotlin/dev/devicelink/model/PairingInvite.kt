package dev.devicelink.model

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A QR pins identity; the discovery address is only an untrusted connection hint. */
@Serializable
data class PairingInvite(val fingerprint: String, val name: String = "Android", val address: String = "") {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.encodeToString(validated()).toByteArray(Charsets.UTF_8))

    private fun validated(): PairingInvite {
        require(fingerprint.matches(Regex("[a-fA-F0-9]{64}")))
        require(name.isNotBlank() && name.length <= 48 && name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
        require(address.isEmpty() || address.matches(Regex("(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}")))
        return copy(fingerprint = fingerprint.lowercase(), address = address.lowercase())
    }

    companion object {
        private const val PREFIX = "dl1:"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

        fun decode(code: String): PairingInvite? = try {
            require(code.startsWith(PREFIX) && code.length < 2048)
            val bytes = Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
            val raw = bytes.toString(Charsets.UTF_8)
            json.decodeFromString<PairingInvite>(raw).validated()
        } catch (_: Exception) {
            null
        }
    }
}
