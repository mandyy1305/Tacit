package com.example.antiwispr.transcribe

import android.content.Context
import android.net.Uri
import com.example.antiwispr.audio.OpusDecoder
import com.example.antiwispr.core.AppLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device whisper-small ASR via sherpa-onnx (ONNX Runtime).
 *
 * [WhisperModel] owns the model files (downloaded once to filesDir) + their download state.
 * [WhisperTranscriber] implements the existing [Transcriber]: it lazily builds ONE
 * sherpa-onnx OfflineRecognizer, decodes the matched .opus to 16 kHz mono via [OpusDecoder],
 * and returns the transcript. Everything runs on the orchestrator's worker thread (off-main).
 */
object WhisperModel {

    // sherpa-onnx whisper-small (int8). ~360 MB total — downloaded once, user-initiated.
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"
    val ENCODER = ModelFile("small-encoder.int8.onnx", 112_442_483L)
    val DECODER = ModelFile("small-decoder.int8.onnx", 262_226_114L)
    val TOKENS = ModelFile("small-tokens.txt", 816_730L)
    private val FILES = listOf(ENCODER, DECODER, TOKENS)

    data class ModelFile(val name: String, val size: Long)

    @Volatile var status: String = "not downloaded"
        private set
    @Volatile var downloading: Boolean = false
        private set

    /** Total bytes of all model files (for the UI progress bar). */
    val totalBytes: Long get() = FILES.sumOf { it.size }

    /** Bytes on disk so far: completed files + current file position. Live during download. */
    @Volatile var downloadedBytes: Long = 0L
        private set

    /** Remove the model files so they can be re-downloaded. No-op while a download runs. */
    fun delete(context: Context) {
        if (downloading) return
        dir(context).listFiles()?.forEach { it.delete() }
        downloadedBytes = 0L
        status = "not downloaded"
        AppLog.i("[whisper] model files deleted.")
    }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper-dl").apply { isDaemon = true } }

    fun dir(context: Context): File = File(context.filesDir, "whisper").apply { mkdirs() }
    fun pathOf(context: Context, f: ModelFile): String = File(dir(context), f.name).absolutePath

    /** All three files present with the expected size. */
    fun isReady(context: Context): Boolean = FILES.all { f ->
        val file = File(dir(context), f.name)
        file.exists() && file.length() == f.size
    }

    fun download(context: Context, onProgress: (String) -> Unit) {
        if (downloading) { onProgress("[whisper] already downloading…"); return }
        if (isReady(context)) { status = "ready"; onProgress("[whisper] model already present (ready)."); return }
        exec.execute {
            downloading = true
            try {
                // Seed the byte counter with files that are already complete on disk.
                var doneBytes = FILES.sumOf { f ->
                    val file = File(dir(context), f.name)
                    if (file.exists() && file.length() == f.size) f.size else 0L
                }
                downloadedBytes = doneBytes
                for (f in FILES) {
                    val dst = File(dir(context), f.name)
                    if (dst.exists() && dst.length() == f.size) { onProgress("[whisper] have ${f.name}."); continue }
                    status = "downloading ${f.name}"
                    onProgress("[whisper] downloading ${f.name} (${f.size / 1_000_000} MB)…")
                    downloadOne("$BASE/${f.name}", dst, f.size, onProgress) { current ->
                        downloadedBytes = doneBytes + current
                    }
                    doneBytes += f.size
                    downloadedBytes = doneBytes
                }
                val ok = isReady(context)
                status = if (ok) "ready" else "incomplete"
                onProgress(if (ok) "[whisper] ✅ model ready." else "[whisper] ⚠ download incomplete.")
            } catch (e: Exception) {
                status = "download failed: ${e.message}"
                AppLog.e("[whisper] download failed: ${e.message}", e)
            } finally {
                downloading = false
            }
        }
    }

    private fun downloadOne(
        url: String,
        dst: File,
        expected: Long,
        onProgress: (String) -> Unit,
        onBytes: (Long) -> Unit = {},
    ) = ModelDownloads.fetch(url, dst, expected, "whisper", onProgress, onBytes)
}

/** Shared HTTP model downloader: tmp file → size check → atomic rename, 10%-step progress. */
internal object ModelDownloads {
    fun fetch(
        url: String,
        dst: File,
        expected: Long,
        tag: String,
        onProgress: (String) -> Unit,
        onBytes: (Long) -> Unit = {},
    ) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                        val pct = if (expected > 0) (total * 100 / expected).toInt() else -1
                        if (pct != lastPct && pct % 10 == 0) { lastPct = pct; onProgress("[$tag] ${dst.name}: $pct%") }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (expected > 0 && tmp.length() != expected) {
            tmp.delete()
            throw IllegalStateException("${dst.name}: size ${tmp.length()} != expected $expected")
        }
        dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("rename failed for ${dst.name}")
    }
}

/** Process-wide single WhisperTranscriber (one recognizer = one copy of the model in RAM).
 *  Shared by the play-tap pipeline and chain summarization. */
object TranscriberHolder {
    @Volatile private var instance: WhisperTranscriber? = null
    fun get(context: Context): WhisperTranscriber {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return WhisperTranscriber(context.applicationContext).also { instance = it }
        }
    }
}

class WhisperTranscriber(context: Context) : Transcriber {

    companion object {
        const val DECODE_MAX_SECONDS = 300.0 // decode the whole note (voice notes are short)
        const val SR = 16000
        const val NUM_THREADS = 2
    }

    private val appContext = context.applicationContext
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var builtLang: String? = null
    private val initLock = Any()

    /** Build the recognizer once; language is always auto-detected. */
    private fun ensureRecognizer(): OfflineRecognizer? {
        if (!WhisperModel.isReady(appContext)) return null
        val lang = "" // "" = auto-detect
        recognizer?.let { if (builtLang == lang) return it }
        synchronized(initLock) {
            recognizer?.let { if (builtLang == lang) return it }
            recognizer?.let { try { it.release() } catch (_: Exception) {} }
            recognizer = null
            AppLog.i("[whisper] loading recognizer (whisper-small int8, language='${if (lang.isEmpty()) "auto" else lang}')…")
            val t0 = System.currentTimeMillis()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = WhisperModel.pathOf(appContext, WhisperModel.ENCODER),
                        decoder = WhisperModel.pathOf(appContext, WhisperModel.DECODER),
                        language = lang,
                        task = "transcribe"
                    ),
                    tokens = WhisperModel.pathOf(appContext, WhisperModel.TOKENS),
                    numThreads = NUM_THREADS,
                    modelType = "whisper",
                    debug = false
                )
            )
            // assetManager = null -> load from absolute filesDir paths.
            val r = OfflineRecognizer(null, config)
            recognizer = r
            builtLang = lang
            AppLog.i("[whisper] recognizer ready in ${System.currentTimeMillis() - t0} ms.")
            return r
        }
    }

    // Synchronized: with the shared TranscriberHolder instance, concurrent callers (orchestrator
    // worker vs chain summarizer) must serialize — sherpa streams aren't cross-thread safe.
    @Synchronized
    override fun transcribe(file: File): String {
        if (!WhisperModel.isReady(appContext))
            return "[whisper model not downloaded — tap 'Download Whisper model' in the app]"
        val r = try {
            ensureRecognizer()
        } catch (e: Exception) {
            AppLog.e("[whisper] recognizer init failed: ${e.message}", e)
            return "[whisper init failed: ${e.message}]"
        } ?: return "[whisper model not ready]"

        return try {
            val t0 = System.currentTimeMillis()
            val pcm = OpusDecoder.decodeToMono16k(appContext, Uri.fromFile(file), DECODE_MAX_SECONDS) { }
            if (pcm.isEmpty()) return "[no audio decoded]"
            val stream = r.createStream()
            stream.acceptWaveform(pcm, SR)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()
            AppLog.i("[whisper] transcribed ${file.name}: ${pcm.size} samp in ${System.currentTimeMillis() - t0} ms.")
            if (text.isEmpty()) "[no speech detected]" else text
        } catch (e: Exception) {
            AppLog.e("[whisper] transcribe failed: ${e.message}", e)
            "[transcription error: ${e.message}]"
        }
    }
}
