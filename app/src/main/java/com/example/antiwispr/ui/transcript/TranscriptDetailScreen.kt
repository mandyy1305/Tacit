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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import com.example.antiwispr.LlmModel
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Summarizer
import com.example.antiwispr.Transcripts
import com.example.antiwispr.parseSummary
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
    onOpenSettings: () -> Unit = {},
) {
    // Process-death restore (or a just-deleted transcript) lands here with no
    // selection — bounce back gracefully.
    if (transcript == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var tab by rememberSaveable(transcript.key) { mutableIntStateOf(0) } // 0 = Summary, 1 = Transcript
    var summaryRaw by remember(transcript.key) { mutableStateOf(transcript.summary) }
    var generating by remember(transcript.key) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }

    val llmReady = remember { LlmModel.isReady(context) }
    fun generate() {
        generating = true
        Summarizer.request(context, transcript.key, transcript.text) { raw ->
            if (!raw.startsWith("[")) Transcripts.get(context).putSummary(transcript.key, raw)
            summaryRaw = raw
            generating = false
        }
    }
    // Auto-generate on first open when the model is present but this note has no summary yet
    // (covers watcher-transcribed notes, which are summarized on-demand by design).
    LaunchedEffect(transcript.key) {
        if (summaryRaw.isEmpty() && llmReady) generate()
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
                        SummaryTab(
                            summaryRaw = summaryRaw,
                            generating = generating,
                            llmReady = llmReady,
                            onRegenerate = { generate() },
                            onOpenSettings = onOpenSettings,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                transcript.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }

            val activeText = if (tab == 0 && summaryRaw.isNotEmpty() && !summaryRaw.startsWith("["))
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

@Composable
private fun ReaderTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
    ) {
        listOf("Summary", "Transcript").forEachIndexed { i, label ->
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
                    "generated entirely on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TacitButton("Download the summary model", onOpenSettings, Modifier.fillMaxWidth())
        }
        else -> ProgressCapsule(null, "Summarizing on this phone…") // auto-start in flight
    }
}
