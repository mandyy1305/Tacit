package com.example.antiwispr.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.WordmarkStyle
import kotlinx.coroutines.delay

enum class SetupStep { Welcome, Microphone, Overlay, AllFiles, Notifications, Accessibility, Model, Summaries, Index, Done }

private fun isSatisfied(step: SetupStep, s: SetupStatus): Boolean = when (step) {
    SetupStep.Welcome, SetupStep.Done -> false
    SetupStep.Microphone -> s.mic
    SetupStep.Overlay -> s.overlay
    SetupStep.AllFiles -> s.files
    SetupStep.Notifications -> s.notifications
    SetupStep.Accessibility -> s.accessibility
    // Signed-in users can run cloud-only — the local models become optional.
    SetupStep.Model -> s.modelReady || s.signedIn
    SetupStep.Summaries -> s.llmReady || s.signedIn
    // An index can exist but be EMPTY (warmed before file access was granted) — that
    // doesn't count as done here, or the step would silently skip on fresh installs.
    SetupStep.Index -> s.indexReady && s.indexCount > 0
}

/** First step after [step] that still needs the user; lands on Done when nothing does. */
private fun nextAfter(step: SetupStep, s: SetupStatus): SetupStep {
    val entries = SetupStep.entries
    var i = step.ordinal + 1
    while (i < SetupStep.Done.ordinal && isSatisfied(entries[i], s)) i++
    return entries[i.coerceAtMost(SetupStep.Done.ordinal)]
}

/**
 * Guided first-run setup: one full-screen step at a time, each explaining WHY before asking.
 * Steps that are already satisfied are skipped; a step that becomes satisfied (e.g. returning
 * from a Settings deep-link) shows a check for a beat and advances on its own.
 */
@Composable
fun OnboardingScreen(
    setup: SetupStatus,
    actions: SetupActions,
    onFinished: () -> Unit,
) {
    var stepOrdinal by rememberSaveable { mutableIntStateOf(SetupStep.Welcome.ordinal) }
    val step = SetupStep.entries[stepOrdinal]
    val satisfied = isSatisfied(step, setup)
    val currentSetup by rememberUpdatedState(setup)

    LaunchedEffect(step, satisfied) {
        if (satisfied) {
            delay(650) // let the check land
            stepOrdinal = nextAfter(step, currentSetup).ordinal
        }
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = Dimens.screenPad)
        ) {
            Spacer(Modifier.height(20.dp))
            if (step != SetupStep.Welcome && step != SetupStep.Done) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TACIT",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    StepDots(step, setup)
                }
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(tween(280, delayMillis = 60)) +
                        slideInHorizontally(tween(340)) { it / 5 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(280)) { -it / 6 })
                },
                label = "step",
            ) { s ->
                StepPage(s, currentSetup, actions,
                    satisfied = isSatisfied(s, currentSetup),
                    onSkip = { stepOrdinal = nextAfter(s, currentSetup).ordinal },
                    onFinished = onFinished)
            }
        }
    }
}

@Composable
private fun StepDots(current: SetupStep, setup: SetupStatus) {
    val steps = SetupStep.entries.filter { it != SetupStep.Welcome && it != SetupStep.Done }
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEach { s ->
            val active = s == current
            val done = isSatisfied(s, setup)
            val width by animateDpAsState(
                targetValue = if (active) 22.dp else 8.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "dot",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun StepPage(
    step: SetupStep,
    setup: SetupStatus,
    actions: SetupActions,
    satisfied: Boolean,
    onSkip: () -> Unit,
    onFinished: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.32f))

        when (step) {
            SetupStep.Welcome -> {
                Text(
                    buildAnnotatedString {
                        append("TACIT")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
                    },
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Every voice note, read.",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "TACIT listens when a WhatsApp voice note plays and shows you the words — " +
                        "right there, over the chat. Everything runs on this phone; " +
                        "nothing ever leaves it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SetupStep.Done -> {
                DoneBadge()
                Spacer(Modifier.height(24.dp))
                Text(
                    "Every voice note, read.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Open WhatsApp and play a voice note — the transcript appears on its own.\n\n" +
                        "Tip: for the most accurate listening, start Precision listening from Home.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val copy = stepCopy(step)
                Text(
                    copy.overline,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    copy.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    copy.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.weight(0.68f))

        StepAction(step, setup, actions, satisfied, onSkip, onFinished)

        Spacer(Modifier.height(28.dp))
    }
}

private data class StepCopy(val overline: String, val title: String, val body: String)

private fun stepCopy(step: SetupStep): StepCopy = when (step) {
    SetupStep.Microphone -> StepCopy(
        "PERMISSIONS",
        "Hear the note as it plays.",
        "TACIT listens for a few seconds to identify which voice note is playing. " +
            "Android requires the microphone permission for any audio capture — " +
            "including the high-quality internal path."
    )
    SetupStep.Overlay -> StepCopy(
        "PERMISSIONS",
        "Words, right over WhatsApp.",
        "The transcript appears in a floating card on top of the chat, so you never " +
            "have to switch apps. Android calls this “display over other apps”."
    )
    SetupStep.AllFiles -> StepCopy(
        "PERMISSIONS",
        "Find your voice notes.",
        "Voice notes live in WhatsApp's media folder. TACIT reads those audio files " +
            "to recognise and transcribe them — they never leave this device."
    )
    SetupStep.Notifications -> StepCopy(
        "PERMISSIONS · OPTIONAL",
        "A quiet heads-up.",
        "Android shows a small notification while TACIT is listening. " +
            "This is optional — everything works without it."
    )
    SetupStep.Accessibility -> StepCopy(
        "PERMISSIONS",
        "Notice the play tap.",
        "TACIT uses an accessibility service — scoped only to WhatsApp — to notice " +
            "when you tap play on a voice note. In the next screen, find TACIT under " +
            "Installed apps and switch it on."
    )
    SetupStep.Model -> StepCopy(
        "ON-DEVICE SPEECH",
        "Bring the words on-device.",
        "TACIT transcribes with Whisper, running entirely on this phone. " +
            "It's a one-time download of about 360 MB — Wi-Fi recommended."
    )
    SetupStep.Summaries -> StepCopy(
        "ON-DEVICE AI · OPTIONAL",
        "Turn notes into orders.",
        "TACIT can distill every note into a short summary with action items — " +
            "quantities, names, dates, promises. It's a one-time 1.6 GB download; " +
            "you can skip this and add it later from Settings."
    )
    SetupStep.Index -> StepCopy(
        "YOUR LIBRARY",
        "Learn your notes.",
        "TACIT fingerprints the voice notes already on this phone so it can tell — " +
            "in a couple of seconds — exactly which one is playing."
    )
    else -> StepCopy("", "", "")
}

@Composable
private fun StepAction(
    step: SetupStep,
    setup: SetupStatus,
    actions: SetupActions,
    satisfied: Boolean,
    onSkip: () -> Unit,
    onFinished: () -> Unit,
) {
    AnimatedContent(
        targetState = satisfied,
        transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(120)) },
        label = "action",
    ) { done ->
        if (done) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DoneBadge(size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Done",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else Column {
            when (step) {
                SetupStep.Welcome ->
                    TacitButton("Begin", onClick = onSkip, modifier = Modifier.fillMaxWidth())
                SetupStep.Microphone ->
                    TacitButton("Allow microphone", actions.requestMic, Modifier.fillMaxWidth())
                SetupStep.Overlay ->
                    TacitButton("Allow overlay", actions.requestOverlay, Modifier.fillMaxWidth())
                SetupStep.AllFiles ->
                    TacitButton("Allow file access", actions.requestAllFiles, Modifier.fillMaxWidth())
                SetupStep.Notifications -> {
                    TacitButton("Allow notifications", actions.requestNotifications, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Skip for now", onSkip, Modifier.align(Alignment.CenterHorizontally))
                }
                SetupStep.Accessibility ->
                    TacitButton("Open Accessibility settings", actions.openAccessibility, Modifier.fillMaxWidth())
                SetupStep.Model -> {
                    if (setup.modelDownloading) {
                        ProgressCapsule(setup.modelProgress, setup.modelStatus)
                    } else {
                        if (setup.modelStatus.startsWith("download failed") || setup.modelStatus == "incomplete") {
                            Text(
                                "Something interrupted the download — it resumes from the finished files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        TacitButton("Download model  ·  360 MB", actions.downloadModel, Modifier.fillMaxWidth())
                    }
                }
                SetupStep.Summaries -> {
                    if (setup.llmDownloading) {
                        ProgressCapsule(setup.llmProgress, setup.llmStatus)
                    } else {
                        if (setup.llmStatus.startsWith("download failed") || setup.llmStatus == "incomplete") {
                            Text(
                                "Something interrupted the download — it resumes where it left off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        TacitButton("Download model  ·  1.6 GB", actions.downloadLlm, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        GhostButton("Skip for now", onSkip, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                SetupStep.Index -> {
                    if (setup.indexBuilding) {
                        ProgressCapsule(setup.indexProgress, setup.indexStatus.ifBlank { "Reading your notes…" })
                    } else {
                        val builtEmpty = setup.indexReady && setup.indexCount == 0
                        if (builtEmpty) {
                            Text(
                                "No voice notes found yet. If your WhatsApp has voice notes, " +
                                    "make sure file access is granted, then try again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        TacitButton("Prepare my notes", actions.buildIndex, Modifier.fillMaxWidth())
                        if (builtEmpty) {
                            Spacer(Modifier.height(6.dp))
                            GhostButton("Skip for now", onSkip, Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
                SetupStep.Done ->
                    TacitButton("Take me home", onFinished, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DoneBadge(size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
