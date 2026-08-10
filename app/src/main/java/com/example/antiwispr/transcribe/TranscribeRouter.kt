package com.example.antiwispr.transcribe

import android.content.Context
import com.example.antiwispr.cloud.CloudClient
import com.example.antiwispr.cloud.CloudPrefs
import com.example.antiwispr.cloud.SyncEngine
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.data.Transcripts
import com.example.antiwispr.data.VoiceNotes
import java.io.File

/**
 * The one place that turns a voice-note FILE into a transcript: cloud (Sarvam via
 * tacit-cloud) when signed in + enabled, on-device Whisper otherwise; caches the result
 * (with the chat name when known) and nudges sync. Used by the play-tap pipeline and by
 * chain summarization. Blocking — worker threads only.
 */
object TranscribeRouter {

    /** Returned when the cloud accepted the note as an async batch job (it exceeded the
     *  synchronous cap): there is no text yet — the finished transcript arrives via sync +
     *  an FCM push. Starts with '[' so no caller ever caches it; the wording is safe to
     *  paint on the overlay card as-is. */
    const val PROCESSING = "[Reading this long note in the cloud. It will be ready in a moment.]"

    fun transcribe(context: Context, file: File, chatName: String? = null, force: Boolean = false): String {
        val ctx = context.applicationContext
        // Cache wins over current STT options: changing mode/language never re-transcribes
        // old notes. [force] (the reader's re-transcribe action) bypasses the cache so the
        // note gets a fresh pass with the CURRENT settings.
        if (!force) Transcripts.get(ctx).find(file)?.let { return it }

        var cloudText: String? = null
        if (CloudClient.ready(ctx) && CloudPrefs.cloudTranscription(ctx)) {
            when (val cloud = CloudClient.transcribe(ctx, file, chatName)) {
                is CloudClient.SttResult.Processing ->
                    // The job is running server-side; a local pass now would only produce a
                    // second transcript for the batch result to overwrite.
                    return PROCESSING
                is CloudClient.SttResult.Text -> {
                    AppLog.i("[cloud] transcribed ${file.name} via Sarvam.")
                    cloudText = cloud.text
                }
                null -> {} // cloud failed (already logged) — fall through to local
            }
        }

        val transcript = cloudText ?: try {
            TranscriberHolder.get(ctx).transcribe(file)
        } catch (t: Throwable) {
            AppLog.e("[transcribe] threw for ${file.name}: ${t.message}", t)
            "[transcription error: ${t.message}]"
        }

        if (!transcript.startsWith("[")) {
            // A forced re-transcription replaces the text, so the old summary is stale — drop it
            // (it regenerates lazily on the next Summary-tab open). Measure duration once here so
            // cards and the reader can show "0:42" (persisted; -1 falls back to unknown).
            Transcripts.get(ctx).put(
                file, transcript, chatName.orEmpty(), keepSummary = !force,
                source = if (cloudText != null) "cloud" else "local",
                durationSec = VoiceNotes.readDurationSec(file),
            )
            SyncEngine.requestSync(ctx)
        }
        return transcript
    }
}
