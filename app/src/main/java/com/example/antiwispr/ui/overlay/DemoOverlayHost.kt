package com.example.antiwispr.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Inline, windowless replay of the overlay card for the onboarding Welcome demo and the Home
 * "Watch the demo again" action. It drives a scripted [OverlayUiState] sequence straight into
 * [TacitOverlayCard] with no system window and no permission (doc 01 W1 / doc 02 §6): listening,
 * amber match flash, transcribing shimmer, then the demo transcript fades in. Change [replayKey]
 * to restart the script. All callbacks are no-ops; nothing here touches the real pipeline.
 */
@Composable
fun DemoOverlayHost(modifier: Modifier = Modifier, replayKey: Int = 0) {
    var state by remember { mutableStateOf(demoInitial()) }
    val demoLevels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(replayKey) {
        state = demoInitial()
        delay(1800)
        state = state.copy(phase = OverlayPhase.MATCHED, match = MatchInfo("30 Jun · 0:42"), source = "local")
        delay(1000)
        state = state.copy(phase = OverlayPhase.TRANSCRIBING)
        delay(1300)
        state = state.copy(phase = OverlayPhase.TRANSCRIPT, transcript = DEMO_TRANSCRIPT, summaryState = SummaryState.NONE)
    }
    // Synthetic, speech-like levels so the demo waveform reacts like the real one while listening.
    LaunchedEffect(replayKey) {
        demoLevels.clear()
        var i = 0
        while (state.phase == OverlayPhase.LISTENING) {
            // Raw RMS-scale synthetic voice (syllabic); the overlay conditions it into the bloom.
            val tt = i * 0.09f
            val s1 = maxOf(0f, sin(tt * 6.5f))
            val s2 = maxOf(0f, sin(tt * 3.7f + 1f))
            val syll = s1 * s1 * 0.6f + s2 * s2 * s2 * 0.4f
            demoLevels.add((0.004f + 0.09f * syll).coerceIn(0f, 1f))  // real mic RMS scale
            while (demoLevels.size > 48) demoLevels.removeAt(0)
            i++
            delay(90)
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // Dark-locked, like the real overlay; rendered inline with no window and no coach lanes
        // (those transparent tooltip lanes only make sense over WhatsApp, not in this framed demo).
        TacitOverlayTheme {
            TacitOverlayCard(
                state = state,
                audioLevels = demoLevels,
                coachRoom = false,
                onClose = {},
                onShare = {},
                onCopy = {},
                onExitFinished = {},
            )
        }
    }
}

private fun demoInitial() = OverlayUiState(
    visible = true,
    phase = OverlayPhase.LISTENING,
    statusLine = "Listening…",
)

private const val DEMO_TRANSCRIPT =
    "Haan bhai, kal milte hain office ke baad. I'll bring the documents you asked for: " +
        "the lease agreement and both ID proofs. Agar time mile toh banker ko call kar lena " +
        "before five."
