package com.example.antiwispr

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real voice-note matcher.
 *
 * Stage 1 (cheap) — shortlist: scan filenames + mtime, rank by RECENCY (filename date + WhatsApp
 *   sequence, then mtime), then narrow by DURATION when a reliable pre-play duration is available.
 *   NOTE: we deliberately do NOT rank/filter by time-of-day — the bubble's send time and a file's
 *   modified-time are different clocks, which produced wrong matches.
 * Stage 2 (acoustic) — DECIDER is normalized cross-correlation of the log-mel spectrograms
 *   ([Matcher.score]) plus a raw-waveform cross-correlation ([Matcher.scorePcm]). Per-band
 *   normalization (CMVN) absorbs the EQ/resampling differences between WhatsApp's rendered playback
 *   and our own decode — which is exactly why this beats exact-peak fingerprint hashing here. The
 *   Shazam-style fingerprint score is still computed and logged for comparison, but does not decide.
 * Stage 3 — rank by mel correlation; confident when the top clears a floor and beats #2 by a margin.
 */
class RealVoiceNoteMatcher(
    private val context: Context,
    private val listFiles: () -> List<File> = { VoiceNotes.listOpusFiles() }
) : VoiceNoteMatcher {

    companion object {
        const val SR = 16000
        const val DUR_TOL_SEC = 1.5
        const val RECENCY_WINDOW = 60        // files kept after the cheap recency rank
        const val ACOUSTIC_MAX = 8           // files actually decoded + correlated
        const val DECODE_SECONDS = 9.0       // how much of each candidate to decode
        const val COMPARE_SECONDS = 6.0      // reference window length
        const val MAX_LAG_SECONDS = 2.0      // alignment search (tap->playback delay)
        const val MIN_ALIGNED = 100          // top fingerprint hit-count must reach this floor
        const val MARGIN_RATIO = 3.0         // ...and beat #2 by at least this factor
        const val SILENCE_RMS = 0.0005
    }

    private val durationCache = HashMap<String, Double>()
    // LRU of decoded candidate PCM (keyed path|mtime|size) so repeated taps skip re-decoding.
    private val pcmCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > 12
    }

    override fun match(capture: ShortArray, durationSec: Double?, timeOfDay: String?): List<CandidateFile> {
        AppLog.i("[matcher] match(): captureSamples=${capture.size}, durationSec=$durationSec, timeOfDay=$timeOfDay (timeOfDay logged only — not used to filter)")

        val files = listFiles()
        if (files.isEmpty()) { AppLog.w("[matcher] no .opus files found — empty."); return emptyList() }
        AppLog.i("[matcher] scanned ${files.size} voice-note file(s).")

        // ---- Stage 1: recency shortlist + duration filter ----------------------------
        data class Meta(val f: File, val date: Int?, val seq: Int?, val mtime: Long)
        val metas = files.map { f ->
            val p = VoiceNotes.parseWhatsAppName(f.name)
            Meta(f, p?.dateYmd, p?.seq, f.lastModified())
        }
        val ranked = metas.sortedWith(
            compareByDescending<Meta> { it.date ?: Int.MIN_VALUE }
                .thenByDescending { it.seq ?: Int.MIN_VALUE }
                .thenByDescending { it.mtime }
        )
        val window = ranked.take(RECENCY_WINDOW)
        AppLog.i("[matcher] stage1 recency window = ${window.size} of ${metas.size} (newest first).")

        var shortlist = window
        if (durationSec != null && durationSec > 0) {
            val kept = window.filter { m -> val d = durationOf(m.f); d < 0 || abs(d - durationSec) <= DUR_TOL_SEC }
            if (kept.isEmpty()) {
                AppLog.w("[matcher] duration filter ${durationSec}s ±${DUR_TOL_SEC}s removed all — falling back to recency window.")
            } else {
                shortlist = kept
                AppLog.i("[matcher] duration filter ${durationSec}s ±${DUR_TOL_SEC}s -> ${kept.size} of ${window.size}.")
            }
        } else {
            AppLog.w("[matcher] NO reliable duration (pre-play cache miss) — skipping duration filter; relying on acoustic match.")
        }
        shortlist = shortlist.take(ACOUSTIC_MAX)
        AppLog.i("[matcher] acoustic shortlist = ${shortlist.size} file(s) (cap $ACOUSTIC_MAX).")

        // ---- Stage 2: cross-correlation (decider) + fingerprint (logged) --------------
        val capFloat = FloatArray(capture.size) { capture[it] / 32768.0f }
        val capRms = rms(capFloat)
        if (capRms < SILENCE_RMS) AppLog.w("[matcher] capture is ≈silent (rms=%.5f) — matching will be meaningless.".format(capRms))

        // DECIDER: Shazam-style fingerprint offset voting. It votes over ALL time offsets, so it's
        // robust to the unknown tap->playback lead-in (which exceeds the cross-correlation lag cap —
        // that's why melR comes out near-zero even for the true file). melR is logged for reference.
        val capFp = Fingerprinter.fingerprint(capFloat)
        val capQuery = capFp.toQueryMap()
        val fps = Fingerprinter.framesPerSecond(SR)
        AppLog.i("[matcher] capture fingerprint: ${capFp.size} hashes.")

        val mel = LogMel(sampleRate = SR)
        val compareFrames = (COMPARE_SECONDS * mel.framesPerSecond).roundToInt()
        val maxOffsetFrames = (MAX_LAG_SECONDS * mel.framesPerSecond).roundToInt()
        val capMel = mel.compute(capFloat)
        LogMel.normalizePerBand(capMel)

        val scored = ArrayList<CandidateFile>(shortlist.size)
        for (m in shortlist) {
            val candFloat = decodedPcm(m.f)
            if (candFloat.isEmpty()) {
                AppLog.w("  cand ${m.f.name}: decode empty -> score 0")
                scored.add(CandidateFile(m.f, m.f.name, durationOf(m.f), m.mtime, 0.0, m.date, m.seq))
                continue
            }
            val fp = Fingerprinter.matchOffsetVoting(capQuery, Fingerprinter.fingerprint(candFloat))
            val candMel = mel.compute(candFloat)
            LogMel.normalizePerBand(candMel)
            val melS = Matcher.score(capMel, candMel, compareFrames, maxOffsetFrames)
            val frac = if (capFp.size > 0) 100.0 * fp.aligned / capFp.size else 0.0
            AppLog.i("  cand ${m.f.name}  dur=%.1fs  fpAligned=%d @ %.2fs (%.1f%% of cap)  [melR=%.4f@%.2fs]".format(
                durationOf(m.f), fp.aligned, fp.bestOffset / fps, frac, melS.bestR, melS.bestOffsetFrames / mel.framesPerSecond))
            scored.add(CandidateFile(m.f, m.f.name, durationOf(m.f), m.mtime, fp.aligned.toDouble(), m.date, m.seq))
        }

        // ---- Stage 3: rank + confidence ----------------------------------------------
        val result = scored.sortedByDescending { it.score }
        val top = result.firstOrNull()
        val second = result.getOrNull(1)
        if (top != null) {
            val confident = top.score >= MIN_ALIGNED && (second == null || top.score >= MARGIN_RATIO * maxOf(second.score, 1.0))
            if (confident) AppLog.i("[matcher] ✅ CONFIDENT: ${top.name} fpAligned=%.0f (#2=%.0f).".format(top.score, second?.score ?: 0.0))
            else AppLog.w("[matcher] ⚠ LOW CONFIDENCE: best=${top.name} fpAligned=%.0f, #2=%.0f (need ≥$MIN_ALIGNED and ≥${MARGIN_RATIO}× #2).".format(top.score, second?.score ?: 0.0))
        }
        return result
    }

    // ---- helpers ----------------------------------------------------------------

    private fun decodedPcm(f: File): FloatArray {
        val key = "${f.absolutePath}|${f.lastModified()}|${f.length()}"
        pcmCache[key]?.let { return it }
        val pcm = try {
            OpusDecoder.decodeToMono16k(context, Uri.fromFile(f), DECODE_SECONDS) { AppLog.i("[matcher] decode ${f.name}: $it") }
        } catch (e: Exception) {
            AppLog.e("[matcher] decode failed for ${f.name}: ${e.message}", e)
            FloatArray(0)
        }
        if (pcm.isNotEmpty()) pcmCache[key] = pcm
        return pcm
    }

    private fun durationOf(f: File): Double {
        val key = "${f.absolutePath}|${f.lastModified()}|${f.length()}"
        durationCache[key]?.let { return it }
        val mmr = MediaMetadataRetriever()
        val d = try {
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (e: Exception) {
            AppLog.w("[matcher] duration read failed for ${f.name}: ${e.message}")
            -1.0
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        durationCache[key] = d
        return d
    }

    private fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size)
    }
}
