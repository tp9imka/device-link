package dev.devicelink.model

/** All times come from a monotonic clock (Android elapsedRealtime), never wall-clock time. */
object SessionPolicy {
    fun expired(deadlineElapsedMillis: Long, nowElapsedMillis: Long): Boolean = nowElapsedMillis >= deadlineElapsedMillis
    fun shouldStop(deadlineElapsedMillis: Long, nowElapsedMillis: Long, activeTransfers: Int): Boolean {
        require(activeTransfers >= 0)
        return expired(deadlineElapsedMillis, nowElapsedMillis) && activeTransfers == 0
    }
}

object TransferLifecycle {
    fun canTransition(from: TransferStatus, to: TransferStatus): Boolean = when (from) {
        TransferStatus.OFFERED -> to in setOf(TransferStatus.TRANSFERRING, TransferStatus.REJECTED, TransferStatus.CANCELLED, TransferStatus.FAILED)
        TransferStatus.TRANSFERRING -> to in setOf(TransferStatus.COMPLETE, TransferStatus.REJECTED, TransferStatus.CANCELLED, TransferStatus.FAILED)
        TransferStatus.COMPLETE, TransferStatus.REJECTED, TransferStatus.CANCELLED, TransferStatus.FAILED -> false
    }
}
