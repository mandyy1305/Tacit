package com.example.antiwispr.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.antiwispr.AppLog
import kotlin.math.roundToInt

/**
 * Hosts a ComposeView inside a WindowManager overlay (no Activity — added from the
 * accessibility service's application context). The view-tree owners MUST be set on the
 * root BEFORE wm.addView: ComposeView's default windowRecomposer then creates a
 * lifecycle-bound Recomposer by itself — no manual Recomposer wiring.
 *
 * The window is PERSISTENT: added once (while things are calm — see OverlayController.warmUp)
 * and kept for the life of the service. It is NOT re-added per play. Adding a
 * TYPE_APPLICATION_OVERLAY window right after the app underneath changes its own window (e.g.
 * WhatsApp opening/switching a chat) intermittently produces a layer the compositor never shows —
 * the window attaches, is full-size, opaque, and even draws, yet never reaches the display. By
 * keeping one window alive and only toggling its content (Compose visibility) + touchability, no
 * window is ever added during that bad moment, so the card shows reliably.
 */
class OverlayComposeWindow(
    private val ctx: Context,
    private val onOutsideTouch: () -> Unit,
) {

    /** Lifecycle + SavedState owner for a windowed ComposeView. Main thread only. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val ssc = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
        fun onShow() {
            ssc.performRestore(null) // must precede leaving INITIALIZED
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun onRemoved() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    /** Catches ACTION_OUTSIDE (FLAG_WATCH_OUTSIDE_TOUCH) before Compose input dispatch. */
    private inner class HostFrame(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // Self-heal: if the system tore this window down without remove() being called,
            // clearing `host` here lets the next update rebuild instead of wedging the
            // overlay invisibly (isShowing would stay true forever).
            if (host === this) {
                AppLog.w("[overlay] window detached unexpectedly — will rebuild on next update.")
                owner?.onRemoved()
                host = null
                owner = null
                params = null
            }
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var host: FrameLayout? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    // Whether the window currently intercepts touches. While the card is hidden it must NOT: an
    // always-present but empty window would otherwise eat taps in its footprint and fire stray
    // ACTION_OUTSIDE dismissals on every screen touch.
    private var touchable = false

    val isShowing: Boolean get() = host != null

    /** Main thread only. No-op when already showing. */
    fun show(content: @Composable () -> Unit) {
        if (host != null) return
        val o = OverlayOwner().also { it.onShow() }
        val frame = HostFrame(ctx).apply {
            // Bleed room for the card's shadow and entrance translation — nothing clips
            // at the window edge, and the window params are never animated.
            val p = dp(12)
            setPadding(p, p, p, p)
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
        }
        frame.addView(
            ComposeView(ctx).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent(content)
            }
        )
        val lp = layoutParams(touchable)
        try {
            wm.addView(frame, lp)
            host = frame
            owner = o
            params = lp
        } catch (e: Exception) {
            o.onRemoved()
            AppLog.e("[overlay] addView(card) failed: ${e.message}", e)
        }
    }

    /**
     * Toggle whether the (persistent) window intercepts touches. Touchable while a card is shown
     * (so its buttons work and tap-away can dismiss); non-touchable while hidden (fully
     * pass-through — no dead-zone, no stray ACTION_OUTSIDE). Main thread only.
     */
    fun setTouchable(value: Boolean) {
        if (touchable == value) return
        touchable = value
        val h = host ?: return
        val p = params ?: return
        p.flags = flagsFor(value)
        try { wm.updateViewLayout(h, p) } catch (e: Exception) {
            AppLog.e("[overlay] updateViewLayout(touchable=$value) failed: ${e.message}", e)
        }
    }

    /** Main thread only. Idempotent. Real teardown (service disabled/destroyed). */
    fun remove() {
        // Null the fields BEFORE removeView: its synchronous detach would otherwise trip
        // HostFrame's unexpected-detach self-heal and log a false alarm.
        val h = host
        host = null
        owner?.onRemoved()
        owner = null
        params = null
        h?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    }

    private fun layoutParams(touchable: Boolean) = WindowManager.LayoutParams(
        minOf(dp(364), (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flagsFor(touchable),
        // FLAG_HARDWARE_ACCELERATED intentionally OMITTED: on this device the GPU overlay layer
        // intermittently failed to composite after being (re-)added. Software rendering composites
        // reliably; the card is small so the CPU-drawn fades/equalizer are fine.
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // The card is pinned ~90 dp down (78 dp base + the 12 dp frame bleed). The Compose content
        // now reserves a 48 dp transparent coach-mark lane ABOVE the card (for floating tooltips
        // that must not clip or cover content), so shift the window up by that lane to keep the
        // visible card exactly where it was: 78 - 48 = 30. Keep in sync with COACH_ROOM in OverlayCard.
        y = dp(30)
    }

    private fun flagsFor(touchable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return if (touchable) {
            // No FLAG_WATCH_OUTSIDE_TOUCH by design: a stray tap while scrolling WhatsApp must not
            // kill a listen the user explicitly started. FLAG_NOT_FOCUSABLE already implies
            // NOT_TOUCH_MODAL, so touches outside the card still pass straight through to WhatsApp;
            // the card now closes only via the close button, a swipe-up, or pausing the note.
            base
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
}
