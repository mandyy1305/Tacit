package com.example.antiwispr

/**
 * Process-wide runtime toggles set from MainActivity and read by the accessibility service /
 * orchestrator. Each is independently testable per the feasibility plan.
 */
object Toggles {
    /** Dump the clicked node + parent + siblings to logcat and the debug overlay. Default ON
     *  so the FIRST thing you can do is SEE what WhatsApp's tree exposes. */
    @Volatile var diagnosticMode: Boolean = true

    /** After a play-tap is detected, performAction(ACTION_CLICK) again to pause. Default OFF
     *  so it can be tested in isolation. */
    @Volatile var pauseOnPlay: Boolean = false

    /** Run the capture -> match -> transcribe-stub -> overlay flow on a detected play-tap. */
    @Volatile var orchestrationEnabled: Boolean = true

    /** Force Whisper to Hindi (best for Hindi-dominant code-switch). Off = auto-detect. */
    @Volatile var forceHindi: Boolean = true

    /** When no screen-share session is active at play-tap, fall back to mic capture (lower accuracy). */
    @Volatile var micFallbackEnabled: Boolean = true
}
