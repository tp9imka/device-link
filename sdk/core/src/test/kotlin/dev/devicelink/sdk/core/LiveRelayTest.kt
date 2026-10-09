package dev.devicelink.sdk.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real relay when DEVICELINK_RELAY_URL is set, e.g.
 * `RELAY_ENROLLMENT_TOKEN=t python -m relay` and DEVICELINK_RELAY_URL=http://127.0.0.1:8000 DEVICELINK_TOKEN=t.
 * scripts/interop_e2e.sh drives this plus the Swift client.
 */
class LiveRelayTest {
    private val url = System.getenv("DEVICELINK_RELAY_URL").orEmpty()
    private val token = System.getenv("DEVICELINK_TOKEN").orEmpty()

    private fun client(name: String, platform: String) = DeviceLinkClient(SoftwareIdentity(), EncryptionKeyPair.generate(),
        MemoryLinkStore(), LinkConfig(name, platform, model = "JVM test", appVersion = "test", allowInsecureRelay = true, pollWaitSeconds = 5))

    @Test fun `pair and exchange clipboard items through a live relay`() = runBlocking {
        assumeTrue("Set DEVICELINK_RELAY_URL to run", url.isNotEmpty())
        val inviter = client("JVM inviter", "desktop")
        val joiner = client("JVM joiner", "android")
        inviter.configure(url, token)
        val session = inviter.invite()
        val linked = async { session.awaitPeer() }
        assertEquals(inviter.deviceId, joiner.join(session.uri).id)
        assertEquals(joiner.deviceId, linked.await().id)

        val sent = inviter.send(OutgoingContent.Text("live hello")).single()
        val received = mutableListOf<String>()
        withTimeout(20_000) { while (received.isEmpty()) joiner.receiveOnce(5) { received += it.text!!; ReceiptStatus.COPIED } }
        assertEquals(listOf("live hello"), received)
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { inviter.events.first { it is LinkEvent.Receipt } }
        withTimeout(20_000) { while (!receipt.isCompleted) inviter.receiveOnce(5) { error("unexpected") } }
        assertEquals(LinkEvent.Receipt(sent.itemId!!, joiner.deviceId, ReceiptStatus.COPIED), receipt.await())

        val image = ByteArray(200_000) { (it % 251).toByte() }
        joiner.send(OutgoingContent.Image("photo.png", "image/png", image))
        var bytes: ByteArray? = null
        withTimeout(20_000) { while (bytes == null) inviter.receiveOnce(5) { bytes = it.bytes(); ReceiptStatus.COPIED } }
        assertEquals(image.toList(), bytes!!.toList())
        inviter.unlink(joiner.deviceId)
    }
}
