package dev.devicelink.sdk

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.PersistableBundle
import android.os.Looper
import dev.devicelink.sdk.core.Encoding
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes received items to the system clipboard. Android lets a foreground service *write* the
 * clipboard; only reading is restricted to the focused app. Clips written here carry [LABEL] so
 * the auto-share listener never echoes them back to the sender.
 */
internal class ClipboardBridge(context: Context) {
    private val context = context.applicationContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile var lastWrittenSignature: String? = null
        private set

    fun writeText(text: String, html: String? = null, sensitive: Boolean = false): Boolean {
        val clip = if (html != null) ClipData.newHtmlText(LABEL, text, html) else ClipData.newPlainText(LABEL, text)
        // Android 13+ hides sensitive clips from the copy confirmation and keyboard suggestions.
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        return write(clip, signature(text))
    }

    fun writeUri(uri: Uri, mime: String): Boolean =
        write(ClipData(ClipDescription(LABEL, arrayOf(mime)), ClipData.Item(uri)), "uri:$uri")

    /** ClipboardManager must be used from a looper thread; block briefly for the real outcome. */
    private fun write(clip: ClipData, signature: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        main.post {
            success = runCatching { clipboard.setPrimaryClip(clip) }.isSuccess
            if (success) lastWrittenSignature = signature
            latch.countDown()
        }
        return latch.await(5, TimeUnit.SECONDS) && success
    }

    companion object {
        const val LABEL = "DeviceLink"
        fun signature(text: String) = "text:" + Encoding.hex(Encoding.sha256(text.toByteArray()))
    }
}
