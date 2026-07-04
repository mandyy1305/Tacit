package com.example.antiwispr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.antiwispr.cloud.CloudClient
import com.example.antiwispr.cloud.CloudPrefs
import com.example.antiwispr.cloud.SyncEngine
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
    // Transcription/summary run OFF the listen worker so [listening] can release at match time:
    // holding the tap gate through a 2-10s Whisper pass silently ate every play-tap in that
    // window ("overlay doesn't appear"). Single thread — Whisper passes serialize naturally.
    private val transcribeExec = Executors.newSingleThreadExecutor { r -> Thread(r, "transcriber").apply { isDaemon = true } }
    // Chain-mode work (per-part transcription, cache lookups) runs OFF transcribeExec so a long
    // burst never blocks the next play-tap's own transcription.
    private val chainExec = Executors.newSingleThreadExecutor { r -> Thread(r, "chain-mode").apply { isDaemon = true } }
    private val indexer = IndexHolder.get(context)
    @Volatile private var listening = false
    @Volatile private var cancelRequested = false
    @Volatile private var listenThread: Thread? = null
    /** Bumped per play-tap; a superseded session's late transcript/summary must not repaint the new card. */
    @Volatile private var generation = 0
    /** Chat title captured by the a11y service at play-tap (sender attribution). */
    @Volatile private var sessionChatName: String? = null
    /** The matched note's chain (if any) for the current session — target of the chain toggle. */
    @Volatile private var pendingChain: Chain? = null
    /** The matched note file for the current session (summary-state restores on chain toggle-off). */
    @Volatile private var sessionFile: File? = null
    /** Card's "Transcribe all N" toggle — Summary tab yields the chain gist while true. */
    @Volatile private var chainModeEnabled = false
    /** Armed when the transcript is summarizable but nothing is cached — the Summary tab's
     *  first open turns this into a Summarizer.request. Reset per play-tap; generation-stamped. */
    @Volatile private var pendingSummary: PendingSummary? = null

    private data class PendingSummary(val file: File, val transcript: String, val gen: Int)
    /** Set by the accessibility service; invoked on a confirmed match to pause WhatsApp playback. */
    @Volatile var onMatchPause: (() -> Unit)? = null

    init {
        // Dismissing the overlay (tap-away / ✕) also cancels an in-progress listen.
        overlay.onDismiss = { cancel("overlay dismissed") }
        overlay.onToggleChain = { on -> onChainModeToggled(on) }
        overlay.onRequestSummary = { requestNoteSummary() }
    }

    /** Abort an in-progress listen (called on pause, overlay-dismiss, etc.). Interrupts the worker so
     *  it stops within a tick rather than running the full window on ambient noise. */
    fun cancel(reason: String) {
        if (!listening || cancelRequested) return
        AppLog.i("[orchestrator] cancelling listen ($reason).")
        cancelRequested = true
        listenThread?.interrupt()
    }

    fun onPlayTap(durationHintSec: Double?, timestamp: String?, chatName: String? = null) {
        if (listening) { AppLog.i("[orchestrator] already listening — ignoring new tap."); return }
        sessionChatName = chatName
        pendingChain = null
        pendingSummary = null
        sessionFile = null
        chainModeEnabled = false
        // Kick an incremental refresh so a JUST-ARRIVED note gets fingerprinted (+auto-transcribed)
        // in the background; the streaming loop re-queries each tick, so it can match mid-listen.
        indexer.loadOrBuild { AppLog.i(it) }
        AppLog.i("[orchestrator] play-tap -> start CONTINUOUS listening (early-stop).")
        // Set state synchronously so a fast pause (cancel) that arrives before the worker starts isn't lost.
        listening = true
        cancelRequested = false
        generation++
        overlay.showSpinner("Listening…")
        overlay.setInfo(durationHintSec, timestamp)
        worker.execute { runListen() }
    }

    private fun runListen() {
        // listening/cancelRequested are set synchronously in onPlayTap (race-free with a fast pause).
        listenThread = Thread.currentThread()
        var usedMicFgs = false
        try {
            if (cancelRequested) { overlay.dismiss(); return }
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
                if (cancelRequested) break
                Thread.sleep(TICK_MS)
                if (cancelRequested) break
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

                if (top != null && IndexConfig.confident(top, second)) {
                    if (top.fileId == lastWinner) streak++ else { streak = 1; lastWinner = top.fileId }
                    if (streak >= CONFIRM_TICKS) {
                        // A tap-away/cancel can land DURING this tick's compute (no sleep, no
                        // interruption point). Without this check we'd auto-pause WhatsApp and
                        // transcribe into a dismissed card — full pipeline, invisible overlay.
                        if (cancelRequested) break
                        finalizeMatch(top, elapsed)
                        return
                    }
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
            if (cancelRequested) {
                AppLog.i("[orchestrator] listen cancelled — stopping capture, dismissing overlay.")
                overlay.dismiss()
            } else {
                overlay.setStatus("stopped (%.1fs) — no usable capture / index.".format(elapsed))
                overlay.setTranscript("[no confident match]")
            }
        } catch (e: InterruptedException) {
            AppLog.i("[orchestrator] listen interrupted (cancelled) — dismissing overlay.")
            overlay.dismiss()
        } catch (t: Throwable) {
            // Catch Throwable (not just Exception) so an OutOfMemoryError etc. is contained here and
            // does NOT kill the shared process (which would take the accessibility service down with it).
            AppLog.e("[orchestrator] listen loop error: ${t.javaClass.simpleName}: ${t.message}", t)
            try { overlay.setTranscript("[matching error — try again]") } catch (_: Throwable) {}
        } finally {
            if (usedMicFgs) MicCaptureService.stop(appContext) // tear down the mic FGS + AudioRecord
            listenThread = null
            Thread.interrupted() // clear any pending interrupt so the pooled worker thread is clean
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
        overlay.setCandidates(listOf(cand), true) // show result + mark card sticky BEFORE the pause click
        if (Toggles.pauseOnMatch && !cancelRequested) {
            AppLog.i("[orchestrator] match confirmed — pausing WhatsApp playback.")
            onMatchPause?.invoke() // pause click lands outside the overlay; sticky keeps the card up
        }

        // Capture is over. Hand transcription/summary to their own thread and return, so the
        // listen worker's `finally` releases [listening] NOW — the next play-tap starts a fresh
        // session immediately instead of being silently ignored for the whole Whisper pass.
        val gen = generation
        transcribeExec.execute { resolveTranscriptAndSummary(cand, gen) }
    }

    /** Runs on [transcribeExec]. [gen] guards a superseded session from repainting the new card. */
    private fun resolveTranscriptAndSummary(cand: CandidateFile, gen: Int) {
        val live = { gen == generation }
        val transcript: String
        val cached = Transcripts.get(appContext).find(cand.file)
        if (cached != null) {
            AppLog.i("[transcripts] cache hit for ${cand.name} — instant.")
            transcript = cached
            // Stamp the chat name onto an already-cached record if we just learned it.
            sessionChatName?.let { Transcripts.get(appContext).put(cand.file, cached, it) }
        } else {
            if (live()) overlay.setTranscript("transcribing…") // real ASR takes a few seconds (+ one-time model load)
            // Cloud→local routing, caching and sync all live in TranscribeRouter (shared
            // with chain summarization).
            transcript = TranscribeRouter.transcribe(appContext, cand.file, sessionChatName)
        }
        AppLog.i("[orchestrator] transcript => $transcript")
        if (!live()) {
            AppLog.i("[orchestrator] session superseded by a newer play-tap — transcript cached, card untouched.")
            return
        }

        sessionFile = cand.file

        // Summary disposition — LAZY: never auto-generate. Cached → instant READY; summarizable →
        // arm pendingSummary and leave summaryState at NONE (idle); else → settings hint.
        // Decided BEFORE setTranscript() flips the card to TRANSCRIPT and makes the Summary tab
        // tappable, so a fast tab-tap can never observe an unarmed pendingSummary.
        if (!transcript.startsWith("[")) {
            val cachedSummary = Transcripts.get(appContext).entry(cand.file)?.summary.orEmpty()
            when {
                cachedSummary.isNotEmpty() -> overlay.setSummaryReady(cachedSummary)
                Summarizer.canSummarize(appContext) ->
                    pendingSummary = PendingSummary(cand.file, transcript, gen)
                else -> overlay.setSummaryUnavailable()
            }
        }
        overlay.setTranscript(transcript)

        // Chain detection: does this note belong to a burst? Toggle-first — the card offers
        // "Transcribe all N"; both tabs go burst-wide only when the user flips it on.
        val chain = try { Chains.chainFor(appContext, cand.file) } catch (t: Throwable) {
            AppLog.w("[chains] detection failed (non-fatal): ${t.message}"); null
        }
        pendingChain = chain
        if (chain != null && gen == generation) {
            AppLog.i("[chains] ${cand.name} is part ${chain.partIndexOf(cand.file)} of ${chain.size} (${chain.id}).")
            overlay.setChainInfo(chain.partIndexOf(cand.file), chain.size)
        }
    }

    /** Overlay's Summary tab, first open in the current mode. Lazy: single-note summary, or the
     *  chain gist when the "Transcribe all N" toggle is on. Invoked on the main thread (Compose
     *  tap); the actual work runs off-main. NOT one-shot — the chain toggle resets the tab to
     *  idle, so the same session can legitimately request each flavor once; duplicate taps are
     *  deduped by the card's state guard and the summarizers' in-flight sets. */
    private fun requestNoteSummary() {
        val gen = generation
        if (chainModeEnabled) {
            val chain = pendingChain ?: return
            overlay.setChainSummaryGenerating()
            ChainSummarizer.request(appContext, chain, sessionChatName ?: chain.chatName) { raw ->
                // Only paint if this session is still current AND still in chain mode.
                if (gen == generation && chainModeEnabled) overlay.setChainSummaryReady(raw)
            }
            return
        }
        val p = pendingSummary ?: return  // nothing summarizable, or cached already shown
        if (p.gen != generation) return   // stale tap from a superseded card
        // The summary may have been persisted since the transcript landed (in-app reader,
        // an earlier session's late finish) — cached is still instant, zero compute.
        val cached = Transcripts.get(appContext).entry(p.file)?.summary.orEmpty()
        if (cached.isNotEmpty()) { overlay.setSummaryReady(cached); return }
        overlay.setSummaryGenerating()
        val key = Transcripts.keyFor(p.file)
        Summarizer.request(appContext, key, p.transcript) { raw ->
            if (!raw.startsWith("[")) {
                Transcripts.get(appContext).putSummary(key, raw) // persist regardless of overlay
                SyncEngine.requestSync(appContext)
            }
            if (p.gen == generation && !chainModeEnabled) overlay.setSummaryReady(raw)
        }
    }

    /** Card's "Transcribe all N" toggle. Chain mode ON: kick per-part transcription and point the
     *  Summary tab at the chain gist (cached → instant, else idle until tapped). OFF: restore the
     *  single-note summary state. Store lookups run on [chainExec] (first get() reads disk). */
    private fun onChainModeToggled(on: Boolean) {
        val chain = pendingChain ?: return
        val gen = generation
        chainModeEnabled = on
        overlay.setChainMode(on)
        chainExec.execute {
            if (gen != generation) return@execute
            if (on) {
                val cachedGist = ChainSummaries.get(appContext).find(chain.id)
                if (cachedGist != null) overlay.setChainSummaryReady(cachedGist.raw)
                else overlay.setSummaryIdle()
                transcribeChainParts(chain, gen)
            } else {
                val cachedSingle = sessionFile
                    ?.let { Transcripts.get(appContext).entry(it)?.summary.orEmpty() }.orEmpty()
                when {
                    cachedSingle.isNotEmpty() -> overlay.setSummaryReady(cachedSingle)
                    Summarizer.canSummarize(appContext) -> overlay.setSummaryIdle()
                    else -> overlay.setSummaryUnavailable()
                }
            }
        }
    }

    /** Runs on [chainExec]. Paints cached member transcripts instantly, then transcribes the
     *  missing ones in order (cloud→local via the shared router — they land in history too),
     *  repainting after each. Bails between parts if the session or the mode moved on. */
    private fun transcribeChainParts(chain: Chain, gen: Int) {
        val store = Transcripts.get(appContext)
        val parts = arrayOfNulls<String>(chain.size)
        for ((i, f) in chain.files.withIndex()) parts[i] = store.find(f)
        if (gen != generation) return
        overlay.setChainParts(parts.toList())
        for ((i, f) in chain.files.withIndex()) {
            if (parts[i] != null) continue
            if (gen != generation || !chainModeEnabled) return
            parts[i] = if (!f.exists()) "[this note isn't on this phone]" else try {
                TranscribeRouter.transcribe(appContext, f, sessionChatName ?: chain.chatName)
            } catch (t: Throwable) {
                AppLog.w("[chains] part ${i + 1} transcription failed: ${t.message}")
                "[transcription error: ${t.message}]"
            }
            if (gen == generation) overlay.setChainParts(parts.toList())
        }
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
