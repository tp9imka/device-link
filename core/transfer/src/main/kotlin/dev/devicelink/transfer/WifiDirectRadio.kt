package dev.devicelink.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.devicelink.model.Peer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Wi-Fi Direct owns discovery and group negotiation; TCP is restricted to the negotiated peer link. */
@android.annotation.SuppressLint("MissingPermission") // Composition root requests runtime permissions; controller handles revocation.
internal class WifiDirectRadio(private val context: Context, private val scope: CoroutineScope, private val events: Events) {
    interface Events {
        fun peers(peers: List<Peer>)
        fun connected()
        suspend fun frame(bytes: ByteArray)
        fun failed()
    }
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var running = false
    private var epoch = 0L
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var connectionJob: Job? = null
    private var output: DataOutputStream? = null
    private val writeLock = Mutex()
    var localAddress: String? = null
        private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            val ch = channel ?: return
            val session = epoch
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> runCatching {
                    manager.requestPeers(ch) { list -> if (running && session == epoch) events.peers(list.deviceList.map { Peer(it.deviceAddress, it.deviceName.take(48)) }) }
                }.onFailure { events.failed() }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> manager.requestConnectionInfo(ch) { info ->
                    if (!running || session != epoch) return@requestConnectionInfo
                    if (info.groupFormed && connectionJob == null) {
                        stopDiscovery()
                        connectionJob = scope.launch(Dispatchers.IO) {
                            try {
                                val link = if (info.isGroupOwner) {
                                    ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 120_000; server = this }.accept()
                                } else {
                                    var connected: Socket? = null
                                    repeat(15) {
                                        if (connected == null) {
                                            val attempt = Socket()
                                            try { attempt.connect(InetSocketAddress(info.groupOwnerAddress, PORT), 3000); connected = attempt }
                                            catch (_: Exception) { attempt.close(); delay(500) }
                                        }
                                    }
                                    connected ?: error("No peer")
                                }
                                socket = link; server?.close(); server = null
                                link.tcpNoDelay = true; link.soTimeout = 0
                                output = DataOutputStream(link.getOutputStream())
                                withContext(Dispatchers.Main.immediate) { if (running && session == epoch) events.connected() }
                                val input = DataInputStream(link.getInputStream())
                                while (isActive && running && session == epoch) {
                                    val length = input.readInt(); require(length in 1..MAX_FRAME)
                                    val frame = ByteArray(length); input.readFully(frame)
                                    withContext(Dispatchers.Main.immediate) { if (running && session == epoch) events.frame(frame) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { withContext(Dispatchers.Main.immediate) { if (running && session == epoch) events.failed() } }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    localAddress = device?.deviceAddress
                }
            }
        }
    }
    fun start() {
        if (running) return
        running = true
        val session = ++epoch
        channel = manager.initialize(context, Looper.getMainLooper()) { if (running && session == epoch) events.failed() }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        manager.discoverPeers(channel, listener { events.failed() })
        manager.requestPeers(channel) { list -> if (running && session == epoch) events.peers(list.deviceList.map { Peer(it.deviceAddress, it.deviceName.take(48)) }) }
    }
    fun connect(address: String) {
        val config = android.net.wifi.p2p.WifiP2pConfig().apply { deviceAddress = address; wps.setup = android.net.wifi.WpsInfo.PBC }
        manager.connect(channel, config, listener { events.failed() })
    }
    fun stopDiscovery() { channel?.let { runCatching { manager.stopPeerDiscovery(it, null) } } }
    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size in 1..MAX_FRAME)
        writeLock.withLock {
            val writer = output ?: error("Disconnected")
            writer.writeInt(bytes.size); writer.write(bytes); writer.flush()
        }
    }
    fun stop() {
        running = false
        epoch++
        connectionJob?.cancel(); connectionJob = null
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null; output = null
        channel?.let { ch ->
            runCatching { manager.stopPeerDiscovery(ch, null); manager.cancelConnect(ch, null); manager.removeGroup(ch, null); ch.close() }
        }
        channel = null
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
    }
    private fun listener(failure: () -> Unit): WifiP2pManager.ActionListener {
        val session = epoch
        return object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) { if (running && session == epoch) failure() }
        }
    }
    private companion object { const val PORT = 39473; const val MAX_FRAME = 65561 }
}
