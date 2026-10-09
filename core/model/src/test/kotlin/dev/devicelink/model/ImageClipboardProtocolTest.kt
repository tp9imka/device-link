package dev.devicelink.model

import org.junit.Assert.*
import org.junit.Test

class ImageClipboardProtocolTest {
    @Test fun `image clipboard intent preserves ordinary file offer bytes`() {
        val ordinary = WireMessage.Offer("file-1", "photo.png", 1234, "image/png", TransferKind.FILE, 42)
        val golden = """{"version":1,"message":{"type":"offer","id":"file-1","name":"photo.png","size":1234,"mime":"image/png","kind":"FILE","payloadId":42}}""".toByteArray()
        assertArrayEquals(golden, WireCodec.encode(ordinary))
        val clip = WireMessage.ClipImageOffer(ordinary.id, ordinary.name, ordinary.size, ordinary.mime, 42)
        assertEquals(clip, WireCodec.decode(WireCodec.encode(clip)))
        assertFalse(WireCodec.encode(clip).contentEquals(golden))
    }

    @Test fun `automatic image offers reject oversized unsafe or unbound payloads`() {
        val valid = WireMessage.ClipImageOffer("image-1", "photo.png", WireCodec.MAX_CLIP_IMAGE_BYTES, "image/png", 43)
        assertEquals(valid, WireCodec.decode(WireCodec.encode(valid)))
        for (invalid in listOf(valid.copy(size = 0), valid.copy(size = valid.size + 1), valid.copy(mime = "image/svg+xml"), valid.copy(mime = "text/plain"), valid.copy(id = "../image"))) {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireCodec.decode("""{"version":1,"message":{"type":"clip_image_offer","id":"image-1","name":"photo.png","size":12,"mime":"image/png"}}""".toByteArray())
        }
    }

    @Test fun `relay key payload is bounded by UTF8 bytes`() {
        val valid = WireMessage.RelayKeys("é".repeat(8192))
        assertEquals(valid, WireCodec.decode(WireCodec.encode(valid)))
        assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(valid.copy(bundle = valid.bundle + "!")) }
        assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(WireMessage.RelayKeys("")) }
    }
}
