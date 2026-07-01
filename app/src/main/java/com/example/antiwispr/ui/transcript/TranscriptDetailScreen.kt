package com.example.antiwispr.ui.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.components.waDateLabel
import com.example.antiwispr.ui.theme.Dimens
import kotlinx.coroutines.delay

/** Reading view: metadata block over a hairline, then the transcript in a book column. */
@Composable
fun TranscriptDetailScreen(
    transcript: StoredTranscript?,
    onBack: () -> Unit,
) {
    // Process-death restore can land here with no selection — bounce back gracefully.
    if (transcript == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
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
                Spacer(Modifier.height(16.dp))
                InkDivider()
                Spacer(Modifier.height(20.dp))
                SelectionContainer {
                    Text(
                        transcript.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(32.dp))
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
                            ?.setPrimaryClip(ClipData.newPlainText("Tacit transcript", transcript.text))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GhostButton("Share", onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, transcript.text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share transcript"))
                })
            }
        }
    }
}
