package com.example.antiwispr

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-device transcript summaries via MediaPipe LLM Inference (Qwen2.5-1.5B-Instruct, q8 .task).
 *
 * [LlmModel] owns the model file (downloaded once to filesDir/llm, ~1.6 GB) + download state,
 * mirroring [WhisperModel]. [Summarizer] lazily builds ONE LlmInference engine and serves
 * summary requests on a single background thread; results are raw "SUMMARY:/ACTIONS:" text
 * cached in [Transcripts] and parsed for display with [parseSummary].
 */
object LlmModel {

    private const val BASE = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main"
    // Exact size verified via HEAD request (integrity check, same idiom as WhisperModel).
    val MODEL = WhisperModel.ModelFile("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task", 1_598_556_720L)

    @Volatile var status: String = "not downloaded"
        private set
    @Volatile var downloading: Boolean = false
        private set

    /** Total bytes of the model (for the UI progress bar). */
    val totalBytes: Long get() = MODEL.size

    /** Bytes on disk so far. Live during download. */
    @Volatile var downloadedBytes: Long = 0L
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-dl").apply { isDaemon = true } }

    fun dir(context: Context): File = File(context.filesDir, "llm").apply { mkdirs() }
    fun path(context: Context): String = File(dir(context), MODEL.name).absolutePath

    fun isReady(context: Context): Boolean =
        File(dir(context), MODEL.name).let { it.exists() && it.length() == MODEL.size }

    fun download(context: Context, onProgress: (String) -> Unit) {
        if (downloading) { onProgress("[llm] already downloading…"); return }
        if (isReady(context)) { status = "ready"; onProgress("[llm] model already present (ready)."); return }
        exec.execute {
            downloading = true
            try {
                val dst = File(dir(context), MODEL.name)
                status = "downloading ${MODEL.name}"
                onProgress("[llm] downloading summary model (${MODEL.size / 1_000_000} MB)…")
                ModelDownloads.fetch("$BASE/${MODEL.name}", dst, MODEL.size, "llm", onProgress) {
                    downloadedBytes = it
                }
                val ok = isReady(context)
                status = if (ok) "ready" else "incomplete"
                onProgress(if (ok) "[llm] ✅ summary model ready." else "[llm] ⚠ download incomplete.")
            } catch (e: Exception) {
                status = "download failed: ${e.message}"
                AppLog.e("[llm] download failed: ${e.message}", e)
            } finally {
                downloading = false
            }
        }
    }

    /** Remove the model so it can be re-downloaded. No-op while a download runs. */
    fun delete(context: Context) {
        if (downloading) return
        dir(context).listFiles()?.forEach { it.delete() }
        downloadedBytes = 0L
        status = "not downloaded"
        Summarizer.releaseEngine()
        AppLog.i("[llm] summary model deleted.")
    }
}

/** Parsed pieces of a raw "SUMMARY:/ACTIONS:" summary for display. */
data class SummaryParts(val summary: String, val actions: List<String>)

/** Tolerant parser: missing headers → whole text as summary, no actions. */
fun parseSummary(raw: String): SummaryParts {
    val text = raw.trim()
    val summaryIdx = text.indexOf("SUMMARY:", ignoreCase = true)
    val actionsIdx = text.indexOf("ACTIONS:", ignoreCase = true)
    if (summaryIdx < 0 && actionsIdx < 0) return SummaryParts(text, emptyList())

    val summary = when {
        summaryIdx < 0 -> text.substring(0, if (actionsIdx > 0) actionsIdx else text.length)
        actionsIdx > summaryIdx -> text.substring(summaryIdx + 8, actionsIdx)
        else -> text.substring(summaryIdx + 8)
    }.trim()

    val actions = if (actionsIdx < 0) emptyList() else
        text.substring(actionsIdx + 8).lines()
            .map { it.trim().trimStart('-', '•', '*').trim() }
            .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }

    return SummaryParts(summary.ifEmpty { text }, actions)
}

object Summarizer {

    private const val MAX_INPUT_CHARS = 6000
    private const val MAX_TOKENS = 4096 // matches the ekv4096 model variant

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "summarizer").apply { isDaemon = true } }
    @Volatile private var engine: LlmInference? = null
    private val engineLock = Any()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Some path can produce a summary: local model present, or cloud reachable-in-principle. */
    fun canSummarize(context: Context): Boolean =
        LlmModel.isReady(context) ||
            (com.example.antiwispr.cloud.CloudClient.ready(context) &&
                com.example.antiwispr.cloud.CloudPrefs.cloudSummaries(context))

    /**
     * Queue a summary for [transcript]; [onDone] receives the RAW result text (bracket-prefixed
     * "[…]" = error, same convention as transcription) on the summarizer thread. Duplicate
     * requests for a key already in flight are dropped. Cloud (gpt-4o-mini via tacit-cloud)
     * is preferred when signed in + enabled; the on-device engine is the fallback.
     */
    fun request(context: Context, key: String, transcript: String, onDone: (String) -> Unit) {
        if (!inFlight.add(key)) { AppLog.i("[summarizer] already summarizing this note — skipping."); return }
        val appContext = context.applicationContext
        exec.execute {
            val result = try {
                val cloud = if (com.example.antiwispr.cloud.CloudClient.ready(appContext) &&
                    com.example.antiwispr.cloud.CloudPrefs.cloudSummaries(appContext)
                ) {
                    com.example.antiwispr.cloud.CloudClient.summarize(appContext, transcript.take(MAX_INPUT_CHARS))
                } else null
                if (cloud != null) {
                    AppLog.i("[summarizer] cloud summary (gpt-4o-mini).")
                    cloud
                } else {
                    generate(appContext, transcript)
                }
            } catch (t: Throwable) {
                // Contain Throwable: a native OOM in the LLM must not kill the shared process.
                AppLog.e("[summarizer] failed: ${t.javaClass.simpleName}: ${t.message}", t)
                "[summary failed: ${t.message}]"
            } finally {
                inFlight.remove(key)
            }
            onDone(result)
        }
    }

    /**
     * Chain variant, BLOCKING (runs on ChainSummarizer's thread): cloud first, local
     * fallback, chain-specific prompt, same SUMMARY:/ACTIONS: contract.
     */
    fun summarizeChainBlocking(context: Context, combined: String, count: Int, sender: String?): String {
        val ctx = context.applicationContext
        val text = combined.take(MAX_INPUT_CHARS)
        val cloud = if (com.example.antiwispr.cloud.CloudClient.ready(ctx) &&
            com.example.antiwispr.cloud.CloudPrefs.cloudSummaries(ctx)
        ) {
            com.example.antiwispr.cloud.CloudClient.summarizeChain(ctx, text, count, sender)
        } else null
        if (cloud != null) {
            AppLog.i("[summarizer] cloud chain summary (gpt-4o-mini).")
            return cloud
        }
        if (!LlmModel.isReady(ctx)) return "[summary unavailable — no model and no cloud connection]"
        val eng = ensureEngine(ctx) ?: return "[summarizer init failed]"
        val t0 = System.currentTimeMillis()
        val session = LlmInferenceSession.createFromOptions(
            eng,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.3f)
                .build()
        )
        return try {
            session.addQueryChunk(buildChainPrompt(text, count, sender))
            val out = session.generateResponse().trim()
            AppLog.i("[summarizer] chain generated ${out.length} chars in ${System.currentTimeMillis() - t0} ms.")
            out.ifEmpty { "[summary empty — try regenerating]" }
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    /**
     * Ask variant, BLOCKING (call from a worker thread): answers a question from retrieved
     * notes. Cloud (gpt-4o-mini via /v1/ask) first, on-device Qwen fallback. Builds the
     * numbered-notes context itself so the budget matches the engine: 24k chars for cloud,
     * 4k for Qwen (its 4096-token cap covers instructions + question + answer too, and
     * Devanagari runs ~2 chars/token). Returns raw "ANSWER:/SOURCES:" text; bracket-prefixed
     * = error, same convention as everything else.
     */
    fun askBlocking(context: Context, question: String, hits: List<SearchHit>): String {
        val ctx = context.applicationContext
        if (hits.isEmpty()) return "[no matching notes found — try different words]"
        val language = com.example.antiwispr.cloud.CloudPrefs.askLanguage(ctx)
        val cloudReady = com.example.antiwispr.cloud.CloudClient.ready(ctx) &&
            com.example.antiwispr.cloud.CloudPrefs.cloudSummaries(ctx)
        if (cloudReady) {
            val cloud = com.example.antiwispr.cloud.CloudClient.ask(
                ctx, question, buildAskContext(hits, maxChars = 24_000), language
            )
            if (cloud != null) {
                AppLog.i("[ask] cloud answer (gpt-4o-mini).")
                return cloud
            }
        }
        if (!LlmModel.isReady(ctx)) return "[ask unavailable — no model and no cloud connection]"
        val eng = ensureEngine(ctx) ?: return "[summarizer init failed]"
        val t0 = System.currentTimeMillis()
        val session = LlmInferenceSession.createFromOptions(
            eng,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.3f)
                .build()
        )
        return try {
            session.addQueryChunk(
                buildAskPrompt(question, buildAskContext(hits, maxChars = 4_000, perNoteCap = 1000), language)
            )
            val out = session.generateResponse().trim()
            AppLog.i("[ask] local answer, ${out.length} chars in ${System.currentTimeMillis() - t0} ms.")
            out.ifEmpty { "[answer empty — try again]" }
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun buildAskPrompt(question: String, notesContext: String, language: String): String {
        val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date())
        val langClause =
            if (language == com.example.antiwispr.cloud.AskLanguage.Match.wire)
                "Reply in the SAME language and style as the question (match Hinglish with Hinglish)."
            else
                "Reply in clear, simple English, whatever language the notes or question use."
        return """
            You answer questions about a user's WhatsApp voice notes. You get numbered notes
            ([Note 1], [Note 2], …), each with its date and sender, then a question.
            Answer ONLY from the notes — never invent or guess details. $langClause Keep amounts,
            dates, times, phone numbers, addresses and names EXACTLY as written in the notes.
            Use EXACTLY this format:
            ANSWER: <1-4 short sentences>
            SOURCES: <the note numbers you used, e.g. [Note 2], [Note 5]>
            If the notes do not contain the answer, say so briefly in ANSWER and write exactly:
            SOURCES: none

            Notes:
            ${'"'}${'"'}${'"'}
            $notesContext
            ${'"'}${'"'}${'"'}

            Today: $today
            Question: $question
        """.trimIndent()
    }

    private fun buildChainPrompt(combined: String, count: Int, sender: String?): String {
        val who = if (sender.isNullOrBlank()) "" else " by $sender"
        return """
            These are $count WhatsApp voice notes sent IN A ROW$who — treat them as ONE message.
            Reply in the SAME language and style as the notes (if they mix Hindi and English, do
            the same). Be brief and factual — never invent details. Put the single most important
            ask or point FIRST. Use EXACTLY this format:
            SUMMARY: <2-4 short sentences covering the whole chain>
            ACTIONS:
            - <one action item per line — keep amounts, dates, times, phone numbers, addresses
              and names EXACTLY as spoken; never paraphrase them away>
            If there is nothing to act on, write exactly:
            ACTIONS:
            - none

            Voice notes:
            ${'"'}${'"'}${'"'}
            $combined
            ${'"'}${'"'}${'"'}
        """.trimIndent()
    }

    /** Free the engine (model deleted / re-downloaded). Safe to call anytime. */
    fun releaseEngine() {
        synchronized(engineLock) {
            engine?.let { try { it.close() } catch (_: Exception) {} }
            engine = null
        }
    }

    private fun generate(context: Context, transcript: String): String {
        if (!LlmModel.isReady(context)) return "[summary unavailable — no model and no cloud connection]"
        val eng = ensureEngine(context) ?: return "[summarizer init failed]"
        val text = transcript.take(MAX_INPUT_CHARS)
        val t0 = System.currentTimeMillis()
        // A fresh session per request: no context bleed between notes; low temperature for
        // factual extraction.
        val session = LlmInferenceSession.createFromOptions(
            eng,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.3f)
                .build()
        )
        return try {
            session.addQueryChunk(buildPrompt(text))
            val out = session.generateResponse().trim()
            AppLog.i("[summarizer] generated ${out.length} chars in ${System.currentTimeMillis() - t0} ms.")
            if (out.isEmpty()) "[summary empty — try regenerating]" else out
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun ensureEngine(context: Context): LlmInference? {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            AppLog.i("[summarizer] loading LLM engine (Qwen2.5-1.5B q8)…")
            val t0 = System.currentTimeMillis()
            return try {
                val e = LlmInference.createFromOptions(
                    context.applicationContext,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(LlmModel.path(context))
                        .setMaxTokens(MAX_TOKENS)
                        .build()
                )
                engine = e
                AppLog.i("[summarizer] engine ready in ${System.currentTimeMillis() - t0} ms.")
                e
            } catch (t: Throwable) {
                AppLog.e("[summarizer] engine init failed: ${t.message}", t)
                null
            }
        }
    }

    private fun buildPrompt(transcript: String): String = """
        You summarize WhatsApp voice-note transcripts. Reply in the SAME language and style as
        the transcript (if it mixes Hindi and English, do the same). Be brief and factual —
        never invent details. Use EXACTLY this format:
        SUMMARY: <2-3 short sentences>
        ACTIONS:
        - <one action item per line — keep amounts, dates, times, phone numbers, addresses
          and names EXACTLY as spoken; never paraphrase them away>
        If there is nothing to act on, write exactly:
        ACTIONS:
        - none

        Transcript:
        ${'"'}${'"'}${'"'}
        $transcript
        ${'"'}${'"'}${'"'}
    """.trimIndent()
}
