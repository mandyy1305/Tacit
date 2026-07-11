package com.example.antiwispr.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import com.example.antiwispr.transcribe.BackfillTranscriber
import com.example.antiwispr.BuildConfig
import com.example.antiwispr.core.Toggles
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

/** Product-language controls over the runtime Toggles, the on-device packs, account, privacy,
 *  and (gated) developer tools. Vendor and model names never appear here; see the copy system. */
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
    var showDisclosure by remember { mutableStateOf(false) }
    var devOpen by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    // Developer options: always available in debug; in release only after the 7-tap unlock.
    var devUnlocked by remember { mutableStateOf(BuildConfig.DEBUG) }
    var versionTaps by remember { mutableStateOf(0) }
    var lastTapMs by remember { mutableStateOf(0L) }

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
                // Master switch (hero row).
                ToggleRow(
                    if (setup.tacitEnabled) "TACIT is on." else "TACIT is off.",
                    if (setup.tacitEnabled) "Reading voice notes when they play in WhatsApp."
                    else "Not listening, not matching, no floating card. Your library and search still work.",
                    checked = setup.tacitEnabled,
                ) { vm.setTacitEnabled(it) }

                // ACCOUNT (hidden entirely if the build genuinely lacks cloud configuration).
                if (setup.cloudConfigured) {
                    Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                    SectionHeader("Account")
                    Spacer(Modifier.height(4.dp))
                    if (!setup.signedIn) {
                        Column {
                            Text(
                                "Sign in with Google to keep your transcripts backed up and use " +
                                    "TACIT Cloud: sharper accuracy, 23 Indian languages, cloud summaries and Ask.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            TacitButton("Continue with Google", onClick = {
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
                        }
                    } else {
                        Column {
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
                                            setup.syncing -> "Backing up…"
                                            setup.lastSyncMs > 0 -> "Backed up ${relativeTime(setup.lastSyncMs).lowercase()}"
                                            else -> "Not backed up yet"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                GhostButton("Sync now", onClick = { vm.syncNow() }, enabled = !setup.syncing)
                                GhostButton("Sign out", onClick = { vm.signOut() })
                            }
                            ToggleRow(
                                "Transcribe with TACIT Cloud",
                                "Sharper accuracy in 23 Indian languages. Notes are sent securely, transcribed, then discarded.",
                                checked = setup.cloudTranscription,
                            ) { vm.setCloudTranscription(it) }
                            AnimatedVisibility(
                                visible = setup.cloudTranscription,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column {
                                    PickerRow("Output style", setup.sttMode.label) { showModeSheet = true }
                                    PickerRow("Spoken language", setup.sttLanguage.label) { showLanguageSheet = true }
                                    Text(
                                        "Applies to TACIT Cloud transcription.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            ToggleRow(
                                "Summarize with TACIT Cloud",
                                "Sharper briefs and action points. Also powers Ask.",
                                checked = setup.cloudSummaries,
                            ) { vm.setCloudSummaries(it) }
                        }
                    }
                }

                // TRANSCRIPTION
                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Transcription")
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Offline transcription",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (setup.modelReady) "On this phone · 360 MB · works without internet"
                            else setup.modelStatus.ifBlank { "Not downloaded · 360 MB · works without internet" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (setup.modelReady) {
                        GhostButton("Delete", onClick = { confirmDeleteWhisper = true })
                        GhostButton("Re-download", onClick = { confirmRedownload = true }, enabled = !setup.modelDownloading)
                    } else {
                        GhostButton("Download", onClick = { vm.downloadModel() }, enabled = !setup.modelDownloading)
                    }
                }
                if (setup.modelDownloading) {
                    ProgressCapsule(setup.modelProgress, setup.modelStatus)
                    Spacer(Modifier.height(10.dp))
                }
                LinkRow(
                    "Catch up on older notes",
                    if (setup.backfillRunning) "Working through your notes…"
                    else "Transcribe a recent period so it shows in search and your library.",
                ) { if (!setup.backfillRunning) showBackfillSheet = true }
                if (setup.backfillRunning) {
                    ProgressCapsule(setup.backfillProgress, setup.backfillStatus)
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Cancel", onClick = { vm.cancelBackfill() })
                    Spacer(Modifier.height(10.dp))
                }

                // SUMMARIES
                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Summaries")
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Offline summaries",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (setup.llmReady) "On this phone · 1.6 GB"
                            else setup.llmStatus.ifBlank { "Not downloaded · 1.6 GB" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (setup.llmReady) {
                        GhostButton("Delete", onClick = { confirmDeleteLlm = true })
                        GhostButton("Re-download", onClick = { confirmRedownloadLlm = true }, enabled = !setup.llmDownloading)
                    } else {
                        GhostButton("Download", onClick = { vm.downloadLlm() }, enabled = !setup.llmDownloading)
                    }
                }
                if (setup.llmDownloading) {
                    ProgressCapsule(setup.llmProgress, setup.llmStatus)
                    Spacer(Modifier.height(10.dp))
                }
                PickerRow("Ask answers", setup.askLanguage.label) { showAskLanguageSheet = true }
                Text(
                    "Each note becomes a short brief with action points.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                // BEHAVIOUR
                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Behaviour")
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    "Pause the note once identified",
                    "Pauses WhatsApp playback so you can read instead of listen.",
                    checked = Toggles.pauseOnMatch,
                ) { vm.setToggle(ToggleKey.PauseOnMatch, it) }
                ToggleRow(
                    "Microphone fallback",
                    "When Precision listening is off, listen through the mic. Less accurate.",
                    checked = Toggles.micFallbackEnabled,
                ) { vm.setToggle(ToggleKey.MicFallback, it) }

                // PRIVACY
                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Privacy")
                Spacer(Modifier.height(4.dp))
                LinkRow(
                    "How TACIT reads WhatsApp",
                    "The accessibility connection, explained.",
                ) { showDisclosure = true }

                // ABOUT
                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("About")
                Spacer(Modifier.height(4.dp))
                LinkRow("Version", "1.0 (1)") {
                    val now = System.currentTimeMillis()
                    versionTaps = if (now - lastTapMs < 3000) versionTaps + 1 else 1
                    lastTapMs = now
                    if (!devUnlocked && versionTaps >= 7) {
                        devUnlocked = true
                        Toast.makeText(context, "Developer options unlocked.", Toast.LENGTH_SHORT).show()
                    }
                }
                LinkRow("Rate TACIT", "Takes a minute, means a lot.") {
                    val pkg = context.packageName
                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(market) } catch (_: Exception) {
                        try { context.startActivity(web) } catch (_: Exception) {}
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "TACIT · Every voice note, read.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )

                // DEVELOPER (gated)
                if (devUnlocked) {
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
                                checked = Toggles.diagnosticMode,
                            ) { vm.setToggle(ToggleKey.DiagnosticMode, it) }
                            ToggleRow(
                                "Pause on play tap",
                                "Click the control again right after a play tap (isolation test).",
                                checked = Toggles.pauseOnPlay,
                            ) { vm.setToggle(ToggleKey.PauseOnPlay, it) }
                            LinkRow("Preview overlay card", "Cycles fake listening, match and transcript over this screen.") {
                                OverlayPreviewDriver.run(context)
                            }
                            LinkRow("Voice notes folder report", "Where TACIT looks for voice notes on this device.") {
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
                            if (BuildConfig.DEBUG) {
                                CloudUrlField(setup.cloudBaseUrl) { vm.setCloudBaseUrl(it) }
                                Text(
                                    "Debug builds only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            LinkRow("Open log", setup.detection) { onOpenLog() }
                            LinkRow("Hide developer options", "") { devUnlocked = false; devOpen = false }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            title = { Text("Re-download the offline transcription pack?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The current files are removed and 360 MB is fetched again. " +
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
            title = { Text("Delete the offline transcription pack?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Frees 360 MB. With Pro, transcription continues through TACIT Cloud. " +
                        "Without it, transcription stops until you download it again.",
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
            title = { Text("Delete the offline summaries pack?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Frees 1.6 GB. With Pro, summaries continue through TACIT Cloud. " +
                        "Without it, summaries stop until you download it again.",
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
            title = { Text("Re-download the offline summaries pack?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The current file is removed and 1.6 GB is fetched again. " +
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

    if (showDisclosure) {
        ModalBottomSheet(
            onDismissRequest = { showDisclosure = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.screenPad)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "How TACIT reads WhatsApp",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                DisclosurePara("TACIT uses an Android accessibility service to know when a voice note starts playing.")
                DisclosurePara(
                    "What it reads. Inside WhatsApp only: the parts of the screen that belong to voice " +
                        "note messages. That is the play button you tap, the note's length and time stamp, " +
                        "and the sender's name shown on that message. It does not read your typed messages, " +
                        "your other chats' text, other apps, or anything you type."
                )
                DisclosurePara(
                    "Why it reads this. So TACIT can start reading the exact note you played, and label " +
                        "the transcript with the sender and time."
                )
                DisclosurePara(
                    "What is kept. The sender's name and the note's time are saved with your transcript on " +
                        "this phone, and in your backup if you use TACIT Cloud. Everything else TACIT sees on " +
                        "screen is processed in the moment and never stored, collected, or shared."
                )
            }
        }
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
                    "Voice note folders",
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
            title = "Catch up on older notes",
            options = listOf(7, 30, 90),
            selected = -1, // nothing pre-selected; picking a row starts the run
            label = { "Last $it days" },
            description = { days ->
                when (val n = counts?.get(days)) {
                    null -> "counting…"
                    0 -> "nothing new to transcribe"
                    else -> "$n note${if (n == 1) "" else "s"} to catch up" +
                        if (setup.signedIn && setup.cloudTranscription) " · uses TACIT Cloud" else ""
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

@Composable
private fun DisclosurePara(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/** Server URL (developer, debug builds only) while the tacit-cloud server runs locally. */
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

/** Stateless controlled toggle row: the caller owns the value, so the switch never drifts from
 *  its backing state (re-keyed on [checked]). */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var local by remember(checked) { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = local,
            onCheckedChange = { local = it; onChange(it) },
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}
