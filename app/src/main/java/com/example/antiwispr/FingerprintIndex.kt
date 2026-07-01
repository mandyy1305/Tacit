package com.example.antiwispr

import java.io.File

/** One indexed voice note (fileId is assignment-order, snapshot-local). */
data class FileMeta(
    val file: File,
    val name: String,
    val mtime: Long,
    val size: Long,
    val durationSec: Double,
    val hashCount: Int
)

/** A scored candidate from a query: [aligned] = largest time-coherent landmark count. */
data class Scored(
    val fileId: Int,
    val file: File,
    val name: String,
    val aligned: Int,
    val bestOffset: Int,
    val durationSec: Double
)

/**
 * Index packing + invalidation + match policy. The query index is a single LongArray of entries,
 * each packing (hash | fileId | frameTime) with the HASH in the HIGH bits so a plain ascending
 * Arrays.sort(long[]) orders by hash. Bit budget keeps bit 63 clear (entries stay non-negative):
 *   hash 26b << 37 | fileId 15b << 22 | frameTime 22b   => max bit used = 62.
 */
object IndexConfig {
    const val SR = 16000
    const val INDEX_SECONDS = 12.0

    const val HASH_BITS = 26
    const val FILEID_BITS = 15      // cap 32767 files (asserted at build)
    const val TIME_BITS = 22        // 4.19M frames (we cap ~12 s ≈ 750)
    const val HASH_MASK = (1L shl HASH_BITS) - 1
    const val FILEID_MASK = (1L shl FILEID_BITS) - 1
    const val TIME_MASK = (1L shl TIME_BITS) - 1
    const val MAX_FILES = (1 shl FILEID_BITS) - 1
    const val OFFSET_BIAS = 1 shl 20 // make (fileFrame - capFrame) non-negative for packing

    // Query safety: a capture hash matching more than this many postings is a "stop-hash" (too common
    // to be discriminative — e.g. silence/noise on the mic fallback) and is skipped. And total match
    // events are hard-capped so a degenerate capture can never OOM the process (this crashed before).
    const val MAX_POSTINGS_PER_HASH = 800
    const val MAX_QUERY_EVENTS = 1_500_000 // 1.5M longs ≈ 12 MB ceiling

    // ---- match policy (early-stop gate) ----
    // Calibrated for BOTH clean internal capture AND the noisier mic fallback: judge by how much the
    // top dominates #2, plus a small absolute floor. Do NOT gate on fraction-of-capture-hashes or a
    // time-growing floor — those were tuned for clean audio (~99% of hashes align) and are unreachable
    // over the mic (only a few % align; aligned counts land in the tens–low-hundreds).
    const val MARGIN_RATIO = 3.0      // top must beat #2 by this factor
    const val MIN_ALIGNED = 30        // absolute floor (noise guard)

    fun pack(hash: Long, fileId: Int, time: Int): Long =
        ((hash and HASH_MASK) shl (FILEID_BITS + TIME_BITS)) or
            ((fileId.toLong() and FILEID_MASK) shl TIME_BITS) or
            (time.toLong() and TIME_MASK)

    fun hashOf(entry: Long): Long = (entry ushr (FILEID_BITS + TIME_BITS)) and HASH_MASK
    fun fileIdOf(entry: Long): Int = ((entry ushr TIME_BITS) and FILEID_MASK).toInt()
    fun timeOf(entry: Long): Int = (entry and TIME_MASK).toInt()

    /** Identifies the fingerprint params; a change invalidates a persisted index. */
    fun paramsSignature(): Int {
        var h = 17
        h = 31 * h + Fingerprinter.NFFT
        h = 31 * h + Fingerprinter.HOP
        h = 31 * h + Fingerprinter.BANDS
        h = 31 * h + Fingerprinter.FAN_T
        h = 31 * h + Fingerprinter.FAN_OUT
        h = 31 * h + Fingerprinter.PEAK_REL_FRAC.toRawBits()
        h = 31 * h + Fingerprinter.GLOBAL_FLOOR_FRAC.toRawBits()
        h = 31 * h + INDEX_SECONDS.toInt()
        h = 31 * h + SR
        return h
    }

    /** Confident when the top clears a small absolute floor AND dominates #2 by [MARGIN_RATIO]×.
     *  Stability (not firing on a lucky tick) is enforced by the caller requiring CONFIRM_TICKS
     *  consecutive ticks with the same winner. */
    fun confident(top: Scored?, second: Scored?): Boolean {
        if (top == null || top.aligned < MIN_ALIGNED) return false
        return top.aligned >= MARGIN_RATIO * maxOf(second?.aligned ?: 0, 1)
    }
}

/**
 * Immutable, lock-free query snapshot. [packed] is sorted by hash (high bits). Query does a
 * binary-search per capture hash, emits (fileId, offset) match events into a primitive long[],
 * sorts once, and linear-scans runs to find each file's largest offset bin — no HashMap on the hot
 * path, no boxing during accumulation.
 */
class IndexSnapshot(val packed: LongArray, val metas: Array<FileMeta>) {

    val fileCount: Int get() = metas.size
    val hashCount: Int get() = packed.size

    private fun lowerBoundByHash(h: Long): Int {
        var lo = 0
        var hi = packed.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (IndexConfig.hashOf(packed[mid]) < h) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun query(capFp: Fingerprint): List<Scored> {
        if (packed.isEmpty() || capFp.size == 0) return emptyList()

        val maxEvents = IndexConfig.MAX_QUERY_EVENTS
        var ev = LongArray(minOf(maxEvents, maxOf(1024, capFp.size * 4)))
        var n = 0
        var skippedCommon = 0
        var capped = false
        loop@ for (i in 0 until capFp.size) {
            val h = capFp.hashes[i] and IndexConfig.HASH_MASK
            val qt = capFp.times[i]
            val lo = lowerBoundByHash(h)
            val hi = lowerBoundByHash(h + 1)                 // first entry with hash > h
            val postings = hi - lo
            if (postings <= 0) continue
            if (postings > IndexConfig.MAX_POSTINGS_PER_HASH) { skippedCommon++; continue } // stop-hash
            var idx = lo
            while (idx < hi) {
                if (n == ev.size) {
                    if (n >= maxEvents) { capped = true; break@loop }   // hard ceiling — never OOM
                    ev = ev.copyOf(minOf(maxEvents, ev.size * 2))
                }
                val e = packed[idx]
                val fid = IndexConfig.fileIdOf(e)
                val off = IndexConfig.timeOf(e) - qt + IndexConfig.OFFSET_BIAS
                ev[n++] = (fid.toLong() shl 32) or (off.toLong() and 0xFFFFFFFFL)
                idx++
            }
        }
        if (skippedCommon > 0 || capped)
            AppLog.i("[index] query: $n events, skipped $skippedCommon over-common hash(es)${if (capped) " (CAPPED at $maxEvents)" else ""}.")
        if (n == 0) return emptyList()

        java.util.Arrays.sort(ev, 0, n) // groups by fileId, then offset
        val bestCount = HashMap<Int, Int>()
        val bestOff = HashMap<Int, Int>()
        var i = 0
        while (i < n) {
            val cur = ev[i]
            var j = i + 1
            while (j < n && ev[j] == cur) j++
            val count = j - i
            val fid = (cur ushr 32).toInt()
            if (count > (bestCount[fid] ?: 0)) {
                bestCount[fid] = count
                bestOff[fid] = (cur and 0xFFFFFFFFL).toInt() - IndexConfig.OFFSET_BIAS
            }
            i = j
        }

        val out = ArrayList<Scored>(bestCount.size)
        for ((fid, cnt) in bestCount) {
            val m = metas[fid]
            out.add(Scored(fid, m.file, m.name, cnt, bestOff[fid] ?: 0, m.durationSec))
        }
        out.sortByDescending { it.aligned }
        return out
    }
}
