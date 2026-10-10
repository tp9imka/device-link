package dev.devicelink.sdk.core

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStoreTest {
    /** Two stores on one file stand in for two writers (another process, or a second engine). */
    @Test fun `concurrent writers on one file lose no update`() {
        val directory = kotlin.io.path.createTempDirectory("links").toFile()
        try {
            val file = java.io.File(directory, "links.json")
            val stores = listOf(FileLinkStore(file), FileLinkStore(file))
            val rounds = 100
            stores.mapIndexed { index, store ->
                thread {
                    repeat(rounds) { round ->
                        store.update { it.copy(sequence = it.sequence + 1, seen = it.seen + ("$index-$round" to Long.MAX_VALUE)) }
                    }
                }
            }.forEach { it.join() }
            val final = FileLinkStore(file).load()
            assertEquals((stores.size * rounds).toLong(), final.sequence)
            assertEquals(stores.size * rounds, final.seen.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
