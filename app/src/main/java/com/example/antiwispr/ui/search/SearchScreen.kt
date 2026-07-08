package com.example.antiwispr.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.antiwispr.SearchEngine
import com.example.antiwispr.SearchFilters
import com.example.antiwispr.SearchHit
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Summarizer
import com.example.antiwispr.Transcripts
import com.example.antiwispr.parseAskAnswer
import com.example.antiwispr.ui.components.OptionPickerSheet
import com.example.antiwispr.ui.components.ProgressCapsule
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Day-granular presets for the date filter chip. */
private enum class DatePreset(val label: String, val days: Int) {
    ANY("Any time", -1),
    TODAY("Today", 0),
    WEEK("Last 7 days", 7),
    MONTH("Last 30 days", 30),
    QUARTER("Last 90 days", 90);

    fun fromYmd(): Int {
        if (days < 0) return -1
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }
}

/**
 * The searchable history: ranked cross-script search over every transcript (Search mode)
 * and LLM answers grounded in them (Ask mode). Filters constrain both.
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    chainKeys: Set<String> = emptySet(),
    initialAsk: Boolean = false,
    onBack: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    var mode by rememberSaveable { mutableIntStateOf(if (initialAsk) 1 else 0) } // 0 = Search, 1 = Ask
    var senderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var datePreset by rememberSaveable { mutableStateOf(DatePreset.ANY) }
    val filters = remember(senderFilter, datePreset) {
        SearchFilters(chatName = senderFilter, fromYmd = datePreset.fromYmd())
    }
    var showSenderSheet by remember { mutableStateOf(false) }
    var showDateSheet by remember { mutableStateOf(false) }

    val senders by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.Default) {
            Transcripts.get(context).all()
                .mapNotNull { it.chatName.ifEmpty { null } }
                .distinct().sortedBy { it.lowercase() }
        }
    }

    // ---- search mode results (debounced; transliteration must stay off main) ----
    val trimmed = query.trim()
    val results by produceState(emptyList<SearchHit>(), trimmed, filters, mode) {
        if (mode != 0 || trimmed.length < 2) { value = emptyList(); return@produceState }
        delay(150) // a new keystroke restarts the producer, cancelling the pending scan
        value = withContext(Dispatchers.Default) {
            SearchEngine.search(Transcripts.get(context).all(), trimmed, filters, limit = 100)
        }
    }

    // ---- ask mode state ----
    var askBusy by remember { mutableStateOf(false) }
    var askStatus by remember { mutableStateOf("") }
    var askRaw by remember { mutableStateOf<String?>(null) }
    var askHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun runAsk() {
        val q = query.trim()
        if (q.length < 3 || askBusy) return
        askBusy = true
        askRaw = null
        scope.launch {
            askStatus = "Finding the right notes…"
            val hits = withContext(Dispatchers.Default) {
                SearchEngine.retrieveForAsk(Transcripts.get(context).all(), q, filters)
            }
            askHits = hits
            askStatus = "Reading ${hits.size} note${if (hits.size == 1) "" else "s"}…"
            askRaw = withContext(Dispatchers.IO) { Summarizer.askBlocking(context, q, hits) }
            askBusy = false
        }
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
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            if (mode == 0) "Search your notes…" else "Ask about your notes…",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (mode == 1) runAsk() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                Modifier.padding(horizontal = Dimens.screenPad),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModePills(mode, onSelect = { mode = it })
                Spacer(Modifier.weight(1f))
                FilterChip(senderFilter ?: "Anyone", active = senderFilter != null) { showSenderSheet = true }
                FilterChip(datePreset.label, active = datePreset != DatePreset.ANY) { showDateSheet = true }
            }
            Spacer(Modifier.height(6.dp))

            if (mode == 0) {
                SearchResults(trimmed, results, chainKeys, onOpen) { text ->
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("TACIT transcript", text))
                }
            } else {
                AskPane(
                    busy = askBusy,
                    status = askStatus,
                    raw = askRaw,
                    hits = askHits,
                    chainKeys = chainKeys,
                    onAsk = { runAsk() },
                    onOpen = onOpen,
                )
            }
        }
    }

    if (showSenderSheet) {
        OptionPickerSheet(
            title = "From",
            options = listOf<String?>(null) + senders,
            selected = senderFilter,
            label = { it ?: "Anyone" },
            onSelect = { senderFilter = it; showSenderSheet = false },
            onDismiss = { showSenderSheet = false },
        )
    }
    if (showDateSheet) {
        OptionPickerSheet(
            title = "When",
            options = DatePreset.entries.toList(),
            selected = datePreset,
            label = { it.label },
            onSelect = { datePreset = it; showDateSheet = false },
            onDismiss = { showDateSheet = false },
        )
    }
}

@Composable
private fun SearchResults(
    trimmed: String,
    results: List<SearchHit>,
    chainKeys: Set<String>,
    onOpen: (StoredTranscript) -> Unit,
    onCopy: (String) -> Unit,
) {
    Crossfade(targetState = trimmed.length >= 2, label = "searchState") { searching ->
        if (!searching) {
            CenteredHint(
                "Every word is searchable, in almost any spelling.\n" +
                    "Typing ghar finds घर. Hindi bhi, chaahe kisi bhi script mein ho.\n" +
                    "Type at least 2 characters.\n\n" +
                    "Only transcribed notes appear here. Catch up older notes from Settings."
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = results.size,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "count",
                ) { n ->
                    Text(
                        if (n == 0) "No matches for “$trimmed”."
                        else if (n == 1) "1 transcript"
                        else "$n transcripts",
                        modifier = Modifier.padding(horizontal = Dimens.screenPad, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Dimens.screenPad, end = Dimens.screenPad,
                        top = 4.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                ) {
                    items(results, key = { it.transcript.key }) { hit ->
                        TranscriptCard(
                            hit.transcript,
                            query = trimmed,
                            chained = hit.transcript.key in chainKeys,
                            onClick = { onOpen(hit.transcript) },
                            onLongPress = { onCopy(hit.transcript.text) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AskPane(
    busy: Boolean,
    status: String,
    raw: String?,
    hits: List<SearchHit>,
    chainKeys: Set<String>,
    onAsk: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPad),
    ) {
        when {
            busy -> {
                Spacer(Modifier.height(18.dp))
                ProgressCapsule(null, status)
            }
            raw == null -> {
                Spacer(Modifier.height(48.dp))
                Text(
                    "Ask in your own words.\n\"kya address bheja tha Rahul ne last week?\"\n\n" +
                        "TACIT reads your matching notes and answers from them. " +
                        "Press search to ask.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            raw.startsWith("[") -> {
                Spacer(Modifier.height(18.dp))
                Text(
                    raw.trim('[', ']'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .clickable(onClick = onAsk)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Try again",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            else -> {
                val parsed = remember(raw) { parseAskAnswer(raw) }
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ) {
                    SelectionContainer {
                        Text(
                            parsed.answer,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                val cited = remember(raw, hits) {
                    parsed.sources.mapNotNull { n -> hits.getOrNull(n - 1) }
                        .distinctBy { it.transcript.key }
                        .ifEmpty { hits }
                }
                if (cited.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionHeader("From your notes")
                    Spacer(Modifier.height(8.dp))
                    cited.forEach { hit ->
                        TranscriptCard(
                            hit.transcript,
                            chained = hit.transcript.key in chainKeys,
                            onClick = { onOpen(hit.transcript) },
                            modifier = Modifier.padding(bottom = Dimens.itemGap),
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ModePills(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        listOf("Search", "Ask").forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
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
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "$label ▾",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier.padding(Dimens.screenPad),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
