package com.example.antiwispr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.theme.Dimens

/**
 * One transcript in a list: sent-date + freshness up top, a 3-line snippet below.
 * Search matches are highlighted in the accent color when [query] is set.
 * NOTE: durationSec in the store is always -1; deliberately never rendered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptCard(
    transcript: StoredTranscript,
    query: String? = null,
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
            Row {
                Text(
                    buildString {
                        append(waDateLabel(transcript.waDate) ?: "Voice note")
                        if (transcript.chatName.isNotEmpty()) append("  ·  ${transcript.chatName}")
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (chained) {
                    Text(
                        "CHAIN",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    relativeTime(transcript.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
