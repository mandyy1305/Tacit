package com.example.antiwispr

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns a [Chain] into ONE summary: transcribes missing members in order (cloud→local via
 * [TranscribeRouter]), joins them with note markers, summarizes with the chain prompt,
 * caches in [ChainSummaries]. Shared by the overlay flow and the in-app reader.
 */
object ChainSummarizer {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "chain").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * [onDone] gets the RAW "SUMMARY:/ACTIONS:" text (bracket-prefixed = error), on the
     * chain thread. Cached results return immediately (still via the callback).
     * Duplicate requests for a chain already generating are dropped.
     */
    fun request(context: Context, chain: Chain, chatName: String?, onDone: (String) -> Unit) {
        val ctx = context.applicationContext
        ChainSummaries.get(ctx).find(chain.id)?.let { onDone(it.raw); return }
        if (!inFlight.add(chain.id)) { AppLog.i("[chains] already summarizing ${chain.id} — skipping."); return }
        exec.execute {
            val result = try {
                generate(ctx, chain, chatName)
            } catch (t: Throwable) {
                AppLog.e("[chains] failed: ${t.javaClass.simpleName}: ${t.message}", t)
                "[chain summary failed: ${t.message}]"
            } finally {
                inFlight.remove(chain.id)
            }
            onDone(result)
        }
    }

    private fun generate(ctx: Context, chain: Chain, chatName: String?): String {
        val t0 = System.currentTimeMillis()
        val parts = ArrayList<String>(chain.size)
        for ((i, f) in chain.files.withIndex()) {
            if (!f.exists()) return "[note ${i + 1} of ${chain.size} isn't on this phone]"
            AppLog.i("[chains] ${chain.id}: transcribing note ${i + 1}/${chain.size}…")
            val text = TranscribeRouter.transcribe(ctx, f, chatName)
            if (text.startsWith("[")) return "[couldn't transcribe note ${i + 1} of ${chain.size}: ${text.trim('[', ']')}]"
            parts += "[Note ${i + 1}]\n$text"
        }
        val combined = parts.joinToString("\n\n")

        // Synchronous summarize on THIS thread (not Summarizer's queue) so the chain flow
        // stays one linear job; routing mirrors Summarizer.request.
        val raw = Summarizer.summarizeChainBlocking(ctx, combined, chain.size, chatName ?: chain.chatName)
        if (!raw.startsWith("[")) {
            ChainSummaries.get(ctx).put(chain.id, raw, chain.size)
            AppLog.i("[chains] ${chain.id} summarized in ${System.currentTimeMillis() - t0} ms.")
        }
        return raw
    }
}
