package com.example.antiwispr.ui.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.ChainSummaries
import com.example.antiwispr.ChainSummarizer
import com.example.antiwispr.Chains
import com.example.antiwispr.LlmModel
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Summarizer
import com.example.antiwispr.Transcripts
import com.example.antiwispr.cloud.SyncEngine
import com.example.antiwispr.parseSummary
import java.io.File
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.components.waDateLabel
import com.example.antiwispr.ui.theme.Dimens
import kotlinx.coroutines.delay

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

    // Chain membership (files on disk; null on a restored library without the audio).
    val chain = remember(transcript.key) {
        runCatching { Chains.chainFor(context, File(transcript.path)) }.getOrNull()
    }
    var chainRaw by remember(transcript.key) {
        mutableStateOf(chain?.let { ChainSummaries.get(context).find(it.id)?.raw }.orEmpty())
    }
    var chainGenerating by remember(transcript.key) { mutableStateOf(false) }
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
                Text(
                    waDateLabel(transcript.waDate) ?: "Voice note",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Transcribed ${relativeTime(transcript.updatedAt).lowercase()}  ·  ${transcript.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chain != null) {
                    Spacer(Modifier.height(14.dp))
                    ChainSection(
                        part = chain.partIndexOf(File(transcript.path)),
                        count = chain.size,
                        chatName = chain.chatName ?: transcript.chatName.ifEmpty { null },
                        raw = chainRaw,
                        generating = chainGenerating,
                        canGenerate = canSummarize,
                        onGenerate = {
                            chainGenerating = true
                            ChainSummarizer.request(context, chain, chain.chatName) { result ->
                                chainRaw = result
                                chainGenerating = false
                            }
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                ReaderTabs(tab, onSelect = { tab = it })
                Spacer(Modifier.height(14.dp))
                InkDivider()
                Spacer(Modifier.height(18.dp))

                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) using null },
                    label = "readerTab",
                ) { t ->
                    if (t == 0) {
                        SelectionContainer {
                            Text(
                                transcript.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
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

            val activeText = if (tab == 1 && summaryRaw.isNotEmpty() && !summaryRaw.startsWith("["))
                summaryRaw else transcript.text
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
                            ?.setPrimaryClip(ClipData.newPlainText("Tacit note", activeText))
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
            title = { Text("Re-transcribe this note?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Replaces the saved transcript and summary using your current " +
                        "transcription settings.",
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
                    "The saved text is removed. The voice note itself is untouched — " +
                        "play it again in WhatsApp and TACIT transcribes it fresh.",
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

/** "Part 2 of 4 · sent together · Mom" + the chain gist (cached, generating, or a button). */
@Composable
private fun ChainSection(
    part: Int,
    count: Int,
    chatName: String?,
    raw: String,
    generating: Boolean,
    canGenerate: Boolean,
    onGenerate: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                buildString {
                    append("PART $part OF $count · SENT TOGETHER")
                    if (!chatName.isNullOrBlank()) append(" · ${chatName.uppercase()}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
            when {
                generating -> ProgressCapsule(null, "Summarizing $count notes…")
                raw.startsWith("[") -> Column {
                    Text(
                        raw.trim('[', ']'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Try again", onGenerate, enabled = canGenerate)
                }
                raw.isNotEmpty() -> {
                    val parts = remember(raw) { parseSummary(raw) }
                    Column {
                        Text(
                            parts.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (parts.actions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            parts.actions.forEach { action ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text("–", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(action, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                else -> GhostButton(
                    "Summarize the chain ($count notes)",
                    onGenerate,
                    enabled = canGenerate,
                )
            }
        }
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
            val parts = remember(summaryRaw) { parseSummary(summaryRaw) }
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
                Spacer(Modifier.height(12.dp))
                GhostButton("Regenerate", onRegenerate)
            }
        }
        !llmReady -> Column {
            Text(
                "Summaries turn each note into a short brief with action items — " +
                    "on this phone, or via your cloud account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TacitButton("Set up summaries", onOpenSettings, Modifier.fillMaxWidth())
        }
        else -> ProgressCapsule(null, "Summarizing on this phone…") // auto-start in flight
    }
}
