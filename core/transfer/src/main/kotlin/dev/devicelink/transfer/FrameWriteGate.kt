package dev.devicelink.transfer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Once a cipher counter is consumed, finish writing that frame or fail the connection. */
internal class FrameWriteGate(
    private val onPendingChanged: () -> Unit = {},
    private val writeFrame: suspend (ByteArray) -> Unit,
) {
    private val mutex = Mutex()
    // Callers run on the controller's serial dispatcher. Old session writes cannot extend a new session.
    private val pending = mutableMapOf<Long, Int>()
    fun pendingWrites(session: Long): Int = pending[session] ?: 0

    suspend fun write(session: Long = 0, buildFrame: () -> ByteArray) {
        pending[session] = pendingWrites(session) + 1
        onPendingChanged()
        try {
            // Waiting for another frame remains cancellable. Only the selected frame is indivisible.
            mutex.withLock {
                withContext(NonCancellable) { writeFrame(buildFrame()) }
            }
        } finally {
            val remaining = pendingWrites(session) - 1
            if (remaining == 0) pending.remove(session) else pending[session] = remaining
            onPendingChanged()
        }
    }
}
