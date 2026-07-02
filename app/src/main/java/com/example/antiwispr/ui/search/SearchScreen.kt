package com.example.antiwispr.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Transcripts
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens

/**
 * Live transcript search. Parity with the old SearchActivity: >= 2 characters,
 * case-insensitive substring, newest first, capped at 100; long-press copies.
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val trimmed = query.trim()
    val results = remember(trimmed) {
        if (trimmed.length < 2) emptyList()
        else Transcripts.get(context).search(trimmed).take(100)
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
                            "Search transcripts…",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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

            Crossfade(targetState = trimmed.length >= 2, label = "searchState") { searching ->
                if (!searching) {
                    CenteredHint(
                        "Every word of every transcript is searchable.\nType at least 2 characters."
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
                                modifier = Modifier.padding(
                                    horizontal = Dimens.screenPad, vertical = 6.dp
                                ),
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
                            items(results, key = { it.key }) { t ->
                                TranscriptCard(
                                    t,
                                    query = trimmed,
                                    onClick = { onOpen(t) },
                                    onLongPress = {
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(
                                                ClipData.newPlainText("Tacit transcript", t.text)
                                            )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
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
