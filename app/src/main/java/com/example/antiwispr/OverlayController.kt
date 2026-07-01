package com.example.antiwispr

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Manages the SYSTEM_ALERT_WINDOW overlays:
 *  - a floating RESULT CARD (spinner -> duration/timestamp + candidate list + transcript),
 *    dismissable by tap-away (ACTION_OUTSIDE) or its ✕.
 *  - a scrollable DEBUG DUMP overlay that accumulates accessibility node dumps.
 *
 * All window operations are marshalled to the main thread, so any component can call from
 * any thread. If overlay permission isn't granted, calls log and no-op.
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density

    // result card
    private var card: View? = null
    private var spinner: ProgressBar? = null
    private var infoTv: TextView? = null
    private var candTv: TextView? = null
    private var transcriptTv: TextView? = null
    private var bannerRow: LinearLayout? = null
    private var bannerText: TextView? = null
    @Volatile private var onShareAction: (() -> Unit)? = null

    private fun dp(v: Int) = (v * density).roundToInt()

    private fun canDraw(): Boolean {
        val ok = Settings.canDrawOverlays(ctx)
        if (!ok) AppLog.w("[overlay] SYSTEM_ALERT_WINDOW not granted — cannot draw overlay.")
        return ok
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---- result card ------------------------------------------------------------

    fun showSpinner(message: String) = onMain {
        if (!canDraw()) return@onMain
        if (card == null) buildCard()
        spinner?.visibility = View.VISIBLE
        bannerRow?.visibility = View.GONE // default hidden; mic path re-shows it
        infoTv?.text = message
        candTv?.text = "candidates: …"
        transcriptTv?.text = "transcript: …"
        AppLog.i("[overlay] showing result card: \"$message\"")
    }

    /** Warn that we're on the mic fallback (no screen share) and offer a Share-screen button. */
    fun showMicFallbackBanner(onShare: () -> Unit) = onMain {
        if (!canDraw()) return@onMain
        if (card == null) buildCard()
        onShareAction = onShare
        bannerText?.text = "⚠ Screen not shared — using mic (lower accuracy)."
        bannerRow?.visibility = View.VISIBLE
    }

    fun clearBanner() = onMain { bannerRow?.visibility = View.GONE }

    fun setInfo(durationSec: Double?, timestamp: String?) = onMain {
        val d = durationSec?.let { "%.0fs".format(it) } ?: "?"
        infoTv?.text = "duration=$d   timestamp=${timestamp ?: "?"}"
    }

    /** Live one-line status during continuous listening (keeps the spinner spinning). */
    fun setStatus(text: String) = onMain {
        if (!canDraw()) return@onMain
        if (card == null) buildCard()
        spinner?.visibility = View.VISIBLE
        candTv?.text = text
    }

    fun setCandidates(candidates: List<CandidateFile>, confident: Boolean) = onMain {
        spinner?.visibility = View.GONE
        if (candidates.isEmpty()) { candTv?.text = "match: (no candidates)"; return@onMain }
        val verdict = if (confident) "✅ match" else "⚠ uncertain"
        val header = "$verdict — compared ${candidates.size} candidate(s) (fp hits):"
        candTv?.text = header + "\n" + candidates.take(6).mapIndexed { i, c ->
            val sc = if (c.score.isNaN()) "?" else "%.0f".format(c.score)
            "${if (i == 0) "▶" else "•"} ${c.name}  $sc  (%.0fs)".format(c.durationSec)
        }.joinToString("\n")
    }

    fun setTranscript(text: String) = onMain {
        spinner?.visibility = View.GONE
        transcriptTv?.text = "transcript (STUB):\n$text"
    }

    fun dismiss() = onMain {
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
        AppLog.i("[overlay] result card dismissed.")
    }

    private fun buildCard() {
        val pad = dp(14)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21E1E22.toInt())
                setStroke(dp(1), 0xFF3A3A40.toInt())
            }
        }

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(ctx).apply {
            text = "Voice note"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "  ✕  "
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener { dismiss() }
        })
        root.addView(header)

        // Mic-fallback warning banner + Share-screen button (hidden unless on the mic path).
        val banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        val bt = TextView(ctx).apply {
            text = "⚠ Screen not shared — using mic (lower accuracy)."
            setTextColor(0xFFFFC107.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val shareBtn = TextView(ctx).apply {
            text = " Share screen "
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2E7D32.toInt()) }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onShareAction?.invoke() }
        }
        banner.addView(bt)
        banner.addView(shareBtn)
        root.addView(banner)
        bannerRow = banner
        bannerText = bt

        spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(dp(28), dp(28))
            lp.topMargin = dp(8); lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        root.addView(spinner)

        infoTv = textRow("Reading voice note…")
        root.addView(infoTv)
        candTv = textRow("candidates: …")
        root.addView(candTv)
        transcriptTv = textRow("transcript: …")
        root.addView(transcriptTv)

        val lp = WindowManager.LayoutParams(
            minOf(dp(340), (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }
        // tap-away to dismiss
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false
        }
        try {
            wm.addView(root, lp)
            card = root
        } catch (e: Exception) {
            AppLog.e("[overlay] addView(card) failed: ${e.message}", e)
        }
    }

    private fun textRow(initial: String) = TextView(ctx).apply {
        text = initial
        setTextColor(0xFFE6E6E6.toInt())
        textSize = 13f
        setPadding(0, dp(4), 0, dp(4))
    }
}
