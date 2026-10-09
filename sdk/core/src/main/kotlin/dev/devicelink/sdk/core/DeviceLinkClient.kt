package dev.devicelink.sdk.core

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
    data class Text(val text: String) : OutgoingContent
    class Image(val name: String, val mime: String, val bytes: ByteArray) : OutgoingContent
    class File(val name: String, val mime: String, val bytes: ByteArray) : OutgoingContent
}

/** A decrypted item from a linked peer, handed to the platform layer to apply. */
data class IncomingItem(
    val id: String,
    val peer: LinkedPeer,
    val payload: Payload,
    val expiresAt: Long,
    /** A newer clip from the same peer was already applied; show it, do not overwrite the clipboard. */
    val stale: Boolean,
) {
    val kind: PayloadKind get() = payload.kind
    val text: String? get() = payload.text
    val fileName: String? get() = payload.name
    val mime: String get() = payload.mime ?: "text/plain"
    fun bytes(): ByteArray? = payload.dataBytes()
}

data class SendOutcome(val peerId: String, val itemId: String?, val error: String? = null) {
    val accepted: Boolean get() = itemId != null && error == null
}

enum class ReceiverStatus { IDLE, CONNECTING, ONLINE, OFFLINE, NEEDS_SETUP, REJECTED }

sealed interface LinkEvent {
    data class PeerLinked(val peer: LinkedPeer) : LinkEvent
    data class PeerUnlinked(val peerId: String, val byRemote: Boolean) : LinkEvent
    data class Receipt(val itemId: String, val peerId: String, val status: ReceiptStatus) : LinkEvent
}

class PairingException(message: String) : Exception(message)

/** Pause between pairing polls; real relays long-poll, this only guards against a tight loop. */
private const val POLL_PAUSE_MILLIS = 200L

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
        val state = store.load()
        if (state.relayUrl.isEmpty() && config.defaultRelayUrl.isNotEmpty()) {
            store.save(state.copy(relayUrl = RelayUrl.normalize(config.defaultRelayUrl, config.allowInsecureRelay),
                enrollmentToken = config.defaultEnrollmentToken))
        }
    }

    private val mutablePeers = MutableStateFlow(store.load().peers)
    val peers: StateFlow<List<LinkedPeer>> = mutablePeers.asStateFlow()
    private val mutableStatus = MutableStateFlow(ReceiverStatus.IDLE)
    val status: StateFlow<ReceiverStatus> = mutableStatus.asStateFlow()

    val relayUrl: String get() = store.load().relayUrl
    val isConfigured: Boolean get() = relayUrl.isNotEmpty()

    private fun relay(url: String = relayUrl): RelayClient {
        if (url.isEmpty()) throw PairingException("Relay is not configured")
        return RelayClient(url, identity, transport, clock)
    }

    private suspend fun update(transform: (LinkState) -> LinkState): LinkState = lock.withLock {
        val now = clock()
        val next = transform(store.load()).let { state -> state.copy(seen = state.seen.filterValues { it > now }) }
        store.save(next)
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
                state.copy(relayUrl = normalized, enrollmentToken = enrollmentToken, registeredRelay = "")
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
        if (!force && state.registeredRelay == state.relayUrl && pairingId == null) return
        relay().register(DeviceMetadata(config.platform, config.model, config.appVersion), state.enrollmentToken, pairingId)
        update { it.copy(registeredRelay = state.relayUrl) }
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

        /** Waits for a joiner, pins its bundle, allows it on the relay and confirms. */
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
                        relay.allow(bundle.id)
                        val peer = LinkedPeer(bundle, clock())
                        update { state -> state.copy(peers = state.peers.filterNot { it.id == bundle.id } + peer,
                            pendingRevocations = state.pendingRevocations - bundle.id) }
                        relay.confirmPairing(invite.pairingId, invite.sealConfirm(localBundle))
                        mutableEvents.tryEmit(LinkEvent.PeerLinked(peer))
                        return peer
                    }
                    else -> throw PairingException("Pairing ${status.state}")
                }
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
            throw PairingException(if (failure.status == 404 || failure.status == 410) "Pairing code expired or already used" else "Relay refused pairing (${failure.code})")
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
                    relay.allow(bundle.id)
                    val peer = LinkedPeer(bundle, clock())
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

    private suspend fun flushRevocations() {
        for (id in store.load().pendingRevocations) {
            registered { it.revoke(id) }
            update { it.copy(pendingRevocations = it.pendingRevocations - id) }
        }
    }

    /** Encrypts [content] separately for each target peer (all linked peers by default). */
    suspend fun send(content: OutgoingContent, peerIds: Collection<String>? = null): List<SendOutcome> {
        val now = clock()
        val payload = when (content) {
            is OutgoingContent.Text -> Payload.text(content.text, now)
            is OutgoingContent.Image -> Payload.image(content.name, content.mime, content.bytes, now)
            is OutgoingContent.File -> Payload.file(content.name, content.mime, content.bytes, now)
        }
        payload.validate()
        val targets = store.load().peers.filter { peerIds == null || it.id in peerIds }
        return targets.map { peer ->
            try {
                SendOutcome(peer.id, deliver(payload, peer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RelayException) {
                SendOutcome(peer.id, null, failure.code)
            } catch (failure: Exception) {
                SendOutcome(peer.id, null, failure.message ?: "send_failed")
            }
        }
    }

    private suspend fun deliver(payload: Payload, peer: LinkedPeer): String {
        val sequence = update { it.copy(sequence = Math.addExact(it.sequence, 1)) }.sequence
        val envelope = crypto.seal(payload, peer.bundle, clock(), sequence, config.lifetimeMillis)
        var attempt = 0
        while (true) {
            try {
                registered { it.upload(envelope) }
                return envelope.id
            } catch (failure: IOException) {
                if (++attempt >= 3) throw failure
                delay(500L * attempt)
            }
        }
    }

    /**
     * Fetches and processes at most one envelope. [handler] applies the item (clipboard, notification)
     * and returns the receipt status reported back to the sender. Returns how many items reached [handler]
     * (receipts, unlink notices and duplicates are handled internally and not counted).
     */
    suspend fun receiveOnce(waitSeconds: Int = config.pollWaitSeconds, handler: suspend (IncomingItem) -> ReceiptStatus): Int =
        registered { it.poll(waitSeconds, 1) }.count { process(it, handler) }

    private suspend fun process(envelope: Envelope, handler: suspend (IncomingItem) -> ReceiptStatus): Boolean {
        val relay = relay()
        val state = store.load()
        val peer = state.peers.firstOrNull { it.id == envelope.senderId }
        if (envelope.id in state.seen || peer == null) { relay.acknowledge(envelope.id); return false }
        val payload = try { crypto.open(envelope, peer.bundle, clock()) } catch (_: Exception) { null }
        if (payload == null) { relay.acknowledge(envelope.id); return false }
        // Record before applying: a crash must never re-apply an old clip on restart.
        val recorded = update { it.copy(seen = it.seen + (envelope.id to envelope.expiresAt)) }
        when (payload.kind) {
            PayloadKind.RECEIPT -> {
                relay.acknowledge(envelope.id)
                mutableEvents.tryEmit(LinkEvent.Receipt(payload.receiptFor!!, peer.id, payload.status!!))
                return false
            }
            PayloadKind.UNLINK -> {
                relay.acknowledge(envelope.id)
                update { s -> s.copy(peers = s.peers.filterNot { it.id == peer.id }, pendingRevocations = s.pendingRevocations + peer.id,
                    lastClip = s.lastClip - peer.id) }
                mutableEvents.tryEmit(LinkEvent.PeerUnlinked(peer.id, byRemote = true))
                runCatching { flushRevocations() }
                return false
            }
            PayloadKind.TEXT, PayloadKind.IMAGE, PayloadKind.FILE -> {
                val stale = payload.kind != PayloadKind.FILE && envelope.sequence <= (recorded.lastClip[peer.id] ?: 0)
                val status = try {
                    handler(IncomingItem(envelope.id, peer, payload, envelope.expiresAt, stale))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ReceiptStatus.FAILED
                }
                if (status == ReceiptStatus.COPIED) update { s ->
                    s.copy(lastClip = s.lastClip + (peer.id to maxOf(s.lastClip[peer.id] ?: 0, envelope.sequence)))
                }
                relay.acknowledge(envelope.id)
                runCatching { deliver(Payload.receipt(envelope.id, status), peer) }
                return true
            }
        }
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
                    mutableStatus.value = if (failure.status in listOf(401, 403)) ReceiverStatus.REJECTED else ReceiverStatus.OFFLINE
                    delay(if (failure.status == 429) 60_000L else maxOf(backoff, 15_000L))
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } catch (_: Exception) {
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
