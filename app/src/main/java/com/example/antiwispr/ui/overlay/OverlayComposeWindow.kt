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
            }
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var host: FrameLayout? = null
    private var owner: OverlayOwner? = null

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
        try {
            wm.addView(frame, layoutParams())
            host = frame
            owner = o
        } catch (e: Exception) {
            o.onRemoved()
            AppLog.e("[overlay] addView(card) failed: ${e.message}", e)
        }
    }

    /** Main thread only. Idempotent. */
    fun remove() {
        // Null the fields BEFORE removeView: its synchronous detach would otherwise trip
        // HostFrame's unexpected-detach self-heal and log a false alarm.
        val h = host
        host = null
        owner?.onRemoved()
        owner = null
        h?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        minOf(dp(364), (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // WindowManager-added windows are SOFTWARE-rendered unless this flag is set
            // (activities get GPU rendering implicitly; service overlays do not). Without
            // it every animation frame — resizes, fades, the equalizer — draws on the CPU.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(78)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
}
