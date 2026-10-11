package dev.devicelink.sdk.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class LinkedPeer(val bundle: KeyBundle, val linkedAt: Long, val pairingCode: String? = null) {
    val id: String get() = bundle.id
    val name: String get() = bundle.name
    val platform: String get() = bundle.platform
}

/** Everything the engine persists. Contains public keys and counters only, never content. */
@Serializable
data class LinkState(
    val relayUrl: String = "",
    val enrollmentToken: String = "",
    val registeredRelay: String = "",
    val sequence: Long = 0,
    val peers: List<LinkedPeer> = emptyList(),
    /** Envelope ID -> expiry. Duplicate deliveries are acknowledged without reprocessing. */
    val seen: Map<String, Long> = emptyMap(),
    /** Sender ID -> highest applied clipboard sequence. */
    val lastClip: Map<String, Long> = emptyMap(),
    /** Server-side revocations not yet confirmed by the relay. */
    val pendingRevocations: Set<String> = emptySet(),
    val registeredMetadata: DeviceMetadata? = null,
)

interface LinkStore {
    fun load(): LinkState
    fun save(state: LinkState)

    /**
     * Load -> modify -> save as one step. The engine writes only through this, so concurrent writers never
     * drop each other's `seen`, `lastClip` or `sequence` updates. The default is not atomic; stores shared
     * between threads or processes override it.
     */
    fun update(transform: (LinkState) -> LinkState): LinkState = transform(load()).also(::save)
}

class MemoryLinkStore(private var state: LinkState = LinkState()) : LinkStore {
    @Synchronized override fun load() = state
    @Synchronized override fun save(state: LinkState) { this.state = state }
    @Synchronized override fun update(transform: (LinkState) -> LinkState): LinkState = transform(state).also { state = it }
}

/** Atomic JSON file (write temp, fsync, rename). Place it in app-private storage. */
class FileLinkStore(private val file: File) : LinkStore {
    /** Encrypted chunks of files in transfer live next to the link state (same storage class, no backup). */
    internal val chunkDirectory: File get() = File(file.parentFile, file.name + ".chunks")

    /** Lock-free: writes are an atomic rename, so a reader always sees a whole file. */
    override fun load(): LinkState = read()

    override fun save(state: LinkState) { exclusive { write(state) } }

    /** Re-reads the file under an exclusive lock: a JVM-wide monitor per file plus a `<name>.lock` file lock for other processes. */
    override fun update(transform: (LinkState) -> LinkState): LinkState = exclusive { transform(read()).also(::write) }

    private fun read(): LinkState = runCatching {
        if (!file.isFile) LinkState() else wireJson.decodeFromString<LinkState>(file.readText())
    }.getOrDefault(LinkState())

    private fun write(state: LinkState) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temporary).use { output ->
            output.write(wireJson.encodeToString(state).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Cannot persist link state" }
    }

    private fun <T> exclusive(body: () -> T): T {
        file.parentFile?.mkdirs()
        val path = file.absoluteFile.normalize().path
        // FileChannel locks are per JVM, so threads (and several stores on one file) also need a shared monitor.
        synchronized(monitors.computeIfAbsent(path) { Any() }) {
            java.io.RandomAccessFile(File(file.parentFile, file.name + ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use { return body() }
            }
        }
    }

    private companion object { val monitors = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
