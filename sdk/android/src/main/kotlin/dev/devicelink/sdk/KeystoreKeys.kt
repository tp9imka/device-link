package dev.devicelink.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.devicelink.sdk.core.DeviceIdentity
import dev.devicelink.sdk.core.EncryptionKeyPair
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** P-256 identity created on first launch; the private key never leaves Android Keystore. */
internal class KeystoreIdentity : DeviceIdentity {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
    }

    override val publicKeyDer: ByteArray = keyStore.getCertificate(ALIAS).publicKey.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore.getKey(ALIAS, null) as PrivateKey); update(data); sign()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "devicelink.v2.identity"

        fun delete() { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/**
 * The X25519 HPKE key cannot live inside Keystore on API 26, so it is stored wrapped with a
 * Keystore AES-256-GCM key in `noBackupFilesDir`, which Android never includes in cloud backups or
 * device-to-device transfers (the wrapping key cannot leave this device anyway).
 *
 * Linked peers pinned this key's public half. If it cannot be read back, a replacement would make
 * every later item from them undecryptable while both sides still look linked, so a fresh key is
 * only created on a device without links. Otherwise the result is [Loaded.lost]: the caller gets a
 * throwaway key that is never saved, the unreadable file is left in place (a transient Keystore
 * failure recovers on the next start), and the user is asked to reset and link again.
 */
internal object WrappedEncryptionKey {
    private const val ALIAS = "devicelink.v2.wrap"

    class Loaded(val pair: EncryptionKeyPair, val lost: Boolean)

    fun load(context: Context, hasLinks: Boolean): Loaded =
        load(File(context.noBackupFilesDir, "devicelink/encryption.key"), hasLinks, ::wrap, ::unwrap)

    internal fun load(file: File, hasLinks: Boolean, wrap: (ByteArray) -> ByteArray, unwrap: (ByteArray) -> ByteArray): Loaded {
        if (file.isFile) runCatching { return Loaded(EncryptionKeyPair(unwrap(file.readBytes())), false) }
        // No peer has pinned anything yet, so a new key breaks nothing; with links it would.
        if (hasLinks) return Loaded(EncryptionKeyPair.generate(), lost = true)
        val pair = EncryptionKeyPair.generate()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "encryption.key.tmp")
        temporary.writeBytes(wrap(pair.privateKeyBytes()))
        check(temporary.renameTo(file)) { "Cannot store encryption key" }
        return Loaded(pair, false)
    }

    fun delete(context: Context) {
        File(context.noBackupFilesDir, "devicelink/encryption.key").delete()
        KeyStore.getInstance(KeystoreIdentity.PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KeystoreIdentity.PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreIdentity.PROVIDER).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(secret)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }
}
