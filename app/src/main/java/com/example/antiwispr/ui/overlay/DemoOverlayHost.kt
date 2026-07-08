package com.example.antiwispr.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

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
    LaunchedEffect(replayKey) {
        state = demoInitial()
        delay(1800)
        state = state.copy(phase = OverlayPhase.MATCHED, match = MatchInfo("30 Jun · 0:42"), source = "local")
        delay(1000)
        state = state.copy(phase = OverlayPhase.TRANSCRIBING)
        delay(1300)
        state = state.copy(phase = OverlayPhase.TRANSCRIPT, transcript = DEMO_TRANSCRIPT, summaryState = SummaryState.NONE)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // Dark-locked, like the real overlay; ~90% scale so it reads as a framed preview.
        TacitOverlayTheme {
            Box(Modifier.graphicsLayer { scaleX = 0.9f; scaleY = 0.9f }) {
                TacitOverlayCard(
                    state = state,
                    onClose = {},
                    onShare = {},
                    onCopy = {},
                    onExitFinished = {},
                )
            }
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
