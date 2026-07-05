package com.example.antiwispr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antiwispr.ActionEntity
import com.example.antiwispr.EntityKind
import kotlinx.coroutines.delay

/**
 * Tappable pills for the actionable entities pulled out of a summary — the reader's
 * surface for "the ask" (call this number, be there then, pay this much). AMOUNT taps
 * copy in place and flash a confirmation; everything else launches out via [onTap].
 */
@Composable
fun EntityChipsRow(
    entities: List<ActionEntity>,
    onTap: (ActionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which amount chip just copied (flash "Copied ✓" for a beat, same idiom as the copy button).
    var copiedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedText) {
        if (copiedText != null) { delay(1500); copiedText = null }
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entities.forEach { entity ->
            val copied = entity.kind == EntityKind.AMOUNT && copiedText == entity.text
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                    .clickable {
                        onTap(entity)
                        if (entity.kind == EntityKind.AMOUNT) copiedText = entity.text
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iconFor(entity.kind)?.let { icon ->
                    Icon(
                        icon, contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (copied) "Copied ✓" else entity.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// icons-core only (extended is banned); AMOUNT/URL labels are self-descriptive ("₹1,500").
private fun iconFor(kind: EntityKind): ImageVector? = when (kind) {
    EntityKind.DATETIME -> Icons.Filled.DateRange
    EntityKind.ADDRESS -> Icons.Filled.Place
    EntityKind.PHONE -> Icons.Filled.Phone
    EntityKind.EMAIL -> Icons.Filled.Email
    EntityKind.AMOUNT, EntityKind.URL -> null
}
