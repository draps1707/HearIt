package com.hearit.app.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class HeadphoneCaptureTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "HearIt Earbuds"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, HeadphoneCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(intent)
    }
}

class HeadphoneCaptureActivity : Activity() {
    private val REQ_CAPTURE = 901

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, HearItService::class.java).apply {
                action = HearItService.ACTION_HEADPHONE_CAPTURE
                putExtra(HearItService.EXTRA_RESULT_CODE, resultCode)
                putExtra(HearItService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, svc)
        }
        finish()
    }
}
