package dev.devicelink.sdk.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-time QR invitation. The secret lives only in the URL fragment, which browsers and the
 * relay landing page never send to the server.
 */
class PairingInvite private constructor(
    val relayUrl: String,
    private val secret: ByteArray,
    val inviterId: String,
) {
    val pairingId: String = Encoding.hex(Encoding.hkdf(secret, "DeviceLink/pairing-id/v2", 16))
    private val joinKey = Encoding.hkdf(secret, "DeviceLink/pairing-join/v2", 32)
    private val confirmKey = Encoding.hkdf(secret, "DeviceLink/pairing-confirm/v2", 32)

    private val fragment: String get() = "v2.${Encoding.b64url(secret)}.${Encoding.b64url(Encoding.unhex(inviterId))}"

    /** The QR content: an https link that opens the app (or the relay's forwarding page). */
    val uri: String get() = "$relayUrl/pair#$fragment"

    /** Custom-scheme form used by the relay landing page to hand the invite to the app. */
    val appUri: String get() = "devicelink://pair?relay=${URLEncoder.encode(relayUrl, "UTF-8")}#$fragment"

    fun sealJoin(bundle: KeyBundle): String = seal(joinKey, "join", bundle.encode())
    fun openJoin(sealed: String): KeyBundle = KeyBundle.decode(open(joinKey, "join", sealed))
    fun sealConfirm(bundle: KeyBundle): String = seal(confirmKey, "confirm", bundle.encode())
    fun openConfirm(sealed: String): KeyBundle = KeyBundle.decode(open(confirmKey, "confirm", sealed))

    private fun aad(label: String) = "DeviceLink/pairing/v2|$pairingId|$label".toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, label: String, plaintext: ByteArray): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(label))
        return Encoding.b64(nonce + cipher.doFinal(plaintext))
    }

    private fun open(key: ByteArray, label: String, sealed: String): ByteArray {
        val bytes = Encoding.unb64(sealed, 32 * 1024)
        require(bytes.size > 12 + 16) { "Sealed pairing data too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(aad(label))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    companion object {
        private val random = SecureRandom()

        fun create(relayUrl: String, inviterId: String): PairingInvite {
            require(inviterId.matches(Envelope.HEX64))
            return PairingInvite(RelayUrl.normalize(relayUrl, allowInsecure = true), ByteArray(32).also(random::nextBytes), inviterId)
        }

        /** Accepts the https QR form and the devicelink:// form. Returns null for anything else. */
        fun parse(text: String, allowInsecure: Boolean = false): PairingInvite? = try {
            val uri = URI(text.trim())
            val relay = when (uri.scheme) {
                "devicelink" -> {
                    require(uri.host == "pair" || uri.schemeSpecificPart.startsWith("//pair"))
                    val query = requireNotNull(uri.rawQuery)
                    val encoded = query.split('&').map { it.split('=', limit = 2) }
                        .single { it[0] == "relay" }[1]
                    URLDecoder.decode(encoded, "UTF-8")
                }
                "https", "http" -> {
                    require(uri.rawPath == "/pair" && uri.rawQuery == null)
                    URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
                }
                else -> error("Unsupported invite")
            }
            val parts = requireNotNull(uri.rawFragment).split('.')
            require(parts.size == 3 && parts[0] == "v2")
            PairingInvite(RelayUrl.normalize(relay, allowInsecure), Encoding.unb64url(parts[1], 32), Encoding.hex(Encoding.unb64url(parts[2], 32)))
        } catch (_: Exception) {
            null
        }
    }
}

/** Admin-issued setup link: relay URL plus optional enrollment token, for the first device. */
data class SetupLink(val relayUrl: String, val enrollmentToken: String) {
    val uri: String get() = "$relayUrl/setup#v2." + Encoding.b64url(enrollmentToken.toByteArray(Charsets.UTF_8))

    companion object {
        fun parse(text: String, allowInsecure: Boolean = false): SetupLink? = try {
            val uri = URI(text.trim())
            require(uri.scheme in setOf("https", "http") && uri.rawPath == "/setup" && uri.rawQuery == null)
            val fragment = requireNotNull(uri.rawFragment)
            require(fragment.startsWith("v2."))
            val encoded = fragment.removePrefix("v2.")
            require(encoded.matches(Regex("[A-Za-z0-9_-]*")) && encoded.length <= 700)
            val token = java.util.Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            require(token.none { it.isISOControl() })
            SetupLink(RelayUrl.normalize(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString(), allowInsecure), token)
        } catch (_: Exception) {
            null
        }
    }
}

object RelayUrl {
    /**
     * `https://host[:port]` only. Plain http is accepted for loopback / private LAN addresses when
     * [allowInsecure] is set (debug builds and local tunnels under test).
     */
    fun normalize(raw: String, allowInsecure: Boolean): String {
        val uri = URI(raw.trim().trimEnd('/'))
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && !uri.host.isNullOrBlank())
        require(uri.rawPath.isNullOrEmpty()) { "Relay URL must not contain a path" }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || (allowInsecure && scheme == "http" && isPrivateHost(uri.host))) { "Relay URL must use HTTPS" }
        return URI(scheme, null, uri.host.lowercase(), uri.port, null, null, null).toString()
    }

    private fun isPrivateHost(host: String): Boolean = host == "localhost" || host == "10.0.2.2" ||
        host.matches(Regex("127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"))
}
