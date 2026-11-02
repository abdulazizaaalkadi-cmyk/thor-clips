package com.aziz.thorclips

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/** Invisible screen that shows Android's capture prompt, starts the buffer, and closes. */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ReplayService::class.java)
                    .setAction(ReplayService.ACTION_START)
                    .putExtra(ReplayService.EXTRA_CODE, resultCode)
                    .putExtra(ReplayService.EXTRA_DATA, data)
            )
            val msg = if (ReplayService.shizukuReady()) "Dual Clips: recording" else "Dual Clips: recording top screen (Shizuku off)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
