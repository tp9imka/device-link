package dev.devicelink

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import dev.devicelink.designsystem.DeviceTheme
import dev.devicelink.designsystem.AppearanceMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.devicelink.feature.link.DeviceLinkApp
import dev.devicelink.feature.link.LinkCallbacks
import dev.devicelink.feature.link.InternetLinkScreen
import dev.devicelink.feature.link.InternetLinkCallbacks
import dev.devicelink.feature.link.SampleChatImage
import dev.devicelink.model.Transfer
import dev.devicelink.model.PairingInvite
import dev.devicelink.model.ClipSendResult
import dev.devicelink.model.LinkPhase
import dev.devicelink.model.InternetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as DeviceLinkApplication
    private var requestedDuration = 15
    private var saveSource: String? = null
    private var showPermissionSettings by mutableStateOf(false)
    private var showInternet by mutableStateOf(false)
    private var startInSample by mutableStateOf(false)
    private val useInternet get() = app.internetLink.state.value.enabled && app.controller.state.value.phase != LinkPhase.CONNECTED
    private val connectionPermission: String get() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    private val permissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasConnectionPermission()) startServiceSession() else message(R.string.permission_required)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasConnectionPermission()) startServiceSession() else message(R.string.permission_required)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            sendFiles(uris.map(Uri::toString))
        }
    }
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { code ->
            if (PairingInvite.decode(code) == null) {
                message(R.string.scan_failed)
            } else {
                app.controller.pairWithCode(code)
                if (!app.controller.state.value.enabled) requestSession(app.appearanceStore.appearance.value.sessionMinutes)
            }
        }
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val source = saveSource
        saveSource = null
        if (destination != null && source != null) lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val input = contentResolver.openInputStream(Uri.parse(source)) ?: error("missing source")
                    input.use { stream ->
                        val output = contentResolver.openOutputStream(destination) ?: error("missing destination")
                        output.use { stream.copyTo(it) }
                    }
                }.isSuccess
            }
            message(if (success) R.string.file_saved else R.string.save_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveSource = savedInstanceState?.getString("saveSource")
        requestedDuration = savedInstanceState?.getInt("requestedDuration") ?: app.appearanceStore.appearance.value.sessionMinutes
        showPermissionSettings = savedInstanceState?.getBoolean("showPermissionSettings") ?: false
        showInternet = savedInstanceState?.getBoolean("showInternet") ?: intent.getBooleanExtra("internet_screen", false)
        enableEdgeToEdge()
        setContent {
            val state = app.controller.state.collectAsStateWithLifecycle().value
            val appearance = app.appearanceStore.appearance.collectAsStateWithLifecycle().value
            val sampleMessages = app.sampleChatStore.messages.collectAsStateWithLifecycle().value
            val internet = app.internetLink.state.collectAsStateWithLifecycle().value
            val sampleImage by produceState<SampleChatImage?>(null) { value = app.imageClipboard.sample() }
            val dark = when (appearance.mode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.DARK -> true
                AppearanceMode.LIGHT -> false
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BackHandler(showInternet) { showInternet = false; startInSample = false }
            if (showInternet) DeviceTheme(appearance) {
                InternetLinkScreen(internet, InternetLinkCallbacks(
                    onConfigure = { url, token, clipboard -> app.applicationScope.launch {
                        message(if (app.internetLink.configure(url, token, clipboard)) R.string.internet_settings_saved else R.string.internet_settings_invalid)
                    } },
                    onStart = { ContextCompat.startForegroundService(this, Intent(this, InternetLinkService::class.java)) },
                    onStop = { app.internetLink.stop() },
                    onSelectPeer = app.internetLink::selectPeer,
                    onForgetPeer = app.internetLink::forgetPeer,
                    onSendClipboard = ::sendClipboard,
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onCopyText = ::copyTextLocally,
                    onCopyImage = { copyImage(it, false) },
                    onReceiveFile = { app.internetLink.acceptFile(it) },
                    onRejectFile = { app.internetLink.rejectFile(it) },
                    onOpenFile = ::openFile,
                    onSaveFile = { transfer -> saveSource = transfer.localUri; saveFile.launch(transfer.name) },
                    onSample = { startInSample = true; showInternet = false },
                    onBack = { startInSample = false; showInternet = false },
                ))
            } else DeviceLinkApp(
                state = state,
                controller = app.controller,
                appearanceStore = app.appearanceStore,
                callbacks = LinkCallbacks(
                    onStartSession = ::requestSession,
                    onSendClipboard = ::sendClipboard,
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onScanCode = {
                        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(false))
                    },
                    onCopyText = ::copyTextLocally,
                    onOpenFile = ::openFile,
                    onSaveFile = { transfer ->
                        saveSource = transfer.localUri
                        if (saveSource != null) saveFile.launch(transfer.name) else message(R.string.file_unavailable)
                    },
                    onShareFile = ::shareFile,
                    onCopySample = ::copySample,
                    onAddSampleMessage = { text ->
                        app.applicationScope.async {
                            app.sampleChatStore.append(text).also { saved ->
                                if (!saved) message(R.string.sample_message_not_saved)
                            }
                        }.await()
                    },
                    onCopyImage = { copyImage(it, false) },
                    onCopySampleImage = { copyImage(it, true) },
                    onPasteImage = { app.imageClipboard.importImage(it) },
                    onAddSampleImage = { text, image -> app.applicationScope.async {
                        app.sampleChatStore.append(text, image).also { if (!it) message(R.string.sample_message_not_saved) }
                    }.await() },
                    onOpenInternetLink = { showInternet = true },
                ),
                sampleMessages = sampleMessages,
                sampleImage = sampleImage,
                relayPeerName = if (!internet.finishing && internet.status == InternetStatus.READY) internet.selectedPeer?.name else null,
                initialSamples = startInSample,
            )
            if (showPermissionSettings) DeviceTheme(appearance) {
                AlertDialog(
                    onDismissRequest = { showPermissionSettings = false },
                    title = { Text(stringResource(R.string.permission_settings_title)) },
                    text = { Text(stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.permission_settings_nearby else R.string.permission_settings_location)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermissionSettings = false
                            permissionSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) { Text(stringResource(R.string.open_settings)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionSettings = false }) { Text(stringResource(R.string.not_now)) }
                    },
                )
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("saveSource", saveSource)
        outState.putInt("requestedDuration", requestedDuration)
        outState.putBoolean("showPermissionSettings", showPermissionSettings)
        outState.putBoolean("showInternet", showInternet)
        super.onSaveInstanceState(outState)
    }

    private fun requestSession(minutes: Int) {
        requestedDuration = minutes
        val permissionHistory = getSharedPreferences("permission_requests", MODE_PRIVATE)
        if (!hasConnectionPermission() && permissionHistory.getBoolean(connectionPermission, false) &&
            !shouldShowRequestPermissionRationale(connectionPermission)) {
            showPermissionSettings = true
            return
        }
        val required = mutableListOf(connectionPermission)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        else required += Manifest.permission.ACCESS_COARSE_LOCATION
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startServiceSession() else {
            if (connectionPermission in missing) permissionHistory.edit().putBoolean(connectionPermission, true).apply()
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun hasConnectionPermission(): Boolean = ContextCompat.checkSelfPermission(this, connectionPermission) == PackageManager.PERMISSION_GRANTED

    private fun startServiceSession() {
        ContextCompat.startForegroundService(this, Intent(this, LinkSessionService::class.java)
            .setAction(LinkSessionService.ACTION_START).putExtra(LinkSessionService.EXTRA_DURATION, requestedDuration))
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) { message(R.string.clipboard_empty); return }
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && uri.scheme == "content") { sendFiles(listOf(uri.toString())); return }
        else {
            val text = item.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) { message(R.string.clipboard_empty); return }
            if (useInternet) {
                app.applicationScope.launch { if (app.internetLink.sendText(text, false) != ClipSendResult.SENT) message(R.string.internet_send_failed) }
                return
            } else app.controller.sendText(text)
        }
        if (!app.controller.state.value.enabled) requestSession(app.appearanceStore.appearance.value.sessionMinutes)
    }

    private fun copySample(text: String) {
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.clipboard_label), text),
            )
        }.isSuccess
        if (!copied) { message(R.string.sample_copy_failed); return }
        app.clipboardAction()
        app.applicationScope.launch {
            val result = if (useInternet) app.internetLink.sendText(text, true) else app.controller.sendClip(text)
            message(when (result) {
                ClipSendResult.SENT -> R.string.sample_copy_sent
                ClipSendResult.NOT_CONNECTED -> R.string.sample_copy_local
                ClipSendResult.EXPIRED -> R.string.sample_copy_expired
                ClipSendResult.INVALID -> R.string.sample_copy_too_large
                ClipSendResult.FAILED -> R.string.sample_copy_not_sent
            })
        }
    }

    private fun copyTextLocally(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), text))
        app.clipboardAction()
        if (Build.VERSION.SDK_INT < 33) message(R.string.clipboard_copied)
    }

    private fun copyImage(uri: String, linked: Boolean) {
        val revision = app.clipboardAction()
        app.applicationScope.launch {
            val image = app.imageClipboard.importImage(uri)
            if (revision != app.clipboardRevision) return@launch
            if (image == null || !app.imageClipboard.write(image)) { message(R.string.image_copy_failed); return@launch }
            if (linked) {
                val result = if (useInternet) app.internetLink.sendFile(image.uri, true) else app.controller.sendImageClip(image.uri)
                message(when (result) {
                    ClipSendResult.SENT -> R.string.sample_copy_sent
                    ClipSendResult.NOT_CONNECTED, ClipSendResult.EXPIRED -> R.string.sample_copy_local
                    else -> R.string.internet_send_failed
                })
            } else if (Build.VERSION.SDK_INT < 33) message(R.string.clipboard_copied)
        }
    }

    private fun sendFiles(uris: List<String>) {
        if (useInternet) app.applicationScope.launch {
            for (uri in uris) if (app.internetLink.sendFile(uri, false) != ClipSendResult.SENT) message(R.string.internet_send_failed)
        } else {
            app.controller.sendFiles(uris)
            if (!app.controller.state.value.enabled) requestSession(app.appearanceStore.appearance.value.sessionMinutes)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(incoming: Intent) {
        if (incoming.getBooleanExtra("internet_screen", false)) { showInternet = true; incoming.removeExtra("internet_screen") }
        if (incoming.getBooleanExtra("activate", false)) {
            incoming.removeExtra("activate")
            requestSession(app.appearanceStore.appearance.value.sessionMinutes)
        }
        if (incoming.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val uris = mutableListOf<Uri>()
        if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
            uris += incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        if (uris.isEmpty()) incoming.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(uris::add) }
        }
        if (uris.isNotEmpty()) sendFiles(uris.distinct().map(Uri::toString))
        else incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
            if (useInternet) app.applicationScope.launch { if (app.internetLink.sendText(text, false) != ClipSendResult.SENT) message(R.string.internet_send_failed) }
            else app.controller.sendText(text)
        }
        incoming.action = Intent.ACTION_MAIN
        if (app.controller.state.value.pendingItems > 0 && !app.controller.state.value.enabled) requestSession(app.appearanceStore.appearance.value.sessionMinutes)
    }

    private fun openFile(transfer: Transfer) {
        val uri = transfer.localUri ?: return message(R.string.file_unavailable)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), transfer.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message(R.string.no_viewer) }
    }

    private fun shareFile(transfer: Transfer) {
        val uri = transfer.localUri?.let(Uri::parse) ?: return message(R.string.file_unavailable)
        val share = Intent(Intent.ACTION_SEND).setType(transfer.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.share_file)))
    }
    private fun message(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()
}
