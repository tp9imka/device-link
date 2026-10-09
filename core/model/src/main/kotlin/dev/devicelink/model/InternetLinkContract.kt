package dev.devicelink.model

data class InternetPeer(val id: String, val name: String)
enum class InternetStatus { OFF, CONNECTING, READY, RETRYING, CONFIGURATION_ERROR }
data class InternetLinkState(
    val serverUrl: String = "",
    val enrollmentToken: String = "",
    val allowClipboard: Boolean = false,
    val status: InternetStatus = InternetStatus.OFF,
    val remainingSeconds: Long = 0,
    val finishing: Boolean = false,
    val peers: List<InternetPeer> = emptyList(),
    val selectedPeerId: String? = null,
    val transfers: List<Transfer> = emptyList(),
) {
    val enabled: Boolean get() = status != InternetStatus.OFF && status != InternetStatus.CONFIGURATION_ERROR
    val selectedPeer: InternetPeer? get() = peers.firstOrNull { it.id == selectedPeerId }
}

data class InternetClipboardDelivery(
    val id: String,
    val text: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val generation: Long,
    val expiresAt: Long,
)
