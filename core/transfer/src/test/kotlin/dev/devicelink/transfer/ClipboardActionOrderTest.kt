package dev.devicelink.transfer

import org.junit.Assert.*
import org.junit.Test

class ClipboardActionOrderTest {
    @Test fun `slow image cannot overwrite a newer text copy`() {
        val order = ClipboardActionOrder()
        order.received("image-A")
        assertTrue(order.isLatest("image-A"))
        order.received("text-B")
        assertFalse(order.isLatest("image-A"))
        assertTrue(order.isLatest("text-B"))
    }

    @Test fun `new image supersedes queued text and previous image`() {
        val order = ClipboardActionOrder()
        order.received("image-A")
        order.received("text-B")
        order.received("image-C")
        assertFalse(order.isLatest("text-B"))
        assertFalse(order.isLatest("image-A"))
        assertTrue(order.isLatest("image-C"))
    }

    @Test fun `ending a session invalidates its newest delivery`() {
        val order = ClipboardActionOrder()
        order.received("image-A")
        order.clear()
        assertFalse(order.isLatest("image-A"))
        order.received("text-B")
        assertFalse(order.isLatest("image-A"))
        assertTrue(order.isLatest("text-B"))
    }
}
