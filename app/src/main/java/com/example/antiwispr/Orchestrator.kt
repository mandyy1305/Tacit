package com.example.antiwispr

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Glue for the play-tap flow:
 *   (pause handled by the a11y service) -> show overlay spinner ->
 *   ProjectionService.captureWindow(~6s) -> matcher STUB -> transcriber STUB -> render.
 *
 * The capture blocks in real time, so it runs on a worker thread; overlay updates are posted
 * back to the main thread.
 */
class Orchestrator(context: Context, private val overlay: OverlayController) {

    companion object {
        // 8 s gives the fingerprint room to align (capture starts ~at the play tap, file from 0:00).
        const val CAPTURE_SECONDS = 8.0
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "orchestrator").apply { isDaemon = true } }

    private val matcher: VoiceNoteMatcher = RealVoiceNoteMatcher(context.applicationContext)
    private val transcriber: Transcriber = StubTranscriber()

    fun onPlayTap(durationHintSec: Double?, timestamp: String?) {
        AppLog.i("[orchestrator] play-tap -> spinner up; will capture ${CAPTURE_SECONDS}s.")
        overlay.showSpinner("Reading voice note…")
        overlay.setInfo(durationHintSec, timestamp)

        worker.execute {
            val src = ProjectionService.source
            if (src == null || !src.isSessionActive) {
                AppLog.w("[orchestrator] NO active projection session — cannot capture. Start the session in the app first.")
                overlay.setTranscript("No capture — projection session not active.\nOpen the app and start the session.")
                return@execute
            }
            val pcm = src.captureWindow(CAPTURE_SECONDS)
            val rms = rms(pcm)
            AppLog.i("[orchestrator] captured ${pcm.size} samples, rms=%.5f %s".format(
                rms, if (rms < 0.0005) "(≈silent!)" else "(signal)"))

            val candidates = matcher.match(pcm, durationHintSec, timestamp)

            val chosen = candidates.firstOrNull()
            val second = candidates.getOrNull(1)
            val confident = chosen != null && chosen.score >= RealVoiceNoteMatcher.MIN_ALIGNED &&
                    (second == null || chosen.score >= RealVoiceNoteMatcher.MARGIN_RATIO * maxOf(second.score, 1.0))
            overlay.setCandidates(candidates, confident)

            // Prefer the cached bubble total (durationHintSec). Only fall back to the matched
            // file's metadata duration when we had no bubble duration for this note.
            if (durationHintSec == null) {
                val fileDur = chosen?.durationSec?.takeIf { it >= 0 }
                if (fileDur != null) {
                    AppLog.i("[orchestrator] no bubble duration; showing matched file's %.0fs.".format(fileDur))
                    overlay.setInfo(fileDur, timestamp)
                }
            }

            // Only transcribe a CONFIDENT acoustic match — otherwise we'd transcribe the wrong note.
            val transcript = if (confident && chosen != null) {
                AppLog.i("[orchestrator] confident match ${chosen.name} (score=%.0f) -> transcribe.".format(chosen.score))
                transcriber.transcribe(chosen.file)
            } else {
                AppLog.w("[orchestrator] no confident match -> not transcribing.")
                "[no confident match — not transcribing]"
            }
            AppLog.i("[orchestrator] transcript => $transcript")
            overlay.setTranscript(transcript)
        }
    }

    private fun rms(x: ShortArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) { val f = v / 32768.0; s += f * f }
        return sqrt(s / x.size)
    }
}
