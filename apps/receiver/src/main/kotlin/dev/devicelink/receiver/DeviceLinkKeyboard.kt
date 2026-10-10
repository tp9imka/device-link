package dev.devicelink.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import dev.devicelink.receiver.keyboard.Action
import dev.devicelink.receiver.keyboard.Key
import dev.devicelink.receiver.keyboard.KeyLabels
import dev.devicelink.receiver.keyboard.KeyboardView
import dev.devicelink.receiver.keyboard.Layouts
import dev.devicelink.receiver.keyboard.Mode
import dev.devicelink.receiver.keyboard.ShiftState
import dev.devicelink.receiver.keyboard.TextRules
import dev.devicelink.sdk.ClipDirection
import dev.devicelink.sdk.DeviceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * DeviceLink keyboard: an everyday QWERTY keyboard that also sends copies to linked devices.
 *
 * The current input method is the one ordinary app Android lets see clipboard changes from other
 * apps, so while this is your keyboard and auto-send is on, every copy is forwarded (clips marked
 * sensitive, e.g. from password managers, are not). The bar above the keys pastes the last received
 * clip and sends the clipboard on demand. Nothing typed is stored or sent: there is no dictionary.
 */
class DeviceLinkKeyboard : InputMethodService(), KeyboardView.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private val link by lazy { DeviceLink.get(this) }
    private var lastSent: String? = null

    private var keys: KeyboardView? = null
    private var status: TextView? = null
    private var clipView: TextView? = null
    private var barJob: Job? = null

    private var mode = Mode.LETTERS
    private val shift = ShiftState()
    private var lastSpaceAt = 0L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!prefs.getBoolean(AUTO, true)) return@OnPrimaryClipChangedListener
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return@OnPrimaryClipChangedListener
        if (link.peers.value.isEmpty() || link.isOwnClip(clip) || clip.isSensitive()) return@OnPrimaryClipChangedListener
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return@OnPrimaryClipChangedListener
        val key = item.uri?.toString() ?: item.text?.toString() ?: return@OnPrimaryClipChangedListener
        // Some apps report one copy twice; send each distinct clip once.
        if (key == lastSent) return@OnPrimaryClipChangedListener
        lastSent = key
        send(clip, quiet = true)
    }

    override fun onCreate() {
        super.onCreate()
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(listener)
        scope.cancel()
        super.onDestroy()
    }

    // ----- views -------------------------------------------------------------------------------

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard, null)
        keys = root.findViewById<KeyboardView>(R.id.kb_keys).also { it.listener = this }
        status = root.findViewById<TextView>(R.id.kb_status).apply {
            setOnClickListener {
                prefs.edit().putBoolean(AUTO, !prefs.getBoolean(AUTO, true)).apply()
                refreshBar()
            }
        }
        clipView = root.findViewById<TextView>(R.id.kb_clip).apply { setOnClickListener { pasteLastClip() } }
        root.findViewById<TextView>(R.id.kb_send).setOnClickListener {
            val clip = runCatching { clipboard.primaryClip }.getOrNull()
            if (clip == null || clip.itemCount == 0) toast(getString(R.string.clipboard_empty)) else send(clip, quiet = false)
        }
        // Keep the keys above the navigation bar when the window extends behind it.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            view.setPadding(0, 0, 0, bottom)
            insets
        }
        barJob?.cancel()
        barJob = scope.launch { link.peers.combine(link.history) { _, _ -> }.collect { refreshBar() } }
        // The view can be recreated mid-input (rotation, theme change): draw the current state now.
        currentInputEditorInfo?.let(::updateEnterLabel)
        render()
        return root
    }

    private fun refreshBar() {
        val linked = link.peers.value.isNotEmpty()
        val auto = prefs.getBoolean(AUTO, true)
        status?.text = getString(when { !linked -> R.string.kb_not_linked; auto -> R.string.kb_auto_on; else -> R.string.kb_auto_off })
        status?.isActivated = linked && auto
        val last = link.history.value.firstOrNull { it.direction == ClipDirection.RECEIVED && it.text != null }
        clipView?.text = when {
            last == null -> getString(R.string.kb_nothing_received)
            last.sensitive -> getString(R.string.kb_paste_sensitive)
            else -> getString(R.string.kb_paste_clip, last.text!!.replace('\n', ' ').take(60))
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        mode = TextRules.initialMode(info.inputType)
        shift.reset()
        lastSpaceAt = 0
        updateEnterLabel(info)
        updateAutoCaps()
        render()
        refreshBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keys?.cancelTouches()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (updateAutoCaps()) render()
    }

    private fun render() {
        val view = keys ?: return
        val labels = KeyLabels(getString(R.string.kb_mode_symbols), getString(R.string.kb_mode_letters),
            getString(R.string.kb_mode_more), getString(R.string.kb_space))
        view.shift = shift.mode
        view.rows = Layouts.build(mode, shift.mode, labels, offersNextKeyboard())
    }

    private fun updateEnterLabel(info: EditorInfo) {
        keys?.enterLabel = when (TextRules.enterAction(info.imeOptions)) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.kb_action_go)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.kb_action_search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.kb_action_send)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.kb_action_next)
            EditorInfo.IME_ACTION_PREVIOUS -> getString(R.string.kb_action_previous)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.kb_action_done)
            else -> "⏎"
        }
    }

    /** Returns true when the shift state changed. */
    private fun updateAutoCaps(): Boolean {
        if (mode != Mode.LETTERS) return false
        val before = shift.mode
        val info = currentInputEditorInfo
        val caps = info != null && info.inputType != 0 &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        shift.autoCapitalize(caps)
        return before != shift.mode
    }

    // ----- keys --------------------------------------------------------------------------------

    override fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (key.action) {
            Action.TEXT -> {
                ic.commitText(key.text, 1)
                lastSpaceAt = 0
                if (mode == Mode.LETTERS) shift.typed()
            }
            Action.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(2, 0) ?: ""
                if (now - lastSpaceAt < DOUBLE_SPACE_MILLIS && TextRules.wantsDoubleSpacePeriod(before)) {
                    ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); ic.endBatchEdit()
                    lastSpaceAt = 0
                } else {
                    ic.commitText(" ", 1)
                    lastSpaceAt = now
                }
                if (TextRules.returnsToLetters(mode, key.action)) mode = Mode.LETTERS
            }
            Action.DELETE -> delete()
            Action.ENTER -> enter()
            Action.SHIFT -> shift.tap(SystemClock.uptimeMillis())
            Action.LETTERS -> mode = Mode.LETTERS
            Action.SYMBOLS -> mode = Mode.SYMBOLS
            Action.SYMBOLS_MORE -> mode = Mode.SYMBOLS_MORE
            Action.EMOJI -> mode = Mode.EMOJI
            Action.NEXT_KEYBOARD -> nextKeyboard()
            Action.SPACER -> Unit
        }
        if (key.action != Action.SHIFT) updateAutoCaps()
        render()
    }

    override fun onLongPress(key: Key): Boolean {
        when {
            key.action == Action.SHIFT -> shift.lock()
            key.action == Action.SPACE || key.action == Action.NEXT_KEYBOARD ->
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            key.text == "," && mode != Mode.NUMBERS -> mode = Mode.EMOJI
            key.alternate != null -> {
                currentInputConnection?.commitText(key.alternate, 1)
                if (mode == Mode.LETTERS) shift.typed()
                updateAutoCaps()
            }
            else -> return false
        }
        render()
        return true
    }

    override fun onCursor(steps: Int) {
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        lastSpaceAt = 0
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(16, 0)
        // Editors that don't expose their text (terminals, some web views) get a real Delete key.
        if (before.isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingText(TextRules.lastGraphemeLength(before), 0)
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = info?.let { TextRules.enterAction(it.imeOptions) }
        if (action != null) currentInputConnection?.performEditorAction(action) else sendKeyChar('\n')
        lastSpaceAt = 0
    }

    private fun offersNextKeyboard(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) shouldOfferSwitchingToNextInputMethod()
        else @Suppress("DEPRECATION") getSystemService(InputMethodManager::class.java)
            .shouldOfferSwitchingToNextInputMethod(window.window?.attributes?.token)

    private fun nextKeyboard() {
        if (Build.VERSION.SDK_INT >= 28) { if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        else @Suppress("DEPRECATION") getSystemService(InputMethodManager::class.java)
            .switchToNextInputMethod(window.window?.attributes?.token, false)
    }

    // ----- DeviceLink --------------------------------------------------------------------------

    private fun pasteLastClip() {
        val text = link.history.value.firstOrNull { it.direction == ClipDirection.RECEIVED && it.text != null }?.text
        if (text == null) toast(getString(R.string.kb_nothing_received)) else currentInputConnection?.commitText(text, 1)
    }

    private fun send(clip: ClipData, quiet: Boolean) {
        scope.launch {
            val outcomes = runCatching { link.sendClip(clip) }.getOrDefault(emptyList())
            val ok = outcomes.isNotEmpty() && outcomes.all { it.accepted }
            if (ok) toast(getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name }))
            else if (!quiet || outcomes.isNotEmpty()) toast(getString(dev.devicelink.sdk.R.string.dl_send_failed))
        }
    }

    private fun ClipData.isSensitive() = Build.VERSION.SDK_INT >= 24 &&
        description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val AUTO = "auto"
        const val DOUBLE_SPACE_MILLIS = 1000L
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); password managers set it on older versions too.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
