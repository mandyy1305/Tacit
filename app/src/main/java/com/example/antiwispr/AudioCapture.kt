package com.example.antiwispr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Mic capture at 16 kHz mono PCM-16. Picks the least-processed audio source available
 * so the OS does NOT run acoustic echo cancellation / noise suppression — those would
 * actively try to remove the loudspeaker sound we are trying to record.
 */
class AudioCapture(
    private val context: Context,
    private val sampleRate: Int,
    private val log: (String) -> Unit
) {
    @Volatile
    var isRunning = false
        private set

    fun requestStop() {
        isRunning = false
    }

    @SuppressLint("MissingPermission")
    fun start(maxSeconds: Int, onRms: (Double) -> Unit, onDone: (ShortArray) -> Unit) {
        if (isRunning) {
            log("capture: already running")
            return
        }
        val am = context.getSystemService(AudioManager::class.java)
        val supportsUnprocessed =
            am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (supportsUnprocessed)
            MediaRecorder.AudioSource.UNPROCESSED
        else
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        log("capture: source=${if (supportsUnprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"} " +
                "(picked to avoid echo-cancellation that would erase the played audio)")

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            log("ERROR: getMinBufferSize returned $minBuf (16 kHz mono unsupported?)")
            return
        }
        val bufSize = maxOf(minBuf, sampleRate) * 2

        val record = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            log("ERROR: AudioRecord failed to initialize (mic permission? source unsupported?)")
            record.release()
            return
        }

        isRunning = true
        Thread {
            val total = ShortArray(maxSeconds * sampleRate)
            var filled = 0
            val chunk = ShortArray(sampleRate / 10) // ~100 ms
            try {
                record.startRecording()
                log("capture: recording started (target ${maxSeconds}s, hard cap)")
                while (isRunning && filled < total.size) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        val toCopy = minOf(n, total.size - filled)
                        System.arraycopy(chunk, 0, total, filled, toCopy)
                        filled += toCopy
                        var s = 0.0
                        for (i in 0 until n) {
                            val v = chunk[i] / 32768.0
                            s += v * v
                        }
                        onRms(sqrt(s / n))
                    } else if (n < 0) {
                        log("capture: read() error code $n")
                        break
                    }
                }
            } catch (e: Exception) {
                log("capture: exception ${e.message}")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                isRunning = false
            }
            val result = total.copyOf(filled)
            log("capture: stopped, %d samples (~%.2fs @ %d Hz)".format(filled, filled.toDouble() / sampleRate, sampleRate))
            onDone(result)
        }.also { it.isDaemon = true }.start()
    }
}
