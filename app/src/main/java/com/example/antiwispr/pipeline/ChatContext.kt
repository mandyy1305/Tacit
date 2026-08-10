package com.example.antiwispr.pipeline

/**
 * What WhatsApp most recently had on screen, stamped by the accessibility service and consumed
 * by the share-import flow: a voice note shared out of WhatsApp arrives seconds after the user
 * was looking at it, so fresh context is that note's best sender attribution (the share intent
 * itself carries none).
 *
 * Two tiers, because sharing always starts with a LONG-PRESS on the note's bubble:
 *  - note: "sender · chat" read from the long-pressed row — same attribution the overlay gets
 *    at play-tap, so group notes name who spoke, not just where.
 *  - chat: the bare conversation title, stamped on ordinary window events — the fallback when
 *    the long-press event wasn't readable.
 * The freshness window bounds the wrong-chat risk when a share actually came from elsewhere.
 */
object ChatContext {

    private const val MAX_AGE_MS = 120_000L

    @Volatile private var noteLabel: String? = null
    @Volatile private var noteAtMs = 0L
    @Volatile private var chatTitle: String? = null
    @Volatile private var chatAtMs = 0L

    /** Strong: "sender · chat" from a long-pressed bubble. */
    fun stampNote(label: String) {
        noteLabel = label
        noteAtMs = System.currentTimeMillis()
    }

    /** Weak: the open conversation's title. Moving to a DIFFERENT chat retires a pending note
     *  stamp — its bubble can no longer be what gets shared next. */
    fun stampChat(title: String) {
        val note = noteLabel
        if (note != null && note != title && !note.endsWith(" · $title")) noteLabel = null
        chatTitle = title
        chatAtMs = System.currentTimeMillis()
    }

    /** The freshest attribution, note-level preferred; null once everything is stale. */
    fun recent(): String? {
        val now = System.currentTimeMillis()
        noteLabel?.takeIf { now - noteAtMs <= MAX_AGE_MS }?.let { return it }
        return chatTitle?.takeIf { now - chatAtMs <= MAX_AGE_MS }
    }
}
