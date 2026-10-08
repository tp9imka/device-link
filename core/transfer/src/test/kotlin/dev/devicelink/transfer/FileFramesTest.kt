package dev.devicelink.transfer

import org.junit.Assert.*
import org.junit.Test

class FileFramesTest {
    @Test fun `chunk binding survives binary contents and signed payload ids`() {
        val binary = byteArrayOf(0, -1, 3, 2, 0)
        val frame = FileFrames.decode(FileFrames.encode(Long.MIN_VALUE, binary))
        assertEquals(Long.MIN_VALUE, frame.payloadId)
        assertArrayEquals(binary, frame.bytes)
        assertNull(FileFrames.decode(FileFrames.encode(Long.MIN_VALUE, null)).bytes)
    }
    @Test fun `truncated ambiguous and oversized frames cannot become a file`() {
        for (frame in listOf(byteArrayOf(3), ByteArray(9).apply { this[0] = 2 },
            ByteArray(10).apply { this[0] = 3 }, ByteArray(65537).apply { this[0] = 2 },
            ByteArray(9).apply { this[0] = 4 })) {
            assertThrows(IllegalArgumentException::class.java) { FileFrames.decode(frame) }
        }
    }
}
