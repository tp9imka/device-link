package dev.devicelink.sdk.core

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStoreTest {
    @Test fun `old state keeps its registration and new metadata persists`() {
        val directory = kotlin.io.path.createTempDirectory("links").toFile()
        try {
            val file = java.io.File(directory, "links.json")
            file.writeText("""{"relayUrl":"https://relay.example","registeredRelay":"https://relay.example","sequence":42}""")
            val store = FileLinkStore(file)
            val old = store.load()
            assertEquals(old.relayUrl, old.registeredRelay)
            assertEquals(42L, old.sequence)
            assertEquals(null, old.registeredMetadata)
            val metadata = DeviceMetadata("ios", "Phone", "2.0.0")
            store.update { it.copy(registeredMetadata = metadata) }
            assertEquals(old.copy(registeredMetadata = metadata), FileLinkStore(file).load())
            val json = wireJson.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            assertEquals(wireJson.parseToJsonElement("""{"platform":"ios","model":"Phone","appVersion":"2.0.0"}"""), json["registeredMetadata"])
        } finally { directory.deleteRecursively() }
    }

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
