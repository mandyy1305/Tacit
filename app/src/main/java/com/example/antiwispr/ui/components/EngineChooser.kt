package com.example.antiwispr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.theme.Dimens

/**
 * The engine fork (doc 01 W2): TACIT Cloud trial vs the offline pack, no preselection.
 * Shared by onboarding's Engine page and Home's "Choose how TACIT reads" sheet so the two
 * surfaces can never drift apart — same cards, same terms, same neutrality. [onAction] fires
 * after either engine action starts (sign-in launched / download begun) so a hosting sheet
 * can dismiss itself; the hero's GETTING_READY state takes over from there.
 */
@Composable
fun EngineChooser(setup: SetupStatus, actions: SetupActions, onAction: () -> Unit = {}) {
    var choice by rememberSaveable { mutableIntStateOf(0) } // 0 = none, 1 = cloud, 2 = offline
    Column(Modifier.fillMaxWidth()) {
        EngineCard(
            selected = choice == 1,
            title = "TACIT Cloud",
            tag = "FREE TRIAL",
            lines = listOf(
                "The sharpest accuracy, in 23 Indian languages.",
                "Choose how words are written: Hinglish, native script, or English.",
                "Backup and sync across your devices.",
                "Free for 7 days or 25 notes. Then TACIT Pro, Rs 129/month, cancel anytime.",
            ),
            onClick = { choice = 1 },
        )
        Spacer(Modifier.height(12.dp))
        EngineCard(
            selected = choice == 2,
            title = "Only on this phone",
            tag = null,
            lines = listOf(
                "Free forever. Nothing ever leaves this phone.",
                "A one-time download (360 MB).",
                "Works without internet once installed.",
            ),
            onClick = { choice = 2 },
        )
        Spacer(Modifier.height(20.dp))
        if (setup.modelDownloading) {
            ProgressCapsule(setup.modelProgress, setup.modelStatus)
            Spacer(Modifier.height(12.dp))
        }
        val label = when (choice) {
            1 -> "Continue with Google"
            2 -> "Get the offline pack  ·  360 MB"
            else -> "Choose one"
        }
        TacitButton(
            label,
            onClick = {
                if (choice == 1) actions.signIn() else actions.downloadModel()
                onAction()
            },
            Modifier.fillMaxWidth(),
            enabled = choice != 0,
        )
    }
}

@Composable
private fun EngineCard(
    selected: Boolean,
    title: String,
    tag: String?,
    lines: List<String>,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .border(if (selected) 1.5.dp else Dimens.hairline, border, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(Dimens.cardPad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (tag != null) {
                Text(
                    tag,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("·  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
