package com.hearit.app.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class HearItTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "HearIt Now"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, HearItService::class.java).apply {
            action = HearItService.ACTION_HEAR_NOW
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
