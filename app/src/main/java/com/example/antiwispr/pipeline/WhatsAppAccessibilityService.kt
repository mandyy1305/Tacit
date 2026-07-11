package com.example.antiwispr.pipeline

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.antiwispr.chains.VoiceRun
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.core.Toggles
import com.example.antiwispr.match.DetectionHealth
import com.example.antiwispr.match.IndexHolder
import com.example.antiwispr.match.VoiceNoteWatcher
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

        /** Max rows to scan on each side of the played note when reading the chain run. */
        private const val MAX_RUN = 25

        /** Max send-time gap (minutes) between neighboring notes to still count as one burst.
         *  A real chain is sent back-to-back; a bigger gap means they're unrelated. */
        private const val MAX_BURST_GAP_MIN = 10

        /** The live orchestrator, exposed so the FCM push handler (a separate component in the
         *  same process) can deliver a finished cloud transcript to the on-screen overlay.
         *  Set on connect, cleared on unbind/destroy. */
        @Volatile
        var active: Orchestrator? = null
            private set
    }

    private enum class Control { NONE, STARTED, STOPPED }

    private lateinit var overlay: OverlayController
    private lateinit var orchestrator: Orchestrator

    private val timeRegex = Regex("""\b(\d{1,2}):(\d{2})\b""")
    // Duration words in a voice bubble's a11y description ("17 seconds", "1 minute 5 seconds").
    private val secWordRegex = Regex("""(\d+)\s*second""", RegexOption.IGNORE_CASE)
    private val minWordRegex = Regex("""(\d+)\s*minute""", RegexOption.IGNORE_CASE)
    // Send time in a bubble desc: 12-hour "1:05 pm" (preferred) or a bare 24-hour "13:05".
    private val clock12Regex = Regex("""(\d{1,2}):(\d{2})\s*([ap])\.?\s?m""", RegexOption.IGNORE_CASE)
    private val clock24Regex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    // When WE click the control to pause (pause-on-match), WhatsApp echoes a click event; ignore clicks
    // until this time so our own pause isn't misread as a user "pause -> cancel".
    @Volatile private var selfPauseUntilMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toggles.load(applicationContext) // the master switch persists; honour it from first event
        overlay = OverlayController(applicationContext)
        overlay.warmUp() // absorb the cold-start first-overlay-window penalty before the first play
        orchestrator = Orchestrator(applicationContext, overlay)
        orchestrator.onMatchPause = { pausePlayingVoiceNote() }
        active = orchestrator // reachable by the FCM push handler while the service is alive
        VoiceNoteWatcher.ensureStarted { IndexHolder.get(applicationContext).loadOrBuild { AppLog.i(it) } }
        AppLog.i("[a11y] SERVICE CONNECTED — bound to com.whatsapp.")
        AppLog.i("[a11y] play control = id '$CONTROL_BTN_ID' (or desc contains 'voice message').")
        AppLog.i("[a11y] toggles: diagnostic=${Toggles.diagnosticMode}, pauseOnPlay=${Toggles.pauseOnPlay}")
    }

    override fun onInterrupt() { AppLog.w("[a11y] onInterrupt()") }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppLog.w("[a11y] SERVICE UNBOUND (disabled in settings or stopped).")
        active = null
        // Tear the overlay window down — a disabled service must not leak the window
        // (or, with Compose, its Recomposer/lifecycle).
        if (::overlay.isInitialized) overlay.destroy()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        active = null
        if (::overlay.isInitialized) overlay.destroy()
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
        // Master switch: when off, TACIT ignores WhatsApp entirely — no detection, no
        // listening, no overlay, not even click logging.
        if (!Toggles.tacitEnabled) return
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

        val title = currentChatTitle()
        val sender = senderNameFor(src)
        // Group note: "Rahul · Family" (who spoke + where). DM / own note / no label: title only.
        val chatName = when {
            sender != null && title != null && !sender.equals(title, ignoreCase = true) -> "$sender · $title"
            sender != null -> sender
            else -> title
        }
        if (chatName != null) AppLog.i("[a11y] chat = \"$chatName\"")
        // Read the consecutive same-sender voice run around this note straight from the chat —
        // chat-scoped, so it can't merge across chats the way the filesystem heuristic did.
        val run = try {
            voiceRunAround(src)
        } catch (e: Exception) {
            AppLog.w("[a11y] voice-run read failed (non-fatal): ${e.message}"); null
        }
        if (run != null) AppLog.i("[a11y] voice run: ${run.durations.size} note(s), anchor@${run.anchorIndex}, durs=${run.durations}")
        orchestrator.onPlayTap(null, timestamp, chatName, run) // duration unused; index identifies the note
    }

    // In-bubble sender label ids (group chats). Version-dependent — extend from a diagnostic
    // dump if WhatsApp renames it; missing just means "no sender", never a broken pipeline.
    private val senderLabelIds = listOf("com.whatsapp:id/name_in_group")

    /**
     * The PERSON who sent the tapped note (groups only — the toolbar title is the GROUP
     * name there). Primary source: the bubble row's a11y description, which names the
     * sender on EVERY incoming group bubble — "Hrishita RS, voice message, 17 seconds,
     * 1:05 pm, played" (confirmed by an on-device node dump). Fallbacks: the visual
     * name_in_group label (first message of a run only; the name sits on a child
     * TextView), then the nearest labeled row above. Own (outgoing) notes are
     * right-aligned — skipped, so a neighbour's name is never attributed to us.
     * Null for DMs.
     */
    private fun senderNameFor(src: AccessibilityNodeInfo): String? = try {
        val bounds = Rect().also { src.getBoundsInScreen(it) }
        if (bounds.left > resources.displayMetrics.widthPixels / 2) {
            null // outgoing bubble (right-aligned control) — our own note
        } else {
            val row = findMessageRow(src)
            val sender = senderFromRowDesc(row) ?: senderLabelIn(row) ?: nearestLabelAbove(row)
            if (sender == null) AppLog.i("[a11y] no sender found (DM, or WhatsApp changed its row description).")
            sender
        }
    } catch (e: Exception) {
        AppLog.w("[a11y] sender lookup failed (non-fatal): ${e.message}")
        null
    }

    /** "Hrishita RS, voice message, 17 seconds, 1:05 pm, played" → "Hrishita RS".
     *  DM rows/own notes start with "voice message"/"You" and yield null. */
    private fun senderFromRowDesc(row: AccessibilityNodeInfo?): String? {
        val desc = findVoiceRowDesc(row, 0) ?: return null
        val first = desc.substringBefore(",").trim()
        val lower = first.lowercase(Locale.US)
        if (first.isEmpty() || lower == "you" ||
            lower.contains("voice message") || lower.contains("voice note")
        ) return null
        return first
    }

    private fun findVoiceRowDesc(n: AccessibilityNodeInfo?, depth: Int): String? {
        if (n == null || depth > 4) return null
        val d = n.contentDescription?.toString()
        if (d != null && d.lowercase(Locale.US).contains("voice message")) return d
        for (i in 0 until n.childCount) findVoiceRowDesc(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** name_in_group is a container — the name text lives on a descendant TextView. */
    private fun senderLabelIn(row: AccessibilityNodeInfo?): String? {
        row ?: return null
        for (id in senderLabelIds) {
            val node = row.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() ?: continue
            firstText(node, 0)?.let { return it }
        }
        return null
    }

    private fun firstText(n: AccessibilityNodeInfo?, depth: Int): String? {
        if (n == null || depth > 3) return null
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (i in 0 until n.childCount) firstText(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** Last resort for label-collapsed runs: any run break re-labels, so the nearest
     *  labeled row above an incoming row belongs to the same sender. */
    private fun nearestLabelAbove(row: AccessibilityNodeInfo): String? {
        val list = row.parent ?: return null
        var idx = -1
        for (i in 0 until list.childCount) if (list.getChild(i) == row) { idx = i; break }
        var i = idx - 1
        while (i >= 0 && idx - i <= 6) {
            senderLabelIn(list.getChild(i))?.let { return it }
            i--
        }
        return null
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

    // ---- chain run (consecutive same-sender voice notes around the played one) ----------

    private data class RowInfo(val durationSec: Double?, val outgoing: Boolean, val sender: String?, val minuteOfDay: Int?)

    /**
     * Walk the chat message list up AND down from the played bubble, collecting the maximal run
     * of CONSECUTIVE voice-note bubbles from the SAME speaker (same side + same sender name). The
     * list is per-chat, so the run can never span chats — this is what makes chain membership
     * correct (unlike the old device-global WA#### + mtime heuristic). Returns per-bubble
     * durations in chat order with the played note's slot; null or a single-note run = "no chain".
     * Only on-screen (non-recycled) rows are in the tree, so the run is bounded by the viewport.
     */
    fun voiceRunAround(played: AccessibilityNodeInfo): VoiceRun? {
        val row = findMessageRow(played)
        val list = row.parent ?: return null
        val n = list.childCount
        var anchorIdx = -1
        for (i in 0 until n) if (list.getChild(i) == row) { anchorIdx = i; break }
        if (anchorIdx < 0) return null

        val anchorOutgoing = isOutgoing(played)
        val anchorSender = senderNameFor(played) // null for own/DM; the name for a group bubble
        val anchorInfo = parseVoiceRow(row)
        fun sameSpeaker(info: RowInfo) = info.outgoing == anchorOutgoing && info.sender == anchorSender

        // Walk up: keep consecutive same-speaker voice notes that are ALSO close in send time. A
        // burst is minutes apart, so a large time gap (or an unreadable time) ends the run — this
        // is what stops a lone note from chaining with an unrelated voice note sent hours earlier.
        val before = ArrayList<Double?>()
        var prev = anchorInfo?.minuteOfDay
        var i = anchorIdx - 1
        while (i >= 0 && anchorIdx - i <= MAX_RUN) {
            val info = parseVoiceRow(list.getChild(i)) ?: break // a non-voice row ends the run
            if (!sameSpeaker(info) || !withinBurst(info.minuteOfDay, prev)) break
            before.add(info.durationSec); prev = info.minuteOfDay; i--
        }
        before.reverse() // chat order: earliest first

        val after = ArrayList<Double?>()
        prev = anchorInfo?.minuteOfDay
        var j = anchorIdx + 1
        while (j < n && j - anchorIdx <= MAX_RUN) {
            val info = parseVoiceRow(list.getChild(j)) ?: break
            if (!sameSpeaker(info) || !withinBurst(info.minuteOfDay, prev)) break
            after.add(info.durationSec); prev = info.minuteOfDay; j++
        }

        val durations = ArrayList<Double?>(before.size + 1 + after.size)
        durations.addAll(before)
        durations.add(anchorInfo?.durationSec) // anchor (its duration may be null while playing)
        durations.addAll(after)
        if (durations.size < 2) return null // lone note → no chain
        return VoiceRun(durations, before.size)
    }

    /** Same burst only if both send times are known and within a few minutes. Unknown time →
     *  NOT the same burst (conservative: never chain across an unverifiable gap). */
    private fun withinBurst(a: Int?, b: Int?): Boolean =
        a != null && b != null && kotlin.math.abs(a - b) <= MAX_BURST_GAP_MIN

    /** Parse a message row into (durationSec, outgoing, sender), or null if it isn't a voice note. */
    private fun parseVoiceRow(row: AccessibilityNodeInfo?): RowInfo? {
        row ?: return null
        val desc = findVoiceRowDesc(row, 0) ?: return null // not a voice-note bubble
        val outgoing = isOutgoingRow(row)
        val sender = if (outgoing) null else (senderFromRowDesc(row) ?: senderLabelIn(row))
        return RowInfo(durationFromDesc(desc), outgoing, sender, sendMinuteOfDay(desc))
    }

    /** "…, 17 seconds, …" or "…, 1 minute 5 seconds, …" → seconds. null when no duration words are
     *  present — the send time ("1:05 pm") is deliberately NOT read as a duration. */
    private fun durationFromDesc(desc: String): Double? {
        val mins = minWordRegex.find(desc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val secs = secWordRegex.find(desc)?.groupValues?.get(1)?.toIntOrNull()
        if (secs == null && mins == 0) return null
        return (mins * 60 + (secs ?: 0)).toDouble()
    }

    /** The bubble's SEND time as a minute-of-day (0..1439), for the burst time-gap check. Prefers
     *  12-hour "1:05 pm"; falls back to the last bare 24-hour "13:05" (the send time trails the
     *  worded duration in the desc). null when no clock is present. */
    private fun sendMinuteOfDay(desc: String): Int? {
        clock12Regex.find(desc)?.let { m ->
            val h = (m.groupValues[1].toIntOrNull() ?: return null) % 12
            val min = m.groupValues[2].toIntOrNull() ?: return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            return h * 60 + min + (if (pm) 12 * 60 else 0)
        }
        val m = clock24Regex.findAll(desc).lastOrNull() ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return h * 60 + min
    }

    private fun isOutgoing(node: AccessibilityNodeInfo): Boolean {
        val b = Rect().also { node.getBoundsInScreen(it) }
        return b.left > resources.displayMetrics.widthPixels / 2
    }

    /** Incoming vs outgoing for a row: judge by the voice control's bounds (left/right aligned),
     *  falling back to the row's own bounds. */
    private fun isOutgoingRow(row: AccessibilityNodeInfo): Boolean {
        val ctrl = row.findAccessibilityNodeInfosByViewId(CONTROL_BTN_ID)?.firstOrNull()
        return isOutgoing(ctrl ?: row)
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
