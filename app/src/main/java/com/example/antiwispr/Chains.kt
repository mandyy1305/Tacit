package com.example.antiwispr

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * A burst of voice notes received back-to-back. WhatsApp's WA#### filename counter is a
 * monotonic per-device media counter, so consecutive seq numbers with reception times
 * (file mtimes) a few minutes apart = "sent in a row". A seq gap means other media landed
 * in between — the natural chain boundary (chains may split too eagerly, never merge wrongly).
 */
data class Chain(
    val files: List<File>,
    val date: Int,
    val firstSeq: Int,
    val lastSeq: Int,
    val chatName: String?,
) {
    val id: String get() = "$date:$firstSeq-$lastSeq"
    val size: Int get() = files.size
    fun partIndexOf(file: File): Int =
        files.indexOfFirst { it.absolutePath == file.absolutePath } + 1
}

object Chains {

    /** Max reception-time gap between neighboring notes to still count as one burst. */
    private const val MAX_NEIGHBOR_GAP_MS = 10 * 60 * 1000L

    /**
     * The chain [file] belongs to, from the actual files on disk — or null when it stands
     * alone. chatName is inferred from any member's stored transcript.
     */
    fun chainFor(context: Context, file: File): Chain? {
        val parsed = VoiceNotes.parseWhatsAppName(file.name) ?: return null
        val bySeq = HashMap<Int, File>()
        for (f in VoiceNotes.listOpusFiles()) {
            val p = VoiceNotes.parseWhatsAppName(f.name) ?: continue
            if (p.dateYmd == parsed.dateYmd) bySeq.putIfAbsent(p.seq, f)
        }
        bySeq.putIfAbsent(parsed.seq, file)

        var lo = parsed.seq
        while (true) {
            val prev = bySeq[lo - 1] ?: break
            if (abs(bySeq[lo]!!.lastModified() - prev.lastModified()) > MAX_NEIGHBOR_GAP_MS) break
            lo--
        }
        var hi = parsed.seq
        while (true) {
            val next = bySeq[hi + 1] ?: break
            if (abs(next.lastModified() - bySeq[hi]!!.lastModified()) > MAX_NEIGHBOR_GAP_MS) break
            hi++
        }
        if (hi == lo) return null

        val files = (lo..hi).map { bySeq[it]!! }
        val store = Transcripts.get(context)
        val chat = files.firstNotNullOfOrNull { f -> store.entry(f)?.chatName?.ifEmpty { null } }
        return Chain(files, parsed.dateYmd, lo, hi, chat)
    }

    /**
     * Keys of all stored transcripts that belong to some chain — store-only (no file
     * access), so it also works on a cloud-restored library. Used for list badges.
     */
    fun memberKeys(store: Transcripts): Set<String> {
        val out = HashSet<String>()
        val entries = store.all().filter { it.waDate > 0 && it.seq >= 0 }
        for ((_, list) in entries.groupBy { it.waDate }) {
            val sorted = list.distinctBy { it.seq }.sortedBy { it.seq }
            var run = mutableListOf(sorted.first())
            fun flush() {
                if (run.size >= 2) run.forEach { out.add(it.key) }
                run = mutableListOf()
            }
            for (i in 1 until sorted.size) {
                if (sorted[i].seq == sorted[i - 1].seq + 1) run.add(sorted[i])
                else { flush(); run.add(sorted[i]) }
            }
            flush()
        }
        return out
    }
}
