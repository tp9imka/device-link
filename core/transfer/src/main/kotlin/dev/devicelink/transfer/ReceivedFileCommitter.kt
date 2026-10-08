package dev.devicelink.transfer

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Publish a receipt only after bytes and its index are durable; cancellation rolls both back. */
internal class ReceivedFileCommitter(private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun commit(
        staged: File,
        destination: File,
        isCurrent: () -> Boolean,
        persist: () -> Unit,
        removeRecord: () -> Unit,
        publish: () -> Unit,
    ): Boolean {
        var moved = false
        var attemptedIndex = false
        var published = false
        try {
            if (!isCurrent()) return false
            withContext(io) {
                destination.parentFile!!.mkdirs()
                check(!destination.exists()) { "Received file already exists" }
                check(staged.renameTo(destination)) { "Cannot finalize received file" }
                moved = true
            }
            if (!isCurrent()) return false
            withContext(io) {
                attemptedIndex = true
                persist()
            }
            if (!isCurrent()) return false
            publish()
            published = true
            return true
        } finally {
            if (!published) withContext(NonCancellable + io) {
                try {
                    if (attemptedIndex) removeRecord()
                } finally {
                    if (moved) destination.delete()
                    staged.delete()
                }
            }
        }
    }
}
