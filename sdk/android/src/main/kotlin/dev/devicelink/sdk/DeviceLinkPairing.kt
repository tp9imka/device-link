package dev.devicelink.sdk

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import dev.devicelink.sdk.core.LinkedPeer
import dev.devicelink.sdk.core.PairingException
import dev.devicelink.sdk.core.PairingInvite
import dev.devicelink.sdk.core.SetupLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ready-made pairing UI for host apps: show this device's one-time QR, scan another device's QR,
 * and handle pairing links opened from the camera. Uses only framework dialogs.
 */
object DeviceLinkPairing {
    const val SCAN_REQUEST = 0x0D11

    /** Shows a one-time QR (valid 5 minutes). The other device scans it; [onLinked] runs once linked. */
    @JvmStatic @JvmOverloads
    fun showCode(activity: Activity, onLinked: (LinkedPeer) -> Unit = {}) {
        val link = DeviceLink.get(activity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val density = activity.resources.displayMetrics.density
        val image = ImageView(activity).apply { adjustViewBounds = true; contentDescription = activity.getString(R.string.dl_pair_qr_description) }
        val hint = TextView(activity).apply { setText(R.string.dl_pair_creating); gravity = Gravity.CENTER; setPadding(0, (12 * density).toInt(), 0, 0) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(ProgressBar(activity))
            addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hint)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.dl_pair_show_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        scope.launch {
            try {
                val session = link.invite()
                content.removeViewAt(0)
                image.setImageBitmap(PairingQr.bitmap(session.uri))
                launch {
                    while (true) {
                        val left = ((session.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        hint.text = activity.getString(R.string.dl_pair_show_hint, left / 60, left % 60)
                        if (left == 0L) break
                        delay(1000)
                    }
                }
                dialog.setOnCancelListener { CoroutineScope(Dispatchers.IO).launch { session.cancel() } }
                val peer = session.awaitPeer()
                link.receiverEnabled = true
                dialog.dismiss()
                showLinked(activity, peer)
                onLinked(peer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dialog.dismiss()
                showError(activity, failure)
            }
        }
    }

    /** Opens the camera scanner; deliver results through [onActivityResult]. */
    @JvmStatic
    fun startScan(activity: Activity) {
        IntentIntegrator(activity).setRequestCode(SCAN_REQUEST).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(activity.getString(R.string.dl_pair_scan_prompt)).setBeepEnabled(false).setOrientationLocked(false).initiateScan()
    }

    /** Call from Activity.onActivityResult; returns true when the result belonged to the scanner. */
    @JvmStatic @JvmOverloads
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?, onLinked: (LinkedPeer) -> Unit = {}): Boolean {
        if (requestCode != SCAN_REQUEST) return false
        val contents = IntentIntegrator.parseActivityResult(resultCode, data)?.contents ?: return true
        open(activity, contents, onLinked)
        return true
    }

    /** Handles VIEW intents for `https://<relay>/pair#…`, `devicelink://pair…` and setup links. */
    @JvmStatic @JvmOverloads
    fun handleIntent(activity: Activity, intent: Intent?, onLinked: (LinkedPeer) -> Unit = {}): Boolean {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return false
        open(activity, data, onLinked)
        intent.data = null
        return true
    }

    /** Joins a pairing link, or applies an admin setup link. Text pasted by the user works too. */
    @JvmStatic @JvmOverloads
    fun open(activity: Activity, text: String, onLinked: (LinkedPeer) -> Unit = {}) {
        val link = DeviceLink.get(activity)
        val insecure = link.options.allowInsecureRelay
        if (SetupLink.parse(text, insecure) != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val applied = runCatching { link.applySetupLink(text) }
                if (applied.isSuccess) Toast.makeText(activity.applicationContext, R.string.dl_setup_applied, Toast.LENGTH_LONG).show()
                else showError(activity, applied.exceptionOrNull()!!)
            }
            return
        }
        if (PairingInvite.parse(text, insecure) == null) {
            showError(activity, PairingException(activity.getString(R.string.dl_pair_not_a_code)))
            return
        }
        val progress = progressDialog(activity)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val peer = link.join(text)
                progress.dismiss()
                showLinked(activity, peer)
                onLinked(peer)
            } catch (failure: Exception) {
                progress.dismiss()
                showError(activity, failure)
            }
        }
    }

    /**
     * One-time confirmation after linking: both screens show the same six digits. Nothing is asked
     * again afterwards; the devices trust each other's keys until unlinked.
     */
    private fun showLinked(activity: Activity, peer: LinkedPeer) {
        if (activity.isFinishing) {
            Toast.makeText(activity.applicationContext, activity.getString(R.string.dl_pair_linked, peer.name), Toast.LENGTH_LONG).show()
            return
        }
        val code = peer.pairingCode?.let { it.take(3) + " " + it.drop(3) }
        val builder = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.dl_pair_linked, peer.name))
            .setPositiveButton(android.R.string.ok, null)
        if (code != null) {
            builder.setMessage(activity.getString(R.string.dl_pair_code_body, code))
                .setNegativeButton(R.string.dl_pair_code_mismatch) { _, _ ->
                    CoroutineScope(Dispatchers.Main).launch { runCatching { DeviceLink.get(activity).unlink(peer.id) } }
                }
        }
        builder.show()
    }

    private fun progressDialog(activity: Activity): Dialog {
        val density = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(activity))
            addView(TextView(activity).apply { setText(R.string.dl_pair_joining); setPadding(padding / 2, 0, 0, 0); setTypeface(typeface, Typeface.BOLD) })
        }
        return AlertDialog.Builder(activity).setView(row).setCancelable(false).create().also { it.show() }
    }

    private fun showError(activity: Activity, failure: Throwable) {
        if (activity.isFinishing) return
        val message = when (failure) {
            is PairingException -> failure.message
            is java.io.IOException -> activity.getString(R.string.dl_error_network)
            else -> failure.message
        } ?: activity.getString(R.string.dl_error_generic)
        AlertDialog.Builder(activity).setTitle(R.string.dl_pair_failed).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }
}
