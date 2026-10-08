package dev.devicelink

import android.app.Application
import dev.devicelink.model.LinkController
import dev.devicelink.transfer.NativeLinkController
import dev.devicelink.designsystem.AppearanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DeviceLinkApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller: LinkController by lazy { NativeLinkController(this, applicationScope) }
    val appearanceStore by lazy { AppearanceStore(this) }
}
