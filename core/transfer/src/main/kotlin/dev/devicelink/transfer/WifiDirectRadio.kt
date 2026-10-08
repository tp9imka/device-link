package dev.devicelink.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.devicelink.model.Peer
import dev.devicelink.model.SecureChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** App-scoped DNS-SD discovery over Wi-Fi Direct; no Internet or Google services. */
@android.annotation.SuppressLint("MissingPermission") // Root requests permissions; controller handles revocation.
internal class WifiDirectRadio(
    private val context: Context,
    private val scope: CoroutineScope,
    private val events: Events,
    private val localName: () -> String,
) {
    interface Events {
        fun peers(peers: List<Peer>)
        fun connecting()
        fun connected()
        suspend fun frame(bytes: ByteArray)
        fun failed()
    }
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var running = false
    private var discovering = false
    private var pendingConnect: String? = null
    private var epoch = 0L
    // Published and cleared only on Main, including sockets still connecting/accepting.
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var output: DataOutputStream? = null
    private var connectionJob: Job? = null
    private var discoveryJob: Job? = null
    private val writeLock = Mutex()
    private val discovered = linkedMapOf<String, Peer>()
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    var localAddress: String? = null
        private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            val ch = channel ?: return
            val session = epoch
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val network = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (network?.isConnectedOrConnecting == true) {
                        discovering = false; discoveryJob?.cancel(); discoveryJob = null
                        events.connecting()
                    }
                    manager.requestConnectionInfo(ch) { info ->
                    if (!isCurrent(session) || !info.groupFormed || connectionJob != null) return@requestConnectionInfo
                    stopDiscovery()
                    connectionJob = scope.launch(Dispatchers.IO) {
                        var localServer: ServerSocket? = null
                        var localSocket: Socket? = null
                        try {
                            if (info.isGroupOwner) {
                                localServer = ServerSocket().apply {
                                    reuseAddress = true
                                    bind(InetSocketAddress(info.groupOwnerAddress, PORT))
                                    soTimeout = 120_000
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    check(isCurrent(session))
                                    server = localServer
                                }
                                localSocket = localServer.accept()
                            } else {
                                repeat(15) {
                                    if (localSocket == null) {
                                        ensureActive()
                                        val attempt = Socket()
                                        try {
                                            withContext(Dispatchers.Main.immediate) {
                                                check(isCurrent(session))
                                                socket = attempt
                                            }
                                            attempt.connect(InetSocketAddress(info.groupOwnerAddress, PORT), 3000)
                                            localSocket = attempt
                                        } catch (cancelled: CancellationException) {
                                            attempt.close(); throw cancelled
                                        } catch (_: Exception) {
                                            attempt.close()
                                            withContext(Dispatchers.Main.immediate) { if (socket === attempt) socket = null }
                                            delay(500)
                                        }
                                    }
                                }
                            }
                            val link = requireNotNull(localSocket)
                            link.tcpNoDelay = true
                            localServer?.close()
                            withContext(Dispatchers.Main.immediate) {
                                check(isCurrent(session))
                                if (server === localServer) server = null
                                socket = link
                                output = DataOutputStream(link.getOutputStream())
                                events.connected()
                            }
                            val input = DataInputStream(link.getInputStream())
                            while (isActive) {
                                val length = input.readInt()
                                require(length in 1..MAX_FRAME)
                                val frame = ByteArray(length)
                                input.readFully(frame)
                                withContext(Dispatchers.Main.immediate) {
                                    check(isCurrent(session))
                                    events.frame(frame)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            withContext(Dispatchers.Main.immediate) { if (isCurrent(session)) events.failed() }
                        } finally {
                            runCatching { localSocket?.close() }
                            runCatching { localServer?.close() }
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (socket === localSocket) { socket = null; output = null }
                                if (server === localServer) server = null
                            }
                        }
                    }
                }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> runCatching {
                    manager.requestPeers(ch) { list ->
                        if (isCurrent(session)) {
                            val selected = pendingConnect
                            if (selected != null && list.deviceList.any { it.deviceAddress.equals(selected, ignoreCase = true) }) {
                                pendingConnect = null
                                connectKnownPeer(selected, session)
                            }
                        }
                        if (isCurrent(session) && discovering) {
                            val visible = list.deviceList.map { it.deviceAddress }.toSet()
                            if (discovered.keys.removeAll { it !in visible }) events.peers(discovered.values.toList())
                        }
                    }
                }.onFailure { if (isCurrent(session)) events.failed() }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    localAddress = device?.deviceAddress
                }
            }
        }
    }
    private fun isCurrent(session: Long) = running && epoch == session
    fun start() {
        if (running) return
        running = true; discovering = true
        val session = ++epoch
        discovered.clear()
        val ch = manager.initialize(context, Looper.getMainLooper()) { if (isCurrent(session)) events.failed() }
        channel = ch
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        manager.setDnsSdResponseListeners(ch, { instance, type, device ->
            if (isCurrent(session) && discovering && instance == INSTANCE && type.startsWith(SERVICE_TYPE)) {
                discovered.putIfAbsent(device.deviceAddress, Peer(device.deviceAddress, device.deviceName.take(48)))
                events.peers(discovered.values.toList())
            }
        }, { domain, record, device ->
            if (isCurrent(session) && discovering && domain.startsWith("$INSTANCE.$SERVICE_TYPE") && record["v"] == "1") {
                val name = record["name"].orEmpty().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.take(48)
                discovered[device.deviceAddress] = Peer(device.deviceAddress, name.ifBlank { device.deviceName.take(48) })
                events.peers(discovered.values.toList())
            }
        })
        localService = WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE_TYPE, mapOf("v" to "1", "name" to localName()))
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        manager.addLocalService(ch, localService, listener(session, discoveryOnly = true, operation = "advertise") {
            if (!discovering) return@listener
            manager.addServiceRequest(ch, serviceRequest, listener(session, discoveryOnly = true, operation = "request_ptr") {
                if (discovering) manager.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(INSTANCE, SERVICE_TYPE), listener(session, discoveryOnly = true, operation = "request_txt") {
                    if (discovering) {
                        discoveryJob = scope.launch(Dispatchers.Main.immediate) {
                            while (isActive && isCurrent(session) && discovering) {
                                manager.discoverServices(ch, listener(session, discoveryOnly = true, operation = "discover_services"))
                                if (android.os.Build.VERSION.SDK_INT < 33) break
                                delay(kotlin.random.Random.nextLong(4_000, 7_000))
                                if (android.os.Build.VERSION.SDK_INT >= 33 && isCurrent(session) && discovering) manager.startListening(ch, listener(session, discoveryOnly = true, operation = "listen"))
                                delay(kotlin.random.Random.nextLong(4_000, 7_000))
                            }
                        }
                    }
                })
            })
        })
    }
    fun connect(address: String) {
        require(discovered.containsKey(address))
        discovering = false; discoveryJob?.cancel(); discoveryJob = null
        val session = epoch
        pendingConnect = address
        manager.requestPeers(channel) { peers ->
            if (!isCurrent(session)) return@requestPeers
            if (peers.deviceList.any { it.deviceAddress.equals(address, ignoreCase = true) }) {
                pendingConnect = null
                connectKnownPeer(address, session)
            } else {
                // DNS-SD responses can outlive the framework peer entry. Refresh it before connect.
                manager.discoverPeers(channel, listener(session, operation = "refresh_peer"))
            }
        }
    }
    private fun connectKnownPeer(address: String, session: Long) {
        if (!isCurrent(session)) return
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = android.net.wifi.WpsInfo.PBC
        }
        manager.connect(channel, config, listener(session, operation = "connect"))
    }
    fun stopDiscovery() {
        discovering = false
        discoveryJob?.cancel(); discoveryJob = null
        channel?.let { ch ->
            if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { manager.stopListening(ch, null) }
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.clearServiceRequests(ch, null) }
            runCatching { manager.clearLocalServices(ch, null) }
        }
        localService = null; serviceRequest = null
    }
    suspend fun send(bytes: ByteArray) {
        // Called on Main. Capture the exact writer before dispatch; never publish into a newer session.
        require(bytes.size in 1..MAX_FRAME)
        val writer = output ?: error("Disconnected")
        withContext(Dispatchers.IO) {
            writeLock.withLock { writer.writeInt(bytes.size); writer.write(bytes); writer.flush() }
        }
    }
    fun stop() {
        running = false; epoch++; pendingConnect = null
        connectionJob?.cancel(); connectionJob = null
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null; output = null
        stopDiscovery()
        channel?.let { ch ->
            runCatching { manager.cancelConnect(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
            if (android.os.Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        channel = null
        discovered.clear()
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
    }
    private fun listener(session: Long, discoveryOnly: Boolean = false, operation: String = "radio", success: () -> Unit = {}): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (isCurrent(session)) success() }
        override fun onFailure(reason: Int) {
            if (isCurrent(session) && (!discoveryOnly || discovering)) {
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.w("DeviceLinkRadio", "$operation failed (platform code $reason)")
                if (discoveryOnly && reason == WifiP2pManager.BUSY) {
                    // Android rejects discovery during native consent/negotiation. Preserve that flow.
                    discovering = false
                    discoveryJob?.cancel(); discoveryJob = null
                    events.connecting()
                } else events.failed()
            }
        }
    }
    private companion object {
        const val PORT = 39473
        const val MAX_FRAME = SecureChannel.MAX_FRAME_BYTES + 1
        const val INSTANCE = "devicelink-v1"
        const val SERVICE_TYPE = "_devicelink._tcp"
    }
}
