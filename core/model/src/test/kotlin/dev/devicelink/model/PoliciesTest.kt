package dev.devicelink.model

import org.junit.Assert.*
import org.junit.Test

class PoliciesTest {
    @Test fun `deadline refuses new work but waits for accepted transfer`() {
        assertFalse(SessionPolicy.expired(100, 99))
        assertTrue(SessionPolicy.expired(100, 100))
        assertFalse(SessionPolicy.shouldStop(100, 100, 1))
        assertTrue(SessionPolicy.shouldStop(100, 100, 0))
        assertFalse(SessionPolicy.shouldStop(100, 99, 0))
    }

    @Test fun `terminal transfers cannot restart and offered data cannot complete`() {
        assertTrue(TransferLifecycle.canTransition(TransferStatus.OFFERED, TransferStatus.TRANSFERRING))
        assertTrue(TransferLifecycle.canTransition(TransferStatus.TRANSFERRING, TransferStatus.COMPLETE))
        assertTrue(TransferLifecycle.canTransition(TransferStatus.OFFERED, TransferStatus.REJECTED))
        assertFalse(TransferLifecycle.canTransition(TransferStatus.OFFERED, TransferStatus.COMPLETE))
        assertFalse(TransferLifecycle.canTransition(TransferStatus.COMPLETE, TransferStatus.TRANSFERRING))
        assertFalse(TransferLifecycle.canTransition(TransferStatus.CANCELLED, TransferStatus.COMPLETE))
        assertFalse(TransferLifecycle.canTransition(TransferStatus.FAILED, TransferStatus.TRANSFERRING))
    }
}
