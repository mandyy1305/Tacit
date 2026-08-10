package com.example.antiwispr.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.antiwispr.cloud.TranscriptNotifier
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.ui.AppRoot
import com.example.antiwispr.ui.AppViewModel
import com.example.antiwispr.ui.theme.TacitTheme

/**
 * TACIT's single activity. All UI is Compose (see ui/AppRoot); the pipeline itself lives in
 * the services (ProjectionService, MicCaptureService, WhatsAppAccessibilityService) and is
 * driven from AppViewModel. Referenced from res/xml/shortcuts.xml by fully-qualified name —
 * update that file if this class moves.
 */
class MainActivity : ComponentActivity() {

    // Shared with AppRoot's viewModel() (both resolve to this activity's store), so a deep-link
    // key set here is observed there.
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            TacitTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** A "transcript ready" notification carries the note key; a launcher shortcut carries a
     *  destination; a share-sheet ACTION_SEND carries an audio stream. Hand any of them to the
     *  VM for AppRoot to act on. */
    private fun handleDeepLink(intent: Intent?) {
        intent?.getStringExtra(TranscriptNotifier.EXTRA_NOTE_KEY)?.let { vm.pendingOpenKey = it }
        intent?.getStringExtra("tacit.dest")?.let { vm.pendingDest = it }
        if (intent?.action == Intent.ACTION_SEND) {
            // ClipData fallback: some senders attach the stream there instead of EXTRA_STREAM.
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) vm.pendingSharedAudio = uri
            else AppLog.w("[share] ACTION_SEND with no stream, ignoring.")
        }
    }
}
