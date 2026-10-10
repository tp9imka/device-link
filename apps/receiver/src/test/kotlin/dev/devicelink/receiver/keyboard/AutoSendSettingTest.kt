package dev.devicelink.receiver.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSendSettingTest {
    private val prefs = mutableMapOf<String, Boolean>()
    private fun setting() = AutoSendSetting({ prefs[it] ?: false }, { key, value -> prefs[key] = value })

    @Test fun aFreshInstallDoesNotSendCopiesAndTheFirstTurnOnAsksForTheDisclosure() {
        val setting = setting()
        assertFalse(setting.isOn)
        assertEquals(AutoSendToggle.NEEDS_DISCLOSURE, setting.toggle())
        assertFalse("asking is not consent", setting.isOn)
        setting.decline()
        assertFalse(setting.isOn)
        assertEquals("declining asks again next time", AutoSendToggle.NEEDS_DISCLOSURE, setting.toggle())
    }

    @Test fun acceptingTurnsItOnAndLaterTogglesDoNotAskAgain() {
        val setting = setting()
        setting.toggle(); setting.accept()
        assertTrue(setting.isOn)
        assertEquals(AutoSendToggle.TURNED_OFF, setting.toggle())
        assertFalse(setting.isOn)
        assertEquals("the disclosure is shown once", AutoSendToggle.TURNED_ON, setting.toggle())
        assertTrue("the choice is persisted", setting().isOn)
    }

    @Test fun anOnValueStoredBeforeTheDisclosureExistedIsNotConsent() {
        prefs["auto"] = true
        assertFalse(setting().isOn)
        assertEquals(AutoSendToggle.NEEDS_DISCLOSURE, setting().toggle())
    }
}
