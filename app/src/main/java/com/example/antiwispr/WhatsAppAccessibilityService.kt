package com.example.antiwispr

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bound to com.whatsapp (see res/xml/accessibility_service_config.xml). Two jobs:
 *
 *  1) DIAGNOSTIC DUMP (Toggles.diagnosticMode): on any click, dump clicked + children +
 *     parent + siblings AND the resolved message-row subtree to logcat + the debug overlay.
 *
 *  2) PLAY DETECTION: the dump confirmed the voice-note control is an ImageButton with
 *     id=com.whatsapp:id/control_btn. We detect on THAT id — NOT on "any clickable node", which
 *     previously false-fired on stickers/rows because their parent is the whole ListView full of
 *     message timestamps. The contentDescription is read AFTER the click toggles the button, so
 *     it's inverted: "Pause voice message" means playback just STARTED (capture), "Play voice
 *     message" means it just STOPPED (skip). Duration/timestamp come from the bubble's row subtree.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        // Stable id of the voice-note play/pause button (from the on-device node dump).
        const val CONTROL_BTN_ID = "com.whatsapp:id/control_btn"
    }

    private enum class Control { NONE, STARTED, STOPPED }

    private lateinit var overlay: OverlayController
    private lateinit var orchestrator: Orchestrator

    private val timeRegex = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Total durations captured while a note is IDLE (button shows "Play" => the time on screen is
    // the TOTAL, not an elapsed counter), keyed by the bubble's send timestamp. This is how we get
    // a reliable duration to feed matching, since by play-tap time the field is already counting up.
    private val durationByTimestamp = LinkedHashMap<String, String>()
    @Volatile private var lastScanUptime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(applicationContext)
        orchestrator = Orchestrator(applicationContext, overlay)
        AppLog.i("[a11y] SERVICE CONNECTED — bound to com.whatsapp.")
        AppLog.i("[a11y] play control = id '$CONTROL_BTN_ID' (or desc contains 'voice message').")
        AppLog.i("[a11y] toggles: diagnostic=${Toggles.diagnosticMode}, pauseOnPlay=${Toggles.pauseOnPlay}, orchestration=${Toggles.orchestrationEnabled}")
    }

    override fun onInterrupt() { AppLog.w("[a11y] onInterrupt()") }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppLog.w("[a11y] SERVICE UNBOUND (disabled in settings or stopped).")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Use these (throttled) to cache the total duration of any IDLE voice notes
                    // currently on screen, before they're played and the field becomes elapsed.
                    maybeCacheDurations()
                }
                else -> {}
            }
        } catch (e: Exception) {
            // Never let a node-tree hiccup (stale/sealed AccessibilityNodeInfo, etc.) kill the
            // whole handler — that would silently swallow real play taps.
            AppLog.e("[a11y] event handling threw (non-fatal): ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun handleClick(event: AccessibilityEvent) {
        val src = event.source
        if (src == null) {
            AppLog.w("[a11y] TYPE_VIEW_CLICKED but source node is null (not retrievable).")
            return
        }
        AppLog.i("[a11y] ── CLICK @ ${clockFmt.format(Date())} pkg=${event.packageName} " +
                "id=${src.viewIdResourceName} desc=\"${src.contentDescription}\" cls=${shortCls(src.className)} ──")

        // Diagnostics must NOT be able to block detection — isolate it.
        if (Toggles.diagnosticMode) {
            try { dumpContext(src) } catch (e: Exception) { AppLog.e("[a11y] node dump failed (non-fatal): ${e.message}", e) }
        }

        val control = classifyControl(src)
        AppLog.i("[a11y] classify => $control")
        when (control) {
            Control.NONE -> { AppLog.i("[a11y] not a voice-note control — ignoring, no overlay."); return }
            Control.STOPPED -> { AppLog.i("[a11y] playback PAUSED/STOPPED (post-click desc shows 'Play') — skipping capture/overlay."); return }
            Control.STARTED -> { AppLog.i("[a11y] ✅ playback STARTED (post-click desc shows 'Pause') — capturing.") }
        }

        // Extraction is best-effort. IMPORTANT: once playback starts, the bubble's time field
        // becomes a LIVE elapsed counter (0:00 → total). We read it post-click, so it's the
        // elapsed value, NOT the total — we deliberately do NOT use it as the duration. The total
        // duration comes from the matched file's metadata downstream. The bubble TIMESTAMP (send
        // time) is static, so it's still usable.
        var timestamp: String? = null
        var durationSec: Double? = null
        try {
            val row = findMessageRow(src)
            AppLog.i("[a11y] bubble ${describe(row, "ROW")}")
            val times = collectTimeTexts(row, 0, 8)
            AppLog.i("[a11y] time-like texts in bubble: $times (smallest is the LIVE elapsed counter, not total duration)")
            timestamp = if (times.size >= 2) times.maxByOrNull { parseTimeToSeconds(it) ?: -1.0 } else null
            if (timestamp != null) AppLog.i("[a11y] TIMESTAMP (provisional) = $timestamp")
            else AppLog.w("[a11y] TIMESTAMP not distinguishable (need ≥2 time texts) or not exposed.")

            // Use the TOTAL duration we cached for this bubble while it was idle (pre-play).
            val cached = timestamp?.let { durationByTimestamp[it] }
            durationSec = cached?.let { parseTimeToSeconds(it) }
            if (durationSec != null) AppLog.i("[a11y] DURATION = $cached (${durationSec}s), cached pre-play by timestamp $timestamp.")
            else AppLog.w("[a11y] no cached pre-play duration for timestamp=$timestamp — matcher will fall back to file metadata.")

            logRangeInfo(row) // if a seekbar exposes the total via RangeInfo, surface it for later use
        } catch (e: Exception) {
            AppLog.e("[a11y] timestamp/duration extraction failed (non-fatal): ${e.message}", e)
        }

        if (Toggles.pauseOnPlay) {
            val ok = src.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i("[a11y] pause toggle: performAction(ACTION_CLICK) returned $ok (confirm it actually paused on screen).")
        }

        if (Toggles.orchestrationEnabled) {
            orchestrator.onPlayTap(durationSec, timestamp)
        } else {
            AppLog.i("[a11y] orchestration disabled — skipping capture/match/transcribe.")
        }
    }

    // ---- detection --------------------------------------------------------------

    private fun classifyControl(n: AccessibilityNodeInfo): Control {
        val id = n.viewIdResourceName ?: ""
        val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.US)
        val isControl = id == CONTROL_BTN_ID ||
                id == "com.whatsapp:id/control_button_container" ||  // click can land on the wrapper
                desc.contains("voice message") || desc.contains("voice note") || desc.contains("push to talk")
        if (!isControl) return Control.NONE
        // IMPORTANT: TYPE_VIEW_CLICKED fires AFTER WhatsApp toggles the button, and we read the
        // LIVE node — so the contentDescription is the POST-click state, i.e. inverted from the
        // icon you tapped. Tapping ▶ to START playback yields desc="Pause voice message"; tapping
        // ⏸ to PAUSE yields desc="Play voice message". Map by what just happened, not the word.
        return when {
            desc.contains("pause") -> Control.STARTED // now shows "Pause" => playback just STARTED
            desc.contains("play") -> Control.STOPPED  // now shows "Play"  => playback just PAUSED/STOPPED
            else -> Control.STARTED                   // control button, ambiguous desc → assume started
        }
    }

    /** Climb to the message-row container (direct child of the chat ListView/RecyclerView). */
    private fun findMessageRow(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur = node
        var hops = 0
        while (hops < 12) {
            val p = cur.parent ?: return cur
            val pid = p.viewIdResourceName
            val pcls = p.className?.toString() ?: ""
            if (pid == "android:id/list" || pcls.endsWith("ListView") || pcls.endsWith("RecyclerView")) return cur
            cur = p
            hops++
        }
        return cur
    }

    // ---- diagnostics ------------------------------------------------------------

    private fun dumpContext(node: AccessibilityNodeInfo) {
        val sb = StringBuilder()
        sb.appendLine("── NODE DUMP @ ${clockFmt.format(Date())} ──")
        sb.appendLine(describe(node, "CLICKED"))
        for (i in 0 until node.childCount) sb.appendLine(describe(node.getChild(i), "  CLICKED.child[$i]"))

        val parent = node.parent
        sb.appendLine(describe(parent, "PARENT"))
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val sib = parent.getChild(i)
                sb.appendLine(describe(sib, "  SIBLING[$i]"))
                if (sib != null) for (j in 0 until sib.childCount)
                    sb.appendLine(describe(sib.getChild(j), "    SIBLING[$i].child[$j]"))
            }
        }

        // Full bubble row subtree — this is where duration/timestamp text nodes live.
        val row = findMessageRow(node)
        sb.appendLine("ROW SUBTREE (the whole message bubble):")
        dumpSubtree(row, sb, 0, 5)

        val dump = sb.toString().trimEnd()
        AppLog.i(dump) // goes to logcat + the app's on-screen log (no floating overlay)
    }

    private fun dumpSubtree(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (n == null || depth > maxDepth) return
        sb.appendLine(describe(n, "  ".repeat(depth + 1) + "L$depth"))
        for (i in 0 until n.childCount) dumpSubtree(n.getChild(i), sb, depth + 1, maxDepth)
    }

    private fun describe(n: AccessibilityNodeInfo?, tag: String): String {
        if (n == null) return "$tag: <null>"
        val r = Rect(); n.getBoundsInScreen(r)
        return "$tag cls=${shortCls(n.className)} id=${n.viewIdResourceName} " +
                "desc=\"${n.contentDescription}\" text=\"${n.text}\" bounds=$r " +
                "clk=${n.isClickable} chk=${n.isCheckable}/${n.isChecked}"
    }

    private fun shortCls(cs: CharSequence?): String {
        val s = cs?.toString() ?: return "?"
        val i = s.lastIndexOf('.')
        return if (i >= 0 && i < s.length - 1) s.substring(i + 1) else s
    }

    // ---- extraction helpers -----------------------------------------------------

    private fun collectTimeTexts(root: AccessibilityNodeInfo?, startDepth: Int, maxDepth: Int): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > maxDepth) return
            for (cs in listOf(n.text, n.contentDescription)) {
                val s = cs?.toString() ?: continue
                for (m in timeRegex.findAll(s)) out.add(m.value)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, startDepth)
        return out.toList()
    }

    private fun parseTimeToSeconds(t: String): Double? {
        val p = t.split(":")
        if (p.size != 2) return null
        val m = p[0].toIntOrNull() ?: return null
        val s = p[1].toIntOrNull() ?: return null
        return (m * 60 + s).toDouble()
    }

    /** Logs any seekbar/progress node's RangeInfo — its max sometimes encodes the total duration,
     *  which would be a stable (non-elapsing) source if we want bubble-side duration later. */
    private fun logRangeInfo(root: AccessibilityNodeInfo?) {
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 8) return
            val ri = n.rangeInfo
            if (ri != null) AppLog.i("[a11y] range node id=${n.viewIdResourceName} cls=${shortCls(n.className)} " +
                    "min=${ri.min} max=${ri.max} cur=${ri.current} (max may encode total duration)")
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
    }

    // ---- pre-play duration cache ------------------------------------------------

    private fun maybeCacheDurations() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanUptime < 500) return // throttle: content-changed is very chatty
        lastScanUptime = now
        try { cacheVisibleDurations() } catch (e: Exception) { AppLog.e("[a11y] duration scan failed (non-fatal): ${e.message}", e) }
    }

    /** Scan all on-screen voice-note controls; for IDLE ones (showing "Play"), the displayed time
     *  is the TOTAL duration. Cache it keyed by the bubble's timestamp so a later play-tap can
     *  recover the total even though the field will be counting up by then. */
    private fun cacheVisibleDurations() {
        val root = rootInActiveWindow ?: return
        val controls = root.findAccessibilityNodeInfosByViewId(CONTROL_BTN_ID) ?: return
        var added = 0
        for (c in controls) {
            val desc = (c.contentDescription?.toString() ?: "").lowercase(Locale.US)
            if (!desc.contains("play")) continue // playing/paused-mid shows elapsed, not total — skip
            val row = findMessageRow(c)
            val times = collectTimeTexts(row, 0, 8)
            if (times.size < 2) continue
            val dur = times.minByOrNull { parseTimeToSeconds(it) ?: Double.MAX_VALUE } ?: continue
            val ts = times.maxByOrNull { parseTimeToSeconds(it) ?: -1.0 } ?: continue
            if (dur == ts) continue
            if (durationByTimestamp[ts] != dur) {
                durationByTimestamp[ts] = dur
                added++
                // bound the cache
                while (durationByTimestamp.size > 200) {
                    val it = durationByTimestamp.keys.iterator(); it.next(); it.remove()
                }
            }
        }
        if (added > 0) AppLog.i("[a11y] cached total duration for $added idle voice note(s); cache size=${durationByTimestamp.size}.")
    }
}
