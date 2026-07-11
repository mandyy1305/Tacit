package com.example.antiwispr.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-Kotlin DSP: radix-2 FFT. No external dependencies on purpose.
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
