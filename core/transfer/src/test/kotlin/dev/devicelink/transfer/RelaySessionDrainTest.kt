package dev.devicelink.transfer

import org.junit.Assert.*
import org.junit.Test

class RelaySessionDrainTest {
    @Test fun `natural deadline rejects new work while admitted transfer finishes`() {
        var now = 900L
        val drain = RelaySessionDrain(1000) { now }
        val upload = requireNotNull(drain.acquire())
        now = 1000
        assertFalse(drain.canStart())
        assertNull(drain.acquire())
        assertFalse(drain.shouldStop())
        // Receipt and durable-delivery cleanup belong to the admitted operation.
        val receipt = requireNotNull(drain.acquire(continuation = true))
        upload.close()
        assertFalse(drain.shouldStop())
        receipt.close()
        assertTrue(drain.shouldStop())
    }

    @Test fun `clipboard wait keeps session available for acknowledgement but cannot extend grace`() {
        var now = 1000L
        val drain = RelaySessionDrain(1000) { now }
        assertFalse(drain.shouldStop(waitingForClipboard = true))
        val acknowledgement = requireNotNull(drain.acquire(continuation = true))
        now = 60_999
        assertFalse(drain.shouldStop())
        now = 61_000
        assertTrue(drain.shouldStop(waitingForClipboard = true))
        assertNull(drain.acquire(continuation = true))
        acknowledgement.close()
    }

    @Test fun `idle expiry stops immediately and explicit stop invalidates active work`() {
        var now = 999L
        val idle = RelaySessionDrain(1000) { now }
        assertFalse(idle.shouldStop())
        now = 1000
        assertTrue(idle.shouldStop())
        val active = RelaySessionDrain(10_000) { now }
        val download = requireNotNull(active.acquire())
        active.stop()
        assertTrue(active.shouldStop())
        assertNull(active.acquire())
        assertNull(active.acquire(continuation = true))
        download.close()
    }

    @Test fun `late cleanup from stopped session cannot release new session work`() {
        var now = 0L
        val old = RelaySessionDrain(1000) { now }
        val oldOperation = requireNotNull(old.acquire())
        old.stop()
        val fresh = RelaySessionDrain(2000) { now }
        val currentOperation = requireNotNull(fresh.acquire())
        oldOperation.close(); oldOperation.close()
        now = 2000
        assertFalse(fresh.shouldStop())
        currentOperation.close()
        assertTrue(fresh.shouldStop())
    }
}
