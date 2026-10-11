package dev.devicelink.receiver.keyboard

/** What a tap on the keyboard's auto-send chip did. */
enum class AutoSendToggle { TURNED_ON, TURNED_OFF, NEEDS_DISCLOSURE }

/**
 * Keyboard auto-send: off until the user turns it on and accepts the disclosure once. After that,
 * the chip toggles it freely. A stored "on" without an accepted disclosure (older builds defaulted
 * to on) is not consent and reads as off. [read] returns false for missing keys.
 */
class AutoSendSetting(private val read: (String) -> Boolean, private val write: (String, Boolean) -> Unit) {
    val isOn: Boolean get() = read(ON) && read(DISCLOSED)

    fun toggle(): AutoSendToggle = when {
        isOn -> { write(ON, false); AutoSendToggle.TURNED_OFF }
        !read(DISCLOSED) -> AutoSendToggle.NEEDS_DISCLOSURE
        else -> { write(ON, true); AutoSendToggle.TURNED_ON }
    }

    /** The user accepted the disclosure: remember it and turn auto-send on. */
    fun accept() { write(DISCLOSED, true); write(ON, true) }

    /** The user dismissed the disclosure: stays off, and the next turn-on asks again. */
    fun decline() { write(ON, false) }

    private companion object {
        const val ON = "auto"
        const val DISCLOSED = "auto_disclosure_accepted"
    }
}
