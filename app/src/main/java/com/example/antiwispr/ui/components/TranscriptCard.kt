package com.example.antiwispr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.theme.Dimens

/**
 * One transcript in a list: sent-date, chat, duration + freshness up top, a 3-line snippet below.
 * Search matches are highlighted in the accent color when [query] is set. Duration renders once a
 * note has been (re-)transcribed since the pipeline started persisting it (legacy records show none).
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.medium,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Dimens.cardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(waDateLabel(transcript.waDate) ?: "Voice note")
                        if (transcript.chatName.isNotEmpty()) append("  ·  ${transcript.chatName}")
                        if (transcript.durationSec > 0) durationLabel(transcript.durationSec)?.let { append("  ·  $it") }
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (transcript.source == "local") {
                    // Engine badge: this note was transcribed by on-device Whisper (cloud-made
                    // records carry no badge). Legacy records without a source show nothing.
                    Text(
                        "ON-DEVICE",
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (chained) {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            TacitIcons.Chain,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "CHAIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    relativeTime(transcript.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hint != null) {
                // Explains a match the snippet can't highlight (cross-script / summary / chat name).
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                highlightMatches(
                    snippetAround(transcript.text, query),
                    query,
                    accent = MaterialTheme.colorScheme.primary,
                    accentBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
