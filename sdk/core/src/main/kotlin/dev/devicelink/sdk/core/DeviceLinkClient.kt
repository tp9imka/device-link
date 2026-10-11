package dev.devicelink.sdk.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LinkConfig(
    val deviceName: String,
    val platform: String,
    val model: String = "",
    val appVersion: String = "",
    /** Allow `http://` relays on loopback/private networks (debug builds, LAN testing). */
    val allowInsecureRelay: Boolean = false,
    val lifetimeMillis: Long = Limits.DEFAULT_LIFETIME_MILLIS,
    val pollWaitSeconds: Int = 25,
    /**
     * Relay built into the app (private build configuration). The first device then needs no setup:
     * it creates its identity, registers on first use and can show a pairing QR immediately.
     */
    val defaultRelayUrl: String = "",
    val defaultEnrollmentToken: String = "",
)

sealed interface OutgoingContent {
    /** [html] is an optional rich-text alternative; [sensitive] hides previews on the receiver. */
    data class Text(val text: String, val html: String? = null, val sensitive: Boolean = false) : OutgoingContent
    class Image(val name: String, val mime: String, val bytes: ByteArray) : OutgoingContent
    class File(val name: String, val mime: String, val bytes: ByteArray) : OutgoingContent

    /**
     * An image or file read from [open] while it is sent, one chunk at a time, so a large file is never held
     * whole in memory. [size] must be exact: it is checked against the limits before anything is sent, and
     * a source that yields a different length fails the send. [open] is called once per send.
     */
    class Source(val kind: PayloadKind, val name: String, val mime: String, val size: Long, val open: () -> InputStream) : OutgoingContent {
        init { require(kind == PayloadKind.IMAGE || kind == PayloadKind.FILE) }
    }
}

/** The content of an [OutgoingContent.Source] could not be read; reported as [SendOutcome.SEND_FAILED]. */
private class SourceReadException(cause: Throwable) : Exception("Could not read the content", cause)

/** A decrypted item from a linked peer, handed to the platform layer to apply. */
data class IncomingItem(
    val id: String,
    val peer: LinkedPeer,
    val payload: Payload,
    val expiresAt: Long,
    /** A newer clip from the same peer was already applied; show it, do not overwrite the clipboard. */
    val stale: Boolean,
    /** Writes a chunked file part by part from the receive spool; null for single-envelope items. */
    private val chunked: ((OutputStream) -> Unit)? = null,
) {
    val kind: PayloadKind get() = payload.kind
    val text: String? get() = payload.text
    val html: String? get() = payload.html
    val sensitive: Boolean get() = payload.sensitive == true
    val fileName: String? get() = payload.name
    val mime: String get() = payload.mime ?: "text/plain"

    /** Whole content in memory. Prefer [writeTo] for files: a chunked file can be 40 MB. */
    fun bytes(): ByteArray? = if (chunked != null) ByteArrayOutputStream().also(chunked).toByteArray() else payload.dataBytes()

    /**
     * Streams an image or file to [output], holding at most one chunk at a time. A chunked file is read from
     * the receive spool, so call this (or [bytes]) inside the receive handler; the spool is cleared after it.
     */
    fun writeTo(output: OutputStream) {
        if (chunked != null) chunked.invoke(output) else output.write(requireNotNull(payload.dataBytes()) { "No content" })
    }
}

/** Snapshot for debug screens: where the device talks to and what happened last. Never contains content. */
data class Diagnostics(
    val deviceId: String,
    val relayUrl: String,
    val registered: Boolean,
    val peers: Int,
    val lastPollAt: Long? = null,
    val lastReceiveAt: Long? = null,
    val lastSendAt: Long? = null,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val received: Int = 0,
    val sent: Int = 0,
    /** Envelopes from a linked peer that failed to authenticate or decrypt (for example after a silent re-key). */
    val undecryptable: Int = 0,
)

/**
 * Result of sending one item to one peer.
 *
 * [error] is null when the relay accepted every envelope of the item. Otherwise it is one of:
 * - the relay's JSON `error` code when the relay answered (for example `recipient_not_authorized`,
 *   `mailbox_full`, `rate_limit`, `payload_too_large`), or `http_<status>` when its answer had no code;
 * - [NETWORK]: no HTTP answer (connection refused, reset or dropped, DNS or TLS failure);
 * - [TIMEOUT]: the connection or the response timed out;
 * - [SEND_FAILED]: a local failure before or after the upload (for example the link file could not be saved).
 * Transient failures (408, 429 other than `mailbox_full`, 5xx, network, timeout) were already retried.
 */
data class SendOutcome(val peerId: String, val itemId: String?, val error: String? = null) {
    val accepted: Boolean get() = itemId != null && error == null

    companion object {
        const val NETWORK = "network"
        const val TIMEOUT = "timeout"
        const val SEND_FAILED = "send_failed"
    }
}

enum class ReceiverStatus { IDLE, CONNECTING, ONLINE, OFFLINE, NEEDS_SETUP, REJECTED }

/** Outcome of [DeviceLinkClient.drain]. */
data class DrainResult(
    /** Items that reached the handler (receipts, unlink notices and duplicates are not counted). */
    val applied: Int,
    /** The relay answered with an empty mailbox: nothing is left waiting. */
    val complete: Boolean,
    /** Another mailbox poll by this device was active (`poll_already_active`), so draining stopped early. */
    val collided: Boolean = false,
)

sealed interface LinkEvent {
    data class PeerLinked(val peer: LinkedPeer) : LinkEvent
    data class PeerUnlinked(val peerId: String, val byRemote: Boolean) : LinkEvent
    data class Receipt(val itemId: String, val peerId: String, val status: ReceiptStatus) : LinkEvent
}

class PairingException(message: String) : Exception(message)

/** Pause between pairing polls; real relays long-poll, this only guards against a tight loop. */
private const val POLL_PAUSE_MILLIS = 200L

/** Relay error for a second concurrent mailbox poll by the same device; see [DeviceLinkClient.runReceiver]. */
internal const val POLL_ALREADY_ACTIVE = "poll_already_active"
private const val POLL_COLLISION_MILLIS = 2_000L
private const val MAILBOX_FULL = "mailbox_full"
/** How long one envelope waits for a full mailbox to drain, counted from the first `mailbox_full` answer. */
internal const val MAILBOX_FULL_WAIT_MILLIS = 90_000L
private const val MAILBOX_FULL_PAUSE_MILLIS = 2_000L
private const val PEER_LIMIT = "peer_limit"

/** Pauses before the 2nd, 3rd and 4th upload attempt of one envelope (4 attempts, about 7 s in total). */
internal val UPLOAD_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L)

/**
 * Relay failures worth resending the same request for (uploads, pairing confirm and allow): 408, 429 (except
 * `mailbox_full`, which has its own wait, and `peer_limit`, which only an unlink clears), 5xx, and transport
 * errors other than certificate failures. Other 4xx (400, 401, 403, 404, 409, 413) cannot succeed on a retry.
 */
internal fun isTransientUploadFailure(failure: Throwable): Boolean = when (failure) {
    is RelayException -> failure.code != MAILBOX_FULL && failure.code != PEER_LIMIT &&
        (failure.status == 408 || failure.status == 429 || failure.status in 500..599)
    is SSLPeerUnverifiedException -> false
    is IOException -> generateSequence<Throwable>(failure) { it.cause }.none { it is CertificateException }
    else -> false
}

/**
 * Platform-neutral DeviceLink engine: QR pairing, fan-out send, receive loop with receipts.
 * Android and the JVM CLI wrap it; the Swift package mirrors it one-to-one.
 */
class DeviceLinkClient(
    private val identity: DeviceIdentity,
    private val encryption: EncryptionKeyPair,
    private val store: LinkStore,
    private val config: LinkConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val deviceId: String = identity.deviceId
    val localBundle: KeyBundle = KeyBundle.create(identity, encryption, config.deviceName, config.platform)
    private val crypto = EnvelopeCrypto(identity, encryption)
    private val lock = Mutex()
    private val mutableEvents = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LinkEvent> = mutableEvents.asSharedFlow()
    init {
        if (store.load().relayUrl.isEmpty() && config.defaultRelayUrl.isNotEmpty()) {
            val relayUrl = RelayUrl.normalize(config.defaultRelayUrl, config.allowInsecureRelay)
            store.update { state ->
                if (state.relayUrl.isNotEmpty()) state else state.copy(relayUrl = relayUrl, enrollmentToken = config.defaultEnrollmentToken)
            }
        }
    }

    private val mutablePeers = MutableStateFlow(store.load().peers)
    val peers: StateFlow<List<LinkedPeer>> = mutablePeers.asStateFlow()
    private val mutableStatus = MutableStateFlow(ReceiverStatus.IDLE)
    val status: StateFlow<ReceiverStatus> = mutableStatus.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(Diagnostics(deviceId, store.load().relayUrl,
        store.load().registeredRelay == store.load().relayUrl && store.load().relayUrl.isNotEmpty(), store.load().peers.size))
    val diagnostics: StateFlow<Diagnostics> = mutableDiagnostics.asStateFlow()
    private val spool: ChunkSpool = (store as? FileLinkStore)?.let { FileChunkSpool(it.chunkDirectory) } ?: MemoryChunkSpool()
    /** Spooled files left by a previous process are resumed once, on the first receive. */
    @Volatile private var spoolResumed = false

    private fun note(transform: (Diagnostics) -> Diagnostics) {
        val state = store.load()
        mutableDiagnostics.value = transform(mutableDiagnostics.value).copy(relayUrl = state.relayUrl, peers = state.peers.size,
            registered = state.relayUrl.isNotEmpty() && state.registeredRelay == state.relayUrl)
    }

    private fun failure(error: Throwable) = note { it.copy(lastError = when (error) {
        is RelayException -> "relay ${error.status} ${error.code}"
        is IOException -> "network: ${error.javaClass.simpleName}"
        else -> error.javaClass.simpleName
    }, lastErrorAt = clock()) }

    val relayUrl: String get() = store.load().relayUrl
    val isConfigured: Boolean get() = relayUrl.isNotEmpty()

    private fun relay(url: String = relayUrl): RelayClient {
        if (url.isEmpty()) throw PairingException("Relay is not configured")
        return RelayClient(url, identity, transport, clock)
    }

    private suspend fun update(transform: (LinkState) -> LinkState): LinkState = lock.withLock {
        val now = clock()
        val next = store.update { current -> transform(current).let { state -> state.copy(seen = state.seen.filterValues { it > now }) } }
        mutablePeers.value = next.peers
        next
    }

    /** Sets the relay (for example from an admin setup link). Existing links stay bound to their relay. */
    suspend fun configure(relayUrl: String, enrollmentToken: String = "") {
        val normalized = RelayUrl.normalize(relayUrl, config.allowInsecureRelay)
        update { state ->
            if (state.relayUrl == normalized) state.copy(enrollmentToken = enrollmentToken)
            else {
                if (state.peers.isNotEmpty()) throw PairingException("Unlink existing devices before switching relay")
                state.copy(relayUrl = normalized, enrollmentToken = enrollmentToken, registeredRelay = "", registeredMetadata = null)
            }
        }
    }

    suspend fun applySetupLink(text: String): Boolean {
        val link = SetupLink.parse(text, config.allowInsecureRelay) ?: return false
        configure(link.relayUrl, link.enrollmentToken)
        return true
    }

    suspend fun ensureRegistered(pairingId: String? = null, force: Boolean = false) {
        val state = store.load()
        val metadata = DeviceMetadata(config.platform, config.model, config.appVersion)
        val refresh = !force && pairingId == null && state.relayUrl.isNotEmpty() && state.registeredRelay == state.relayUrl
        if (refresh && state.registeredMetadata == metadata) return
        try {
            relay().register(metadata, state.enrollmentToken, pairingId)
        } catch (failure: RelayException) {
            if (!refresh) throw failure
            // Older relays may require enrollment again; metadata must not cut off an existing device.
        }
        update { it.copy(registeredRelay = state.relayUrl, registeredMetadata = metadata) }
    }

    /** Runs [block], re-registering once if the relay forgot this device (for example after a reset). */
    private suspend fun <T> registered(block: suspend (RelayClient) -> T): T {
        ensureRegistered()
        return try {
            block(relay())
        } catch (failure: RelayException) {
            if (failure.code != "registration_required") throw failure
            ensureRegistered(force = true)
            block(relay())
        }
    }

    inner class PairingSession internal constructor(val invite: PairingInvite, val expiresAt: Long) {
        /** QR payload. */
        val uri: String get() = invite.uri

        /**
         * Waits for a joiner, pins its bundle, allows it on the relay and confirms. The link is saved and
         * announced only once the relay accepted the confirmation, so a failed confirm never leaves the
         * inviter showing a link the joiner does not have. A confirm whose response was lost is checked
         * against the pairing state; if it really failed, the relay allowance is withdrawn and this throws.
         */
        suspend fun awaitPeer(): LinkedPeer {
            val relay = relay(invite.relayUrl)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (clock() >= expiresAt) throw PairingException("Pairing code expired")
                val status = try { relay.pollPairing(invite.pairingId, 25) } catch (failure: IOException) { delay(2_000); continue }
                when (status.state) {
                    "open" -> delay(POLL_PAUSE_MILLIS)
                    "joined", "confirmed" -> {
                        val bundle = invite.openJoin(requireNotNull(status.sealed))
                        if (bundle.id != status.joinerId || bundle.id == deviceId) throw PairingException("Pairing data does not match joiner")
                        allow(relay, bundle.id)
                        // "confirmed" here means an earlier confirm by this inviter was accepted (response lost).
                        if (status.state == "joined") confirm(relay, bundle.id)
                        val peer = LinkedPeer(bundle, clock(), invite.confirmationCode(bundle.id))
                        update { state -> state.copy(peers = state.peers.filterNot { it.id == bundle.id } + peer,
                            pendingRevocations = state.pendingRevocations - bundle.id) }
                        mutableEvents.tryEmit(LinkEvent.PeerLinked(peer))
                        return peer
                    }
                    else -> throw PairingException("Pairing ${status.state}")
                }
            }
        }

        /**
         * Allows the joiner on the relay before confirming. `PUT /v1/peers` is idempotent, so a transient failure
         * ([isTransientUploadFailure]) is resent up to [UPLOAD_RETRY_DELAYS_MILLIS] more times within the
         * pairing's lifetime. Anything else (403, 429 `peer_limit`) throws at once; nothing was confirmed yet.
         */
        private suspend fun allow(relay: RelayClient, joinerId: String) {
            var attempt = 0
            while (true) {
                try {
                    relay.allow(joinerId)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!isTransientUploadFailure(failure) || attempt >= UPLOAD_RETRY_DELAYS_MILLIS.size || clock() >= expiresAt) {
                        failure(failure)
                        throw failure
                    }
                    delay(UPLOAD_RETRY_DELAYS_MILLIS[attempt++])
                }
            }
        }

        /**
         * Confirms the joiner. The confirm is sealed once and every attempt resends that same body, which the
         * relay accepts as idempotent. After a failure the pairing is re-read (the relay keeps it until it
         * expires): `confirmed` means the response was lost and the link stands. A transient failure with the
         * pairing still `joined`, or a re-read that failed too, is resent up to [UPLOAD_RETRY_DELAYS_MILLIS]
         * more times within the pairing's lifetime. Anything else withdraws the joiner's allowance and throws.
         */
        private suspend fun confirm(relay: RelayClient, joinerId: String) {
            val sealed = invite.sealConfirm(localBundle)
            var attempt = 0
            while (true) {
                val failure = try {
                    relay.confirmPairing(invite.pairingId, sealed)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure
                }
                val state = try {
                    relay.pollPairing(invite.pairingId, 0).state
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (state == "confirmed") return
                val retry = (state == null || state == "joined") && isTransientUploadFailure(failure) &&
                    attempt < UPLOAD_RETRY_DELAYS_MILLIS.size && clock() < expiresAt
                if (retry) {
                    delay(UPLOAD_RETRY_DELAYS_MILLIS[attempt++])
                    continue
                }
                // Keep an existing link to the same device (re-pairing) allowed.
                if (store.load().peers.none { it.id == joinerId }) runCatching { relay.revoke(joinerId) }
                failure(failure)
                throw PairingException(if (failure is RelayException) "Relay refused pairing (${failure.code})" else "Could not reach the relay to confirm the link")
            }
        }

        suspend fun cancel() { runCatching { relay(invite.relayUrl).cancelPairing(invite.pairingId) } }
    }

    /** Creates a one-time QR invitation valid for [lifetimeMillis] (default 5 minutes). */
    suspend fun invite(lifetimeMillis: Long = 5 * 60_000L): PairingSession {
        require(lifetimeMillis in 30_000L..10 * 60_000L)
        val invite = PairingInvite.create(relayUrl.ifEmpty { throw PairingException("Relay is not configured") }, deviceId)
        val expiresAt = clock() + lifetimeMillis
        registered { it.createPairing(invite.pairingId, expiresAt) }
        return PairingSession(invite, expiresAt)
    }

    /** Joins from a scanned QR / opened link. Adopts the invite's relay when this device has no links yet. */
    suspend fun join(uri: String): LinkedPeer {
        val invite = PairingInvite.parse(uri, config.allowInsecureRelay) ?: throw PairingException("Not a DeviceLink pairing code")
        if (invite.inviterId == deviceId) throw PairingException("Scan the code on the other device")
        if (relayUrl != invite.relayUrl) configure(invite.relayUrl, "")
        ensureRegistered(pairingId = invite.pairingId)
        val relay = relay()
        try {
            relay.joinPairing(invite.pairingId, invite.sealJoin(localBundle))
        } catch (failure: RelayException) {
            throw PairingException(if (failure.status in listOf(404, 409, 410)) "Pairing code expired or already used" else "Relay refused pairing (${failure.code})")
        }
        val deadline = clock() + 2 * 60_000L
        while (clock() < deadline) {
            currentCoroutineContext().ensureActive()
            val status = try { relay.pollPairing(invite.pairingId, 25) } catch (failure: IOException) { delay(2_000); continue }
            when (status.state) {
                "joined" -> delay(POLL_PAUSE_MILLIS)
                "confirmed" -> {
                    val bundle = invite.openConfirm(requireNotNull(status.sealed))
                    if (bundle.id != invite.inviterId) throw PairingException("Inviter identity does not match the code")
                    // The relay keeps the pairing readable until it expires, but the confirm is already in
                    // hand: a network error on the allow is retried in place instead of polling again.
                    var attempt = 0
                    while (true) {
                        try { relay.allow(bundle.id); break } catch (failure: IOException) {
                            if (++attempt >= 5 || clock() >= deadline) throw failure
                            delay(2_000L * attempt)
                        }
                    }
                    val peer = LinkedPeer(bundle, clock(), invite.confirmationCode(deviceId))
                    update { state -> state.copy(peers = state.peers.filterNot { it.id == bundle.id } + peer,
                        pendingRevocations = state.pendingRevocations - bundle.id) }
                    mutableEvents.tryEmit(LinkEvent.PeerLinked(peer))
                    return peer
                }
                else -> throw PairingException("Pairing ${status.state}")
            }
        }
        throw PairingException("The other device did not confirm in time")
    }

    /** Removes a link locally at once, tells the peer, and revokes it on the relay (retried later if offline). */
    suspend fun unlink(peerId: String) {
        val peer = store.load().peers.firstOrNull { it.id == peerId }
        if (peer != null) runCatching { deliver(Payload.unlink(), peer) }
        update { state -> state.copy(peers = state.peers.filterNot { it.id == peerId }, pendingRevocations = state.pendingRevocations + peerId,
            lastClip = state.lastClip - peerId) }
        mutableEvents.tryEmit(LinkEvent.PeerUnlinked(peerId, byRemote = false))
        runCatching { flushRevocations() }
    }

    /** "Remove this device everywhere": tells every peer, revokes them on the relay, forgets them locally. */
    suspend fun unlinkAll() { store.load().peers.forEach { unlink(it.id) } }

    /**
     * First step of "Reset device". Sends every linked peer an unlink notice (one attempt each), then asks the
     * relay to delete this device (`DELETE /v1/devices/self`: its links, waiting messages to it, pairings and
     * push token). The relay keeps the notices just sent, so a peer that is offline drops the link when it
     * next polls instead of sending into a void.
     *
     * Best effort: never throws for a network or relay failure. Returns true only when the relay confirmed
     * the delete. Whatever the result, and even when cancelled (wrap it in a short timeout), the local link
     * state is cleared: peers, pending revocations, replay ledger, clip sequences, chunks in transfer and the
     * registration mark. The relay URL and enrollment token are kept, so the device can link again. Callers
     * then wipe keys and app state as usual, which keeps reset working offline.
     */
    suspend fun unregister(): Boolean {
        val state = store.load()
        try {
            if (state.relayUrl.isEmpty()) return false
            for (peer in state.peers) {
                try { deliver(Payload.unlink(), peer, retry = false) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            }
            return try {
                relay().unregister()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure(failure)
                false
            }
        } finally {
            withContext(NonCancellable) {
                update { LinkState(relayUrl = it.relayUrl, enrollmentToken = it.enrollmentToken) }
                spool.keys().forEach(spool::remove)
                note { it }
                state.peers.forEach { mutableEvents.tryEmit(LinkEvent.PeerUnlinked(it.id, byRemote = false)) }
            }
        }
    }

    private suspend fun flushRevocations() {
        for (id in store.load().pendingRevocations) {
            registered { it.revoke(id) }
            update { it.copy(pendingRevocations = it.pendingRevocations - id) }
        }
    }

    /** Encrypts [content] separately for each target peer (all linked peers by default). */
    suspend fun send(content: OutgoingContent, peerIds: Collection<String>? = null): List<SendOutcome> {
        val now = clock()
        val source = when (content) {
            is OutgoingContent.Text -> null
            is OutgoingContent.Image -> OutgoingContent.Source(PayloadKind.IMAGE, content.name, content.mime, content.bytes.size.toLong()) { ByteArrayInputStream(content.bytes) }
            is OutgoingContent.File -> OutgoingContent.Source(PayloadKind.FILE, content.name, content.mime, content.bytes.size.toLong()) { ByteArrayInputStream(content.bytes) }
            is OutgoingContent.Source -> content
        }
        require(source == null || source.size <= Limits.MAX_FILE_BYTES) { "File is larger than ${Limits.MAX_FILE_BYTES / (1024 * 1024)} MB" }
        val targets = store.load().peers.filter { peerIds == null || it.id in peerIds }
        if (source != null && source.size > Limits.CHUNK_BYTES) return sendChunked(source, targets, now)
        val payload = when (content) {
            is OutgoingContent.Text -> Payload.text(content.text, now, content.html, content.sensitive)
            is OutgoingContent.Image -> Payload.image(content.name, content.mime, content.bytes, now)
            is OutgoingContent.File -> Payload.file(content.name, content.mime, content.bytes, now)
            is OutgoingContent.Source -> {
                val bytes = try {
                    readSource { content.open().use { input -> readPart(input, content.size.toInt()).also { expectEnd(input) } } }
                } catch (failure: SourceReadException) {
                    return outcomes(targets, targets.associate { it.id to failure }) { null }
                }
                Payload(content.kind, name = content.name, mime = content.mime, data = Encoding.b64(bytes), sentAt = now)
            }
        }
        payload.validate()
        val failures = HashMap<String, Exception>()
        val ids = targets.associate { peer -> peer.id to deliverTracked(payload, peer, failures) }
        return outcomes(targets, failures) { ids.getValue(it) }
    }

    /**
     * Sends a chunked file holding at most one chunk (its bytes, payload and envelope) at a time: each chunk is
     * read from the source once, then sealed and uploaded to every target in turn. A peer whose chunk failed
     * gets no further chunks; the others continue. A chunked file is one item for receipts and UI: its group ID.
     */
    private suspend fun sendChunked(source: OutgoingContent.Source, targets: List<LinkedPeer>, now: Long): List<SendOutcome> {
        val parts = Payload.partsFor(source.size)
        val group = UUID.randomUUID().toString()
        val failures = HashMap<String, Exception>()
        var input: InputStream? = null
        try {
            val stream = readSource { source.open() }.also { input = it }
            for (part in 0 until parts) {
                val active = targets.filter { it.id !in failures }
                if (active.isEmpty()) break
                val length = minOf(Limits.CHUNK_BYTES.toLong(), source.size - part.toLong() * Limits.CHUNK_BYTES).toInt()
                val slice = readSource { readPart(stream, length) }
                if (part == parts - 1) readSource { expectEnd(stream) }
                val payload = Payload.chunk(source.kind, source.name, source.mime, slice, part, parts, source.size, now, group)
                payload.validate()
                active.forEach { deliverTracked(payload, it, failures) }
            }
        } catch (failure: SourceReadException) {
            targets.forEach { failures.putIfAbsent(it.id, failure) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { input?.close() } }
        }
        return outcomes(targets, failures) { group }
    }

    /** Delivers [payload], recording a failure for [peer] in [failures] instead of throwing. */
    private suspend fun deliverTracked(payload: Payload, peer: LinkedPeer, failures: MutableMap<String, Exception>): String? = try {
        deliver(payload, peer)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failures[peer.id] = failure
        null
    }

    private fun outcomes(targets: List<LinkedPeer>, failures: Map<String, Exception>, itemId: (String) -> String?): List<SendOutcome> =
        targets.map { peer ->
            val failure = failures[peer.id]
            if (failure == null) {
                note { it.copy(lastSendAt = clock(), sent = it.sent + 1) }
                SendOutcome(peer.id, itemId(peer.id))
            } else {
                failure(failure)
                SendOutcome(peer.id, null, sendError(failure))
            }
        }

    private suspend fun <T> readSource(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (failure: IOException) { throw SourceReadException(failure) }
    }

    private fun readPart(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IOException("Content is shorter than its size")
            offset += count
        }
        return bytes
    }

    private fun expectEnd(input: InputStream) { if (input.read() >= 0) throw IOException("Content is longer than its size") }

    private fun sendError(failure: Exception): String = when (failure) {
        is RelayException -> failure.code
        is SocketTimeoutException -> SendOutcome.TIMEOUT
        is IOException -> SendOutcome.NETWORK
        else -> SendOutcome.SEND_FAILED
    }

    /**
     * Seals [payload] once and uploads it. Each envelope is retried on its own, so a failure on chunk 7 of 8
     * resends only chunk 7: transient failures ([isTransientUploadFailure]) up to [UPLOAD_RETRY_DELAYS_MILLIS]
     * more times, `429 mailbox_full` every 2 s for up to [MAILBOX_FULL_WAIT_MILLIS] after the first such answer
     * while the receiver drains. Each attempt waits [uploadTimeoutMillis] for the answer. [retry] false makes
     * one attempt (best-effort notices that must not hold up the caller).
     */
    private suspend fun deliver(payload: Payload, peer: LinkedPeer, retry: Boolean = true): String {
        val sequence = update { it.copy(sequence = Math.addExact(it.sequence, 1)) }.sequence
        val envelope = crypto.seal(payload, peer.bundle, clock(), sequence, config.lifetimeMillis)
        var attempt = 0
        // Counted from the first full answer: a slow upload before it must not use up the wait.
        var fullSince: Long? = null
        while (true) {
            try {
                // Retries resend the exact same envelope: the relay treats it as idempotent.
                registered { it.upload(envelope) }
                return envelope.id
            } catch (failure: Exception) {
                if (failure is CancellationException || !retry) throw failure
                // A full mailbox drains as the receiver acknowledges; large chunked files rely on this.
                if (failure is RelayException && failure.code == MAILBOX_FULL) {
                    val since = fullSince ?: clock().also { fullSince = it }
                    if (clock() - since < MAILBOX_FULL_WAIT_MILLIS) { delay(MAILBOX_FULL_PAUSE_MILLIS); continue }
                }
                if (!isTransientUploadFailure(failure) || attempt >= UPLOAD_RETRY_DELAYS_MILLIS.size) throw failure
                delay(UPLOAD_RETRY_DELAYS_MILLIS[attempt++])
            }
        }
    }

    /**
     * Fetches everything waiting without long-polling, until the relay returns an empty mailbox, and
     * applies each item through [handler]. Use it for one-shot "get what is waiting" actions (a
     * notification Copy action, a shortcut) instead of looping on `receiveOnce(0)` until it returns 0:
     * that stops early because receipts, unlink notices and duplicates count 0, and the relay serves a
     * sender's newest envelope (often a receipt) first.
     *
     * At most [maxEnvelopes] envelopes are processed. Envelopes already handled in this call are excluded
     * from the next poll, so a failed acknowledgement cannot loop. Returns `collided = true` instead of
     * throwing when this device's receive loop is polling at the same time (`poll_already_active`);
     * other relay and network errors are thrown.
     */
    suspend fun drain(maxEnvelopes: Int = 100, handler: suspend (IncomingItem) -> ReceiptStatus): DrainResult {
        var applied = resumeSpooled(handler)
        val handled = ArrayDeque<String>()
        try {
            repeat(maxEnvelopes) {
                note { it.copy(lastPollAt = clock()) }
                val envelopes = try {
                    registered { it.poll(0, 1, handled.toList()) }
                } catch (failure: RelayException) {
                    if (failure.code == POLL_ALREADY_ACTIVE) return DrainResult(applied, complete = false, collided = true)
                    failure(failure)
                    throw failure
                } catch (error: Exception) {
                    if (error !is CancellationException) failure(error)
                    throw error
                }
                if (envelopes.isEmpty()) return DrainResult(applied, complete = true)
                for (envelope in envelopes) {
                    if (process(envelope, handler)) applied++
                    handled.addLast(envelope.id)
                    if (handled.size > 20) handled.removeFirst()
                }
            }
            return DrainResult(applied, complete = false)
        } finally {
            if (applied > 0) note { it.copy(lastReceiveAt = clock(), received = it.received + applied) }
        }
    }

    /**
     * Fetches and processes at most one envelope. [handler] applies the item (clipboard, notification)
     * and returns the receipt status reported back to the sender. Returns how many items reached [handler]
     * (receipts, unlink notices and duplicates are handled internally and not counted), so 0 does not
     * mean the mailbox is empty: use [drain] to fetch everything waiting.
     */
    suspend fun receiveOnce(waitSeconds: Int = config.pollWaitSeconds, handler: suspend (IncomingItem) -> ReceiptStatus): Int {
        val resumed = if (isConfigured) resumeSpooled(handler) else 0
        note { it.copy(lastPollAt = clock()) }
        val envelopes = try { registered { it.poll(waitSeconds, 1) } } catch (error: Exception) {
            if (error !is CancellationException) failure(error)
            if (resumed > 0) note { it.copy(lastReceiveAt = clock(), received = it.received + resumed) }
            throw error
        }
        return (resumed + envelopes.count { process(it, handler) }).also { count ->
            if (count > 0) note { it.copy(lastReceiveAt = clock(), received = it.received + count) }
        }
    }

    /**
     * Delivery order for every envelope: apply -> record (seen, lastClip) -> acknowledge -> receipt.
     * Recording only after the handler ran means a crash never loses an item: the relay delivers it again.
     * The same clip can then reach the clipboard twice, but an OLDER clip never overwrites a newer one,
     * because `lastClip` is saved together with `seen` and the relay serves each sender's newest first.
     */
    private suspend fun process(envelope: Envelope, handler: suspend (IncomingItem) -> ReceiptStatus): Boolean {
        val relay = relay()
        val state = store.load()
        val peer = state.peers.firstOrNull { it.id == envelope.senderId }
        if (envelope.id in state.seen || peer == null) { relay.acknowledge(envelope.id); return false }
        val payload = try { crypto.open(envelope, peer.bundle, clock()) } catch (_: Exception) { null }
        if (payload == null) {
            // It can never decrypt; drop it, but make a silent re-key or tampering visible.
            note { it.copy(undecryptable = it.undecryptable + 1) }
            relay.acknowledge(envelope.id)
            return false
        }
        when (payload.kind) {
            PayloadKind.RECEIPT -> {
                mutableEvents.tryEmit(LinkEvent.Receipt(payload.receiptFor!!, peer.id, payload.status!!))
                update { it.copy(seen = it.seen + (envelope.id to envelope.expiresAt)) }
                acknowledgeApplied(relay, envelope.id)
                return false
            }
            PayloadKind.UNLINK -> {
                update { s -> s.copy(peers = s.peers.filterNot { it.id == peer.id }, pendingRevocations = s.pendingRevocations + peer.id,
                    lastClip = s.lastClip - peer.id, seen = s.seen + (envelope.id to envelope.expiresAt)) }
                acknowledgeApplied(relay, envelope.id)
                mutableEvents.tryEmit(LinkEvent.PeerUnlinked(peer.id, byRemote = true))
                runCatching { flushRevocations() }
                return false
            }
            PayloadKind.TEXT, PayloadKind.IMAGE, PayloadKind.FILE -> {
                if (payload.isChunk) {
                    // Stored (still encrypted) before the acknowledgement, so a restart resumes the file.
                    val key = ChunkSpool.key(peer.id, payload.group!!)
                    spool.purge(clock())
                    spool.put(key, payload.part!!, envelope)
                    acknowledgeApplied(relay, envelope.id)
                    if (spool.count(key) < payload.parts!!) return false
                    return assemble(key, peer, handler)
                }
                val stale = payload.kind != PayloadKind.FILE && envelope.sequence <= (state.lastClip[peer.id] ?: 0)
                val status = apply(IncomingItem(envelope.id, peer, payload, envelope.expiresAt, stale), listOf(envelope), handler)
                acknowledgeApplied(relay, envelope.id)
                runCatching { deliver(Payload.receipt(envelope.id, status), peer) }
                return true
            }
        }
    }

    /** Runs [handler], then records the envelopes as seen (and the clip sequence) in one save. */
    private suspend fun apply(item: IncomingItem, envelopes: List<Envelope>, handler: suspend (IncomingItem) -> ReceiptStatus): ReceiptStatus {
        val status = try {
            handler(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceiptStatus.FAILED
        }
        val sequence = envelopes.maxOf { it.sequence }
        update { s ->
            s.copy(seen = s.seen + envelopes.associate { it.id to it.expiresAt },
                lastClip = if (status != ReceiptStatus.COPIED) s.lastClip
                    else s.lastClip + (item.peer.id to maxOf(s.lastClip[item.peer.id] ?: 0, sequence)))
        }
        return status
    }

    /**
     * Acknowledges an envelope whose effect is already recorded. A failure is not fatal: the relay
     * delivers it again, `seen` turns that into a plain acknowledgement, and the receipt still goes now.
     */
    private suspend fun acknowledgeApplied(relay: RelayClient, id: String) {
        try {
            relay.acknowledge(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure(error)
        }
    }

    /** Applies a spooled chunked file once every part is present. Returns whether the handler ran. */
    private suspend fun assemble(key: String, peer: LinkedPeer, handler: suspend (IncomingItem) -> ReceiptStatus): Boolean {
        val state = store.load()
        val now = clock()
        // One part in memory at a time: authenticate it, keep only its metadata and data length.
        class Part(val number: Int, val envelope: Envelope, val payload: Payload?, val length: Int)
        val stored = spool.partNumbers(key).mapNotNull { number ->
            val envelope = spool.part(key, number) ?: return@mapNotNull null
            val payload = runCatching { crypto.open(envelope, peer.bundle, now) }.getOrNull()
            Part(number, envelope.copy(ciphertext = "", signature = ""), payload?.copy(data = null), payload?.dataBytes()?.size ?: 0)
        }
        // Applied and recorded before a crash, only the cleanup was missed.
        if (stored.isNotEmpty() && stored.all { it.envelope.id in state.seen }) { spool.remove(key); return false }
        if (stored.any { it.payload == null || !it.payload.isChunk }) return rejectFile(key, peer)
        val opened = stored.sortedBy { it.payload!!.part }
        val first = opened.firstOrNull()?.payload ?: return false
        val consistent = opened.map { it.payload!!.part } == (0 until first.parts!!).toList() &&
            opened.all { val p = it.payload!!; p.parts == first.parts && p.size == first.size && p.group == first.group && p.kind == first.kind }
        if (!consistent) {
            // Fewer parts than announced: still arriving. As many or more and still inconsistent: never completes.
            return if (opened.size >= first.parts!!) rejectFile(key, peer) else false
        }
        if (opened.sumOf { it.length.toLong() } != first.size) return rejectFile(key, peer)
        val envelopes = opened.map { it.envelope }
        val newest = envelopes.maxBy { it.sequence }
        val stale = first.kind != PayloadKind.FILE && newest.sequence <= (state.lastClip[peer.id] ?: 0)
        val numbers = opened.map { it.number }
        // Second pass while the handler writes: each part is read and opened again, then released.
        val content: (OutputStream) -> Unit = { output ->
            numbers.forEachIndexed { index, number ->
                val envelope = spool.part(key, number) ?: throw IOException("Chunk is gone")
                val payload = crypto.open(envelope, peer.bundle, now)
                check(payload.part == index && payload.group == first.group) { "Chunk changed" }
                output.write(payload.dataBytes()!!)
            }
        }
        val item = IncomingItem(first.group!!, peer, first.copy(part = null, parts = null),
            envelopes.minOf { it.expiresAt }, stale, content)
        val status = apply(item, envelopes, handler)
        spool.remove(key)
        runCatching { deliver(Payload.receipt(item.id, status), peer) }
        return true
    }

    /**
     * Drops a chunked file that can never be assembled (a part does not open, parts disagree, or the total
     * does not match `size`) and tells the sender with a `failed` receipt for the group, so it does not wait
     * for a receipt that never comes. Returns false: the handler did not run.
     */
    private suspend fun rejectFile(key: String, peer: LinkedPeer): Boolean {
        spool.remove(key)
        val group = key.substringAfter('_')
        if (runCatching { java.util.UUID.fromString(group).toString() == group }.getOrDefault(false)) {
            runCatching { deliver(Payload.receipt(group, ReceiptStatus.FAILED), peer) }
        }
        return false
    }

    /** Completes files whose chunks were all stored by a previous process that stopped before applying. */
    private suspend fun resumeSpooled(handler: suspend (IncomingItem) -> ReceiptStatus): Int {
        if (spoolResumed) return 0
        spoolResumed = true
        spool.purge(clock())
        val peers = store.load().peers
        return spool.keys().count { key ->
            val peer = peers.firstOrNull { it.id == ChunkSpool.peerOf(key) }
            if (peer == null) { spool.remove(key); false } else assemble(key, peer, handler)
        }
    }

    /**
     * Decrypts one waiting item for a notification preview without consuming it; the receive loop still
     * applies and acknowledges it. Null for unknown senders, receipts/unlink notices or undecryptable items.
     */
    suspend fun preview(messageId: String): IncomingItem? {
        val envelope = registered { it.fetch(messageId) }
        val peer = store.load().peers.firstOrNull { it.id == envelope.senderId } ?: return null
        val payload = runCatching { crypto.open(envelope, peer.bundle, clock()) }.getOrNull() ?: return null
        if (payload.kind !in setOf(PayloadKind.TEXT, PayloadKind.IMAGE, PayloadKind.FILE)) return null
        return IncomingItem(payload.group ?: envelope.id, peer, payload, envelope.expiresAt, stale = false)
    }

    /** Long-polls until cancelled, with jittered backoff. Cancel the calling job to stop (Off = no polling). */
    suspend fun runReceiver(handler: suspend (IncomingItem) -> ReceiptStatus) {
        var backoff = 1_000L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isConfigured) { mutableStatus.value = ReceiverStatus.NEEDS_SETUP; delay(5_000); continue }
                if (store.load().peers.isEmpty()) { mutableStatus.value = ReceiverStatus.IDLE; delay(3_000); continue }
                try {
                    if (mutableStatus.value != ReceiverStatus.ONLINE) mutableStatus.value = ReceiverStatus.CONNECTING
                    if (store.load().pendingRevocations.isNotEmpty()) flushRevocations()
                    mutableStatus.value = ReceiverStatus.ONLINE
                    receiveOnce(config.pollWaitSeconds, handler)
                    backoff = 1_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RelayException) {
                    failure(failure)
                    if (failure.code == POLL_ALREADY_ACTIVE) {
                        // The relay still holds this device's previous long poll (for example after a reconnect).
                        // Transient: retry soon instead of the 60 s rate-limit backoff, status unchanged.
                        delay(POLL_COLLISION_MILLIS + Random.nextLong(1_000))
                        continue
                    }
                    mutableStatus.value = if (failure.status in listOf(401, 403)) ReceiverStatus.REJECTED else ReceiverStatus.OFFLINE
                    delay(if (failure.status == 429) 60_000L else maxOf(backoff, 15_000L))
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } catch (error: Exception) {
                    failure(error)
                    mutableStatus.value = ReceiverStatus.OFFLINE
                    delay(backoff + Random.nextLong(backoff / 2 + 1))
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        } finally {
            mutableStatus.value = ReceiverStatus.IDLE
        }
    }
}
