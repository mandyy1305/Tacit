package com.example.antiwispr

import android.content.Context

/**
 * Process-wide runtime toggles set from the UI and read by the accessibility service /
 * orchestrator. The master switch and the behaviour preferences are PERSISTED (a user's choices,
 * especially a paying one's, must survive a process restart); the developer switches are
 * session-scoped. Call [load] from every process entry point (a11y service, app).
 */
object Toggles {

    private const val FILE = "tacit_toggles"

    /** MASTER SWITCH. Off = TACIT ignores WhatsApp entirely: no play-tap detection, no
     *  listening, no matching, no overlay. Search/Library/sync keep working on what's stored. */
    @Volatile var tacitEnabled: Boolean = true
        private set

    /** Flips true at the onboarding Done screen. From then on the app always starts on Home;
     *  a permission revoked later is surfaced on Home, never by re-entering first-run. */
    @Volatile var onboardingDone: Boolean = false
        private set

    // ---- Persisted behaviour preferences -----------------------------------------------------

    /** Run the capture -> match -> transcribe -> overlay flow on a detected play-tap. */
    @Volatile var orchestrationEnabled: Boolean = true
        private set

    /** When no screen-share session is active at play-tap, fall back to mic capture (lower accuracy). */
    @Volatile var micFallbackEnabled: Boolean = true
        private set

    /** When a match is CONFIRMED, pause WhatsApp's playback (click the playing control). */
    @Volatile var pauseOnMatch: Boolean = true
        private set

    // ---- Session-scoped developer switches (not persisted) -----------------------------------

    /** Dump the clicked node + parent + siblings to logcat and the debug overlay. Default follows
     *  the build type: ON in debug for development, OFF in release (the accessibility disclosure
     *  promises no storage of screen content, so a default-on tree dump must not ship). */
    @Volatile var diagnosticMode: Boolean = BuildConfig.DEBUG

    /** After a play-tap is detected, performAction(ACTION_CLICK) again to pause (isolation test). */
    @Volatile var pauseOnPlay: Boolean = false

    // ---- Load / persist ----------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Load persisted toggles — call from every process entry point (a11y service, app). */
    fun load(context: Context) {
        val p = prefs(context)
        tacitEnabled = p.getBoolean("tacit_enabled", true)
        onboardingDone = p.getBoolean("onboarding_done", false)
        orchestrationEnabled = p.getBoolean("orchestration_enabled", true)
        micFallbackEnabled = p.getBoolean("mic_fallback_enabled", true)
        pauseOnMatch = p.getBoolean("pause_on_match", true)
    }

    fun setTacitEnabled(context: Context, v: Boolean) { tacitEnabled = v; persist(context, "tacit_enabled", v) }
    fun setOnboardingDone(context: Context, v: Boolean) { onboardingDone = v; persist(context, "onboarding_done", v) }
    fun setOrchestration(context: Context, v: Boolean) { orchestrationEnabled = v; persist(context, "orchestration_enabled", v) }
    fun setMicFallback(context: Context, v: Boolean) { micFallbackEnabled = v; persist(context, "mic_fallback_enabled", v) }
    fun setPauseOnMatch(context: Context, v: Boolean) { pauseOnMatch = v; persist(context, "pause_on_match", v) }

    private fun persist(context: Context, key: String, v: Boolean) {
        prefs(context).edit().putBoolean(key, v).apply()
    }
}
