package com.example.antiwispr.ui.components

import android.text.format.DateUtils
import androidx.compose.ui.text.AnnotatedString
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
 * Marks every case-insensitive occurrence of [query] in [text] with the accent color.
 * Plain AnnotatedString when the query is blank.
 */
fun highlightMatches(
    text: String,
    query: String?,
    accent: Color,
    accentBackground: Color,
): AnnotatedString {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(q, startIndex = i, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(i)); break
            }
            append(text.substring(i, hit))
            pushStyle(
                SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    background = accentBackground,
                )
            )
            append(text.substring(hit, hit + q.length))
            pop()
            i = hit + q.length
        }
    }
}

/**
 * A readable window around the first match: up to [before] chars of left context and
 * [after] of right, with ellipses. Whole (truncated) text when no query/match.
 */
fun snippetAround(text: String, query: String?, before: Int = 40, after: Int = 80): String {
    val clean = text.replace('\n', ' ').trim()
    val q = query?.trim().orEmpty()
    val hit = if (q.isEmpty()) -1 else clean.indexOf(q, ignoreCase = true)
    if (hit < 0) return clean.take(before + after).let { if (clean.length > it.length) "$it…" else it }
    val start = (hit - before).coerceAtLeast(0)
    val end = (hit + q.length + after).coerceAtMost(clean.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < clean.length) "…" else ""
    return "$prefix${clean.substring(start, end)}$suffix"
}
