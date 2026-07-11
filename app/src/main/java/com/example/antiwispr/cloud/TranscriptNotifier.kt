package com.example.antiwispr.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.app.MainActivity
import com.example.antiwispr.R
import com.example.antiwispr.data.StoredTranscript

/**
 * Posts a "Transcript ready" notification for a long note whose cloud transcript arrived after
 * the overlay was already gone. Tapping it deep-links into the app at that note's detail screen
 * (MainActivity reads the "note_key" extra). Logs loudly if it can't post so a missing
 * notification is diagnosable from the in-app log.
 */
object TranscriptNotifier {

    const val EXTRA_NOTE_KEY = "note_key"
    // v2 channel at HIGH importance so the notification peeks as a heads-up banner. A channel's
    // importance is locked once created and can only be LOWERED, so we can't just raise the old
    // "transcripts" (DEFAULT) channel — we use a new id and retire the old one.
    private const val CHANNEL_ID = "transcript_ready"
    private const val OLD_CHANNEL_ID = "transcripts"

    fun show(ctx: Context, rec: StoredTranscript) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID) // drop the old DEFAULT-importance channel
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Transcripts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "A voice note you played has finished transcribing."
                    }
                )
            }
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                AppLog.w("[fcm] notifications are disabled for TACIT — can't show 'transcript ready' (grant POST_NOTIFICATIONS). The transcript is in History.")
                return
            }
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_NOTE_KEY, rec.key)
            }
            val pi = PendingIntent.getActivity(
                ctx, rec.key.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = if (rec.chatName.isNotBlank()) "Transcript ready · ${rec.chatName}" else "Transcript ready"
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                // Monochrome "T." monogram; the system tints it. (An adaptive mipmap does not
                // render as a notification status icon.)
                .setSmallIcon(R.drawable.ic_stat_tacit)
                .setContentTitle(title)
                .setContentText(rec.text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(rec.text.take(400)))
                .setPriority(NotificationCompat.PRIORITY_HIGH) // pre-O fallback; channel importance governs on 26+
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(rec.key.hashCode(), n)
            AppLog.i("[fcm] posted 'transcript ready' notification for ${rec.name.ifBlank { rec.key }}.")
        } catch (t: Throwable) {
            AppLog.w("[fcm] failed to post notification: ${t.message}")
        }
    }
}
