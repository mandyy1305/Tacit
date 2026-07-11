package com.example.antiwispr.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.antiwispr.core.AppLog
import kotlin.math.sqrt

/**
 * Mic-backed [AudioWindowSource] used as a FALLBACK when no MediaProjection screen-share session is
 * active. Mirrors ProjectionService's ring buffer (monotonic `written` + modulo `ring` under `lock`)
 * so the orchestrator's streaming loop (mark/readSince) works unchanged. Picks the least-processed mic
 * source (UNPROCESSED, else VOICE_RECOGNITION) to avoid the OS echo-cancelling away the speaker audio.
 * Over-the-air mic capture is lower quality than internal capture — that's why the overlay warns.
 */
class MicWindowSource(context: Context) : AudioWindowSource {

    companion object { private const val RING_SECONDS = 16 }

    private val appContext = context.applicationContext
    override val sampleRate = 16000
    @Volatile override var onLevel: ((Float) -> Unit)? = null

    private var record: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    override val isSessionActive: Boolean get() = running

    private val ring = ShortArray(sampleRate * RING_SECONDS)
    private val lock = Any()
    @Volatile private var written = 0L

    @SuppressLint("MissingPermission") // RECORD_AUDIO verified by the caller
    fun start(): Boolean {
        if (running) return true
        val am = appContext.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { AppLog.e("[mic] getMinBufferSize=$minBuf"); return false }
        val rec = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate) * 2)
        } catch (e: Exception) { AppLog.e("[mic] AudioRecord ctor failed: ${e.message}", e); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); AppLog.e("[mic] AudioRecord not initialized"); return false }
        record = rec
        written = 0L
        running = true
        rec.startRecording()
        readerThread = Thread { readerLoop(rec) }.also { it.isDaemon = true; it.start() }
        AppLog.i("[mic] fallback capture started (source=${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"}, 16kHz mono).")
        return true
    }

    fun stop() {
        running = false
        try { readerThread?.join(300) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        AppLog.i("[mic] fallback capture stopped.")
    }

    private fun readerLoop(rec: AudioRecord) {
        val chunk = ShortArray(sampleRate / 10)
        val cap = ring.size
        var accumSq = 0.0; var accumN = 0; var peak = 0
        try {
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(lock) {
                        var w = written
                        for (i in 0 until n) { ring[(w % cap).toInt()] = chunk[i]; w++ }
                        written = w
                    }
                    for (i in 0 until n) {
                        val v = chunk[i].toInt(); val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        val f = v / 32768.0; accumSq += f * f
                    }
                    accumN += n
                    // Per-chunk RAW RMS (~10 Hz) for the live listening waveform; the overlay does
                    // the gate/AGC/gamma conditioning + envelope, so it must receive the raw level.
                    onLevel?.let { cb ->
                        var sq = 0.0
                        for (i in 0 until n) { val f = chunk[i] / 32768.0; sq += f * f }
                        cb(sqrt(sq / n).toFloat())
                    }
                    if (accumN >= sampleRate) {
                        val rms = sqrt(accumSq / accumN)
                        AppLog.i("[mic] window RMS=%.5f peak=%d (%s)".format(
                            rms, peak, if (rms < 0.0005) "≈SILENT — mic likely muted (background) or nothing playing" else "signal"))
                        accumSq = 0.0; accumN = 0; peak = 0
                    }
                } else if (n < 0) { AppLog.w("[mic] read() error $n"); break }
            }
        } catch (e: Exception) { AppLog.e("[mic] reader loop: ${e.message}", e) }
    }

    override fun mark(): Long = synchronized(lock) { written }

    override fun readSince(mark: Long): ShortArray {
        if (!running) return ShortArray(0)
        synchronized(lock) {
            val cap = ring.size
            var from = mark
            val oldest = written - cap
            if (from < oldest) from = oldest
            val count = (written - from).coerceIn(0, cap.toLong()).toInt()
            val out = ShortArray(count)
            for (i in 0 until count) out[i] = ring[((from + i) % cap).toInt()]
            return out
        }
    }

    override fun captureWindow(seconds: Double): ShortArray {
        // Not used by the streaming loop, but part of the interface. Blocking collect from now.
        val need = (seconds * sampleRate).toInt().coerceIn(1, ring.size)
        val start = synchronized(lock) { written }
        val deadline = System.currentTimeMillis() + (seconds * 1000).toLong() + 1500
        while (synchronized(lock) { written } < start + need && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
        return readSince(start)
    }
}
