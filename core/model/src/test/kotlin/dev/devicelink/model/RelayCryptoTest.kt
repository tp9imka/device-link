package dev.devicelink.model

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RelayCryptoTest {
    private class Device {
        private val identity = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(identity.public.encoded)
        val keys = RelayCrypto.generateKeyset()
        fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private); update(bytes); sign()
        })
        val bundle = RelayCrypto.createBundle(publicKey, keys, ::sign)
        val crypto = RelayCrypto(keys, bundle, ::sign)
    }
    private val alice = Device()
    private val bob = Device()
    private val now = 1_000_000L
    private fun guard() = RelayReplayGuard({ null }, { _, _ -> error("Decrypt must never record") })
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }

    @Test fun `all payload kinds round trip with private fields encrypted`() {
        val payloads = listOf(
            RelayPayload(RelayPayloadKind.TEXT, text = "private message"),
            RelayPayload(RelayPayloadKind.CLIP_TEXT, text = "private clipboard"),
            RelayPayload(RelayPayloadKind.FILE, name = "confidential.pdf", mime = "application/pdf", contentBase64 = RelayPayload.encodeContent(byteArrayOf(0, 1, -1))),
            RelayPayload(RelayPayloadKind.CLIP_IMAGE, name = "private.png", mime = "image/png", contentBase64 = RelayPayload.encodeContent(byteArrayOf(5, 6))),
            RelayPayload(RelayPayloadKind.RECEIPT, receiptFor = UUID.randomUUID().toString(), receiptCopied = true),
        )
        payloads.forEachIndexed { index, payload ->
            val sealed = alice.crypto.encrypt(payload, bob.bundle, now, index + 1L)
            val encoded = RelayCodec.encodeEnvelope(sealed)
            assertFalse(encoded.toString(Charsets.UTF_8).contains("private"))
            assertFalse(encoded.toString(Charsets.UTF_8).contains("confidential"))
            assertEquals(payload, bob.crypto.decrypt(RelayCodec.decodeEnvelope(encoded), alice.bundle, now, guard()))
            assertEquals(RelayCodec.ttlMillis(payload.kind), sealed.expiresAt - sealed.createdAt)
        }
    }

    @Test fun `ciphertext metadata recipient and sender are authenticated`() {
        val original = alice.crypto.encrypt(RelayPayload(RelayPayloadKind.TEXT, text = "secret"), bob.bundle, now, 1)
        val ciphertext = Base64.getDecoder().decode(original.ciphertext).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        listOf(
            original.copy(id = UUID.randomUUID().toString()), original.copy(sequence = 2),
            original.copy(createdAt = now - 1, expiresAt = original.expiresAt - 1),
            original.copy(ciphertext = Base64.getEncoder().encodeToString(ciphertext)),
            original.copy(signature = bob.sign(RelayCrypto.envelopeSignedBytes(original))),
        ).forEach { changed -> rejects { bob.crypto.decrypt(changed, alice.bundle, now, guard()) } }
        rejects { alice.crypto.decrypt(original, alice.bundle, now, guard()) }
        rejects { bob.crypto.decrypt(original, bob.bundle, now, guard()) }
        // Even a valid sender signature cannot rebind an already encrypted payload to new context.
        val rebound = original.copy(sequence = 2)
        rejects { bob.crypto.decrypt(rebound.copy(signature = alice.sign(RelayCrypto.envelopeSignedBytes(rebound))), alice.bundle, now, guard()) }
    }

    @Test fun `signed key bundle rejects substituted encryption or identity keys`() {
        assertTrue(RelayCrypto.verifyBundle(alice.bundle))
        assertFalse(RelayCrypto.verifyBundle(alice.bundle.copy(encryptionPublicKey = bob.bundle.encryptionPublicKey)))
        assertFalse(RelayCrypto.verifyBundle(alice.bundle.copy(identityPublicKey = bob.publicKey)))
        rejects { RelayCrypto(bob.keys, alice.bundle, alice::sign) }
        rejects { RelayCrypto.createBundle(alice.publicKey, alice.keys, bob::sign) }
    }

    @Test fun `signed bundles reject unsupported encryption algorithms and private key material`() {
        com.google.crypto.tink.aead.AeadConfig.register()
        val wrong = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.aead.PredefinedAeadParameters.AES256_GCM)
        val encodedSecret = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(wrong, com.google.crypto.tink.InsecureSecretKeyAccess.get())
        val bundle = alice.bundle.copy(encryptionPublicKey = Base64.getEncoder().encodeToString(encodedSecret))
        assertFalse(RelayCrypto.verifyBundle(bundle.copy(signature = alice.sign(RelayCrypto.bundleSignedBytes(bundle)))))
        val alternative = com.google.crypto.tink.hybrid.HpkeParameters.builder()
            .setKemId(com.google.crypto.tink.hybrid.HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(com.google.crypto.tink.hybrid.HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(com.google.crypto.tink.hybrid.HpkeParameters.AeadId.AES_128_GCM)
            .setVariant(com.google.crypto.tink.hybrid.HpkeParameters.Variant.TINK).build()
        val wrongPublic = com.google.crypto.tink.KeysetHandle.generateNew(alternative).publicKeysetHandle
        val substituted = alice.bundle.copy(encryptionPublicKey = Base64.getEncoder().encodeToString(
            com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(wrongPublic)))
        assertFalse(RelayCrypto.verifyBundle(substituted.copy(signature = alice.sign(RelayCrypto.bundleSignedBytes(substituted)))))
    }

    @Test fun `freshness rejects expiry future and excessive clipboard lifetime`() {
        val clip = alice.crypto.encrypt(RelayPayload(RelayPayloadKind.CLIP_TEXT, text = "fresh"), bob.bundle, now, 1)
        rejects { bob.crypto.decrypt(clip, alice.bundle, clip.expiresAt, guard()) }
        rejects { bob.crypto.decrypt(clip, alice.bundle, now - RelayCodec.MAX_CLOCK_SKEW_MILLIS - 1, guard()) }
        assertEquals("fresh", bob.crypto.decrypt(clip, alice.bundle, now - RelayCodec.MAX_CLOCK_SKEW_MILLIS, guard()).text)
        // Build a properly signed and encrypted clip with a file's longer TTL: payload policy must reject it.
        val metadata = clip.copy(expiresAt = now + RelayCodec.MESSAGE_TTL_MILLIS)
        val public = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.getDecoder().decode(bob.bundle.encryptionPublicKey))
        val ciphertext = public.getPrimitive(com.google.crypto.tink.RegistryConfiguration.get(), com.google.crypto.tink.HybridEncrypt::class.java)
            .encrypt(RelayCodec.encodePayload(RelayPayload(RelayPayloadKind.CLIP_TEXT, text = "stale")), RelayCrypto.envelopeContext(metadata))
        val changed = metadata.copy(ciphertext = Base64.getEncoder().encodeToString(ciphertext))
        rejects { bob.crypto.decrypt(changed.copy(signature = alice.sign(RelayCrypto.envelopeSignedBytes(changed))), alice.bundle, now, guard()) }
    }

    @Test fun `delivery recording is explicit and clipboard ordering does not discard older files`() {
        val highWater = mutableMapOf<String, Long>()
        val ordering = RelayReplayGuard(highWater::get) { sender, seq -> highWater[sender] = seq }
        val file = alice.crypto.encrypt(RelayPayload(RelayPayloadKind.FILE, name = "a.txt", mime = "text/plain", contentBase64 = ""), bob.bundle, now, 1)
        val clip = alice.crypto.encrypt(RelayPayload(RelayPayloadKind.CLIP_TEXT, text = "new"), bob.bundle, now, 2)
        bob.crypto.decrypt(clip, alice.bundle, now, ordering)
        assertTrue(highWater.isEmpty())
        ordering.record(clip.senderId, clip.sequence)
        rejects { bob.crypto.decrypt(clip, alice.bundle, now, ordering) }
        rejects { ordering.record(clip.senderId, 1) }
        assertEquals(RelayPayloadKind.FILE, bob.crypto.decrypt(file, alice.bundle, now, guard()).kind)
        assertEquals(2L, highWater[alice.bundle.id])
    }
}
