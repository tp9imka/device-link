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

/** Shared toast wording for every "send" entry point. */
internal fun Activity.reportSend(outcomes: List<SendOutcome>, peers: List<String>) {
    val message = when {
        peers.isEmpty() -> getString(R.string.dl_send_no_devices)
        outcomes.isEmpty() -> getString(R.string.dl_send_nothing)
        outcomes.all { it.accepted } -> getString(R.string.dl_send_ok, peers.joinToString())
        else -> getString(R.string.dl_send_failed)
    }
    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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
        link.scope.launch {
            val outcomes = runCatching { link.sendClip(clip) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { reportSend(outcomes, link.peers.value.map { it.name }); finish() }
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
        link.scope.launch {
            val outcomes = runCatching { send(link, intent) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { reportSend(outcomes, link.peers.value.map { it.name }); finish() }
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
