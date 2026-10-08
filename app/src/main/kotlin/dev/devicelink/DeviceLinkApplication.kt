package dev.devicelink

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import dev.devicelink.model.LinkController
import dev.devicelink.transfer.NativeLinkController
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
                    controller.clipboardApplied(event, copied)
                }
            }
        }
    }
}
