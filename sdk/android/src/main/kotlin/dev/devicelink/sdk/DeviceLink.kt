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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Entry point: `DeviceLink.init(context)` once (for example in Application.onCreate), then `DeviceLink.get(context)`. */
object DeviceLink {
    @Volatile private var instance: DeviceLinkInstance? = null

    @JvmStatic @JvmOverloads
    fun init(context: Context, options: DeviceLinkOptions = DeviceLinkOptions.fromManifest(context)): DeviceLinkInstance =
        instance ?: synchronized(this) { instance ?: DeviceLinkInstance(context.applicationContext, options).also { instance = it } }

    @JvmStatic
    fun get(context: Context): DeviceLinkInstance = instance ?: init(context)
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

    /** Platform-neutral engine; identity and encryption keys are created on first use. */
    val client = DeviceLinkClient(
        KeystoreIdentity(),
        WrappedEncryptionKey.load(context),
        FileLinkStore(File(context.noBackupFilesDir, "devicelink/links.json")),
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
                    is LinkEvent.PeerLinked -> if (receiverEnabled) ReceiverService.start(context)
                    is LinkEvent.PeerUnlinked -> if (client.peers.value.isEmpty()) ReceiverService.stop(context)
                }
            }
        }
    }

    // ----- pairing -------------------------------------------------------------------------------

    /** Shows-a-code side. Works on a brand-new install when a relay is built in. */
    suspend fun invite(): DeviceLinkClient.PairingSession = client.invite()

    /** Scans-a-code side. Accepts the https QR link or the devicelink:// form. */
    suspend fun join(link: String): LinkedPeer = client.join(link).also { receiverEnabled = true }

    suspend fun unlink(peerId: String) = client.unlink(peerId)

    /** Optional admin setup link (relay + enrollment token). Not needed when a relay is built in. */
    suspend fun applySetupLink(link: String): Boolean = client.applySetupLink(link)

    // ----- sending -------------------------------------------------------------------------------

    suspend fun sendText(text: String): List<SendOutcome> = record(client.send(OutgoingContent.Text(text)), "text", text = text)

    /** Sends a content/file URI: images go to the other clipboard, other files arrive as a share prompt. */
    suspend fun sendUri(uri: Uri, mimeHint: String? = null): List<SendOutcome> {
        val (name, mime, bytes) = withContext(Dispatchers.IO) { readUri(uri, mimeHint) }
        val content = if (mime in Limits.IMAGE_MIMES) OutgoingContent.Image(name, mime, bytes) else OutgoingContent.File(name, mime, bytes)
        return record(client.send(content), if (content is OutgoingContent.Image) "image" else "file", name = name, mime = mime, uri = uri.toString())
    }

    /** Sends whatever a ClipData holds: first text item, otherwise its first URI. */
    suspend fun sendClip(clip: ClipData): List<SendOutcome> {
        if (clip.itemCount == 0) return emptyList()
        val item = clip.getItemAt(0)
        item.uri?.let { return sendUri(it, clip.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)) }
        val text = item.coerceToText(context)?.toString().orEmpty()
        return if (text.isBlank()) emptyList() else sendText(text)
    }

    private fun record(outcomes: List<SendOutcome>, kind: String, text: String? = null, name: String? = null,
                       mime: String? = null, uri: String? = null): List<SendOutcome> {
        val names = client.peers.value.associate { it.id to it.name }
        outcomes.forEach { outcome ->
            clipHistory.add(ClipRecord(outcome.itemId ?: java.util.UUID.randomUUID().toString(), ClipDirection.SENT,
                names[outcome.peerId] ?: "", kind, text = text, uri = uri, mime = mime, name = name, at = System.currentTimeMillis(),
                state = if (outcome.accepted) ClipState.PENDING else ClipState.FAILED))
        }
        return outcomes
    }

    private fun readUri(uri: Uri, mimeHint: String?): Triple<String, String, ByteArray> {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        if (uri.scheme == "content") resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
        }
        name = name.replace('/', '_').replace('\\', '_').filterNot { it.isISOControl() }.take(Limits.MAX_FILE_NAME_CHARS)
            .ifBlank { "file" }.let { if (it == "." || it == "..") "file" else it }
        val mime = resolver.getType(uri) ?: mimeHint ?: Mime.forName(name)
        if ('.' !in name && mime != "application/octet-stream") name = "$name.${Mime.extension(mime)}"
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= Limits.MAX_CONTENT_BYTES) { "File is larger than 10 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Triple(name, mime, bytes)
    }

    // ----- receiving -----------------------------------------------------------------------------

    /** Persisted user choice; the foreground receiver runs while this is on and a device is linked. */
    var receiverEnabled: Boolean
        get() = prefs.getBoolean("receiver", true)
        set(value) {
            prefs.edit().putBoolean("receiver", value).apply()
            if (value && client.peers.value.isNotEmpty()) ReceiverService.start(context) else if (!value) ReceiverService.stop(context)
        }

    fun startReceiver() { receiverEnabled = true }
    fun stopReceiver() { receiverEnabled = false }

    /** Applies one received item: clipboard first, share prompt when the clipboard cannot hold it. */
    internal fun handle(item: IncomingItem): ReceiptStatus {
        val now = System.currentTimeMillis()
        val base = ClipRecord(item.id, ClipDirection.RECEIVED, item.peer.name, item.kind.name.lowercase(), at = now, state = ClipState.PENDING)
        return when (item.kind) {
            PayloadKind.TEXT -> {
                val text = requireNotNull(item.text)
                val copied = options.autoCopy && !item.stale && clipboard.writeText(text)
                finish(base.copy(text = text), copied)
            }
            PayloadKind.IMAGE, PayloadKind.FILE -> {
                val name = item.fileName ?: "file.${Mime.extension(item.mime)}"
                val uri = ReceivedFiles.store(context, item.id, name, item.mime, requireNotNull(item.bytes()))
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
            record.text != null -> clipboard.writeText(record.text)
            record.uri != null && record.mime != null -> clipboard.writeUri(Uri.parse(record.uri), record.mime)
            else -> false
        }
        if (copied && record.direction == ClipDirection.RECEIVED) clipHistory.update(record.id, ClipState.COPIED)
        return copied
    }

    fun shareIntent(record: ClipRecord) = Notifications.shareIntent(context, record)
    fun forget(record: ClipRecord) = clipHistory.remove(record.id)
    fun clearHistory() { clipHistory.clear(); scope.launch(Dispatchers.IO) { ReceivedFiles.clear(context) } }

    /** True when the current clipboard change was written by DeviceLink itself (echo suppression). */
    internal fun isOwnClip(clip: ClipData): Boolean {
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
}
