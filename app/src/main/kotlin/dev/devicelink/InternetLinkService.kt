package dev.devicelink

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class InternetLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as DeviceLinkApplication).internetLink
    private var observing = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            controller.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.internet_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(42, notification(15))
        controller.start(15)
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.map { Triple(it.enabled, (it.remainingSeconds + 59) / 60, it.finishing) }.distinctUntilChanged().collect { (enabled, minutes, finishing) ->
                    if (!enabled) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    else manager.notify(42, notification(minutes, finishing))
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(minutes: Long, finishing: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java).putExtra("internet_screen", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 42, Intent(this, InternetLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.internet_session_active))
            .setContentText(if (finishing) getString(R.string.internet_session_finishing) else getString(R.string.session_remaining, minutes))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.stop_session), stop).build()
    }
    override fun onDestroy() { controller.stop(); scope.cancel(); super.onDestroy() }
    companion object { const val ACTION_STOP = "dev.devicelink.INTERNET_STOP"; private const val CHANNEL = "device_link_internet" }
}
