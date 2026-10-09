package dev.devicelink.sdk.core

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes

/**
 * RFC 9180 base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / ChaCha20-Poly1305, single shot,
 * `info` = context, empty AAD. Output is `enc || ciphertext`, identical to CryptoKit's
 * `HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly` sender output.
 */
object Hpke {
    private val parameters: HpkeParameters by lazy {
        HybridConfig.register()
        HpkeParameters.builder()
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .build()
    }

    fun seal(recipientPublicKey: ByteArray, info: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32)
        val key = HpkePublicKey.create(parameters, Bytes.copyFrom(recipientPublicKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(plaintext, info)
    }

    fun open(recipient: EncryptionKeyPair, info: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 32 + 16) { "HPKE ciphertext too short" }
        val public = HpkePublicKey.create(parameters, Bytes.copyFrom(recipient.publicKey), null)
        val private = HpkePrivateKey.create(public, SecretBytes.copyFrom(recipient.privateKeyBytes(), InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(private).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(ciphertext, info)
    }
}
