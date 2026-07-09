package com.example.antiwispr.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.WordmarkStyle

/**
 * The Home tab: a living "listening" medallion that IS the master on/off, the readiness state it
 * reflects, a quiet precision-listening row, and the most recent transcripts. The medallion is the
 * hero because TACIT is a passive utility — the everyday question is "is it working?", not "let me
 * toggle it". Tapping it turns TACIT on/off (or opens setup when grants are still missing).
 */
@Composable
fun HomeScreen(
    setup: SetupStatus,
    recents: List<StoredTranscript>,
    chainKeys: Set<String> = emptySet(),
    actions: SetupActions,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onOpenTranscript: (StoredTranscript) -> Unit,
    onFinishSetup: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        // The bottom bar (owned by AppRoot) handles the bottom inset; take only top + sides here.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.screenPad, end = Dimens.screenPad,
                top = 12.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
        ) {
            item(key = "header") { Header(onOpenSettings) }

            item(key = "hero") { HeroSection(setup, actions, onFinishSetup) }

            if (setup.tacitEnabled && setup.coreGrantsOk) {
                item(key = "precision") { PrecisionRow(setup, actions) }
            }

            item(key = "recentHeader") { RecentHeader(setup, onOpenLibrary) }

            if (recents.isEmpty()) {
                item(key = "empty") { EmptyRecents() }
            } else {
                itemsIndexed(recents.take(2), key = { _, t -> t.key }) { i, t ->
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

            item(key = "footer") { Footer(setup) }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("TACIT", style = WordmarkStyle, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Every voice note, read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// ---- Hero: the medallion + the readiness state it reflects -----------------------------------

@Composable
private fun HeroSection(setup: SetupStatus, actions: SetupActions, onFinishSetup: () -> Unit) {
    val onTap: () -> Unit = {
        when (setup.health) {
            SetupHealth.OFF -> actions.turnOn()
            SetupHealth.NEEDS_SETUP -> onFinishSetup()
            else -> actions.turnOff()
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        ListeningMedallion(setup.health, onTap)
        Spacer(Modifier.height(18.dp))
        AnimatedContent(
            targetState = setup.health,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) },
            label = "heroState",
            modifier = Modifier.fillMaxWidth(),
        ) { health ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (health) {
                    SetupHealth.READY -> ReadyState(setup)
                    SetupHealth.OFF -> OffState(actions)
                    SetupHealth.NEEDS_SETUP -> NeedsSetupState(setup, actions, onFinishSetup)
                    SetupHealth.GETTING_READY -> GettingReadyState(setup)
                    SetupHealth.ATTENTION -> AttentionState(setup, actions)
                }
            }
        }
    }
}

/**
 * The listening dial. READY: an amber disc with the wave motif, breathing via two expanding rings.
 * GETTING_READY: a slow sweep arc. OFF: a muted, outlined disc with a power glyph. NEEDS_SETUP /
 * ATTENTION: awake but static. Tapping toggles the master switch (see [HeroSection.onTap]).
 */
@Composable
private fun ListeningMedallion(health: SetupHealth, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = health == SetupHealth.READY
    val gettingReady = health == SetupHealth.GETTING_READY

    val infinite = rememberInfiniteTransition(label = "medallion")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "pulse",
    )
    val sweep by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )

    val disc = when (health) {
        SetupHealth.READY -> cs.primary
        SetupHealth.GETTING_READY -> cs.primaryContainer
        else -> cs.surfaceContainerHigh
    }
    val glyphTint = when (health) {
        SetupHealth.READY -> cs.onPrimary
        SetupHealth.OFF -> cs.onSurfaceVariant
        else -> cs.primary
    }
    val ring = cs.primary
    val outline = cs.outline

    val desc = when (health) {
        SetupHealth.OFF -> "TACIT is off. Double tap to turn on."
        SetupHealth.NEEDS_SETUP -> "Setup unfinished. Double tap to finish setup."
        else -> "TACIT is on. Double tap to turn off."
    }

    Box(
        Modifier
            .size(196.dp)
            .clip(CircleShape)
            .semantics { contentDescription = desc }
            .clickable(role = Role.Button) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val discR = size.minDimension * 0.31f
            // Breathing rings — READY only.
            if (active) {
                listOf(pulse, (pulse + 0.5f) % 1f).forEach { p ->
                    drawCircle(
                        ring,
                        radius = discR * (1f + p * 0.55f),
                        alpha = (1f - p) * 0.28f,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            // Faint halo for every "awake" state.
            if (health != SetupHealth.OFF) {
                drawCircle(ring, radius = discR * 1.3f, alpha = 0.12f, style = Stroke(width = 2.dp.toPx()))
            }
            // Warming-up sweep.
            if (gettingReady) {
                val r = discR * 1.3f
                drawArc(
                    ring, startAngle = sweep, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(disc, radius = discR)
            if (health == SetupHealth.OFF) {
                drawCircle(outline, radius = discR, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        Icon(
            imageVector = if (health == SetupHealth.OFF) TacitIcons.Power else TacitIcons.Wave,
            contentDescription = null,
            tint = glyphTint,
            modifier = Modifier.size(60.dp),
        )
    }
}

@Composable
private fun HeroTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.headlineSmall,
    color = MaterialTheme.colorScheme.onSurface,
    textAlign = TextAlign.Center,
)

@Composable
private fun HeroBody(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
)

@Composable
private fun ReadyState(setup: SetupStatus) {
    HeroTitle("Listening for voice notes")
    Spacer(Modifier.height(6.dp))
    HeroBody("Play one in WhatsApp. The words appear right there.")
    Spacer(Modifier.height(14.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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
private fun OffState(actions: SetupActions) {
    HeroTitle("TACIT is off")
    Spacer(Modifier.height(6.dp))
    HeroBody("Nothing is listening or matching. Turn it on to read your voice notes again.")
    Spacer(Modifier.height(16.dp))
    TacitButton("Turn TACIT on", actions.turnOn, Modifier.fillMaxWidth())
}

@Composable
private fun NeedsSetupState(setup: SetupStatus, actions: SetupActions, onFinishSetup: () -> Unit) {
    HeroTitle("Almost there")
    Spacer(Modifier.height(6.dp))
    HeroBody("Grant a few things so TACIT can read your voice notes.")
    Spacer(Modifier.height(14.dp))
    DetailCard {
        MissingRow("Voice note access", "No notes can be seen.", setup.files, actions.requestAllFiles)
        MissingRow("The floating card", "Words have nowhere to appear.", setup.overlay, actions.requestOverlay)
        MissingRow("Play detection", "TACIT can't notice the play tap.", setup.accessibility, actions.openAccessibility)
        MissingRow("Microphone", "TACIT can't hear the note.", setup.mic, actions.requestMic)
    }
    Spacer(Modifier.height(14.dp))
    TacitButton("Finish setup", onFinishSetup, Modifier.fillMaxWidth())
}

@Composable
private fun GettingReadyState(setup: SetupStatus) {
    HeroTitle("Nearly ready")
    Spacer(Modifier.height(12.dp))
    DetailCard {
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
}

@Composable
private fun AttentionState(setup: SetupStatus, actions: SetupActions) {
    when {
        !setup.whatsAppInstalled -> {
            HeroTitle("WhatsApp isn't on this phone")
            Spacer(Modifier.height(6.dp))
            HeroBody("TACIT will be ready the moment it is.")
        }
        !setup.engineReady -> {
            HeroTitle("Reading isn't set up")
            Spacer(Modifier.height(6.dp))
            HeroBody(
                if (setup.signedIn) "Turn on TACIT Cloud in Settings, or add the offline pack to read on this phone."
                else "Add the offline pack so notes can be read on this phone."
            )
            Spacer(Modifier.height(14.dp))
            GhostButton("Get the pack · 360 MB", actions.downloadModel)
        }
        else -> { // detection degraded
            HeroTitle("WhatsApp changed something")
            Spacer(Modifier.height(6.dp))
            HeroBody("TACIT switched to its backup detection. Everything still works; an update on our side will restore full speed.")
        }
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Dimens.cardPad, vertical = 10.dp), content = content)
    }
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

// ---- Precision listening: a quiet secondary control ------------------------------------------

@Composable
private fun PrecisionRow(setup: SetupStatus, actions: SetupActions) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.large,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.cardPad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                TacitIcons.ScreenShare, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Precision listening",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (setup.sessionActive) "Live — hearing notes directly. Cleaner audio, surer matches."
                    else "Cleaner audio while a note plays. One consent per session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (setup.sessionActive) {
                GhostButton("Stop", actions.stopSession)
            } else {
                GhostButton("Start", actions.startSession)
            }
        }
    }
}

// ---- Recent transcripts ----------------------------------------------------------------------

@Composable
private fun RecentHeader(setup: SetupStatus, onOpenLibrary: () -> Unit) {
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

@Composable
private fun Footer(setup: SetupStatus) {
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
