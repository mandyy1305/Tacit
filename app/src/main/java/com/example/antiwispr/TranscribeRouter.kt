package com.example.antiwispr

import android.content.Context
import com.example.antiwispr.cloud.CloudClient
import com.example.antiwispr.cloud.CloudPrefs
import com.example.antiwispr.cloud.SyncEngine
import java.io.File

/**
 * The one place that turns a voice-note FILE into a transcript: cloud (Sarvam via
 * tacit-cloud) when signed in + enabled, on-device Whisper otherwise; caches the result
 * (with the chat name when known) and nudges sync. Used by the play-tap pipeline and by
 * chain summarization. Blocking — worker threads only.
 */
object TranscribeRouter {

    fun transcribe(context: Context, file: File, chatName: String? = null): String {
        val ctx = context.applicationContext
        Transcripts.get(ctx).find(file)?.let { return it }

        val cloudText = if (CloudClient.ready(ctx) && CloudPrefs.cloudTranscription(ctx)) {
            CloudClient.transcribe(ctx, file)?.also { AppLog.i("[cloud] transcribed ${file.name} via Sarvam.") }
        } else null

        val transcript = cloudText ?: try {
            TranscriberHolder.get(ctx).transcribe(file)
        } catch (t: Throwable) {
            AppLog.e("[transcribe] threw for ${file.name}: ${t.message}", t)
            "[transcription error: ${t.message}]"
        }

        if (!transcript.startsWith("[")) {
            Transcripts.get(ctx).put(file, transcript, chatName.orEmpty())
            SyncEngine.requestSync(ctx)
        }
        return transcript
    }
}
