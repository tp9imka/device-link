package dev.devicelink.sample

import android.app.Application
import android.content.Context
import dev.devicelink.sdk.DeviceLink
import dev.devicelink.sdk.DeviceLinkOptions

/**
 * The whole integration: initialize once and opt in to auto-share. From then on every copy made
 * inside this app (selection Copy, Copy buttons, pasted-then-copied text) is sent to linked devices.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Host-app mode (validation kit D13): received clips stay with the app instead of the clipboard.
        val options = DeviceLinkOptions.fromManifest(this).copy(autoCopy = !hostMode(this))
        val link = DeviceLink.init(this, options)
        link.enableAutoShare(this)
        if (link.receiverEnabled && link.peers.value.isNotEmpty()) link.startReceiver()
    }

    companion object {
        private const val HOST_MODE = "host_mode"

        /** Persisted choice; applied by [onCreate], so a change takes effect on the next process start. */
        fun hostMode(context: Context) = prefs(context).getBoolean(HOST_MODE, false)

        fun setHostMode(context: Context, on: Boolean) {
            // commit(), not apply(): the process may be restarted right after this.
            prefs(context).edit().putBoolean(HOST_MODE, on).commit()
        }

        private fun prefs(context: Context) = context.getSharedPreferences("sample", Context.MODE_PRIVATE)
    }
}
