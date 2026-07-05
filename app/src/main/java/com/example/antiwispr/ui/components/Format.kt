package com.example.antiwispr.ui.components

import android.text.format.DateUtils
import androidx.compose.ui.text.AnnotatedString
import com.example.antiwispr.SearchEngine
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import java.util.Calendar
import java.util.Locale

/** "3 min. ago" / "Yesterday" for a store timestamp. */
fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    ).toString()

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/** "29 Jun" (or "29 Jun 2025" if not this year) from a WhatsApp yyyymmdd; null if unknown. */
fun waDateLabel(waDate: Int): String? {
    if (waDate <= 0) return null
    val y = waDate / 10000
    val m = (waDate / 100) % 100
    val d = waDate % 100
    if (m !in 1..12 || d !in 1..31) return null
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    return if (y == thisYear) "$d ${MONTHS[m - 1]}" else "$d ${MONTHS[m - 1]} $y"
}

/** "0:42" from seconds; null when unknown (<0). */
fun durationLabel(seconds: Double): String? {
    if (seconds < 0) return null
    val total = seconds.toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/**
 * Merged, sorted surface-form match ranges for all [terms] (case-insensitive). Merging
 * matters: terms can overlap ("ghar" + "har") and nested/overlapping spans would break
 * the pushStyle walk. NOTE: matching here is surface-form only — a record that matched
 * cross-script (Devanagari text hit by a Latin query) or via summary/chatName simply
 * produces no ranges; the card renders un-highlighted with a leading snippet.
 */
internal fun matchRanges(text: String, terms: List<String>): List<IntRange> {
    val raw = ArrayList<IntRange>()
    for (term in terms) {
        if (term.isEmpty()) continue
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(term, startIndex = i, ignoreCase = true)
            if (hit < 0) break
            raw += hit until hit + term.length
            i = hit + term.length
        }
    }
    if (raw.size <= 1) return raw
    raw.sortBy { it.first }
    val merged = ArrayList<IntRange>(raw.size)
    var cur = raw[0]
    for (r in raw.drop(1)) {
        cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { merged += cur; r }
    }
    merged += cur
    return merged
}

/** Multi-term highlight: every term's occurrences marked in the accent color. */
fun highlightMatches(
    text: String,
    terms: List<String>,
    accent: Color,
    accentBackground: Color,
): AnnotatedString {
    val ranges = matchRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        for (r in ranges) {
            append(text.substring(i, r.first))
            pushStyle(
                SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    background = accentBackground,
                )
            )
            append(text.substring(r.first, r.last + 1))
            pop()
            i = r.last + 1
        }
        append(text.substring(i))
    }
}

/** Single-query wrapper — tokenizes like the search engine so per-term hits highlight. */
fun highlightMatches(
    text: String,
    query: String?,
    accent: Color,
    accentBackground: Color,
): AnnotatedString = highlightMatches(text, SearchEngine.tokenize(query.orEmpty()), accent, accentBackground)

/**
 * A readable window around the earliest match of any term: up to [before] chars of left
 * context and [after] of right, with ellipses. Whole (truncated) text when nothing matches.
 */
fun snippetAround(text: String, terms: List<String>, before: Int = 40, after: Int = 80): String {
    val clean = text.replace('\n', ' ').trim()
    val first = matchRanges(clean, terms).firstOrNull()
        ?: return clean.take(before + after).let { if (clean.length > it.length) "$it…" else it }
    val start = (first.first - before).coerceAtLeast(0)
    val end = (first.last + 1 + after).coerceAtMost(clean.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < clean.length) "…" else ""
    return "$prefix${clean.substring(start, end)}$suffix"
}

/** Single-query wrapper — see the terms overload. */
fun snippetAround(text: String, query: String?, before: Int = 40, after: Int = 80): String =
    snippetAround(text, SearchEngine.tokenize(query.orEmpty()), before, after)
