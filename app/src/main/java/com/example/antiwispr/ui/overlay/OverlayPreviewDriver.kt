package com.example.antiwispr.ui.overlay

import android.content.Context
import com.example.antiwispr.AppLog
import com.example.antiwispr.CandidateFile
import com.example.antiwispr.OverlayController
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
        o.showSpinner("Listening…")
        o.setInfo(null, "10:42")
        Thread.sleep(700); o.setStatus("listening 0.7s… (no match yet)")
        Thread.sleep(700); o.setStatus("listening 1.4s… (no match yet)")
        Thread.sleep(700); o.setStatus("listening 2.1s — leading: PTT-20260630-WA0012.opus (14)")
        Thread.sleep(700); o.setStatus("listening 2.8s — leading: PTT-20260630-WA0012.opus (37)")
        Thread.sleep(500)
        o.setCandidates(listOf(fake("PTT-20260630-WA0012.opus", 42.0, 20260630, 52.0)), true)
        o.setTranscript("transcribing…")
        Thread.sleep(2200)
        o.setTranscript(
            "Haan bhai, kal milte hain office ke baad. I'll bring the documents you asked for — " +
                "the lease agreement and both ID proofs. Agar time mile toh please banker ko " +
                "call kar lena before five, warna appointment shift ho jayegi to next week. " +
                "Aur haan, Priya said the venue is confirmed for the twenty-third, so block " +
                "your calendar. Baaki sab theek hai, mummy ko bola maine ki hum Sunday ko " +
                "aayenge lunch pe. Chalo, see you tomorrow!"
        )
        o.setChainInfo(2, 4)
        Thread.sleep(700)
        o.setSummaryGenerating()
        Thread.sleep(2000)
        o.setSummaryReady(
            "SUMMARY: Kal office ke baad milna tay hua hai; documents ready hain aur " +
                "23rd ka venue confirm ho gaya hai.\n" +
                "ACTIONS:\n" +
                "- Lease agreement aur dono ID proofs kal le jana\n" +
                "- Banker ko 5 baje se pehle call karna\n" +
                "- 23rd ke liye calendar block karna\n" +
                "- Sunday lunch pe mummy ke ghar jana"
        )
        // The driver can't tap the "Summarize all 4" button — auto-show the chain gist
        // a few seconds later so the whole chain UI is previewable.
        Thread.sleep(4000)
        o.setChainSummaryGenerating()
        Thread.sleep(1800)
        o.setChainSummaryReady(
            "SUMMARY: Poora plan set hai — kal office ke baad documents exchange, " +
                "banker ka kaam aaj hi, 23rd ka event confirm, aur Sunday family lunch. " +
                "Sabse zaroori: banker ko 5 baje se pehle call karna.\n" +
                "ACTIONS:\n" +
                "- Banker ko aaj 5 baje se pehle call karna (sabse urgent)\n" +
                "- Kal lease agreement aur dono ID proofs le jana\n" +
                "- 23rd ke liye calendar block karna\n" +
                "- Sunday ko mummy ke ghar lunch"
        )
    }

    private fun degraded(o: OverlayController) {
        o.showSpinner("Listening…")
        o.setInfo(null, "18:03")
        Thread.sleep(600)
        o.showMicFallbackBanner { AppLog.i("[preview] Share-screen tapped (no-op in preview).") }
        o.setStatus("listening 0.7s… (no match yet)")
        Thread.sleep(900); o.setStatus("listening 1.6s… (no match yet)")
        Thread.sleep(900); o.setStatus("listening 2.5s… (no match yet)")
        Thread.sleep(900); o.clearBanner()
        Thread.sleep(300)
        o.setCandidates(
            (1..6).map { i ->
                fake("PTT-2026062$i-WA000$i.opus", 8.0 * i, 20260620 + i, 24.0 / i)
            },
            false,
        )
        o.setTranscript("[no confident match after 12s]")
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
