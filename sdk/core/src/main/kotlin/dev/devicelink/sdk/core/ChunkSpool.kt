package dev.devicelink.sdk.core

import java.io.File
import java.io.FileOutputStream

/**
 * Keeps the still-encrypted chunk envelopes of a file in transfer on the receiver, so a chunk can be
 * acknowledged on the relay as soon as it is stored and a restart mid-transfer resumes instead of losing
 * the file. Holding chunks unacknowledged on the relay instead would deadlock files above the relay's
 * per-mailbox byte quota (64 MiB default; ten 4 MiB chunks are ~71 MiB of envelopes).
 *
 * Bounded like the old in-memory assembler: at most [MAX_GROUPS] files in flight, each entry dropped at
 * its envelope expiry. Keys are `<sender id>_<group uuid>` (both validated before use, safe as names).
 */
internal interface ChunkSpool {
    fun put(key: String, part: Int, envelope: Envelope)
    /** Part numbers stored for [key], without reading them. */
    fun partNumbers(key: String): List<Int>
    /** One stored part, read on demand so a whole file is never in memory; null when missing or unreadable. */
    fun part(key: String, part: Int): Envelope?
    /** Stored parts of [key], without reading them. */
    fun count(key: String): Int = partNumbers(key).size
    fun keys(): List<String>
    fun remove(key: String)
    /** Drops every entry whose envelope has expired. */
    fun purge(now: Long)

    companion object {
        const val MAX_GROUPS = 3
        fun key(peerId: String, group: String) = "${peerId}_$group"
        fun peerOf(key: String) = key.substringBefore('_')
    }
}

internal class MemoryChunkSpool : ChunkSpool {
    private val entries = LinkedHashMap<String, MutableMap<Int, Envelope>>()

    @Synchronized override fun put(key: String, part: Int, envelope: Envelope) {
        if (key !in entries) while (entries.size >= ChunkSpool.MAX_GROUPS) entries.remove(entries.keys.first())
        entries.getOrPut(key) { HashMap() }[part] = envelope
    }
    @Synchronized override fun partNumbers(key: String): List<Int> = entries[key]?.keys?.sorted().orEmpty()
    @Synchronized override fun part(key: String, part: Int): Envelope? = entries[key]?.get(part)
    @Synchronized override fun keys(): List<String> = entries.keys.toList()
    @Synchronized override fun remove(key: String) { entries.remove(key) }
    @Synchronized override fun purge(now: Long) {
        entries.values.forEach { parts -> parts.values.removeAll { it.expiresAt <= now } }
        entries.values.removeAll { it.isEmpty() }
    }
}

/** One directory per file, one `<part>-<expiresAt>.json` per chunk envelope (ciphertext only). */
internal class FileChunkSpool(private val directory: File) : ChunkSpool {
    /** (file, part, expiresAt) for each stored chunk of [key]. */
    private fun entries(key: String): List<Triple<File, Int, Long>> = File(directory, key).listFiles().orEmpty().mapNotNull { file ->
        NAME.matchEntire(file.name)?.let { Triple(file, it.groupValues[1].toInt(), it.groupValues[2].toLong()) }
    }

    @Synchronized override fun put(key: String, part: Int, envelope: Envelope) {
        val group = File(directory, key)
        if (!group.isDirectory) {
            keys().sortedBy { existing -> entries(existing).minOfOrNull { it.third } ?: 0L }
                .take(maxOf(0, keys().size - ChunkSpool.MAX_GROUPS + 1)).forEach(::remove)
            check(group.mkdirs() || group.isDirectory) { "Cannot create chunk spool" }
        }
        group.listFiles().orEmpty().filter { it.name.startsWith("$part-") }.forEach { it.delete() }
        val target = File(group, "$part-${envelope.expiresAt}.json")
        val temporary = File(group, "$part.tmp")
        FileOutputStream(temporary).use { output -> output.write(envelope.encode()); output.fd.sync() }
        check(temporary.renameTo(target)) { "Cannot persist chunk" }
    }

    @Synchronized override fun partNumbers(key: String): List<Int> = entries(key).map { it.second }.sorted()

    @Synchronized override fun part(key: String, part: Int): Envelope? = entries(key).firstOrNull { it.second == part }
        ?.let { (file, _, _) -> runCatching { Envelope.decode(file.readText()) }.getOrNull() }

    @Synchronized override fun keys(): List<String> =
        directory.listFiles().orEmpty().filter { it.isDirectory && KEY.matches(it.name) }.map { it.name }

    @Synchronized override fun remove(key: String) { File(directory, key).deleteRecursively() }

    @Synchronized override fun purge(now: Long) {
        for (key in keys()) {
            entries(key).filter { it.third <= now }.forEach { it.first.delete() }
            File(directory, key).listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            if (entries(key).isEmpty()) remove(key)
        }
    }

    private companion object {
        val NAME = Regex("([0-9]{1,2})-([0-9]{1,19})\\.json")
        val KEY = Regex("[0-9a-f]{64}_[0-9a-f-]{36}")
    }
}
