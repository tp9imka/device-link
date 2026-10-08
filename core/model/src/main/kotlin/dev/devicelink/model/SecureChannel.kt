package dev.devicelink.model

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One ephemeral key per connection. Verify signed Hello before using the resulting channel. */
class EphemeralAgreement {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    val publicKey: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    private var established = false

    @Synchronized
    fun establish(localHello: WireMessage.Hello, remoteHello: WireMessage.Hello): SecureChannel {
        require(!established) { "Agreement has already established a channel" }
        established = true
        require(localHello.ephemeralKey == publicKey) { "Local agreement key mismatch" }
        require(remoteHello.ephemeralKey.isNotEmpty()) { "Peer omitted agreement key" }
        val transcript = TrustProof.canonicalTranscript(localHello, remoteHello)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(TrustProof.key(remoteHello.ephemeralKey), true)
            generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(transcript)
        val prk = hmac(salt, shared)
        shared.fill(0)
        val lowToHigh = expand(prk, "DeviceLink/v1/low-to-high")
        val highToLow = expand(prk, "DeviceLink/v1/high-to-low")
        prk.fill(0)
        val localIsLow = TrustProof.fingerprint(localHello.publicKey) < TrustProof.fingerprint(remoteHello.publicKey)
        return if (localIsLow) SecureChannel(lowToHigh, highToLow) else SecureChannel(highToLow, lowToHigh)
    }

    private fun expand(prk: ByteArray, label: String): ByteArray = hmac(prk, label.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
}

/** Ordered AES-256-GCM records. Independent direction keys prevent reflected records being accepted. */
class SecureChannel internal constructor(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private val sendLock = Any()
    private val receiveLock = Any()

    fun encrypt(plaintext: ByteArray): ByteArray = synchronized(sendLock) {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES) { "Invalid clear record size" }
        require(sendSequence < Long.MAX_VALUE) { "Session sequence exhausted" }
        val sequence = sendSequence
        val header = ByteBuffer.allocate(8).putLong(sequence).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sequence)))
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintext)
        sendSequence++
        header + ciphertext
    }

    fun decrypt(frame: ByteArray): ByteArray = synchronized(receiveLock) {
        require(frame.size in 25..MAX_FRAME_BYTES) { "Invalid encrypted record size" }
        require(receiveSequence < Long.MAX_VALUE) { "Session sequence exhausted" }
        val sequence = ByteBuffer.wrap(frame, 0, 8).long
        require(sequence == receiveSequence) { "Unexpected record sequence" }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(sequence)))
            cipher.updateAAD(frame, 0, 8)
            cipher.doFinal(frame, 8, frame.size - 8)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid encrypted record", error)
        }
        receiveSequence++
        plaintext
    }

    private fun nonce(sequence: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()

    companion object {
        const val MAX_PLAINTEXT_BYTES = 65_536
        const val MAX_FRAME_BYTES = MAX_PLAINTEXT_BYTES + 24
    }
}
