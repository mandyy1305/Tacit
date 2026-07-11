package com.example.antiwispr.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes an audio file (WhatsApp voice notes are Ogg/Opus) to mono 16 kHz float PCM
 * using only Android's built-in MediaExtractor + MediaCodec. The Opus decoder emits
 * 48 kHz PCM; we downmix to mono and linearly resample to 16 kHz to match the capture.
 */
object OpusDecoder {

    /**
     * @param maxSeconds how much of the file to decode (we only need the first few seconds).
     * @return mono PCM at 16 kHz in [-1, 1].
     */
    fun decodeToMono16k(
        context: Context,
        uri: Uri,
        maxSeconds: Double,
        log: (String) -> Unit
    ): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "no audio track in file" }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            log("    decode: mime=$mime")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val chunks = ArrayList<FloatArray>()
            var totalMono = 0
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // target measured at decoder output rate; refined after format change
            fun targetSamples() = (maxSeconds * rate).toInt()

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val mono = readMono(outBuf, info.size, channels, encoding)
                            chunks.add(mono)
                            totalMono += mono.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        if (totalMono >= targetSamples()) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        log("    decode: output rate=$rate ch=$channels enc=$encoding")
                    }
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED -> just loop
                }
            }

            // concatenate mono chunks (still at decoder 'rate')
            val mono = FloatArray(totalMono)
            var p = 0
            for (c in chunks) {
                System.arraycopy(c, 0, mono, p, c.size)
                p += c.size
            }
            val out = resampleLinear(mono, rate, 16000)
            log("    decode: ${mono.size} samp @${rate}Hz -> ${out.size} samp @16000Hz")
            return out
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Reads one output buffer and downmixes to mono float in [-1,1]. */
    private fun readMono(buf: java.nio.ByteBuffer, byteSize: Int, channels: Int, encoding: Int): FloatArray {
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val n = byteSize / 4
            val tmp = FloatArray(n)
            fb.get(tmp)
            downmix(tmp, channels)
        } else {
            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val n = byteSize / 2
            val tmp = FloatArray(n)
            for (i in 0 until n) tmp[i] = sb.get(i) / 32768.0f
            downmix(tmp, channels)
        }
    }

    private fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0.0f
            val base = f * channels
            for (c in 0 until channels) s += interleaved[base + c]
            out[f] = s / channels
        }
        return out
    }

    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (src.isEmpty() || srcRate == dstRate) return src
        val dstLen = (src.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(dstLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until dstLen) {
            val sp = i * ratio
            val i0 = sp.toInt()
            val frac = (sp - i0).toFloat()
            val a = src[i0]
            val b = if (i0 + 1 < src.size) src[i0 + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
