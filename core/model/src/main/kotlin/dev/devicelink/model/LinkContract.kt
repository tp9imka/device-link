package dev.devicelink.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class LinkPhase { OFF, SEARCHING, CONNECTING, VERIFYING, CONNECTED }
@Serializable
enum class TransferKind { TEXT, FILE }
enum class TransferDirection { INCOMING, OUTGOING }
enum class TransferStatus { OFFERED, TRANSFERRING, COMPLETE, REJECTED, CANCELLED, FAILED }
data class Peer(val id: String, val name: String)
data class Verification(val peerName: String, val code: String)
data class Transfer(
    val id: String,
    val name: String,
    val kind: TransferKind,
    val direction: TransferDirection,
    val status: TransferStatus,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val text: String? = null,
    val localUri: String? = null,
    val mimeType: String = "application/octet-stream",
    val error: String? = null,
)
data class LinkState(
    val localName: String = "This phone",
    val phase: LinkPhase = LinkPhase.OFF,
    val nearbyPeers: List<Peer> = emptyList(),
    val trustedPeers: List<Peer> = emptyList(),
    val verification: Verification? = null,
    val connectedPeerName: String? = null,
    val remainingSeconds: Long = 0,
    val transfers: List<Transfer> = emptyList(),
    val pendingItems: Int = 0,
    val error: String? = null,
) {
    val enabled: Boolean get() = phase != LinkPhase.OFF
    val hasActiveTransfers: Boolean get() = transfers.any { it.status == TransferStatus.TRANSFERRING }
}
interface LinkController {
    val state: StateFlow<LinkState>
    val pairingCode: String
    fun startSession(durationMinutes: Int = 15)
    fun stopSession()
    fun connect(endpointId: String)
    fun confirmPairing(accept: Boolean)
    fun pairWithCode(code: String)
    fun sendText(text: String)
    fun sendFiles(uris: List<String>)
    fun acceptTransfer(id: String)
    fun rejectTransfer(id: String)
    fun cancelTransfer(id: String)
    fun forgetPeer(id: String)
    fun clearTransfer(id: String)
    fun clearPending()
    fun setDeviceName(name: String)
    fun dismissError()
}
