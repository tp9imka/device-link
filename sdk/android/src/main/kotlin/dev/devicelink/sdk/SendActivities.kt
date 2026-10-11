package dev.devicelink.sdk

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import dev.devicelink.sdk.core.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared toast wording for every "send" entry point: success, or the reason the user can act on. */
internal fun Activity.reportSend(result: Result<List<SendOutcome>>, link: DeviceLinkInstance) {
    Toast.makeText(applicationContext, link.sendResultMessage(result), Toast.LENGTH_LONG).show()
}

/** Files and images can take a while to seal and upload: say so at once instead of staying silent. */
internal fun Activity.reportSending() {
    Toast.makeText(applicationContext, R.string.dl_sending, Toast.LENGTH_SHORT).show()
}

/**
 * Invisible activity behind the "Send clipboard" tile, notification action and app shortcut.
 * Android only lets the focused app read the clipboard, so it reads once window focus arrives.
 */
class SendClipboardActivity : Activity() {
    private var sent = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent) return
        sent = true
        val link = DeviceLink.get(this)
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null) {
            Toast.makeText(applicationContext, R.string.dl_send_nothing, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (clip.itemCount > 0 && clip.getItemAt(0).uri != null) reportSending()
        link.scope.launch {
            val result = runCatching { link.sendClip(clip) }
            withContext(Dispatchers.Main) { reportSend(result, link); finish() }
        }
    }
}

/**
 * "DeviceLink" in the share sheet and the text-selection menu ("Send to device"): the reliable way to
 * send from any app, since background clipboard capture is unavailable on Android.
 */
class ShareToDeviceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = DeviceLink.get(this)
        val intent = intent
        if (intent.action == Intent.ACTION_SEND_MULTIPLE || (intent.action == Intent.ACTION_SEND && intent.hasExtra(Intent.EXTRA_STREAM))) reportSending()
        link.scope.launch {
            val result = runCatching { send(link, intent) }
            withContext(Dispatchers.Main) { reportSend(result, link); finish() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun send(link: DeviceLinkInstance, intent: Intent): List<SendOutcome> = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?.let { link.sendText(it) }.orEmpty()
        Intent.ACTION_SEND -> {
            val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            when {
                stream != null -> link.sendUri(stream, intent.type)
                else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { link.sendText(it) }.orEmpty()
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val streams = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            streams.orEmpty().take(10).flatMap { link.sendUri(it, null) }
        }
        else -> emptyList()
    }
}
