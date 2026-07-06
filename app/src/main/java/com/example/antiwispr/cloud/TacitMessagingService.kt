package com.example.antiwispr.cloud

import com.example.antiwispr.AppLog
import com.example.antiwispr.Transcripts
import com.example.antiwispr.WhatsAppAccessibilityService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the server's "transcript ready" push for a long note transcribed via the Sarvam
 * batch API. onMessageReceived runs off the main thread, so it syncs the just-written record,
 * then either updates the LIVE overlay (feels instant, like the sync path) or — if the card is
 * gone — posts a tappable notification that deep-links to the note.
 */
class TacitMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppLog.i("[fcm] new device token.")
        val app = applicationContext
        Thread {
            if (CloudClient.ready(app)) CloudClient.registerDeviceToken(app, token)
        }.apply { isDaemon = true }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "transcript_ready") return
        val key = data["key"] ?: return
        AppLog.i("[fcm] transcript_ready for $key")

        // Pull the record the server just wrote (blocking; we're already off-main).
        SyncEngine.syncNow(applicationContext)
        val rec = Transcripts.get(applicationContext).byKey(key)
        if (rec == null || rec.text.isBlank()) {
            AppLog.w("[fcm] record $key not present after sync — skipping.")
            return
        }

        // Update the live overlay if it's still showing this note; else notify.
        val handled = WhatsAppAccessibilityService.active?.onCloudTranscriptReady(key, rec.text) ?: false
        if (handled) {
            AppLog.i("[fcm] delivered to live overlay for $key.")
        } else {
            AppLog.i("[fcm] overlay gone — posting notification for $key.")
            TranscriptNotifier.show(applicationContext, rec)
        }
    }
}
