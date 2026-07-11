package com.example.antiwispr.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Central definition of TACIT's notification channels (design doc 09). Created once at process
 * start (TacitApp) and defensively before any service posts. Every channel is silent: no sound,
 * no vibration. The overlay is the delivery surface; notifications exist only for sessions, long
 * jobs, and rare nudges, never for an individual transcription or match. Legacy Antiwispr
 * channels are deleted on upgrade so they stop appearing in the system settings list.
 *
 * minSdk is 30, so NotificationChannel is always available; no O guard needed.
 */
object TacitNotifications {
    const val CHANNEL_SESSION = "tacit_session"    // Precision listening FGS, ongoing
    const val CHANNEL_MIC = "tacit_mic"            // mic FGS, transient
    const val CHANNEL_PROGRESS = "tacit_progress"  // pack downloads, Catch up runs
    const val CHANNEL_ALERTS = "tacit_alerts"      // rare nudges, health alerts, account notices

    private val LEGACY = listOf("antiwispr_projection", "antiwispr_mic")

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        LEGACY.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(channel(CHANNEL_SESSION, "Precision listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(channel(CHANNEL_MIC, "Microphone", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(channel(CHANNEL_PROGRESS, "Downloads and Catch up", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(channel(CHANNEL_ALERTS, "Setup and alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun channel(id: String, name: String, importance: Int) =
        NotificationChannel(id, name, importance).apply {
            setSound(null, null)
            enableVibration(false)
        }
}
