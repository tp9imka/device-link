package dev.devicelink.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.devicelink.model.SessionPolicy

@OptIn(ExperimentalCoroutinesApi::class)
class FrameWriteGateTest {
    @Test fun `expired session remains open until final receipt is written`() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = FrameWriteGate { release.await() }
        val receipt = launch { gate.write(session = 7) { byteArrayOf(1) } }
        runCurrent()
        assertFalse(SessionPolicy.shouldStop(100, 101, gate.pendingWrites(7)))
        // An old session's writer must not keep another session alive.
        assertTrue(SessionPolicy.shouldStop(100, 101, gate.pendingWrites(8)))
        release.complete(Unit)
        receipt.join()
        assertTrue(SessionPolicy.shouldStop(100, 101, gate.pendingWrites(7)))
    }

    @Test fun `cancelling after counter allocation cannot skip an encrypted frame`() = runTest {
        var counter = 0
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val gate = FrameWriteGate { frame ->
            if (frame[0].toInt() == 0) release.await()
            written += frame[0].toInt()
        }
        val sending = launch { gate.write { byteArrayOf((counter++).toByte()) } }
        runCurrent()
        sending.cancel()
        val next = launch { gate.write { byteArrayOf((counter++).toByte()) } }
        runCurrent()
        release.complete(Unit)
        sending.join(); next.join()
        assertEquals(listOf(0, 1), written)
    }

    @Test fun `cancelling a waiting transfer consumes no cipher counter`() = runTest {
        var counter = 0
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val gate = FrameWriteGate { frame ->
            if (frame[0].toInt() == 0) release.await()
            written += frame[0].toInt()
        }
        val first = launch { gate.write { byteArrayOf((counter++).toByte()) } }
        runCurrent()
        val cancelled = launch { gate.write { byteArrayOf((counter++).toByte()) } }
        runCurrent(); cancelled.cancel(); cancelled.join()
        val last = launch { gate.write { byteArrayOf((counter++).toByte()) } }
        release.complete(Unit)
        first.join(); last.join()
        assertEquals(listOf(0, 1), written)
    }
}
