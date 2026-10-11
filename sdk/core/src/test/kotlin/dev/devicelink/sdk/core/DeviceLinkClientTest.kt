package dev.devicelink.sdk.core

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
    val registrations = mutableListOf<DeviceMetadata>()
    var registrationFault: (() -> HttpResponse)? = null
    /** Injected failures: the next N acknowledgements / mailbox polls / confirms answer with this error. */
    var failAcks = 0
    val pollErrors = ArrayDeque<Pair<Int, String>>()
    var failConfirm: Pair<Int, String>? = null
    /** The confirm is stored, then the response is lost (answered with [failConfirm]). */
    var confirmCommitsBeforeFailing = false
    /** The next confirm is stored, then the connection drops before the response arrives. */
    var confirmResponseLost = false
    /** The next N `GET /v1/pairings/{id}?wait=0` (the inviter's re-read after a failed confirm) fail with a network error. */
    var failPairingReReads = 0
    /** Every confirm body the relay received, in order. */
    val confirms = mutableListOf<String>()
    /** The next `PUT /v1/peers/…` by this device ID fails with a network error. */
    var failNextAllowBy: String? = null
    /** Every `PUT /v1/peers/…` by this device ID is refused with this status and error code. */
    var refuseAllowBy: Pair<String, Pair<Int, String>>? = null
    /** `PUT /v1/peers/…` attempts per calling device ID. */
    val allowAttempts = mutableMapOf<String, Int>()
    val polls = mutableListOf<String>()
    /** `POST /v1/messages` attempts so far; [uploadFaults] answers (or throws for) the attempt with that 1-based number. */
    var uploads = 0
    val uploadFaults = mutableMapOf<Int, () -> HttpResponse>()
    /** Recipient of every envelope the relay stored, in order (survives later deletes). */
    val storedFor = mutableListOf<String>()
    /** The relay cannot be reached: every request fails with a connection error. */
    var down = false

    /** Empty mailbox polls wait out `wait` (virtual time under runTest), like the real long poll. */
    var longPoll = false

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Int): HttpResponse {
        if (down) throw java.net.ConnectException("connection refused")
        val response = synchronized(this) { handle(method, url, headers, body) }
        val wait = Regex("[?&]wait=([0-9]+)").find(url)?.groupValues?.get(1)?.toLong() ?: 0
        if (longPoll && method == "GET" && "/v1/messages?" in url && String(response.body) == "[]") kotlinx.coroutines.delay(wait * 1000)
        return response
    }

    private fun handle(method: String, url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
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
        if (path == listOf("v1", "register")) {
            registrations += wireJson.decodeFromString<DeviceMetadata>(body.toString(Charsets.UTF_8))
            registrationFault?.let { return it() }
            devices += caller
            return ok()
        }
        if (path == listOf("v1", "devices", "self") && method == "DELETE") {
            // Same effect as server/relay store.remove_device; idempotent for an unknown device.
            devices -= caller
            allow.removeAll { it.first == caller || it.second == caller }
            // What it sent (its unlink notices) stays until delivered or expired.
            mailbox.removeAll { it.recipientId == caller }
            pairings.values.removeAll { it["creator"] == caller || it["joiner"] == caller }
            return HttpResponse(204, ByteArray(0))
        }
        if (caller !in devices) return error(403, "registration_required")
        val json = if (body.isEmpty()) JsonObject(emptyMap()) else wireJson.decodeFromString<JsonObject>(body.toString(Charsets.UTF_8))
        if (path.size == 3 && path[1] == "peers" && method == "PUT") {
            allowAttempts.merge(caller, 1, Int::plus)
            refuseAllowBy?.takeIf { it.first == caller }?.let { (_, refusal) -> return error(refusal.first, refusal.second) }
        }
        return when {
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
                if (failConfirm != null && !confirmCommitsBeforeFailing) return error(failConfirm!!.first, failConfirm!!.second)
                val sealed = json["sealed"]!!.jsonPrimitive.content
                confirms += sealed
                val pairing = pairings[path[2]] ?: return error(404, "pairing_not_found")
                check(pairing["creator"] == caller)
                // Same as server/relay store.confirm_pairing: a resend of the same confirm is idempotent.
                if (pairing["state"] == "confirmed" && pairing["confirmSealed"] == sealed) return ok()
                if (pairing["state"] != "joined") return error(409, "pairing_not_joined")
                pairing += mapOf("state" to "confirmed", "confirmSealed" to sealed)
                allow += caller to pairing.getValue("joiner")
                if (confirmResponseLost) { confirmResponseLost = false; throw java.io.IOException("connection reset") }
                failConfirm?.let { (status, code) -> return error(status, code) }
                ok()
            }
            path.size == 3 && path[1] == "pairings" && method == "GET" && "wait=0" in target && failPairingReReads > 0 -> {
                failPairingReReads--
                throw java.io.IOException("connection reset")
            }
            path.size == 3 && path[1] == "pairings" && method == "GET" -> {
                val p = pairings[path[2]] ?: return error(404, "pairing_not_found")
                val sealed = if (caller == p["creator"]) p["joinSealed"] else p["confirmSealed"]
                ok("""{"state":"${p["state"]}","joinerId":${p["joiner"]?.let { "\"$it\"" }},"sealed":${sealed?.let { "\"$it\"" }}}""")
            }
            path.size == 3 && path[1] == "pairings" && method == "DELETE" -> { pairings.remove(path[2]); ok() }
            path.size == 3 && path[1] == "peers" && method == "PUT" && failNextAllowBy == caller -> {
                failNextAllowBy = null
                throw java.io.IOException("connection reset")
            }
            path.size == 3 && path[1] == "peers" -> { if (method == "PUT") allow += caller to path[2] else allow -= caller to path[2]; HttpResponse(204, ByteArray(0)) }
            path == listOf("v1", "messages") && method == "POST" -> {
                uploadFaults.remove(++uploads)?.let { return it() }
                val envelope = Envelope.decode(body.toString(Charsets.UTF_8))
                if (envelope.senderId != caller) return error(403, "sender_mismatch")
                if ((envelope.recipientId to caller) !in allow) return error(403, "recipient_not_authorized")
                mailbox += envelope; storedFor += envelope.recipientId; HttpResponse(201, "{}".toByteArray())
            }
            path == listOf("v1", "messages") && method == "GET" -> {
                polls += target
                pollErrors.removeFirstOrNull()?.let { (status, code) -> return error(status, code) }
                mailbox.removeAll { it.expiresAt <= clock() }
                val query = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val excluded = query["exclude"]?.split(',').orEmpty()
                check(excluded.size <= 20) { "relay caps exclude at 20" }
                // Same order as server/relay/store.py: sender, sequence DESC, id.
                val mine = mailbox.filter { it.recipientId == caller && it.id !in excluded }
                    .sortedWith(compareBy<Envelope> { it.senderId }.thenByDescending { it.sequence }.thenBy { it.id })
                    .take(query["limit"]?.toInt() ?: 20)
                ok("[" + mine.joinToString(",") { it.encode().toString(Charsets.UTF_8) } + "]")
            }
            path.size == 3 && path[1] == "messages" && method == "GET" ->
                mailbox.firstOrNull { it.id == path[2] && it.recipientId == caller }?.let { ok(it.encode().toString(Charsets.UTF_8)) } ?: error(404, "message_not_found")
            path.size == 3 && path[1] == "messages" && method == "DELETE" && failAcks > 0 -> { failAcks--; error(503, "unavailable") }
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

    private fun metadataClient(device: Restartable, metadata: DeviceMetadata) =
        device.start(LinkConfig("Phone", metadata.platform, metadata.model, metadata.appVersion))

    @Test fun `metadata refreshes once per change and survives engine restart`() = runBlocking {
        val device = Restartable()
        val original = DeviceMetadata("android", "Phone", "1.0")
        metadataClient(device, original).apply { configure("https://relay.example"); ensureRegistered() }
        for (metadata in listOf(original, original.copy(appVersion = "2.0.0"),
                original.copy(appVersion = "2.0.0", model = "Tablet"),
                original.copy(appVersion = "2.0.0", model = "Tablet", platform = "desktop"))) {
            val before = relay.registrations.size
            val changed = device.store.load().registeredMetadata != metadata
            val engine = metadataClient(device, metadata)
            assertTrue(engine.diagnostics.value.registered)
            repeat(2) { assertEquals(0, engine.receiveOnce(0) { error("empty mailbox") }) }
            metadataClient(device, metadata).receiveOnce(0) { error("empty mailbox") }
            assertEquals(before + if (changed) 1 else 0, relay.registrations.size)
            assertEquals(metadata, relay.registrations.last())
            assertEquals(metadata, device.store.load().registeredMetadata)
        }
    }

    @Test fun `legacy registration without metadata refreshes once`() = runBlocking {
        val device = Restartable()
        val metadata = DeviceMetadata("android", "Phone", "2.0.0")
        val engine = metadataClient(device, metadata)
        engine.configure("https://relay.example")
        engine.ensureRegistered()
        device.store.update { it.copy(registeredMetadata = null) }
        repeat(2) { engine.receiveOnce(0) { error("empty mailbox") } }
        assertEquals(2, relay.registrations.size)
        assertEquals(metadata, device.store.load().registeredMetadata)
    }

    @Test fun `relay rejection of metadata refresh preserves calls and is not retried`() = runBlocking {
        val device = Restartable()
        val old = DeviceMetadata("android", "Phone", "1.0")
        metadataClient(device, old).apply { configure("https://relay.example"); ensureRegistered() }
        val metadata = old.copy(appVersion = "2.0.0")
        relay.registrationFault = relayError(403, "enrollment_required")
        val updated = metadataClient(device, metadata)
        repeat(2) { assertEquals(0, updated.receiveOnce(0) { error("empty mailbox") }) }
        metadataClient(device, metadata).receiveOnce(0) { error("empty mailbox") }
        assertEquals(2, relay.registrations.size)
        assertEquals(3, relay.polls.size)
        assertEquals(metadata, device.store.load().registeredMetadata)
        assertTrue(updated.diagnostics.value.registered)
    }

    @Test fun `transport failure leaves metadata refresh pending`() = runBlocking {
        val device = Restartable()
        val old = DeviceMetadata("android", "Phone", "1.0")
        metadataClient(device, old).apply { configure("https://relay.example"); ensureRegistered() }
        val metadata = old.copy(appVersion = "2.0.0")
        val updated = metadataClient(device, metadata)
        relay.registrationFault = { throw java.io.IOException("connection reset") }
        try { updated.receiveOnce(0) { error("empty mailbox") }; error("expected transport failure") }
        catch (_: java.io.IOException) { }
        assertEquals(old, device.store.load().registeredMetadata)
        relay.registrationFault = null
        repeat(2) { assertEquals(0, updated.receiveOnce(0) { error("empty mailbox") }) }
        assertEquals(3, relay.registrations.size)
        assertEquals(metadata, device.store.load().registeredMetadata)
    }

    @Test fun `enrollment failures still propagate for first forced pairing and forgotten registrations`() = runBlocking {
        for (mode in listOf("first", "forced", "pairing", "forgotten")) {
            relay.registrationFault = null
            val device = Restartable()
            val metadata = DeviceMetadata("android", "Phone", "2.0.0")
            val engine = metadataClient(device, metadata)
            engine.configure("https://relay.example")
            if (mode != "first") engine.ensureRegistered()
            if (mode == "forgotten") relay.devices.clear()
            val before = device.store.load()
            relay.registrationFault = relayError(403, "enrollment_required")
            try {
                when (mode) {
                    "forced" -> engine.ensureRegistered(force = true)
                    "pairing" -> engine.ensureRegistered(pairingId = "a".repeat(32))
                    else -> engine.receiveOnce(0) { error("empty mailbox") }
                }
                error("expected enrollment failure")
            } catch (failure: RelayException) { assertEquals("enrollment_required", failure.code) }
            assertEquals(before, device.store.load())
        }
    }

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
        // A chunked file is read from the spool, so only inside the handler.
        val contents = mutableListOf<ByteArray?>()
        repeat(4) { iphone.receiveOnce { items += it; contents += it.bytes(); ReceiptStatus.DELIVERED } }
        assertEquals(sent.itemId, items.single().id)
        assertTrue(file.contentEquals(contents.single()!!))
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

    @Test fun `preview decrypts a waiting item without consuming it`() = runBlocking {
        linked()
        val sent = android.send(OutgoingContent.Text("peek")).single()
        assertEquals("peek", iphone.preview(sent.itemId!!)!!.text)
        assertEquals(1, relay.mailbox.size)
        assertEquals(1, iphone.receiveOnce { ReceiptStatus.COPIED })
    }

    private fun link(inviter: DeviceLinkClient, joiner: DeviceLinkClient) = runBlocking {
        inviter.configure("https://relay.example")
        val session = inviter.invite()
        val waiting = async { session.awaitPeer() }
        joiner.join(session.uri)
        waiting.await()
    }

    /** A device whose process can be "restarted": same keys, same link file, fresh engine. */
    private inner class Restartable(val name: String = "Desk") {
        val file = java.io.File.createTempFile("link", ".json").apply { delete(); deleteOnExit() }
        private val identity = SoftwareIdentity()
        private val keys = EncryptionKeyPair.generate()
        val store get() = FileLinkStore(file)
        fun start(config: LinkConfig = LinkConfig(name, "desktop")) = DeviceLinkClient(identity, keys, store, config, relay) { now }
        fun spooled() = java.io.File(file.parentFile, file.name + ".chunks").listFiles().orEmpty().toList()
    }

    private class Crash : kotlinx.coroutines.CancellationException("process died")

    private fun bigFile() = ByteArray(Limits.MAX_CONTENT_BYTES + 3 * 1024 * 1024) { (it * 31).toByte() }

    @Test fun `a restart mid-transfer resumes a chunked file and receipts it`() = runBlocking {
        val desk = Restartable()
        val before = desk.start()
        link(android, before)
        val file = bigFile()
        val sent = android.send(OutgoingContent.File("big.bin", "application/octet-stream", file)).single()
        repeat(2) { assertEquals(0, before.receiveOnce { error("incomplete file must not be applied") }) }

        val after = desk.start()
        val items = mutableListOf<IncomingItem>()
        // A chunked file is read from the spool, so only inside the handler.
        val contents = mutableListOf<ByteArray?>()
        repeat(2) { after.receiveOnce { items += it; contents += it.bytes(); ReceiptStatus.DELIVERED } }
        assertEquals(sent.itemId, items.single().id)
        assertTrue(file.contentEquals(contents.single()!!))
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { android.events.first { it is LinkEvent.Receipt } }
        android.receiveOnce { error("no content") }
        assertEquals(sent.itemId, (withTimeout(1_000) { receipt.await() } as LinkEvent.Receipt).itemId)
        assertTrue("spool is cleaned after apply", desk.spooled().isEmpty())
    }

    @Test fun `a file whose last chunk arrived just before a crash is applied on restart`() = runBlocking {
        val desk = Restartable()
        val before = desk.start()
        link(android, before)
        val file = bigFile()
        android.send(OutgoingContent.File("big.bin", "application/octet-stream", file))
        repeat(3) { before.receiveOnce { error("incomplete") } }
        runCatching { before.receiveOnce { throw Crash() } }
        assertTrue(relay.mailbox.none { it.recipientId == before.deviceId })

        val items = mutableListOf<IncomingItem>()
        // A chunked file is read from the spool, so only inside the handler.
        val contents = mutableListOf<ByteArray?>()
        desk.start().receiveOnce(waitSeconds = 0) { items += it; contents += it.bytes(); ReceiptStatus.DELIVERED }
        assertTrue(file.contentEquals(contents.single()!!))
        assertEquals(0, desk.start().receiveOnce(waitSeconds = 0) { error("applied twice") })
    }

    @Test fun `a crash while applying an item does not lose it`() = runBlocking {
        val desk = Restartable()
        link(android, desk.start())
        android.send(OutgoingContent.Text("survives"))
        assertTrue(runCatching { desk.start().receiveOnce { throw Crash() } }.exceptionOrNull() is Crash)
        val received = mutableListOf<String?>()
        desk.start().receiveOnce { received += it.text; ReceiptStatus.COPIED }
        assertEquals(listOf<String?>("survives"), received)
    }

    @Test fun `a failed acknowledgement still sends the receipt and never applies twice`() = runBlocking {
        linked()
        val sent = android.send(OutgoingContent.Text("ack me")).single()
        relay.failAcks = 1
        var calls = 0
        assertEquals(1, iphone.receiveOnce { calls++; ReceiptStatus.COPIED })
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { android.events.first { it is LinkEvent.Receipt } }
        android.receiveOnce { error("no content") }
        assertEquals(sent.itemId, (withTimeout(1_000) { receipt.await() } as LinkEvent.Receipt).itemId)
        assertEquals(0, iphone.receiveOnce { calls++; ReceiptStatus.COPIED })
        assertEquals(1, calls)
        assertTrue(relay.mailbox.none { it.recipientId == iphone.deviceId })
    }

    @Test fun `undecryptable envelopes are dropped and counted`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("tampered"))
        relay.mailbox[0] = relay.mailbox[0].copy(ciphertext = Encoding.b64(ByteArray(64)))
        assertEquals(0, iphone.receiveOnce { error("must not apply") })
        assertEquals(1, iphone.diagnostics.value.undecryptable)
        assertTrue(relay.mailbox.isEmpty())
    }

    /** Runs the receive loop in virtual time; returns the first item text, or null if none arrived within [withinMillis]. */
    private fun receiverAfterPollError(code: String, withinMillis: Long): String? {
        linked()
        relay.longPoll = true
        runBlocking { android.send(OutgoingContent.Text("after $code")) }
        relay.pollErrors += 429 to code
        var text: String? = null
        runTest {
            val received = CompletableDeferred<String?>()
            val loop = launch { iphone.runReceiver { received.complete(it.text); ReceiptStatus.COPIED } }
            text = withTimeoutOrNull(withinMillis) { received.await() }
            loop.cancel()
        }
        return text
    }

    @Test fun `a poll collision after a reconnect retries within seconds`() {
        assertEquals("after poll_already_active", receiverAfterPollError("poll_already_active", withinMillis = 10_000))
    }

    @Test fun `a relay rate limit keeps the long backoff`() {
        assertEquals(null, receiverAfterPollError("rate_limit", withinMillis = 30_000))
    }

    @Test fun `drain applies every item even when a receipt is served first`() = runBlocking {
        linked()
        iphone.send(OutgoingContent.Text("two"))
        iphone.send(OutgoingContent.Text("three"))
        android.send(OutgoingContent.Text("x"))
        iphone.receiveOnce { ReceiptStatus.COPIED } // its receipt is iPhone's newest envelope for Android
        val texts = mutableListOf<String?>()
        val result = android.drain { texts += it.text; ReceiptStatus.DELIVERED }
        assertEquals(DrainResult(applied = 2, complete = true), result)
        assertEquals(listOf<String?>("three", "two"), texts)
        assertTrue(relay.mailbox.none { it.recipientId == android.deviceId })
    }

    @Test fun `drain stops on a failed acknowledgement instead of looping`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("once"))
        relay.failAcks = 5
        var calls = 0
        assertEquals(DrainResult(applied = 1, complete = true), iphone.drain { calls++; ReceiptStatus.COPIED })
        assertEquals(1, calls)
        assertTrue(relay.polls.last().contains("exclude="))
    }

    @Test fun `drain reports a poll collision instead of failing`() = runBlocking {
        linked()
        android.send(OutgoingContent.Text("waiting"))
        relay.pollErrors += 429 to "poll_already_active"
        assertEquals(DrainResult(applied = 0, complete = false, collided = true), iphone.drain { ReceiptStatus.COPIED })
        assertEquals(1, iphone.drain { ReceiptStatus.COPIED }.applied)
    }

    // Virtual time: the confirm is resent with pauses before the inviter gives up.
    @Test fun `a failed confirm leaves neither side linked`() = runTest {
        android.configure("https://relay.example")
        val session = android.invite()
        relay.failConfirm = 503 to "unavailable"
        val events = mutableListOf<LinkEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { android.events.collect { events += it } }
        val joiner = launch { runCatching { iphone.join(session.uri) } }
        val failure = runCatching { session.awaitPeer() }.exceptionOrNull()
        joiner.cancel(); collector.cancel()
        assertTrue(failure is PairingException)
        assertTrue(android.peers.value.isEmpty())
        assertTrue(iphone.peers.value.isEmpty())
        assertFalse((android.deviceId to iphone.deviceId) in relay.allow)
        assertTrue(events.none { it is LinkEvent.PeerLinked })
    }

    @Test fun `a confirm whose response was lost still links both sides`() {
        relay.failConfirm = 503 to "unavailable"
        relay.confirmCommitsBeforeFailing = true
        linked()
        assertEquals(listOf(iphone.deviceId), android.peers.value.map { it.id })
        assertEquals(listOf(android.deviceId), iphone.peers.value.map { it.id })
    }

    /** [linked] in virtual time, so confirm retry pauses are skipped. */
    private fun linkedInVirtualTime() = runTest {
        android.configure("https://relay.example")
        val session = android.invite()
        val inviter = async { session.awaitPeer() }
        assertEquals(android.deviceId, iphone.join(session.uri).id)
        assertEquals(iphone.deviceId, inviter.await().id)
    }

    @Test fun `a lost confirm response ends linked on both sides even when the re-read fails too`() {
        relay.confirmResponseLost = true
        relay.failPairingReReads = 1
        linkedInVirtualTime()
        assertEquals(listOf(iphone.deviceId), android.peers.value.map { it.id })
        assertEquals(listOf(android.deviceId), iphone.peers.value.map { it.id })
        assertTrue(relay.allow.containsAll(listOf(android.deviceId to iphone.deviceId, iphone.deviceId to android.deviceId)))
        // The resend carried the very same sealed confirm, which the relay accepts as idempotent.
        assertEquals(2, relay.confirms.size)
        assertEquals(1, relay.confirms.toSet().size)
    }

    @Test fun `a network error on the joiner's allow after confirm is retried`() {
        relay.failNextAllowBy = iphone.deviceId
        linked()
        assertEquals(listOf(android.deviceId), iphone.peers.value.map { it.id })
        assertTrue((iphone.deviceId to android.deviceId) in relay.allow)
    }

    @Test fun `a network error on the inviter's allow is retried and both sides end linked`() {
        relay.failNextAllowBy = android.deviceId
        linkedInVirtualTime()
        assertEquals(listOf(iphone.deviceId), android.peers.value.map { it.id })
        assertEquals(listOf(android.deviceId), iphone.peers.value.map { it.id })
        assertTrue(relay.allow.containsAll(listOf(android.deviceId to iphone.deviceId, iphone.deviceId to android.deviceId)))
        assertEquals(2, relay.allowAttempts[android.deviceId])
    }

    // Virtual time, so a wrongly retried refusal would show up as extra attempts rather than a slow test.
    @Test fun `a relay refusal of the inviter's allow fails at once`() = runTest {
        relay.refuseAllowBy = android.deviceId to (429 to "peer_limit")
        android.configure("https://relay.example")
        val session = android.invite()
        val joiner = launch { runCatching { iphone.join(session.uri) } }
        val failure = runCatching { session.awaitPeer() }.exceptionOrNull()
        joiner.cancel()
        assertEquals("peer_limit", (failure as RelayException).code)
        assertEquals(1, relay.allowAttempts[android.deviceId])
        assertTrue(android.peers.value.isEmpty())
        assertTrue(iphone.peers.value.isEmpty())
    }

    private fun relayError(status: Int, code: String): () -> HttpResponse = { HttpResponse(status, """{"error":"$code"}""".toByteArray()) }

    /** Sends a 4-chunk file after linking, in virtual time (retry pauses are skipped). */
    private fun sendBigFileWith(vararg faults: Pair<Int, () -> HttpResponse>): SendOutcome {
        linked()
        val before = relay.uploads
        faults.forEach { (attempt, fault) -> relay.uploadFaults[before + attempt] = fault }
        var outcome: SendOutcome? = null
        runTest { outcome = android.send(OutgoingContent.File("big.bin", "application/octet-stream", bigFile())).single() }
        return outcome!!
    }

    @Test fun `a transient relay error on one chunk resends only that chunk`() {
        val outcome = sendBigFileWith(3 to relayError(503, "unavailable"))
        assertTrue(outcome.accepted)
        assertEquals(4, relay.mailbox.size)
        assertEquals("4 chunks + 1 resend of chunk 3", 5, relay.uploads)
    }

    @Test fun `a dropped connection on a chunk is retried and the item completes`() {
        val outcome = sendBigFileWith(4 to { throw java.io.IOException("connection reset") }, 5 to relayError(408, "timeout"))
        assertTrue(outcome.accepted)
        assertEquals(4, relay.mailbox.size)
    }

    @Test fun `a refused chunk is not retried and reports the relay code`() {
        val outcome = sendBigFileWith(2 to relayError(413, "payload_too_large"))
        assertFalse(outcome.accepted)
        assertEquals("payload_too_large", outcome.error)
        assertEquals(1, relay.mailbox.size)
        assertEquals(2, relay.uploads)
    }

    @Test fun `a persistent transient failure gives up after four attempts`() {
        val outcome = sendBigFileWith(*Array(10) { (it + 1) to relayError(502, "bad_gateway") })
        assertEquals("bad_gateway", outcome.error)
        assertEquals(4, relay.uploads)
        assertTrue(relay.mailbox.isEmpty())
    }

    @Test fun `transport failures report stable network and timeout codes`() {
        val network = sendBigFileWith(*Array(4) { (it + 1) to { throw java.net.ConnectException("refused") } })
        assertEquals(SendOutcome.NETWORK, network.error)
        relay.uploadFaults.clear()
        val start = relay.uploads
        repeat(4) { relay.uploadFaults[start + it + 1] = { throw java.net.SocketTimeoutException("read timed out") } }
        var timeout: SendOutcome? = null
        runTest { timeout = android.send(OutgoingContent.Text("x")).single() }
        assertEquals(SendOutcome.TIMEOUT, timeout!!.error)
        assertFalse(timeout!!.accepted)
    }

    @Test fun `a chunked file that cannot be assembled gets a failed receipt`() = runBlocking {
        val identity = SoftwareIdentity(); val keys = EncryptionKeyPair.generate()
        val sender = DeviceLinkClient(identity, keys, MemoryLinkStore(), LinkConfig("Pixel", "android"), relay) { now }
        link(sender, iphone)
        // Every part claims one byte more than the parts hold: consistent, but the total never matches.
        val bytes = ByteArray(Limits.CHUNK_BYTES + 10) { it.toByte() }
        val parts = Payload.chunks(PayloadKind.FILE, "big.bin", "application/octet-stream", bytes, now).map { it.copy(size = bytes.size + 1L) }
        val crypto = EnvelopeCrypto(identity, keys)
        parts.forEachIndexed { index, part -> relay.mailbox += crypto.seal(part, iphone.localBundle, now, 100L + index) }
        repeat(2) { assertEquals(0, iphone.receiveOnce { error("a broken file must not be applied") }) }
        val receipt = async(start = CoroutineStart.UNDISPATCHED) { sender.events.first { it is LinkEvent.Receipt } }
        sender.receiveOnce { error("no content") }
        assertEquals(LinkEvent.Receipt(parts.first().group!!, iphone.deviceId, ReceiptStatus.FAILED), withTimeout(1_000) { receipt.await() })
    }

    @Test fun `unregister tells peers, deletes the device on the relay and forgets every link`() = runBlocking {
        linked()
        val unlinked = async(start = CoroutineStart.UNDISPATCHED) { android.events.first { it is LinkEvent.PeerUnlinked } }
        assertTrue(android.unregister())
        assertEquals(listOf(iphone.deviceId), relay.storedFor)
        assertFalse(android.deviceId in relay.devices)
        assertTrue(relay.allow.none { android.deviceId in it.toList() })
        assertTrue(android.peers.value.isEmpty())
        assertEquals(LinkEvent.PeerUnlinked(iphone.deviceId, byRemote = false), withTimeout(1_000) { unlinked.await() })
        // The peer was not polling; the unlink notice outlives the unregister and it drops the link later.
        iphone.receiveOnce { error("no content expected") }
        assertTrue(iphone.peers.value.isEmpty())
        assertFalse(android.diagnostics.value.registered)
        assertEquals("https://relay.example", android.relayUrl)
    }

    @Test fun `unregister with the relay unreachable still clears local links and does not throw`() = runBlocking {
        linked()
        relay.down = true
        assertFalse(android.unregister())
        assertTrue(android.peers.value.isEmpty())
        assertTrue(android.deviceId in relay.devices)
    }

    @Test fun `unregister cancelled by a short timeout still clears local links`() = runBlocking {
        linked()
        val slow = DeviceLinkClient(SoftwareIdentity(), EncryptionKeyPair.generate(), MemoryLinkStore(), LinkConfig("Slow", "android"),
            HttpTransport { method, url, headers, body, timeout ->
                if ("/v1/devices/self" in url) kotlinx.coroutines.awaitCancellation()
                relay.execute(method, url, headers, body, timeout)
            }) { now }
        link(slow, client("Other", "ios"))
        assertEquals(null, withTimeoutOrNull(500) { slow.unregister() })
        assertTrue(slow.peers.value.isEmpty())
    }

    @Test fun `unlink all forgets every peer`() = runBlocking {
        linked()
        android.unlinkAll()
        assertTrue(android.peers.value.isEmpty())
    }

    /** Generated content read on demand; records how far it was read and how often it was opened. */
    private class CountingSource(val size: Long) {
        var opened = 0
        var position = 0L
        fun byteAt(index: Long) = (index * 31 % 251).toByte()
        fun content() = OutgoingContent.Source(PayloadKind.FILE, "big.zip", "application/zip", size) {
            opened++
            object : java.io.InputStream() {
                override fun read(): Int = if (position >= size) -1 else byteAt(position++).toInt() and 0xff
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= size) return -1
                    val count = minOf(length.toLong(), size - position).toInt()
                    repeat(count) { buffer[offset + it] = byteAt(position++) }
                    return count
                }
            }
        }
        fun sha256(): ByteArray = java.security.MessageDigest.getInstance("SHA-256").also { digest ->
            val block = ByteArray(1 shl 16)
            var index = 0L
            while (index < size) {
                val count = minOf(block.size.toLong(), size - index).toInt()
                repeat(count) { block[it] = byteAt(index + it) }
                digest.update(block, 0, count); index += count
            }
        }.digest()
    }

    private class Device(val identity: DeviceIdentity = SoftwareIdentity(), val keys: EncryptionKeyPair = EncryptionKeyPair.generate()) {
        val crypto = EnvelopeCrypto(identity, keys)
    }

    @Test fun `an upload answered after 30 s but within the relay's allowance succeeds`() {
        // The real stack starts the response timer once the body is in the send buffer; here the relay stores
        // the chunk, then answers late. A 30 s timer reported such uploads as failed although they arrived.
        val answerMillis = 90_000L
        val timeouts = mutableListOf<Int>()
        val slow = HttpTransport { method, url, headers, body, timeout ->
            val response = relay.execute(method, url, headers, body, timeout)
            if (method == "POST" && url.endsWith("/v1/messages")) {
                timeouts += timeout
                kotlinx.coroutines.delay(minOf(answerMillis, timeout.toLong()))
                if (timeout < answerMillis) throw java.net.SocketTimeoutException("read timed out")
            }
            response
        }
        val sender = DeviceLinkClient(SoftwareIdentity(), EncryptionKeyPair.generate(), MemoryLinkStore(), LinkConfig("Pixel", "android"), slow) { now }
        link(sender, iphone)
        var outcome: SendOutcome? = null
        runTest(timeout = kotlin.time.Duration.parse("2m")) { outcome = sender.send(OutgoingContent.File("big.bin", "application/octet-stream", bigFile())).single() }
        assertTrue(outcome!!.accepted)
        assertEquals("one attempt per chunk", 4, relay.uploads)
        assertTrue(timeouts.all { it > answerMillis })
    }

    @Test fun `a 30 MB source is read one chunk at a time and arrives intact`() {
        val sending = Device(); val receiving = Device()
        val source = CountingSource(30L * 1024 * 1024)
        val readAtUpload = mutableListOf<Long>()
        val counting = HttpTransport { method, url, headers, body, timeout ->
            if (method == "POST" && url.endsWith("/v1/messages")) readAtUpload += source.position
            relay.execute(method, url, headers, body, timeout)
        }
        val sender = DeviceLinkClient(sending.identity, sending.keys, MemoryLinkStore(), LinkConfig("Pixel", "android"), counting) { now }
        val receiver = DeviceLinkClient(receiving.identity, receiving.keys, MemoryLinkStore(), LinkConfig("Desk", "desktop"), relay) { now }
        link(sender, receiver)
        relay.uploadFaults[relay.uploads + 3] = relayError(503, "unavailable")
        var outcome: SendOutcome? = null
        // Real time: the one retry pause is 1 s, and 30 MB of sealing should not race a virtual-time timeout.
        runBlocking { outcome = sender.send(source.content()).single() }
        assertTrue(outcome!!.accepted)
        assertEquals(1, source.opened)
        val parts = Payload.partsFor(source.size)
        // Upload k (and the resend of chunk 2) happens with exactly chunks 0..k read: nothing is read ahead.
        val chunkEnd = { k: Int -> minOf(source.size, (k + 1L) * Limits.CHUNK_BYTES) }
        assertEquals(listOf(0, 1, 2, 2).map(chunkEnd) + (3 until parts).map(chunkEnd), readAtUpload)
        val opened = relay.mailbox.map { receiving.crypto.open(it, sender.localBundle, now) }
        assertEquals((0 until parts).toList(), opened.map { it.part })
        assertEquals(setOf(outcome!!.itemId), opened.map { it.group }.toSet())
        assertTrue(opened.all { it.parts == parts && it.size == source.size && it.kind == PayloadKind.FILE && it.name == "big.zip" })

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var applied = 0
        runBlocking {
            repeat(parts) {
                applied += receiver.receiveOnce(0) { item ->
                    assertEquals(outcome!!.itemId, item.id)
                    item.writeTo(java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))
                    ReceiptStatus.DELIVERED
                }
            }
        }
        assertEquals(1, applied)
        assertTrue(source.sha256().contentEquals(digest.digest()))
    }

    @Test fun `a source shorter than its size fails the send without a network error`() {
        linked()
        val short = OutgoingContent.Source(PayloadKind.FILE, "cut.bin", "application/octet-stream", Limits.MAX_CONTENT_BYTES + 1L) {
            java.io.ByteArrayInputStream(ByteArray(Limits.CHUNK_BYTES + 5))
        }
        var outcome: SendOutcome? = null
        runTest { outcome = android.send(short).single() }
        assertEquals(SendOutcome.SEND_FAILED, outcome!!.error)
        assertEquals("only the complete first chunk went out", 1, relay.mailbox.size)
    }
}
