package com.example.antiwispr.ui.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.summarize.ActionEntity
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.chains.ChainOverrides
import com.example.antiwispr.chains.ChainSummaries
import com.example.antiwispr.summarize.ChainSummarizer
import com.example.antiwispr.chains.Chains
import com.example.antiwispr.summarize.EntityExtractor
import com.example.antiwispr.summarize.EntityLauncher
import com.example.antiwispr.data.StoredTranscript
import com.example.antiwispr.summarize.Summarizer
import com.example.antiwispr.transcribe.TranscribeRouter
import com.example.antiwispr.data.Transcripts
import com.example.antiwispr.cloud.SyncEngine
import com.example.antiwispr.summarize.parseSummary
import java.io.File
import com.example.antiwispr.ui.components.EntityChipsRow
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.durationLabel
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.components.waDateLabel
import com.example.antiwispr.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reading view with two tabs: Summary (LLM summary + action items, generated on-device,
 * on-demand for notes that don't have one yet) and Transcript (the full text).
 */
@Composable
fun TranscriptDetailScreen(
    transcript: StoredTranscript?,
    onBack: () -> Unit,
    onDelete: (StoredTranscript) -> Unit = {},
    onRetranscribe: (StoredTranscript) -> Unit = {},
    retranscribing: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onOpenNote: (StoredTranscript) -> Unit = {},
    onChainChanged: () -> Unit = {},
) {
    // Process-death restore (or a just-deleted transcript) lands here with no
    // selection — bounce back gracefully.
    if (transcript == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var tab by rememberSaveable(transcript.key) { mutableIntStateOf(0) } // 0 = Transcript, 1 = Summary
    // Keyed on updatedAt too: a re-transcription keeps the key (path|mtime|size) but swaps the
    // record — the summary state must reset with it.
    var summaryRaw by remember(transcript.key, transcript.updatedAt) { mutableStateOf(transcript.summary) }
    var generating by remember(transcript.key, transcript.updatedAt) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    // Re-transcribe needs the original audio; a record synced from another phone may not have it.
    val audioAvailable = remember(transcript.key) { File(transcript.path).exists() }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }

    val canSummarize = remember { Summarizer.canSummarize(context) } // local model OR cloud

    // Bumped when the user detaches / re-attaches or transcribes chain members, so state recomputes.
    var chainEdits by remember(transcript.key) { mutableIntStateOf(0) }
    // Chain membership: the overlay-detected burst (includes notes not transcribed yet, so we can
    // offer "Transcribe all") when available, else the store-derived cluster. null = lone/detached.
    val chain = remember(transcript.key, chainEdits) {
        runCatching { Chains.chainForNote(context, transcript) }.getOrNull()
    }
    // Each member paired with its stored transcript (null = not transcribed yet).
    val chainMembers = remember(transcript.key, chainEdits) {
        chain?.files?.map { it to Transcripts.get(context).entry(it) } ?: emptyList()
    }
    val detached = remember(transcript.key, chainEdits) {
        ChainOverrides.get(context).isDetached(ChainOverrides.idOf(transcript.waDate, transcript.seq))
    }
    // Whole-chain toggle: OFF = this note only; ON = both tabs cover the whole chain.
    var wholeChain by rememberSaveable(transcript.key) { mutableStateOf(false) }
    val chainTranscript = remember(chain?.id, chainEdits) {
        chainMembers.mapIndexed { i, (_, rec) ->
            "[Note ${i + 1}]\n" + (rec?.text?.ifBlank { null } ?: "[not transcribed yet, tap Transcribe all]")
        }.joinToString("\n\n")
    }
    var chainRaw by remember(transcript.key, chainEdits) {
        mutableStateOf(chain?.let { ChainSummaries.get(context).find(it.id)?.raw }.orEmpty())
    }
    var chainGenerating by remember(transcript.key) { mutableStateOf(false) }
    var transcribingChain by remember(transcript.key) { mutableStateOf(false) }
    val chainScope = rememberCoroutineScope()
    fun transcribeChain() {
        val c = chain ?: return
        transcribingChain = true
        chainScope.launch(Dispatchers.IO) {
            for (f in c.files) {
                if (Transcripts.get(context).entry(f) == null && f.exists()) {
                    runCatching { TranscribeRouter.transcribe(context, f, c.chatName) }
                }
            }
            withContext(Dispatchers.Main) {
                transcribingChain = false
                chainEdits++      // refresh member list + whole-chain transcript
                onChainChanged()  // refresh badges/history
            }
        }
    }
    fun generate() {
        generating = true
        Summarizer.request(context, transcript.key, transcript.text) { raw ->
            if (!raw.startsWith("[")) {
                Transcripts.get(context).putSummary(transcript.key, raw)
                SyncEngine.requestSync(context)
            }
            summaryRaw = raw
            generating = false
        }
    }
    // Lazy: generate only when the user actually opens the Summary tab (first time). Also keyed
    // on updatedAt so a re-transcription that lands while the Summary tab is open regenerates.
    LaunchedEffect(tab, transcript.updatedAt) {
        if (tab == 1 && summaryRaw.isEmpty() && canSummarize && !generating) generate()
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (audioAvailable) {
                    IconButton(onClick = { confirmRetranscribe = true }, enabled = !retranscribing) {
                        if (retranscribing) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh, contentDescription = "Re-transcribe",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = "Delete transcript",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.screenPad)
            ) {
                // Title is the chat name — people remember who, not which date.
                Text(
                    transcript.chatName.ifBlank { "Voice note" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                // Meta line 1: date · duration (duration renders once the pipeline persists it).
                val dateLabel = waDateLabel(transcript.waDate)
                val durLabel = if (transcript.durationSec > 0) durationLabel(transcript.durationSec) else null
                Text(
                    listOfNotNull(dateLabel, durLabel).joinToString("  ·  ").ifBlank { "Voice note" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Meta line 2: provenance, source-aware.
                val engine = when (transcript.source) {
                    "cloud" -> " with TACIT Cloud"
                    "local" -> " on this phone"
                    else -> ""
                }
                Text(
                    "Transcribed ${relativeTime(transcript.updatedAt).lowercase()}$engine",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audioAvailable) {
                    Spacer(Modifier.height(12.dp))
                    AudioPlayerRow(transcript.path, transcript.key)
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This note's audio isn't on this phone. The words are saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (chain != null) {
                    Spacer(Modifier.height(14.dp))
                    ChainCard(
                        members = chainMembers,
                        currentKey = transcript.key,
                        chatName = chain.chatName ?: transcript.chatName.ifEmpty { null },
                        wholeChain = wholeChain,
                        transcribing = transcribingChain,
                        onToggle = { wholeChain = it },
                        onOpen = onOpenNote,
                        onTranscribeAll = { transcribeChain() },
                        onRemove = {
                            ChainOverrides.get(context)
                                .detach(ChainOverrides.idOf(transcript.waDate, transcript.seq))
                            wholeChain = false
                            chainEdits++
                            onChainChanged()
                        },
                    )
                } else if (detached) {
                    Spacer(Modifier.height(14.dp))
                    GhostButton("Add this note back to its chain", onClick = {
                        ChainOverrides.get(context)
                            .reattach(ChainOverrides.idOf(transcript.waDate, transcript.seq))
                        chainEdits++
                        onChainChanged()
                    })
                }
                Spacer(Modifier.height(14.dp))
                ReaderTabs(tab, onSelect = { tab = it })
                Spacer(Modifier.height(14.dp))
                InkDivider()
                Spacer(Modifier.height(18.dp))

                AnimatedContent(
                    targetState = tab to (wholeChain && chain != null),
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) using null },
                    label = "readerTab",
                ) { (t, whole) ->
                    if (t == 0) {
                        SelectionContainer {
                            Text(
                                if (whole) chainTranscript else transcript.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    } else if (whole && chain != null) {
                        ChainGist(
                            raw = chainRaw,
                            generating = chainGenerating,
                            canGenerate = canSummarize,
                            count = chain.size,
                            onGenerate = {
                                chainGenerating = true
                                ChainSummarizer.request(context, chain, chain.chatName) { result ->
                                    chainRaw = result
                                    chainGenerating = false
                                }
                            },
                        )
                    } else {
                        SummaryTab(
                            summaryRaw = summaryRaw,
                            generating = generating,
                            llmReady = canSummarize,
                            onRegenerate = { generate() },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }

            val activeText = when {
                wholeChain && chain != null && tab == 0 -> chainTranscript
                wholeChain && chain != null && tab == 1 && chainRaw.isNotEmpty() && !chainRaw.startsWith("[") -> chainRaw
                tab == 1 && summaryRaw.isNotEmpty() && !summaryRaw.startsWith("[") -> summaryRaw
                else -> transcript.text
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPad, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TacitButton(
                    if (copied) "Copied ✓" else "Copy",
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("TACIT note", activeText))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Share", onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, activeText)
                    }
                    context.startActivity(Intent.createChooser(send, "Share"))
                })
            }
        }
    }

    if (confirmRetranscribe) {
        AlertDialog(
            onDismissRequest = { confirmRetranscribe = false },
            title = { Text("Transcribe this note again?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The saved words and summary are replaced using your current settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetranscribe = false
                    onRetranscribe(transcript)
                }) {
                    Text("Re-transcribe", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetranscribe = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transcript?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "The saved text is removed. The voice note itself is untouched. " +
                        "Play it again in WhatsApp and TACIT transcribes it fresh.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(transcript) // clears the selection; the null guard pops back
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

/** The chain this note belongs to: a header + a "whole chain" toggle, the linked notes (each
 *  tappable to open it; un-transcribed ones marked), a "Transcribe all" action for notes not yet
 *  transcribed, and a control to detach this note from the chain. */
@Composable
private fun ChainCard(
    members: List<Pair<File, StoredTranscript?>>,
    currentKey: String,
    chatName: String?,
    wholeChain: Boolean,
    transcribing: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: (StoredTranscript) -> Unit,
    onTranscribeAll: () -> Unit,
    onRemove: () -> Unit,
) {
    val untranscribed = members.count { it.second == null }
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append("CHAIN · ${members.size} NOTES")
                        if (!chatName.isNullOrBlank()) append(" · ${chatName.uppercase()}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Whole chain",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(checked = wholeChain, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            members.forEachIndexed { i, (_, rec) ->
                val isCurrent = rec?.key == currentKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = rec != null && !isCurrent) { rec?.let(onOpen) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        rec?.text?.take(90)?.ifBlank { "(no transcript)" } ?: "Not transcribed yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            rec == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            isCurrent -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "THIS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (transcribing) {
                ProgressCapsule(null, "Transcribing $untranscribed note${if (untranscribed == 1) "" else "s"}…")
                Spacer(Modifier.height(8.dp))
            } else if (untranscribed > 0) {
                GhostButton("Transcribe all ${members.size} notes", onTranscribeAll)
                Spacer(Modifier.height(8.dp))
            }
            GhostButton("Remove this note from the chain", onRemove)
        }
    }
}

/** The whole-chain gist (cached, generating, error, or a generate button) — shown on the Summary
 *  tab when the chain toggle is on. Mirrors [SummaryTab]'s styling. */
@Composable
private fun ChainGist(
    raw: String,
    generating: Boolean,
    canGenerate: Boolean,
    count: Int,
    onGenerate: () -> Unit,
) {
    when {
        generating -> ProgressCapsule(null, "Summarizing $count notes…")
        raw.startsWith("[") -> Column {
            Text(
                raw.trim('[', ']'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GhostButton("Try again", onGenerate, enabled = canGenerate)
        }
        raw.isNotEmpty() -> {
            val context = LocalContext.current
            val parts = remember(raw) { parseSummary(raw) }
            val entities by produceState(emptyList<ActionEntity>(), raw) {
                value = withContext(Dispatchers.IO) { EntityExtractor.extract(context, parts) }
            }
            Column {
                SelectionContainer {
                    Text(
                        parts.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (parts.actions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader("Actions")
                    Spacer(Modifier.height(8.dp))
                    parts.actions.forEach { action ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("–", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            SelectionContainer {
                                Text(action, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                    color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }
                }
                if (entities.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    EntityChipsRow(entities, onTap = { EntityLauncher.launch(context, it) })
                }
                Spacer(Modifier.height(12.dp))
                GhostButton("Regenerate", onGenerate, enabled = canGenerate)
            }
        }
        else -> GhostButton("Summarize the chain ($count notes)", onGenerate, enabled = canGenerate)
    }
}

@Composable
private fun ReaderTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
    ) {
        listOf("Transcript", "Summary").forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(
    summaryRaw: String,
    generating: Boolean,
    llmReady: Boolean,
    onRegenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        generating -> ProgressCapsule(null, "Summarizing on this phone…")
        summaryRaw.startsWith("[") -> Column {
            Text(
                summaryRaw.trim('[', ']'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GhostButton("Try again", onRegenerate)
        }
        summaryRaw.isNotEmpty() -> {
            val context = LocalContext.current
            val parts = remember(summaryRaw) { parseSummary(summaryRaw) }
            val entities by produceState(emptyList<ActionEntity>(), summaryRaw) {
                value = withContext(Dispatchers.IO) { EntityExtractor.extract(context, parts) }
            }
            Column {
                SelectionContainer {
                    Text(
                        parts.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (parts.actions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader("Actions")
                    Spacer(Modifier.height(8.dp))
                    parts.actions.forEach { action ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "–",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            SelectionContainer {
                                Text(
                                    action,
                                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
                // Tappable ask: outside the SelectionContainers (chips fight selection handles).
                if (entities.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    EntityChipsRow(entities, onTap = { EntityLauncher.launch(context, it) })
                }
                Spacer(Modifier.height(12.dp))
                GhostButton("Regenerate", onRegenerate)
            }
        }
        !llmReady -> Column {
            Text(
                "Summaries turn each note into a short brief with action items.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TacitButton("Set up summaries", onOpenSettings, Modifier.fillMaxWidth())
        }
        else -> ProgressCapsule(null, "Summarizing on this phone…") // auto-start in flight
    }
}

/** In-app playback of the note's audio — WhatsApp's ogg/opus plays natively via MediaPlayer.
 *  Tap or drag the line to seek. Takes transient audio focus while playing (pauses other
 *  audio; other apps resume when the note ends) and pauses itself if focus is taken away.
 *  Renders nothing if the file can't be opened; released when the screen leaves. */
@Composable
private fun AudioPlayerRow(path: String, key: String) {
    val context = LocalContext.current
    var player by remember(key) { mutableStateOf<MediaPlayer?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var playing by remember(key) { mutableStateOf(false) }
    var positionMs by remember(key) { mutableIntStateOf(0) }
    var durationMs by remember(key) { mutableIntStateOf(0) }
    var dragFrac by remember(key) { mutableStateOf<Float?>(null) } // non-null while scrubbing

    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val focusRequest = remember(key) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) { // something else started playing — yield
                    player?.let { runCatching { it.pause() } }
                    playing = false
                }
            }
            .build()
    }

    fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        if (failed) return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                prepare()
                setOnCompletionListener { mp ->
                    playing = false
                    positionMs = 0
                    runCatching { mp.seekTo(0) }
                    audioManager?.abandonAudioFocusRequest(focusRequest)
                }
            }.also {
                player = it
                durationMs = it.duration
            }
        } catch (e: Exception) {
            AppLog.w("[player] can't open $path: ${e.message}")
            failed = true
            null
        }
    }

    fun pausePlayback() {
        player?.let { runCatching { it.pause() } }
        playing = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    fun startPlayback() {
        val p = ensurePlayer() ?: return
        audioManager?.requestAudioFocus(focusRequest) // best effort — a voice note tap always wins
        runCatching { p.start() }
        playing = true
    }

    fun seekToFraction(f: Float) {
        val p = ensurePlayer() ?: return
        val ms = (f.coerceIn(0f, 1f) * durationMs).toInt()
        runCatching { p.seekTo(ms) }
        positionMs = ms
    }

    // Prepare eagerly off-main so the duration shows before the first tap.
    LaunchedEffect(key) { withContext(Dispatchers.IO) { ensurePlayer() } }
    // Tick the progress line while playing.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.let { runCatching { it.currentPosition }.getOrNull() } ?: 0
            delay(200)
        }
    }
    DisposableEffect(key) {
        onDispose {
            audioManager?.abandonAudioFocusRequest(focusRequest)
            player?.let { runCatching { it.release() } }
            player = null
        }
    }

    if (failed) {
        Text(
            "Couldn't play this audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { if (playing) pausePlayback() else startPlayback() },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { // Pause isn't in the pinned icons-core set — draw the two bars
                        Box(
                            Modifier
                                .size(width = 4.dp, height = 14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                }
            } else {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = "Play voice note",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val playedFrac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val frac = dragFrac ?: playedFrac
        // Tall hit area around the thin line — draggable to scrub, tappable to jump.
        Box(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) seekToFraction(offset.x / size.width)
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (durationMs > 0) dragFrac = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            if (durationMs > 0) dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFrac?.let { seekToFraction(it) }
                            dragFrac = null
                        },
                        onDragCancel = { dragFrac = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(frac)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        if (durationMs > 0) {
            Spacer(Modifier.width(12.dp))
            val shownMs = dragFrac?.let { (it * durationMs).toInt() } ?: positionMs
            Text(
                "${durationLabel(shownMs / 1000.0) ?: "0:00"} / ${durationLabel(durationMs / 1000.0)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
