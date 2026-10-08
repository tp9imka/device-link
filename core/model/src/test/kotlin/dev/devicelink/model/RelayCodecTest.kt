package dev.devicelink.model

import org.junit.Assert.*
import org.junit.Test

class RelayCodecTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }

    @Test fun `strict decoding rejects unknown fields malformed UTF8 and excessive nesting`() {
        val valid = RelayCodec.encodePayload(RelayPayload(RelayPayloadKind.TEXT, text = "hello"))
        assertEquals("hello", RelayCodec.decodePayload(valid).text)
        val unknown = valid.toString(Charsets.UTF_8).dropLast(1) + ",\"extension\":true}"
        rejects { RelayCodec.decodePayload(unknown.toByteArray()) }
        rejects { RelayCodec.decodePayload("{\"kind\":\"TEXT\",\"text\":\"one\",\"te\\u0078t\":\"two\"}".toByteArray()) }
        rejects { RelayCodec.decodePayload(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejects { RelayCodec.decodePayload("[[[[[{}]]]]]".toByteArray()) }
        rejects { RelayCodec.decodePayload("{\"kind\":\"FUTURE_KIND\"}".toByteArray()) }
    }

    @Test fun `poll batch preserves duplicate rejection and enforces flat bounded response`() {
        val envelope = RelayEnvelope("123e4567-e89b-12d3-a456-426614174000", "a".repeat(64), "b".repeat(64),
            100L, 200L, 1L, "AA==", "AAAAAAAAAAA=")
        val encoded = RelayCodec.encodeEnvelope(envelope).toString(Charsets.UTF_8)
        assertEquals(listOf(envelope), RelayCodec.decodeEnvelopes("[ $encoded ]".toByteArray()))
        assertTrue(RelayCodec.decodeEnvelopes("[]".toByteArray()).isEmpty())
        rejects { RelayCodec.decodeEnvelopes("[$encoded,$encoded]".toByteArray()) }
        rejects { RelayCodec.decodeEnvelopes("[$encoded,]".toByteArray()) }
        rejects { RelayCodec.decodeEnvelopes("[$encoded]garbage".toByteArray()) }
        rejects { RelayCodec.decodeEnvelopes(("[" + encoded.dropLast(1) + ",\"sequence\":2}]").toByteArray()) }
        rejects { RelayCodec.decodeEnvelopes("[{\"nested\":[[[[]]]]}]".toByteArray()) }
        rejects { RelayCodec.decodeEnvelopes(byteArrayOf(91, 0xc3.toByte(), 0x28, 93)) }
    }

    @Test fun `text limit uses UTF8 bytes and binary limits are bounded before decoding`() {
        val boundary = RelayPayload(RelayPayloadKind.CLIP_TEXT, text = "é".repeat(4096))
        assertEquals(boundary, RelayCodec.decodePayload(RelayCodec.encodePayload(boundary)))
        rejects { RelayCodec.encodePayload(boundary.copy(text = boundary.text + "a")) }
        rejects { RelayCodec.encodePayload(boundary.copy(text = "")) }
        val file = RelayPayload(RelayPayloadKind.FILE, name = "report.bin", mime = "application/octet-stream",
            contentBase64 = RelayPayload.encodeContent(ByteArray(RelayCodec.MAX_CONTENT_BYTES)))
        RelayCodec.validatePayload(file)
        rejects { RelayCodec.validatePayload(file.copy(contentBase64 = file.contentBase64 + "AAAA")) }
        rejects { RelayPayload.encodeContent(ByteArray(RelayCodec.MAX_CONTENT_BYTES + 1)) }
        rejects { RelayCodec.decodeContent("!!!!") }
    }

    @Test fun `payload kinds prohibit confused content and unsafe file names`() {
        val file = RelayPayload(RelayPayloadKind.FILE, name = "a.txt", mime = "text/plain", contentBase64 = "")
        listOf("../secret", "a/b", "a\\b", ".", "..", "a\u0000b", "a\u202eb").forEach { name ->
            rejects { RelayCodec.validatePayload(file.copy(name = name)) }
        }
        rejects { RelayCodec.validatePayload(file.copy(text = "hidden")) }
        rejects { RelayCodec.validatePayload(file.copy(mime = "text/plain\r\nheader")) }
        rejects { RelayCodec.validatePayload(file.copy(kind = RelayPayloadKind.CLIP_IMAGE)) }
        rejects { RelayCodec.validatePayload(file.copy(kind = RelayPayloadKind.CLIP_IMAGE, mime = "image/png")) }
        rejects { RelayCodec.validatePayload(RelayPayload(RelayPayloadKind.TEXT, text = "a", receiptCopied = true)) }
        rejects { RelayCodec.validatePayload(RelayPayload(RelayPayloadKind.RECEIPT, receiptFor = "1-2-3-4-5")) }
    }

    @Test fun `envelopes require supported explicit version and bounded canonical routing fields`() {
        val envelope = RelayEnvelope("123e4567-e89b-12d3-a456-426614174000", "a".repeat(64), "b".repeat(64),
            100L, 200L, 1L, "AA==", "AAAAAAAAAAA=")
        val bytes = RelayCodec.encodeEnvelope(envelope)
        assertEquals(envelope, RelayCodec.decodeEnvelope(bytes))
        rejects { RelayCodec.decodeEnvelope(bytes.toString(Charsets.UTF_8).replace(",\"version\":1", "").toByteArray()) }
        rejects { RelayCodec.validateEnvelope(envelope.copy(version = 2)) }
        rejects { RelayCodec.validateEnvelope(envelope.copy(sequence = 0)) }
        rejects { RelayCodec.validateEnvelope(envelope.copy(recipientId = envelope.senderId)) }
        rejects { RelayCodec.validateEnvelope(envelope.copy(id = "1-2-3-4-5")) }
        rejects { RelayCodec.validateEnvelope(envelope.copy(expiresAt = 100 + RelayCodec.MESSAGE_TTL_MILLIS + 1)) }
    }
}
