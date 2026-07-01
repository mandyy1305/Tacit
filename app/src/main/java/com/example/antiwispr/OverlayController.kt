package com.example.antiwispr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import com.example.antiwispr.ui.overlay.OverlayComposeWindow
import com.example.antiwispr.ui.overlay.OverlayPhase
import com.example.antiwispr.ui.overlay.OverlayUiState
import com.example.antiwispr.ui.overlay.TacitOverlayCard
import com.example.antiwispr.ui.overlay.TacitOverlayTheme
import com.example.antiwispr.ui.overlay.humanizeNotice
import com.example.antiwispr.ui.overlay.interpretStatus
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

    @Volatile private var onShareAction: (() -> Unit)? = null
    /** Invoked when the user dismisses the card (tap-away / ✕). Orchestrator uses it to cancel a listen. */
    @Volatile var onDismiss: (() -> Unit)? = null
    /** True once the user dismissed the card; blocks a still-running listen from re-creating it. */
    @Volatile private var dismissed = false
    /** When a result is showing, ignore outside-touch dismissal (incl. our own pause-on-match click). */
    @Volatile private var sticky = false

    private var removeFallback: Runnable? = null

    private fun canDraw(): Boolean {
        val ok = Settings.canDrawOverlays(ctx)
        if (!ok) AppLog.w("[overlay] SYSTEM_ALERT_WINDOW not granted — cannot draw overlay.")
        return ok
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---- public API (frozen) ------------------------------------------------------

    fun showSpinner(message: String) = onMain {
        if (!canDraw()) return@onMain
        dismissed = false // new listen session — allow the card again
        sticky = false    // listening is dismissable by tapping away
        cancelRemoveFallback()
        ensureWindow()
        state.value = OverlayUiState(visible = true, phase = OverlayPhase.LISTENING, statusLine = message)
        AppLog.i("[overlay] showing result card: \"$message\"")
    }

    /** Warn that we're on the mic fallback (no screen share) and offer a Share-screen button. */
    fun showMicFallbackBanner(onShare: () -> Unit) = onMain {
        if (!canDraw() || dismissed) return@onMain
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

    /** Live one-line status during continuous listening. */
    fun setStatus(text: String) = onMain {
        if (!canDraw() || dismissed) return@onMain // never resurrect a card the user dismissed mid-listen
        ensureWindow()
        val info = interpretStatus(text)
        state.value = state.value.copy(
            visible = true,
            phase = OverlayPhase.LISTENING,
            statusLine = info.line,
            statusWarn = info.warn,
        )
    }

    fun setCandidates(candidates: List<CandidateFile>, confident: Boolean) = onMain {
        sticky = true // result showing — outside touches (incl. the pause click) must not dismiss it
        state.value = state.value.copy(
            phase = if (confident) OverlayPhase.MATCHED else OverlayPhase.NO_MATCH,
            match = candidates.firstOrNull()?.toMatchInfo(),
        )
    }

    fun setTranscript(text: String) = onMain {
        state.value = when {
            text.startsWith("transcribing") -> state.value.copy(phase = OverlayPhase.TRANSCRIBING)
            text.startsWith("[") -> state.value.copy(phase = OverlayPhase.NOTICE, notice = humanizeNotice(text))
            else -> state.value.copy(phase = OverlayPhase.TRANSCRIPT, transcript = text, copied = false)
        }
    }

    fun dismiss() = onMain {
        dismissed = true
        sticky = false
        state.value = state.value.copy(visible = false) // plays the exit transition
        AppLog.i("[overlay] result card dismissed.")
        onDismiss?.invoke() // synchronous — cancels an in-progress listen immediately
        scheduleRemoveFallback()
    }

    // ---- internals ------------------------------------------------------------------

    private fun ensureWindow() {
        val w = window ?: OverlayComposeWindow(ctx) { if (!sticky) dismiss() }.also { window = it }
        if (!w.isShowing) {
            w.show {
                TacitOverlayTheme {
                    TacitOverlayCard(
                        state = state.value,
                        onClose = { dismiss() },
                        onShare = { onShareAction?.invoke() },
                        onCopy = { copyTranscript() },
                        onExitFinished = { removeWindow() },
                    )
                }
            }
        }
    }

    private fun removeWindow() = onMain {
        cancelRemoveFallback()
        window?.remove()
    }

    /** Hard fallback: remove the window even if the exit transition never reports back. */
    private fun scheduleRemoveFallback() {
        cancelRemoveFallback()
        val r = Runnable { if (dismissed) window?.remove() }
        removeFallback = r
        main.postDelayed(r, 350)
    }

    private fun cancelRemoveFallback() {
        removeFallback?.let { main.removeCallbacks(it) }
        removeFallback = null
    }

    private fun copyTranscript() {
        val text = state.value.transcript ?: return
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Tacit transcript", text))
        state.value = state.value.copy(copied = true)
        main.postDelayed({ state.value = state.value.copy(copied = false) }, 1500)
    }
}
