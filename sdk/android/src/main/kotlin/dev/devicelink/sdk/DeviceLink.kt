package dev.devicelink.sdk

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import dev.devicelink.sdk.core.DeviceLinkClient
import dev.devicelink.sdk.core.FileLinkStore
import dev.devicelink.sdk.core.IncomingItem
import dev.devicelink.sdk.core.LinkConfig
import dev.devicelink.sdk.core.LinkEvent
import dev.devicelink.sdk.core.LinkedPeer
import dev.devicelink.sdk.core.Limits
import dev.devicelink.sdk.core.OutgoingContent
import dev.devicelink.sdk.core.PayloadKind
import dev.devicelink.sdk.core.ReceiptStatus
import dev.devicelink.sdk.core.ReceiverStatus
import dev.devicelink.sdk.core.SendOutcome
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SDK options. By default the relay comes from the host app's manifest meta-data
 * (`dev.devicelink.RELAY_URL`, `dev.devicelink.ENROLLMENT_TOKEN`), filled from private build config.
 */
data class DeviceLinkOptions(
    val relayUrl: String = "",
    val enrollmentToken: String = "",
    val deviceName: String = "",
    val allowInsecureRelay: Boolean = false,
    /** Put received text/images on the clipboard automatically. */
    val autoCopy: Boolean = true,
    /** Show a short "Copied from …" notification for each received clip. */
    val notifyCopies: Boolean = true,
    val appVersion: String = "",
) {
    companion object {
        fun fromManifest(context: Context): DeviceLinkOptions {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val meta = info.metaData
            val debuggable = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            return DeviceLinkOptions(
                relayUrl = meta?.getString("dev.devicelink.RELAY_URL").orEmpty(),
                enrollmentToken = meta?.getString("dev.devicelink.ENROLLMENT_TOKEN").orEmpty(),
                allowInsecureRelay = debuggable,
                appVersion = version,
            )
        }
    }
}

/** Health of this device's stored keys; see [DeviceLinkInstance.keyState]. */
enum class KeyState { OK, LOST }

/** Pairing refused because [DeviceLinkInstance.keyState] is [KeyState.LOST]. The message is user-facing. */
class KeysLostException(message: String) : IllegalStateException(message)

/** Entry point: `DeviceLink.init(context)` once (for example in Application.onCreate), then `DeviceLink.get(context)`. */
object DeviceLink {
    @Volatile private var instance: DeviceLinkInstance? = null

    @JvmStatic @JvmOverloads
    fun init(context: Context, options: DeviceLinkOptions = DeviceLinkOptions.fromManifest(context)): DeviceLinkInstance =
        instance ?: synchronized(this) { instance ?: DeviceLinkInstance(context.applicationContext, options).also { instance = it } }

    @JvmStatic
    fun get(context: Context): DeviceLinkInstance = instance ?: init(context)

    /** After [DeviceLinkInstance.resetDevice] the next [get] builds a fresh identity. */
    internal fun forget(old: DeviceLinkInstance) = synchronized(this) { if (instance === old) instance = null }
}

class DeviceLinkInstance internal constructor(private val context: Context, val options: DeviceLinkOptions) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("devicelink_sdk", Context.MODE_PRIVATE)
    internal val clipboard = ClipboardBridge(context)
    private val clipHistory = ClipHistory(context)

    val deviceName: String = options.deviceName.ifBlank {
        (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL).trim().take(Limits.MAX_NAME_CHARS)
            .ifBlank { "Android" }
    }

    private val store = FileLinkStore(File(context.noBackupFilesDir, "devicelink/links.json"))
    private val encryptionKey = WrappedEncryptionKey.load(context, hasLinks = store.load().peers.isNotEmpty())

    /**
     * [KeyState.LOST] when this device's encryption key could not be read back while devices are linked.
     * Nothing sent to this device can be decrypted then, so pairing and receiving are refused until
     * [resetDevice]. Show [R.string.dl_keys_lost] with a reset action.
     */
    val keyState: KeyState = if (encryptionKey.lost) KeyState.LOST else KeyState.OK

    /** Platform-neutral engine; identity and encryption keys are created on first use. */
    val client = DeviceLinkClient(
        KeystoreIdentity(),
        encryptionKey.pair,
        store,
        LinkConfig(deviceName, "android", model = Build.MODEL, appVersion = options.appVersion,
            allowInsecureRelay = options.allowInsecureRelay, defaultRelayUrl = options.relayUrl,
            defaultEnrollmentToken = options.enrollmentToken),
    )

    val peers: StateFlow<List<LinkedPeer>> get() = client.peers
    val status: StateFlow<ReceiverStatus> get() = client.status
    val events: SharedFlow<LinkEvent> get() = client.events
    val history: StateFlow<List<ClipRecord>> get() = clipHistory.records

    init {
        Notifications.createChannels(context)
        scope.launch { ReceivedFiles.prune(context) }
        scope.launch {
            client.events.collect { event ->
                when (event) {
                    is LinkEvent.Receipt -> clipHistory.update(event.itemId, when (event.status) {
                        ReceiptStatus.COPIED -> ClipState.COPIED
                        ReceiptStatus.DELIVERED -> ClipState.DELIVERED
                        ReceiptStatus.FAILED -> ClipState.FAILED
                    })
                    is LinkEvent.PeerLinked -> if (canReceive) ReceiverService.start(context)
                    is LinkEvent.PeerUnlinked -> if (client.peers.value.isEmpty()) ReceiverService.stop(context)
                }
            }
        }
    }

    // ----- pairing -------------------------------------------------------------------------------

    /** Shows-a-code side. Works on a brand-new install when a relay is built in. */
    suspend fun invite(): DeviceLinkClient.PairingSession { requireKeys(); return client.invite() }

    /** Scans-a-code side. Accepts the https QR link or the devicelink:// form. */
    suspend fun join(link: String): LinkedPeer { requireKeys(); return client.join(link).also { receiverEnabled = true } }

    /** A new peer would pin the throwaway key, which is gone after the next start. */
    private fun requireKeys() {
        if (keyState == KeyState.LOST) throw KeysLostException(context.getString(R.string.dl_keys_lost))
    }

    suspend fun unlink(peerId: String) = client.unlink(peerId)

    /** Optional admin setup link (relay + enrollment token). Not needed when a relay is built in. */
    suspend fun applySetupLink(link: String): Boolean = client.applySetupLink(link)

    // ----- sending -------------------------------------------------------------------------------

    /**
     * Devices that sends go to; empty means every linked device. Persisted, so the choice survives restarts.
     */
    var sendTargets: Set<String>
        get() = prefs.getStringSet("targets", emptySet()).orEmpty().filter { id -> client.peers.value.any { it.id == id } }.toSet()
        set(value) { prefs.edit().putStringSet("targets", value).apply() }

    private fun targets(): Set<String>? = sendTargets.takeIf { it.isNotEmpty() }

    /** [html] is an optional rich-text alternative; [sensitive] hides previews on the other device. */
    @JvmOverloads
    suspend fun sendText(text: String, html: String? = null, sensitive: Boolean = false): List<SendOutcome> = withContext(Dispatchers.Default) {
        if (text.toByteArray(Charsets.UTF_8).size > Limits.MAX_TEXT_BYTES) throw ContentTooLargeException(Limits.MAX_TEXT_BYTES)
        record(client.send(OutgoingContent.Text(text, html, sensitive), targets()), "text", text = text, sensitive = sensitive)
    }

    /** Sends a content/file URI: images go to the other clipboard, other files arrive as a share prompt. */
    suspend fun sendUri(uri: Uri, mimeHint: String? = null): List<SendOutcome> {
        val content = withContext(Dispatchers.IO) { describeUri(uri, mimeHint) }
        // Base64, validation, HPKE sealing of up to 10 MiB and the Keystore signature are CPU work:
        // keep them off the caller's (often the main) thread. The engine hops to IO for reads and HTTP itself.
        return withContext(Dispatchers.Default) {
            record(client.send(content, targets()), if (content.kind == PayloadKind.IMAGE) "image" else "file",
                name = content.name, mime = content.mime, uri = uri.toString())
        }
    }

    /** Sends whatever a ClipData holds: first text item, otherwise its first URI. */
    suspend fun sendClip(clip: ClipData): List<SendOutcome> {
        if (clip.itemCount == 0) return emptyList()
        val item = clip.getItemAt(0)
        item.uri?.let { return sendUri(it, clip.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)) }
        val text = item.coerceToText(context)?.toString().orEmpty()
        val sensitive = android.os.Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        return if (text.isBlank()) emptyList() else sendText(text, item.htmlText?.takeIf { it.isNotBlank() }, sensitive)
    }

    /**
     * User-facing text for the result of a `send*` call wrapped in `runCatching`: "Sent to …" naming
     * only the devices that accepted it when every target did, otherwise the reason the user can act on.
     * `send*` reports relay failures in [SendOutcome] instead of throwing, so never infer success from
     * the absence of an exception.
     */
    fun sendResultMessage(result: Result<List<SendOutcome>>): String {
        val peers = client.peers.value
        return when (val problem = SendProblem.of(result, peers.isNotEmpty())) {
            // Only the devices this went to: sendTargets may limit it to some of them.
            null -> context.getString(R.string.dl_send_ok, result.getOrThrow()
                .mapNotNull { outcome -> peers.firstOrNull { it.id == outcome.peerId }?.name }.joinToString())
            SendProblem.TOO_LARGE -> context.getString(problem.message, Limits.MAX_FILE_BYTES / (1024 * 1024), Limits.MAX_TEXT_BYTES / 1024)
            else -> context.getString(problem.message)
        }
    }

    private fun record(outcomes: List<SendOutcome>, kind: String, text: String? = null, name: String? = null,
                       mime: String? = null, uri: String? = null, sensitive: Boolean = false): List<SendOutcome> {
        val names = client.peers.value.associate { it.id to it.name }
        outcomes.forEach { outcome ->
            clipHistory.add(ClipRecord(outcome.itemId ?: java.util.UUID.randomUUID().toString(), ClipDirection.SENT,
                names[outcome.peerId] ?: "", kind, text = text, uri = uri, mime = mime, name = name, at = System.currentTimeMillis(),
                state = if (outcome.accepted) ClipState.PENDING else ClipState.FAILED, sensitive = sensitive))
        }
        return outcomes
    }

    /**
     * Name, type and exact size of [uri], checked against the limit before anything is sent. The engine then
     * reads the stream one chunk at a time, so a 40 MB file never sits in memory whole.
     */
    private fun describeUri(uri: Uri, mimeHint: String?): OutgoingContent.Source {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size: Long? = null
        if (uri.scheme == "content") resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1).takeIf { length -> length >= 0 }
            }
        }
        name = name.replace('/', '_').replace('\\', '_').filterNot { it.isISOControl() }.take(Limits.MAX_FILE_NAME_CHARS)
            .ifBlank { "file" }.let { if (it == "." || it == "..") "file" else it }
        val mime = resolver.getType(uri) ?: mimeHint ?: Mime.forName(name)
        if ('.' !in name && mime != "application/octet-stream") name = "$name.${Mime.extension(mime)}"
        val open = { resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("No content") }
        // No reported size: count it once without keeping the bytes, stopping at the limit.
        val length = size ?: open().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total <= Limits.MAX_FILE_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
        if (length > Limits.MAX_FILE_BYTES) throw ContentTooLargeException(Limits.MAX_FILE_BYTES)
        return OutgoingContent.Source(if (mime in Limits.IMAGE_MIMES) PayloadKind.IMAGE else PayloadKind.FILE, name, mime, length, open)
    }

    // ----- receiving -----------------------------------------------------------------------------

    /** Persisted user choice; the foreground receiver runs while this is on and a device is linked. */
    var receiverEnabled: Boolean
        get() = prefs.getBoolean("receiver", true)
        set(value) {
            prefs.edit().putBoolean("receiver", value).apply()
            if (canReceive) ReceiverService.start(context) else if (!value) ReceiverService.stop(context)
        }

    /**
     * The receiver may run: switched on, a device linked, and keys readable. With lost keys every item
     * would fail to decrypt, so nothing is fetched and the items expire on the relay instead.
     */
    internal val canReceive: Boolean get() = receiverEnabled && client.peers.value.isNotEmpty() && keyState == KeyState.OK

    fun startReceiver() { receiverEnabled = true }
    fun stopReceiver() { receiverEnabled = false }

    /** Applies one received item: clipboard first, share prompt when the clipboard cannot hold it. */
    internal fun handle(item: IncomingItem): ReceiptStatus {
        val now = System.currentTimeMillis()
        val base = ClipRecord(item.id, ClipDirection.RECEIVED, item.peer.name, item.kind.name.lowercase(), at = now, state = ClipState.PENDING)
        return when (item.kind) {
            PayloadKind.TEXT -> {
                val text = requireNotNull(item.text)
                val copied = options.autoCopy && !item.stale && clipboard.writeText(text, item.html, item.sensitive)
                finish(base.copy(text = text, html = item.html, sensitive = item.sensitive), copied)
            }
            PayloadKind.IMAGE, PayloadKind.FILE -> {
                val name = item.fileName ?: "file.${Mime.extension(item.mime)}"
                val uri = ReceivedFiles.store(context, item.id, name, item.mime, item::writeTo)
                val record = base.copy(uri = uri.toString(), mime = item.mime, name = name)
                val copied = item.kind == PayloadKind.IMAGE && options.autoCopy && !item.stale && clipboard.writeUri(uri, item.mime)
                finish(record, copied)
            }
            else -> ReceiptStatus.DELIVERED
        }
    }

    private fun finish(record: ClipRecord, copied: Boolean): ReceiptStatus {
        val final = record.copy(state = if (copied) ClipState.COPIED else ClipState.SHARE_NEEDED)
        clipHistory.add(final)
        if (copied) { if (options.notifyCopies) Notifications.copied(context, final) } else Notifications.shareNeeded(context, final)
        return if (copied) ReceiptStatus.COPIED else ReceiptStatus.DELIVERED
    }

    /** Re-copies a history entry (history screen, notification action). */
    fun copyToClipboard(record: ClipRecord): Boolean {
        val copied = when {
            record.text != null -> clipboard.writeText(record.text, record.html, record.sensitive)
            record.uri != null && record.mime != null ->
                Uri.parse(record.uri).let { Notifications.isReadable(context, it) && clipboard.writeUri(it, record.mime) }
            else -> false
        }
        if (copied && record.direction == ClipDirection.RECEIVED) clipHistory.update(record.id, ClipState.COPIED)
        return copied
    }

    fun shareIntent(record: ClipRecord) = Notifications.shareIntent(context, record)

    /**
     * Opens the system "save as" picker for a received image or file and copies it there, so it
     * outlives the 24-hour private retention. Null when the record carries no file. Start it with
     * `startActivity`; the SDK shows the result.
     */
    fun saveIntent(record: ClipRecord): android.content.Intent? =
        if (record.direction == ClipDirection.RECEIVED) SaveFileActivity.intent(context, record) else null
    fun forget(record: ClipRecord) = clipHistory.remove(record.id)
    fun clearHistory() { clipHistory.clear(); scope.launch(Dispatchers.IO) { ReceivedFiles.clear(context) } }

    /** True when the clip was written by DeviceLink itself (echo suppression for auto-share and keyboards). */
    fun isOwnClip(clip: ClipData): Boolean {
        if (clip.description.label?.toString() == ClipboardBridge.LABEL) return true
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false
        val signature = item.uri?.let { "uri:$it" } ?: item.text?.toString()?.let(ClipboardBridge::signature)
        return signature != null && signature == clipboard.lastWrittenSignature
    }

    /**
     * Shares every copy made inside this app automatically while it is in the foreground.
     * Android does not report clipboard changes to background apps, so this is the integration
     * point for host apps; the receiver app offers Share/selection/tile actions instead.
     */
    fun enableAutoShare(application: Application) = AutoShare.install(application, this)

    /** Content-free status for debug screens: relay, registration, last poll/receive/send, last error. */
    val diagnostics get() = client.diagnostics

    /** Plain-text diagnostics a user can copy into a bug report. Contains no clipboard content or names. */
    fun diagnosticsReport(): String {
        val d = client.diagnostics.value
        fun at(time: Long?) = time?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "never"
        return buildString {
            appendLine("DeviceLink ${options.appVersion} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Device ID: ${d.deviceId.take(16)}…")
            appendLine("Keys: ${if (keyState == KeyState.LOST) "LOST (encryption key unreadable; reset and link again)" else "ok"}")
            appendLine("Relay: ${d.relayUrl.ifEmpty { "not configured" }} · registered: ${d.registered}")
            appendLine("Linked devices: ${d.peers} · receiver on: $receiverEnabled · status: ${client.status.value}")
            appendLine("Last poll: ${at(d.lastPollAt)} · last receive: ${at(d.lastReceiveAt)} · last send: ${at(d.lastSendAt)}")
            appendLine("Received: ${d.received} · sent: ${d.sent} · undecryptable: ${d.undecryptable} (since app start)")
            append("Last error: ${d.lastError ?: "none"}${d.lastErrorAt?.let { " at " + at(it) } ?: ""}")
        }
    }

    /**
     * Tells every linked device, removes this device from the relay, deletes this device's keys and
     * history, and starts over with a fresh identity. Use when a phone is handed on or keys may be
     * compromised. Takes up to [RESET_UNREGISTER_TIMEOUT_MILLIS] plus local cleanup; call it off the
     * main thread's blocking path (from a coroutine) and show progress.
     *
     * The relay step is best effort and bounded, so reset also works offline; the local wipe always
     * runs, even when the caller is cancelled. Also works with [KeyState.LOST]: only the identity key
     * (Keystore) signs the relay request, and notices are sealed to the peers' keys.
     *
     * @return true when the relay confirmed this device is gone; false when it was reset locally only
     *   (offline, relay unreachable, timed out, or no relay configured).
     */
    suspend fun resetDevice(): Boolean {
        // Stop polling first: a poll after the delete would register this device again.
        stopReceiver()
        var removed = false
        try {
            removed = withTimeoutOrNull(RESET_UNREGISTER_TIMEOUT_MILLIS) { client.unregister() } == true
        } finally {
            withContext(NonCancellable) {
                clipHistory.clear()
                withContext(Dispatchers.IO) {
                    // Inline, not clearHistory(): that launches on [scope], which is cancelled just below.
                    ReceivedFiles.clear(context)
                    KeystoreIdentity.delete()
                    WrappedEncryptionKey.delete(context)
                    java.io.File(context.noBackupFilesDir, "devicelink/links.json").delete()
                    prefs.edit().clear().apply()
                }
                scope.cancel()
                DeviceLink.forget(this@DeviceLinkInstance)
            }
        }
        return removed
    }

    companion object {
        /** Upper bound for telling peers and the relay during [resetDevice]; the local wipe follows regardless. */
        const val RESET_UNREGISTER_TIMEOUT_MILLIS = 5_000L
    }
}
