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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.core.Toggles
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.components.EngineChooser
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.overlay.DemoOverlayHost
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.WordmarkStyle
import kotlinx.coroutines.delay

// W1..W8. Notifications, the offline-summaries pack, and the transcription pack's own step are
// contextual now (doc 01): the engine choice (W2) covers transcription; the rest surface later.
enum class SetupStep { Welcome, Engine, Files, Overlay, Accessibility, Microphone, Index, Done }

private fun isSatisfied(step: SetupStep, s: SetupStatus): Boolean = when (step) {
    SetupStep.Welcome, SetupStep.Done -> false
    // Engine chosen: signed in (cloud), or the offline pack is present/arriving.
    SetupStep.Engine -> s.signedIn || s.modelReady || s.modelDownloading
    SetupStep.Files -> s.files
    SetupStep.Overlay -> s.overlay
    SetupStep.Accessibility -> s.accessibility
    SetupStep.Microphone -> s.mic
    // Empty index counts as ready (doc 01 §0): a user with zero notes must not be stuck here.
    SetupStep.Index -> s.indexReady
}

/** First step after [step] that still needs the user; lands on Done when nothing does. */
private fun nextAfter(step: SetupStep, s: SetupStatus): SetupStep {
    val entries = SetupStep.entries
    var i = step.ordinal + 1
    while (i < SetupStep.Done.ordinal && isSatisfied(entries[i], s)) i++
    return entries[i.coerceAtMost(SetupStep.Done.ordinal)]
}

/**
 * Guided first-run setup, W1..W8: value first (a live demo), then the engine fork, then each grant
 * exactly when it is needed with a Play-compliant prominent disclosure before the accessibility and
 * microphone asks. Satisfied steps auto-skip; a step that becomes satisfied shows a check and
 * advances on its own.
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
                StepPage(
                    s, currentSetup, actions,
                    onSkip = { stepOrdinal = nextAfter(s, currentSetup).ordinal },
                    onFinished = onFinished,
                )
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
    onSkip: () -> Unit,
    onFinished: () -> Unit,
) {
    when (step) {
        SetupStep.Welcome -> WelcomePage(onContinue = onSkip)
        SetupStep.Engine -> EnginePage(setup, actions, onDecideLater = onSkip)
        SetupStep.Accessibility -> DisclosurePage(kind = DisclosureKind.ACCESSIBILITY, actions = actions, onSkip = onSkip)
        SetupStep.Microphone -> DisclosurePage(kind = DisclosureKind.MICROPHONE, actions = actions, onSkip = onSkip)
        SetupStep.Done -> DonePage(setup, onFinished)
        else -> GrantPage(step, setup, actions)
    }
}

// ---- W1: Welcome (live demo) ------------------------------------------------------------------

@Composable
private fun WelcomePage(onContinue: () -> Unit) {
    var replay by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        Text(
            buildAnnotatedString {
                append("TACIT")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
            },
            style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Every voice note, read.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(20.dp))
        // The live demo: the real overlay card, replayed inline (no window, no permission). Fixed
        // height so the card growing through its states never reflows the page around it — sized
        // to fit the tallest (transcript) state without the old empty gutters.
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp), // equal inset on all four sides
            contentAlignment = Alignment.Center,
        ) {
            DemoOverlayHost(replayKey = replay)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This card appears right over your chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            GhostButton("Play it again", onClick = { replay++ })
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "TACIT reads WhatsApp voice notes while they play. The words float over the chat, " +
                "in the sender's own mix of languages.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Made to stay private. Notes can be read on this phone alone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(24.dp))
        TacitButton("Set up TACIT", onContinue, Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
    }
}

// ---- W2: Choose your engine -------------------------------------------------------------------

@Composable
private fun EnginePage(setup: SetupStatus, actions: SetupActions, onDecideLater: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Overline("HOW TACIT READS")
        Spacer(Modifier.height(12.dp))
        Text(
            "Choose how notes become words.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Both read every note. You can switch anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        EngineChooser(setup, actions)
        Spacer(Modifier.height(6.dp))
        GhostButton("Decide later", onDecideLater, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(28.dp))
    }
}

// ---- W5 / W6: prominent-disclosure consent ----------------------------------------------------

private enum class DisclosureKind { ACCESSIBILITY, MICROPHONE }

@Composable
private fun DisclosurePage(kind: DisclosureKind, actions: SetupActions, onSkip: () -> Unit) {
    val context = LocalContext.current
    val consented = when (kind) {
        DisclosureKind.ACCESSIBILITY -> Toggles.accessibilityConsentMs > 0
        DisclosureKind.MICROPHONE -> Toggles.micConsentMs > 0
    }
    // Accessibility has a guide sub-state (enable is in system settings); the mic ask is a runtime
    // dialog fired straight from consent, so it needs no guide.
    var guide by rememberSaveable(kind) { mutableStateOf(consented && kind == DisclosureKind.ACCESSIBILITY) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        if (kind == DisclosureKind.ACCESSIBILITY && guide) {
            // Guide sub-state: send the user to Accessibility settings.
            Overline("PERMISSIONS · ACCESSIBILITY")
            Spacer(Modifier.height(12.dp))
            Title("Turn on TACIT.")
            Spacer(Modifier.height(16.dp))
            StepLine("1. Under Installed apps, tap TACIT")
            StepLine("2. Turn on Use TACIT")
            StepLine("3. Tap Allow on Android's confirmation")
            Spacer(Modifier.height(24.dp))
            TacitButton("Open Accessibility settings", actions.openAccessibility, Modifier.fillMaxWidth())
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        Overline(if (kind == DisclosureKind.ACCESSIBILITY) "PERMISSIONS · ACCESSIBILITY" else "PERMISSIONS · MICROPHONE")
        Spacer(Modifier.height(12.dp))
        Title(
            if (kind == DisclosureKind.ACCESSIBILITY) "Notice the moment you press play."
            else "Hear the note as it plays."
        )
        Spacer(Modifier.height(16.dp))
        val paras = if (kind == DisclosureKind.ACCESSIBILITY) A11Y_DISCLOSURE else MIC_DISCLOSURE
        paras.forEach { Para(it) }
        Spacer(Modifier.height(24.dp))
        TacitButton(
            if (kind == DisclosureKind.ACCESSIBILITY) "I agree, continue" else "I agree, allow microphone",
            onClick = {
                val now = System.currentTimeMillis()
                if (kind == DisclosureKind.ACCESSIBILITY) {
                    Toggles.setAccessibilityConsent(context, now)
                    guide = true
                } else {
                    Toggles.setMicConsent(context, now)
                    actions.requestMic()
                }
            },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        GhostButton("Not now", onSkip, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(28.dp))
    }
}

private val A11Y_DISCLOSURE = listOf(
    "TACIT uses an Android accessibility service to know when a voice note starts playing.",
    "What it reads. Inside WhatsApp only: the parts of the screen that belong to voice note " +
        "messages. That is the play button you tap, the note's length and time stamp, and the " +
        "sender's name shown on that message. It does not read your typed messages, your other " +
        "chats' text, other apps, or anything you type.",
    "Why it reads this. So TACIT can start reading the exact note you played, and label the " +
        "transcript with the sender and time.",
    "What is kept. The sender's name and the note's time are saved with your transcript on this " +
        "phone, and in your backup if you use TACIT Cloud. Everything else TACIT sees on screen is " +
        "processed in the moment and never stored, collected, or shared.",
    "This service is limited to WhatsApp. TACIT cannot see any other app.",
)

private val MIC_DISCLOSURE = listOf(
    "When you play a voice note, TACIT listens for a few seconds to recognise which note it is.",
    "When it listens. Only after you press play on a voice note, or when you ask TACIT to listen " +
        "again, and never at any other time. While it listens in the background, Android shows its " +
        "microphone indicator and TACIT posts a quiet notice.",
    "What happens to the sound. Recognition uses a short sound signature, not a recording. The " +
        "captured sound is discarded within seconds and never stored or uploaded.",
    "A cleaner way to hear. From Home you can start Precision listening, which lets TACIT hear the " +
        "phone's own audio directly instead of through the microphone. Same privacy, sharper " +
        "hearing. The microphone permission is needed for both.",
)

// ---- W3 / W4 / W7: standard grant pages -------------------------------------------------------

@Composable
private fun GrantPage(step: SetupStep, setup: SetupStatus, actions: SetupActions) {
    val copy = stepCopy(step)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Overline(copy.overline)
        Spacer(Modifier.height(12.dp))
        Title(copy.title)
        Spacer(Modifier.height(16.dp))
        Text(
            copy.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when (step) {
            SetupStep.Files -> TacitButton("Allow file access", actions.requestAllFiles, Modifier.fillMaxWidth())
            SetupStep.Overlay -> TacitButton("Allow the floating card", actions.requestOverlay, Modifier.fillMaxWidth())
            SetupStep.Index -> {
                if (setup.indexBuilding) {
                    ProgressCapsule(setup.indexProgress, setup.indexStatus.ifBlank { "Reading your notes…" })
                } else {
                    TacitButton("Prepare my notes", actions.buildIndex, Modifier.fillMaxWidth())
                }
            }
            else -> {}
        }
        Spacer(Modifier.height(28.dp))
    }
}

private data class StepCopy(val overline: String, val title: String, val body: String)

private fun stepCopy(step: SetupStep): StepCopy = when (step) {
    SetupStep.Files -> StepCopy(
        "PERMISSIONS · FILES",
        "Find your voice notes.",
        "Voice notes live in WhatsApp's media folder. TACIT reads those audio files to recognise " +
            "which note is playing and to build your library. It reads voice notes only.",
    )
    SetupStep.Overlay -> StepCopy(
        "PERMISSIONS · FLOATING CARD",
        "Words, right over the chat.",
        "The card you saw in the demo is drawn over WhatsApp. Android calls this display over other " +
            "apps. TACIT draws only that one card, only while a note is being read.",
    )
    SetupStep.Index -> StepCopy(
        "YOUR LIBRARY",
        "Learn your notes.",
        "TACIT makes a small sound signature of each voice note already on this phone so it can " +
            "tell, in a couple of seconds, exactly which one is playing. Signatures never leave this phone.",
    )
    else -> StepCopy("", "", "")
}

// ---- W8: Done ---------------------------------------------------------------------------------

@Composable
private fun DonePage(setup: SetupStatus, onFinished: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(48.dp))
        DoneBadge()
        Spacer(Modifier.height(24.dp))
        Text(
            "Every voice note, read.",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Open WhatsApp and play any voice note. The words appear on their own, right over the chat.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (setup.modelDownloading) {
            Spacer(Modifier.height(16.dp))
            ProgressCapsule(setup.modelProgress, "The offline pack is still arriving")
        }
        Spacer(Modifier.height(28.dp))
        TacitButton("Take me home", onFinished, Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
    }
}

// ---- small shared bits ------------------------------------------------------------------------

@Composable
private fun Overline(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
    color = MaterialTheme.colorScheme.primary,
)

@Composable
private fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.headlineLarge,
    color = MaterialTheme.colorScheme.onBackground,
)

@Composable
private fun Para(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 12.dp),
)

@Composable
private fun StepLine(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(vertical = 4.dp),
)

@Composable
private fun DoneBadge(size: androidx.compose.ui.unit.Dp = 64.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
