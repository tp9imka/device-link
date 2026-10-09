package dev.devicelink.sdk.core

import com.google.crypto.tink.subtle.X25519
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A device's long-term P-256 signing identity. Platform adapters keep the private key in
 * Android Keystore or the iOS Secure Enclave; the SDK only needs the public key and a signer.
 */
interface DeviceIdentity {
    /** X.509 SubjectPublicKeyInfo DER of the P-256 public key. */
    val publicKeyDer: ByteArray

    /** DER ECDSA-SHA256 signature over [data]. */
    fun sign(data: ByteArray): ByteArray

    val deviceId: String get() = Identity.deviceId(publicKeyDer)
}

object Identity {
    fun deviceId(publicKeyDer: ByteArray): String = Encoding.hex(Encoding.sha256(publicKeyDer))

    fun verify(publicKeyDer: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        require(signature.size in 8..80)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKeyDer)); update(data); verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    /** Parses and checks the key is canonical P-256, so device IDs cannot be aliased. */
    fun publicKey(der: ByteArray): ECPublicKey {
        require(der.size in 64..128) { "Invalid identity key length" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "Identity key must be P-256" }
        require(key.encoded.contentEquals(der)) { "Identity key is not canonical" }
        return key
    }
}

/** In-memory software identity for JVM clients and tests. Android uses a Keystore-backed adapter. */
class SoftwareIdentity(private val keyPair: KeyPair = generate()) : DeviceIdentity {
    override val publicKeyDer: ByteArray get() = keyPair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(data); sign()
    }

    val privateKeyPkcs8: ByteArray get() = keyPair.private.encoded

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun fromPkcs8(privateKey: ByteArray, publicKeyDer: ByteArray): SoftwareIdentity {
            val factory = KeyFactory.getInstance("EC")
            val private = factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKey))
            return SoftwareIdentity(KeyPair(Identity.publicKey(publicKeyDer), private))
        }
    }
}

/** Raw X25519 key pair used as the HPKE recipient key. */
class EncryptionKeyPair(privateKey: ByteArray) {
    private val secret = privateKey.copyOf()
    val publicKey: ByteArray = X25519.publicFromPrivate(secret)

    init { require(secret.size == 32) }

    /** Raw private scalar; callers must store it encrypted (Keystore / Keychain). */
    fun privateKeyBytes(): ByteArray = secret.copyOf()

    companion object {
        fun generate(): EncryptionKeyPair = EncryptionKeyPair(X25519.generatePrivateKey())
    }
}
