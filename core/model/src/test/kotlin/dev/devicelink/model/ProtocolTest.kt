package dev.devicelink.model

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `file offer round trips with payload binding`() {
        val offer = WireMessage.Offer("transfer-123", "report.pdf", 1234, "application/pdf", TransferKind.FILE, 42)
        assertEquals(offer, WireCodec.decode(WireCodec.encode(offer)))
    }

    @Test fun `protocol rejects unsupported versions unknown fields and malformed messages`() {
        reject { WireCodec.decode("""{"version":2,"message":{"type":"accept","id":"x"}}""".toByteArray()) }
        reject { WireCodec.decode("""{"version":1,"message":{"type":"accept","id":"x","admin":true}}""".toByteArray()) }
        reject { WireCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        reject { WireCodec.decode(ByteArray(WireCodec.MAX_WIRE_BYTES + 1)) }
        reject { WireCodec.encode(WireMessage.Accept("")) }
    }

    @Test fun `offers cannot have missing file binding negative size or mismatched text metadata`() {
        reject { WireCodec.encode(WireMessage.Offer("id", "a", 1, "text/plain", TransferKind.FILE)) }
        reject { WireCodec.encode(WireMessage.Offer("id", "a", -1, "text/plain", TransferKind.FILE, 12)) }
        reject { WireCodec.encode(WireMessage.Offer("id", "a", WireCodec.MAX_FILE_BYTES + 1, "text/plain", TransferKind.FILE, 12)) }
        reject { WireCodec.encode(WireMessage.Offer("id", "a", 1, "text/plain", TransferKind.TEXT, 12)) }
        reject { WireCodec.encode(WireMessage.Text("id", "a".repeat(WireCodec.MAX_TEXT_BYTES + 1))) }
        reject { WireCodec.encode(WireMessage.Offer("id", "a", 1, "text/plain\nattack", TransferKind.FILE, 12)) }
    }

    @Test fun `filenames are confined to a printable nonempty basename`() {
        assertEquals("secret.txt", FileNames.sanitize("../../secret.txt"))
        assertEquals("secret.txt", FileNames.sanitize("C:\\folder\\secret.txt"))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("report.pdf", FileNames.sanitize("report\u202E.pdf\u0000"))
        assertTrue(FileNames.sanitize("a".repeat(500)).length <= 120)
    }

    private fun reject(action: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, action)
    }
}
