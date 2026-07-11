package com.example.antiwispr.audio

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.antiwispr.core.AppLog

/**
 * Invisible trampoline so the overlay's "Share screen" button (which runs in the accessibility SERVICE
 * context and can't startActivityForResult) can still request MediaProjection consent. Launched with
 * FLAG_ACTIVITY_NEW_TASK; it runs the consent dialog, starts ProjectionService with the result, and
 * finishes. Uses a translucent theme (declared in the manifest) so nothing visible flashes.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                AppLog.i("[share] projection consent GRANTED from overlay — starting session.")
                val svc = Intent(this, ProjectionService::class.java).apply {
                    action = ProjectionService.ACTION_START_SESSION
                    putExtra(ProjectionService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ProjectionService.EXTRA_RESULT_DATA, res.data)
                    putExtra(ProjectionService.EXTRA_RATE, 16000)
                }
                ContextCompat.startForegroundService(this, svc)
            } else {
                AppLog.i("[share] projection consent cancelled.")
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { AppLog.w("[share] MediaProjectionManager unavailable."); finish(); return }
        try {
            launcher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            AppLog.e("[share] failed to launch consent: ${e.message}", e); finish()
        }
    }
}
