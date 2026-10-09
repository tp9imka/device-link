package dev.devicelink.receiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.devicelink.sdk.ClipRecord
import dev.devicelink.sdk.DeviceLink
import dev.devicelink.sdk.DeviceLinkInstance
import dev.devicelink.sdk.DeviceLinkPairing
import dev.devicelink.sdk.core.LinkedPeer
import dev.devicelink.sdk.core.ReceiverStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The thin receiver: link devices, see clips, re-copy or share them, send the clipboard back. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var link: DeviceLinkInstance
    private lateinit var adapter: ClipAdapter
    private lateinit var header: View
    private lateinit var receive: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        link = DeviceLink.get(this)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        val list = findViewById<ListView>(R.id.clips)
        header = LayoutInflater.from(this).inflate(R.layout.header, list, false)
        list.addHeaderView(header, null, false)
        list.addFooterView(LayoutInflater.from(this).inflate(R.layout.footer, list, false), null, false)
        adapter = ClipAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> recopy(adapter.getItem(position - list.headerViewsCount)) }
        list.setOnItemLongClickListener { _, view, position, _ -> menu(view, adapter.getItem(position - list.headerViewsCount)); true }

        receive = findViewById(R.id.receive)
        receive.setOnCheckedChangeListener { _, checked -> if (checked != link.receiverEnabled) link.receiverEnabled = checked }
        header.findViewById<Button>(R.id.show_code).setOnClickListener { DeviceLinkPairing.showCode(this) }
        header.findViewById<Button>(R.id.link_more).setOnClickListener { DeviceLinkPairing.showCode(this) }
        header.findViewById<Button>(R.id.scan_code).setOnClickListener { DeviceLinkPairing.startScan(this) }
        header.findViewById<Button>(R.id.paste_link).setOnClickListener { pasteLink() }
        header.findViewById<Button>(R.id.paste_send).setOnClickListener { pasteAndSend() }
        header.findViewById<Button>(R.id.send_file).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FILE)
        }
        header.findViewById<Button>(R.id.clear).setOnClickListener { link.clearHistory() }
        findViewById<Button>(R.id.diagnostics).setOnClickListener { showDiagnostics() }

        scope.launch { combine(link.peers, link.status) { peers, status -> peers to status }.collect { (peers, status) -> render(peers, status) } }
        scope.launch { link.history.collect { records ->
            adapter.items = records
            header.findViewById<View>(R.id.clips_empty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            header.findViewById<View>(R.id.clear).visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        } }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        }
        DeviceLinkPairing.handleIntent(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeviceLinkPairing.handleIntent(this, intent)
    }

    private fun render(peers: List<LinkedPeer>, status: ReceiverStatus) {
        header.findViewById<View>(R.id.empty).visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        header.findViewById<View>(R.id.linked).visibility = if (peers.isEmpty()) View.GONE else View.VISIBLE
        receive.visibility = if (peers.isEmpty()) View.GONE else View.VISIBLE
        receive.isChecked = link.receiverEnabled
        val targets = link.sendTargets
        findViewById<TextView>(R.id.status).text = if (targets.isNotEmpty() && peers.size > 1)
            getString(R.string.sending_to, peers.filter { it.id in targets }.joinToString { it.name }) else getString(when {
            peers.isEmpty() -> if (link.client.isConfigured) R.string.status_idle else R.string.status_setup
            !link.receiverEnabled -> R.string.status_off
            status == ReceiverStatus.ONLINE -> R.string.status_online
            status == ReceiverStatus.OFFLINE -> R.string.status_offline
            status == ReceiverStatus.REJECTED -> R.string.status_rejected
            else -> R.string.status_connecting
        })
        val container = header.findViewById<android.widget.LinearLayout>(R.id.peers)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        peers.forEach { peer ->
            container.addView(TextView(this).apply {
                text = "${platformGlyph(peer.platform)}  ${peer.name}"
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.chip)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (40 * density).toInt()
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                contentDescription = peer.name
                setOnClickListener { peerMenu(this, peer) }
            }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * density).toInt(); topMargin = (8 * density).toInt() })
        }
    }

    private fun peerMenu(anchor: View, peer: LinkedPeer) {
        PopupMenu(this, anchor).apply {
            if (link.peers.value.size > 1) {
                menu.add(0, 1, 0, getString(R.string.send_only_to, peer.name))
                if (link.sendTargets.isNotEmpty()) menu.add(0, 2, 1, R.string.send_to_all)
            }
            menu.add(0, 3, 2, R.string.unlink)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> link.sendTargets = setOf(peer.id)
                    2 -> link.sendTargets = emptySet()
                    else -> confirmUnlink(peer)
                }
                render(link.peers.value, link.status.value)
                true
            }
        }.show()
    }

    /** Relay, registration, last poll/receive/send and last error, plus a test clip and a full reset. */
    private fun showDiagnostics() {
        AlertDialog.Builder(this).setTitle(R.string.diagnostics_title).setMessage(link.diagnosticsReport())
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("DeviceLink diagnostics", link.diagnosticsReport()))
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.diagnostics_test) { _, _ ->
                scope.launch {
                    val stamp = java.text.DateFormat.getTimeInstance().format(java.util.Date())
                    val outcomes = runCatching { link.sendText(getString(R.string.test_clip, stamp)) }.getOrDefault(emptyList())
                    Toast.makeText(this@MainActivity, if (outcomes.isNotEmpty() && outcomes.all { it.accepted })
                        getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name })
                        else getString(dev.devicelink.sdk.R.string.dl_send_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.diagnostics_reset) { _, _ -> confirmReset() }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle(R.string.reset_title).setMessage(R.string.reset_body)
            .setPositiveButton(R.string.diagnostics_reset) { _, _ ->
                scope.launch {
                    link.resetDevice()
                    Toast.makeText(this@MainActivity, R.string.reset_done, Toast.LENGTH_LONG).show()
                    recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun platformGlyph(platform: String) = when (platform) { "ios" -> "📱"; "android" -> "🤖"; else -> "💻" }

    private fun confirmUnlink(peer: LinkedPeer) {
        AlertDialog.Builder(this).setTitle(getString(R.string.unlink_title, peer.name)).setMessage(R.string.unlink_body)
            .setPositiveButton(R.string.unlink) { _, _ -> scope.launch { runCatching { link.unlink(peer.id) } } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun recopy(record: ClipRecord) {
        scope.launch(Dispatchers.Default) {
            val copied = link.copyToClipboard(record)
            launch(Dispatchers.Main) {
                if (copied) Toast.makeText(this@MainActivity, R.string.copied_again, Toast.LENGTH_SHORT).show() else share(record)
            }
        }
    }

    private fun share(record: ClipRecord) { link.shareIntent(record)?.let(::startActivity) }

    private fun menu(anchor: View, record: ClipRecord) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.copy); menu.add(0, 2, 1, R.string.share); menu.add(0, 3, 2, R.string.remove)
            setOnMenuItemClickListener { when (it.itemId) { 1 -> recopy(record); 2 -> share(record); else -> link.forget(record) }; true }
        }.show()
    }

    /** The activity is focused here, so Android allows reading the clipboard. */
    private fun pasteAndSend() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) { Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            val outcomes = runCatching { link.sendClip(clip) }.getOrDefault(emptyList())
            Toast.makeText(this@MainActivity, if (outcomes.isNotEmpty() && outcomes.all { it.accepted })
                getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name })
                else getString(dev.devicelink.sdk.R.string.dl_send_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteLink() {
        val input = EditText(this).apply {
            setHint(R.string.paste_link_hint)
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.let { setText(it) }
        }
        AlertDialog.Builder(this).setTitle(R.string.paste_link_title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> DeviceLinkPairing.open(this, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (DeviceLinkPairing.onActivityResult(this, requestCode, resultCode, data)) return
        val uri = data?.data
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && uri != null) scope.launch {
            val result = runCatching { link.sendUri(uri) }
            Toast.makeText(this@MainActivity, result.exceptionOrNull()?.message
                ?: getString(dev.devicelink.sdk.R.string.dl_send_ok, link.peers.value.joinToString { it.name }), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object { const val PICK_FILE = 41; const val NOTIFICATIONS = 42 }
}
