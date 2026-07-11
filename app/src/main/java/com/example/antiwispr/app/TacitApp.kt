package com.example.antiwispr.app

import android.app.Application
import com.example.antiwispr.cloud.FirebaseBootstrap
import com.example.antiwispr.core.TacitNotifications

/**
 * Application entry point. Its one job is to initialize Firebase for EVERY process entry point
 * (Activity, FCM messaging service, accessibility service). The app initializes Firebase
 * manually from assets/google-services.json — there is no google-services plugin and thus no
 * FirebaseInitProvider auto-init — so an FCM push that cold-starts the process would otherwise
 * find no default FirebaseApp. ensureInitialized is idempotent and cheap.
 */
class TacitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseBootstrap.ensureInitialized(this)
        TacitNotifications.ensureChannels(this)
    }
}
