package com.example.antiwispr

import android.content.Context

/**
 * Process-wide runtime toggles set from MainActivity and read by the accessibility service /
 * orchestrator. Each is independently testable per the feasibility plan.
 * [tacitEnabled] is the one PERSISTED toggle (a master off-switch must survive restarts);
 * the rest are session-scoped dev/behaviour switches.
 */
object Toggles {

    private const val FILE = "tacit_toggles"

    /** MASTER SWITCH. Off = TACIT ignores WhatsApp entirely: no play-tap detection, no
     *  listening, no matching, no overlay. Search/History/sync keep working on what's stored. */
    @Volatile var tacitEnabled: Boolean = true
        private set

    /** Load persisted toggles — call from every process entry point (a11y service, app). */
    fun load(context: Context) {
        tacitEnabled = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("tacit_enabled", true)
    }

    fun setTacitEnabled(context: Context, v: Boolean) {
        tacitEnabled = v
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("tacit_enabled", v).apply()
    }
    /** Dump the clicked node + parent + siblings to logcat and the debug overlay. Default ON
     *  so the FIRST thing you can do is SEE what WhatsApp's tree exposes. */
    @Volatile var diagnosticMode: Boolean = true

    /** After a play-tap is detected, performAction(ACTION_CLICK) again to pause. Default OFF
     *  so it can be tested in isolation. */
    @Volatile var pauseOnPlay: Boolean = false

    /** Run the capture -> match -> transcribe-stub -> overlay flow on a detected play-tap. */
    @Volatile var orchestrationEnabled: Boolean = true

    /** When no screen-share session is active at play-tap, fall back to mic capture (lower accuracy). */
    @Volatile var micFallbackEnabled: Boolean = true

    /** When a match is CONFIRMED, pause WhatsApp's playback (click the playing control). */
    @Volatile var pauseOnMatch: Boolean = true
}
