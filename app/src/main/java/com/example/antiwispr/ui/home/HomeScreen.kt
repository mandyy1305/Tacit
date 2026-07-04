package com.example.antiwispr.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.PillState
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.StatusPill
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.WordmarkStyle

/** The everyday screen: status at a glance, session control, recent transcripts. */
@Composable
fun HomeScreen(
    setup: SetupStatus,
    recents: List<StoredTranscript>,
    chainKeys: Set<String> = emptySet(),
    actions: SetupActions,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTranscript: (StoredTranscript) -> Unit,
    onFinishSetup: () -> Unit,
) {
    Scaffold(containerColor = Color.Transparent) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Dimens.screenPad, end = Dimens.screenPad,
                top = 12.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("TACIT", style = WordmarkStyle, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            "Every voice note, read.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Filled.Search, contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings, contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "status") { StatusCard(setup, actions, onFinishSetup) }

            item(key = "session") { SessionCard(setup, actions) }

            item(key = "recentHeader") {
                Row(
                    Modifier.padding(top = Dimens.sectionGap - Dimens.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader("Recent")
                    Spacer(Modifier.weight(1f))
                    if (setup.transcriptCount > 0) {
                        TextButton(onClick = onOpenSearch) {
                            Text(
                                "Search all ${setup.transcriptCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (recents.isEmpty()) {
                item(key = "empty") { EmptyRecents() }
            } else {
                itemsIndexed(recents.take(5), key = { _, t -> t.key }) { i, t ->
                    val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = entrance,
                        enter = fadeIn(tween(260, delayMillis = i * 45)) +
                            slideInVertically(tween(320, delayMillis = i * 45)) { it / 6 },
                    ) {
                        TranscriptCard(t, chained = t.key in chainKeys, onClick = { onOpenTranscript(t) })
                    }
                }
            }

            item(key = "footer") {
                Text(
                    "All processing happens on this phone.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.sectionGap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(setup: SetupStatus, actions: SetupActions, onFinishSetup: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.large,
            )
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnimatedContent(
            targetState = setup.setupComplete,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) },
            label = "statusCard",
        ) { complete ->
            if (complete) {
                Column(Modifier.padding(Dimens.cardPad)) {
                    Text(
                        "Listening for voice notes.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Play one in WhatsApp — the transcript appears right there.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val detectionOk = !setup.detection.startsWith("⚠")
                        StatusPill(
                            "Detection",
                            if (detectionOk) PillState.Ok else PillState.Attention,
                        )
                        StatusPill("Whisper", PillState.Ok)
                        StatusPill("${setup.indexCount} notes", PillState.Ok)
                    }
                }
            } else {
                Column(Modifier.padding(Dimens.cardPad)) {
                    Text(
                        "Almost there.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "TACIT needs a few things before it can read voice notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MissingRow("Microphone", setup.mic, actions.requestMic)
                    MissingRow("Overlay over WhatsApp", setup.overlay, actions.requestOverlay)
                    MissingRow("File access", setup.files, actions.requestAllFiles)
                    MissingRow("Accessibility service", setup.accessibility, actions.openAccessibility)
                    MissingRow("Whisper model", setup.modelReady || setup.signedIn, actions.downloadModel)
                    MissingRow("Voice-note index", setup.indexReady, actions.buildIndex)
                    Spacer(Modifier.height(14.dp))
                    TacitButton("Finish setup", onFinishSetup, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MissingRow(label: String, satisfied: Boolean, onFix: () -> Unit) {
    if (satisfied) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        GhostButton("Fix", onFix)
    }
}

@Composable
private fun SessionCard(setup: SetupStatus, actions: SetupActions) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.large,
            )
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Dimens.cardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Precision listening",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (setup.sessionActive) StatusPill("Live", PillState.Active)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Screen share lets TACIT hear the note directly instead of through the microphone — " +
                    "cleaner audio, faster matches. One consent per session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = setup.sessionActive,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
                label = "session",
            ) { active ->
                if (active) {
                    GhostButton("Stop listening session", actions.stopSession)
                } else {
                    TacitButton("Start screen share", actions.startSession, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EmptyRecents() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing transcribed yet.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Play a voice note in WhatsApp and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
