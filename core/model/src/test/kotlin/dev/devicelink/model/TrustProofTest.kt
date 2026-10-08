package dev.devicelink.model

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TrustProofTest {
    private val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    private fun hello(key: KeyPair, seed: Byte) = WireMessage.Hello(
        Base64.getEncoder().encodeToString(key.public.encoded),
        Base64.getEncoder().encodeToString(ByteArray(32) { seed }),
    )
    private fun sign(key: KeyPair, local: WireMessage.Hello, remote: WireMessage.Hello): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(TrustProof.transcript(local, remote))
            Base64.getEncoder().encodeToString(sign())
        }

    @Test fun `proof binds both identities nonces and direction`() {
        val alice = generator.generateKeyPair()
        val bob = generator.generateKeyPair()
        val mallory = generator.generateKeyPair()
        val a = hello(alice, 1)
        val b = hello(bob, 2)
        val proof = sign(alice, a, b)
        assertTrue(TrustProof.verify(a, b, proof))
        assertFalse(TrustProof.verify(a, hello(bob, 3), proof))
        assertFalse(TrustProof.verify(hello(alice, 4), b, proof))
        assertFalse(TrustProof.verify(a, hello(mallory, 2), proof))
        assertFalse(TrustProof.verify(hello(mallory, 1), b, proof))
        assertFalse(TrustProof.verify(b, a, proof))
        assertFalse(TrustProof.verify(a, b, "not base64"))
    }

    @Test fun `commitment fixes entire hello before code comparison`() {
        val key = generator.generateKeyPair()
        val initial = hello(key, 1).copy(ephemeralKey = EphemeralAgreement().publicKey)
        assertEquals(TrustProof.commitment(initial), TrustProof.commitment(initial.copy()))
        assertNotEquals(TrustProof.commitment(initial), TrustProof.commitment(initial.copy(nonce = hello(key, 2).nonce)))
        assertNotEquals(TrustProof.commitment(initial), TrustProof.commitment(initial.copy(ephemeralKey = EphemeralAgreement().publicKey)))
    }

    @Test fun `fingerprint stable across nonce changes but changes with key`() {
        val key = generator.generateKeyPair()
        assertEquals(TrustProof.fingerprint(hello(key, 1).publicKey), TrustProof.fingerprint(hello(key, 2).publicKey))
        assertNotEquals(TrustProof.fingerprint(hello(key, 1).publicKey), TrustProof.fingerprint(hello(generator.generateKeyPair(), 1).publicKey))
    }
}
