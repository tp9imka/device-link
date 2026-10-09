package dev.devicelink.receiver.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModelTest {
    private val labels = KeyLabels("?123", "ABC", "=\\<", "space")

    @Test fun shiftTapIsOneShotAndDoubleTapLocks() {
        val shift = ShiftState()
        shift.tap(0)
        assertEquals(ShiftMode.ONCE, shift.mode)
        shift.typed()
        assertEquals(ShiftMode.OFF, shift.mode)

        shift.tap(1_000); shift.tap(1_200)
        assertEquals(ShiftMode.LOCKED, shift.mode)
        shift.typed(); shift.typed()
        assertEquals("caps lock survives typing", ShiftMode.LOCKED, shift.mode)
        shift.tap(1_300)
        assertEquals(ShiftMode.OFF, shift.mode)
        shift.tap(1_400)
        assertEquals("a quick tap right after unlocking is a fresh one-shot", ShiftMode.ONCE, shift.mode)
    }

    @Test fun typingBetweenShiftTapsIsNotADoubleTap() {
        val shift = ShiftState()
        shift.tap(0); shift.typed(); shift.tap(100)
        assertEquals(ShiftMode.ONCE, shift.mode)
    }

    @Test fun autoCapitalisationIsClearedButManualShiftIsKept() {
        val shift = ShiftState()
        shift.autoCapitalize(true)
        assertEquals(ShiftMode.ONCE, shift.mode)
        shift.autoCapitalize(false)
        assertEquals("cursor moved mid-sentence", ShiftMode.OFF, shift.mode)

        shift.tap(5_000)
        shift.autoCapitalize(false)
        assertEquals(ShiftMode.ONCE, shift.mode)

        shift.reset(); shift.lock()
        shift.autoCapitalize(false)
        assertEquals(ShiftMode.LOCKED, shift.mode)
    }

    @Test fun deleteRemovesWholeCharacters() {
        assertEquals(0, TextRules.lastGraphemeLength(""))
        assertEquals(1, TextRules.lastGraphemeLength("ab"))
        assertEquals("surrogate pair emoji", 2, TextRules.lastGraphemeLength("a😀"))
        assertEquals("letter + combining accent", 2, TextRules.lastGraphemeLength("aé"))
    }

    @Test fun doubleSpacePeriodOnlyAfterAWord() {
        assertTrue(TextRules.wantsDoubleSpacePeriod("o "))
        assertTrue(TextRules.wantsDoubleSpacePeriod("7 "))
        assertFalse(TextRules.wantsDoubleSpacePeriod(". "))
        assertFalse(TextRules.wantsDoubleSpacePeriod("  "))
        assertFalse(TextRules.wantsDoubleSpacePeriod("o"))
        assertFalse(TextRules.wantsDoubleSpacePeriod(" "))
    }

    @Test fun enterPerformsTheEditorActionUnlessSuppressed() {
        assertEquals(EditorInfo.IME_ACTION_SEARCH, TextRules.enterAction(EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EditorInfo.IME_ACTION_SEND, TextRules.enterAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI))
        assertNull("multi-line editors ask for newlines", TextRules.enterAction(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(TextRules.enterAction(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertNull(TextRules.enterAction(EditorInfo.IME_ACTION_NONE))
    }

    @Test fun numericEditorsOpenTheNumberPad() {
        assertEquals(Mode.NUMBERS, TextRules.initialMode(InputType.TYPE_CLASS_PHONE))
        assertEquals(Mode.NUMBERS, TextRules.initialMode(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(Mode.LETTERS, TextRules.initialMode(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(TextRules.returnsToLetters(Mode.SYMBOLS, Action.SPACE))
        assertFalse(TextRules.returnsToLetters(Mode.SYMBOLS_MORE, Action.SPACE))
    }

    @Test fun letterLayoutHasEveryLetterOnceAndFollowsShift() {
        val lower = Layouts.build(Mode.LETTERS, ShiftMode.OFF, labels, showNextKeyboard = false).flatten()
        val letters = lower.filter { it.action == Action.TEXT && it.text.single().isLetter() }.map { it.text }
        assertEquals(('a'..'z').map { it.toString() }, letters.sorted())
        val upper = Layouts.build(Mode.LETTERS, ShiftMode.ONCE, labels, showNextKeyboard = false).flatten()
        assertTrue(upper.filter { it.action == Action.TEXT && it.text.single().isLetter() }.all { it.text.single().isUpperCase() })
    }

    @Test fun everyPageCanDeleteAndGetBackToLetters() {
        for (mode in Mode.entries) for (next in listOf(true, false)) {
            val keys = Layouts.build(mode, ShiftMode.OFF, labels, next).flatten()
            assertTrue("$mode delete", keys.any { it.action == Action.DELETE })
            assertTrue("$mode space", keys.any { it.action == Action.SPACE })
            if (mode != Mode.LETTERS) assertTrue("$mode letters", keys.any { it.action == Action.LETTERS || it.action == Action.SYMBOLS })
            assertEquals("$mode globe", next && mode in setOf(Mode.LETTERS, Mode.SYMBOLS, Mode.SYMBOLS_MORE), keys.any { it.action == Action.NEXT_KEYBOARD })
        }
    }
}
