package dev.devicelink.sdk

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.devicelink.sdk.core.ReceiverStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Visible foreground service that long-polls the relay and applies clips as they arrive.
 * Turning it off cancels the poll: no background network use while off.
 */
class ReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiving: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val link = DeviceLink.get(this)
        if (intent?.action == ACTION_STOP) {
            link.stopReceiver()
            shutdown()
            return START_NOT_STICKY
        }
        if (!link.receiverEnabled || link.peers.value.isEmpty()) { shutdown(); return START_NOT_STICKY }
        val notification = Notifications.service(this, getString(R.string.dl_service_title), getString(R.string.dl_status_connecting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifications.SERVICE_ID, notification)
        }
        if (receiving == null) {
            receiving = scope.launch { link.client.runReceiver { item -> link.handle(item) } }
            scope.launch {
                combine(link.status, link.peers) { status, peers -> status to peers.map { it.name } }.distinctUntilChanged().collect { (status, names) ->
                    if (names.isEmpty()) { shutdown(); return@collect }
                    val text = when (status) {
                        ReceiverStatus.ONLINE -> getString(R.string.dl_status_online, names.joinToString())
                        ReceiverStatus.OFFLINE -> getString(R.string.dl_status_offline)
                        ReceiverStatus.REJECTED -> getString(R.string.dl_status_rejected)
                        else -> getString(R.string.dl_status_connecting)
                    }
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.SERVICE_ID, Notifications.service(this@ReceiverService, getString(R.string.dl_service_title), text))
                }
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        receiving?.cancel(); receiving = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "dev.devicelink.sdk.STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ReceiverService::class.java)) }
    }
}

/** Restores the receiver after reboot or app update when the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val link = DeviceLink.get(context)
        if (link.receiverEnabled && link.peers.value.isNotEmpty()) ReceiverService.start(context)
    }
}
