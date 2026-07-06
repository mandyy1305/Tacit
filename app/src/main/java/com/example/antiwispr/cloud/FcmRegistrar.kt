package com.example.antiwispr.cloud

import android.content.Context
import com.example.antiwispr.AppLog
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Proactively fetches the current FCM token and registers it with tacit-cloud. Called on
 * app start (when signed in) and right after sign-in — [TacitMessagingService.onNewToken]
 * only fires on rotation, so this covers the "token already existed" case.
 */
object FcmRegistrar {

    fun register(ctx: Context) {
        if (!CloudClient.ready(ctx)) return
        val app = ctx.applicationContext
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    AppLog.w("[fcm] token fetch failed: ${task.exception?.message}")
                    return@addOnCompleteListener
                }
                val token = task.result ?: return@addOnCompleteListener
                // CloudClient is blocking — register off the main thread.
                Thread {
                    if (CloudClient.registerDeviceToken(app, token)) {
                        AppLog.i("[fcm] device token registered with server.")
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
            AppLog.w("[fcm] register error: ${t.message}")
        }
    }
}
