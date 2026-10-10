package dev.devicelink.sample

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.devicelink.sdk.ClipDirection
import dev.devicelink.sdk.ClipState
import dev.devicelink.sdk.DeviceLink
import dev.devicelink.sdk.DeviceLinkPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Synthetic sample data. Copying anything here goes through ClipboardManager; the SDK does the rest. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val link by lazy { DeviceLink.get(this) }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val imageUri by lazy { Uri.parse("android.resource://$packageName/${R.raw.sample_image}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        findViewById<Button>(R.id.show_code).setOnClickListener { DeviceLinkPairing.showCode(this) }
        findViewById<Button>(R.id.scan_code).setOnClickListener { DeviceLinkPairing.startScan(this) }
        findViewById<ImageView>(R.id.image).setImageURI(imageUri)
        findViewById<Button>(R.id.copy_image).setOnClickListener {
            copy(ClipData(ClipDescription(getString(R.string.image_heading), arrayOf("image/png")), ClipData.Item(imageUri)))
        }
        findViewById<Button>(R.id.send_file).setOnClickListener {
            scope.launch {
                val uri = Uri.parse("android.resource://$packageName/${R.raw.sample_notes}")
                val result = runCatching { link.sendUri(uri, "text/plain") }
                Toast.makeText(this@MainActivity, result.exceptionOrNull()?.message ?: getString(R.string.copied_sent, peerNames()), Toast.LENGTH_SHORT).show()
            }
        }
        addSamples()
        scope.launch { link.peers.collect { peers ->
            findViewById<TextView>(R.id.link_state).text = if (peers.isEmpty()) getString(R.string.not_linked)
                else getString(R.string.linked_with, peers.joinToString { it.name })
        } }
        scope.launch { link.history.collect { records ->
            val sent = records.filter { it.direction == ClipDirection.SENT }.take(6)
            findViewById<TextView>(R.id.deliveries).text = if (sent.isEmpty()) getString(R.string.deliveries_empty) else sent.joinToString("\n") {
                val what = (it.text ?: it.name ?: "image").replace('\n', ' ').take(40)
                val state = when (it.state) {
                    ClipState.PENDING -> getString(R.string.state_pending)
                    ClipState.COPIED -> getString(R.string.state_copied, it.peerName)
                    ClipState.DELIVERED, ClipState.SHARE_NEEDED -> getString(R.string.state_delivered, it.peerName)
                    ClipState.FAILED -> getString(R.string.state_failed)
                }
                "• $what — $state"
            }
        } }
    }

    private fun addSamples() {
        val long = buildString { var n = 1; while (length < 20_000) append("Line ${n++}: synthetic long clipboard text for DeviceLink.\n") }
        val samples = listOf(
            getString(R.string.sample_short) to getString(R.string.sample_short),
            getString(R.string.sample_otp) to getString(R.string.sample_otp),
            getString(R.string.sample_url) to getString(R.string.sample_url),
            getString(R.string.sample_address) to getString(R.string.sample_address),
            getString(R.string.sample_unicode) to getString(R.string.sample_unicode),
            getString(R.string.sample_code) to getString(R.string.sample_code),
            getString(R.string.sample_long_label) to long,
        )
        val container = findViewById<LinearLayout>(R.id.samples)
        val density = resources.displayMetrics.density
        samples.forEach { (label, value) ->
            container.addView(LinearLayout(this).apply {
                setBackgroundResource(R.drawable.card)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                addView(TextView(this@MainActivity).apply {
                    text = label; setTextColor(getColor(R.color.on_surface)); setTextIsSelectable(true); maxLines = 3
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@MainActivity, null, 0, R.style.SecondaryButton).apply {
                    setText(R.string.copy)
                    setOnClickListener { copy(ClipData.newPlainText(label, value)) }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
        }
    }

    /** A plain clipboard write: the SDK's listener notices it and sends it, exactly as for any other copy. */
    private fun copy(clip: ClipData) {
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, if (link.peers.value.isEmpty()) getString(R.string.copied_local) else getString(R.string.copied_sent, peerNames()),
            Toast.LENGTH_SHORT).show()
    }

    private fun peerNames() = link.peers.value.joinToString { it.name }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        DeviceLinkPairing.onActivityResult(this, requestCode, resultCode, data)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
