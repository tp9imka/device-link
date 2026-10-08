package dev.devicelink.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64

/** Private key operations stay in the Android Keystore adapter. Nonces must be fresh per connection. */
object TrustProof {
    /** Exchange and lock commitments before revealing Hello to prevent offline SAS nonce grinding. */
    fun commitment(hello: WireMessage.Hello): String = MessageDigest.getInstance("SHA-256")
        .digest(WireCodec.encode(hello))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fingerprint(publicKeyBase64: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key(publicKeyBase64).encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Ordered and length-prefixed transcript prevents reflection, identity substitution and replay. */
    fun transcript(signerHello: WireMessage.Hello, verifierHello: WireMessage.Hello): ByteArray {
        WireCodec.validate(signerHello)
        WireCodec.validate(verifierHello)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            listOf(
                "DeviceLink/identity-proof/v1".toByteArray(Charsets.UTF_8),
                key(signerHello.publicKey).encoded,
                signerHello.displayName.toByteArray(Charsets.UTF_8),
                Base64.getDecoder().decode(signerHello.nonce),
                signerHello.ephemeralKey.takeIf { it.isNotEmpty() }?.let { key(it).encoded } ?: byteArrayOf(),
                key(verifierHello.publicKey).encoded,
                verifierHello.displayName.toByteArray(Charsets.UTF_8),
                Base64.getDecoder().decode(verifierHello.nonce),
                verifierHello.ephemeralKey.takeIf { it.isNotEmpty() }?.let { key(it).encoded } ?: byteArrayOf(),
            ).forEach { field -> data.writeInt(field.size); data.write(field) }
        }
        return output.toByteArray()
    }

    fun verify(signerHello: WireMessage.Hello, verifierHello: WireMessage.Hello, signatureBase64: String): Boolean = try {
        WireCodec.validate(WireMessage.Proof(signatureBase64))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key(signerHello.publicKey))
            update(transcript(signerHello, verifierHello))
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (_: Exception) {
        false
    }

    /** Display on both devices; users must compare all digits before first-time trust. */
    fun authenticationCode(a: WireMessage.Hello, b: WireMessage.Hello): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalTranscript(a, b))
        val unsigned = java.nio.ByteBuffer.wrap(digest).int.toLong() and 0xffffffffL
        return (unsigned % 1_000_000).toString().padStart(6, '0')
    }

    internal fun canonicalTranscript(a: WireMessage.Hello, b: WireMessage.Hello): ByteArray {
        val aId = fingerprint(a.publicKey)
        val bId = fingerprint(b.publicKey)
        require(aId != bId) { "Cannot link identical identities" }
        return if (aId < bId) transcript(a, b) else transcript(b, a)
    }

    internal fun key(encoded: String): ECPublicKey {
        require(encoded.length <= 344) { "Invalid identity key length" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded))) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "Key requires P-256 curve" }
        return key
    }
}
