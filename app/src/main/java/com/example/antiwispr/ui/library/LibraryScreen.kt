package com.example.antiwispr.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.cloud.SyncEngine
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.components.dayGroupKey
import com.example.antiwispr.ui.components.dayHeaderLabel
import com.example.antiwispr.ui.components.waDateLabel
import com.example.antiwispr.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * The Library: the full transcript corpus, grouped under sticky day headers, newest first. Cloud-
 * synced and locally transcribed records live in the same store, so one list covers both. Opening
 * nudges a sync (no-op signed out). Long-press a card to enter multi-select: copy or delete in bulk,
 * with an Undo window on delete. Tap a card to open it.
 */
@Composable
fun LibraryScreen(
    library: List<StoredTranscript>,
    chainKeys: Set<String> = emptySet(),
    onBack: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
    onOpenSearch: () -> Unit = {},
    onDelete: (List<StoredTranscript>) -> Unit = {},
    onUndoDelete: () -> Unit = {},
    onCommitDelete: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEngine.requestSync(context) }

    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun exitSelection() { selectionMode = false; selected.clear() }
    fun toggle(key: String) { if (!selected.remove(key)) selected.add(key) }

    BackHandler(enabled = selectionMode) { exitSelection() }

    // Newest day first; newest note first within a day. Grouped into day buckets (order preserved).
    val groups = remember(library) {
        library.sortedWith(
            compareByDescending<StoredTranscript> { dayGroupKey(it.waDate, it.updatedAt) }
                .thenByDescending { it.updatedAt }
        ).groupBy { dayGroupKey(it.waDate, it.updatedAt) }
    }

    fun copySelected() {
        val recs = library.filter { it.key in selected }
        if (recs.isEmpty()) return
        val text = recs.joinToString("\n\n") { r ->
            val head = listOfNotNull(r.chatName.ifBlank { null }, waDateLabel(r.waDate)).joinToString("  ·  ")
            if (head.isNotBlank()) "$head\n${r.text}" else r.text
        }
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("TACIT transcripts", text))
        val n = recs.size
        exitSelection()
        scope.launch { snackbar.showSnackbar("Copied $n transcript${if (n == 1) "" else "s"}.") }
    }

    fun deleteSelected() {
        val recs = library.filter { it.key in selected }
        if (recs.isEmpty()) return
        val n = recs.size
        onDelete(recs) // soft-delete: removed from the store now, tombstone deferred
        exitSelection()
        scope.launch {
            val res = snackbar.showSnackbar(
                message = "$n transcript${if (n == 1) "" else "s"} deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) onUndoDelete() else onCommitDelete()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (selectionMode) {
                SelectionBar(
                    count = selected.size,
                    onClose = { exitSelection() },
                    onSelectAll = { selected.clear(); selected.addAll(library.map { it.key }) },
                    onCopy = { copySelected() },
                    onDelete = { deleteSelected() },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
                if (!selectionMode) {
                    Text(
                        if (library.size == 1) "1 transcript" else "${library.size} transcripts",
                        modifier = Modifier.padding(horizontal = Dimens.screenPad, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                ) {
                    groups.forEach { (dayEpoch, dayItems) ->
                        item(key = "h$dayEpoch") { DayHeader(dayHeaderLabel(dayEpoch)) }
                        items(dayItems, key = { it.key }) { t ->
                            LibraryRow(
                                transcript = t,
                                chained = t.key in chainKeys,
                                selectionMode = selectionMode,
                                selected = t.key in selected,
                                onClick = { if (selectionMode) toggle(t.key) else onOpen(t) },
                                onLongPress = {
                                    if (!selectionMode) selectionMode = true
                                    if (t.key !in selected) selected.add(t.key)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Text(
            label,
            modifier = Modifier.padding(start = Dimens.screenPad, end = Dimens.screenPad, top = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
    }
}

@Composable
private fun LibraryRow(
    transcript: StoredTranscript,
    chained: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        TranscriptCard(
            transcript,
            chained = chained,
            onClick = onClick,
            onLongPress = onLongPress,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close, contentDescription = "Exit selection",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "$count selected",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onSelectAll) {
            Text("Select all", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onCopy, enabled = count > 0) {
            Icon(
                TacitIcons.Copy, contentDescription = "Copy selected",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Filled.Delete, contentDescription = "Delete selected",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
