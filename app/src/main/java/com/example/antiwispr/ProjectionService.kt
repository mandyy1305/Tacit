package com.example.antiwispr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.sqrt

/**
 * What the orchestrator needs from the capture layer — keeps it decoupled from the service.
 */
interface AudioWindowSource {
    val isSessionActive: Boolean
    val sampleRate: Int
    /** Blocking: returns the next [seconds] of audio starting at the moment of the call.
     *  MUST be called off the main thread (it waits in real time). */
    fun captureWindow(seconds: Double): ShortArray
    /** Non-blocking: current write position (total samples written since session start). */
    fun mark(): Long
    /** Non-blocking: returns samples from [mark] up to the current write head (clamped to the ring).
     *  For streaming: mark() at the start, then call repeatedly as audio accumulates. */
    fun readSince(mark: Long): ShortArray
}

/**
 * Persistent foreground service holding the MediaProjection + AudioPlaybackCapture session
 * ("Transcription active" notification). Consent is granted ONCE (from MainActivity) and the
 * session stays open. A continuous reader fills a rolling ring buffer at 16 kHz mono; the RMS
 * of every ~1 s window is logged so you can confirm real signal (not zeros). [captureWindow]
 * hands the orchestrator the next N seconds on demand.
 *
 * Android 14+ ordering: startForeground(type=mediaProjection) MUST precede getMediaProjection().
 */
class ProjectionService : Service(), AudioWindowSource {

    companion object {
        const val ACTION_START_SESSION = "com.example.antiwispr.proj.START"
        const val ACTION_STOP_SESSION = "com.example.antiwispr.proj.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RATE = "rate"

        private const val NOTIF_ID = 0x4157 // "AW"
        private const val RING_SECONDS = 16 // holds a full streaming listen window + slack

        /** Process-wide handle to the live capture source (null when no session). */
        @Volatile var source: AudioWindowSource? = null
            private set
        @Volatile var sessionActive: Boolean = false
            private set
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var reading = false
    private val main = Handler(Looper.getMainLooper())

    private var rate = 16000
    private var ring = ShortArray(0)
    private val lock = Any()
    @Volatile private var written: Long = 0L

    override val isSessionActive: Boolean get() = sessionActive
    override val sampleRate: Int get() = rate

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppLog.w("[projection] MediaProjection.onStop() — session revoked/stopped. Tearing down.")
            main.post { teardown(projectionAlreadyStopped = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession(intent)
            ACTION_STOP_SESSION -> teardown(projectionAlreadyStopped = false)
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (projection != null) {
            AppLog.i("[projection] session already open — reusing (no new consent needed).")
            return
        }
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        rate = intent.getIntExtra(EXTRA_RATE, 16000)
        if (data == null) {
            AppLog.e("[projection] no consent data in start intent.")
            stopSelf(); return
        }

        startAsForeground()
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data)
                ?: throw IllegalStateException("getMediaProjection() returned null")
            AppLog.i("[projection] MediaProjection obtained (FGS type=mediaProjection was started first, per Android 14+).")
            projection!!.registerCallback(projectionCallback, main)
            AppLog.i("[projection] MediaProjection.Callback registered.")
            buildRecorderAndStart()
            source = this
            sessionActive = true
            AppLog.i("[projection] SESSION OPEN. rolling buffer=${RING_SECONDS}s @ ${rate}Hz mono. captureWindow() ready.")
        } catch (e: Exception) {
            AppLog.e("[projection] session setup FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            teardown(projectionAlreadyStopped = false)
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO verified in MainActivity before start
    private fun buildRecorderAndStart() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        AppLog.i("[projection] AudioPlaybackCaptureConfiguration usages = MEDIA, GAME, UNKNOWN " +
                "(VOICE_COMMUNICATION is NOT capturable and would read as zeros).")

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("getMinBufferSize returned $minBuf")
        val bufSize = maxOf(minBuf, rate) * 2

        ring = ShortArray(rate * RING_SECONDS)
        synchronized(lock) { written = 0L }

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); throw IllegalStateException("AudioRecord(playback capture) failed to initialize")
        }
        record = rec
        reading = true
        rec.startRecording()
        readerThread = Thread { readerLoop(rec) }.also { it.isDaemon = true; it.start() }
        AppLog.i("[projection] continuous reader started; per-second RMS will be logged.")
    }

    private fun readerLoop(rec: AudioRecord) {
        val chunk = ShortArray(rate / 10) // ~100 ms
        var accumSq = 0.0
        var accumN = 0
        var peak = 0
        try {
            while (reading) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val cap = ring.size
                    synchronized(lock) {
                        var w = written
                        for (i in 0 until n) { ring[(w % cap).toInt()] = chunk[i]; w++ }
                        written = w
                    }
                    for (i in 0 until n) {
                        val v = chunk[i].toInt()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        val f = v / 32768.0
                        accumSq += f * f
                    }
                    accumN += n
                    if (accumN >= rate) {
                        val rms = sqrt(accumSq / accumN)
                        AppLog.i("[projection] window RMS=%.5f peak=%d (%s)".format(
                            rms, peak, if (rms < 0.0005) "≈silent — nothing playing / opted out" else "REAL SIGNAL"))
                        accumSq = 0.0; accumN = 0; peak = 0
                    }
                } else if (n < 0) {
                    AppLog.w("[projection] AudioRecord.read() error code $n")
                    break
                }
            }
        } catch (e: Exception) {
            AppLog.e("[projection] reader loop exception: ${e.message}", e)
        }
        AppLog.i("[projection] reader loop ended.")
    }

    override fun captureWindow(seconds: Double): ShortArray {
        if (!sessionActive || record == null) {
            AppLog.w("[projection] captureWindow() called with no active session — returning empty.")
            return ShortArray(0)
        }
        val need = (seconds * rate).toInt().coerceIn(1, ring.size)
        val start = synchronized(lock) { written }
        AppLog.i("[projection] captureWindow(%.1fs): collecting %d samples from now…".format(seconds, need))

        val deadline = System.currentTimeMillis() + (seconds * 1000).toLong() + 1500
        while (true) {
            val w = synchronized(lock) { written }
            if (w >= start + need) break
            if (System.currentTimeMillis() > deadline) {
                AppLog.w("[projection] captureWindow timed out waiting for samples — returning partial.")
                break
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }

        val out = ShortArray(need)
        synchronized(lock) {
            val cap = ring.size
            val avail = (written - start).coerceIn(0, need.toLong()).toInt()
            var from = start
            val oldest = written - cap
            if (from < oldest) from = oldest
            for (i in 0 until avail) out[i] = ring[((from + i) % cap).toInt()]
        }
        var s = 0.0
        for (v in out) { val f = v / 32768.0; s += f * f }
        val rms = if (out.isEmpty()) 0.0 else sqrt(s / out.size)
        AppLog.i("[projection] captureWindow done: ${out.size} samples, RMS=%.5f %s".format(
            rms, if (rms < 0.0005) "(≈silent)" else "(signal)"))
        return out
    }

    override fun mark(): Long = synchronized(lock) { written }

    override fun readSince(mark: Long): ShortArray {
        if (!sessionActive) return ShortArray(0)
        synchronized(lock) {
            val cap = ring.size
            if (cap == 0) return ShortArray(0)
            var from = mark
            val oldest = written - cap
            if (from < oldest) from = oldest // clamp: anything older fell out of the ring
            val count = (written - from).coerceIn(0, cap.toLong()).toInt()
            val out = ShortArray(count)
            for (i in 0 until count) out[i] = ring[((from + i) % cap).toInt()]
            return out
        }
    }

    private fun startAsForeground() {
        TacitNotifications.ensureChannels(this)
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ProjectionService::class.java).apply { action = ACTION_STOP_SESSION },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, TacitNotifications.CHANNEL_SESSION)
            .setContentTitle("Precision listening is on")
            .setContentText("TACIT hears WhatsApp playback directly for cleaner matches. Audio is matched and discarded.")
            .setStyle(Notification.BigTextStyle().bigText(
                "TACIT hears WhatsApp playback directly for cleaner matches. Audio is matched and discarded."))
            .setSmallIcon(R.drawable.ic_stat_tacit)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stopPi
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        AppLog.i("[projection] FGS started, type=mediaProjection (manifest foregroundServiceType=\"mediaProjection\").")
    }

    private fun teardown(projectionAlreadyStopped: Boolean) {
        reading = false
        try { readerThread?.join(300) } catch (_: Exception) {}
        readerThread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        if (!projectionAlreadyStopped) try { projection?.stop() } catch (_: Exception) {}
        projection = null
        sessionActive = false
        source = null
        AppLog.i("[projection] session CLOSED; foreground service stopping.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        reading = false
        sessionActive = false
        source = null
        super.onDestroy()
    }
}
