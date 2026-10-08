package dev.devicelink.transfer

import org.junit.Assert.*
import org.junit.Test

class ClipboardDeliveriesTest {
    @Test fun `delivery is one shot and stop invalidates queued clipboard events`() {
        val deliveries = ClipboardDeliveries()
        val first = requireNotNull(deliveries.offer("a", "first", 1))
        assertTrue(deliveries.isPending(first, 1, true))
        assertTrue(deliveries.consume(first, 1, true))
        assertFalse(deliveries.consume(first, 1, true))
        val queued = requireNotNull(deliveries.offer("b", "queued", 1))
        deliveries.clear()
        assertFalse(deliveries.isPending(queued, 1, true))
        deliveries.offer("b", "new session", 2)
        assertFalse(deliveries.isPending(queued, 2, true))
    }

    @Test fun `disconnected modified and cancelled events cannot authorize a write`() {
        val deliveries = ClipboardDeliveries()
        val event = requireNotNull(deliveries.offer("a", "payload", 7))
        assertFalse(deliveries.isPending(event, 7, false))
        assertFalse(deliveries.isPending(event.copy(text = "changed"), 7, true))
        assertFalse(deliveries.isPending(event, 8, true))
        deliveries.discard(event.id)
        assertFalse(deliveries.isPending(event, 7, true))
    }

    @Test fun `backpressure remains bounded and capacity returns after acknowledgement`() {
        val deliveries = ClipboardDeliveries()
        val events = (0 until ClipboardDeliveries.MAX_PENDING).map {
            requireNotNull(deliveries.offer(it.toString(), "text", 1))
        }
        assertNull(deliveries.offer("overflow", "text", 1))
        assertTrue(deliveries.consume(events.first(), 1, true))
        assertNotNull(deliveries.offer("next", "text", 1))
    }
}
