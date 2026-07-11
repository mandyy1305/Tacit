package com.example.antiwispr.match

import com.example.antiwispr.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shazam-style acoustic fingerprint.
 *
 * Pipeline: magnitude spectrogram (reusing [Fft]) -> sparse spectral-peak "constellation"
 * (strongest bin per log-spaced band per frame, above an adaptive floor) -> combinatorial hashes
 * pairing each anchor peak with a few later peaks in a target zone. Matching is time-offset
 * voting: for every hash shared between query and candidate, bin the time difference; the size of
 * the largest bin is the number of time-coherent landmark matches. For clean same-source audio the
 * true file yields a tall single-offset peak while unrelated files scatter near zero.
 *
 * A [Fingerprint] is just parallel arrays of (hash, anchorTime-in-frames).
 */
class Fingerprint(val hashes: LongArray, val times: IntArray) {
    val size: Int get() = hashes.size

    /** Build a hash -> [anchor times] lookup for the query (capture) side. */
    fun toQueryMap(): HashMap<Long, MutableList<Int>> {
        val m = HashMap<Long, MutableList<Int>>(hashes.size * 2)
        for (i in hashes.indices) m.getOrPut(hashes[i]) { ArrayList(2) }.add(times[i])
        return m
    }
}

/** Result of offset voting: [aligned] = largest time-coherent bin (the score), [bestOffset] its
 *  frame offset, [totalMatches] all hash collisions (for diagnostics). */
data class FpScore(val aligned: Int, val bestOffset: Int, val totalMatches: Int)

object Fingerprinter {
    // Spectrogram (16 kHz mono). 1024-pt FFT, 256 hop => ~62.5 frames/s, ~15.6 Hz/bin.
    const val NFFT = 1024
    const val HOP = 256
    const val BANDS = 6              // log-spaced frequency bands; one peak kept per band per frame
    const val FAN_T = 48             // target-zone width (frames, ~0.77 s)
    const val FAN_OUT = 5            // landmark pairs per anchor peak
    const val PEAK_REL_FRAC = 0.20f  // a peak must be >= this * the frame's max magnitude
    const val GLOBAL_FLOOR_FRAC = 0.02f // ...and >= this * the whole clip's max magnitude

    private val hann = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (NFFT - 1)) }

    fun framesPerSecond(sampleRate: Int): Double = sampleRate.toDouble() / HOP

    fun fingerprint(pcm: FloatArray): Fingerprint {
        if (pcm.size < NFFT) return Fingerprint(LongArray(0), IntArray(0))
        val nFrames = 1 + (pcm.size - NFFT) / HOP
        val nBins = NFFT / 2 // 512; usable bins 0..nBins

        // Spectrogram magnitudes + global max.
        val mags = Array(nFrames) { FloatArray(nBins + 1) }
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        var globalMax = 0.0
        for (fi in 0 until nFrames) {
            val start = fi * HOP
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until NFFT) re[i] = pcm[start + i] * hann[i]
            Fft.transform(re, im)
            val row = mags[fi]
            for (k in 0..nBins) {
                val m = hypot(re[k], im[k])
                row[k] = m.toFloat()
                if (m > globalMax) globalMax = m
            }
        }
        val floor = (globalMax * GLOBAL_FLOOR_FRAC).toFloat()

        // Geometric (log-spaced) band edges over bins [1, nBins].
        val edges = IntArray(BANDS + 1)
        val lo = 1.0
        val hi = nBins.toDouble()
        for (b in 0..BANDS) edges[b] = (lo * (hi / lo).pow(b.toDouble() / BANDS)).roundToInt().coerceIn(1, nBins)

        // Peak picking: strongest bin per band per frame, above adaptive + global thresholds.
        val peakT = ArrayList<Int>()
        val peakF = ArrayList<Int>()
        for (fi in 0 until nFrames) {
            val row = mags[fi]
            var frameMax = 0f
            for (k in 1..nBins) if (row[k] > frameMax) frameMax = row[k]
            if (frameMax <= 0f) continue
            val relThresh = frameMax * PEAK_REL_FRAC
            for (b in 0 until BANDS) {
                var bestBin = -1
                var bestMag = 0f
                for (k in edges[b] until edges[b + 1]) if (row[k] > bestMag) { bestMag = row[k]; bestBin = k }
                if (bestBin >= 0 && bestMag >= relThresh && bestMag >= floor) {
                    peakT.add(fi); peakF.add(bestBin)
                }
            }
        }

        // Combinatorial hashing: anchor peak paired with up to FAN_OUT later peaks in the zone.
        val hashes = ArrayList<Long>(peakT.size * FAN_OUT)
        val times = ArrayList<Int>(peakT.size * FAN_OUT)
        val n = peakT.size
        for (i in 0 until n) {
            val ta = peakT[i]
            val fa = peakF[i].toLong()
            var made = 0
            var j = i + 1
            while (j < n && made < FAN_OUT) {
                val dt = peakT[j] - ta
                if (dt < 1) { j++; continue }
                if (dt > FAN_T) break
                val fb = peakF[j].toLong()
                // pack f1(10b) | f2(10b) | dt(6b)
                val h = (fa shl 16) or (fb shl 6) or dt.toLong()
                hashes.add(h); times.add(ta); made++
                j++
            }
        }
        return Fingerprint(hashes.toLongArray(), times.toIntArray())
    }

    /** Time-offset voting. [queryMap] is the capture's hash->times; [cand] the candidate file's. */
    fun matchOffsetVoting(queryMap: Map<Long, MutableList<Int>>, cand: Fingerprint): FpScore {
        if (queryMap.isEmpty() || cand.size == 0) return FpScore(0, 0, 0)
        val hist = HashMap<Int, Int>()
        var total = 0
        for (i in cand.hashes.indices) {
            val qs = queryMap[cand.hashes[i]] ?: continue
            val tf = cand.times[i]
            for (tq in qs) {
                val d = tf - tq
                hist[d] = (hist[d] ?: 0) + 1
                total++
            }
        }
        var bestD = 0
        var bestC = 0
        for ((d, c) in hist) if (c > bestC) { bestC = c; bestD = d }
        return FpScore(bestC, bestD, total)
    }
}
