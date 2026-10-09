package dev.devicelink

import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImageOwnershipTest {
    @Test fun `cancellation returning imported image cleans file before publication`() = runBlocking {
        val file = Files.createTempFile("import-image", ".png").toFile()
        val job = launch {
            importOwnedImageFile { own ->
                own(file)
                currentCoroutineContext().cancel()
                "image-uri"
            }
            fail("Cancelled import must not publish its URI")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(file.exists())
    }

    @Test fun `failed imports are removed but published assets survive`() = runBlocking {
        val failed = Files.createTempFile("import-image", ".part").toFile()
        assertNull(importOwnedImageFile<String> { own -> own(failed); null })
        assertFalse(failed.exists())
        val published = Files.createTempFile("import-image", ".png").toFile()
        try {
            assertEquals("image-uri", importOwnedImageFile { own -> own(published); "image-uri" })
            assertTrue(published.exists())
        } finally { published.delete() }
    }

    @Test fun `maintenance reclaims stale orphans and preserves history clipboard fixture and recent assets`() {
        val directory = Files.createTempDirectory("image-retention").toFile()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        try {
            fun create(name: String, age: Long) = directory.resolve(name).apply {
                writeText("image bytes")
                check(setLastModified(now - age))
            }
            val history = create("history.png", 3 * day)
            val clipboard = create("clipboard.jpg", 3 * day)
            val fixture = create("sample_landscape.png", 3 * day)
            val orphan = create("orphan.webp", 2 * day)
            val partial = create("abandoned.part", 2 * day)
            val recent = create("inflight.png", day / 2)
            val boundary = create("boundary.gif", day)
            pruneImageFiles(directory, setOf(history.name, clipboard.name), now)
            assertFalse(orphan.exists())
            assertFalse(partial.exists())
            listOf(history, clipboard, fixture, recent, boundary).forEach { assertTrue(it.name, it.exists()) }
        } finally { directory.deleteRecursively() }
    }
}
