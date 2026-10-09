package dev.devicelink.sdk

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.os.Bundle
import kotlinx.coroutines.launch

/**
 * Listens for clipboard changes while any activity of the host app is resumed (the only time
 * Android delivers them to an ordinary app) and sends each new copy to every linked device.
 */
internal object AutoShare {
    @Volatile private var installed = false

    fun install(application: Application, link: DeviceLinkInstance) {
        if (installed) return
        installed = true
        val clipboard = application.getSystemService(ClipboardManager::class.java)
        var lastSent: String? = null
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return@OnPrimaryClipChangedListener
            if (link.isOwnClip(clip) || link.peers.value.isEmpty()) return@OnPrimaryClipChangedListener
            val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return@OnPrimaryClipChangedListener
            // Some apps fire the callback twice for one copy; send each distinct clip once.
            val key = item.uri?.toString() ?: item.text?.toString() ?: return@OnPrimaryClipChangedListener
            if (key == lastSent) return@OnPrimaryClipChangedListener
            lastSent = key
            link.scope.launch { runCatching { link.sendClip(clip) } }
        }
        var resumed = 0
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (resumed++ == 0) clipboard.addPrimaryClipChangedListener(listener) }
            override fun onActivityPaused(activity: Activity) { if (--resumed == 0) clipboard.removePrimaryClipChangedListener(listener) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
