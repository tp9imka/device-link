package dev.devicelink.receiver

import android.app.Application
import dev.devicelink.sdk.DeviceLink

/** Creates the device identity on first launch and resumes receiving if the user left it on. */
class ReceiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val link = DeviceLink.init(this)
        if (link.receiverEnabled && link.peers.value.isNotEmpty()) link.startReceiver()
    }
}
