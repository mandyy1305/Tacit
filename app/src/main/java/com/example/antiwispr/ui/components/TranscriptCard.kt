package com.example.antiwispr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript

/**
 * One transcript in a list — kept compact so ~5 fit a screen. Header: the sender (accent) with a
 * chain-link when it belongs to a detected burst, and the group/chat on the right. A two-line
 * snippet (search matches highlighted when [query] is set), a hairline, then a footer of
 * date · duration · freshness.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptCard(
    transcript: StoredTranscript,
    query: String? = null,
    hint: String? = null,
    chained: Boolean = false,
    onClick: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val (sender, group) = splitChatName(transcript.chatName)
    val cardShape = RoundedCornerShape(13.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(0.5.dp, cs.outlineVariant), cardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = cardShape,
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Header: sender (+ chain link) left, group right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sender,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chained) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            TacitIcons.Chain, contentDescription = "In a chain",
                            tint = cs.tertiary, modifier = Modifier.size(13.dp),
                        )
                    }
                }
                if (group != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        TacitIcons.People, contentDescription = null,
                        tint = cs.tertiary, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        group,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                }
            }

            if (hint != null) {
                // Explains a match the snippet can't highlight (cross-script / summary / chat name).
                Spacer(Modifier.height(5.dp))
                Text(
                    hint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(cs.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                highlightMatches(
                    snippetAround(transcript.text, query),
                    query,
                    accent = cs.primary,
                    accentBackground = cs.primaryContainer.copy(alpha = 0.35f),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(11.dp))
            InkDivider()
            Spacer(Modifier.height(8.dp))

            // Footer: date · duration on the left, freshness on the right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val date = waDateLabel(transcript.waDate)
                val dur = if (transcript.durationSec > 0) durationLabel(transcript.durationSec) else null
                if (date != null) MetaItem(TacitIcons.Calendar, cs.onSurfaceVariant, date)
                if (date != null && dur != null) MetaDivider()
                if (dur != null) MetaItem(TacitIcons.Wave, cs.primary, dur)
                Spacer(Modifier.weight(1f))
                MetaDivider()
                Text(
                    relativeTime(transcript.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Sender · Group" (group note) splits into sender + group; a plain name has no group. */
private fun splitChatName(chatName: String): Pair<String, String?> {
    if (chatName.isBlank()) return "Voice note" to null
    val i = chatName.indexOf(" · ")
    return if (i >= 0) chatName.substring(0, i) to chatName.substring(i + 3) else chatName to null
}

@Composable
private fun MetaItem(icon: ImageVector, iconTint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetaDivider() {
    Box(
        Modifier
            .padding(horizontal = 9.dp)
            .size(width = 1.dp, height = 10.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
