package dev.devicelink.model

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Tink HPKE encrypts content; the existing device identity authenticates keys and envelopes. */
class RelayCrypto(
    private val privateKeyset: KeysetHandle,
    val localBundle: RelayKeyBundle,
    private val signer: (ByteArray) -> String,
) {
    init {
        initialize()
        require(verifyBundle(localBundle)) { "Invalid local relay key bundle" }
        require(exportPublicKey(privateKeyset) == localBundle.encryptionPublicKey) { "Relay key bundle does not match private key" }
    }

    fun encrypt(
        payload: RelayPayload,
        recipientBundle: RelayKeyBundle,
        nowMillis: Long,
        sequence: Long,
        id: String = UUID.randomUUID().toString(),
    ): RelayEnvelope {
        require(verifyBundle(recipientBundle)) { "Invalid recipient relay key bundle" }
        require(nowMillis >= 0 && nowMillis <= Long.MAX_VALUE - RelayCodec.MESSAGE_TTL_MILLIS)
        val plaintext = RelayCodec.encodePayload(payload)
        val metadata = RelayEnvelope(id, localBundle.id, recipientBundle.id, nowMillis,
            nowMillis + RelayCodec.ttlMillis(payload.kind), sequence, "AA==", "AAAAAAAAAAA=")
        RelayCodec.validateEnvelope(metadata)
        val primitive = importPublicKey(recipientBundle.encryptionPublicKey)
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        val ciphertext = primitive.encrypt(plaintext, envelopeContext(metadata))
        val unsigned = metadata.copy(ciphertext = Base64.getEncoder().encodeToString(ciphertext))
        val envelope = unsigned.copy(signature = signer(envelopeSignedBytes(unsigned)))
        RelayCodec.validateEnvelope(envelope)
        require(verifyIdentity(localBundle.identityPublicKey, envelopeSignedBytes(envelope), envelope.signature)) { "Signer does not match local identity" }
        return envelope
    }

    /** Never records delivery. Persist ID deduplication after successful application handling. */
    fun decrypt(
        envelope: RelayEnvelope,
        pinnedSenderBundle: RelayKeyBundle,
        nowMillis: Long,
        replayGuard: RelayReplayGuard,
    ): RelayPayload {
        RelayCodec.validateEnvelope(envelope)
        require(verifyBundle(pinnedSenderBundle)) { "Invalid sender relay key bundle" }
        require(envelope.senderId == pinnedSenderBundle.id && envelope.recipientId == localBundle.id) { "Relay identity mismatch" }
        require(nowMillis >= 0 && envelope.expiresAt > nowMillis) { "Expired relay envelope" }
        require(envelope.createdAt <= nowMillis || envelope.createdAt - nowMillis <= RelayCodec.MAX_CLOCK_SKEW_MILLIS) { "Relay envelope is from the future" }
        replayGuard.check(envelope.senderId, envelope.sequence)
        require(verifyIdentity(pinnedSenderBundle.identityPublicKey, envelopeSignedBytes(envelope), envelope.signature)) { "Invalid relay envelope signature" }
        val primitive = privateKeyset.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        val plaintext = primitive.decrypt(RelayCodec.decodeBase64(envelope.ciphertext, RelayCodec.MAX_CIPHERTEXT_BYTES), envelopeContext(envelope))
        val payload = RelayCodec.decodePayload(plaintext)
        require(envelope.expiresAt - envelope.createdAt <= RelayCodec.ttlMillis(payload.kind)) { "Invalid payload lifetime" }
        return payload
    }

    companion object {
        private val parameters: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.TINK)
            .build()

        private fun initialize() { HybridConfig.register() }
        fun keyTemplate(): KeyTemplate { initialize(); return KeyTemplate.createFrom(parameters) }
        fun generateKeyset(): KeysetHandle { initialize(); return KeysetHandle.generateNew(parameters) }

        fun createBundle(identityPublicKey: String, privateKeyset: KeysetHandle, signer: (ByteArray) -> String): RelayKeyBundle {
            initialize()
            val unsigned = RelayKeyBundle(identityPublicKey, exportPublicKey(privateKeyset), "")
            val signed = unsigned.copy(signature = signer(bundleSignedBytes(unsigned)))
            require(verifyBundle(signed)) { "Signer does not match bundle identity" }
            return signed
        }

        fun verifyBundle(bundle: RelayKeyBundle): Boolean = try {
            initialize()
            RelayCodec.validateBundle(bundle)
            importPublicKey(bundle.encryptionPublicKey)
            verifyIdentity(bundle.identityPublicKey, bundleSignedBytes(bundle), bundle.signature)
        } catch (_: Exception) { false }

        /** Domain-separated canonical bytes independent of JSON field order or whitespace. */
        fun bundleSignedBytes(bundle: RelayKeyBundle): ByteArray = canonical { data ->
            data.field("DeviceLink/relay-key-bundle/v1")
            data.field(TrustProof.key(bundle.identityPublicKey).encoded)
            data.field(RelayCodec.decodeBase64(bundle.encryptionPublicKey, 8192))
        }

        fun envelopeContext(envelope: RelayEnvelope): ByteArray = canonical { data ->
            data.field("DeviceLink/relay-context/v1")
            data.writeInt(envelope.version)
            data.field(envelope.id); data.field(envelope.senderId); data.field(envelope.recipientId)
            data.writeLong(envelope.createdAt); data.writeLong(envelope.expiresAt); data.writeLong(envelope.sequence)
        }

        fun envelopeSignedBytes(envelope: RelayEnvelope): ByteArray = canonical { data ->
            data.field("DeviceLink/relay-envelope/v1")
            data.field(envelopeContext(envelope))
            data.field(RelayCodec.decodeBase64(envelope.ciphertext, RelayCodec.MAX_CIPHERTEXT_BYTES))
        }

        private fun exportPublicKey(handle: KeysetHandle): String {
            val public = handle.publicKeysetHandle
            require(public.size() == 1 && public.primary.key.parameters == parameters) { "Unsupported relay encryption key" }
            return Base64.getEncoder().encodeToString(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(public))
        }
        private fun importPublicKey(encoded: String): KeysetHandle {
            val public = TinkProtoKeysetFormat.parseKeysetWithoutSecret(RelayCodec.decodeBase64(encoded, 8192))
            require(public.size() == 1 && public.primary.key.parameters == parameters) { "Unsupported relay encryption key" }
            return public
        }
        private fun verifyIdentity(identity: String, bytes: ByteArray, signature: String): Boolean = try {
            RelayCodec.validateSignature(signature)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(TrustProof.key(identity)); update(bytes)
                verify(RelayCodec.decodeBase64(signature, 80))
            }
        } catch (_: Exception) { false }
        private fun canonical(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().let { buffer ->
            DataOutputStream(buffer).use(write); buffer.toByteArray()
        }
        private fun DataOutputStream.field(value: String) = field(value.toByteArray(Charsets.UTF_8))
        private fun DataOutputStream.field(value: ByteArray) { writeInt(value.size); write(value) }
    }
}
