package com.example.antiwispr.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class PillState { Ok, Attention, Off, Active }

/** Small status capsule: colored dot + label. [PillState.Active] breathes. */
@Composable
fun StatusPill(label: String, state: PillState, modifier: Modifier = Modifier) {
    val dotColor = when (state) {
        PillState.Ok -> MaterialTheme.colorScheme.tertiary
        PillState.Attention -> MaterialTheme.colorScheme.error
        PillState.Off -> MaterialTheme.colorScheme.outline
        PillState.Active -> MaterialTheme.colorScheme.primary
    }
    val dotAlpha = if (state == PillState.Active) {
        val breathe = rememberInfiniteTransition(label = "pill")
        val a by breathe.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pillAlpha",
        )
        a
    } else 1f

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
