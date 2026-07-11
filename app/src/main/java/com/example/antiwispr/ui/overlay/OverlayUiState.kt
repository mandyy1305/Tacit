package com.example.antiwispr.ui.overlay

import com.example.antiwispr.match.CandidateFile
import com.example.antiwispr.data.VoiceNotes
import com.example.antiwispr.ui.components.durationLabel
import com.example.antiwispr.ui.components.waDateLabel

enum class OverlayPhase { LISTENING, MATCHED, TRANSCRIBING, TRANSCRIPT, NO_MATCH, NOTICE }

enum class SummaryState { NONE, GENERATING, READY, UNAVAILABLE }

/** The single recovery action a notice card may carry (doc 02 §2.J). */
enum class NoticeAction { NONE, FINISH_SETUP, TRY_AGAIN, SHARE_SCREEN, LISTEN_AGAIN }

/** Human-facing description of the matched note: "30 Jun · 0:42" (or "Voice note"). */
data class MatchInfo(val meta: String)

/** The whole overlay renders from this one immutable value (single mutableStateOf). */
data class OverlayUiState(
    val visible: Boolean = false,
    val phase: OverlayPhase = OverlayPhase.LISTENING,
    val micBanner: Boolean = false,
    val statusLine: String = "Listening…",
    val statusWarn: Boolean = false,
    val match: MatchInfo? = null,        // survives MATCHED → TRANSCRIBING → TRANSCRIPT
    val transcript: String? = null,
    val notice: String? = null,          // humanized bracket-message ("[no confident match …]")
    val noticeAction: NoticeAction = NoticeAction.NONE, // recovery button the notice card offers
    val summaryState: SummaryState = SummaryState.NONE,
    val summaryRaw: String? = null,      // raw "SUMMARY:/ACTIONS:" text; parsed at render time
    val chainPart: Int = 0,              // this note's position in its burst (0 = no chain)
    val chainCount: Int = 0,             // burst size (0/1 = no chain)
    val chainSummary: Boolean = false,   // Summary tab currently shows the CHAIN gist
    val chainMode: Boolean = false,      // "Transcribe all N" toggle: tabs cover the whole burst
    val chainParts: List<String?> = emptyList(), // per-part transcripts in chain mode; null = pending
    val copied: Boolean = false,
    val source: String = "",             // provenance of the result/engine: "local" | "cloud" | "" = unknown
)

internal data class StatusInfo(val line: String, val warn: Boolean = false)

// Formats pinned against Orchestrator.kt's setStatus call sites — keep in sync.
private val LISTEN_RX = Regex("""^listening [\d.]+s""")

/**
 * Prettifies Orchestrator's raw status strings so Orchestrator itself needs zero changes.
 * All "listening …" ticks collapse to a constant line — the equalizer animation carries
 * the liveness; no seconds counter, no leading-file readout.
 */
internal fun interpretStatus(raw: String): StatusInfo = when {
    LISTEN_RX.containsMatchIn(raw) -> StatusInfo(line = "Listening…")
    raw.startsWith("building index") -> StatusInfo(line = "Preparing your notes…")
    raw.startsWith("stopped") -> StatusInfo(line = "Couldn't hear the note", warn = true)
    else -> StatusInfo(line = raw, warn = true) // mic / no-capture guidance shown verbatim
}

/** "[no confident match after 12s]" → calm human copy. */
internal fun humanizeNotice(bracket: String): String {
    val t = bracket.trim().removePrefix("[").removeSuffix("]")
    return when {
        t.startsWith("no confident match") -> "No confident match. Replay the note and TACIT listens again."
        t.startsWith("whisper model not downloaded") -> "Transcription isn't set up yet."
        t.startsWith("whisper model not ready") -> "The transcriber is still warming up."
        t.startsWith("no speech detected") -> "No speech detected in this note."
        t.startsWith("no audio decoded") -> "Couldn't read this note's audio."
        t.startsWith("matching error") -> "Something went wrong while matching."
        else -> t.replaceFirstChar { it.uppercase() }
    }
}

/** The recovery action for a bracket notice, paired with [humanizeNotice]'s copy. */
internal fun noticeActionFor(bracket: String): NoticeAction {
    val t = bracket.trim().removePrefix("[").removeSuffix("]")
    return when {
        t.startsWith("whisper model not downloaded") -> NoticeAction.FINISH_SETUP
        t.startsWith("whisper model not ready") -> NoticeAction.TRY_AGAIN
        t.startsWith("matching error") -> NoticeAction.LISTEN_AGAIN
        else -> NoticeAction.NONE
    }
}

/** "30 Jun · 0:42" — never the raw filename. Falls back to "Voice note". */
internal fun CandidateFile.toMatchInfo(): MatchInfo {
    val date = whatsAppDate ?: VoiceNotes.parseWhatsAppName(name)?.dateYmd
    val parts = listOfNotNull(
        date?.let { waDateLabel(it) },
        durationLabel(durationSec),
    )
    return MatchInfo(if (parts.isEmpty()) "Voice note" else parts.joinToString("  ·  "))
}
