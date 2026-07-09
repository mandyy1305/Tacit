package com.example.antiwispr.ui.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.ui.AskMessage
import com.example.antiwispr.ui.AskRole
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.components.TranscriptCard
import com.example.antiwispr.ui.theme.Dimens

/**
 * The AI Ask tab: a ChatGPT-style thread over the user's own notes. Each question is retrieved +
 * answered independently (no cross-message memory yet) and appended as a bubble; assistant answers
 * carry the source notes they drew from. State lives in the ViewModel so the thread survives tab
 * switches.
 */
@Composable
fun AskScreen(
    messages: List<AskMessage>,
    busy: Boolean,
    entered: MutableSet<Long> = mutableSetOf(),
    chainKeys: Set<String> = emptySet(),
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onOpen: (StoredTranscript) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Keep the newest turn in view as the thread grows (index 0 is the bottom in reverseLayout).
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.screenPad, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ask",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Answers from your own notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = onClear, enabled = !busy) {
                        Icon(
                            Icons.Filled.Delete, contentDescription = "Clear conversation",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Empty hint crossfades into the thread on the first message.
                Crossfade(targetState = messages.isEmpty(), label = "askBody") { empty ->
                    if (empty) {
                        EmptyAsk(Modifier.fillMaxSize())
                    } else {
                        // reverseLayout: newest sits at the bottom and the list stays anchored there,
                        // so adding a turn never re-lays-out the whole stack. Stable id keys let
                        // animateItem fade each new bubble in and glide the rest up.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = Dimens.screenPad, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                count = messages.size,
                                key = { i -> messages[messages.lastIndex - i].id },
                            ) { i ->
                                val m = messages[messages.lastIndex - i]
                                val play = remember(m.id) { m.id !in entered }
                                // animateItem handles only reflow of existing bubbles; the entrance
                                // (scale + fade "pop") is owned by BubbleEntrance so both sent and
                                // received bubbles animate in the same way, once each.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                ) {
                                    BubbleEntrance(play = play, onEntered = { entered.add(m.id) }) {
                                        when (m.role) {
                                            AskRole.User -> UserBubble(m.text)
                                            AskRole.Assistant -> AssistantBubble(m, chainKeys, onOpen)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                busy = busy,
                onSend = {
                    val q = draft.trim()
                    if (q.length >= 3) {
                        onSend(q)
                        draft = ""
                        focusManager.clearFocus() // drop focus + dismiss the keyboard on send
                        keyboard?.hide()
                    }
                },
            )
        }
    }
}

/**
 * Plays a scale + fade "pop" the first time a bubble appears (sent or received), then stays put.
 * [play] is false for bubbles that already entered (e.g. after switching tabs and back), so the
 * animation fires exactly once per turn.
 */
@Composable
private fun BubbleEntrance(play: Boolean, onEntered: () -> Unit, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(initialState = !play) }
    LaunchedEffect(Unit) {
        state.targetState = true
        onEntered()
    }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.9f, animationSpec = tween(240)),
    ) {
        content()
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    m: AskMessage,
    chainKeys: Set<String>,
    onOpen: (StoredTranscript) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            // Crossfade the "typing" indicator into the answer; the bubble grows into place.
            AnimatedContent(
                targetState = m.pending,
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(initialScale = 0.94f, animationSpec = tween(240)))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "askBubble",
            ) { pending ->
                if (pending) {
                    TypingDots(Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                } else {
                    SelectionContainer {
                        Text(
                            m.text,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (m.error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = m.sources.isNotEmpty(),
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(
                    "From your notes",
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                m.sources.forEach { t ->
                    TranscriptCard(
                        t,
                        chained = t.key in chainKeys,
                        onClick = { onOpen(t) },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** Three staggered fading dots — the assistant's "typing" indicator while an answer is generated. */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(520, delayMillis = i * 160, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(8.dp)
                    .alpha(a)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
) {
    val canSend = !busy && draft.trim().length >= 3
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        InkDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            if (draft.isEmpty()) {
                                Text(
                                    "Ask about your notes…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable(enabled = canSend, onClickLabel = "Send") { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TacitIcons.Send, contentDescription = null,
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyAsk(modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(Dimens.screenPad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            TacitIcons.Ask, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Ask in your own words",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "\"kya address bheja tha Rahul ne last week?\"\n\n" +
                "TACIT reads your matching notes and answers from them — nothing leaves your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
