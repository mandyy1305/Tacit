package com.example.antiwispr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Hosts the mic-fallback [MicWindowSource] inside a FOREGROUND SERVICE with the `microphone` type.
 * This is required because Android mutes the mic for background apps — capturing from the accessibility
 * service directly (while WhatsApp is foreground) returns silence. Starting this FGS from the background
 * is permitted because the app holds SYSTEM_ALERT_WINDOW (an FGS background-start exemption); once it's
 * running with type=microphone, the mic delivers real audio.
 */
class MicCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.antiwispr.mic.START"
        const val ACTION_STOP = "com.example.antiwispr.mic.STOP"
        private const val CHANNEL_ID = "antiwispr_mic"
        private const val NOTIF_ID = 0x4D49 // "MI"

        @Volatile var source: AudioWindowSource? = null
            private set
        @Volatile var active: Boolean = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, MicCaptureService::class.java).apply { action = ACTION_START })

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, MicCaptureService::class.java).apply { action = ACTION_STOP }) } catch (_: Exception) {}
        }
    }

    private var mic: MicWindowSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMic()
            ACTION_STOP -> teardown()
            else -> if (mic == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMic() {
        if (mic != null) return
        startForegroundMic()
        val m = MicWindowSource(this)
        if (!m.start()) {
            AppLog.e("[mic-fgs] mic AudioRecord failed to start (permission? busy?).")
            teardown()
            return
        }
        mic = m
        source = m
        active = true
        AppLog.i("[mic-fgs] microphone foreground service running — mic capture is live.")
    }

    private fun startForegroundMic() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mic capture", NotificationManager.IMPORTANCE_LOW))
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Antiwispr — listening (mic)")
            .setContentText("Screen not shared; using microphone")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        AppLog.i("[mic-fgs] startForeground(type=microphone).")
    }

    private fun teardown() {
        active = false
        source = null
        try { mic?.stop() } catch (_: Exception) {}
        mic = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        source = null
        try { mic?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
