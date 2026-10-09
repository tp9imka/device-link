package dev.devicelink.receiver.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.text.BreakIterator

/*
 * Keyboard model: layouts, shift state and text rules. No views here, so it is unit-testable.
 * Behaviour follows the conventions of AOSP LatinIME and Simple Keyboard (rkkr/simple-keyboard):
 * one-shot/locked shift, auto-capitalisation from the editor, double-space period, long-press
 * alternates shown as hints, space-bar cursor sliding and an Enter key that performs the editor action.
 */

enum class Action { TEXT, SHIFT, DELETE, ENTER, SPACE, SPACER, LETTERS, SYMBOLS, SYMBOLS_MORE, EMOJI, NEXT_KEYBOARD }

enum class Mode { LETTERS, SYMBOLS, SYMBOLS_MORE, EMOJI, NUMBERS }

/**
 * One key. [text] is what a tap commits (for TEXT keys); [alternate] is committed on long-press and
 * drawn as a small hint. [width] is relative to the other keys of its row.
 */
data class Key(
    val action: Action,
    val label: String = "",
    val text: String = label,
    val alternate: String? = null,
    val width: Float = 1f,
)

/** Labels that come from resources (user strings). */
data class KeyLabels(val symbols: String, val letters: String, val more: String, val space: String)

object Layouts {
    private const val TOP_ROW = "qwertyuiop"
    private const val TOP_HINTS = "1234567890"
    private const val MIDDLE_ROW = "asdfghjkl"
    private const val MIDDLE_HINTS = "@#\$_&-+()"
    private const val BOTTOM_ROW = "zxcvbnm"
    private const val BOTTOM_HINTS = "*\"':;!?"

    val EMOJI = listOf(
        "😀", "😂", "🥲", "😊", "😍", "😘", "😎", "🤔",
        "😅", "😭", "😡", "🥳", "😴", "🙄", "😉", "🤯",
        "👍", "👎", "👏", "🙏", "💪", "👌", "❤️", "🔥",
    )

    fun build(mode: Mode, shift: ShiftMode, labels: KeyLabels, showNextKeyboard: Boolean): List<List<Key>> = when (mode) {
        Mode.LETTERS -> letters(shift != ShiftMode.OFF, labels, showNextKeyboard)
        Mode.SYMBOLS -> symbols(labels, showNextKeyboard)
        Mode.SYMBOLS_MORE -> symbolsMore(labels, showNextKeyboard)
        Mode.EMOJI -> emoji(labels)
        Mode.NUMBERS -> numbers(labels)
    }

    private fun chars(row: String, hints: String, upper: Boolean) = row.mapIndexed { index, char ->
        val text = if (upper) char.uppercase() else char.toString()
        Key(Action.TEXT, text, alternate = hints[index].toString())
    }

    private fun letters(upper: Boolean, labels: KeyLabels, next: Boolean) = listOf(
        chars(TOP_ROW, TOP_HINTS, upper),
        listOf(Key(Action.SPACER, width = 0.5f)) + chars(MIDDLE_ROW, MIDDLE_HINTS, upper) + Key(Action.SPACER, width = 0.5f),
        listOf(Key(Action.SHIFT, width = 1.5f)) + chars(BOTTOM_ROW, BOTTOM_HINTS, upper) + Key(Action.DELETE, width = 1.5f),
        bottomRow(Key(Action.SYMBOLS, labels.symbols, width = 1.5f), labels, next),
    )

    private fun symbols(labels: KeyLabels, next: Boolean) = listOf(
        "1234567890".map { Key(Action.TEXT, it.toString()) },
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { Key(Action.TEXT, it) },
        listOf(Key(Action.SYMBOLS_MORE, labels.more, width = 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(Action.TEXT, it) } + Key(Action.DELETE, width = 1.5f),
        bottomRow(Key(Action.LETTERS, labels.letters, width = 1.5f), labels, next),
    )

    private fun symbolsMore(labels: KeyLabels, next: Boolean) = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key(Action.TEXT, it) },
        listOf("£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\").map { Key(Action.TEXT, it) },
        listOf(Key(Action.SYMBOLS, labels.symbols, width = 1.5f)) +
            listOf("%", "<", ">", "[", "]", "©", "™").map { Key(Action.TEXT, it) } + Key(Action.DELETE, width = 1.5f),
        bottomRow(Key(Action.LETTERS, labels.letters, width = 1.5f), labels, next),
    )

    private fun bottomRow(mode: Key, labels: KeyLabels, next: Boolean) = buildList {
        add(mode)
        add(Key(Action.TEXT, ",", alternate = "😊"))
        if (next) add(Key(Action.NEXT_KEYBOARD, "🌐"))
        add(Key(Action.SPACE, labels.space, text = " ", width = if (next) 4f else 5f))
        add(Key(Action.TEXT, ".", alternate = "…"))
        add(Key(Action.ENTER, width = 1.5f))
    }

    private fun emoji(labels: KeyLabels) = EMOJI.chunked(8).map { row -> row.map { Key(Action.TEXT, it) } } + listOf(listOf(
        Key(Action.LETTERS, labels.letters, width = 1.5f),
        Key(Action.SPACE, labels.space, text = " ", width = 5f),
        Key(Action.DELETE, width = 1.5f),
    ))

    private fun numbers(labels: KeyLabels) = listOf(
        listOf(Key(Action.TEXT, "1"), Key(Action.TEXT, "2"), Key(Action.TEXT, "3", alternate = "#"), Key(Action.TEXT, "-", alternate = "/")),
        listOf(Key(Action.TEXT, "4"), Key(Action.TEXT, "5"), Key(Action.TEXT, "6"), Key(Action.SPACE, "␣", text = " ")),
        listOf(Key(Action.TEXT, "7"), Key(Action.TEXT, "8", alternate = "*"), Key(Action.TEXT, "9"), Key(Action.DELETE)),
        listOf(Key(Action.LETTERS, labels.letters), Key(Action.TEXT, "0", alternate = "+"), Key(Action.TEXT, ".", alternate = ","), Key(Action.ENTER)),
    )
}

enum class ShiftMode { OFF, ONCE, LOCKED }

/**
 * Shift: tap for one capital, double-tap (or long-press) for caps lock, tap again to release.
 * Auto-capitalisation sets a one-shot shift that it may also clear again; a manual shift is kept.
 */
class ShiftState(private val doubleTapMillis: Long = 400) {
    var mode = ShiftMode.OFF
        private set
    private var automatic = false
    private var lastTap = NEVER

    fun tap(now: Long) {
        val previous = mode
        val quick = now - lastTap < doubleTapMillis
        mode = when {
            previous == ShiftMode.LOCKED -> ShiftMode.OFF
            quick -> ShiftMode.LOCKED
            previous == ShiftMode.ONCE -> ShiftMode.OFF
            else -> ShiftMode.ONCE
        }
        // Only two taps on an unlocked shift form a double-tap.
        lastTap = if (mode == ShiftMode.LOCKED || previous == ShiftMode.LOCKED) NEVER else now
        automatic = false
    }

    fun lock() { mode = ShiftMode.LOCKED; automatic = false; lastTap = NEVER }

    /** A character was typed: a one-shot shift is used up. */
    fun typed() {
        lastTap = NEVER
        if (mode == ShiftMode.ONCE) { mode = ShiftMode.OFF; automatic = false }
    }

    /** The editor says whether the cursor is at a position that wants a capital. */
    fun autoCapitalize(wantsCapital: Boolean) {
        if (mode == ShiftMode.LOCKED) return
        if (wantsCapital && mode == ShiftMode.OFF) { mode = ShiftMode.ONCE; automatic = true }
        else if (!wantsCapital && mode == ShiftMode.ONCE && automatic) { mode = ShiftMode.OFF; automatic = false }
    }

    fun reset() { mode = ShiftMode.OFF; automatic = false; lastTap = NEVER }

    private companion object { const val NEVER = Long.MIN_VALUE / 2 }
}

object TextRules {
    /** Number of chars the last user-perceived character (emoji, accented letter) occupies. */
    fun lastGraphemeLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val text = before.toString()
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val start = iterator.preceding(text.length)
        return if (start == BreakIterator.DONE) 1 else text.length - start
    }

    /** Second space right after a word: replace "x " with "x. ". */
    fun wantsDoubleSpacePeriod(before: CharSequence): Boolean {
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val previous = before[before.length - 2]
        return previous.isLetterOrDigit() || previous == ')' || previous == '"' || previous == '\''
    }

    /** Editor action for Enter, or null when Enter should insert a newline / send KEYCODE_ENTER. */
    fun enterAction(imeOptions: Int): Int? {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS -> action
            else -> null
        }
    }

    fun initialMode(inputType: Int): Mode = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Mode.NUMBERS
        else -> Mode.LETTERS
    }

    /** Symbol pages return to letters after a space, as on most Android keyboards. */
    fun returnsToLetters(mode: Mode, action: Action) = action == Action.SPACE && (mode == Mode.SYMBOLS || mode == Mode.EMOJI)
}
