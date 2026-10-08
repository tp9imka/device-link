package dev.devicelink.model

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PairingInviteTest {
    @Test fun `QR round trip normalizes identity pin and connection hint`() {
        val invite = PairingInvite("AB".repeat(32), "Work phone", "AA:BB:CC:DD:EE:FF")
        assertEquals(PairingInvite("ab".repeat(32), "Work phone", "aa:bb:cc:dd:ee:ff"), PairingInvite.decode(invite.encode()))
    }

    @Test fun `legacy QR remains compatible including a missing discovery address`() {
        val fingerprint = "ab".repeat(32)
        val legacy = encoded("""{"fingerprint":"$fingerprint","name":"Work phone","address":""}""")
        assertEquals(PairingInvite(fingerprint, "Work phone"), PairingInvite.decode(legacy))
    }

    @Test fun `invalid QR is rejected before starting a session`() {
        assertNull(PairingInvite.decode("https://example.com"))
        assertNull(PairingInvite.decode("dl2:abcd"))
        assertNull(PairingInvite.decode("dl1:!!"))
        assertNull(PairingInvite.decode("dl1:" + "a".repeat(2048)))
        assertNull(PairingInvite.decode(encoded("""{"fingerprint":"wrong"}""")))
        assertNull(PairingInvite.decode(encoded("""{"fingerprint":"${"ab".repeat(32)}","address":"../../remote"}""")))
    }

    private fun encoded(json: String): String = "dl1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
