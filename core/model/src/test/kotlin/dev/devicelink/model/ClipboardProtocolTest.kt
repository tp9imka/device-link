package dev.devicelink.model

import org.junit.Assert.*
import org.junit.Test

class ClipboardProtocolTest {
    @Test fun `ordinary text keeps exact existing wire representation`() {
        val text = WireMessage.Text("transfer-123", "Hello\nother phone")
        val golden = """{"version":1,"message":{"type":"text","id":"transfer-123","text":"Hello\nother phone"}}""".toByteArray()
        assertArrayEquals(golden, WireCodec.encode(text))
        assertEquals(text, WireCodec.decode(golden))
    }

    @Test fun `clip intent and clipboard result have distinct round trip types`() {
        val clip = WireMessage.Clip("transfer-123", "Hello other phone")
        val wire = WireCodec.encode(clip)
        assertEquals(clip, WireCodec.decode(wire))
        assertFalse(wire.contentEquals(WireCodec.encode(WireMessage.Text(clip.id, clip.text))))
        for (copied in listOf(true, false)) {
            val result = WireMessage.ClipResult(clip.id, copied)
            assertEquals(result, WireCodec.decode(WireCodec.encode(result)))
        }
    }

    @Test fun `clip bounds count UTF8 bytes and reject malformed commands`() {
        val atLimit = "é".repeat(WireCodec.MAX_TEXT_BYTES / 2)
        assertEquals(WireMessage.Clip("id", atLimit), WireCodec.decode(WireCodec.encode(WireMessage.Clip("id", atLimit))))
        reject { WireCodec.encode(WireMessage.Clip("id", "$atLimit!")) }
        reject { WireCodec.encode(WireMessage.Clip("id", "")) }
        reject { WireCodec.encode(WireMessage.Clip("../id", "text")) }
        reject { WireCodec.encode(WireMessage.ClipResult("", true)) }
        reject { WireCodec.decode("""{"version":1,"message":{"type":"clip","id":"id","text":"copy","extra":true}}""".toByteArray()) }
        reject { WireCodec.decode("""{"version":1,"message":{"type":"clip_result","id":"id"}}""".toByteArray()) }
        reject { WireCodec.decode("""{"version":1,"message":{"type":"clip_result","id":"id","copied":true,"extra":1}}""".toByteArray()) }
    }

    private fun reject(action: () -> Unit) = assertThrows(IllegalArgumentException::class.java, action)
}
