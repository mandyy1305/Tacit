package com.example.antiwispr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.antiwispr.ui.overlay.NoticeAction
import com.example.antiwispr.ui.overlay.OverlayComposeWindow
import com.example.antiwispr.ui.overlay.OverlayPhase
import com.example.antiwispr.ui.overlay.OverlayUiState
import com.example.antiwispr.ui.overlay.SummaryState
import com.example.antiwispr.ui.overlay.TacitOverlayCard
import com.example.antiwispr.ui.overlay.TacitOverlayTheme
import com.example.antiwispr.ui.overlay.humanizeNotice
import com.example.antiwispr.ui.overlay.interpretStatus
import com.example.antiwispr.ui.overlay.noticeActionFor
import com.example.antiwispr.ui.overlay.toMatchInfo

/**
 * The floating result card over WhatsApp — TACIT's hero UI. Public API is frozen (Orchestrator
 * and the accessibility service call it from any thread); rendering is Compose inside a
 * WindowManager window (see ui/overlay/). One immutable [OverlayUiState] in a single
 * mutableStateOf drives everything — no partial frames.
 *
 * Semantics preserved from the View version:
 *  - tap-away dismisses only while listening (sticky=false); results are sticky (✕ only).
 *  - [dismissed] blocks a still-running listen from resurrecting the card.
 *  - dismiss() fires [onDismiss] synchronously (Orchestrator's cancel must not wait for the
 *    exit animation); only the physical removeView is deferred to the transition end, with a
 *    hard fallback in case the animation never reports.
 */
class OverlayController(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var window: OverlayComposeWindow? = null
    private val state = mutableStateOf(OverlayUiState())
    /** Recent capture amplitudes (0..1), newest last — drives the live listening waveform. */
    private val audioLevels = mutableStateListOf<Float>()

    @Volatile private var onShareAction: (() -> Unit)? = null
    /** Invoked when the user dismisses the card (tap-away / ✕). Orchestrator uses it to cancel a listen. */
    @Volatile var onDismiss: (() -> Unit)? = null
    /** Invoked by the card's "Transcribe all N" chain toggle with the new desired state. */
    @Volatile var onToggleChain: ((Boolean) -> Unit)? = null
    /** Invoked when the user swipes to chain part [index] (0-based); drives lazy per-part transcription. */
    @Volatile var onPartVisible: ((Int) -> Unit)? = null
    /** Invoked when the user opens the Summary tab and no summary exists yet (lazy generation). */
    @Volatile var onRequestSummary: (() -> Unit)? = null
    /** Notice "Try again": retry transcription of the matched note in place. */
    @Volatile var onTryAgain: (() -> Unit)? = null
    /** No-match / matching-error "Listen again": re-arm a fresh capture session. */
    @Volatile var onListenAgain: (() -> Unit)? = null
    /** True once the user dismissed the card; blocks a still-running listen from re-creating it. */
    @Volatile private var dismissed = false
    /** When a result is showing, ignore outside-touch dismissal (incl. our own pause-on-match click). */
    @Volatile private var sticky = false
    /**
     * Monotonic listen-session id, bumped by every showSpinner. A cancelled listen's worker
     * thread calls dismiss() asynchronously; if a NEW play-tap's showSpinner wins the race to
     * the main thread, that stale dismiss must not kill the new card (it would set [dismissed]
     * and silently drop every update of the new session — overlay never appears while the
     * pipeline runs to completion). Captured at call time, verified on the main thread.
     */
    @Volatile private var session = 0

    private var removeFallback: Runnable? = null

    private fun canDraw(): Boolean {
        val ok = Settings.canDrawOverlays(ctx)
        if (!ok) AppLog.w("[overlay] SYSTEM_ALERT_WINDOW not granted — cannot draw overlay.")
        return ok
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Common guard for update calls; LOGS what it drops so a suppressed card is visible in the log. */
    private fun dropUpdate(what: String): Boolean {
        if (!canDraw()) return true
        if (dismissed) {
            AppLog.i("[overlay] $what dropped — card dismissed (session $session).")
            return true
        }
        return false
    }

    // ---- public API (frozen) ------------------------------------------------------

    /**
     * Establish the persistent overlay window NOW, while things are calm (at service-connect),
     * hidden and non-touchable. Adding the window later — on a play-tap right after WhatsApp
     * opened/switched a chat — intermittently yields a layer the compositor never shows (attached,
     * sized, opaque, drawn, yet off-screen). Adding it once here and only toggling content +
     * touchability thereafter means no window is ever added during that bad moment. Idempotent.
     */
    fun warmUp() = onMain {
        if (!canDraw()) return@onMain
        ensureWindow()
        window?.setTouchable(false)
        AppLog.i("[overlay] persistent window established (warm-up).")
    }

    /** Real teardown for a disabled/destroyed service — the persistent window must not leak. */
    fun destroy() = onMain {
        cancelRemoveFallback()
        window?.remove()
        window = null
    }

    fun showSpinner(message: String) = onMain {
        if (!canDraw()) return@onMain
        session++         // new listen session — stale dismisses from older sessions are void
        dismissed = false // allow the card again
        sticky = false    // listening is dismissable by tapping away
        cancelRemoveFallback()
        audioLevels.clear()
        state.value = OverlayUiState(visible = true, phase = OverlayPhase.LISTENING, statusLine = message)
        ensureWindow()             // reuses the persistent window (created at warm-up) — never re-added
        window?.setTouchable(true) // card is up: accept its taps + tap-away
        AppLog.i("[overlay] showing result card: \"$message\" (session $session)")
    }

    /** Warn that we're on the mic fallback (no screen share) and offer a Share-screen button. */
    fun showMicFallbackBanner(onShare: () -> Unit) = onMain {
        if (dropUpdate("mic banner")) return@onMain
        ensureWindow()
        onShareAction = onShare
        state.value = state.value.copy(visible = true, micBanner = true)
    }

    fun clearBanner() = onMain {
        state.value = state.value.copy(micBanner = false)
    }

    fun setInfo(durationSec: Double?, timestamp: String?) {
        // API kept for Orchestrator; the card deliberately shows neither value.
    }

    /** Push a live capture amplitude (0..1) for the listening waveform (~10 Hz, from the audio
     *  thread). Keeps a short rolling history; the newest sample is the right-most bar. */
    fun pushAudioLevel(level: Float) = onMain {
        audioLevels.add(level.coerceIn(0f, 1f))
        while (audioLevels.size > 48) audioLevels.removeAt(0)
    }

    /** Live one-line status during continuous listening. */
    fun setStatus(text: String) = onMain {
        if (dropUpdate("status")) return@onMain // never resurrect a card the user dismissed mid-listen
        ensureWindow()
        val info = interpretStatus(text)
        state.value = state.value.copy(
            visible = true,
            phase = OverlayPhase.LISTENING,
            statusLine = info.line,
            statusWarn = info.warn,
        )
    }

    fun setCandidates(candidates: List<CandidateFile>, confident: Boolean) {
        // Sticky must flip SYNCHRONOUSLY on the calling (worker) thread. If it only flipped
        // inside the posted block, a stray outside-touch in the hop between "match confirmed"
        // and the post landing would tap-away-dismiss the card while finalizeMatch runs to
        // completion — auto-pause + transcript with no overlay ever shown.
        sticky = true
        onMain {
            if (dropUpdate("match result")) return@onMain
            ensureWindow() // a result must never land on a missing window — recreate if needed
            val infos = candidates.map { it.toMatchInfo() }
            state.value = state.value.copy(
                visible = true,
                phase = if (confident) OverlayPhase.MATCHED else OverlayPhase.NO_MATCH,
                match = infos.firstOrNull(),
            )
        }
    }

    /** Show the plain no-match recovery state (replay prompt; screen-share nudge if on the mic). */
    fun showNoMatch() = onMain {
        if (dropUpdate("no match")) return@onMain
        state.value = state.value.copy(phase = OverlayPhase.NO_MATCH)
    }

    /** Provenance of the current result / engine in flight: "local" (on-device) or "cloud". Drives
     *  the SourceMark and the transcribing/summarizing captions. Additive to the frozen API. */
    fun setSource(source: String) = onMain {
        if (dropUpdate("source")) return@onMain
        state.value = state.value.copy(source = source)
    }

    fun setTranscript(text: String) = onMain {
        if (dropUpdate("transcript")) return@onMain
        ensureWindow()
        state.value = when {
            text.startsWith("transcribing") -> state.value.copy(visible = true, phase = OverlayPhase.TRANSCRIBING)
            text.startsWith("[") -> state.value.copy(
                visible = true, phase = OverlayPhase.NOTICE,
                notice = humanizeNotice(text), noticeAction = noticeActionFor(text),
            )
            else -> state.value.copy(visible = true, phase = OverlayPhase.TRANSCRIPT, transcript = text, copied = false)
        }
    }

    /** Summary tab: generation started (shimmer). */
    fun setSummaryGenerating() = onMain {
        if (dropUpdate("summary(generating)")) return@onMain
        ensureWindow()
        state.value = state.value.copy(summaryState = SummaryState.GENERATING)
    }

    /** Summary tab: raw "SUMMARY:/ACTIONS:" text (bracket-prefixed = error, shown as-is).
     *  Clears [OverlayUiState.chainSummary] — a single-note result must not wear the chain label. */
    fun setSummaryReady(raw: String) = onMain {
        if (dropUpdate("summary")) return@onMain
        ensureWindow()
        state.value = state.value.copy(
            summaryState = SummaryState.READY, summaryRaw = raw, chainSummary = false,
        )
    }

    /** Summary tab back to idle (nothing shown, generation not started) — used when the chain
     *  toggle flips what the tab should contain and the new content isn't cached yet. */
    fun setSummaryIdle() = onMain {
        if (dropUpdate("summary(idle)")) return@onMain
        ensureWindow()
        state.value = state.value.copy(summaryState = SummaryState.NONE, chainSummary = false)
    }

    /** Summary tab: model not downloaded — show the settings hint. */
    fun setSummaryUnavailable() = onMain {
        if (dropUpdate("summary(unavailable)")) return@onMain
        ensureWindow()
        state.value = state.value.copy(summaryState = SummaryState.UNAVAILABLE)
    }

    /** The matched note belongs to a burst: "Part [part] of [count]". */
    fun setChainInfo(part: Int, count: Int) = onMain {
        if (dropUpdate("chain info")) return@onMain
        state.value = state.value.copy(chainPart = part, chainCount = count)
    }

    /** Chain mode on/off — the Transcript tab covers the whole burst vs just this note. */
    fun setChainMode(on: Boolean) = onMain {
        if (dropUpdate("chain mode")) return@onMain
        ensureWindow()
        state.value = state.value.copy(chainMode = on)
    }

    /** Per-part transcripts for chain mode (progressive; null entries are still transcribing). */
    fun setChainParts(parts: List<String?>) = onMain {
        if (dropUpdate("chain parts")) return@onMain
        ensureWindow()
        state.value = state.value.copy(chainParts = parts)
    }

    /** Chain gist is being generated (transcribe members + summarize). */
    fun setChainSummaryGenerating() = onMain {
        if (dropUpdate("chain summary(generating)")) return@onMain
        state.value = state.value.copy(summaryState = SummaryState.GENERATING, chainSummary = true)
    }

    /** Chain gist ready — replaces the single-note summary in the Summary tab. */
    fun setChainSummaryReady(raw: String) = onMain {
        if (dropUpdate("chain summary")) return@onMain
        ensureWindow()
        state.value = state.value.copy(
            summaryState = SummaryState.READY, summaryRaw = raw, chainSummary = true,
        )
    }

    fun dismiss() {
        val issuedFor = session // capture on the CALLING thread, before marshalling
        onMain {
            if (issuedFor != session) {
                // A newer showSpinner superseded the session this dismiss was meant for
                // (e.g. a cancelled listen's worker racing the next play-tap). Ignore it.
                AppLog.i("[overlay] stale dismiss (session $issuedFor < $session) — ignored.")
                return@onMain
            }
            dismissed = true
            sticky = false
            audioLevels.clear()
            state.value = state.value.copy(visible = false) // plays the exit transition
            AppLog.i("[overlay] result card dismissed.")
            onDismiss?.invoke() // synchronous — cancels an in-progress listen immediately
            scheduleRemoveFallback()
        }
    }

    // ---- internals ------------------------------------------------------------------

    private fun ensureWindow() {
        val w = window ?: OverlayComposeWindow(ctx) { if (!sticky) dismiss() }.also { window = it }
        if (!w.isShowing) {
            w.show {
                TacitOverlayTheme {
                    TacitOverlayCard(
                        state = state.value,
                        audioLevels = audioLevels,
                        onClose = { dismiss() },
                        onShare = { onShareAction?.invoke() },
                        onCopy = { text -> copyText(text) },
                        onToggleChain = { on -> onToggleChain?.invoke(on) },
                        onPartVisible = { i -> onPartVisible?.invoke(i) },
                        onRequestSummary = { onRequestSummary?.invoke() },
                        onNoticeAction = { action ->
                            when (action) {
                                NoticeAction.FINISH_SETUP -> { launchApp(); dismiss() }
                                NoticeAction.TRY_AGAIN -> onTryAgain?.invoke()
                                NoticeAction.SHARE_SCREEN -> onShareAction?.invoke()
                                NoticeAction.LISTEN_AGAIN -> onListenAgain?.invoke()
                                NoticeAction.NONE -> {}
                            }
                        },
                        onListenAgain = { onListenAgain?.invoke() },
                        // Launching an external app (Maps/dialer/calendar) must drop the card —
                        // the overlay window floats above whatever it just opened. In-place
                        // actions (amount copy) return false and keep it up.
                        onEntityTap = { entity -> if (EntityLauncher.launch(ctx, entity)) dismiss() },
                        // The window is persistent; when the exit animation finishes we don't
                        // remove it, we just make it pass-through again until the next card.
                        onExitFinished = { onMain { cancelRemoveFallback(); window?.setTouchable(false) } },
                    )
                }
            }
        }
    }

    /** Hard fallback: make the window pass-through even if the exit transition never reports back. */
    private fun scheduleRemoveFallback() {
        cancelRemoveFallback()
        val r = Runnable { if (dismissed) window?.setTouchable(false) }
        removeFallback = r
        main.postDelayed(r, 350)
    }

    private fun cancelRemoveFallback() {
        removeFallback?.let { main.removeCallbacks(it) }
        removeFallback = null
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("TACIT note", text))
        state.value = state.value.copy(copied = true)
        main.postDelayed({ state.value = state.value.copy(copied = false) }, 1500)
    }

    /** Open TACIT (for the "Finish setup" notice action); the overlay dismisses after. */
    private fun launchApp() {
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            AppLog.w("[overlay] couldn't open TACIT: ${e.message}")
        }
    }
}
