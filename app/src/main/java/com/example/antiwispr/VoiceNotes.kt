package com.example.antiwispr

import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/**
 * Resolves where WhatsApp keeps voice notes. WhatsApp moved them to Android/media/... on
 * Android 11+ (that subtree is readable with all-files access, unlike Android/data). We try
 * the known locations across WhatsApp / WhatsApp Business / legacy layouts and report what's
 * actually present on THIS device — so you can confirm the path or tell me a different one.
 */
object VoiceNotes {

    private fun roots(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(ext, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
            File(ext, "WhatsApp/Media/WhatsApp Voice Notes"),                 // legacy (pre-Android 11)
            File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio") // some builds use Audio/
        )
    }

    /** Folders that actually exist right now. */
    fun resolveFolders(): List<File> = roots().filter { it.exists() && it.isDirectory }

    /** Recursively collect every .opus/.ogg under the resolved folders (handles the numbered
     *  subfolders like 202627/). Cheap — no decoding or metadata reads. */
    fun listOpusFiles(): List<File> {
        val out = ArrayList<File>()
        for (root in resolveFolders()) collectOpus(root, out)
        return out
    }

    private fun collectOpus(dir: File, out: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) collectOpus(c, out)
            else if (c.name.endsWith(".opus", true) || c.name.endsWith(".ogg", true)) out.add(c)
        }
    }

    /** Audio duration in seconds via MediaMetadataRetriever; -1.0 on failure. Decodes container
     *  metadata only (no PCM), so it's cheap enough to probe a handful of chain-candidate files. */
    fun readDurationSec(f: File): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Exception) {
            -1.0
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Date (yyyymmdd) + WhatsApp media sequence parsed from a voice-note filename, e.g.
     *  "PTT-20260629-WA0016.opus" -> Parsed(20260629, 16). WA#### is a monotonic counter, so a
     *  higher seq on the same date is later. null when the name doesn't match the pattern. */
    data class Parsed(val dateYmd: Int, val seq: Int)

    private val nameRegex = Regex("""(?:PTT|AUD)-(\d{8})-WA(\d+)""", RegexOption.IGNORE_CASE)

    fun parseWhatsAppName(name: String): Parsed? {
        val m = nameRegex.find(name) ?: return null
        val date = m.groupValues[1].toIntOrNull() ?: return null
        val seq = m.groupValues[2].toIntOrNull() ?: return null
        return Parsed(date, seq)
    }

    // ---- clock helpers (shared by the matchers) ---------------------------------

    /** "HH:MM" -> minutes-of-day [0,1439], or null if unparseable. */
    fun parseClockToMinutes(t: String): Int? {
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun minuteOfDay(epochMs: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = epochMs
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Circular distance in minutes between a file's mtime time-of-day and a target minute. */
    fun clockDistanceMin(epochMs: Long, targetMin: Int): Int {
        val d = abs(minuteOfDay(epochMs) - targetMin)
        return min(d, 1440 - d)
    }

    fun clockOf(epochMs: Long): String {
        val m = minuteOfDay(epochMs)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Human-readable status of every known path; also logs it. Used by the "Verify" button. */
    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("[voicenotes] externalStorage=${Environment.getExternalStorageDirectory()}")
        sb.appendLine("[voicenotes] allFilesAccess=${Environment.isExternalStorageManager()}")
        for (r in roots()) {
            if (r.exists() && r.isDirectory) sb.appendLine("  ✓ EXISTS (${countOpus(r)} .opus): ${r.absolutePath}")
            else sb.appendLine("  ✗ missing: ${r.absolutePath}")
        }
        val found = resolveFolders()
        if (found.isEmpty())
            sb.appendLine("[voicenotes] NONE of the known paths exist. If yours differs, tell me the exact path and I'll add it.")
        else
            sb.appendLine("[voicenotes] will use ${found.size} folder(s).")
        val s = sb.toString().trimEnd()
        AppLog.i(s)
        return s
    }

    private fun countOpus(dir: File): Int {
        var c = 0
        val ch = dir.listFiles() ?: return 0
        for (f in ch) {
            if (f.isDirectory) c += countOpus(f)
            else if (f.name.endsWith(".opus", true)) c++
        }
        return c
    }
}
