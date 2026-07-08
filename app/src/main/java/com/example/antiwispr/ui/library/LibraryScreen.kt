package com.example.antiwispr.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.cloud.SyncEngine
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens

/**
 * The Library: the full transcript corpus, newest first. Cloud-synced and locally transcribed
 * records live in the same store, so one list covers both. Opening the screen nudges a sync so
 * anything new on the server appears (no-op when signed out). Long-press a card to copy it.
 */
@Composable
fun LibraryScreen(
    library: List<StoredTranscript>,
    chainKeys: Set<String> = emptySet(),
    onBack: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEngine.requestSync(context) }

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
                Text(
                    "Library",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Filled.Search, contentDescription = "Search your notes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (library.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(Dimens.screenPad),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Nothing here yet.",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Transcripts appear as notes play, or sync from your other phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Text(
                    if (library.size == 1) "1 transcript" else "${library.size} transcripts",
                    modifier = Modifier.padding(horizontal = Dimens.screenPad, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Dimens.screenPad, end = Dimens.screenPad,
                        top = 4.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                ) {
                    items(library, key = { it.key }) { t ->
                        TranscriptCard(
                            t,
                            chained = t.key in chainKeys,
                            onClick = { onOpen(t) },
                            onLongPress = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText("TACIT transcript", t.text)
                                    )
                            },
                        )
                    }
                }
            }
        }
    }
}
