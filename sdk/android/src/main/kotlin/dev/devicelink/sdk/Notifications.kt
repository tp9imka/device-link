package dev.devicelink.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** All DeviceLink notifications. Content previews are hidden on the lock screen. */
internal object Notifications {
    const val SERVICE_ID = 0x0D11
    private const val SERVICE = "devicelink_service"
    private const val COPIED = "devicelink_copied"
    private const val ACTION = "devicelink_action"
    private const val PREVIEW = 120

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(SERVICE, context.getString(R.string.dl_channel_service), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(COPIED, context.getString(R.string.dl_channel_copied), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(ACTION, context.getString(R.string.dl_channel_action), NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    private fun builder(context: Context, channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.dl_ic_notification)
        .setColor(context.getColor(R.color.dl_accent))
        .setContentIntent(openApp(context))

    private fun openApp(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun service(context: Context, title: String, text: String): Notification {
        val send = PendingIntent.getActivity(context, 2, Intent(context, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 3, Intent(context, ReceiverService::class.java).setAction(ReceiverService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return builder(context, SERVICE).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.dl_action_send_clipboard), send).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.dl_action_turn_off), stop).build())
            .build()
    }

    private fun preview(text: String) = if (text.length <= PREVIEW) text else text.take(PREVIEW) + "…"

    private fun publicVersion(context: Context, title: String) =
        builder(context, COPIED).setContentTitle(title).setContentText(context.getString(R.string.dl_hidden_content)).build()

    /** Quiet confirmation that something is ready to paste; removes itself after a short while. */
    fun copied(context: Context, record: ClipRecord) {
        val title = context.getString(R.string.dl_copied_from, record.peerName)
        val body = if (record.sensitive) context.getString(R.string.dl_hidden_content)
            else record.text?.let(::preview) ?: record.name ?: context.getString(R.string.dl_image)
        val notification = builder(context, COPIED).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setTimeoutAfter(20_000)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion(context, title))
            .setCategory(Notification.CATEGORY_STATUS).build()
        notify(context, record.id, notification)
    }

    /**
     * Content that cannot go to the clipboard (files, or a failed write): offer the share sheet so the
     * user can hand it to any app, plus Open / Copy where they make sense.
     */
    fun shareNeeded(context: Context, record: ClipRecord) {
        val title = context.getString(R.string.dl_received_from, record.peerName)
        val body = if (record.sensitive) context.getString(R.string.dl_hidden_content)
            else record.text?.let(::preview) ?: record.name ?: context.getString(R.string.dl_file)
        val builder = builder(context, ACTION).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion(context, title))
            .setCategory(Notification.CATEGORY_MESSAGE)
        shareIntent(context, record)?.let { chooser ->
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.dl_action_share),
                PendingIntent.getActivity(context, record.id.hashCode(), chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        record.uri?.let { uri ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), record.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.dl_action_open),
                PendingIntent.getActivity(context, record.id.hashCode() + 1, Intent.createChooser(view, null),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        if (record.text != null || record.mime?.startsWith("image/") == true) {
            val copy = Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.COPY).putExtra("id", record.id)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.dl_action_copy),
                PendingIntent.getBroadcast(context, record.id.hashCode() + 2, copy, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        notify(context, record.id, builder.build())
    }

    /** Platform share sheet for a received record; also used by apps' history screens. */
    fun shareIntent(context: Context, record: ClipRecord): Intent? {
        val send = Intent(Intent.ACTION_SEND)
        when {
            record.uri != null -> {
                val uri = Uri.parse(record.uri)
                send.setType(record.mime ?: "application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(record.name ?: "", uri)
            }
            record.text != null -> send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, record.text)
            else -> return null
        }
        return Intent.createChooser(send, context.getString(R.string.dl_share_title, record.peerName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun cancel(context: Context, id: String) = context.getSystemService(NotificationManager::class.java).cancel(id, 0)

    private fun notify(context: Context, tag: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).notify(tag, 0, notification)
    }
}

/** Notification "Copy" action: writing the clipboard is allowed from a broadcast receiver. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != COPY) return
        val id = intent.getStringExtra("id") ?: return
        val link = DeviceLink.get(context)
        val record = link.history.value.firstOrNull { it.id == id } ?: return
        val pending = goAsync()
        Thread {
            try {
                if (link.copyToClipboard(record)) Notifications.cancel(context, id)
            } finally { pending.finish() }
        }.start()
    }

    companion object { const val COPY = "dev.devicelink.sdk.COPY" }
}
