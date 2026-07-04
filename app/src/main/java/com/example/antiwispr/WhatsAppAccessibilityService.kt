package com.example.antiwispr

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())
    // When WE click the control to pause (pause-on-match), WhatsApp echoes a click event; ignore clicks
    // until this time so our own pause isn't misread as a user "pause -> cancel".
    @Volatile private var selfPauseUntilMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(applicationContext)
        orchestrator = Orchestrator(applicationContext, overlay)
        orchestrator.onMatchPause = { pausePlayingVoiceNote() }
        VoiceNoteWatcher.ensureStarted { IndexHolder.get(applicationContext).loadOrBuild { AppLog.i(it) } }
        AppLog.i("[a11y] SERVICE CONNECTED — bound to com.whatsapp.")
        AppLog.i("[a11y] play control = id '$CONTROL_BTN_ID' (or desc contains 'voice message').")
        AppLog.i("[a11y] toggles: diagnostic=${Toggles.diagnosticMode}, pauseOnPlay=${Toggles.pauseOnPlay}, orchestration=${Toggles.orchestrationEnabled}")
    }

    override fun onInterrupt() { AppLog.w("[a11y] onInterrupt()") }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppLog.w("[a11y] SERVICE UNBOUND (disabled in settings or stopped).")
        // Tear the overlay window down — a disabled service must not leak the window
        // (or, with Compose, its Recomposer/lifecycle).
        if (::overlay.isInitialized) overlay.dismiss()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.dismiss()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { /* not needed anymore */ }
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

        // Echo suppression, SHAPE-matched: the echo of our own pause-on-match click always
        // classifies as STOPPED (the control now reads "Play voice message"). A STARTED click
        // inside the window is a REAL user tap and must pass — the old blanket time-window
        // ignore swallowed the reflexive "play the next note" tap right after an auto-pause,
        // making the overlay "not appear". Consume exactly one echo, then close the window.
        if (System.currentTimeMillis() < selfPauseUntilMs && control == Control.STOPPED) {
            selfPauseUntilMs = 0L
            AppLog.i("[a11y] ignoring click — echo of our own pause-on-match tap (consumed).")
            return
        }
        when (control) {
            Control.NONE -> { AppLog.i("[a11y] not a voice-note control — ignoring, no overlay."); return }
            Control.STOPPED -> {
                AppLog.i("[a11y] playback PAUSED/STOPPED — cancelling any in-progress listen.")
                orchestrator.cancel("paused")
                return
            }
            Control.STARTED -> {
                DetectionHealth.lastPlayDetectMs = System.currentTimeMillis()
                AppLog.i("[a11y] ✅ playback STARTED — capturing.")
            }
        }

        // Matching no longer needs the bubble duration (the persistent index identifies the note
        // acoustically). We still grab the static send-TIMESTAMP for the overlay display.
        var timestamp: String? = null
        try {
            val row = findMessageRow(src)
            val times = collectTimeTexts(row, 0, 8)
            timestamp = if (times.size >= 2) times.maxByOrNull { parseTimeToSeconds(it) ?: -1.0 } else null
            if (timestamp != null) AppLog.i("[a11y] timestamp (display only) = $timestamp")
        } catch (e: Exception) {
            AppLog.e("[a11y] timestamp extraction failed (non-fatal): ${e.message}", e)
        }

        if (Toggles.pauseOnPlay) {
            val ok = src.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i("[a11y] pause toggle: performAction(ACTION_CLICK) returned $ok (confirm it actually paused on screen).")
        }

        if (Toggles.orchestrationEnabled) {
            val chatName = currentChatTitle()
            if (chatName != null) AppLog.i("[a11y] chat = \"$chatName\"")
            orchestrator.onPlayTap(null, timestamp, chatName) // duration unused; index handles identification
        } else {
            AppLog.i("[a11y] orchestration disabled — skipping capture/match/transcribe.")
        }
    }

    /** Best-effort chat title from the conversation screen (for sender attribution).
     *  If WhatsApp renames the id, we just lose the label — never the pipeline. */
    private fun currentChatTitle(): String? = try {
        val root = rootInActiveWindow
        val direct = root
            ?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            ?.firstOrNull()?.text?.toString()?.trim()
        if (direct.isNullOrEmpty()) {
            AppLog.i("[a11y] chat title not found (id may have changed — check a diagnostic dump).")
            null
        } else direct
    } catch (e: Exception) {
        AppLog.w("[a11y] chat title read failed (non-fatal): ${e.message}")
        null
    }

    // ---- detection --------------------------------------------------------------

    private fun classifyControl(n: AccessibilityNodeInfo): Control {
        val id = n.viewIdResourceName ?: ""
        val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.US)
        val knownId = id == CONTROL_BTN_ID || id == "com.whatsapp:id/control_button_container"
        val descMatch = desc.contains("voice message") || desc.contains("voice note") || desc.contains("push to talk")

        if (knownId) { DetectionHealth.everSawKnownId = true; DetectionHealth.lastKnownIdMs = System.currentTimeMillis() }

        if (knownId || descMatch) {
            // IMPORTANT: TYPE_VIEW_CLICKED fires AFTER WhatsApp toggles the button, and we read the LIVE
            // node — so the contentDescription is the POST-click state (inverted from the icon tapped).
            // ▶ START -> desc="Pause voice message"; ⏸ PAUSE -> desc="Play voice message".
            return when {
                desc.contains("pause") -> Control.STARTED
                desc.contains("play") -> Control.STOPPED
                else -> Control.STARTED
            }
        }

        // RESILIENCE: if WhatsApp renamed the id/desc, fall back to STRUCTURE — a clickable control whose
        // message row contains a seekbar (rangeInfo) AND a m:ss duration text looks like a voice note.
        // Bounded to the single row (findMessageRow) so it doesn't false-fire on stickers/list rows.
        if (n.isClickable) {
            val row = findMessageRow(n)
            if (hasRangeInfo(row, 0) && collectTimeTexts(row, 0, 8).isNotEmpty()) {
                DetectionHealth.usedFallback = true
                DetectionHealth.lastFallbackMs = System.currentTimeMillis()
                AppLog.w("⚠ detection via STRUCTURAL fallback (id='$id' desc='$desc') — WhatsApp control id may have changed.")
                return Control.STARTED
            }
        }
        return Control.NONE
    }

    /** True if any descendant is a range/seek node (SeekBar/ProgressBar expose RangeInfo). */
    private fun hasRangeInfo(n: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (n == null || depth > 8) return false
        if (n.rangeInfo != null) return true
        for (i in 0 until n.childCount) if (hasRangeInfo(n.getChild(i), depth + 1)) return true
        return false
    }

    /** Pause the currently-playing voice note: find the control showing "Pause …" and click it.
     *  Invoked by the orchestrator on a confirmed match. Runs on the main thread. */
    private fun pausePlayingVoiceNote() {
        main.post {
            try {
                val root = rootInActiveWindow ?: run { AppLog.w("[a11y] pause-on-match: no active window."); return@post }
                val controls = root.findAccessibilityNodeInfosByViewId(CONTROL_BTN_ID) ?: emptyList()
                // The PLAYING note's control shows "Pause voice message" (tap => pauses it).
                val playing = controls.firstOrNull {
                    (it.contentDescription?.toString() ?: "").lowercase(Locale.US).contains("pause")
                }
                if (playing == null) {
                    AppLog.w("[a11y] pause-on-match: no playing voice-note control found (already stopped?).")
                    return@post
                }
                selfPauseUntilMs = System.currentTimeMillis() + 1500 // suppress the echo of the click we're about to make
                val ok = playing.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLog.i("[a11y] pause-on-match: clicked the playing control => $ok")
            } catch (e: Exception) {
                AppLog.e("[a11y] pause-on-match failed: ${e.message}", e)
            }
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
}
