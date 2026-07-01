package com.example.antiwispr.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.antiwispr.AppLog
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.theme.Dimens
import com.example.antiwispr.ui.theme.MonoStyle
import kotlinx.coroutines.launch

/**
 * Live AppLog viewer. Owns the single-slot AppLog listener while visible (the listener
 * already posts to the main thread); detaches on dispose. Follows the tail until the
 * user scrolls up, with a jump-back chip.
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>().apply { addAll(AppLog.snapshot()) } }
    DisposableEffect(Unit) {
        AppLog.setListener { line ->
            lines += line
            if (lines.size > 3000) lines.removeAt(0)
        }
        onDispose { AppLog.setListener(null) }
    }

    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(lines.size) {
        if (atBottom && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {
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
                        "Log",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(
                                ClipData.newPlainText("Tacit log", lines.joinToString("\n"))
                            )
                    }) {
                        Text(
                            "Copy all",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Dimens.screenPad, end = Dimens.screenPad,
                            top = 4.dp, bottom = 24.dp,
                        ),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                line,
                                style = MonoStyle,
                                color = when {
                                    line.startsWith("‼") -> MaterialTheme.colorScheme.error
                                    line.startsWith("⚠") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !atBottom && lines.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                androidx.compose.material3.Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp,
                ) {
                    GhostButton("Jump to latest ↓", onClick = {
                        scope.launch { listState.scrollToItem(lines.size - 1) }
                    })
                }
            }
        }
    }
}
