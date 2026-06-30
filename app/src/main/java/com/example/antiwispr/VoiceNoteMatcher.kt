package com.example.antiwispr

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/** A voice-note file on disk that might be the one that just played. */
data class CandidateFile(
    val file: File,
    val name: String,
    val durationSec: Double,   // <0 if unknown
    val lastModified: Long,
    val score: Double = Double.NaN,    // acoustic match score (aligned-hash count); NaN if unscored
    val whatsAppDate: Int? = null,     // yyyymmdd parsed from filename
    val seq: Int? = null               // WhatsApp media sequence (WA####) parsed from filename
)

/**
 * Picks which on-disk .opus file corresponds to the audio that just played. The real
 * implementation will correlate/fingerprint [capture] against the decoded files; for now
 * that is deliberately NOT built.
 */
interface VoiceNoteMatcher {
    fun match(capture: ShortArray, durationSec: Double?, timeOfDay: String?): List<CandidateFile>
}

/**
 * ===================== STUB — NOT REAL MATCHING =====================
 * Lists .opus files in the WhatsApp Voice Notes folder(s), narrows by the
 * accessibility-reported clip duration (within a tolerance), logs the candidates, and
 * returns them newest-first. It does NOT look at [capture] at all — no correlation or
 * fingerprinting yet. Swap for a real VoiceNoteMatcher later.
 * ====================================================================
 */
class StubVoiceNoteMatcher(
    private val resolveFolders: () -> List<File>
) : VoiceNoteMatcher {

    companion object { const val DURATION_TOLERANCE_SEC = 2.0 }

    override fun match(capture: ShortArray, durationSec: Double?, timeOfDay: String?): List<CandidateFile> {
        AppLog.i("[matcher STUB] match(): captureSamples=${capture.size}, durationSec=$durationSec, timeOfDay=$timeOfDay")
        val folders = resolveFolders()
        if (folders.isEmpty()) {
            AppLog.w("[matcher STUB] no Voice Notes folder resolved — returning empty.")
            return emptyList()
        }
        val opus = ArrayList<File>()
        for (f in folders) collectOpus(f, opus)
        AppLog.i("[matcher STUB] scanned ${folders.size} folder(s); found ${opus.size} .opus file(s).")
        if (opus.isEmpty()) return emptyList()

        val all = opus.map { f -> CandidateFile(f, f.name, readDurationSec(f), f.lastModified()) }

        // (1) Narrow by DURATION (the note's total, captured pre-play). Keep files whose metadata
        //     duration is within tolerance; keep unknown-duration files rather than dropping them.
        var filtered = all
        if (durationSec != null && durationSec > 0) {
            filtered = all.filter { it.durationSec < 0 || abs(it.durationSec - durationSec) <= DURATION_TOLERANCE_SEC }
            AppLog.i("[matcher STUB] duration filter ${durationSec}s ±${DURATION_TOLERANCE_SEC}s -> ${filtered.size} of ${all.size} file(s).")
        } else {
            AppLog.i("[matcher STUB] no duration provided — skipping duration filter.")
        }

        // (2) Rank by TIMESTAMP closeness (bubble send-time vs file's modified time-of-day), then
        //     by recency. If no timestamp, just newest-first.
        val targetMin = timeOfDay?.let { parseClockToMinutes(it) }
        val sorted = if (targetMin != null) {
            AppLog.i("[matcher STUB] ranking by closeness to timestamp $timeOfDay (file mtime time-of-day), then recency.")
            filtered.sortedWith(compareBy({ clockDistanceMin(it.lastModified, targetMin) }, { -it.lastModified }))
        } else {
            AppLog.i("[matcher STUB] no timestamp — ranking newest-first.")
            filtered.sortedByDescending { it.lastModified }
        }

        sorted.take(10).forEachIndexed { i, c ->
            val dist = targetMin?.let { clockDistanceMin(c.lastModified, it) }
            AppLog.i("  cand[$i] ${c.name}  dur=%.1fs  mtime=%s%s".format(
                c.durationSec, clockOf(c.lastModified), if (dist != null) "  Δtime=${dist}min" else ""))
        }
        AppLog.i("[matcher STUB] returning ${sorted.size} candidate(s) (STUB ranking: duration filter + timestamp/recency — NOT real correlation).")
        return sorted
    }

    /** "HH:MM" -> minutes-of-day, or null if unparseable. */
    private fun parseClockToMinutes(t: String): Int? {
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun minuteOfDay(epochMs: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = epochMs
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Circular distance in minutes between a file's mtime time-of-day and the target minute. */
    private fun clockDistanceMin(epochMs: Long, targetMin: Int): Int {
        val d = abs(minuteOfDay(epochMs) - targetMin)
        return min(d, 1440 - d)
    }

    private fun clockOf(epochMs: Long): String {
        val m = minuteOfDay(epochMs)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun collectOpus(dir: File, out: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) collectOpus(c, out)
            else if (c.name.endsWith(".opus", true) || c.name.endsWith(".ogg", true)) out.add(c)
        }
    }

    private fun readDurationSec(f: File): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (e: Exception) {
            AppLog.w("[matcher STUB] duration read failed for ${f.name}: ${e.message}")
            -1.0
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
