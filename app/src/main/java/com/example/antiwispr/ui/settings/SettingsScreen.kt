package com.example.antiwispr.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antiwispr.Toggles
import com.example.antiwispr.ui.AppViewModel
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.ToggleKey
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.overlay.OverlayPreviewDriver
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.MonoStyle

/** Human-readable controls over the runtime Toggles + model management + Developer tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    setup: SetupStatus,
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    var confirmRedownload by remember { mutableStateOf(false) }
    var showFolderReport by remember { mutableStateOf(false) }
    var devOpen by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(Modifier.padding(horizontal = Dimens.screenPad)) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Transcription")
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    "Force Hindi",
                    "Best for Hindi-dominant chats. Off = auto-detect the language.",
                    initial = Toggles.forceHindi,
                ) { vm.setToggle(ToggleKey.ForceHindi, it) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Whisper model",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (setup.modelReady) "whisper-small · on-device · ready"
                            else setup.modelStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GhostButton(
                        "Re-download",
                        onClick = { confirmRedownload = true },
                        enabled = !setup.modelDownloading,
                    )
                }
                if (setup.modelDownloading) {
                    ProgressCapsule(setup.modelProgress, setup.modelStatus)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Behaviour")
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    "Transcribe automatically",
                    "Listen, match and transcribe whenever a voice note plays.",
                    initial = Toggles.orchestrationEnabled,
                ) { vm.setToggle(ToggleKey.Orchestration, it) }
                ToggleRow(
                    "Pause the note once identified",
                    "Stops playback the moment TACIT recognises the note.",
                    initial = Toggles.pauseOnMatch,
                ) { vm.setToggle(ToggleKey.PauseOnMatch, it) }
                ToggleRow(
                    "Microphone fallback",
                    "Listen through the mic when screen share is off. Lower accuracy.",
                    initial = Toggles.micFallbackEnabled,
                ) { vm.setToggle(ToggleKey.MicFallback, it) }

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                InkDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { devOpen = !devOpen }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader("Developer", Modifier.weight(1f))
                    val rot by animateFloatAsState(if (devOpen) 180f else 0f, label = "chevron")
                    Icon(
                        Icons.Filled.KeyboardArrowDown, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(rot),
                    )
                }
                AnimatedVisibility(
                    visible = devOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        ToggleRow(
                            "Diagnostic mode",
                            "Dump WhatsApp's node tree on taps; show raw match details in the overlay.",
                            initial = Toggles.diagnosticMode,
                        ) { vm.setToggle(ToggleKey.DiagnosticMode, it) }
                        ToggleRow(
                            "Pause on play tap",
                            "Click the control again right after a play tap (isolation test).",
                            initial = Toggles.pauseOnPlay,
                        ) { vm.setToggle(ToggleKey.PauseOnPlay, it) }

                        LinkRow("Preview overlay card", "Cycles fake listening → match → transcript over this screen.") {
                            OverlayPreviewDriver.run(context)
                        }
                        LinkRow("Voice Notes folder report", "Where TACIT looks for .opus files on this device.") {
                            showFolderReport = true
                        }
                        LinkRow(
                            "Rebuild index",
                            if (setup.indexBuilding) "Building…" else setup.indexStatus,
                        ) { vm.buildIndex() }
                        if (setup.indexBuilding) {
                            ProgressCapsule(setup.indexProgress, setup.indexStatus)
                            Spacer(Modifier.height(10.dp))
                        }
                        LinkRow("Open log", setup.detection) { onOpenLog() }
                    }
                }

                Spacer(Modifier.height(Dimens.sectionGap))
                Text(
                    "TACIT 1.0  ·  whisper-small  ·  sherpa-onnx  ·  fully on-device",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            title = { Text("Re-download model?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The current files are deleted and ~360 MB is fetched again. " +
                        "Transcription is unavailable until it finishes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRedownload = false; vm.redownloadModel() }) {
                    Text("Re-download", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRedownload = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (showFolderReport) {
        ModalBottomSheet(
            onDismissRequest = { showFolderReport = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier
                    .padding(horizontal = Dimens.screenPad)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Voice Notes folders",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    remember { vm.folderReport() },
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { checked = it; onChange(it) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}
