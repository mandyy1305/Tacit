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

    /** Timestamps of the Play prominent-disclosure consents (0 = not yet given). Recorded BEFORE
     *  the user is sent to the system settings to enable the service. */
    @Volatile var accessibilityConsentMs: Long = 0L
        private set
    @Volatile var micConsentMs: Long = 0L
        private set

    // ---- Persisted behaviour preferences -----------------------------------------------------

    /** When no screen-share session is active at play-tap, fall back to mic capture (lower accuracy). */
    @Volatile var micFallbackEnabled: Boolean = true
        private set

    /** When a match is CONFIRMED, pause WhatsApp's playback (click the playing control). */
    @Volatile var pauseOnMatch: Boolean = true
        private set

    /** True once the user dismissed the Precision Listening intro ("Don't show again"): from then
     *  the Home CTA shows an inline Start instead of the arrow that opens the explanation sheet. */
    @Volatile var precisionExplained: Boolean = false
        private set

    // ---- Overlay coach-marks: persisted counts of times each tooltip has been shown. A tooltip
    //      keeps showing until the user does the action (count jumps to the cap) or it has been
    //      shown COACH_CAP times — a few reminders, never nagging forever. ---------------------
    @Volatile var shareTipCount: Int = 0
        private set
    @Volatile var swipeTipCount: Int = 0
        private set
    @Volatile var aiTipCount: Int = 0
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
        micFallbackEnabled = p.getBoolean("mic_fallback_enabled", true)
        pauseOnMatch = p.getBoolean("pause_on_match", true)
        precisionExplained = p.getBoolean("precision_explained", false)
        accessibilityConsentMs = p.getLong("a11y_consent_ms", 0L)
        micConsentMs = p.getLong("mic_consent_ms", 0L)
        shareTipCount = p.getInt("share_tip_count", 0)
        swipeTipCount = p.getInt("swipe_tip_count", 0)
        aiTipCount = p.getInt("ai_tip_count", 0)
    }

    fun setShareTipCount(context: Context, v: Int) { shareTipCount = v; persistInt(context, "share_tip_count", v) }
    fun setSwipeTipCount(context: Context, v: Int) { swipeTipCount = v; persistInt(context, "swipe_tip_count", v) }
    fun setAiTipCount(context: Context, v: Int) { aiTipCount = v; persistInt(context, "ai_tip_count", v) }
    private fun persistInt(context: Context, key: String, v: Int) { prefs(context).edit().putInt(key, v).apply() }

    fun setTacitEnabled(context: Context, v: Boolean) { tacitEnabled = v; persist(context, "tacit_enabled", v) }
    fun setOnboardingDone(context: Context, v: Boolean) { onboardingDone = v; persist(context, "onboarding_done", v) }
    fun setAccessibilityConsent(context: Context, ms: Long) { accessibilityConsentMs = ms; persistLong(context, "a11y_consent_ms", ms) }
    fun setMicConsent(context: Context, ms: Long) { micConsentMs = ms; persistLong(context, "mic_consent_ms", ms) }
    fun setMicFallback(context: Context, v: Boolean) { micFallbackEnabled = v; persist(context, "mic_fallback_enabled", v) }
    fun setPauseOnMatch(context: Context, v: Boolean) { pauseOnMatch = v; persist(context, "pause_on_match", v) }
    fun setPrecisionExplained(context: Context, v: Boolean) { precisionExplained = v; persist(context, "precision_explained", v) }

    private fun persist(context: Context, key: String, v: Boolean) {
        prefs(context).edit().putBoolean(key, v).apply()
    }

    private fun persistLong(context: Context, key: String, v: Long) {
        prefs(context).edit().putLong(key, v).apply()
    }
}
