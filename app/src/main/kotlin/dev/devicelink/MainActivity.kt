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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
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
import dev.devicelink.model.Transfer
import dev.devicelink.model.PairingInvite
import dev.devicelink.model.ClipSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as DeviceLinkApplication
    private var requestedDuration = 15
    private var saveSource: String? = null
    private var showPermissionSettings by mutableStateOf(false)
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
            app.controller.sendFiles(uris.map(Uri::toString))
            if (!app.controller.state.value.enabled) requestSession(app.appearanceStore.appearance.value.sessionMinutes)
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
        enableEdgeToEdge()
        setContent {
            val state = app.controller.state.collectAsStateWithLifecycle().value
            val appearance = app.appearanceStore.appearance.collectAsStateWithLifecycle().value
            val sampleMessages = app.sampleChatStore.messages.collectAsStateWithLifecycle().value
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
            DeviceLinkApp(
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
                    onCopyText = { text ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), text))
                        if (Build.VERSION.SDK_INT < 33) message(R.string.clipboard_copied)
                    },
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
                ),
                sampleMessages = sampleMessages,
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
        if (uri != null && uri.scheme == "content") app.controller.sendFiles(listOf(uri.toString()))
        else {
            val text = item.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) { message(R.string.clipboard_empty); return }
            app.controller.sendText(text)
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
        app.applicationScope.launch {
            val result = app.controller.sendClip(text)
            message(when (result) {
                ClipSendResult.SENT -> R.string.sample_copy_sent
                ClipSendResult.NOT_CONNECTED -> R.string.sample_copy_local
                ClipSendResult.EXPIRED -> R.string.sample_copy_expired
                ClipSendResult.INVALID -> R.string.sample_copy_too_large
                ClipSendResult.FAILED -> R.string.sample_copy_not_sent
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(incoming: Intent) {
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
        if (uris.isNotEmpty()) app.controller.sendFiles(uris.distinct().map(Uri::toString))
        else incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(app.controller::sendText)
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
