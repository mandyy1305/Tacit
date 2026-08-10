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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.antiwispr.ui.components.LinkRow
import com.example.antiwispr.ui.components.OptionPickerSheet
import com.example.antiwispr.ui.components.PickerRow
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.RowDivider
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.SettingsGroup
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.components.ToggleRow
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.overlay.OverlayPreviewDriver
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Product-language controls over the runtime Toggles, the on-device packs, account, privacy,
 *  and (gated) developer tools. Vendor and model names never appear here; see the copy system.
 *  Layout follows the profile screens of the best consumer apps: a personalized account card
 *  on top, then one rounded card per category with compact glyph-led rows. Account matter
 *  lives on the Account screen (doc 05); this screen keeps only the doorway. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    setup: SetupStatus,
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDeleteWhisper by remember { mutableStateOf(false) }
    var confirmDeleteLlm by remember { mutableStateOf(false) }
    var showFolderReport by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showAskLanguageSheet by remember { mutableStateOf(false) }
    var showBackfillSheet by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showSignInSheet by remember { mutableStateOf(false) }
    var showEmailSheet by remember { mutableStateOf(false) }
    var devOpen by remember { mutableStateOf(false) }

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
                Spacer(Modifier.height(12.dp))

                // The personalized anchor: who you are (or the door to becoming someone).
                if (setup.cloudConfigured) {
                    AccountCard(
                        setup = setup,
                        onOpenAccount = onOpenAccount,
                        onSignIn = { showSignInSheet = true },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Master switch (hero card, doc 06 §2).
                SettingsGroup {
                    ToggleRow(
                        if (setup.tacitEnabled) "TACIT is on." else "TACIT is off.",
                        if (setup.tacitEnabled) "Reading voice notes when they play in WhatsApp."
                        else "Not listening, not matching, no floating card. Your library and search still work.",
                        checked = setup.tacitEnabled,
                        icon = TacitIcons.Power,
                    ) { vm.setTacitEnabled(it) }
                }

                // TRANSCRIPTION
                SettingsSection("Transcription")
                SettingsGroup {
                    PackRow(
                        icon = TacitIcons.Wave,
                        title = "Offline transcription",
                        subtitle = if (setup.modelReady) "On this phone · 360 MB"
                        else setup.modelStatus.ifBlank { "Not downloaded · 360 MB · works without internet" },
                        ready = setup.modelReady,
                        busy = setup.modelDownloading,
                        onDownload = { vm.downloadModel() },
                        onDelete = { confirmDeleteWhisper = true },
                    )
                    if (setup.modelDownloading) {
                        ProgressCapsule(setup.modelProgress, setup.modelStatus)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (setup.cloudConfigured && setup.signedIn) {
                        RowDivider()
                        ToggleRow(
                            "Transcribe with TACIT Cloud",
                            "Sharper accuracy in 23 Indian languages.",
                            checked = setup.cloudTranscription,
                            icon = TacitIcons.Cloud,
                        ) { vm.setCloudTranscription(it) }
                        AnimatedVisibility(
                            visible = setup.cloudTranscription,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(Modifier.padding(start = 32.dp)) {
                                PickerRow("Output style", setup.sttMode.label) { showModeSheet = true }
                                PickerRow("Spoken language", setup.sttLanguage.label) { showLanguageSheet = true }
                            }
                        }
                    }
                    RowDivider()
                    LinkRow(
                        "Catch up on older notes",
                        if (setup.backfillRunning) "Working through your notes…"
                        else "Transcribe a recent period.",
                        icon = TacitIcons.Calendar,
                    ) { if (!setup.backfillRunning) showBackfillSheet = true }
                    if (setup.backfillRunning) {
                        ProgressCapsule(setup.backfillProgress, setup.backfillStatus)
                        GhostButton("Cancel", onClick = { vm.cancelBackfill() })
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // SUMMARIES
                SettingsSection("Summaries")
                SettingsGroup {
                    PackRow(
                        icon = TacitIcons.Summary,
                        title = "Offline summaries",
                        subtitle = if (setup.llmReady) "On this phone · 1.6 GB"
                        else setup.llmStatus.ifBlank { "Not downloaded · 1.6 GB" },
                        ready = setup.llmReady,
                        busy = setup.llmDownloading,
                        onDownload = { vm.downloadLlm() },
                        onDelete = { confirmDeleteLlm = true },
                    )
                    if (setup.llmDownloading) {
                        ProgressCapsule(setup.llmProgress, setup.llmStatus)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (setup.cloudConfigured && setup.signedIn) {
                        RowDivider()
                        ToggleRow(
                            "Summarize with TACIT Cloud",
                            "Sharper briefs and action points. Also powers Ask.",
                            checked = setup.cloudSummaries,
                            icon = TacitIcons.Cloud,
                        ) { vm.setCloudSummaries(it) }
                    }
                    RowDivider()
                    PickerRow("Ask answers", setup.askLanguage.label, icon = TacitIcons.Ask) {
                        showAskLanguageSheet = true
                    }
                }

                // BEHAVIOUR
                SettingsSection("Behaviour")
                SettingsGroup {
                    ToggleRow(
                        "Pause the note once identified",
                        "Read instead of listen.",
                        checked = Toggles.pauseOnMatch,
                        icon = Icons.Filled.PlayArrow,
                    ) { vm.setToggle(ToggleKey.PauseOnMatch, it) }
                    RowDivider()
                    ToggleRow(
                        "Microphone fallback",
                        "When Precision listening is off. Less accurate.",
                        checked = Toggles.micFallbackEnabled,
                        icon = TacitIcons.Mic,
                    ) { vm.setToggle(ToggleKey.MicFallback, it) }
                }

                // PRIVACY
                SettingsSection("Privacy")
                SettingsGroup {
                    LinkRow(
                        "What leaves this phone",
                        "",
                        icon = TacitIcons.Lock,
                    ) { showPrivacy = true }
                    RowDivider()
                    LinkRow(
                        "How TACIT reads WhatsApp",
                        "",
                        icon = TacitIcons.ScreenShare,
                    ) { showDisclosure = true }
                }

                // ABOUT
                SettingsSection("About")
                SettingsGroup {
                    LinkRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = Icons.Filled.Info) {
                        val now = System.currentTimeMillis()
                        versionTaps = if (now - lastTapMs < 3000) versionTaps + 1 else 1
                        lastTapMs = now
                        if (!devUnlocked && versionTaps >= 7) {
                            devUnlocked = true
                            Toast.makeText(context, "Developer options unlocked.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    RowDivider()
                    LinkRow("Rate TACIT", "Takes a minute, means a lot.", icon = Icons.Filled.Star) {
                        val pkg = context.packageName
                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(market) } catch (_: Exception) {
                            try { context.startActivity(web) } catch (_: Exception) {}
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
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
                        SettingsGroup {
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

    if (confirmDeleteWhisper) {
        PackDeleteDialog(
            title = "Delete the offline transcription pack?",
            body = "Frees 360 MB. With Pro, transcription continues through TACIT Cloud. " +
                "Without it, transcription stops until you download it again.",
            onDelete = { confirmDeleteWhisper = false; vm.deleteWhisperModel() },
            onRedownload = { confirmDeleteWhisper = false; vm.redownloadModel() },
            onDismiss = { confirmDeleteWhisper = false },
        )
    }

    if (confirmDeleteLlm) {
        PackDeleteDialog(
            title = "Delete the offline summaries pack?",
            body = "Frees 1.6 GB. With Pro, summaries continue through TACIT Cloud. " +
                "Without it, summaries stop until you download it again.",
            onDelete = { confirmDeleteLlm = false; vm.deleteLlmModel() },
            onRedownload = { confirmDeleteLlm = false; vm.redownloadLlm() },
            onDismiss = { confirmDeleteLlm = false },
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

    if (showPrivacy) {
        ModalBottomSheet(
            onDismissRequest = { showPrivacy = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.screenPad)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "What leaves this phone",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                PrivacyRow("Transcription (offline pack)", "Nothing leaves. Audio and words stay here.")
                PrivacyRow(
                    "TACIT Cloud transcription",
                    "The voice note file is sent securely, transcribed, and discarded. The transcript is stored in your backup.",
                )
                PrivacyRow("Summaries and Ask (offline)", "Nothing leaves.")
                PrivacyRow(
                    "TACIT Cloud summaries and Ask",
                    "The transcript text (not audio) is sent securely to generate the brief or answer.",
                )
                PrivacyRow(
                    "Backup",
                    "When signed in, transcripts sync to your account so any signed-in phone can read them.",
                )
                PrivacyRow(
                    "Play detection",
                    "TACIT reads the voice note bubble in WhatsApp on this screen only. Never uploaded.",
                )
                PrivacyRow(
                    "Listening (mic or Precision)",
                    "A few seconds of audio are matched on this phone and immediately discarded.",
                )
            }
        }
    }

    if (showSignInSheet) {
        SignInSheet(
            vm = vm,
            onUseEmail = { showSignInSheet = false; showEmailSheet = true },
            onDismiss = { showSignInSheet = false },
        )
    }

    if (showEmailSheet) {
        EmailSignInSheet(
            onSuccess = { vm.onSignedIn(); showEmailSheet = false },
            onDismiss = { showEmailSheet = false },
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

/** Section overline sitting between cards. */
@Composable
private fun SettingsSection(title: String) {
    Spacer(Modifier.height(Dimens.sectionGap - 8.dp))
    SectionHeader(title)
    Spacer(Modifier.height(8.dp))
}

/**
 * The account anchor at the top of Settings. Signed out it is the door to signing in (the
 * buttons live in a sheet, keeping the page quiet); signed in it is the doorway to the
 * Account screen.
 */
@Composable
private fun AccountCard(setup: SetupStatus, onOpenAccount: () -> Unit, onSignIn: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsGroup {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { if (setup.signedIn) onOpenAccount() else onSignIn() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (setup.signedIn) cs.primaryContainer else cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                val initial = (setup.accountName ?: setup.accountEmail)
                    ?.trim()?.firstOrNull()?.uppercaseChar()
                    ?.takeIf { setup.signedIn }
                if (initial != null) {
                    Text(initial.toString(), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                } else {
                    Icon(
                        Icons.Filled.Person, contentDescription = null,
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (setup.signedIn) {
                    val email = setup.accountEmail.orEmpty()
                    Text(
                        setup.accountName ?: email.substringBefore('@').ifBlank { "Signed in" },
                        style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                    )
                    Text(
                        when {
                            setup.syncing -> "Backing up…"
                            setup.lastSyncMs > 0 -> "Backed up ${relativeTime(setup.lastSyncMs).lowercase()}"
                            else -> "Not backed up yet"
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                } else {
                    Text("Back up your library", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(
                        "Sign in for TACIT Cloud: sharper accuracy, backup and Ask.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = cs.outline,
            )
        }
    }
}

/** The sign-in fork, off the main page so Settings stays a list, not a landing page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInSheet(vm: AppViewModel, onUseEmail: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = cs.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPad)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text("Back up your library.", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "Sign in to keep transcripts safe and use TACIT Cloud: sharper accuracy, " +
                    "23 Indian languages, cloud summaries and Ask.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            TacitButton(
                "Continue with Google",
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        CloudAuth.signIn(context)
                            .onSuccess { vm.onSignedIn(); onDismiss() }
                            .onFailure { error = CloudAuth.friendlyGoogleError(it) }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
            Spacer(Modifier.height(4.dp))
            GhostButton(
                "Use email instead",
                onClick = onUseEmail,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !busy,
            )
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
    }
}

/** A pack-manager row: glyph, name, state line, one quiet action (doc 06 §4: re-download
 *  moves inside the delete dialog so the ready state carries a single button). */
@Composable
private fun PackRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ready: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ready) GhostButton("Delete", onClick = onDelete)
        else GhostButton("Download", onClick = onDownload, enabled = !busy)
    }
}

/** Delete confirm for a pack; offers re-download in place (doc 08). */
@Composable
private fun PackDeleteDialog(
    title: String,
    body: String,
    onDelete: () -> Unit,
    onRedownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRedownload) {
                    Text("Re-download", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
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

/** One feature in the "What leaves this phone" table: name, then the data-flow sentence. */
@Composable
private fun PrivacyRow(feature: String, sends: String) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(feature, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(sends, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
