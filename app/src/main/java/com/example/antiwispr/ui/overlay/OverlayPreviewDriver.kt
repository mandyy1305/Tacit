package com.example.antiwispr.ui.overlay

import android.content.Context
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.match.CandidateFile
import com.example.antiwispr.pipeline.OverlayController
import java.io.File

/**
 * Developer tool: replays the exact call sequences Orchestrator makes against the overlay's
 * public API, with fake data, from a background thread (which also exercises the
 * main-thread marshalling). Lets us iterate on the overlay design without WhatsApp.
 * Triggered from Settings → Developer → "Preview overlay card"; alternates scenarios.
 */
object OverlayPreviewDriver {

    @Volatile private var controller: OverlayController? = null
    @Volatile private var scenario = 0

    fun run(context: Context) {
        val ctx = context.applicationContext
        val ctrl = controller ?: OverlayController(ctx).also { controller = it }
        val which = scenario++ % 2
        AppLog.i("[preview] overlay scenario ${if (which == 0) "A (match + transcript)" else "B (mic fallback + no match)"}")
        Thread({
            try {
                if (which == 0) happyPath(ctrl) else degraded(ctrl)
            } catch (e: InterruptedException) {
                // preview cancelled
            }
        }, "overlay-preview").apply { isDaemon = true }.start()
    }

    private fun happyPath(o: OverlayController) {
        // Lazy chain (matches Orchestrator): seed the pager at match time, then transcribe a part
        // only when the previewer swipes to it. The AI-summary button summarises the shown note.
        // (Callbacks fire on main; the fake latency runs on its own daemon thread.)
        val parts = arrayOf<String?>(null, FAKE_PART_2, null, null) // part 2 (index 1) = the played note
        o.onRequestSummary = {
            o.setSummaryGenerating()
            Thread({ Thread.sleep(1600); o.setSummaryReady(FAKE_SUMMARY) }, "preview-summary")
                .apply { isDaemon = true }.start()
        }
        o.onPartVisible = { i ->
            o.setSummaryIdle() // summary is per-note — re-arm for the newly shown part
            if (parts.getOrNull(i) == null) {
                Thread({
                    Thread.sleep(1100)
                    parts[i] = FAKE_PARTS.getOrNull(i)
                    o.setChainParts(parts.toList())
                }, "preview-part-$i").apply { isDaemon = true }.start()
            }
        }
        o.showSpinner("Listening…")
        o.setInfo(null, "10:42")
        fakeListen(o, 40, listOf(
            0.7 to "listening 0.7s… (no match yet)",
            1.4 to "listening 1.4s… (no match yet)",
            2.1 to "listening 2.1s — leading: PTT-20260630-WA0012.opus (14)",
            2.8 to "listening 2.8s — leading: PTT-20260630-WA0012.opus (37)",
        ))
        o.setCandidates(
            listOf(
                fake("PTT-20260630-WA0012.opus", 42.0, 20260630, 52.0),
                fake("PTT-20260629-WA0031.opus", 41.0, 20260629, 18.0),
                fake("PTT-20260630-WA0007.opus", 44.0, 20260630, 12.0),
            ),
            true,
        )
        o.setSource("local")
        o.setTranscript("transcribing…")
        Thread.sleep(2200)
        o.setTranscript(FAKE_PART_2)      // the matched note is part 2 of the chain
        o.setChainInfo(2, 4)
        o.setChainParts(parts.toList())   // seed the pager: part 2 filled, the rest lazy
    }

    private const val FAKE_SUMMARY =
        "SUMMARY: Kal office ke baad milna tay hua hai; documents ready hain aur " +
            "23rd ka venue confirm ho gaya hai.\n" +
            "ACTIONS:\n" +
            "- Lease agreement aur dono ID proofs kal le jana\n" +
            "- Banker ko 5 baje se pehle call karna\n" +
            "- 23rd ke liye calendar block karna\n" +
            "- Sunday lunch pe mummy ke ghar jana"

    private const val FAKE_PART_2 =
        "Haan bhai, kal milte hain office ke baad. I'll bring the documents you asked for: " +
            "the lease agreement and both ID proofs. Agar time mile toh please banker ko call " +
            "kar lena before five, warna appointment shift ho jayegi to next week."
    private val FAKE_PARTS = arrayOf(
        "Arre sun, ek important baat batani thi tujhe. Do teen cheezein hain actually.",
        FAKE_PART_2,
        "Agar time mile toh please banker ko call kar lena before five, warna appointment " +
            "shift ho jayegi to next week.",
        "Aur haan, Priya said the venue is confirmed for the twenty-third, so block your " +
            "calendar. Sunday ko mummy ke ghar lunch pe aana hai, bhoolna mat!",
    )

    private fun degraded(o: OverlayController) {
        o.showSpinner("Listening…")
        o.setInfo(null, "18:03")
        o.showMicFallbackBanner { AppLog.i("[preview] Share-screen tapped (no-op in preview).") }
        fakeListen(o, 34, listOf(
            0.7 to "listening 0.7s… (no match yet)",
            1.6 to "listening 1.6s… (no match yet)",
            2.5 to "listening 2.5s… (no match yet)",
        ))
        // Keep the mic banner set so no-match shows the Share-screen recovery variant.
        o.setCandidates(emptyList(), false) // → NO_MATCH (plain replay prompt + share nudge)
    }

    /** Drives [steps] ~90 ms ticks of the listening state: pushes a synthetic, speech-like audio
     *  level each tick (so the live waveform reacts) and flips the status line at the given
     *  elapsed-second thresholds. Mirrors what the real capture source feeds via onLevel. */
    private fun fakeListen(o: OverlayController, steps: Int, statuses: List<Pair<Double, String>>) {
        var next = 0
        for (i in 0 until steps) {
            val tSec = i * 0.09
            while (next < statuses.size && tSec >= statuses[next].first) { o.setStatus(statuses[next].second); next++ }
            // Raw RMS-scale synthetic voice: syllabic swells that settle toward the gate floor
            // (so the overlay's conditioning collapses to dots between syllables, then blooms).
            val s1 = Math.max(0.0, Math.sin(tSec * 6.5))
            val s2 = Math.max(0.0, Math.sin(tSec * 3.7 + 1.0))
            val syll = s1 * s1 * 0.6 + s2 * s2 * s2 * 0.4
            o.pushAudioLevel((0.004 + 0.09 * syll).toFloat())  // real mic RMS scale (~0.004–0.094)
            Thread.sleep(90)
        }
    }

    private fun fake(name: String, dur: Double, waDate: Int, score: Double) = CandidateFile(
        file = File("/preview/$name"),
        name = name,
        durationSec = dur,
        lastModified = System.currentTimeMillis(),
        score = score,
        whatsAppDate = waDate,
    )
}
