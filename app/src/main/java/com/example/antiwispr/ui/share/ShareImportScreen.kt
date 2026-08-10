package com.example.antiwispr.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.antiwispr.data.StoredTranscript
import com.example.antiwispr.ui.SetupActions
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.ShareImportState
import com.example.antiwispr.ui.components.EngineChooser
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.theme.Dimens

/**
 * The in-between of a shared-audio import (ACTION_SEND): copying, transcribing, the engine fork
 * when nothing can transcribe yet, and failure. Success never renders — Done immediately hands
 * off to the reader via [onDone]. State lives in AppViewModel.shareImport so it survives
 * recomposition and navigation.
 */
@Composable
fun ShareImportScreen(
    state: ShareImportState,
    setup: SetupStatus,
    actions: SetupActions,
    onDone: (StoredTranscript) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    // Every exit funnels through [close] exactly once. Dismissing resets the state to Idle,
    // which recomposes this screen during its own exit transition — without the flag the Idle
    // guard would pop a second back-stack entry.
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (!closing) { closing = true; onClose() }
    }

    // Process-death restore lands here with no import running — bounce back gracefully
    // (same idiom as the reader's null guard).
    if (state is ShareImportState.Idle) {
        LaunchedEffect(Unit) { close() }
        return
    }
    if (state is ShareImportState.Done) {
        LaunchedEffect(state) {
            if (!closing) { closing = true; onDone(state.transcript) }
        }
    }

    val cs = MaterialTheme.colorScheme
    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = ::close) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.screenPad)
            ) {
                when (state) {
                    is ShareImportState.Copying, is ShareImportState.Done -> {
                        Headline("New audio")
                        Spacer(Modifier.height(24.dp))
                        ProgressCapsule(null, "Bringing your audio in…")
                    }
                    is ShareImportState.Transcribing -> {
                        Headline("New audio")
                        Spacer(Modifier.height(8.dp))
                        Text(state.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        ProgressCapsule(null, "Turning it into words…")
                    }
                    is ShareImportState.Processing -> {
                        Headline("New audio")
                        Spacer(Modifier.height(8.dp))
                        Text(state.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        ProgressCapsule(null, "Reading this long note in the cloud…")
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Long notes take a little more time. You can leave this screen; the note lands in your Library and you get a notification when it is ready.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    is ShareImportState.AwaitingEngine -> {
                        // The audio is safely copied; the only missing piece is an engine. Present
                        // the same neutral fork as onboarding/Home and resume the moment one exists
                        // (sign-in completes or the pack finishes downloading).
                        LaunchedEffect(setup.engineReady) { if (setup.engineReady) onRetry() }
                        Headline("Choose how notes become words.")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your audio is saved. Pick one and TACIT reads it right away.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        EngineChooser(setup, actions)
                    }
                    is ShareImportState.Failed -> {
                        Headline("That didn't work")
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        if (state.file != null) {
                            TacitButton("Try again", onRetry, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                        }
                        GhostButton("Not now", ::close, Modifier.fillMaxWidth())
                    }
                    is ShareImportState.Idle -> Unit // handled above
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
}
