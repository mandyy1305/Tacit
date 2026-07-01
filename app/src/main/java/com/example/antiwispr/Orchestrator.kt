package com.example.antiwispr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Continuous-listen orchestrator (Shazam-style). On a play-tap it marks the capture position and then
 * repeatedly reads the GROWING capture, fingerprints it, and queries the in-RAM inverted index — no
 * per-query decoding. It stops the moment the match is confident for [CONFIRM_TICKS] consecutive ticks
 * (early stop), or gives up at [MAX_LISTEN]. Then it transcribes (stub) the confident winner.
 */
class Orchestrator(context: Context, private val overlay: OverlayController) {

    companion object {
        const val SR = 16000
        const val TICK_MS = 700L
        const val MIN_LISTEN = 1.5   // don't judge before this much audio
        const val MAX_LISTEN = 12.0  // hard give-up
        const val CONFIRM_TICKS = 2  // consecutive confident ticks (same winner) before stopping
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "orchestrator").apply { isDaemon = true } }
    private val indexer = IndexHolder.get(context)
    private val transcriber: Transcriber = WhisperTranscriber(context)
    @Volatile private var listening = false

    fun onPlayTap(durationHintSec: Double?, timestamp: String?) {
        if (listening) { AppLog.i("[orchestrator] already listening — ignoring new tap."); return }
        AppLog.i("[orchestrator] play-tap -> start CONTINUOUS listening (early-stop).")
        overlay.showSpinner("Listening…")
        overlay.setInfo(durationHintSec, timestamp)
        worker.execute { runListen() }
    }

    private fun runListen() {
        listening = true
        var usedMicFgs = false
        try {
            val proj = ProjectionService.source
            val src: AudioWindowSource
            if (proj != null && proj.isSessionActive) {
                src = proj
                overlay.clearBanner()
            } else if (Toggles.micFallbackEnabled && hasRecordAudio()) {
                // Mic must run inside a microphone-type foreground service, or Android mutes it (background).
                AppLog.w("[orchestrator] screen NOT shared — starting MIC FALLBACK (foreground mic service; lower accuracy).")
                overlay.showMicFallbackBanner { launchShareScreen() }
                MicCaptureService.start(appContext)
                var waited = 0
                while (MicCaptureService.source == null && waited < 2500) { Thread.sleep(100); waited += 100 }
                val m = MicCaptureService.source
                if (m == null || !m.isSessionActive) {
                    AppLog.w("[orchestrator] mic foreground service didn't come up (background-mic blocked on this device?).")
                    overlay.setStatus("Mic couldn't start — tap 'Share screen' for capture.")
                    MicCaptureService.stop(appContext)
                    return
                }
                usedMicFgs = true
                src = m
            } else {
                AppLog.w("[orchestrator] no projection session and mic fallback unavailable/off.")
                overlay.setStatus("No capture — grant mic or tap 'Share screen'.")
                overlay.showMicFallbackBanner { launchShareScreen() }
                return
            }
            if (indexer.snapshot == null) AppLog.w("[orchestrator] index not ready (building?) — will keep trying while listening.")

            val mark = src.mark()
            var streak = 0
            var lastWinner = -1
            var elapsed = 0.0

            while (true) {
                Thread.sleep(TICK_MS)
                val pcm = src.readSince(mark)
                elapsed = pcm.size.toDouble() / SR

                if (elapsed < MIN_LISTEN) {
                    overlay.setStatus("listening %.1fs…".format(elapsed))
                    if (elapsed >= MAX_LISTEN) break else continue
                }

                val snap = indexer.snapshot
                if (snap == null) {
                    overlay.setStatus("building index… (%.1fs)".format(elapsed))
                    if (elapsed >= MAX_LISTEN) break else continue
                }

                val capFp = Fingerprinter.fingerprint(FloatArray(pcm.size) { pcm[it] / 32768.0f })
                val ranked = snap.query(capFp)
                val top = ranked.firstOrNull()
                val second = ranked.getOrNull(1)

                AppLog.i("[orchestrator] t=%.1fs capHashes=%d top=%s aligned=%d #2=%d".format(
                    elapsed, capFp.size, top?.name ?: "—", top?.aligned ?: 0, second?.aligned ?: 0))
                overlay.setStatus(
                    if (top != null) "listening %.1fs — leading: ${top.name} (%d)".format(elapsed, top.aligned)
                    else "listening %.1fs… (no match yet)".format(elapsed)
                )

                if (top != null && IndexConfig.confident(top, second, capFp.size, elapsed)) {
                    if (top.fileId == lastWinner) streak++ else { streak = 1; lastWinner = top.fileId }
                    if (streak >= CONFIRM_TICKS) { finalizeMatch(top, elapsed); return }
                } else {
                    streak = 0
                }

                if (elapsed >= MAX_LISTEN) {
                    AppLog.w("[orchestrator] MAX_LISTEN ${MAX_LISTEN}s reached — no confident match.")
                    overlay.setCandidates(ranked.take(6).map { toCandidate(it) }, false)
                    overlay.setTranscript("[no confident match after %.0fs]".format(elapsed))
                    return
                }
            }
            overlay.setStatus("stopped (%.1fs) — no usable capture / index.".format(elapsed))
            overlay.setTranscript("[no confident match]")
        } catch (e: InterruptedException) {
            // session torn down; ignore
        } catch (t: Throwable) {
            // Catch Throwable (not just Exception) so an OutOfMemoryError etc. is contained here and
            // does NOT kill the shared process (which would take the accessibility service down with it).
            AppLog.e("[orchestrator] listen loop error: ${t.javaClass.simpleName}: ${t.message}", t)
            try { overlay.setTranscript("[matching error — try again]") } catch (_: Throwable) {}
        } finally {
            if (usedMicFgs) MicCaptureService.stop(appContext) // tear down the mic FGS + AudioRecord
            listening = false
        }
    }

    private fun hasRecordAudio() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun launchShareScreen() {
        try {
            appContext.startActivity(Intent(appContext, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("[orchestrator] couldn't launch share-screen consent: ${e.message}", e)
        }
    }

    private fun finalizeMatch(top: Scored, elapsed: Double) {
        AppLog.i("[orchestrator] ✅ CONFIDENT after %.1fs: ${top.name} aligned=${top.aligned} -> transcribe.".format(elapsed))
        val cand = toCandidate(top, withDuration = true)
        overlay.setCandidates(listOf(cand), true)

        val cached = Transcripts.get(appContext).find(cand.file)
        val transcript: String
        if (cached != null) {
            AppLog.i("[transcripts] cache hit for ${cand.name} — instant.")
            transcript = cached
        } else {
            overlay.setTranscript("transcribing…") // real ASR takes a few seconds (+ one-time model load)
            transcript = transcriber.transcribe(cand.file)
            if (!transcript.startsWith("[")) Transcripts.get(appContext).put(cand.file, transcript) // don't cache errors/placeholders
        }
        AppLog.i("[orchestrator] transcript => $transcript")
        overlay.setTranscript(transcript)
    }

    private fun toCandidate(s: Scored, withDuration: Boolean = false): CandidateFile {
        val dur = if (withDuration && s.durationSec < 0) readDurationSec(s.file) else s.durationSec
        return CandidateFile(s.file, s.name, dur, s.file.lastModified(), s.aligned.toDouble())
    }

    private fun readDurationSec(f: File): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Exception) { -1.0 } finally { try { mmr.release() } catch (_: Exception) {} }
    }
}
