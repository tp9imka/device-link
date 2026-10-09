package dev.devicelink.sdk.core

import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cross-language vectors (docs/protocol/vectors). Swift writes swift.json, Kotlin writes kotlin.json;
 * each implementation must open the other's envelopes, pairing blobs and request signatures.
 * Keys in these files are throwaway test keys.
 */
class InteropVectorsTest {
    @Serializable data class Party(val identityPrivate: String, val encryptionPrivate: String, val bundle: KeyBundle)
    @Serializable data class Item(val now: Long, val envelope: Envelope, val payload: Payload)
    @Serializable data class Pairing(val uri: String, val pairingId: String, val joinSealed: String, val confirmSealed: String, val code: String)
    @Serializable data class Request(val method: String, val path: String, val body: String, val headers: Map<String, String>)
    @Serializable data class VectorFile(val producer: String, val sender: Party, val recipient: Party, val items: List<Item>,
        val pairing: Pairing, val request: Request)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val directory = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/protocol/vectors") }.firstOrNull { it.parentFile.isDirectory } ?: File("docs/protocol/vectors")

    @Test fun `opens envelopes, pairing data and request signatures produced by Swift`() {
        val file = File(directory, "swift.json")
        assumeTrue("swift.json not generated", file.isFile)
        verify(json.decodeFromString<VectorFile>(file.readText()))
    }

    @Test fun `writes kotlin vectors when requested`() {
        assumeTrue(System.getenv("DEVICELINK_WRITE_VECTORS") == "1")
        val vectors = generate()
        verify(vectors)
        directory.mkdirs()
        File(directory, "kotlin.json").writeText(json.encodeToString(vectors))
    }

    private fun identity(raw: ByteArray, publicDer: ByteArray): SoftwareIdentity {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), params))
        return SoftwareIdentity(KeyPair(Identity.publicKey(publicDer), private))
    }

    private fun raw(identity: SoftwareIdentity): ByteArray {
        val scalar = (KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(identity.privateKeyPkcs8)) as ECPrivateKey).s
        return scalar.toByteArray().let { bytes -> ByteArray(32).also { out -> bytes.takeLast(32).toByteArray().copyInto(out, 32 - minOf(32, bytes.size)) } }
    }

    private fun verify(file: VectorFile) {
        assertTrue(file.sender.bundle.verified() && file.recipient.bundle.verified())
        val recipient = identity(Encoding.unb64(file.recipient.identityPrivate, 32), file.recipient.bundle.identityKeyDer())
        assertEquals(file.recipient.bundle.id, recipient.deviceId)
        val crypto = EnvelopeCrypto(recipient, EncryptionKeyPair(Encoding.unb64(file.recipient.encryptionPrivate, 32)))
        file.items.forEach { assertEquals(it.payload, crypto.open(it.envelope, file.sender.bundle, it.now)) }
        val invite = requireNotNull(PairingInvite.parse(file.pairing.uri))
        assertEquals(file.pairing.pairingId, invite.pairingId)
        assertEquals(file.recipient.bundle, invite.openJoin(file.pairing.joinSealed))
        assertEquals(file.sender.bundle, invite.openConfirm(file.pairing.confirmSealed))
        assertEquals(file.pairing.code, invite.confirmationCode(file.recipient.bundle.id))
        val h = file.request.headers
        val canonical = "DeviceLink relay request v1\n${file.request.method}\n${file.request.path}\n${h["X-Device-Time"]}\n${h["X-Device-Nonce"]}\n" +
            Encoding.hex(Encoding.sha256(file.request.body.toByteArray()))
        assertTrue(Identity.verify(Encoding.unb64(h.getValue("X-Device-Key"), 128), canonical.toByteArray(), Encoding.unb64(h.getValue("X-Device-Signature"), 80)))
    }

    private fun generate(): VectorFile {
        val now = 1_800_000_000_000L
        val senderIdentity = SoftwareIdentity(); val senderKeys = EncryptionKeyPair.generate()
        val recipientIdentity = SoftwareIdentity(); val recipientKeys = EncryptionKeyPair.generate()
        val sender = KeyBundle.create(senderIdentity, senderKeys, "Kotlin sender ✓", "android")
        val recipient = KeyBundle.create(recipientIdentity, recipientKeys, "Recipient", "ios")
        val crypto = EnvelopeCrypto(senderIdentity, senderKeys)
        val payloads = listOf(Payload.text("Hello from kotlin — ünïcødé 📋", now),
            Payload.image("pixel.png", "image/png", ByteArray(64) { it.toByte() }, now),
            Payload.receipt("6f0d3c9e-6a45-4b8e-9b51-2f4c1b4e0a11", ReceiptStatus.COPIED),
            Payload.text("Rich", now, html = "<b>Rich</b>", sensitive = true),
            Payload(PayloadKind.FILE, name = "part.bin", mime = "application/octet-stream", data = Encoding.b64(ByteArray(16) { 7 }), sentAt = now,
                group = "0b6c2f4e-1d2a-4c3b-8e9f-0a1b2c3d4e5f", part = 1, parts = 3, size = 9_000_000))
        val items = payloads.mapIndexed { index, payload -> Item(now, crypto.seal(payload, recipient, now, index + 1L), payload) }
        val invite = PairingInvite.create("https://relay.example", sender.id)
        val path = "/v1/peers/${recipient.id}"
        val headers = RelayClient("https://relay.example", senderIdentity) { now }.signatureHeaders("PUT", path, "{}".toByteArray())
        return VectorFile("kotlin",
            Party(Encoding.b64(raw(senderIdentity)), Encoding.b64(senderKeys.privateKeyBytes()), sender),
            Party(Encoding.b64(raw(recipientIdentity)), Encoding.b64(recipientKeys.privateKeyBytes()), recipient),
            items, Pairing(invite.uri, invite.pairingId, invite.sealJoin(recipient), invite.sealConfirm(sender), invite.confirmationCode(recipient.id)),
            Request("PUT", path, "{}", headers))
    }
}
