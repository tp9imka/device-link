package dev.devicelink.model

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecureChannelTest {
    private fun hello(agreement: EphemeralAgreement): WireMessage.Hello {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return WireMessage.Hello(
            Base64.getEncoder().encodeToString(pair.public.encoded),
            Base64.getEncoder().encodeToString(ByteArray(32).apply { SecureRandom().nextBytes(this) }),
            agreement.publicKey,
        )
    }
    private fun peers(): Pair<SecureChannel, SecureChannel> {
        val a = EphemeralAgreement(); val b = EphemeralAgreement()
        val ah = hello(a); val bh = hello(b)
        assertEquals(TrustProof.authenticationCode(ah, bh), TrustProof.authenticationCode(bh, ah))
        return a.establish(ah, bh) to b.establish(bh, ah)
    }

    @Test fun `encrypted records round trip independently in both directions`() {
        val (a, b) = peers()
        val forward = "clipboard from first phone".toByteArray()
        val reverse = ByteArray(SecureChannel.MAX_PLAINTEXT_BYTES) { it.toByte() }
        val record = a.encrypt(forward)
        assertFalse(record.contentEquals(forward))
        assertArrayEquals(forward, b.decrypt(record))
        assertArrayEquals(reverse, a.decrypt(b.encrypt(reverse)))
        assertArrayEquals(forward, b.decrypt(a.encrypt(forward)))
    }

    @Test fun `tampering replay out of order and reflection are rejected`() {
        val (a, b) = peers()
        val first = a.encrypt(byteArrayOf(1)); val second = a.encrypt(byteArrayOf(2))
        reject { b.decrypt(second) }
        reject { a.decrypt(first) }
        val tampered = first.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        reject { b.decrypt(tampered) }
        assertArrayEquals(byteArrayOf(1), b.decrypt(first))
        reject { b.decrypt(first) }
        assertArrayEquals(byteArrayOf(2), b.decrypt(second))
        reject { a.encrypt(ByteArray(SecureChannel.MAX_PLAINTEXT_BYTES + 1)) }
        reject { a.decrypt(ByteArray(SecureChannel.MAX_FRAME_BYTES + 1)) }
    }

    @Test fun `a new handshake cannot decrypt previous session records`() {
        val (first, _) = peers()
        val (_, freshReceiver) = peers()
        reject { freshReceiver.decrypt(first.encrypt(byteArrayOf(1))) }
    }

    @Test fun `ephemeral substitution changes proof transcript and keys`() {
        val a = EphemeralAgreement(); val b = EphemeralAgreement(); val attacker = EphemeralAgreement()
        val ah = hello(a); val bh = hello(b)
        assertFalse(TrustProof.transcript(ah, bh).contentEquals(TrustProof.transcript(ah, bh.copy(ephemeralKey = attacker.publicKey))))
        val sender = a.establish(ah, bh.copy(ephemeralKey = attacker.publicKey))
        val receiver = b.establish(bh, ah)
        reject { receiver.decrypt(sender.encrypt(byteArrayOf(1))) }
        reject { a.establish(ah.copy(ephemeralKey = attacker.publicKey), bh) }
    }

    @Test fun `agreement cannot reset record counters by establishing twice`() {
        val a = EphemeralAgreement(); val b = EphemeralAgreement()
        val ah = hello(a); val bh = hello(b)
        a.establish(ah, bh)
        reject { a.establish(ah, bh) }
    }

    private fun reject(action: () -> Unit) = assertThrows(IllegalArgumentException::class.java, action)
}
