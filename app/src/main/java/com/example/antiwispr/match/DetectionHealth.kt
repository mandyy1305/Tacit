package com.example.antiwispr.match

/**
 * Tracks whether WhatsApp voice-note detection still works. If WhatsApp renames the play control,
 * the known-id path stops matching and we fall back to the structural heuristic; this object records
 * that so the health screen can warn "detection may be broken (WhatsApp update?)".
 */
object DetectionHealth {
    @Volatile var everSawKnownId = false
    @Volatile var lastKnownIdMs = 0L
    @Volatile var lastPlayDetectMs = 0L
    @Volatile var usedFallback = false
    @Volatile var lastFallbackMs = 0L

    private fun ago(ms: Long): String {
        if (ms == 0L) return "never"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            else -> "${s / 3600}h ago"
        }
    }

    fun summary(): String = when {
        lastPlayDetectMs == 0L && !everSawKnownId ->
            "no voice-note tap seen yet"
        usedFallback && (lastFallbackMs >= lastKnownIdMs) ->
            "⚠ using STRUCTURAL fallback (WhatsApp id may have changed) — enable Diagnostic mode + capture a dump"
        everSawKnownId ->
            "OK (known control seen ${ago(lastKnownIdMs)}, last play ${ago(lastPlayDetectMs)})"
        else ->
            "known control id NOT seen — detection may be broken"
    }
}
