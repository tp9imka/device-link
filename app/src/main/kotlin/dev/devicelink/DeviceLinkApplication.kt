package dev.devicelink

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import dev.devicelink.model.LinkController
import dev.devicelink.transfer.NativeLinkController
import dev.devicelink.transfer.InternetLinkController
import dev.devicelink.designsystem.AppearanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceLinkApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller: LinkController by lazy { NativeLinkController(this, applicationScope) }
    val appearanceStore by lazy { AppearanceStore(this) }
    val sampleChatStore by lazy { SampleChatStore(this) }
    val imageClipboard by lazy { ImageClipboard(this) }
    val internetLink by lazy { InternetLinkController(this, applicationScope, controller) }
    var clipboardRevision: Long = 0
        private set
    fun clipboardAction(): Long = ++clipboardRevision

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            controller.incomingClips.collect { event ->
                // Stay on Main without suspension between the guard and clipboard write.
                if (controller.isClipCurrent(event)) {
                    val copied = runCatching {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.clipboard_label), event.text),
                        )
                    }.isSuccess
                    if (copied) clipboardAction()
                    controller.clipboardApplied(event, copied)
                }
            }
        }
        applicationScope.launch {
            controller.incomingImageClips.collect { event ->
                val revision = clipboardRevision
                val image = imageClipboard.importImage(event.uri)
                // Import can suspend. Recheck the session immediately before touching the clipboard.
                if (controller.isImageClipCurrent(event)) {
                    val copied = revision == clipboardRevision && image != null && imageClipboard.write(image)
                    if (copied) clipboardAction()
                    controller.imageClipboardApplied(event, copied)
                }
            }
        }
        applicationScope.launch {
            internetLink.incomingClips.collect { event ->
                val revision = clipboardRevision
                val image = event.uri?.let { imageClipboard.importImage(it) }
                if (internetLink.isClipCurrent(event)) {
                    val copied = if (event.text != null) runCatching {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), event.text))
                    }.isSuccess else revision == clipboardRevision && image != null && imageClipboard.write(image)
                    if (copied) clipboardAction()
                    internetLink.clipboardApplied(event, copied)
                }
            }
        }
    }
}
