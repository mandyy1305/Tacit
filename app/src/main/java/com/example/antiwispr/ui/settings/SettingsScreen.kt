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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antiwispr.BackfillTranscriber
import com.example.antiwispr.Toggles
import com.example.antiwispr.cloud.AskLanguage
import com.example.antiwispr.cloud.CloudAuth
import com.example.antiwispr.cloud.CloudSttLanguage
import com.example.antiwispr.cloud.CloudSttMode
import com.example.antiwispr.ui.AppViewModel
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.ToggleKey
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.OptionPickerSheet
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.overlay.OverlayPreviewDriver
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    var confirmRedownload by remember { mutableStateOf(false) }
    var confirmRedownloadLlm by remember { mutableStateOf(false) }
    var confirmDeleteWhisper by remember { mutableStateOf(false) }
    var confirmDeleteLlm by remember { mutableStateOf(false) }
    var showFolderReport by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showAskLanguageSheet by remember { mutableStateOf(false) }
    var showBackfillSheet by remember { mutableStateOf(false) }
    var devOpen by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = Color.Transparent) { pad ->
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
                ToggleRow(
                    "TACIT active",
                    if (setup.tacitEnabled) "Listening for voice notes when they play in WhatsApp."
                    else "Off — no listening, matching, or overlay. Search and history still work.",
                    initial = setup.tacitEnabled,
                ) { vm.setTacitEnabled(it) }

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Account")
                Spacer(Modifier.height(4.dp))
                when {
                    !setup.cloudConfigured -> Text(
                        "Cloud isn't configured — add google-services.json from the Firebase " +
                            "console to app/src/main/assets and rebuild.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !setup.signedIn -> Column {
                        Text(
                            "Sign in to back up your transcripts and unlock cloud transcription " +
                                "and sharper summaries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        TacitButton("Sign in with Google", onClick = {
                            signInError = null
                            scope.launch {
                                CloudAuth.signIn(context)
                                    .onSuccess { vm.onSignedIn() }
                                    .onFailure { signInError = it.message }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                        signInError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(10.dp))
                        CloudUrlField(setup.cloudBaseUrl) { vm.setCloudBaseUrl(it) }
                    }
                    else -> Column {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    setup.accountEmail ?: "Signed in",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    when {
                                        setup.syncing -> "Syncing…"
                                        setup.lastSyncMs > 0 -> "Synced ${relativeTime(setup.lastSyncMs).lowercase()}"
                                        else -> "Not synced yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GhostButton("Sync now", onClick = { vm.syncNow() }, enabled = !setup.syncing)
                            GhostButton("Sign out", onClick = { vm.signOut() })
                        }
                        ToggleRow(
                            "Cloud transcription",
                            "Sarvam AI via your server — pick output style and language below.",
                            initial = setup.cloudTranscription,
                        ) { vm.setCloudTranscription(it) }
                        AnimatedVisibility(
                            visible = setup.cloudTranscription,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                PickerRow("Output style", setup.sttMode.label) { showModeSheet = true }
                                PickerRow("Spoken language", setup.sttLanguage.label) { showLanguageSheet = true }
                            }
                        }
                        ToggleRow(
                            "Cloud summaries",
                            "gpt-4o-mini via your server — better summaries and action items.",
                            initial = setup.cloudSummaries,
                        ) { vm.setCloudSummaries(it) }
                        CloudUrlField(setup.cloudBaseUrl) { vm.setCloudBaseUrl(it) }
                    }
                }

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Transcription")
                Spacer(Modifier.height(4.dp))
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
                    if (setup.modelReady) {
                        GhostButton("Delete", onClick = { confirmDeleteWhisper = true })
                        GhostButton(
                            "Re-download",
                            onClick = { confirmRedownload = true },
                            enabled = !setup.modelDownloading,
                        )
                    } else {
                        GhostButton(
                            "Download",
                            onClick = { vm.downloadModel() },
                            enabled = !setup.modelDownloading,
                        )
                    }
                }
                if (setup.modelDownloading) {
                    ProgressCapsule(setup.modelProgress, setup.modelStatus)
                    Spacer(Modifier.height(10.dp))
                }
                LinkRow(
                    "Make older notes searchable",
                    if (setup.backfillRunning) "Working through your chosen period…"
                    else "Transcribe a recent period so it appears in search and history.",
                ) { if (!setup.backfillRunning) showBackfillSheet = true }
                if (setup.backfillRunning) {
                    ProgressCapsule(setup.backfillProgress, setup.backfillStatus)
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Cancel", onClick = { vm.cancelBackfill() })
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Summaries")
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Summary model",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (setup.llmReady) "Qwen 2.5 1.5B · on-device · ready"
                            else setup.llmStatus.ifBlank { "not downloaded · 1.6 GB" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (setup.llmReady) {
                        GhostButton("Delete", onClick = { confirmDeleteLlm = true })
                        GhostButton(
                            "Re-download",
                            onClick = { confirmRedownloadLlm = true },
                            enabled = !setup.llmDownloading,
                        )
                    } else {
                        GhostButton(
                            "Download",
                            onClick = { vm.downloadLlm() },
                            enabled = !setup.llmDownloading,
                        )
                    }
                }
                if (setup.llmDownloading) {
                    ProgressCapsule(setup.llmProgress, setup.llmStatus)
                    Spacer(Modifier.height(10.dp))
                }
                PickerRow("Ask answers", setup.askLanguage.label) { showAskLanguageSheet = true }
                Text(
                    "Each note becomes a short brief with action items — generated entirely on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

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

    if (confirmDeleteWhisper) {
        AlertDialog(
            onDismissRequest = { confirmDeleteWhisper = false },
            title = { Text("Delete Whisper model?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Frees ~360 MB. Transcription will use the cloud when you're signed in — " +
                        "or you can re-download the model anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteWhisper = false; vm.deleteWhisperModel() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteWhisper = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (confirmDeleteLlm) {
        AlertDialog(
            onDismissRequest = { confirmDeleteLlm = false },
            title = { Text("Delete summary model?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Frees ~1.6 GB. Summaries will use the cloud when you're signed in — " +
                        "or you can re-download the model anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteLlm = false; vm.deleteLlmModel() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteLlm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (confirmRedownloadLlm) {
        AlertDialog(
            onDismissRequest = { confirmRedownloadLlm = false },
            title = { Text("Re-download summary model?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The current file is deleted and ~1.6 GB is fetched again. " +
                        "Summaries are unavailable until it finishes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRedownloadLlm = false; vm.redownloadLlm() }) {
                    Text("Re-download", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRedownloadLlm = false }) {
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

    if (showAskLanguageSheet) {
        OptionPickerSheet(
            title = "Ask answers",
            options = AskLanguage.entries.toList(),
            selected = setup.askLanguage,
            label = { it.label },
            description = { it.description },
            onSelect = { vm.setAskLanguage(it); showAskLanguageSheet = false },
            onDismiss = { showAskLanguageSheet = false },
        )
    }
    if (showBackfillSheet) {
        // Live counts per preset — file listing + store lookups, so computed off-main.
        val counts by produceState<Map<Int, Int>?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                listOf(7, 30, 90).associateWith { days ->
                    BackfillTranscriber.countCandidates(context, now - days * 86_400_000L)
                }
            }
        }
        OptionPickerSheet(
            title = "Make searchable",
            options = listOf(7, 30, 90),
            selected = -1, // nothing pre-selected; picking a row starts the run
            label = { "Last $it days" },
            description = { days ->
                when (val n = counts?.get(days)) {
                    null -> "counting…"
                    0 -> "nothing new to transcribe"
                    else -> "$n note${if (n == 1) "" else "s"} to transcribe" +
                        if (setup.signedIn && setup.cloudTranscription) " (uses cloud transcription)" else ""
                }
            },
            onSelect = { days ->
                showBackfillSheet = false
                if ((counts?.get(days) ?: 1) > 0) vm.startBackfill(days)
            },
            onDismiss = { showBackfillSheet = false },
        )
    }
    if (showModeSheet) {
        OptionPickerSheet(
            title = "Output style",
            options = CloudSttMode.entries,
            selected = setup.sttMode,
            label = { it.label },
            description = { it.description },
            onSelect = { vm.setSttMode(it); showModeSheet = false },
            onDismiss = { showModeSheet = false },
        )
    }

    if (showLanguageSheet) {
        OptionPickerSheet(
            title = "Spoken language",
            options = CloudSttLanguage.entries,
            selected = setup.sttLanguage,
            label = { it.label },
            onSelect = { vm.setSttLanguage(it); showLanguageSheet = false },
            onDismiss = { showLanguageSheet = false },
        )
    }
}

/** Dev-facing server URL (e.g. http://192.168.1.5:8080 while the Go server runs locally). */
@Composable
private fun CloudUrlField(current: String, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onChange(it) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        label = { Text("Server URL", style = MaterialTheme.typography.labelMedium) },
        placeholder = { Text("http://192.168.1.5:8080", style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
    )
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

/** Tappable row showing the current choice; opens a picker sheet. */
@Composable
private fun PickerRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
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
