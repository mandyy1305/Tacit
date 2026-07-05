package com.example.antiwispr

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Timeline-scoped backfill: transcribe the untranscribed voice notes of a user-chosen
 * recent period so they become searchable (and show up in History). Deliberately bounded —
 * the user picks the window and sees the count first; there is no whole-library option.
 * Each file goes through [TranscribeRouter] (cloud/local per settings, saved + synced).
 */
object BackfillTranscriber {

    @Volatile var running: Boolean = false
        private set
    @Volatile var progressDone: Int = 0
        private set
    @Volatile var progressTotal: Int = 0
        private set
    @Volatile var status: String = ""
        private set
    @Volatile private var cancelRequested = false

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "backfill").apply { isDaemon = true } }

    /** Untranscribed notes newer than [sinceMs] — the picker's live preview. Lists files; call off-main. */
    fun countCandidates(context: Context, sinceMs: Long): Int =
        candidates(context.applicationContext, sinceMs).size

    private fun candidates(ctx: Context, sinceMs: Long): List<File> {
        val store = Transcripts.get(ctx)
        return VoiceNotes.listOpusFiles()
            .filter { it.lastModified() >= sinceMs && store.find(it) == null }
            .sortedByDescending { it.lastModified() }
    }

    fun start(context: Context, sinceMs: Long) {
        if (running) { AppLog.i("[backfill] already running — ignoring."); return }
        val ctx = context.applicationContext
        running = true
        cancelRequested = false
        progressDone = 0
        progressTotal = 0
        status = "finding notes…"
        exec.execute {
            try {
                val files = candidates(ctx, sinceMs)
                progressTotal = files.size
                AppLog.i("[backfill] ${files.size} note(s) to transcribe.")
                if (files.isEmpty()) { status = "nothing new to transcribe"; return@execute }
                var failed = 0
                for (f in files) {
                    if (cancelRequested) {
                        status = "cancelled at $progressDone of $progressTotal"
                        AppLog.i("[backfill] cancelled by user.")
                        return@execute
                    }
                    status = "transcribing ${progressDone + 1} of $progressTotal…"
                    val text = try {
                        TranscribeRouter.transcribe(ctx, f)
                    } catch (t: Throwable) {
                        AppLog.w("[backfill] ${f.name} threw: ${t.message}")
                        "[${t.message}]"
                    }
                    if (text.startsWith("[")) { failed++; AppLog.w("[backfill] ${f.name}: $text") }
                    progressDone++
                }
                status = if (failed == 0) "done — $progressDone note(s) transcribed"
                else "done — ${progressDone - failed} ok, $failed failed"
                AppLog.i("[backfill] $status")
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        cancelRequested = true
    }
}
