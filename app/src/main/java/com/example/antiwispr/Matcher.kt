package com.example.antiwispr

import kotlin.math.sqrt

/**
 * Sliding normalized cross-correlation (Pearson r) between the mic-capture
 * spectrogram and each candidate spectrogram, searching over a lag range to
 * absorb the unknown delay between "Start capture" and actual playback.
 */
object Matcher {

    data class Score(val bestR: Double, val bestOffsetFrames: Int)

    data class PcmScore(val bestR: Double, val bestLagSeconds: Double)

    /**
     * "Simple" normalized cross-correlation directly on the decoded PCM waveforms, over a
     * lag range. This is the clean-audio counterpart to the log-mel score: when internal
     * capture yields the same source samples (not a mic re-recording), the raw waveform
     * should line up almost perfectly, so a plain time-domain correlation alone may already
     * pick the right file. Over the air this is useless (room/phase/EQ destroy the waveform);
     * it is meaningful only for the internal-capture path.
     *
     * Implementation: both signals are de-meaned, the raw lag products are computed in one
     * shot via FFT cross-correlation (O(n log n) instead of O(n*lags)), and each lag is
     * normalized by the candidate energy and the per-lag capture-window energy (a prefix-sum
     * lookup). The result at each lag is therefore a cosine/Pearson-style r in roughly [-1, 1].
     *
     * @param capture        full capture PCM (16 kHz mono, [-1, 1])
     * @param candidate      decoded candidate PCM (16 kHz mono, [-1, 1])
     * @param sampleRate     samples per second (16000)
     * @param compareSeconds length of the candidate window to match
     * @param maxLagSeconds  how far to slide the window into the capture
     */
    fun scorePcm(
        capture: FloatArray,
        candidate: FloatArray,
        sampleRate: Int,
        compareSeconds: Double,
        maxLagSeconds: Double
    ): PcmScore {
        if (capture.isEmpty() || candidate.isEmpty()) return PcmScore(0.0, 0.0)

        val refLen = minOf((compareSeconds * sampleRate).toInt(), candidate.size, capture.size)
        if (refLen <= 0) return PcmScore(0.0, 0.0)
        val maxLag = minOf((maxLagSeconds * sampleRate).toInt(), capture.size - refLen).coerceAtLeast(0)

        // De-mean the candidate window.
        val cand = DoubleArray(refLen)
        var cm = 0.0
        for (i in 0 until refLen) cm += candidate[i]
        cm /= refLen
        var candE = 0.0
        for (i in 0 until refLen) {
            val v = candidate[i] - cm
            cand[i] = v
            candE += v * v
        }
        val candNorm = sqrt(candE)
        if (candNorm <= 0.0) return PcmScore(0.0, 0.0)

        // De-mean the capture (global mean is enough for near-zero-mean audio).
        val m = capture.size
        val cap = DoubleArray(m)
        var capMean = 0.0
        for (i in 0 until m) capMean += capture[i]
        capMean /= m
        for (i in 0 until m) cap[i] = capture[i] - capMean

        // Raw lag products S[lag] = sum_i cand[i] * cap[lag+i], via FFT: ifft(conj(FFT(cand)) * FFT(cap)).
        var nfft = 1
        val need = m + refLen
        while (nfft < need) nfft = nfft shl 1
        val aRe = DoubleArray(nfft); val aIm = DoubleArray(nfft)
        val bRe = DoubleArray(nfft); val bIm = DoubleArray(nfft)
        for (i in 0 until refLen) aRe[i] = cand[i]
        for (i in 0 until m) bRe[i] = cap[i]
        Fft.transform(aRe, aIm)
        Fft.transform(bRe, bIm)
        val cRe = DoubleArray(nfft); val cIm = DoubleArray(nfft)
        for (k in 0 until nfft) {
            val ar = aRe[k]; val ai = -aIm[k]   // conj(A)
            val br = bRe[k]; val bi = bIm[k]
            cRe[k] = ar * br - ai * bi
            cIm[k] = ar * bi + ai * br
        }
        // inverse FFT via conjugation: ifft(x) = conj(fft(conj(x))) / n  (real part is all we need)
        for (k in 0 until nfft) cIm[k] = -cIm[k]
        Fft.transform(cRe, cIm)

        // Per-lag normalization uses the capture-window energy from a prefix sum of cap^2.
        val pre = DoubleArray(m + 1)
        for (i in 0 until m) pre[i + 1] = pre[i] + cap[i] * cap[i]

        var best = -2.0
        var bestLag = 0
        for (lag in 0..maxLag) {
            val raw = cRe[lag] / nfft
            val capE = pre[lag + refLen] - pre[lag]
            val denom = candNorm * sqrt(capE)
            val r = if (denom <= 0.0) 0.0 else raw / denom
            if (r > best) {
                best = r
                bestLag = lag
            }
        }
        return PcmScore(best, bestLag.toDouble() / sampleRate)
    }

    /**
     * @param capture    per-band-normalized capture spectrogram [frame][mel] (long, ~9 s)
     * @param candidate  per-band-normalized candidate spectrogram [frame][mel]
     * @param compareFrames    how many candidate frames to match (e.g. 600 = 6 s)
     * @param maxOffsetFrames  max lag to slide into the capture (e.g. 200 = 2 s)
     */
    fun score(
        capture: Array<FloatArray>,
        candidate: Array<FloatArray>,
        compareFrames: Int,
        maxOffsetFrames: Int
    ): Score {
        if (capture.isEmpty() || candidate.isEmpty()) return Score(0.0, 0)
        val nMel = capture[0].size

        // reference window = first compareFrames of the candidate, but no longer
        // than what either signal can supply.
        var refLen = minOf(compareFrames, candidate.size, capture.size)
        if (refLen <= 0) return Score(0.0, 0)
        // how far we can slide while still keeping a full refLen window inside the capture
        var maxO = minOf(maxOffsetFrames, capture.size - refLen)
        if (maxO < 0) {
            // capture shorter than the reference window: shrink window, no sliding
            refLen = capture.size
            maxO = 0
        }

        var best = -2.0
        var bestO = 0
        for (o in 0..maxO) {
            val r = pearson(capture, o, candidate, refLen, nMel)
            if (r > best) {
                best = r
                bestO = o
            }
        }
        return Score(best, bestO)
    }

    /** Pearson correlation over the flattened refLen*nMel window (capture[off..] vs candidate[0..]). */
    private fun pearson(
        cap: Array<FloatArray>,
        off: Int,
        ref: Array<FloatArray>,
        refLen: Int,
        nMel: Int
    ): Double {
        val n = refLen.toLong() * nMel
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until refLen) {
            val a = cap[off + i]
            val b = ref[i]
            for (m in 0 until nMel) {
                sa += a[m]
                sb += b[m]
            }
        }
        val ma = sa / n
        val mb = sb / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until refLen) {
            val a = cap[off + i]
            val b = ref[i]
            for (m in 0 until nMel) {
                val x = a[m] - ma
                val y = b[m] - mb
                num += x * y
                da += x * x
                db += y * y
            }
        }
        return if (da <= 0.0 || db <= 0.0) 0.0 else num / sqrt(da * db)
    }
}
