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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupHealth
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.PillState
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.StatusPill
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.WordmarkStyle

/** The everyday screen: readiness at a glance, session control, recent transcripts. */
@Composable
fun HomeScreen(
    setup: SetupStatus,
    recents: List<StoredTranscript>,
    chainKeys: Set<String> = emptySet(),
    actions: SetupActions,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit = {},
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
                            Icons.Filled.Search, contentDescription = "Search your notes",
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
                        TextButton(onClick = onOpenLibrary) {
                            Text(
                                "View all ${setup.transcriptCount}",
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
                    if (setup.signedIn) "On this phone, backed up to your TACIT Cloud."
                    else "Everything stays on this phone.",
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

/** The single source of truth on Home: renders exactly one of the five SetupHealth states. */
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
            targetState = setup.health,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) },
            label = "statusCard",
        ) { health ->
            Column(Modifier.padding(Dimens.cardPad)) {
                when (health) {
                    SetupHealth.READY -> ReadyContent(setup)
                    SetupHealth.GETTING_READY -> GettingReadyContent(setup)
                    SetupHealth.NEEDS_SETUP -> NeedsSetupContent(setup, actions, onFinishSetup)
                    SetupHealth.ATTENTION -> AttentionContent(setup, actions)
                    SetupHealth.OFF -> OffContent(actions)
                }
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun CardBody(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ReadyContent(setup: SetupStatus) {
    CardTitle("Listening for voice notes.")
    Spacer(Modifier.height(6.dp))
    CardBody("Play one in WhatsApp. The words appear right there.")
    Spacer(Modifier.height(14.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill("Detection", if (setup.detectionDegraded) PillState.Attention else PillState.Ok)
        val transcription = when {
            setup.modelReady -> "Transcription · on phone"
            setup.signedIn && setup.cloudTranscription -> "Transcription · Cloud"
            else -> "Transcription"
        }
        StatusPill(transcription, PillState.Ok)
        if (setup.transcriptCount > 0) {
            StatusPill("${setup.transcriptCount} notes", PillState.Ok)
        } else {
            StatusPill("Waiting for your first note", PillState.Off)
        }
        if (!setup.autoRead) StatusPill("Auto-read · off", PillState.Off)
    }
}

@Composable
private fun GettingReadyContent(setup: SetupStatus) {
    CardTitle("Nearly ready.")
    Spacer(Modifier.height(12.dp))
    if (setup.modelDownloading) {
        ProgressCapsule(setup.modelProgress, "Offline transcription")
        Spacer(Modifier.height(8.dp))
    }
    if (setup.llmDownloading) {
        ProgressCapsule(setup.llmProgress, "Offline summaries")
        Spacer(Modifier.height(8.dp))
    }
    if (setup.indexBuilding && !setup.indexReady) {
        ProgressCapsule(setup.indexProgress, "Learning your notes")
    }
}

@Composable
private fun NeedsSetupContent(setup: SetupStatus, actions: SetupActions, onFinishSetup: () -> Unit) {
    CardTitle("Almost there.")
    Spacer(Modifier.height(6.dp))
    CardBody("Grant a few things so TACIT can read your voice notes.")
    Spacer(Modifier.height(12.dp))
    MissingRow("Voice note access", "No notes can be seen.", setup.files, actions.requestAllFiles)
    MissingRow("The floating card", "Words have nowhere to appear.", setup.overlay, actions.requestOverlay)
    MissingRow("Play detection", "TACIT can't notice the play tap.", setup.accessibility, actions.openAccessibility)
    MissingRow("Microphone", "TACIT can't hear the note.", setup.mic, actions.requestMic)
    Spacer(Modifier.height(14.dp))
    TacitButton("Finish setup", onFinishSetup, Modifier.fillMaxWidth())
}

@Composable
private fun AttentionContent(setup: SetupStatus, actions: SetupActions) {
    when {
        !setup.whatsAppInstalled -> {
            CardTitle("WhatsApp isn't on this phone.")
            Spacer(Modifier.height(6.dp))
            CardBody("TACIT will be ready the moment it is.")
        }
        !setup.engineReady -> {
            CardTitle("Reading isn't set up.")
            Spacer(Modifier.height(6.dp))
            CardBody(
                if (setup.signedIn) "Turn on TACIT Cloud in Settings, or add the offline pack to read on this phone."
                else "Add the offline pack so notes can be read on this phone."
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Get the pack · 360 MB", actions.downloadModel)
        }
        else -> { // detection degraded
            CardTitle("WhatsApp changed something.")
            Spacer(Modifier.height(6.dp))
            CardBody("TACIT switched to its backup detection. Everything still works; an update on our side will restore full speed.")
        }
    }
}

@Composable
private fun OffContent(actions: SetupActions) {
    CardTitle("TACIT is off.")
    Spacer(Modifier.height(6.dp))
    CardBody("No listening, matching, or floating card. Search and Library still work.")
    Spacer(Modifier.height(14.dp))
    TacitButton("Turn TACIT on", actions.turnOn, Modifier.fillMaxWidth())
}

@Composable
private fun MissingRow(label: String, consequence: String, satisfied: Boolean, onFix: () -> Unit) {
    if (satisfied) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(consequence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                "TACIT hears the note directly instead of through the mic. Cleaner audio, surer matches. " +
                    "Android asks for one consent per session, and a session lasts until you stop it or the phone restarts.",
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
                    GhostButton("Stop session", actions.stopSession)
                } else {
                    TacitButton("Start a session", actions.startSession, Modifier.fillMaxWidth())
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
