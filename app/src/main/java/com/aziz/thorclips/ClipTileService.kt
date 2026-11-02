package com.aziz.thorclips

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: tap to start or stop the clip buffer without opening the app.
 * Long-press opens Dual Clips.
 */
class ClipTileService : TileService() {

    companion object {
        /** Ask the system to redraw the tile (called when recording starts/stops). */
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, ClipTileService::class.java)) }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        if (ReplayService.instance != null && !ReplayService.status.startsWith("Error")) {
            startService(Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_STOP))
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        // Starting needs Android's screen-capture prompt, which must come from an activity.
        val i = Intent(this, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val t = qsTile ?: return
        val on = ReplayService.recording || (ReplayService.instance != null && !ReplayService.status.startsWith("Error"))
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Dual Clips"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                !on -> "Off"
                ReplayService.status.contains("both") -> "Both screens"
                ReplayService.status.contains("bottom") -> "Bottom screen"
                else -> "Top screen"
            }
        }
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        t.updateTile()
    }
}
