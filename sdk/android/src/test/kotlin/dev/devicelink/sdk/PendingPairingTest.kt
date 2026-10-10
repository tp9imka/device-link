package dev.devicelink.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPairingTest {
    private class HostActivity
    private class OtherActivity

    @Test fun linkOpenedBeforeResumeRunsOnceWhenThatActivityResumes() {
        val pending = PendingPairing<String>()
        pending.put(HostActivity::class.java, "devicelink://pair#v2.x.y", "cb", now = 0)
        assertEquals("devicelink://pair#v2.x.y" to "cb", pending.take(HostActivity::class.java, now = 60_000))
        assertNull(pending.take(HostActivity::class.java, now = 60_001))
    }

    @Test fun anotherActivityResumingDoesNotConsumeIt() {
        val pending = PendingPairing<String>()
        pending.put(HostActivity::class.java, "link", "cb", now = 0)
        assertNull(pending.take(OtherActivity::class.java, now = 1))
        assertEquals("link" to "cb", pending.take(HostActivity::class.java, now = 2))
    }

    @Test fun aLinkOlderThanAnyPairingCodeIsDropped() {
        val pending = PendingPairing<String>()
        pending.put(HostActivity::class.java, "link", "cb", now = 0)
        assertNull(pending.take(HostActivity::class.java, now = PendingPairing.MAX_AGE_MILLIS + 1))
    }

    @Test fun theNewestLinkReplacesAnOlderOne() {
        val pending = PendingPairing<String>()
        pending.put(HostActivity::class.java, "old", "a", now = 0)
        pending.put(HostActivity::class.java, "new", "b", now = 1)
        assertEquals("new" to "b", pending.take(HostActivity::class.java, now = 2))
    }
}
