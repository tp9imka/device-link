package dev.devicelink.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import dev.devicelink.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.UUID

/** All protocol state is serialized on Main; file IO is bounded and runs on IO. */
class NativeLinkController(context: Context, private val scope: CoroutineScope) : LinkController {
    private val context = context.applicationContext
    private val radioDelegate = lazy { WifiDirectRadio(this.context, scope, radioEvents) { identity.deviceName } }
    private val radio: WifiDirectRadio by radioDelegate
    private val identity = IdentityStore(this.context)
    private val mutable = MutableStateFlow(LinkState(localName = identity.deviceName,
        trustedPeers = identity.trustedPeers(), transfers = identity.receivedFiles(this.context)))
    override val state: StateFlow<LinkState> = mutable.asStateFlow()
    override val pairingCode: String get() = PairingInvite(TrustProof.fingerprint(identity.publicKey), identity.deviceName,
        if (radioDelegate.isInitialized()) radio.localAddress.orEmpty() else "").encode()
    private val startupCleanup = scope.async(Dispatchers.IO) {
        val retained = mutable.value.transfers.filter { it.kind == TransferKind.FILE }.map { it.id }.toSet()
        File(this@NativeLinkController.context.filesDir, "incoming").listFiles()?.forEach { it.delete() }
        File(this@NativeLinkController.context.filesDir, "received").listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
    }
    private var endpoint: String? = null
    private var peerName = ""
    private var authenticationCode = ""
    private var agreement: EphemeralAgreement? = null
    private var secureChannel: SecureChannel? = null
    private val sendLock = Mutex()
    private val transferWakeLock = (this.context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DeviceLink:transfer").apply { setReferenceCounted(false) }
    private fun updateWakeLock() {
        if (activeCount() > 0) {
            if (!transferWakeLock.isHeld) transferWakeLock.acquire(10 * 60_000L)
        } else if (transferWakeLock.isHeld) transferWakeLock.release()
    }
    private var localHello: WireMessage.Hello? = null
    private var remoteCommitment: String? = null
    private var remoteHello: WireMessage.Hello? = null
    private var proofVerified = false
    private var localApproved = false
    private var remoteApproved = false
    private var authenticated = false
    private var pinnedFingerprint: String? = null
    private var pinnedName: String? = null
    private var deadline = 0L
    private var generation = 0L
    private var timer: Job? = null
    private var searchTimer: Job? = null
    private var handshakeTimer: Job? = null
    private val outgoing = mutableMapOf<String, PreparedFile>()
    private val offers = mutableMapOf<String, WireMessage.Offer>()
    private val incomingStreams = mutableMapOf<String, FileOutputStream>()
    private val incomingCounts = mutableMapOf<String, Long>()
    private val finalizing = mutableSetOf<String>()
    private val receivedCommitter = ReceivedFileCommitter()
    private val copyJobs = mutableMapOf<String, Job>()
    private val pending = ArrayDeque<Pending>()
    private data class PreparedFile(val descriptor: ParcelFileDescriptor)
    private sealed interface Pending { data class Text(val text: String) : Pending; data class Files(val uris: List<String>) : Pending }
    private fun main(action: suspend () -> Unit) { scope.launch(Dispatchers.Main.immediate) { action() } }
    private fun message(id: Int) = context.getString(id)
    private fun error(id: Int) { mutable.update { it.copy(error = message(id)) } }
    private fun activeCount() = mutable.value.transfers.count { it.status == TransferStatus.TRANSFERRING }
    private fun expired() = SessionPolicy.expired(deadline, SystemClock.elapsedRealtime())
    private val progressPublished = mutableMapOf<String, Long>()
    private fun updateTransfer(id: String, transform: (Transfer) -> Transfer) {
        val previous = mutable.value.transfers.firstOrNull { it.id == id } ?: return
        val next = transform(previous)
        val now = SystemClock.elapsedRealtime()
        if (previous.status == TransferStatus.TRANSFERRING && next.status == TransferStatus.TRANSFERRING &&
            previous.copy(transferredBytes = next.transferredBytes) == next) {
            if (now - (progressPublished[id] ?: 0) < 150) return
            progressPublished[id] = now
        } else progressPublished.remove(id)
        mutable.update { current -> current.copy(transfers = current.transfers.map { if (it.id == id) next else it }) }
        updateWakeLock()
    }
    private fun append(transfer: Transfer) {
        mutable.update { current ->
            var completedTexts = 0
            current.copy(transfers = (listOf(transfer) + current.transfers).filter {
                if (it.kind == TransferKind.TEXT && it.status !in listOf(TransferStatus.OFFERED, TransferStatus.TRANSFERRING)) ++completedTexts <= 200 else true
            })
        }
        updateWakeLock()
    }
    private fun disconnectWithError(id: Int) { stopInternal(); error(id) }

    override fun startSession(durationMinutes: Int) = main {
        if (mutable.value.enabled) {
            deadline = SystemClock.elapsedRealtime() + durationMinutes.coerceIn(1, 60) * 60_000L
            return@main
        }
        generation++
        val session = generation
        deadline = SystemClock.elapsedRealtime() + durationMinutes.coerceIn(1, 60) * 60_000L
        mutable.update { it.copy(phase = LinkPhase.SEARCHING, nearbyPeers = emptyList(), error = null,
            remainingSeconds = durationMinutes.coerceIn(1, 60) * 60L) }
        startupCleanup.await()
        if (session != generation || !mutable.value.enabled) return@main
        try { radio.start() }
        catch (_: SecurityException) { disconnectWithError(R.string.link_permission_error); return@main }
        catch (_: Exception) { disconnectWithError(R.string.link_radio_error); return@main }
        timer = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && generation == session) {
                mutable.update { it.copy(remainingSeconds = ((deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)) }
                if (SessionPolicy.shouldStop(deadline, SystemClock.elapsedRealtime(), activeCount())) { stopInternal(); break }
                delay(1000)
            }
        }
        searchTimer = scope.launch(Dispatchers.Main.immediate) {
            delay(60_000)
            if (generation == session && mutable.value.phase == LinkPhase.SEARCHING) disconnectWithError(R.string.link_search_timeout)
        }
    }
    override fun stopSession() = main { pending.clear(); mutable.update { it.copy(pendingItems = 0) }; stopInternal() }
    private fun stopInternal() {
        generation++
        if (transferWakeLock.isHeld) transferWakeLock.release()
        timer?.cancel(); searchTimer?.cancel(); handshakeTimer?.cancel()
        copyJobs.values.forEach { it.cancel() }; copyJobs.clear()
        if (radioDelegate.isInitialized()) radio.stop()
        outgoing.values.forEach { runCatching { it.descriptor.close() } }; outgoing.clear()
        incomingStreams.forEach { (id, stream) -> runCatching { stream.close() }; File(context.filesDir, "incoming/$id").delete() }
        incomingStreams.clear(); incomingCounts.clear(); offers.clear()
        agreement = null; secureChannel = null
        endpoint = null; localHello = null; remoteHello = null; remoteCommitment = null; proofVerified = false
        localApproved = false; remoteApproved = false; authenticated = false
        pinnedFingerprint = null; pinnedName = null
        mutable.update { it.copy(phase = LinkPhase.OFF, nearbyPeers = emptyList(), verification = null,
            connectedPeerName = null, remainingSeconds = 0,
            transfers = it.transfers.map { transfer -> if (transfer.status in listOf(TransferStatus.OFFERED, TransferStatus.TRANSFERRING))
                transfer.copy(status = TransferStatus.CANCELLED) else transfer }) }
    }
    private val radioEvents: WifiDirectRadio.Events = object : WifiDirectRadio.Events {
        override fun peers(peers: List<Peer>) {
            if (mutable.value.phase != LinkPhase.SEARCHING) return
            mutable.update { it.copy(nearbyPeers = peers) }
            peers.firstOrNull { it.id.equals(pinnedName, ignoreCase = true) }?.let { connect(it.id) }
        }
        override fun connecting() {
            if (mutable.value.phase == LinkPhase.SEARCHING) {
                searchTimer?.cancel()
                mutable.update { it.copy(phase = LinkPhase.CONNECTING) }
                startHandshakeTimeout()
            }
        }
        override fun connected() {
            if (!mutable.value.enabled || expired()) { stopInternal(); return }
            searchTimer?.cancel(); radio.stopDiscovery()
            endpoint = endpoint ?: "incoming"
            mutable.update { it.copy(phase = LinkPhase.CONNECTING, nearbyPeers = emptyList()) }
            agreement = EphemeralAgreement()
            localHello = WireMessage.Hello(identity.publicKey, Base64.encodeToString(ByteArray(32).apply { SecureRandom().nextBytes(this) }, Base64.NO_WRAP), agreement!!.publicKey, identity.deviceName)
            startHandshakeTimeout(); sendWire(WireMessage.Commitment(TrustProof.commitment(localHello!!)))
        }
        override suspend fun frame(bytes: ByteArray) {
            val session = generation
            try {
                require(bytes.isNotEmpty())
                if (bytes[0] == 0.toByte()) {
                    require(remoteHello == null)
                    val hello = WireCodec.decode(bytes.copyOfRange(1, bytes.size))
                    require(hello is WireMessage.Hello || hello is WireMessage.Commitment)
                    receive(hello)
                } else {
                    require(bytes[0] == 1.toByte())
                    val decrypted = requireNotNull(secureChannel).decrypt(bytes.copyOfRange(1, bytes.size))
                    require(decrypted.isNotEmpty())
                    when (decrypted[0].toInt()) {
                        1 -> receive(WireCodec.decode(decrypted.copyOfRange(1, decrypted.size)))
                        2, 3 -> receiveFileFrame(decrypted)
                        else -> error("Invalid frame")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session == generation && mutable.value.enabled) disconnectWithError(R.string.link_protocol_error) }
        }
        override fun failed() { if (mutable.value.enabled) disconnectWithError(R.string.link_connect_error) }
    }
    override fun connect(endpointId: String) = main {
        if (mutable.value.phase != LinkPhase.SEARCHING || expired()) return@main
        val peer = mutable.value.nearbyPeers.firstOrNull { it.id == endpointId } ?: return@main
        endpoint = endpointId; peerName = peer.name
        mutable.update { it.copy(phase = LinkPhase.CONNECTING, error = null) }
        searchTimer?.cancel()
        runCatching { radio.connect(endpointId) }.onFailure { disconnectWithError(R.string.link_connect_error) }
        startHandshakeTimeout()
    }
    private fun startHandshakeTimeout() {
        handshakeTimer?.cancel()
        handshakeTimer = scope.launch(Dispatchers.Main.immediate) { delay(120_000); if (!authenticated) disconnectWithError(R.string.link_connect_error) }
    }
    private fun sendWire(value: WireMessage) {
        val session = generation
        main {
            try {
                sendLock.withLock {
                    if (session != generation) return@withLock
                    val bytes = WireCodec.encode(value)
                    val frame = if (value is WireMessage.Hello || value is WireMessage.Commitment) byteArrayOf(0) + bytes
                        else byteArrayOf(1) + requireNotNull(secureChannel).encrypt(byteArrayOf(1) + bytes)
                    radio.send(frame)
                }
            } catch (_: Exception) { if (generation == session) disconnectWithError(R.string.link_transfer_error) }
        }
    }
    private suspend fun sendFileFrame(payloadId: Long, bytes: ByteArray?, session: Long) {
        sendLock.withLock {
            check(session == generation)
            val plain = FileFrames.encode(payloadId, bytes)
            radio.send(byteArrayOf(1) + requireNotNull(secureChannel).encrypt(plain))
        }
    }
    private fun receive(wire: WireMessage) {
        when (wire) {
            is WireMessage.Commitment -> {
                if (remoteCommitment != null || remoteHello != null || localHello == null) { disconnectWithError(R.string.link_protocol_error); return }
                remoteCommitment = wire.digest; sendWire(localHello!!)
            }
            is WireMessage.Hello -> {
                if (remoteHello != null || localHello == null || remoteCommitment != TrustProof.commitment(wire)) { disconnectWithError(R.string.link_protocol_error); return }
                val fingerprint = runCatching { TrustProof.fingerprint(wire.publicKey) }.getOrNull()
                if (fingerprint == null || (pinnedFingerprint != null && pinnedFingerprint != fingerprint)) { disconnectWithError(R.string.link_identity_error); return }
                remoteHello = wire
                peerName = wire.displayName
                secureChannel = agreement!!.establish(localHello!!, wire)
                authenticationCode = TrustProof.authenticationCode(localHello!!, wire)
                sendWire(WireMessage.Proof(identity.sign(TrustProof.transcript(localHello!!, wire))))
            }
            is WireMessage.Proof -> {
                if (proofVerified || remoteHello == null || localHello == null || !TrustProof.verify(remoteHello!!, localHello!!, wire.signature)) {
                    disconnectWithError(R.string.link_identity_error); return
                }
                proofVerified = true
                val fingerprint = TrustProof.fingerprint(remoteHello!!.publicKey)
                if (mutable.value.trustedPeers.any { it.id == fingerprint }) {
                    localApproved = true; sendWire(WireMessage.PairApproved); readyIfApproved()
                } else mutable.update { it.copy(phase = LinkPhase.VERIFYING, verification = Verification(peerName.ifBlank { message(R.string.link_other_phone) }, authenticationCode)) }
            }
            WireMessage.PairApproved -> {
                if (!proofVerified) { disconnectWithError(R.string.link_protocol_error); return }
                remoteApproved = true; readyIfApproved()
            }
            WireMessage.PairRejected -> disconnectWithError(R.string.link_pair_rejected)
            else -> {
                if (!authenticated) { disconnectWithError(R.string.link_protocol_error); return }
                when (wire) {
                    is WireMessage.Text -> receiveText(wire)
                    is WireMessage.Offer -> receiveOffer(wire)
                    is WireMessage.Accept -> sendAccepted(wire.id)
                    is WireMessage.Reject -> terminal(wire.id, TransferStatus.REJECTED)
                    is WireMessage.Cancel -> terminal(wire.id, TransferStatus.CANCELLED)
                    is WireMessage.Receipt -> {
                        val item = mutable.value.transfers.firstOrNull { it.id == wire.id }
                        if (item?.direction == TransferDirection.OUTGOING && item.status == TransferStatus.TRANSFERRING) terminal(wire.id, TransferStatus.COMPLETE)
                    }
                }
            }
        }
    }
    override fun confirmPairing(accept: Boolean) = main {
        if (!proofVerified || authenticated || mutable.value.verification == null) return@main
        if (!accept) { sendWire(WireMessage.PairRejected); stopInternal(); return@main }
        localApproved = true; mutable.update { it.copy(verification = null) }; sendWire(WireMessage.PairApproved); readyIfApproved()
    }
    private fun readyIfApproved() {
        if (authenticated || !proofVerified || !localApproved || !remoteApproved) return
        authenticated = true; handshakeTimer?.cancel()
        val peer = Peer(TrustProof.fingerprint(remoteHello!!.publicKey), peerName.ifBlank { message(R.string.link_other_phone) })
        val peers = mutable.value.trustedPeers.filterNot { it.id == peer.id } + peer
        identity.savePeers(peers)
        mutable.update { it.copy(phase = LinkPhase.CONNECTED, trustedPeers = peers, verification = null, connectedPeerName = peerName) }
        val queued = pending.toList(); pending.clear(); mutable.update { it.copy(pendingItems = 0) }
        queued.forEach { when (it) { is Pending.Text -> sendText(it.text); is Pending.Files -> sendFiles(it.uris) } }
    }
    override fun pairWithCode(code: String) = main {
        if (mutable.value.phase !in listOf(LinkPhase.OFF, LinkPhase.SEARCHING)) { error(R.string.link_pair_busy); return@main }
        val parsed = PairingInvite.decode(code)
        if (parsed == null) { error(R.string.link_invalid_code); return@main }
        pinnedFingerprint = parsed.fingerprint; pinnedName = parsed.address
        mutable.value.nearbyPeers.firstOrNull { it.id.equals(parsed.address, ignoreCase = true) }?.let { connect(it.id) }
    }
    private fun queue(item: Pending, count: Int) {
        if (mutable.value.pendingItems + count > 20) { error(R.string.link_queue_full); return }
        pending.add(item); mutable.update { it.copy(pendingItems = it.pendingItems + count) }
    }
    override fun sendText(text: String) = main {
        if (text.isBlank() || text.toByteArray().size > WireCodec.MAX_TEXT_BYTES) { error(R.string.link_text_size); return@main }
        if (!authenticated) { queue(Pending.Text(text), 1); return@main }
        if (expired()) { error(R.string.link_session_expired); return@main }
        val id = UUID.randomUUID().toString()
        append(Transfer(id, message(R.string.link_text_item), TransferKind.TEXT, TransferDirection.OUTGOING,
            TransferStatus.TRANSFERRING, totalBytes = text.toByteArray().size.toLong(), text = text))
        sendWire(WireMessage.Text(id, text))
        receiptTimeout(id)
    }
    private fun receiveText(text: WireMessage.Text) {
        if (expired()) { sendWire(WireMessage.Reject(text.id)); return }
        if (mutable.value.transfers.any { it.id == text.id }) { disconnectWithError(R.string.link_protocol_error); return }
        val size = text.text.toByteArray().size.toLong()
        append(Transfer(text.id, message(R.string.link_text_item), TransferKind.TEXT, TransferDirection.INCOMING,
            TransferStatus.COMPLETE, totalBytes = size, transferredBytes = size, text = text.text))
        sendWire(WireMessage.Receipt(text.id))
    }
    override fun sendFiles(uris: List<String>) = main {
        if (uris.isEmpty()) return@main
        if (uris.size > 20) { error(R.string.link_queue_full); return@main }
        if (!authenticated) { queue(Pending.Files(uris), uris.size); return@main }
        if (expired()) { error(R.string.link_session_expired); return@main }
        val session = generation
        uris.forEach { raw ->
            if (session != generation || !authenticated || expired()) return@main
            val prepared = withContext(Dispatchers.IO) { prepareFile(raw) }
            if (prepared == null) { error(R.string.link_file_unavailable); return@forEach }
            val (offer, file) = prepared
            if (session != generation || !authenticated || expired()) { file.descriptor.close(); return@main }
            outgoing[offer.id] = file; offers[offer.id] = offer
            append(Transfer(offer.id, offer.name, TransferKind.FILE, TransferDirection.OUTGOING,
                TransferStatus.OFFERED, totalBytes = offer.size, mimeType = offer.mime))
            sendWire(offer)
        }
    }
    private fun prepareFile(raw: String): Pair<WireMessage.Offer, PreparedFile>? = runCatching {
        val uri = Uri.parse(raw); require(uri.scheme == "content")
        var name = "file"; var declaredSize = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unavailable")
        try {
            val size = if (descriptor.statSize >= 0) descriptor.statSize else declaredSize
            require(size in 0..WireCodec.MAX_FILE_BYTES)
            val mime = context.contentResolver.getType(uri)?.takeIf { it.length <= 128 } ?: "application/octet-stream"
            WireMessage.Offer(UUID.randomUUID().toString(), FileNames.sanitize(name), size, mime, TransferKind.FILE, SecureRandom().nextLong()) to PreparedFile(descriptor)
        } catch (failure: Exception) { descriptor.close(); throw failure }
    }.getOrNull()
    private fun receiveOffer(offer: WireMessage.Offer) {
        if (expired() || offers.size >= 40 || offer.kind != TransferKind.FILE) { sendWire(WireMessage.Reject(offer.id)); return }
        if (mutable.value.transfers.any { it.id == offer.id } || offers.values.any { it.payloadId == offer.payloadId }) {
            disconnectWithError(R.string.link_protocol_error); return
        }
        offers[offer.id] = offer
        append(Transfer(offer.id, FileNames.sanitize(offer.name), TransferKind.FILE, TransferDirection.INCOMING,
            TransferStatus.OFFERED, totalBytes = offer.size, mimeType = offer.mime))
    }
    override fun acceptTransfer(id: String) = main {
        if (!authenticated || expired()) { error(R.string.link_session_expired); return@main }
        val transfer = mutable.value.transfers.firstOrNull { it.id == id } ?: return@main
        if (transfer.direction != TransferDirection.INCOMING || transfer.status != TransferStatus.OFFERED) return@main
        if (context.filesDir.usableSpace < transfer.totalBytes + 10 * 1024 * 1024) { error(R.string.link_storage_full); return@main }
        val file = File(context.filesDir, "incoming/$id")
        try { file.parentFile!!.mkdirs(); check(file.createNewFile()); incomingStreams[id] = file.outputStream(); incomingCounts[id] = 0 }
        catch (_: Exception) { error(R.string.link_storage_full); return@main }
        updateTransfer(id) { it.copy(status = TransferStatus.TRANSFERRING) }; sendWire(WireMessage.Accept(id)); receiptTimeout(id)
    }
    private fun sendAccepted(id: String) {
        val transfer = mutable.value.transfers.firstOrNull { it.id == id } ?: return
        if (transfer.direction != TransferDirection.OUTGOING || transfer.status != TransferStatus.OFFERED) return
        if (expired()) { terminal(id, TransferStatus.CANCELLED); sendWire(WireMessage.Cancel(id)); return }
        val file = outgoing[id] ?: return
        updateTransfer(id) { it.copy(status = TransferStatus.TRANSFERRING) }
        val session = generation
        val offer = offers[id] ?: return
        copyJobs[id] = scope.launch(Dispatchers.Main.immediate) {
            try {
                withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(file.descriptor) }.use { source ->
                    val buffer = ByteArray(48 * 1024); var total = 0L
                    while (isActive) {
                        val count = withContext(Dispatchers.IO) { source.read(buffer) }; if (count < 0) break
                        total += count; require(total <= offer.size)
                        sendFileFrame(offer.payloadId!!, buffer.copyOf(count), session)
                        updateTransfer(id) { it.copy(transferredBytes = total) }
                    }
                    require(total == offer.size)
                    sendFileFrame(offer.payloadId!!, null, session)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == session) { terminal(id, TransferStatus.FAILED); sendWire(WireMessage.Cancel(id)) } }
            finally { copyJobs.remove(id) }
        }
        receiptTimeout(id)
    }
    private suspend fun receiveFileFrame(frame: ByteArray) {
        val session = generation
        require(authenticated && frame.size >= 9)
        val decoded = FileFrames.decode(frame); val payloadId = decoded.payloadId
        val offer = offers.values.firstOrNull { it.payloadId == payloadId } ?: return // Frames already in flight after a cancel.
        val transfer = mutable.value.transfers.firstOrNull { it.id == offer.id } ?: error("Missing offer")
        require(transfer.direction == TransferDirection.INCOMING && transfer.status == TransferStatus.TRANSFERRING)
        val stream = incomingStreams[offer.id] ?: error("Not accepted")
        if (decoded.bytes != null) {
            val count = (incomingCounts[offer.id] ?: 0) + decoded.bytes.size
            require(count <= offer.size)
            if (!receivingIo(offer.id, session) { stream.write(decoded.bytes) }) return
            incomingCounts[offer.id] = count
            updateTransfer(offer.id) { it.copy(transferredBytes = count) }
        } else {
            require(frame.size == 9 && incomingCounts[offer.id] == offer.size)
            if (!receivingIo(offer.id, session) { stream.fd.sync(); stream.close() }) return
            incomingStreams.remove(offer.id); incomingCounts.remove(offer.id)
            val file = File(context.filesDir, "received/${offer.id}")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
            val completed = transfer.copy(status = TransferStatus.COMPLETE, transferredBytes = offer.size, localUri = uri)
            finalizing.add(offer.id)
            try {
                val committed = receivedCommitter.commit(
                    staged = File(context.filesDir, "incoming/${offer.id}"), destination = file,
                    isCurrent = { isReceiving(offer.id, session) },
                    persist = { identity.addReceivedFile(completed) },
                    removeRecord = { identity.removeReceivedFile(offer.id) },
                    publish = { updateTransfer(offer.id) { completed } },
                )
                if (committed) { offers.remove(offer.id); sendWire(WireMessage.Receipt(offer.id)) }
            } finally {
                finalizing.remove(offer.id)
            }
        }
    }
    private fun isReceiving(id: String, session: Long): Boolean = session == generation &&
        mutable.value.transfers.any { it.id == id && it.status == TransferStatus.TRANSFERRING }

    private suspend fun receivingIo(id: String, session: Long, action: () -> Unit): Boolean {
        try { withContext(Dispatchers.IO) { action() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (isReceiving(id, session)) throw failure else return false }
        return isReceiving(id, session)
    }

    private fun receiptTimeout(id: String) {
        val session = generation
        scope.launch(Dispatchers.Main.immediate) {
            var previous = -1L
            while (generation == session) {
                delay(120_000)
                val transfer = mutable.value.transfers.firstOrNull { it.id == id } ?: break
                if (transfer.status != TransferStatus.TRANSFERRING) break
                if (transfer.transferredBytes == previous) { terminal(id, TransferStatus.FAILED); sendWire(WireMessage.Cancel(id)); break }
                previous = transfer.transferredBytes
            }
        }
    }
    private fun terminal(id: String, status: TransferStatus) {
        val item = mutable.value.transfers.firstOrNull { it.id == id } ?: return
        if (!TransferLifecycle.canTransition(item.status, status)) return
        offers.remove(id)
        if (status != TransferStatus.COMPLETE) {
            incomingStreams.remove(id)?.let { runCatching { it.close() }; File(context.filesDir, "incoming/$id").delete() }
            incomingCounts.remove(id)
            copyJobs.remove(id)?.cancel()
        }
        outgoing.remove(id)?.descriptor?.let { runCatching { it.close() } }
        updateTransfer(id) { it.copy(status = status, transferredBytes = if (status == TransferStatus.COMPLETE) it.totalBytes else it.transferredBytes,
            error = if (status == TransferStatus.FAILED) message(R.string.link_transfer_error) else null) }
    }
    override fun rejectTransfer(id: String) = main { if (authenticated) sendWire(WireMessage.Reject(id)); terminal(id, TransferStatus.REJECTED) }
    override fun cancelTransfer(id: String) = main { if (authenticated) sendWire(WireMessage.Cancel(id)); terminal(id, TransferStatus.CANCELLED) }
    override fun forgetPeer(id: String) = main {
        val peers = mutable.value.trustedPeers.filterNot { it.id == id }; identity.savePeers(peers)
        mutable.update { it.copy(trustedPeers = peers) }
        if (remoteHello?.let { TrustProof.fingerprint(it.publicKey) } == id) stopInternal()
    }
    override fun clearTransfer(id: String) = main {
        val item = mutable.value.transfers.firstOrNull { it.id == id } ?: return@main
        if (item.status in listOf(TransferStatus.OFFERED, TransferStatus.TRANSFERRING) || id in finalizing) return@main
        if (item.direction == TransferDirection.INCOMING && item.kind == TransferKind.FILE) {
            try {
                withContext(Dispatchers.IO) {
                    identity.removeReceivedFile(id)
                    File(context.filesDir, "received/$id").delete()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error(R.string.link_transfer_error); return@main }
        }
        mutable.update { it.copy(transfers = it.transfers.filterNot { transfer -> transfer.id == id }) }
    }
    override fun clearPending() = main { pending.clear(); mutable.update { it.copy(pendingItems = 0) } }
    override fun setDeviceName(name: String) = main {
        val safe = name.trim().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.take(48)
        if (safe.isEmpty()) return@main
        identity.deviceName = safe; mutable.update { it.copy(localName = safe) }
    }
    override fun dismissError() = main { mutable.update { it.copy(error = null) } }
}
