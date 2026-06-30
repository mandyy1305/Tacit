package com.example.antiwispr

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin DSP: radix-2 FFT + log-mel spectrogram + per-band normalization.
 * No external dependencies on purpose (this is a throwaway feasibility harness).
 */

/** Iterative in-place radix-2 Cooley-Tukey FFT. size must be a power of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            val half = len / 2
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val iaRe = re[i + k]
                    val iaIm = im[i + k]
                    val ibRe0 = re[i + k + half]
                    val ibIm0 = im[i + k + half]
                    val ibRe = ibRe0 * curRe - ibIm0 * curIm
                    val ibIm = ibRe0 * curIm + ibIm0 * curRe
                    re[i + k] = iaRe + ibRe
                    im[i + k] = iaIm + ibIm
                    re[i + k + half] = iaRe - ibRe
                    im[i + k + half] = iaIm - ibIm
                    val nRe = curRe * wRe - curIm * wIm
                    val nIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                    curIm = nIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Computes a log-mel spectrogram. Defaults match the spec:
 * 16 kHz, 25 ms window (400 samp), 10 ms hop (160 samp), 40 mel bands.
 * Output is frames[frameIndex][melBand].
 */
class LogMel(
    private val sampleRate: Int = 16000,
    private val nFft: Int = 512,
    private val frameLen: Int = 400,   // 25 ms @ 16 kHz
    private val hop: Int = 160,        // 10 ms @ 16 kHz
    val nMel: Int = 40,
    private val fMin: Double = 0.0,
    private val fMax: Double = 8000.0  // Nyquist @ 16 kHz
) {
    val framesPerSecond: Double get() = sampleRate.toDouble() / hop

    private val window = DoubleArray(frameLen) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (frameLen - 1)) }
    private val nBins = nFft / 2 + 1
    private val filterbank: Array<DoubleArray> = buildFilterbank()

    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

    private fun buildFilterbank(): Array<DoubleArray> {
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val hzPoints = DoubleArray(nMel + 2) { melToHz(melMin + (melMax - melMin) * it / (nMel + 1)) }
        val bin = IntArray(nMel + 2) {
            (Math.floor((nFft + 1) * hzPoints[it] / sampleRate)).toInt().coerceIn(0, nBins - 1)
        }
        val fb = Array(nMel) { DoubleArray(nBins) }
        for (m in 1..nMel) {
            val left = bin[m - 1]
            val center = bin[m]
            val right = bin[m + 1]
            if (center > left) for (k in left until center) fb[m - 1][k] = (k - left).toDouble() / (center - left)
            if (right > center) for (k in center until right) fb[m - 1][k] = (right - k).toDouble() / (right - center)
            // degenerate filter (collapses near 0 Hz): give it at least its center bin
            if (center <= left && right <= center) fb[m - 1][center.coerceIn(0, nBins - 1)] = 1.0
        }
        return fb
    }

    /** Returns frames[frame][mel] of log mel energies. */
    fun compute(pcm: FloatArray): Array<FloatArray> {
        if (pcm.size < frameLen) return arrayOf()
        val nFrames = 1 + (pcm.size - frameLen) / hop
        val out = Array(nFrames) { FloatArray(nMel) }
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val power = DoubleArray(nBins)
        for (f in 0 until nFrames) {
            val start = f * hop
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until frameLen) re[i] = pcm[start + i].toDouble() * window[i]
            Fft.transform(re, im)
            for (k in 0 until nBins) power[k] = re[k] * re[k] + im[k] * im[k]
            val row = out[f]
            for (m in 0 until nMel) {
                var s = 0.0
                val filt = filterbank[m]
                for (k in 0 until nBins) s += filt[k] * power[k]
                row[m] = ln(s + 1e-10).toFloat()
            }
        }
        return out
    }

    companion object {
        /**
         * Per-band mean/variance normalization (CMVN) in place. Subtracting the
         * per-band mean removes static EQ coloration from the speaker+mic path;
         * dividing by the std makes the correlation amplitude-robust.
         */
        fun normalizePerBand(frames: Array<FloatArray>) {
            if (frames.isEmpty()) return
            val nMel = frames[0].size
            for (b in 0 until nMel) {
                var mean = 0.0
                for (fr in frames) mean += fr[b]
                mean /= frames.size
                var v = 0.0
                for (fr in frames) {
                    val d = fr[b] - mean
                    v += d * d
                }
                val sd = sqrt(v / frames.size) + 1e-6
                for (fr in frames) fr[b] = ((fr[b] - mean) / sd).toFloat()
            }
        }
    }
}
