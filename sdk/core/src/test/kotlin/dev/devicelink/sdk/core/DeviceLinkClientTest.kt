package dev.devicelink.sdk.core

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory relay that verifies request signatures, enough to drive the engine end to end. */
private class FakeRelay(private val clock: () -> Long) : HttpTransport {
    val devices = mutableSetOf<String>()
    val allow = mutableSetOf<Pair<String, String>>() // recipient to sender
    val mailbox = mutableListOf<Envelope>()
    val pairings = mutableMapOf<String, MutableMap<String, String>>()
    var requests = 0

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Int): HttpResponse = synchronized(this) {
        requests++
        val uri = URI(url)
        val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val key = Encoding.unb64(headers.getValue("X-Device-Key"), 128)
        val canonical = "DeviceLink relay request v1\n$method\n$target\n${headers["X-Device-Time"]}\n${headers["X-Device-Nonce"]}\n${Encoding.hex(Encoding.sha256(body))}"
        check(Identity.verify(key, canonical.toByteArray(), Encoding.unb64(headers.getValue("X-Device-Signature"), 80))) { "bad signature" }
        val caller = Identity.deviceId(key)
        val path = uri.rawPath.split('/').drop(1)
        fun ok(text: String = "{}") = HttpResponse(200, text.toByteArray())
        fun error(status: Int, code: String) = HttpResponse(status, """{"error":"$code"}""".toByteArray())
        if (path == listOf("v1", "register")) { devices += caller; return ok() }
        if (caller !in devices) return error(403, "registration_required")
        val json = if (body.isEmpty()) JsonObject(emptyMap()) else wireJson.decodeFromString<JsonObject>(body.toString(Charsets.UTF_8))
        when {
            path == listOf("v1", "pairings") && method == "POST" -> {
                pairings[json["id"]!!.jsonPrimitive.content] = mutableMapOf("creator" to caller, "state" to "open", "expires" to json["expiresAt"]!!.jsonPrimitive.long.toString())
                ok()
            }
            path.size == 4 && path[1] == "pairings" && path[3] == "join" -> {
                val pairing = pairings[path[2]] ?: return error(404, "pairing_not_found")
                if (pairing["state"] != "open") return error(409, "pairing_used")
                pairing += mapOf("state" to "joined", "joiner" to caller, "joinSealed" to json["sealed"]!!.jsonPrimitive.content); ok()
            }
            path.size == 4 && path[1] == "pairings" && path[3] == "confirm" -> {
                val pairing = pairings[path[2]]!!
                check(pairing["creator"] == caller)
                pairing += mapOf("state" to "confirmed", "confirmSealed" to json["sealed"]!!.jsonPrimitive.content); ok()
            }
            path.size == 3 && path[1] == "pairings" && method == "GET" -> {
                val p = pairings[path[2]] ?: return error(404, "pairing_not_found")
                val sealed = if (caller == p["creator"]) p["joinSealed"] else p["confirmSealed"]
                ok("""{"state":"${p["state"]}","joinerId":${p["joiner"]?.let { "\"$it\"" }},"sealed":${sealed?.let { "\"$it\"" }}}""")
            }
            path.size == 3 && path[1] == "pairings" && method == "DELETE" -> { pairings.remove(path[2]); ok() }
            path.size == 3 && path[1] == "peers" -> { if (method == "PUT") allow += caller to path[2] else allow -= caller to path[2]; HttpResponse(204, ByteArray(0)) }
            path == listOf("v1", "messages") && method == "POST" -> {
                val envelope = Envelope.decode(body.toString(Charsets.UTF_8))
                if (envelope.senderId != caller) return error(403, "sender_mismatch")
                if ((envelope.recipientId to caller) !in allow) return error(403, "recipient_not_authorized")
                mailbox += envelope; HttpResponse(201, "{}".toByteArray())
            }
            path == listOf("v1", "messages") && method == "GET" -> {
                mailbox.removeAll { it.expiresAt <= clock() }
                val mine = mailbox.filter { it.recipientId == caller }.sortedByDescending { it.sequence }.take(1)
                ok("[" + mine.joinToString(",") { it.encode().toString(Charsets.UTF_8) } + "]")
            }
            path.size == 3 && path[1] == "messages" && method == "DELETE" -> { mailbox.removeAll { it.id == path[2] && it.recipientId == caller }; HttpResponse(204, ByteArray(0)) }
            else -> error(404, "not_found")
        }
    }
}

class DeviceLinkClientTest {
    private var now = 1_800_000_000_000L
    private val relay = FakeRelay { now }
    private fun client(name: String, platform: String, store: LinkStore = MemoryLinkStore()) = DeviceLinkClient(
        SoftwareIdentity(), EncryptionKeyPair.generate(), store, LinkConfig(name, platform), relay) { now }

    private val android = client("Pixel", "android")
    private val iphone = client("iPhone", "ios")

    private fun linked() = runBlocking {
        android.configure("https://relay.example")
        val session = android.invite()
        val inviter = async { session.awaitPeer() }
        val joined = iphone.join(session.uri)
        assertEquals(android.deviceId, joined.id)
        assertEquals(iphone.deviceId, inviter.await().id)
    }

    @Test fun `qr pairing links both devices with names and relay adopted from the code`() {
        linked()
        assertEquals("https://relay.example", iphone.relayUrl)
        assertEquals(listOf("iPhone"), android.peers.value.map { it.name })
        assertEquals(listOf("Pixel"), iphone.peers.value.map { it.name })
        assertTrue(relay.allow.containsAll(listOf(android.deviceId to iphone.deviceId, iphone.deviceId to android.deviceId)))
    }

    @Test fun `first device with a built-in relay shows a code without any setup`() = runBlocking {
        val first = DeviceLinkClient(SoftwareIdentity(), EncryptionKeyPair.generate(), MemoryLinkStore(),
            LinkConfig("Pixel", "android", defaultRelayUrl = "https://relay.example/"), relay) { now }
        assertEquals("https://relay.example", first.relayUrl)
        val session = first.invite()
        val inviter = async { session.awaitPeer() }
        iphone.join(session.uri)
        assertEquals(iphone.deviceId, inviter.await().id)
        assertTrue(first.deviceId in relay.devices)
    }

    @Test fun `a pairing code links exactly one joiner`() = runBlocking {
        android.configure("https://relay.example")
        val session = android.invite()
        val inviter = async { session.awaitPeer() }
        iphone.join(session.uri)
        inviter.await()
        val intruder = client("Other", "ios")
        val failure = runCatching { intruder.join(session.uri) }.exceptionOrNull()
        assertTrue(failure is PairingException)
        assertEquals(1, android.peers.value.size)
    }

    @Test fun `copied text arrives once, receipt reports copied`() = runBlocking {
        linked()
        val sent = android.send(OutgoingContent.Text("hello from android")).single()
        assertTrue(sent.accepted)
        val received = mutableListOf<IncomingItem>()
        iphone.receiveOnce { received += it; ReceiptStatus.COPIED }
        assertEquals("hello from android", received.single().text)
        assertFalse(received.single().stale)
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { android.events.first { it is LinkEvent.Receipt } }
        android.receiveOnce { error("no content expected") }
        assertEquals(LinkEvent.Receipt(sent.itemId!!, iphone.deviceId, ReceiptStatus.COPIED), withTimeout(1_000) { receipt.await() })
        assertTrue(relay.mailbox.isEmpty())
    }

    @Test fun `an older clip delivered after a newer one is marked stale`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("first"))
        android.send(OutgoingContent.Text("second"))
        val received = mutableListOf<IncomingItem>()
        repeat(2) { iphone.receiveOnce { received += it; if (it.stale) ReceiptStatus.DELIVERED else ReceiptStatus.COPIED } }
        assertEquals(listOf("second" to false, "first" to true), received.map { it.text to it.stale })
    }

    @Test fun `duplicate deliveries are acknowledged without reprocessing`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("once"))
        val copy = relay.mailbox.single()
        var calls = 0
        iphone.receiveOnce { calls++; ReceiptStatus.COPIED }
        relay.mailbox += copy
        iphone.receiveOnce { calls++; ReceiptStatus.COPIED }
        assertEquals(1, calls)
    }

    @Test fun `expired items are never delivered`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("late"))
        now += Limits.DEFAULT_LIFETIME_MILLIS
        assertEquals(0, iphone.receiveOnce { error("must not deliver") })
    }

    @Test fun `unlink removes the peer on both sides and stops delivery`() = runBlocking {
        linked()
        android.unlink(iphone.deviceId)
        assertTrue(android.peers.value.isEmpty())
        iphone.receiveOnce { error("unlink is not content") }
        assertTrue(iphone.peers.value.isEmpty())
        assertFalse((iphone.deviceId to android.deviceId) in relay.allow)
        assertTrue(iphone.send(OutgoingContent.Text("x")).isEmpty())
    }

    @Test fun `images and files carry bytes and metadata`() = runBlocking {
        linked()
        iphone.send(OutgoingContent.Image("shot.png", "image/png", byteArrayOf(1, 2, 3)))
        val item = mutableListOf<IncomingItem>()
        android.receiveOnce { item += it; ReceiptStatus.COPIED }
        assertEquals(PayloadKind.IMAGE, item.single().kind)
        assertEquals("image/png", item.single().mime)
        assertEquals(listOf<Byte>(1, 2, 3), item.single().bytes()!!.toList())
    }

    @Test fun `link state survives restart through the file store`() = runBlocking {
        val file = java.io.File.createTempFile("link", ".json").apply { delete(); deleteOnExit() }
        val identity = SoftwareIdentity(); val keys = EncryptionKeyPair.generate()
        val first = DeviceLinkClient(identity, keys, FileLinkStore(file), LinkConfig("Desk", "desktop"), relay) { now }
        first.configure("https://relay.example")
        val session = first.invite()
        val inviter = async { session.awaitPeer() }
        iphone.join(session.uri); inviter.await()
        val restarted = DeviceLinkClient(identity, keys, FileLinkStore(file), LinkConfig("Desk", "desktop"), relay) { now }
        assertEquals(listOf(iphone.deviceId), restarted.peers.value.map { it.id })
        assertTrue(restarted.send(OutgoingContent.Text("after restart")).single().accepted)
    }

    @Test fun `both devices derive the same confirmation code`() {
        linked()
        val code = android.peers.value.single().pairingCode
        assertTrue(code!!.matches(Regex("[0-9]{6}")))
        assertEquals(code, iphone.peers.value.single().pairingCode)
    }

    @Test fun `files above one envelope are chunked, reassembled and receipted as one item`() = runBlocking {
        linked()
        val file = ByteArray(Limits.MAX_CONTENT_BYTES + 3 * 1024 * 1024) { (it * 31).toByte() }
        val sent = android.send(OutgoingContent.File("big.bin", "application/octet-stream", file)).single()
        assertTrue(sent.accepted)
        assertEquals(4, relay.mailbox.size)
        val items = mutableListOf<IncomingItem>()
        repeat(4) { iphone.receiveOnce { items += it; ReceiptStatus.DELIVERED } }
        assertEquals(sent.itemId, items.single().id)
        assertTrue(file.contentEquals(items.single().bytes()!!))
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { android.events.first { it is LinkEvent.Receipt } }
        android.receiveOnce { error("no content") }
        assertEquals(sent.itemId, (withTimeout(1_000) { receipt.await() } as LinkEvent.Receipt).itemId)
        assertTrue(relay.mailbox.isEmpty())
    }

    @Test fun `rich text and sensitive flag reach the receiver`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("bold", html = "<b>bold</b>", sensitive = true))
        val items = mutableListOf<IncomingItem>()
        iphone.receiveOnce { items += it; ReceiptStatus.COPIED }
        assertEquals("<b>bold</b>", items.single().html)
        assertTrue(items.single().sensitive)
    }

    @Test fun `oversized files are refused before upload`() = runBlocking {
        linked()
        val failure = runCatching { android.send(OutgoingContent.File("huge", "application/zip", ByteArray(Limits.MAX_FILE_BYTES + 1))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(relay.mailbox.isEmpty())
    }

    @Test fun `diagnostics record polls, deliveries and errors without content`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("secret words"))
        iphone.receiveOnce { ReceiptStatus.COPIED }
        val diagnostics = iphone.diagnostics.value
        assertEquals(1, diagnostics.received)
        assertTrue(diagnostics.lastPollAt != null && diagnostics.registered && diagnostics.peers == 1)
        relay.devices.remove(iphone.deviceId)
        relay.devices.clear()
        runCatching { iphone.receiveOnce { ReceiptStatus.COPIED } }
        assertFalse(iphone.diagnostics.value.toString().contains("secret"))
    }

    @Test fun `unlink all forgets every peer`() = runBlocking {
        linked()
        android.unlinkAll()
        assertTrue(android.peers.value.isEmpty())
    }
}
