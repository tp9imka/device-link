package dev.devicelink.sdk.core

import java.io.File
import java.io.FileOutputStream

/**
 * Keeps the still-encrypted chunk envelopes of a file in transfer on the receiver, so a chunk can be
 * acknowledged on the relay as soon as it is stored and a restart mid-transfer resumes instead of losing
 * the file. Holding chunks unacknowledged on the relay instead would deadlock files above the relay's
 * per-mailbox byte quota (64 MiB default; ten 4 MiB chunks are ~71 MiB of envelopes).
 *
 * Bounded: at most [MAX_GROUPS] files in flight, each of at most [Limits.MAX_PARTS] parts, and a file is
 * dropped once no new part of it has arrived for [GROUP_IDLE_MILLIS] (group idle expiry). Each part is
 * kept with the time it was received, so a file still arriving is not lost to its first part's envelope
 * expiry. Keys are `<sender id>_<group uuid>` (both validated before use, safe as names).
 */
internal interface ChunkSpool {
    /** Stores [part] received at [receivedAt]. A part already stored is kept as it is: a redelivery is not progress. */
    fun put(key: String, part: Int, envelope: Envelope, receivedAt: Long)
    /** Part numbers stored for [key], without reading them. */
    fun partNumbers(key: String): List<Int>
    /** One stored part, read on demand so a whole file is never in memory; null when missing or unreadable. */
    fun part(key: String, part: Int): Envelope?
    /** When [part] of [key] was stored; null when it is missing. */
    fun receivedAt(key: String, part: Int): Long?
    /** Stored parts of [key], without reading them. */
    fun count(key: String): Int = partNumbers(key).size
    fun keys(): List<String>
    fun remove(key: String)
    /** Group idle expiry: drops every file with no new part for [GROUP_IDLE_MILLIS] and returns their keys. */
    fun purge(now: Long): List<String>

    companion object {
        const val MAX_GROUPS = 3
        /** One envelope lifetime without a new part ends a file in transfer. */
        const val GROUP_IDLE_MILLIS = Limits.DEFAULT_LIFETIME_MILLIS
        fun key(peerId: String, group: String) = "${peerId}_$group"
        fun peerOf(key: String) = key.substringBefore('_')
    }
}

internal class MemoryChunkSpool : ChunkSpool {
    private class Stored(val envelope: Envelope, val receivedAt: Long)
    private val entries = LinkedHashMap<String, MutableMap<Int, Stored>>()

    @Synchronized override fun put(key: String, part: Int, envelope: Envelope, receivedAt: Long) {
        if (key !in entries) while (entries.size >= ChunkSpool.MAX_GROUPS) entries.remove(entries.keys.first())
        entries.getOrPut(key) { HashMap() }.putIfAbsent(part, Stored(envelope, receivedAt))
    }
    @Synchronized override fun partNumbers(key: String): List<Int> = entries[key]?.keys?.sorted().orEmpty()
    @Synchronized override fun part(key: String, part: Int): Envelope? = entries[key]?.get(part)?.envelope
    @Synchronized override fun receivedAt(key: String, part: Int): Long? = entries[key]?.get(part)?.receivedAt
    @Synchronized override fun keys(): List<String> = entries.keys.toList()
    @Synchronized override fun remove(key: String) { entries.remove(key) }
    @Synchronized override fun purge(now: Long): List<String> {
        val idle = entries.filterValues { parts -> parts.isEmpty() || now - parts.values.maxOf { it.receivedAt } >= ChunkSpool.GROUP_IDLE_MILLIS }.keys.toList()
        idle.forEach(entries::remove)
        return idle
    }
}

/**
 * One directory per file, one `<part>-<receivedAt>.chunk` per chunk envelope (ciphertext only). Anything
 * else in a file's directory (a torn write, or a `<part>-<expiresAt>.json` from an older version) is
 * deleted on purge.
 */
internal class FileChunkSpool(private val directory: File) : ChunkSpool {
    /** (file, part, receivedAt) for each stored chunk of [key]. */
    private fun entries(key: String): List<Triple<File, Int, Long>> = File(directory, key).listFiles().orEmpty().mapNotNull { file ->
        NAME.matchEntire(file.name)?.let { Triple(file, it.groupValues[1].toInt(), it.groupValues[2].toLong()) }
    }

    /** When the newest part of [key] arrived; null when it holds none. */
    private fun lastProgress(key: String): Long? = entries(key).maxOfOrNull { it.third }

    @Synchronized override fun put(key: String, part: Int, envelope: Envelope, receivedAt: Long) {
        val group = File(directory, key)
        if (!group.isDirectory) {
            keys().sortedBy { lastProgress(it) ?: 0L }
                .take(maxOf(0, keys().size - ChunkSpool.MAX_GROUPS + 1)).forEach(::remove)
            check(group.mkdirs() || group.isDirectory) { "Cannot create chunk spool" }
        }
        if (entries(key).any { it.second == part }) return
        val target = File(group, "$part-$receivedAt.chunk")
        val temporary = File(group, "$part.tmp")
        FileOutputStream(temporary).use { output -> output.write(envelope.encode()); output.fd.sync() }
        check(temporary.renameTo(target)) { "Cannot persist chunk" }
    }

    @Synchronized override fun partNumbers(key: String): List<Int> = entries(key).map { it.second }.sorted()

    @Synchronized override fun part(key: String, part: Int): Envelope? = entries(key).firstOrNull { it.second == part }
        ?.let { (file, _, _) -> runCatching { Envelope.decode(file.readText()) }.getOrNull() }

    @Synchronized override fun receivedAt(key: String, part: Int): Long? = entries(key).firstOrNull { it.second == part }?.third

    @Synchronized override fun keys(): List<String> =
        directory.listFiles().orEmpty().filter { it.isDirectory && KEY.matches(it.name) }.map { it.name }

    @Synchronized override fun remove(key: String) { File(directory, key).deleteRecursively() }

    @Synchronized override fun purge(now: Long): List<String> = keys().filter { key ->
        File(directory, key).listFiles().orEmpty().filter { !NAME.matches(it.name) }.forEach { it.delete() }
        val last = lastProgress(key)
        (last == null || now - last >= ChunkSpool.GROUP_IDLE_MILLIS).also { idle -> if (idle) remove(key) }
    }

    private companion object {
        val NAME = Regex("([0-9]{1,2})-([0-9]{1,19})\\.chunk")
        val KEY = Regex("[0-9a-f]{64}_[0-9a-f-]{36}")
    }
}
