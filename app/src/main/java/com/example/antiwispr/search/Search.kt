package com.example.antiwispr.search

import android.icu.text.Transliterator
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.data.StoredTranscript
import com.example.antiwispr.match.Scored
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Ranked, cross-script search over the transcript library — the engine behind the
 * searchable-history promise ("what was that address Rahul sent last week").
 *
 * The same Hindi word lands in the store in two scripts: romanised Hinglish (cloud
 * Sarvam's default translit mode) or Devanagari (on-device Whisper / cloud transcribe
 * mode). [TextFolder] folds everything — records AND query — into lowercase ASCII-ish
 * Latin so matching crosses scripts. Pure JVM logic otherwise, so it unit-tests with
 * the transliterator seam swapped.
 */

/** Folds any Indic script (and Latin diacritics) to lowercase ASCII for matching. */
object TextFolder {

    /** Test seam — JVM tests replace this (the stub android.jar throws on ICU calls). */
    internal var translit: (String) -> String = ::icuFold

    private val lock = Any()
    private var icu: Transliterator? = null
    @Volatile private var icuBroken = false

    private fun icuFold(s: String): String {
        // Fast path: translit-mode records (the cloud default) are already ASCII.
        if (s.none { it.code > 0x7F }) return s.lowercase()
        synchronized(lock) { // ICU transliterators are NOT thread-safe; instance is cached (getInstance compiles rules)
            if (icuBroken) return s.lowercase()
            val t = icu ?: try {
                Transliterator.getInstance("Any-Latin; Latin-ASCII; Lower").also { icu = it }
            } catch (e: Exception) {
                AppLog.w("[search] ICU transliterator unavailable — falling back to lowercase: ${e.message}")
                icuBroken = true
                return s.lowercase()
            }
            return t.transliterate(s)
        }
    }

    /**
     * fold + Hinglish long-vowel digraph reduction, applied to BOTH sides of a match.
     * ISO-15919 output ("aarama" for आराम) then matches typed "aaram" → "aram"; "ghar"
     * matches "ghara" by substring. Symmetric, so en↔en matching is unaffected.
     * Known residual misses (accepted): mid-word schwa deletion ("kamra" vs "kamara"),
     * ISO "ca" vs Hinglish "cha".
     */
    fun normalize(s: String): String = reduceDigraphs(translit(s))

    internal fun reduceDigraphs(s: String): String =
        s.replace("aa", "a").replace("ee", "i").replace("oo", "u")
}

/** Day-granular filters; ymd = yyyymmdd Ints matching [StoredTranscript.waDate]. -1 = open. */
data class SearchFilters(
    val chatName: String? = null,
    val fromYmd: Int = -1,
    val toYmd: Int = -1,
) {
    val isEmpty: Boolean get() = chatName == null && fromYmd < 0 && toYmd < 0

    fun accepts(t: StoredTranscript): Boolean {
        if (chatName != null && !t.chatName.equals(chatName, ignoreCase = true)) return false
        if (fromYmd < 0 && toYmd < 0) return true
        val ymd = if (t.waDate > 0) t.waDate else ymdOf(t.updatedAt)
        if (fromYmd >= 0 && ymd < fromYmd) return false
        if (toYmd >= 0 && ymd > toYmd) return false
        return true
    }
}

/**
 * [surfaceInText] = a raw query term appears literally in the note text, so the card can highlight
 * it. When false, [hint] explains where/what matched ("match: घर" for a cross-script hit,
 * "found in summary", "found in chat name") so the result never reads as a false positive.
 */
data class SearchHit(
    val transcript: StoredTranscript,
    val score: Int,
    val surfaceInText: Boolean = true,
    val hint: String? = null,
)

object SearchEngine {

    /** Whitespace/punctuation split, lowercased, terms < 2 chars dropped. NOT normalized —
     *  Format.kt uses these raw terms for surface-form highlighting. */
    fun tokenize(query: String): List<String> =
        query.lowercase().split(TOKEN_SPLIT).filter { it.length >= 2 }

    /**
     * Multi-term ranked search over a SNAPSHOT of the store (pass Transcripts.all() —
     * never scan while holding the store lock). BLOCKING — first pass transliterates
     * un-cached records; call on Dispatchers.Default, never main.
     *
     * requireAll=true: every term must match some field (AND). Score per matched term:
     * chatName +3, summary +2, text +1 (additive). Order: score desc, updatedAt desc;
     * in OR mode, matched-term count is the primary key (3-of-4 terms beats 1 fat hit).
     */
    fun search(
        records: List<StoredTranscript>,
        query: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 100,
        requireAll: Boolean = true,
    ): List<SearchHit> {
        val rawTerms = tokenize(query) // un-normalized: for literal surface-highlight detection
        val terms = tokenize(query).map { TextFolder.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty()) return emptyList()
        data class Scored(val hit: SearchHit, val matched: Int)
        val out = ArrayList<Scored>()
        for (t in records) {
            if (!filters.accepts(t)) continue
            val n = normOf(t)
            var score = 0
            var matched = 0
            for (term in terms) {
                var s = 0
                if (n.chatName.contains(term)) s += 3
                if (n.summary.contains(term)) s += 2
                if (n.text.contains(term)) s += 1
                if (s > 0) matched++
                score += s
            }
            val ok = if (requireAll) matched == terms.size else matched > 0
            if (ok) {
                val surface = rawTerms.any { t.text.contains(it, ignoreCase = true) }
                val hint = if (surface) null else matchHint(t, n, terms)
                out += Scored(SearchHit(t, score, surface, hint), matched)
            }
        }
        pruneCache(records)
        val cmp = if (requireAll) {
            compareByDescending<Scored> { it.hit.score }.thenByDescending { it.hit.transcript.updatedAt }
        } else {
            compareByDescending<Scored> { it.matched }
                .thenByDescending { it.hit.score }
                .thenByDescending { it.hit.transcript.updatedAt }
        }
        return out.sortedWith(cmp).take(limit).map { it.hit }
    }

    /**
     * Ask-mode retrieval: stopword-stripped terms (kept whole if stripping empties them),
     * AND first; fewer than 3 hits → OR rerun (ranked by matched-term count). Filters are
     * hard pre-filters — the LLM never sees excluded notes.
     */
    fun retrieveForAsk(
        records: List<StoredTranscript>,
        question: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 15,
    ): List<SearchHit> {
        val raw = tokenize(question)
        val content = raw.filterNot { it in STOPWORDS }.ifEmpty { raw }
        val q = content.joinToString(" ")
        val and = search(records, q, filters, limit, requireAll = true)
        if (and.size >= 3) return and
        val or = search(records, q, filters, limit, requireAll = false)
        return if (or.size > and.size) or else and
    }

    // ---- normalized-text cache -----------------------------------------------------

    private class Norm(val updatedAt: Long, val text: String, val summary: String, val chatName: String)

    private val cache = ConcurrentHashMap<String, Norm>()

    private fun normOf(t: StoredTranscript): Norm =
        cache[t.key]?.takeIf { it.updatedAt == t.updatedAt }
            ?: Norm(
                t.updatedAt,
                TextFolder.normalize(t.text),
                TextFolder.normalize(t.summary),
                TextFolder.normalize(t.chatName),
            ).also { cache[t.key] = it }

    /** Drop cache entries for deleted records once drift gets noticeable. */
    private fun pruneCache(records: List<StoredTranscript>) {
        if (cache.size <= records.size + 64) return
        val live = records.mapTo(HashSet()) { it.key }
        cache.keys.retainAll(live)
    }

    /** Chip text for a hit the card can't surface-highlight: the actual cross-script word that
     *  matched, else which field carried the match. Runs only for non-surface hits (the minority). */
    private fun matchHint(t: StoredTranscript, n: Norm, terms: List<String>): String? {
        if (terms.any { n.text.contains(it) }) {
            val word = t.text.split(TOKEN_SPLIT).firstOrNull { w ->
                w.isNotBlank() && terms.any { TextFolder.normalize(w).contains(it) }
            }
            if (!word.isNullOrBlank()) return "match: $word"
        }
        if (terms.any { n.summary.contains(it) }) return "found in summary"
        if (terms.any { n.chatName.contains(it) }) return "found in chat name"
        return null
    }

    /** Optional warm-up so the first search doesn't pay the transliteration cost. */
    fun prewarm(records: List<StoredTranscript>) {
        for (t in records) normOf(t)
    }

    private val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}₹+@.]+""")
}

private fun ymdOf(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

// ---- Ask mode: retrieval context + answer contract -----------------------------------

/**
 * Minimal en+Hinglish stopword set for retrieval ONLY (typed search terms are always
 * intentional). Deliberately excludes content/date-hint words: last, week, address,
 * time, ghar, paisa … are exactly what the user wants found.
 */
internal val STOPWORDS = setOf(
    // en
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did",
    "what", "when", "where", "who", "whom", "which", "why", "how", "i", "me", "my", "mine",
    "you", "your", "he", "she", "it", "we", "they", "of", "in", "on", "at", "to", "for",
    "from", "with", "and", "or", "not", "no", "that", "this", "had", "has", "have", "there",
    // hinglish
    "kya", "kab", "kahan", "kaun", "kisne", "kyu", "kyun", "kaise", "tha", "thi", "hai",
    "hain", "ho", "hua", "ka", "ki", "ke", "ko", "se", "par", "pe", "aur", "ya", "na",
    "nahi", "woh", "wo", "yeh", "ye", "jo", "ne", "mujhe", "mera", "meri", "tum", "aap",
    "bata", "batao",
)

/**
 * Numbered notes block for the LLM — same format for cloud and local so both see
 * identical context. Numbering = retrieval rank, 1-based; date/sender headers degrade
 * gracefully when unknown. Stops before exceeding [maxChars].
 * (Date label is duplicated here rather than importing ui.components.Format — that file
 * drags Compose types onto the unit-test classpath.)
 */
fun buildAskContext(hits: List<SearchHit>, maxChars: Int, perNoteCap: Int = 1500): String {
    val sb = StringBuilder()
    for ((i, h) in hits.withIndex()) {
        val t = h.transcript
        val header = buildString {
            append("[Note ${i + 1}")
            val date = ymdLabel(t.waDate)
            if (date != null) append(" — $date")
            if (t.chatName.isNotEmpty()) append(if (date != null) ", ${t.chatName}" else " — ${t.chatName}")
            append("]")
        }
        val block = (if (sb.isEmpty()) "" else "\n\n") + header + "\n" + t.text.take(perNoteCap)
        if (sb.length + block.length > maxChars) break
        sb.append(block)
    }
    return sb.toString()
}

private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

internal fun ymdLabel(ymd: Int): String? {
    if (ymd <= 0) return null
    val m = ymd / 100 % 100
    val d = ymd % 100
    if (m !in 1..12 || d !in 1..31) return null
    return "$d ${MONTHS[m - 1]} ${ymd / 10000}"
}

/** Parsed "ANSWER:/SOURCES:" reply; [sources] are 1-based note numbers. */
data class AskAnswer(val answer: String, val sources: List<Int>)

/**
 * Tolerant (the parseSummary idiom): missing ANSWER: header → whole text is the answer;
 * missing/garbled/"none" SOURCES: → empty list, and the UI then shows ALL retrieved notes.
 */
fun parseAskAnswer(raw: String): AskAnswer {
    val text = raw.trim()
    val aIdx = text.indexOf("ANSWER:", ignoreCase = true)
    val sIdx = text.indexOf("SOURCES:", ignoreCase = true)
    val answer = when {
        aIdx < 0 && sIdx < 0 -> text
        aIdx < 0 -> text.substring(0, sIdx)
        sIdx > aIdx -> text.substring(aIdx + 7, sIdx)
        else -> text.substring(aIdx + 7)
    }.trim()
    val sources = if (sIdx < 0) emptyList() else
        Regex("""\d+""").findAll(text.substring(sIdx + 8))
            .mapNotNull { it.value.toIntOrNull() }.distinct().toList()
    return AskAnswer(answer.ifEmpty { text }, sources)
}
