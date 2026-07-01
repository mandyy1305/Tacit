package com.example.antiwispr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.antiwispr.ui.AppRoot
import com.example.antiwispr.ui.theme.TacitTheme

/**
 * TACIT's single activity. All UI is Compose (see ui/AppRoot); the pipeline itself lives in
 * the services (ProjectionService, MicCaptureService, WhatsAppAccessibilityService) and is
 * driven from AppViewModel. NOTE: ProjectionService's notification PendingIntent targets this
 * class by name — do not rename/move.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TacitTheme {
                AppRoot()
            }
        }
    }
}
