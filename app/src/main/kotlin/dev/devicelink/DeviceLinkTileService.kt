package dev.devicelink

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DeviceLinkTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val enabled = (application as DeviceLinkApplication).controller.state.value.enabled
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (enabled) R.string.tile_on else R.string.tile_off)
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val controller = (application as DeviceLinkApplication).controller
        if (controller.state.value.enabled) {
            controller.stopSession()
            onStartListening()
        } else {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("activate", true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        }
    }
}
