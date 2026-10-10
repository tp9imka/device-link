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
)

interface LinkStore {
    fun load(): LinkState
    fun save(state: LinkState)
}

class MemoryLinkStore(private var state: LinkState = LinkState()) : LinkStore {
    @Synchronized override fun load() = state
    @Synchronized override fun save(state: LinkState) { this.state = state }
}

/** Atomic JSON file (write temp, fsync, rename). Place it in app-private storage. */
class FileLinkStore(private val file: File) : LinkStore {
    @Synchronized override fun load(): LinkState = runCatching {
        if (!file.isFile) LinkState() else wireJson.decodeFromString<LinkState>(file.readText())
    }.getOrDefault(LinkState())

    @Synchronized override fun save(state: LinkState) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temporary).use { output ->
            output.write(wireJson.encodeToString(state).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Cannot persist link state" }
    }
}
