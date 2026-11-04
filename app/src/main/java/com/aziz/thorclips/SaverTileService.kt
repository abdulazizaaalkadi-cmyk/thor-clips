package com.aziz.thorclips

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap turns Dual Clips' Battery saver on or off (applies straight away). */
class SaverTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, SaverTileService::class.java)) }
        }

        /** Flip the setting and, if the buffer is running, restart it with the new settings. */
        fun toggle(ctx: Context) {
            val s = Settings(ctx)
            s.batterySaver = !s.batterySaver
            EventLog.add("battery saver ${if (s.batterySaver) "on" else "off"}")
            if (ReplayService.instance != null) {
                runCatching { ctx.startService(Intent(ctx, ReplayService::class.java).setAction(ReplayService.ACTION_APPLY)) }
            }
            refresh(ctx)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        toggle(this)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = Settings(this).batterySaver
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Clip saver"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "30 fps · games only" else "Off"
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        t.updateTile()
    }
}
