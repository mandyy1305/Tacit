package com.example.antiwispr

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.CalendarContract
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import android.widget.Toast
import java.time.ZonedDateTime

/**
 * Actionable entities pulled out of a summary — the concrete "ask" inside a voice note
 * (when, where, whom to call, how much). Extracted at render time from the cached
 * SUMMARY:/ACTIONS: text; never persisted, never synced.
 *
 * Declaration order = display order (the when/where/who a user acts on comes first).
 */
enum class EntityKind { DATETIME, ADDRESS, PHONE, AMOUNT, EMAIL, URL }

data class ActionEntity(
    val kind: EntityKind,
    val text: String,        // chip label, normalized ("₹1,500", "98765 43210", the span)
    val data: String,        // launch payload (address/url/mail text, phone digits, clipboard text)
    val sourceLine: String,  // the summary/action line it came from (calendar-fallback title)
    val remoteAction: RemoteAction? = null, // DATETIME only: classifier's parsed-date action
)

/** Internal working shape: an entity candidate with its span in the joined text. */
internal data class EntityCandidate(
    val kind: EntityKind,
    val start: Int,
    val end: Int,
    val text: String,
    val data: String,
    val sourceLine: String,
    val remoteAction: RemoteAction? = null,
)

/**
 * Finds entities via the platform [TextClassifier] (same on-device ML as long-press smart
 * selection) plus regexes for what it can't do (₹ amounts) or might miss (Indian phone
 * numbers on classifier-less devices). BLOCKING — binder + on-device model, ~5-500 ms —
 * call on Dispatchers.IO only. Never throws: any failure degrades to fewer/zero chips.
 */
object EntityExtractor {

    const val OVERLAY_CAP = 6
    const val READER_CAP = 12
    private const val MAX_CHARS = 2000
    private const val MIN_CONFIDENCE = 0.5f

    fun extract(
        context: Context,
        parts: SummaryParts,
        cap: Int = READER_CAP,
        referenceTime: ZonedDateTime = ZonedDateTime.now(),
    ): List<ActionEntity> = try {
        val joined = (listOf(parts.summary) + parts.actions)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_CHARS)
        if (joined.isBlank()) emptyList()
        else assembleEntities(joined, classifierCandidates(context, joined, referenceTime), cap)
    } catch (t: Throwable) {
        AppLog.w("[entities] extraction failed (non-fatal): ${t.message}")
        emptyList()
    }

    private fun classifierCandidates(
        context: Context,
        joined: String,
        referenceTime: ZonedDateTime,
    ): List<EntityCandidate> {
        val tcm = context.getSystemService(TextClassificationManager::class.java)
            ?: return emptyList()
        val classifier = tcm.textClassifier
        val config = TextClassifier.EntityConfig.Builder()
            .setIncludedTypes(
                listOf(
                    TextClassifier.TYPE_ADDRESS,
                    TextClassifier.TYPE_DATE_TIME,
                    TextClassifier.TYPE_DATE, // "tomorrow" alone classifies as DATE, not DATE_TIME
                    TextClassifier.TYPE_PHONE,
                    TextClassifier.TYPE_EMAIL,
                    TextClassifier.TYPE_URL,
                )
            )
            .includeTypesFromTextClassifier(false) // ONLY our list, not the device defaults
            .build()
        val request = TextLinks.Request.Builder(joined)
            .setEntityConfig(config)
            .setDefaultLocales(LocaleList.getDefault())
            .setReferenceTime(referenceTime)
            .build()
        val out = ArrayList<EntityCandidate>()
        for (link in classifier.generateLinks(request).links) {
            if (link.entityCount == 0) continue
            val type = link.getEntity(0) // ranked by confidence; 0 = best
            if (link.getConfidenceScore(type) < MIN_CONFIDENCE) continue
            val kind = when (type) {
                TextClassifier.TYPE_ADDRESS -> EntityKind.ADDRESS
                TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME -> EntityKind.DATETIME
                TextClassifier.TYPE_PHONE -> EntityKind.PHONE
                TextClassifier.TYPE_EMAIL -> EntityKind.EMAIL
                TextClassifier.TYPE_URL -> EntityKind.URL
                else -> continue
            }
            val span = joined.substring(link.start, link.end).trim()
            if (span.isEmpty()) continue
            // The classifier's action carries the PARSED timestamp inside its PendingIntent —
            // the only way "tomorrow 5pm" lands on the right calendar slot. (tc.intent is
            // binder-stripped to null for the system classifier; actions are the only path.)
            val remote = if (kind == EntityKind.DATETIME) runCatching {
                classifier.classifyText(
                    TextClassification.Request.Builder(joined, link.start, link.end)
                        .setReferenceTime(referenceTime)
                        .build()
                ).actions.firstOrNull()
            }.getOrNull() else null
            out += EntityCandidate(
                kind, link.start, link.end, span,
                data = if (kind == EntityKind.PHONE) span.filter { it.isDigit() || it == '+' } else span,
                sourceLine = lineAt(joined, link.start),
                remoteAction = remote,
            )
        }
        return out
    }
}

// ---- pure (JVM-testable) extraction core --------------------------------------------

// Grouped-with-commas Indian style (1,50,000) OR a plain run capped at 8 digits
// (the cap keeps 10-digit phone-shaped runs out), optional paise.
private const val NUM = """(?:\d{1,3}(?:,\d{2,3})+|\d{1,8})(?:\.\d{1,2})?"""

// Prefix form: ₹1,500 · Rs. 500 · Rs 500 · INR 2000 — marker REQUIRED, so bare digit
// runs (phones, dates) can never match. Suffix form: 1500 rupees · 500 rs.
internal val AMOUNT_RX = Regex(
    """(?:₹|\bRs\.?|\bINR\b)\s*($NUM)(?!\d)""" +
        """|\b($NUM)\s*(?:rupees?|rs)\b""" +
        """|([${'$'}€£])\s*($NUM)(?!\d)""", // common foreign symbols so "$50" also chips
    RegexOption.IGNORE_CASE,
)

// Indian mobile (optional +91) or explicit international. NOT Patterns.PHONE — that
// matches date/amount-shaped digit runs.
internal val PHONE_RX = Regex("""(?<!\d)(?:\+91[\s-]?)?[6-9]\d{9}\b|(?<!\d)\+\d{10,14}\b""")

/** "Rs. 1500" / "1500 rupees" / "INR 1,500" → "₹1,500" (Indian lakh grouping); null if unparseable.
 *  Grouping is hand-rolled (last 3 digits, then pairs) — the host-JVM en-IN locale doesn't do
 *  lakh grouping, and display must match on-device and in unit tests. */
internal fun normalizeAmount(numText: String): String? {
    val clean = numText.replace(",", "")
    val intPart = clean.substringBefore('.').trimStart('0').ifEmpty { "0" }
    if (intPart.any { !it.isDigit() }) return null
    val grouped = if (intPart.length <= 3) intPart else {
        val head = intPart.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        "$pairs,${intPart.takeLast(3)}"
    }
    val frac = clean.substringAfter('.', "").let { if (it.isEmpty()) "" else "." + it.padEnd(2, '0').take(2) }
    return "₹$grouped$frac"
}

/** "$1500" / "€ 2,000" / "£50" → "$1,500" etc. (Western thousands grouping); null if unparseable. */
internal fun normalizeForeign(symbol: String, numText: String): String? {
    val clean = numText.replace(",", "")
    val intPart = clean.substringBefore('.').trimStart('0').ifEmpty { "0" }
    if (intPart.any { !it.isDigit() }) return null
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    val frac = clean.substringAfter('.', "").let { if (it.isEmpty()) "" else "." + it.padEnd(2, '0').take(2) }
    return "$symbol$grouped$frac"
}

internal fun amountCandidates(joined: String): List<EntityCandidate> =
    AMOUNT_RX.findAll(joined).mapNotNull { m ->
        val inr = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val label = (if (inr.isNotEmpty()) normalizeAmount(inr)
                     else normalizeForeign(m.groupValues[3], m.groupValues[4]))
            ?: return@mapNotNull null
        EntityCandidate(
            EntityKind.AMOUNT, m.range.first, m.range.last + 1,
            text = label, data = label, sourceLine = lineAt(joined, m.range.first),
        )
    }.toList()

internal fun phoneCandidates(joined: String, claimed: List<IntRange>): List<EntityCandidate> =
    PHONE_RX.findAll(joined).mapNotNull { m ->
        if (claimed.any { it.overlaps(m.range) }) return@mapNotNull null
        val span = m.value.trim()
        EntityCandidate(
            EntityKind.PHONE, m.range.first, m.range.last + 1,
            text = span, data = span.filter { it.isDigit() || it == '+' },
            sourceLine = lineAt(joined, m.range.first),
        )
    }.toList()

/** Merge classifier + regex candidates: amounts beat overlapping classifier spans (the
 *  currency marker is explicit evidence), dedupe by kind + normalized text, order by
 *  [EntityKind] (stable — occurrence order within a kind), cap AFTER sorting so
 *  datetime/address/phone survive a tight cap. */
internal fun assembleEntities(
    joined: String,
    classifierCands: List<EntityCandidate>,
    cap: Int,
): List<ActionEntity> {
    val amounts = amountCandidates(joined)
    val amountRanges = amounts.map { it.start until it.end }
    val keptTc = classifierCands.filterNot { c ->
        amountRanges.any { it.overlaps(c.start until c.end) }
    }
    val claimed = (keptTc + amounts).map { it.start until it.end }
    val phones = phoneCandidates(joined, claimed)
    return (keptTc + amounts + phones)
        .sortedBy { it.start }
        .distinctBy { "${it.kind}:${normalizedKey(it)}" }
        .sortedBy { it.kind.ordinal }
        .take(cap)
        .map { ActionEntity(it.kind, it.text, it.data, it.sourceLine, it.remoteAction) }
}

private fun normalizedKey(c: EntityCandidate): String = when (c.kind) {
    EntityKind.PHONE -> c.data.trimStart('+').removePrefix("91").trimStart('0')
    EntityKind.EMAIL, EntityKind.URL -> c.text.lowercase().trimEnd('.', ',', ')')
    EntityKind.AMOUNT -> c.data // already normalized display form
    else -> c.text.lowercase().replace(Regex("""\s+"""), " ")
}

private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last

private fun lineAt(joined: String, index: Int): String {
    val start = joined.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = joined.indexOf('\n', index).let { if (it < 0) joined.length else it }
    return joined.substring(start, end).trim()
}

// ---- launching ----------------------------------------------------------------------

/**
 * Turns a tapped [ActionEntity] into the matching system intent. Main-thread OK.
 * Returns true iff an EXTERNAL app was launched — the overlay uses that to dismiss
 * itself (it floats above everything, including whatever it just opened).
 */
object EntityLauncher {

    fun launch(context: Context, entity: ActionEntity): Boolean = when (entity.kind) {
        EntityKind.PHONE ->
            start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(entity.data)}")), entity.text)
        EntityKind.EMAIL ->
            start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(entity.data)}")), entity.text)
        EntityKind.ADDRESS ->
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(entity.data)}")), entity.text)
        EntityKind.URL -> {
            val url = if (entity.data.contains("://")) entity.data else "https://${entity.data}"
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), entity.data)
        }
        EntityKind.DATETIME -> launchDatetime(context, entity)
        EntityKind.AMOUNT -> {
            copyToClipboard(context, "TACIT amount", entity.data)
            false // in-place action — never dismiss the card for a copy
        }
    }

    /** Copies to the clipboard; on 12L and below (no system paste chip) also shows a toast. */
    private fun copyToClipboard(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun launchDatetime(context: Context, entity: ActionEntity): Boolean {
        entity.remoteAction?.let { action ->
            try {
                // API 34+ blocks background PendingIntent activity starts unless the sender
                // opts in; our privilege is the visible overlay / foreground activity.
                val opts = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    ).toBundle()
                } else null
                action.actionIntent.send(context, 0, null, null, null, null, opts)
                return true
            } catch (e: PendingIntent.CanceledException) {
                AppLog.w("[entities] calendar action cancelled — falling back to insert: ${e.message}")
            }
        }
        // No parsed time available — open the event editor with the ask as the title.
        return start(
            context,
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, entity.sourceLine),
            entity.text,
        )
    }

    /** Launches [intent]; if no app can handle it, falls back to copying [fallbackCopy] so a chip
     *  never does nothing. Returns true only when an external app actually opened. */
    private fun start(context: Context, intent: Intent, fallbackCopy: String? = null): Boolean = try {
        // The overlay hands us the application context — cross-app launches need NEW_TASK.
        if (findActivity(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        if (fallbackCopy != null) {
            AppLog.i("[entities] no app for ${intent.action} — copied instead.")
            copyToClipboard(context, "TACIT", fallbackCopy)
        } else {
            AppLog.w("[entities] no app for ${intent.action} ${intent.data} — ignoring tap.")
        }
        false
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
