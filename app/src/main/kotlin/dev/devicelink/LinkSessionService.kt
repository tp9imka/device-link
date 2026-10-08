package dev.devicelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LinkSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as DeviceLinkApplication).controller
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            controller.stopSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(15))
        controller.startSession(intent.getIntExtra(EXTRA_DURATION, 15))
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.map { Pair(it.enabled, (it.remainingSeconds + 59) / 60) }
                    .distinctUntilChanged().collect { (enabled, minutes) ->
                        TileService.requestListeningState(this@LinkSessionService, ComponentName(this@LinkSessionService, DeviceLinkTileService::class.java))
                        if (!enabled) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else manager.notify(NOTIFICATION_ID, notification(minutes))
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(minutes: Long): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.session_active))
            .setContentText(if (minutes > 0) getString(R.string.session_remaining, minutes) else getString(R.string.session_finishing))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.stop_session), stop).build()
    }
    override fun onDestroy() {
        controller.stopSession()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val ACTION_START = "dev.devicelink.START"
        const val ACTION_STOP = "dev.devicelink.STOP"
        const val EXTRA_DURATION = "duration"
        private const val CHANNEL = "device_link_session"
        private const val NOTIFICATION_ID = 41
    }
}
