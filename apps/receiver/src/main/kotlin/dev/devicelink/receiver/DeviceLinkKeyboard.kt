package dev.devicelink.receiver

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import dev.devicelink.sdk.ClipDirection
import dev.devicelink.sdk.DeviceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional "DeviceLink keyboard": the active input method is the one ordinary app Android lets see
 * clipboard changes from other apps. While it is the current keyboard and auto-send is on, every copy
 * goes to the linked devices. Its bar also pastes the last received clip and switches back.
 */
class DeviceLinkKeyboard : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private var listening = false
    private var lastSent: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!prefs.getBoolean("auto", true)) return@OnPrimaryClipChangedListener
        val link = DeviceLink.get(this)
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return@OnPrimaryClipChangedListener
        if (link.peers.value.isEmpty() || link.isOwnClip(clip)) return@OnPrimaryClipChangedListener
        val key = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { it.uri?.toString() ?: it.text?.toString() } ?: return@OnPrimaryClipChangedListener
        if (key == lastSent) return@OnPrimaryClipChangedListener
        lastSent = key
        scope.launch {
            val outcomes = runCatching { link.sendClip(clip) }.getOrDefault(emptyList())
            if (outcomes.isNotEmpty() && outcomes.all { it.accepted }) {
                Toast.makeText(this@DeviceLinkKeyboard, getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name }), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard, null)
        val link = DeviceLink.get(this)
        view.findViewById<Button>(R.id.kb_paste).setOnClickListener {
            val text = link.history.value.firstOrNull { it.direction == ClipDirection.RECEIVED && it.text != null }?.text
            if (text == null) Toast.makeText(this, R.string.kb_nothing_received, Toast.LENGTH_SHORT).show()
            else currentInputConnection?.commitText(text, 1)
        }
        view.findViewById<Button>(R.id.kb_send).setOnClickListener {
            val clip = runCatching { clipboard.primaryClip }.getOrNull()
            if (clip == null) { Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            scope.launch {
                val outcomes = runCatching { link.sendClip(clip) }.getOrDefault(emptyList())
                Toast.makeText(this@DeviceLinkKeyboard, if (outcomes.isNotEmpty() && outcomes.all { it.accepted })
                    getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name })
                    else getString(dev.devicelink.sdk.R.string.dl_send_failed), Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Switch>(R.id.kb_auto).apply {
            isChecked = prefs.getBoolean("auto", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("auto", checked).apply() }
        }
        view.findViewById<Button>(R.id.kb_switch).setOnClickListener { switchAway() }
        return view
    }

    private fun switchAway() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!listening) { clipboard.addPrimaryClipChangedListener(listener); listening = true }
    }

    override fun onDestroy() {
        if (listening) clipboard.removePrimaryClipChangedListener(listener)
        scope.cancel()
        super.onDestroy()
    }
}
