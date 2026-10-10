package dev.devicelink.sample

import android.app.Application
import dev.devicelink.sdk.DeviceLink

/**
 * The whole integration: initialize once and opt in to auto-share. From then on every copy made
 * inside this app (selection Copy, Copy buttons, pasted-then-copied text) is sent to linked devices.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val link = DeviceLink.init(this)
        link.enableAutoShare(this)
        if (link.receiverEnabled && link.peers.value.isNotEmpty()) link.startReceiver()
    }
}
