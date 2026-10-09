package dev.devicelink.transfer

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StagedClipboardImageTest {
    @Test fun `cancel during image validation removes EOF staging`() = runBlocking {
        val staged = Files.createTempFile("clipboard-image", ".incoming").toFile()
        val validating = CompletableDeferred<Unit>()
        val job = launch {
            withOwnedStagedFile(staged) {
                validating.complete(Unit)
                awaitCancellation()
            }
        }
        validating.await()
        job.cancelAndJoin()
        assertFalse(staged.exists())
    }

    @Test fun `validation failure removes staging and committed file survives ownership release`() = runBlocking {
        val directory = Files.createTempDirectory("clipboard-image").toFile()
        try {
            val rejected = directory.resolve("rejected").apply { writeText("invalid image") }
            runCatching { withOwnedStagedFile(rejected) { error("validation failed") } }
            assertFalse(rejected.exists())
            val staged = directory.resolve("incoming").apply { writeText("verified image") }
            val received = directory.resolve("received")
            withOwnedStagedFile(staged) { check(staged.renameTo(received)) }
            assertFalse(staged.exists())
            assertEquals("verified image", received.readText())
        } finally { directory.deleteRecursively() }
    }
}
