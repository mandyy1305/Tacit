package com.example.antiwispr.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.ActionEntity
import com.example.antiwispr.EntityExtractor
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.theme.Fraunces
import com.example.antiwispr.ui.theme.Inter
import com.example.antiwispr.ui.theme.TacitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The overlay is ALWAYS dark-glass — it floats over WhatsApp's green/dark chrome,
 * so it never follows the system theme.
 */
internal object OverlayPalette {
    val surface = Color(0xF2141210)    // espresso, ~95% alpha — the "glass"
    val surfaceHi = Color(0xFF1E1A16)  // gradient top edge
    val ink = Color(0xFFECE6DC)        // cream text
    val inkMuted = Color(0xB3ECE6DC)   // cream @ 70%
    val inkFaint = Color(0x66ECE6DC)   // cream @ 40%
    val accent = Color(0xFFD97B45)     // burnt amber (dark-theme tone)
    val accentDeep = Color(0xFFC4622D)
    val hairline = Color(0x1FECE6DC)   // cream @ 12% border
    val mint = Color(0xFFA6B899)       // ok/success (muted sage)
}

/** Dark-locked theme wrapper so typography/shape tokens resolve inside the overlay window. */
@Composable
fun TacitOverlayTheme(content: @Composable () -> Unit) {
    TacitTheme(darkTheme = true, content = content)
}

@Composable
fun TacitOverlayCard(
    state: OverlayUiState,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCopy: (String) -> Unit,
    onToggleChain: (Boolean) -> Unit = {},
    onRequestSummary: () -> Unit = {},
    onEntityTap: (ActionEntity) -> Unit = {},
    onCommitCandidate: (Int) -> Unit = {},
    onNoneOfThese: () -> Unit = {},
    onWrongNote: () -> Unit = {},
    onExitFinished: () -> Unit,
) {
    val enterState = remember { MutableTransitionState(false) }
    enterState.targetState = state.visible
    LaunchedEffect(enterState.currentState, enterState.targetState) {
        if (!enterState.currentState && !enterState.targetState) onExitFinished()
    }

    // One-shot amber wash when the match lands.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.phase) {
        if (state.phase == OverlayPhase.MATCHED) {
            flash.snapTo(0.22f)
            flash.animateTo(0f, tween(700))
        }
    }

    AnimatedVisibility(
        visibleState = enterState,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
            scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically { -it / 8 },
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.97f, animationSpec = tween(140)) +
            slideOutVertically(tween(140)) { -it / 10 },
    ) {
        val corner = RoundedCornerShape(20.dp)
        val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .shadow(12.dp, corner, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(corner)
                .background(Brush.verticalGradient(listOf(OverlayPalette.surfaceHi, OverlayPalette.surface)))
                .drawBehind {
                    if (flash.value > 0f) {
                        drawRoundRect(
                            OverlayPalette.accent.copy(alpha = flash.value),
                            cornerRadius = CornerRadius(20.dp.toPx()),
                        )
                    }
                }
                .border(1.dp, OverlayPalette.hairline, corner)
                .padding(18.dp)
        ) {
            Header(onClose)

            // Fade only — expand/shrink would animate layout height and resize the window
            // per frame (same stutter as above).
            AnimatedVisibility(
                visible = state.micBanner,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(150)),
            ) {
                MicFallbackPill(onShare)
            }

            Spacer(Modifier.height(12.dp))

            // NO size animation, on purpose. Animating layout height in a WRAP_CONTENT
            // overlay resizes the WINDOW every frame (IPC + surface realloc) — that is the
            // stutter, and no rendering backend fixes it. `using null` makes the card snap
            // to its new height in a single relayout; the felt motion is fade+slide only,
            // which is pure GPU work and never touches the window size.
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 40, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 14 }) togetherWith
                        fadeOut(tween(90)) using null
                },
                label = "phase",
            ) { phase ->
                when (phase) {
                    OverlayPhase.LISTENING -> ListeningBody(state)
                    OverlayPhase.MATCHED -> MatchedBody(state)
                    OverlayPhase.TRANSCRIBING -> TranscribingBody(state)
                    OverlayPhase.TRANSCRIPT -> TranscriptBody(state, onCopy, onToggleChain, onRequestSummary, onEntityTap, onWrongNote)
                    OverlayPhase.CLOSE_MATCHES -> CloseMatchesBody(state, onCommitCandidate, onNoneOfThese)
                    OverlayPhase.NO_MATCH -> NoMatchBody()
                    OverlayPhase.NOTICE -> NoticeBody(state)
                }
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------

@Composable
private fun Header(onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "TACIT",
                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 2.sp,
                color = OverlayPalette.accent,
            )
            Text(
                "Voice note",
                fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp, color = OverlayPalette.ink,
            )
        }
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close, contentDescription = "Dismiss",
                tint = OverlayPalette.inkMuted, modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MicFallbackPill(onShare: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OverlayPalette.accent.copy(alpha = 0.12f))
            .border(1.dp, OverlayPalette.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Using the mic for this one.",
                fontFamily = Inter, fontWeight = FontWeight.Medium,
                fontSize = 13.sp, color = OverlayPalette.ink,
            )
            Text(
                "Screen share hears notes directly. Surer matches.",
                fontFamily = Inter, fontSize = 11.sp,
                color = OverlayPalette.inkMuted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(OverlayPalette.accentDeep)
                .clickable(onClick = onShare)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                "Share screen",
                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = Color(0xFFFFF3E9),
            )
        }
    }
}

/** Five amber bars breathing in a staggered wave — the listening motif. */
@Composable
private fun ListeningBars(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = List(5) { i ->
        t.animateFloat(
            initialValue = 0.22f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(420, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 90),
            ),
            label = "bar$i",
        )
    }
    Canvas(modifier.width(34.dp).height(20.dp)) {
        val bw = size.width / 9f // 5 bars + 4 gaps
        bars.forEachIndexed { i, f ->
            val h = size.height * f.value
            drawRoundRect(
                OverlayPalette.accent,
                topLeft = Offset(i * 2 * bw, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
}

@Composable
private fun ListeningBody(state: OverlayUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListeningBars()
        Spacer(Modifier.width(14.dp))
        AnimatedContent(
            targetState = state.statusLine,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
            label = "status",
        ) { line ->
            Text(
                line,
                fontFamily = Inter, fontSize = 14.sp,
                color = if (state.statusWarn) OverlayPalette.accent else OverlayPalette.inkMuted,
            )
        }
    }
}

/** Check in an amber ring; bounces only when [animate] (the moment the match lands). */
@Composable
private fun MatchBadge(animate: Boolean) {
    val scale = remember { Animatable(if (animate) 0.5f else 1f) }
    if (animate) {
        LaunchedEffect(Unit) {
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Box(
        Modifier
            .size(30.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape)
            .background(OverlayPalette.accent.copy(alpha = 0.16f))
            .border(1.dp, OverlayPalette.accent.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check, contentDescription = null,
            tint = OverlayPalette.accent, modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The matched row: badge, MATCHED overline, then the note's date · duration as the
 * main line (the header already says "Voice note" — no need to repeat it).
 */
@Composable
private fun MatchLine(state: OverlayUiState, animateBadge: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MatchBadge(animateBadge)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "MATCHED",
                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 1.5.sp,
                color = OverlayPalette.mint,
            )
            val meta = state.match?.meta ?: "Voice note"
            Text(
                if (state.chainCount > 1) "Part ${state.chainPart} of ${state.chainCount}  ·  $meta" else meta,
                fontFamily = Inter, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, color = OverlayPalette.ink,
            )
        }
    }
}

@Composable
private fun MatchedBody(state: OverlayUiState) {
    MatchLine(state, animateBadge = true)
}

@Composable
private fun TranscribingBody(state: OverlayUiState) {
    Column {
        MatchLine(state, animateBadge = false)
        Spacer(Modifier.height(14.dp))
        TranscribingShimmer()
        Spacer(Modifier.height(10.dp))
        Text(
            if (state.source == "cloud") "Transcribing with TACIT Cloud…" else "Transcribing on this phone…",
            fontFamily = Inter, fontSize = 11.sp,
            color = OverlayPalette.inkFaint,
        )
    }
}

/** Quiet provenance wordlet on results: "ON-DEVICE" or "CLOUD". Renders nothing when unknown. */
@Composable
private fun SourceMark(source: String) {
    val label = when (source) {
        "local" -> "ON-DEVICE"
        "cloud" -> "CLOUD"
        else -> return
    }
    Text(
        label,
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 1.sp,
        color = OverlayPalette.inkMuted,
    )
}

@Composable
private fun TranscribingShimmer() {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "x",
    )
    Column {
        listOf(1f, 0.92f, 0.61f).forEach { w ->
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth(w)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .drawBehind {
                        val wide = size.width
                        val start = (x * 2f - 1f) * wide
                        drawRect(
                            Brush.linearGradient(
                                listOf(
                                    OverlayPalette.ink.copy(alpha = 0.05f),
                                    OverlayPalette.ink.copy(alpha = 0.14f),
                                    OverlayPalette.ink.copy(alpha = 0.05f),
                                ),
                                start = Offset(start, 0f),
                                end = Offset(start + wide, 0f),
                            )
                        )
                    }
            )
        }
    }
}

@Composable
private fun TranscriptBody(
    state: OverlayUiState,
    onCopy: (String) -> Unit,
    onToggleChain: (Boolean) -> Unit,
    onRequestSummary: () -> Unit,
    onEntityTap: (ActionEntity) -> Unit,
    onWrongNote: () -> Unit,
) {
    // 0 = Transcript (default — instantly available), 1 = Summary (generated lazily the
    // first time the user opens it).
    var tab by remember { mutableIntStateOf(0) }
    Column {
        MatchLine(state, animateBadge = false)
        if (state.chainCount > 1) {
            Spacer(Modifier.height(8.dp))
            ChainTogglePill(state, onToggleChain)
        }
        Spacer(Modifier.height(12.dp))
        OverlayTabs(tab, onSelect = { i ->
            tab = i
            // Lazy summaries: the first open of the Summary tab starts generation. NONE =
            // idle (Orchestrator armed a pending request); READY/UNAVAILABLE/GENERATING
            // never re-fire, and the state flips to GENERATING synchronously on this tap.
            if (i == 1 && state.summaryState == SummaryState.NONE) onRequestSummary()
        })
        Spacer(Modifier.height(10.dp))
        // Fade only, size snaps (`using null`) — the no-window-resize-animation rule.
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) using null },
            label = "tab",
        ) { t ->
            Box(Modifier.verticalScroll(rememberScrollState())) {
                if (t == 0) TranscriptPane(state) else SummaryPane(state, onEntityTap)
            }
        }
        Spacer(Modifier.height(10.dp))
        val copyText = when {
            tab == 1 -> state.summaryRaw.orEmpty()
            state.chainMode && state.chainParts.any { it != null } ->
                state.chainParts.mapIndexedNotNull { i, t -> t?.let { "Part ${i + 1}: $it" } }
                    .joinToString("\n\n")
            else -> state.transcript.orEmpty()
        }
        if (copyText.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceMark(state.source)
                if (state.candidates.size > 1) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Wrong note?",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onWrongNote() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        fontFamily = Inter, fontSize = 11.sp,
                        color = OverlayPalette.inkMuted,
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCopy(copyText) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    AnimatedContent(
                        targetState = state.copied,
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                        label = "copy",
                    ) { copied ->
                        Text(
                            if (copied) "COPIED ✓" else "COPY",
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp, letterSpacing = 1.sp,
                            color = if (copied) OverlayPalette.mint else OverlayPalette.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(OverlayPalette.ink.copy(alpha = 0.06f))
            .padding(3.dp)
    ) {
        listOf("Transcript", "Summary").forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) OverlayPalette.accentDeep else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    fontFamily = Inter,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.sp,
                    color = if (active) Color(0xFFFFF3E9) else OverlayPalette.inkMuted,
                )
            }
        }
    }
}

/** "Transcribe all N" toggle — flips the card between this-note and whole-burst content. */
@Composable
private fun ChainTogglePill(state: OverlayUiState, onToggleChain: (Boolean) -> Unit) {
    val on = state.chainMode
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (on) Modifier.background(OverlayPalette.accentDeep)
                else Modifier.border(1.dp, OverlayPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            )
            .clickable { onToggleChain(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (on) "All ${state.chainCount} notes ✓" else "Transcribe all ${state.chainCount}",
            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = if (on) Color(0xFFFFF3E9) else OverlayPalette.accent,
        )
    }
}

@Composable
private fun TranscriptPane(state: OverlayUiState) {
    if (state.chainMode && state.chainParts.isNotEmpty()) {
        Column {
            state.chainParts.forEachIndexed { i, part ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Text(
                    if (i + 1 == state.chainPart) "PART ${i + 1} · THIS NOTE" else "PART ${i + 1}",
                    fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp, letterSpacing = 1.5.sp,
                    color = OverlayPalette.accent,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    part == null -> Text(
                        "transcribing…",
                        fontFamily = Inter, fontSize = 13.sp,
                        color = OverlayPalette.inkFaint,
                    )
                    part.startsWith("[") -> Text(
                        humanizeNotice(part),
                        fontFamily = Inter, fontSize = 13.sp,
                        color = OverlayPalette.inkMuted,
                    )
                    else -> Text(
                        part,
                        fontFamily = Inter, fontSize = 15.sp, lineHeight = 23.sp,
                        color = OverlayPalette.ink,
                    )
                }
            }
        }
    } else {
        Text(
            state.transcript.orEmpty(),
            fontFamily = Inter, fontSize = 15.sp, lineHeight = 23.sp,
            color = OverlayPalette.ink,
        )
    }
}

@Composable
private fun SummaryPane(state: OverlayUiState, onEntityTap: (ActionEntity) -> Unit) {
    Column {
        if (state.chainSummary && state.chainCount > 1) {
            Text(
                "CHAIN · ${state.chainCount} NOTES",
                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 1.5.sp,
                color = OverlayPalette.mint,
            )
            Spacer(Modifier.height(6.dp))
        }
        when (state.summaryState) {
            // NONE (lazy idle) renders the shimmer too — it is only visible for the sub-frame
            // between the Summary-tab tap and setSummaryGenerating landing, so the two must
            // look identical.
            SummaryState.GENERATING, SummaryState.NONE -> Column {
                Text(
                    when {
                        state.chainSummary && state.chainCount > 1 -> "Summarizing ${state.chainCount} notes…"
                        state.source == "cloud" -> "Summarizing with TACIT Cloud…"
                        else -> "Summarizing on this phone…"
                    },
                    fontFamily = Inter, fontSize = 12.sp,
                    color = OverlayPalette.inkFaint,
                )
                Spacer(Modifier.height(8.dp))
                TranscribingShimmer()
            }
            SummaryState.UNAVAILABLE -> Text(
                "Summaries need a one-time setup. Open TACIT to set them up.",
                fontFamily = Inter, fontSize = 13.sp,
                color = OverlayPalette.inkMuted,
            )
            SummaryState.READY -> {
                val raw = state.summaryRaw.orEmpty()
                if (raw.startsWith("[")) {
                    Text(
                        humanizeNotice(raw),
                        fontFamily = Inter, fontSize = 13.sp,
                        color = OverlayPalette.inkMuted,
                    )
                } else {
                    val context = LocalContext.current
                    val parts = remember(raw) { com.example.antiwispr.parseSummary(raw) }
                    val entities by produceState(emptyList<ActionEntity>(), raw) {
                        value = withContext(Dispatchers.IO) {
                            EntityExtractor.extract(context, parts, cap = EntityExtractor.OVERLAY_CAP)
                        }
                    }
                    Column {
                        Text(
                            parts.summary,
                            fontFamily = Inter, fontSize = 15.sp, lineHeight = 23.sp,
                            color = OverlayPalette.ink,
                        )
                        if (parts.actions.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "ACTIONS",
                                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp, letterSpacing = 1.5.sp,
                                color = OverlayPalette.accent,
                            )
                            Spacer(Modifier.height(4.dp))
                            parts.actions.forEach { action ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        "–  ",
                                        fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp,
                                        color = OverlayPalette.accent,
                                    )
                                    Text(
                                        action,
                                        fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp,
                                        color = OverlayPalette.ink,
                                    )
                                }
                            }
                        }
                        // The tappable ask. Appears as a snap once extraction lands (allowed);
                        // never wrap in expand/shrink — the window-resize animation rule.
                        if (entities.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            EntityChipsFlow(entities, onEntityTap)
                        }
                    }
                }
            }
        }
    }
}

/** Tappable entity pills (the concrete ask) — overlay-styled clone of the chain toggle pill.
 *  Tap acts (call, map, calendar); long-press copies the full text so a truncated chip is
 *  never a dead end. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntityChipsFlow(entities: List<ActionEntity>, onTap: (ActionEntity) -> Unit) {
    val context = LocalContext.current
    var copiedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedText) { if (copiedText != null) { delay(1500); copiedText = null } }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entities.forEach { entity ->
            val copied = copiedText == entity.text
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, OverlayPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onTap(entity) },
                        onLongClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("TACIT", entity.text))
                            copiedText = entity.text
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    if (copied) "Copied ✓" else entity.text,
                    fontFamily = Inter, fontWeight = FontWeight.Medium,
                    fontSize = 12.sp, color = OverlayPalette.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Low-confidence disambiguation: up to three tappable candidate rows + "None of these". */
@Composable
private fun CloseMatchesBody(
    state: OverlayUiState,
    onCommit: (Int) -> Unit,
    onNone: () -> Unit,
) {
    Column {
        Text(
            "CLOSE MATCHES",
            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 1.5.sp,
            color = OverlayPalette.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Not sure which one this was.",
            fontFamily = Inter, fontWeight = FontWeight.Medium,
            fontSize = 15.sp, color = OverlayPalette.ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Pick the note you played.",
            fontFamily = Inter, fontSize = 12.sp,
            color = OverlayPalette.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        state.candidates.take(3).forEachIndexed { i, c ->
            CandidateRow(c.meta) { onCommit(i) }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, OverlayPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .clickable { onNone() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "None of these",
                fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = OverlayPalette.accent,
            )
        }
    }
}

@Composable
private fun CandidateRow(meta: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            TacitIcons.Wave, contentDescription = null,
            tint = OverlayPalette.accent, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Voice note",
                fontFamily = Inter, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, color = OverlayPalette.ink,
            )
            Text(
                meta,
                fontFamily = Inter, fontSize = 12.sp,
                color = OverlayPalette.inkMuted,
            )
        }
    }
}

@Composable
private fun NoMatchBody() {
    Column {
        Text(
            "No confident match",
            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = OverlayPalette.accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Replay the note and TACIT listens again from the start.",
            fontFamily = Inter, fontSize = 13.sp,
            color = OverlayPalette.inkMuted,
        )
    }
}

@Composable
private fun NoticeBody(state: OverlayUiState) {
    Text(
        state.notice.orEmpty(),
        fontFamily = Inter, fontSize = 13.sp,
        color = OverlayPalette.inkMuted,
    )
}
