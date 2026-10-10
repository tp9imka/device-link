package dev.devicelink.sdk.core

import com.google.crypto.tink.subtle.X25519
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the production (Tink) HPKE is plain RFC 9180 with info=context and empty AAD, which is
 * what CryptoKit's HPKE.Sender/Recipient and the Python reference produce.
 */
class HpkeTest {
    @Test fun `reference implementation matches RFC 9180 A_2_1 vector`() {
        val skR = Encoding.unhex("8057991eef8f1f1af18f4a9491d16a1ce333f695d4db8e38da75975c4478e0fb")
        val enc = Encoding.unhex("1afa08d3dec047a643885163f1180476fa7ddb54c6a8029ea33f95796bf2ac4a")
        val info = Encoding.unhex("4f6465206f6e2061204772656369616e2055726e")
        val ct = Encoding.unhex("1c5250d8034ec2b784ba2cfd69dbdb8af406cfe3ff938e131f0def8c8b60b4db21993c62ce81883d2dd1b51a28")
        val plaintext = ReferenceHpke.open(skR, enc, info, Encoding.unhex("436f756e742d30"), ct)
        assertEquals("Beauty is truth, truth beauty", plaintext.toString(Charsets.UTF_8))
    }

    @Test fun `tink output opens with the reference and vice versa`() {
        val recipient = EncryptionKeyPair.generate()
        val info = "context".toByteArray()
        val message = "clipboard secret".toByteArray()
        val sealed = Hpke.seal(recipient.publicKey, info, message)
        assertArrayEquals(message, ReferenceHpke.open(recipient.privateKeyBytes(), sealed.copyOf(32), info, ByteArray(0), sealed.copyOfRange(32, sealed.size)))
        val reference = ReferenceHpke.seal(recipient.publicKey, info, message)
        assertArrayEquals(message, Hpke.open(recipient, info, reference))
    }

    @Test(expected = Exception::class) fun `wrong info fails`() {
        val recipient = EncryptionKeyPair.generate()
        Hpke.open(recipient, "b".toByteArray(), Hpke.seal(recipient.publicKey, "a".toByteArray(), byteArrayOf(1)))
    }
}

/** Independent minimal RFC 9180 base-mode implementation used only to cross-check Tink. */
internal object ReferenceHpke {
    private val kemSuite = "KEM".toByteArray() + byteArrayOf(0, 0x20)
    private val suite = "HPKE".toByteArray() + byteArrayOf(0, 0x20, 0, 1, 0, 3)

    fun seal(pkR: ByteArray, info: ByteArray, plaintext: ByteArray): ByteArray {
        val skE = X25519.generatePrivateKey()
        val enc = X25519.publicFromPrivate(skE)
        val (key, nonce) = schedule(sharedSecret(X25519.computeSharedSecret(skE, pkR), enc, pkR), info)
        return enc + aead(Cipher.ENCRYPT_MODE, key, nonce, ByteArray(0), plaintext)
    }

    fun open(skR: ByteArray, enc: ByteArray, info: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val pkR = X25519.publicFromPrivate(skR)
        val (key, nonce) = schedule(sharedSecret(X25519.computeSharedSecret(skR, enc), enc, pkR), info)
        return aead(Cipher.DECRYPT_MODE, key, nonce, aad, ct)
    }

    private fun sharedSecret(dh: ByteArray, enc: ByteArray, pkR: ByteArray): ByteArray {
        val prk = extract(ByteArray(0), kemSuite, "eae_prk", dh)
        return expand(prk, kemSuite, "shared_secret", enc + pkR, 32)
    }

    private fun schedule(shared: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val context = byteArrayOf(0) + extract(ByteArray(0), suite, "psk_id_hash", ByteArray(0)) + extract(ByteArray(0), suite, "info_hash", info)
        val secret = extract(shared, suite, "secret", ByteArray(0))
        return expand(secret, suite, "key", context, 32) to expand(secret, suite, "base_nonce", context, 12)
    }

    private fun extract(salt: ByteArray, suite: ByteArray, label: String, ikm: ByteArray) =
        hmac(if (salt.isEmpty()) ByteArray(32) else salt, "HPKE-v1".toByteArray() + suite + label.toByteArray() + ikm)

    private fun expand(prk: ByteArray, suite: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeled = byteArrayOf((length shr 8).toByte(), length.toByte()) + "HPKE-v1".toByteArray() + suite + label.toByteArray() + info
        var t = ByteArray(0); var out = ByteArray(0); var i = 1
        while (out.size < length) { t = hmac(prk, t + labeled + byteArrayOf(i++.toByte())); out += t }
        return out.copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)); updateAAD(aad); doFinal(input)
        }
}
