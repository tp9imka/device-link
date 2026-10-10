package dev.devicelink.sdk.core

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin half of scripts/interop_e2e.sh, run with DEVICELINK_INTEROP_DIR and DEVICELINK_RELAY_URL.
 * Phase 1: Kotlin shows the code, the Swift CLI joins and they exchange text, an image and receipts.
 * Phase 2: the Swift CLI shows the code and a second Kotlin device joins.
 */
class InteropPeerTest {
    private val directory = System.getenv("DEVICELINK_INTEROP_DIR")?.let(::File)
    private val relay = System.getenv("DEVICELINK_RELAY_URL").orEmpty()

    private fun client(name: String) = DeviceLinkClient(SoftwareIdentity(), EncryptionKeyPair.generate(), MemoryLinkStore(),
        LinkConfig(name, "android", allowInsecureRelay = true, pollWaitSeconds = 5, defaultRelayUrl = relay,
            defaultEnrollmentToken = System.getenv("DEVICELINK_TOKEN").orEmpty()))

    @Test fun `kotlin and swift link and exchange through a live relay`() = runBlocking {
        assumeTrue(directory != null && relay.isNotEmpty())
        val android = client("Kotlin phone")
        val session = android.invite()
        File(directory, "invite.txt").writeText(session.uri)
        val swift = withTimeout(120_000) { session.awaitPeer() }
        assertEquals("desktop", swift.platform)

        val texts = mutableListOf<String>()
        withTimeout(60_000) { while (texts.isEmpty()) android.receiveOnce(5) { texts += it.text!!; ReceiptStatus.COPIED } }
        assertEquals("hello from swift", texts.single())

        val receipts = async(start = CoroutineStart.UNDISPATCHED) {
            android.events.filterIsInstance<LinkEvent.Receipt>().first { it.status == ReceiptStatus.COPIED }
        }
        android.send(OutgoingContent.Text("hello from kotlin ✓"))
        val image = ByteArray(150_000) { (it * 7).toByte() }
        File(directory, "image.sha256").writeText(Encoding.hex(Encoding.sha256(image)))
        android.send(OutgoingContent.Image("kotlin.png", "image/png", image))
        withTimeout(60_000) { while (!receipts.isCompleted) android.receiveOnce(5) { ReceiptStatus.FAILED } }
        File(directory, "phase1.done").writeText("ok")

        // Phase 2: join the code shown by the Swift CLI.
        val joinFile = File(directory, "swift-invite.txt")
        withTimeout(120_000) { while (!joinFile.isFile || joinFile.readText().isBlank()) delay(200) }
        val second = client("Kotlin tablet")
        assertEquals(swift.id, second.join(joinFile.readText().trim()).id)
        assertEquals(true, second.send(OutgoingContent.Text("joined your code")).single().accepted)
    }
}
