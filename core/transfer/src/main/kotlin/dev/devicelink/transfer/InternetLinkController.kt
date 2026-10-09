package dev.devicelink.transfer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import dev.devicelink.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional bounded relay session. Nearby transport remains independent. */
class InternetLinkController(context: Context, private val scope: CoroutineScope, private val nearby: LinkController) {
    private val context = context.applicationContext
    private val identity = IdentityStore(this.context)
    private val store = RelayStore(this.context, identity)
    private val mutable = MutableStateFlow(InternetLinkState(store.serverUrl, store.token, store.allowClipboard, transfers = store.receivedTexts()))
    val state: StateFlow<InternetLinkState> = mutable.asStateFlow()
    private val clipboardEvents = MutableSharedFlow<InternetClipboardDelivery>(extraBufferCapacity = 20)
    val incomingClips = clipboardEvents.asSharedFlow()
    private val pendingClips = mutableMapOf<String, Pair<InternetClipboardDelivery, RelayEnvelope>>()
    private val offers = mutableMapOf<String, RelayEnvelope>()
    private val outgoing = RelayOutgoingTracker(scope)
    private val acl = RelayAclReconciler(store::pendingRevocations, store::completeRevocation)
    private val accepting = mutableSetOf<String>()
    private val blockedKeys = mutableSetOf<String>()
    private val sends = Mutex()
    private var client: RelayHttpClient? = null
    private var session: Job? = null
    private var generation = 0L
    private var deadline = 0L
    private var drain: RelaySessionDrain? = null
    private val offerDirectory = File(this.context.filesDir, "relay_offers")

    init {
        refreshPeers()
        scope.launch {
            nearby.incomingRelayKeys.collect { encoded ->
                runCatching {
                    val bundle = RelayCodec.decodeBundle(encoded.toByteArray())
                    require(RelayCrypto.verifyBundle(bundle))
                    require(nearby.state.value.connectedPeerId == bundle.id)
                    require(nearby.state.value.trustedPeers.any { it.id == bundle.id })
                    if (store.serverUrl.isNotBlank() && bundle.id !in blockedKeys) {
                        val firstExchange = store.peers()[bundle.id] == null
                        withContext(Dispatchers.IO) { store.addPeer(bundle) }
                        if (bundle.id in blockedKeys || nearby.state.value.trustedPeers.none { it.id == bundle.id }) {
                            store.removePeer(bundle.id)
                            return@collect
                        }
                        refreshPeers()
                        if (firstExchange) exchangeKeys()
                        client?.let { http ->
                            acl.grant({ client === http && trusted(bundle.id) }) {
                                http.request("PUT", "/v1/peers/${bundle.id}", "{}".toByteArray())
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            nearby.state.map { Pair(it.connectedPeerId, it.trustedPeers) }.distinctUntilChanged().collect {
                if (it.first == null) blockedKeys.clear()
                refreshPeers()
                exchangeKeys()
            }
        }
    }

    suspend fun configure(url: String, token: String, allowClipboard: Boolean): Boolean = runCatching {
        val normalized = RelayHttpClient.normalizeUrl(url, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        require(token.length <= 512 && token.none { it.isISOControl() })
        stop()
        withContext(Dispatchers.IO) { store.configure(normalized, token, allowClipboard); store.bundle }
        mutable.update { it.copy(serverUrl = normalized, enrollmentToken = token, allowClipboard = allowClipboard) }
        exchangeKeys()
    }.isSuccess

    fun selectPeer(id: String) {
        if (state.value.peers.none { it.id == id }) return
        store.selectedPeerId = id
        refreshPeers()
    }

    fun forgetPeer(id: String) {
        // Local revocation takes effect immediately, even if the server is unavailable.
        scope.launch {
            blockedKeys.add(id)
            store.removePeer(id)
            refreshPeers()
            offers.filterValues { it.senderId == id }.keys.toList().forEach { removeOffer(it) }
            pendingClips.filterValues { it.second.senderId == id }.keys.toList().forEach(pendingClips::remove)
            outgoing.idsForPeer(id).forEach(::failOutgoing)
            val http = client
            val url = store.serverUrl
            if (http != null) runCatching { acl.revokePending(url, { client === http }) { peer ->
                http.request("DELETE", "/v1/peers/$peer")
            } }
        }
    }

    private fun refreshPeers() {
        val trusted = nearby.state.value.trustedPeers.associateBy { it.id }
        val peers = store.peers().keys.mapNotNull { id -> trusted[id]?.let { InternetPeer(id, it.name) } }
        mutable.update { it.copy(peers = peers, selectedPeerId = store.selectedPeerId?.takeIf { id -> peers.any { it.id == id } } ?: peers.firstOrNull()?.id) }
    }

    private fun trusted(id: String): Boolean = id !in blockedKeys && store.peers().containsKey(id) && nearby.state.value.trustedPeers.any { it.id == id }

    private suspend fun exchangeKeys() {
        if (store.serverUrl.isBlank() || nearby.state.value.phase != LinkPhase.CONNECTED) return
        runCatching {
            val bundle = withContext(Dispatchers.IO) { store.bundle }
            nearby.sendRelayKeys(RelayCodec.encodeBundle(bundle).toString(Charsets.UTF_8))
        }
    }

    fun start(minutes: Int = 15) {
        stop()
        if (store.serverUrl.isBlank()) { mutable.update { it.copy(status = InternetStatus.CONFIGURATION_ERROR) }; return }
        val token = generation
        deadline = android.os.SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60_000L
        val sessionDrain = RelaySessionDrain(deadline) { android.os.SystemClock.elapsedRealtime() }
        drain = sessionDrain
        val relayUrl = store.serverUrl
        val http = RelayHttpClient(relayUrl, identity, store.token)
        client = http
        mutable.update { it.copy(status = InternetStatus.CONNECTING) }
        session = scope.launch {
            launch {
                while (token == generation && isActive) {
                    val remaining = ((deadline - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
                    mutable.update { it.copy(remainingSeconds = remaining, finishing = !sessionDrain.canStart()) }
                    if (sessionDrain.shouldStop(pendingClips.isNotEmpty())) { stop(); break }
                    delay(1000)
                }
            }
            var registered = false
            var backoff = 1_000L
            while (token == generation && isActive) {
                val operation = sessionDrain.acquire() ?: break
                try {
                    if (!registered) {
                        withContext(Dispatchers.IO) { store.crypto }
                        http.request("POST", "/v1/register", "{}".toByteArray())
                        refreshPeers()
                        acl.revokePending(relayUrl, { token == generation }) { http.request("DELETE", "/v1/peers/$it") }
                        state.value.peers.forEach { peer ->
                            acl.grant({ token == generation && trusted(peer.id) }) {
                                http.request("PUT", "/v1/peers/${peer.id}", "{}".toByteArray())
                            }
                        }
                        registered = true
                        exchangeKeys()
                        restoreOffers(token)
                    }
                    if (token != generation) break
                    acl.revokePending(relayUrl, { token == generation }) { http.request("DELETE", "/v1/peers/$it") }
                    if (token != generation) break
                    mutable.update { it.copy(status = InternetStatus.READY) }
                    offers.values.filter { it.expiresAt <= System.currentTimeMillis() || !trusted(it.senderId) }.map { it.id }.forEach {
                        removeOffer(it); update(it) { transfer -> transfer.copy(status = TransferStatus.FAILED) }
                    }
                    val excluded = offers.keys.take(20).joinToString(",")
                    val path = "/v1/messages?wait=25&limit=1" + if (excluded.isEmpty()) "" else "&exclude=$excluded"
                    if (!sessionDrain.canStart()) break
                    val bytes = http.request("GET", path)
                    val envelopes = withContext(Dispatchers.IO) { RelayCodec.decodeEnvelopes(bytes, 1) }
                    for (envelope in envelopes.sortedWith(compareBy<RelayEnvelope> { it.senderId }.thenByDescending { it.sequence })) {
                        if (token != generation) break
                        receive(envelope, token)
                    }
                    backoff = 1_000
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    if (token != generation) break
                    if (failure is RelayHttpException && failure.status in listOf(401, 403)) {
                        stop(); mutable.update { it.copy(status = InternetStatus.CONFIGURATION_ERROR) }; break
                    }
                    mutable.update { it.copy(status = InternetStatus.RETRYING) }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                } finally { operation.close() }
            }
        }
    }

    fun stop() {
        generation++
        drain?.stop(); drain = null
        client?.close(); client = null
        session?.cancel(); session = null
        pendingClips.clear()
        mutable.update { it.copy(status = InternetStatus.OFF, remainingSeconds = 0, finishing = false,
            transfers = it.transfers.map { t -> if (t.clipboardStatus == ClipboardStatus.PENDING && t.direction == TransferDirection.INCOMING) t.copy(clipboardStatus = ClipboardStatus.NOT_COPIED) else t }) }
    }

    suspend fun sendText(text: String, clipboard: Boolean): ClipSendResult = send(
        RelayPayload(if (clipboard) RelayPayloadKind.CLIP_TEXT else RelayPayloadKind.TEXT, text = text),
    )

    suspend fun sendFile(uri: String, clipboard: Boolean): ClipSendResult {
        val token = generation
        val operation = drain?.acquire() ?: return if (state.value.enabled) ClipSendResult.EXPIRED else ClipSendResult.NOT_CONNECTED
        try {
        val payload = withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(uri)
                require(parsed.scheme == "content")
                var name = "file"
                context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
                val bytes = requireNotNull(context.contentResolver.openInputStream(parsed)).use {
                    val data = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32_768)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        require(data.size() + count <= RelayCodec.MAX_CONTENT_BYTES)
                        data.write(buffer, 0, count)
                    }
                    data.toByteArray()
                }
                RelayPayload(if (clipboard) RelayPayloadKind.CLIP_IMAGE else RelayPayloadKind.FILE,
                    name = FileNames.sanitize(name), mime = context.contentResolver.getType(parsed) ?: "application/octet-stream",
                    contentBase64 = RelayPayload.encodeContent(bytes))
            }.getOrNull()
        } ?: return ClipSendResult.INVALID
        if (token != generation) return ClipSendResult.NOT_CONNECTED
        return send(payload, continuation = true)
        } finally { operation.close() }
    }

    private suspend fun send(payload: RelayPayload, recipientId: String? = state.value.selectedPeerId,
        continuation: Boolean = payload.kind == RelayPayloadKind.RECEIPT,
        expectedGeneration: Long = generation): ClipSendResult = sends.withLock {
        if (expectedGeneration != generation) return ClipSendResult.NOT_CONNECTED
        val operation = drain?.acquire(continuation) ?: return if (state.value.enabled) ClipSendResult.EXPIRED else ClipSendResult.NOT_CONNECTED
        try {
        val http = client ?: return ClipSendResult.NOT_CONNECTED
        if (!state.value.enabled || recipientId == null || state.value.peers.none { it.id == recipientId }) return ClipSendResult.NOT_CONNECTED
        if (!trusted(recipientId)) return ClipSendResult.NOT_CONNECTED
        if (payload.kind != RelayPayloadKind.RECEIPT && outgoing.size >= 20) return ClipSendResult.FAILED
        val recipient = store.peers()[recipientId] ?: return ClipSendResult.NOT_CONNECTED
        val token = generation
        val envelope = withContext(Dispatchers.IO) {
            runCatching { store.crypto.encrypt(payload, recipient, System.currentTimeMillis(), store.nextSequence()) }.getOrNull()
        } ?: return ClipSendResult.INVALID
        if (token != generation || !trusted(recipientId)) return ClipSendResult.NOT_CONNECTED
        val clip = payload.kind in listOf(RelayPayloadKind.CLIP_TEXT, RelayPayloadKind.CLIP_IMAGE)
        if (payload.kind != RelayPayloadKind.RECEIPT) {
            check(outgoing.track(envelope.id, recipientId, envelope.expiresAt) { failOutgoing(envelope.id) })
            put(Transfer(envelope.id, payload.name ?: "", if (payload.contentBase64 == null) TransferKind.TEXT else TransferKind.FILE,
                TransferDirection.OUTGOING, TransferStatus.TRANSFERRING, text = payload.text, mimeType = payload.mime ?: "text/plain",
                mode = if (clip) TransferMode.CLIPBOARD else TransferMode.STANDARD,
                clipboardStatus = if (clip) ClipboardStatus.PENDING else ClipboardStatus.NOT_REQUESTED))
        }
        try {
            http.request("POST", "/v1/messages", RelayCodec.encodeEnvelope(envelope))
            if (token != generation) { failOutgoing(envelope.id); return ClipSendResult.FAILED }
            ClipSendResult.SENT
        } catch (cancelled: CancellationException) {
            failOutgoing(envelope.id)
            throw cancelled
        } catch (_: Exception) {
            failOutgoing(envelope.id)
            ClipSendResult.FAILED
        }
        } finally { operation.close() }
    }

    private fun failOutgoing(id: String) {
        outgoing.remove(id)
        update(id) { if (it.status == TransferStatus.TRANSFERRING) it.copy(status = TransferStatus.FAILED,
            clipboardStatus = if (it.mode == TransferMode.CLIPBOARD) ClipboardStatus.NOT_COPIED else it.clipboardStatus) else it }
    }

    private suspend fun receive(envelope: RelayEnvelope, token: Long) {
        if (store.hasConsumed(envelope.id)) { acknowledge(envelope.id); return }
        val sender = store.peers()[envelope.senderId]?.takeIf { state.value.peers.any { p -> p.id == it.id } }
        if (sender == null || !trusted(envelope.senderId)) { acknowledge(envelope.id); return }
        val payload = withContext(Dispatchers.IO) { runCatching {
            store.crypto.decrypt(envelope, sender, System.currentTimeMillis(), RelayReplayGuard({ null }, { _, _ -> }))
        }.getOrNull() }
        if (token != generation || !trusted(envelope.senderId)) return
        if (payload == null) { acknowledge(envelope.id); return }
        val clip = payload.kind in listOf(RelayPayloadKind.CLIP_TEXT, RelayPayloadKind.CLIP_IMAGE)
        if (clip && envelope.sequence <= store.lastClipSequence(envelope.senderId)) { acknowledge(envelope.id); return }
        when (payload.kind) {
            RelayPayloadKind.RECEIPT -> {
                val originalId = requireNotNull(payload.receiptFor)
                if (outgoing.recipient(originalId) == envelope.senderId) {
                    outgoing.remove(originalId)
                    update(originalId) {
                        it.copy(status = if (it.mode == TransferMode.STANDARD && it.kind == TransferKind.FILE && payload.receiptCopied == false) TransferStatus.REJECTED else TransferStatus.COMPLETE,
                            clipboardStatus = if (it.mode != TransferMode.CLIPBOARD) it.clipboardStatus else if (payload.receiptCopied == true) ClipboardStatus.COPIED else ClipboardStatus.NOT_COPIED)
                    }
                }
                store.consume(envelope.id, envelope.expiresAt)
                acknowledge(envelope.id)
            }
            RelayPayloadKind.FILE -> {
                if (offers.containsKey(envelope.id)) return
                if (offers.size >= 20) { acknowledge(envelope.id); return }
                withContext(Dispatchers.IO) {
                    offerDirectory.mkdirs()
                    val file = AtomicFile(File(offerDirectory, envelope.id))
                    val output = file.startWrite()
                    try { output.write(RelayCodec.encodeEnvelope(envelope)); file.finishWrite(output) }
                    catch (failure: Exception) { file.failWrite(output); throw failure }
                }
                if (token != generation || !trusted(envelope.senderId)) return
                offers[envelope.id] = envelope
                put(Transfer(envelope.id, payload.name.orEmpty(), TransferKind.FILE, TransferDirection.INCOMING,
                    TransferStatus.OFFERED, mimeType = payload.mime.orEmpty(), totalBytes = payload.contentBytes()!!.size.toLong()))
            }
            RelayPayloadKind.TEXT, RelayPayloadKind.CLIP_TEXT, RelayPayloadKind.CLIP_IMAGE -> {
                var uri: String? = null
                if (payload.kind == RelayPayloadKind.CLIP_IMAGE) uri = persistFile(envelope, payload, token) ?: return
                if (token != generation || !trusted(envelope.senderId)) return
                // Persist replay state before clipboard delivery: a crash must never reapply an old clip.
                withContext(Dispatchers.IO) {
                    if (payload.kind == RelayPayloadKind.TEXT) store.saveText(envelope, requireNotNull(payload.text))
                    store.consume(envelope.id, envelope.expiresAt, if (clip) envelope.senderId else null, if (clip) envelope.sequence else null)
                }
                if (token != generation || !trusted(envelope.senderId)) return
                put(Transfer(envelope.id, payload.name.orEmpty(), if (uri == null) TransferKind.TEXT else TransferKind.FILE,
                    TransferDirection.INCOMING, TransferStatus.COMPLETE, text = payload.text, localUri = uri,
                    mimeType = payload.mime ?: "text/plain", mode = if (clip) TransferMode.CLIPBOARD else TransferMode.STANDARD,
                    clipboardStatus = if (clip) ClipboardStatus.NOT_COPIED else ClipboardStatus.NOT_REQUESTED))
                if (clip && state.value.allowClipboard && envelope.expiresAt > System.currentTimeMillis()) {
                    val event = InternetClipboardDelivery(envelope.id, payload.text, uri, payload.mime, token, envelope.expiresAt)
                    pendingClips[event.id] = event to envelope
                    update(event.id) { it.copy(clipboardStatus = ClipboardStatus.PENDING) }
                    clipboardEvents.emit(event)
                    scope.launch { delay(10_000); clipboardApplied(event, false) }
                } else finishDelivery(envelope, if (clip) false else null)
            }
        }
    }

    fun isClipCurrent(event: InternetClipboardDelivery): Boolean = event.generation == generation && state.value.enabled &&
        state.value.allowClipboard && event.expiresAt > System.currentTimeMillis() && pendingClips[event.id]?.let { (pending, envelope) ->
            pending == event && trusted(envelope.senderId) && envelope.sequence == store.lastClipSequence(envelope.senderId)
        } == true

    fun clipboardApplied(event: InternetClipboardDelivery, copied: Boolean) {
        val pending = pendingClips[event.id] ?: return
        if (pending.first != event || event.generation != generation) return
        val operation = drain?.acquire(continuation = true)
        pendingClips.remove(event.id)
        val actual = copied && event.expiresAt > System.currentTimeMillis()
        update(event.id) { it.copy(clipboardStatus = if (actual) ClipboardStatus.COPIED else ClipboardStatus.NOT_COPIED) }
        if (operation != null) scope.launch {
            try { if (event.generation == generation) finishDelivery(pending.second, actual) } finally { operation.close() }
        }
    }

    private suspend fun finishDelivery(envelope: RelayEnvelope, copied: Boolean?) {
        val token = generation
        send(RelayPayload(RelayPayloadKind.RECEIPT, receiptFor = envelope.id, receiptCopied = copied), envelope.senderId)
        if (token == generation) acknowledge(envelope.id)
    }
    private suspend fun acknowledge(id: String) { runCatching { client?.request("DELETE", "/v1/messages/$id") } }

    fun acceptFile(id: String) = scope.launch {
        val envelope = offers[id] ?: return@launch
        val sender = store.peers()[envelope.senderId] ?: return@launch
        val token = generation
        if (!state.value.enabled || !trusted(envelope.senderId) || !accepting.add(id)) return@launch
        val operation = drain?.acquire()
        if (operation == null) { accepting.remove(id); return@launch }
        try {
            val payload = withContext(Dispatchers.IO) { store.crypto.decrypt(envelope, sender, System.currentTimeMillis(), RelayReplayGuard({ null }, { _, _ -> })) }
            if (token != generation || !trusted(envelope.senderId)) return@launch
            val uri = persistFile(envelope, payload, token) ?: return@launch
            if (token != generation) return@launch
            store.consume(envelope.id, envelope.expiresAt)
            update(id) { it.copy(status = TransferStatus.COMPLETE, localUri = uri, transferredBytes = it.totalBytes) }
            removeOffer(id)
            finishDelivery(envelope, null)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { update(id) { it.copy(status = TransferStatus.FAILED) } }
        finally { accepting.remove(id); operation.close() }
    }

    fun rejectFile(id: String) = scope.launch {
        val envelope = offers[id] ?: return@launch
        val operation = drain?.acquire() ?: return@launch
        try {
        store.consume(id, envelope.expiresAt)
        removeOffer(id)
        update(id) { it.copy(status = TransferStatus.REJECTED) }
        finishDelivery(envelope, false)
        } finally { operation.close() }
    }

    private suspend fun persistFile(envelope: RelayEnvelope, payload: RelayPayload, token: Long): String? {
        val destination = File(context.filesDir, "received/${envelope.id}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination, requireNotNull(payload.name)).toString()
        if (destination.isFile) return if (token == generation && trusted(envelope.senderId)) uri else null
        val staged = File(context.cacheDir, "relay-${envelope.id}")
        val bytes = requireNotNull(payload.contentBytes())
        try {
        withContext(Dispatchers.IO) { staged.outputStream().use { it.write(bytes); it.fd.sync() } }
        val item = Transfer(envelope.id, payload.name.orEmpty(), TransferKind.FILE, TransferDirection.INCOMING, TransferStatus.COMPLETE,
            bytes.size.toLong(), bytes.size.toLong(), localUri = uri, mimeType = payload.mime.orEmpty())
        val committed = ReceivedFileCommitter().commit(staged, destination, { token == generation && state.value.enabled && trusted(envelope.senderId) && envelope.expiresAt > System.currentTimeMillis() },
            { identity.addReceivedFile(item) }, { identity.removeReceivedFile(envelope.id) }, {})
        return if (committed) uri else null
        } finally { withContext(NonCancellable + Dispatchers.IO) { staged.delete() } }
    }

    private suspend fun restoreOffers(token: Long) {
        val files = withContext(Dispatchers.IO) { offerDirectory.listFiles().orEmpty().filter { it.isFile }.take(20) }
        for (file in files) {
            val envelope = withContext(Dispatchers.IO) { runCatching { require(file.length() <= RelayCodec.MAX_ENVELOPE_BYTES); RelayCodec.decodeEnvelope(file.readBytes()) }.getOrNull() }
            if (envelope == null || envelope.expiresAt <= System.currentTimeMillis() || store.hasConsumed(envelope.id)) file.delete()
            else if (!offers.containsKey(envelope.id)) receive(envelope, token)
        }
    }
    private fun removeOffer(id: String) { offers.remove(id); File(offerDirectory, id).delete() }
    private fun put(transfer: Transfer) { mutable.update { it.copy(transfers = (listOf(transfer) + it.transfers.filterNot { item -> item.id == transfer.id }).take(100)) } }
    private fun update(id: String, transform: (Transfer) -> Transfer) { mutable.update { it.copy(transfers = it.transfers.map { t -> if (t.id == id) transform(t) else t }) } }
}


/** Main-dispatcher confined; retained entries and expiry jobs share the same bounded lifetime. */
internal class RelayOutgoingTracker(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(val peer: String, val expiry: Job)
    private val pending = mutableMapOf<String, Pending>()
    val size: Int get() = pending.size
    fun recipient(id: String): String? = pending[id]?.peer
    fun idsForPeer(peer: String): List<String> = pending.filterValues { it.peer == peer }.keys.toList()

    fun track(id: String, peer: String, expiresAt: Long, expired: () -> Unit): Boolean {
        if (pending.size >= 20 || pending.containsKey(id)) return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay((expiresAt - nowMillis()).coerceAtLeast(1))
            pending.remove(id)
            expired()
        }
        pending[id] = Pending(peer, job)
        job.start()
        return true
    }

    fun remove(id: String) { pending.remove(id)?.expiry?.cancel() }
}


/** Deadline stops admission; already admitted work may drain for at most one minute. */
internal class RelaySessionDrain(
    private val deadline: Long,
    private val nowMillis: () -> Long,
) {
    private var active = 0
    private var stopped = false
    fun canStart(): Boolean = !stopped && nowMillis() < deadline
    fun shouldStop(waitingForClipboard: Boolean = false): Boolean = stopped ||
        (nowMillis() >= deadline && ((active == 0 && !waitingForClipboard) || nowMillis() - deadline >= 60_000))

    fun acquire(continuation: Boolean = false): AutoCloseable? {
        if (stopped || (!continuation && !canStart()) || (nowMillis() >= deadline && nowMillis() - deadline >= 60_000)) return null
        active++
        var released = false
        return AutoCloseable { if (!released) { released = true; active-- } }
    }

    fun stop() { stopped = true }
}
