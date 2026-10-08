package dev.devicelink

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.devicelink.feature.link.DeviceLinkApp
import dev.devicelink.feature.link.LinkCallbacks
import dev.devicelink.model.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as DeviceLinkApplication
    private var requestedDuration = 15
    private var saveSource: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasConnectionPermission()) startServiceSession() else message(R.string.permission_required)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            app.controller.sendFiles(uris.map(Uri::toString))
            if (!app.controller.state.value.enabled) requestSession(requestedDuration)
        }
    }
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            app.controller.pairWithCode(it)
            if (!app.controller.state.value.enabled) requestSession(requestedDuration)
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
        enableEdgeToEdge()
        setContent {
            val state = app.controller.state.collectAsStateWithLifecycle().value
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
                ),
            )
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
        super.onSaveInstanceState(outState)
    }

    private fun requestSession(minutes: Int) {
        requestedDuration = minutes
        val required = mutableListOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startServiceSession() else permissions.launch(missing.toTypedArray())
    }

    private fun hasConnectionPermission(): Boolean = ContextCompat.checkSelfPermission(this,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

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
        if (!app.controller.state.value.enabled) requestSession(requestedDuration)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(incoming: Intent) {
        if (incoming.getBooleanExtra("activate", false)) {
            incoming.removeExtra("activate")
            requestSession(requestedDuration)
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
        if (app.controller.state.value.pendingItems > 0 && !app.controller.state.value.enabled) requestSession(requestedDuration)
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
