package dev.devicelink.transfer

import org.junit.Assert.*
import org.junit.Test

class RelayHttpClientTest {
    private fun rejected(url: String, debug: Boolean = true) {
        try { RelayHttpClient.normalizeUrl(url, debug); fail("Unsafe relay URL accepted") } catch (_: Exception) { }
    }
    @Test fun `release only allows HTTPS server origins`() {
        assertEquals("https://relay.example:8443", RelayHttpClient.normalizeUrl(" https://relay.example:8443/ ", false))
        rejected("http://relay.example", false)
        rejected("http://127.0.0.1:8080", false)
        rejected("file:///tmp/relay", false)
        rejected("https://user:password@relay.example", false)
        rejected("https://relay.example/path", false)
        rejected("https://relay.example?token=secret", false)
        rejected("https://relay.example#fragment", false)
    }
    @Test fun `debug cleartext exception is literal IPv4 loopback only`() {
        assertEquals("http://127.0.0.1:8080", RelayHttpClient.normalizeUrl("http://127.0.0.1:8080/", true))
        rejected("http://localhost:8080")
        rejected("http://127.0.0.2:8080")
        rejected("http://[::1]:8080")
        rejected("http://127.0.0.1.example:8080")
        rejected("http://2130706433:8080")
        rejected("http://127.0.0.1@external.example")
        rejected("http://127.0.0.1:8080/path")
    }
}
