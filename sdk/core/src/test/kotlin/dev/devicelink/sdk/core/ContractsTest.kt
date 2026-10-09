package dev.devicelink.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContractsTest {
    private class Device(name: String) {
        val identity = SoftwareIdentity()
        val keys = EncryptionKeyPair.generate()
        val bundle = KeyBundle.create(identity, keys, name, "android")
        val crypto = EnvelopeCrypto(identity, keys)
    }

    private val alice = Device("Alice phone")
    private val bob = Device("Bob iPhone")
    private val now = 1_800_000_000_000L
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }

    @Test fun `bundle signature covers name, platform and keys`() {
        assertTrue(alice.bundle.verified())
        assertEquals(alice.bundle, KeyBundle.decode(alice.bundle.encode()))
        assertFalse(alice.bundle.copy(name = "Mallory").verified())
        assertFalse(alice.bundle.copy(platform = "ios").verified())
        assertFalse(alice.bundle.copy(encryptionKey = bob.bundle.encryptionKey).verified())
        assertFalse(alice.bundle.copy(identityKey = bob.bundle.identityKey).verified())
    }

    @Test fun `every content kind round trips and the relay-visible JSON leaks nothing`() {
        val payloads = listOf(
            Payload.text("private clipboard", now),
            Payload.image("private.png", "image/png", byteArrayOf(1, 2, 3), now),
            Payload.file("confidential.pdf", "application/pdf", byteArrayOf(9), now),
            Payload.receipt("6f0d3c9e-6a45-4b8e-9b51-2f4c1b4e0a11", ReceiptStatus.COPIED),
            Payload.unlink(),
        )
        payloads.forEachIndexed { index, payload ->
            val envelope = alice.crypto.seal(payload, bob.bundle, now, index + 1L)
            val visible = envelope.encode().toString(Charsets.UTF_8)
            assertFalse(visible.contains("private") || visible.contains("confidential") || visible.contains("image/png"))
            assertEquals(payload, bob.crypto.open(Envelope.decode(visible), alice.bundle, now + 1))
        }
    }

    @Test fun `tampering, misrouting, expiry and wrong sender are rejected`() {
        val envelope = alice.crypto.seal(Payload.text("hi", now), bob.bundle, now, 5)
        rejects { bob.crypto.open(envelope.copy(sequence = 6), alice.bundle, now) }
        rejects { bob.crypto.open(envelope.copy(expiresAt = envelope.expiresAt + 1), alice.bundle, now) }
        rejects { bob.crypto.open(envelope, bob.bundle, now) }
        rejects { alice.crypto.open(envelope, alice.bundle, now) }
        rejects { bob.crypto.open(envelope, alice.bundle, envelope.expiresAt) }
        val carol = Device("Carol")
        val forged = carol.crypto.seal(Payload.text("hi", now), bob.bundle, now, 5)
        rejects { bob.crypto.open(forged.copy(senderId = alice.bundle.id), alice.bundle, now) }
        val tampered = Encoding.unb64(envelope.ciphertext, 1 shl 20).also { it[40] = (it[40] + 1).toByte() }
        rejects { bob.crypto.open(envelope.copy(ciphertext = Encoding.b64(tampered)), alice.bundle, now) }
    }

    @Test fun `payload validation enforces content rules`() {
        rejects { Payload(PayloadKind.TEXT, text = "").validate() }
        rejects { Payload(PayloadKind.TEXT, text = "x".repeat(Limits.MAX_TEXT_BYTES + 1)).validate() }
        rejects { Payload.image("a.pdf", "application/pdf", byteArrayOf(1), now).validate() }
        rejects { Payload.file("../x", "text/plain", byteArrayOf(1), now).validate() }
        rejects { Payload.file("x", "text/plain", ByteArray(0), now).validate() }
        rejects { Payload(PayloadKind.RECEIPT, receiptFor = "not-a-uuid", status = ReceiptStatus.COPIED).validate() }
        // Forward compatible: unknown fields from a newer peer are ignored.
        assertEquals("ok", Payload.decode("""{"kind":"text","text":"ok","future":1}""".toByteArray()).text)
    }

    @Test fun `pairing invite round trips through both link forms`() {
        val invite = PairingInvite.create("https://relay.example", alice.bundle.id)
        assertTrue(invite.uri.startsWith("https://relay.example/pair#v2."))
        for (form in listOf(invite.uri, invite.appUri)) {
            val parsed = requireNotNull(PairingInvite.parse(form))
            assertEquals(invite.pairingId, parsed.pairingId)
            assertEquals(alice.bundle.id, parsed.inviterId)
            assertEquals("https://relay.example", parsed.relayUrl)
            assertEquals(bob.bundle, parsed.openJoin(invite.sealJoin(bob.bundle)))
            assertEquals(alice.bundle, invite.openConfirm(parsed.sealConfirm(alice.bundle)))
        }
        rejects { invite.openConfirm(invite.sealJoin(bob.bundle)) }
        val other = PairingInvite.create("https://relay.example", alice.bundle.id)
        rejects { other.openJoin(invite.sealJoin(bob.bundle)) }
    }

    @Test fun `invalid invites and relay URLs are refused`() {
        listOf("", "https://relay.example/pair", "https://relay.example/x#v2.a.b", "ftp://relay/pair#v2.a.b",
            "https://relay.example/pair#v1.AAAA.BBBB", "http://relay.example/pair#v2.a.b").forEach { assertNull(it, PairingInvite.parse(it)) }
        assertEquals("https://relay.example:8443", RelayUrl.normalize("HTTPS://Relay.Example:8443/", false))
        assertEquals("http://192.168.1.20:8000", RelayUrl.normalize("http://192.168.1.20:8000", true))
        rejects { RelayUrl.normalize("http://relay.example", true) }
        rejects { RelayUrl.normalize("http://127.0.0.1:8000", false) }
        rejects { RelayUrl.normalize("https://user@relay.example", false) }
        rejects { RelayUrl.normalize("https://relay.example/path", false) }
    }

    @Test fun `setup link carries relay and enrollment token`() {
        val link = SetupLink("https://relay.example", "s3cret token")
        assertEquals(link, SetupLink.parse(link.uri))
        assertEquals(link, SetupLink.parse(link.appUri))
        assertNull(SetupLink.parse("https://relay.example/setup"))
    }

    @Test fun `hkdf matches RFC 5869 test case 3`() {
        // RFC 5869 A.3: zero-length salt and info.
        val okm = Encoding.hkdf(Encoding.unhex("0b".repeat(22)), "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", Encoding.hex(okm))
    }
}
