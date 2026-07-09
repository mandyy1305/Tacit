package com.example.antiwispr.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antiwispr.ActionEntity
import com.example.antiwispr.EntityExtractor
import com.example.antiwispr.Toggles
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.theme.Inter
import com.example.antiwispr.ui.theme.TacitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

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
    audioLevels: List<Float> = emptyList(),
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCopy: (String) -> Unit,
    onToggleChain: (Boolean) -> Unit = {},
    onPartVisible: (Int) -> Unit = {},
    onRequestSummary: () -> Unit = {},
    onEntityTap: (ActionEntity) -> Unit = {},
    onNoticeAction: (NoticeAction) -> Unit = {},
    onListenAgain: () -> Unit = {},
    onExitFinished: () -> Unit,
) {
    val enterState = remember { MutableTransitionState(false) }
    enterState.targetState = state.visible
    LaunchedEffect(enterState.currentState, enterState.targetState) {
        if (!enterState.currentState && !enterState.targetState) onExitFinished()
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
        // Swipe-up to dismiss: the gesture translates + fades the CARD CONTENT; the window itself
        // never moves or resizes (the frozen-window rule). Armed from the pinned header so it never
        // fights the scrollable content pane.
        var dragY by remember { mutableFloatStateOf(0f) }
        val dismissPx = with(LocalDensity.current) { 56.dp.toPx() }
        val dragModifier = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, delta ->
                    change.consume()
                    dragY = (dragY + delta).coerceIn(-1000f, 40f) // up freely; slight rubber-band down
                },
                onDragEnd = { if (dragY < -dismissPx) onClose() else dragY = 0f },
                onDragCancel = { dragY = 0f },
            )
        }
        val ctx = LocalContext.current
        // Hoisted so the top bar, transcript body, and coach-marks all agree on which chain part /
        // view is open. Resets to the matched part when a new result lands (chainPart changes).
        var current by remember(state.chainPart, state.chainCount) {
            mutableIntStateOf((state.chainPart - 1).coerceAtLeast(0))
        }
        var hasSwiped by remember(state.chainPart, state.chainCount) { mutableStateOf(false) }
        var view by remember(current) { mutableIntStateOf(0) } // 0 transcript, 1 summary (per note)
        // Coach-marks: persisted show-count per tooltip. A tip shows up to [coachCap] times OR until
        // the user does the action (count jumps to the cap) — a few reminders, never nagging forever.
        val coachCap = 3
        var shareCount by remember { mutableIntStateOf(Toggles.shareTipCount) }
        var swipeCount by remember { mutableIntStateOf(Toggles.swipeTipCount) }
        var aiCount by remember { mutableIntStateOf(Toggles.aiTipCount) }
        val onShareTracked: () -> Unit = {
            if (shareCount < coachCap) { shareCount = coachCap; Toggles.setShareTipCount(ctx, coachCap) }
            onShare()
        }
        val goToPart: (Int) -> Unit = { i ->
            val clamped = i.coerceIn(0, (state.chainCount - 1).coerceAtLeast(0))
            if (clamped != current) {
                current = clamped; hasSwiped = true
                if (swipeCount < coachCap) { swipeCount = coachCap; Toggles.setSwipeTipCount(ctx, coachCap) }
                onPartVisible(clamped)
            }
        }
        val onAiUsed: () -> Unit = {
            if (aiCount < coachCap) { aiCount = coachCap; Toggles.setAiTipCount(ctx, coachCap) }
        }
        // Which coach-mark is live now (gated by phase/state + the show-count cap; auto-hides).
        val curText = if (state.chainCount > 1) state.chainParts.getOrNull(current) else state.transcript
        val showShare = rememberCoachShown(
            state.phase == OverlayPhase.LISTENING && state.micBanner && shareCount < coachCap,
        ) { shareCount++; Toggles.setShareTipCount(ctx, shareCount) }
        val showSwipe = rememberCoachShown(
            state.phase == OverlayPhase.TRANSCRIPT && state.chainCount > 1 && !hasSwiped && swipeCount < coachCap,
        ) { swipeCount++; Toggles.setSwipeTipCount(ctx, swipeCount) }
        val showAi = rememberCoachShown(
            state.phase == OverlayPhase.TRANSCRIPT && view == 0 && (curText?.length ?: 0) > 220 && aiCount < coachCap,
        ) { aiCount++; Toggles.setAiTipCount(ctx, aiCount) }

        Column(
            Modifier
                .graphicsLayer {
                    translationY = dragY
                    alpha = (1f + dragY / (dismissPx * 3f)).coerceIn(0f, 1f)
                }
                .fillMaxWidth()
        ) {
            // TOP coach-mark lane — transparent room ABOVE the card. Share/swipe tooltips float here
            // (over WhatsApp, never covering the card's content); the window reserves matching room
            // so nothing clips. Bottom-aligned so the caret sits just above the card's top edge.
            Box(Modifier.fillMaxWidth().height(COACH_ROOM)) {
                if (showShare) {
                    CoachTip(
                        "Share your screen for surer matches.",
                        caretDown = true, caretAtStart = true,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 3.dp),
                    )
                }
                if (showSwipe) {
                    CoachTip(
                        "Swipe to read the next note.",
                        caretDown = true, caretAtStart = true,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 3.dp),
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxCardHeight)
                    .shadow(12.dp, corner, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(corner)
                    .background(Brush.verticalGradient(listOf(OverlayPalette.surfaceHi, OverlayPalette.surface)))
                    .border(1.dp, OverlayPalette.hairline, corner)
                    .padding(18.dp)
            ) {
                OverlayTopBar(state, current, onClose, onShareTracked, goToPart, dragModifier)
                Spacer(Modifier.height(12.dp))
                // Fade only, height snaps (`using null`) — the no-window-resize-animation rule.
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
                        OverlayPhase.LISTENING -> ListeningBody(state, audioLevels)
                        OverlayPhase.MATCHED -> MatchedBody(state)
                        OverlayPhase.TRANSCRIBING -> TranscribingBody(state)
                        OverlayPhase.TRANSCRIPT -> TranscriptBody(
                            state, current, view, { view = it }, goToPart, onCopy, onRequestSummary, onEntityTap, onAiUsed,
                        )
                        OverlayPhase.NO_MATCH -> NoMatchBody(state, onShareTracked)
                        OverlayPhase.NOTICE -> NoticeBody(state, onNoticeAction)
                    }
                }
            }

            // BOTTOM coach-mark lane — transparent room BELOW the card. The AI-summary tooltip floats
            // here, under its button, so it never covers the transcript. Top-aligned so the caret
            // sits just below the card's bottom edge.
            Box(Modifier.fillMaxWidth().height(COACH_ROOM)) {
                if (showAi) {
                    CoachTip(
                        "Long note. Tap to summarise.",
                        caretDown = false, caretAtStart = false,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 3.dp),
                    )
                }
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------

/**
 * Pinned top row. The left slot depends on phase/state — Share-screen control (on the mic),
 * TACIT wordmark, or the chain pager — with the close button always on the right. Height is pinned
 * so swapping slots never resizes the window. Carries the swipe-up-to-dismiss [dragModifier].
 */
@Composable
private fun OverlayTopBar(
    state: OverlayUiState,
    current: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onGoToPart: (Int) -> Unit,
    dragModifier: Modifier,
) {
    val listening = state.phase == OverlayPhase.LISTENING
    val chain = state.chainCount > 1 && !listening
    Row(
        dragModifier.fillMaxWidth().defaultMinSize(minHeight = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when {
                listening && state.micBanner -> ShareScreenControl(onShare)
                chain -> ChainPager(state.chainCount, current, state.chainParts, onGoToPart)
                else -> BrandWordmark()
            }
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close, contentDescription = "Dismiss",
                tint = OverlayPalette.inkMuted, modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val COACH_TIP_BG = Color(0xFF2A241C)

/** Transparent lane reserved above and below the card for floating coach-marks. The window carries
 *  matching room (OverlayComposeWindow.y is shifted up by this), so tooltips never clip. */
private val COACH_ROOM = 48.dp

/** Little triangle that connects a coach-mark to its anchor. [up] points up (bubble is below the
 *  anchor); [atStart] keeps it near the left, else near the right. */
@Composable
private fun Caret(up: Boolean, atStart: Boolean) {
    Canvas(
        Modifier
            .padding(start = if (atStart) 16.dp else 0.dp, end = if (!atStart) 16.dp else 0.dp)
            .width(12.dp).height(6.dp)
    ) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            if (up) { moveTo(0f, h); lineTo(w, h); lineTo(w / 2f, 0f) }
            else { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h) }
            close()
        }
        drawPath(p, COACH_TIP_BG)
    }
}

/** A coach-mark bubble (shadow + border + a caret pointing at its anchor) — a real tooltip. The
 *  column wraps to the bubble width; the caret aligns to whichever edge sits over the anchor. */
@Composable
private fun CoachTip(text: String, caretDown: Boolean, caretAtStart: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = if (caretAtStart) Alignment.Start else Alignment.End) {
        if (!caretDown) Caret(up = true, atStart = caretAtStart)
        Text(
            text,
            Modifier
                .shadow(6.dp, RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp))
                .background(COACH_TIP_BG)
                .border(1.dp, OverlayPalette.hairline, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            fontFamily = Inter, fontSize = 11.sp, lineHeight = 15.sp,
            color = OverlayPalette.ink,
        )
        if (caretDown) Caret(up = false, atStart = caretAtStart)
    }
}

/** True while a coach-mark should show: appears when [active] becomes true, auto-hides after a few
 *  seconds (then [onShown] counts the completed appearance), and vanishes immediately if [active]
 *  goes false first (the user acted, or the phase changed). */
@Composable
private fun rememberCoachShown(active: Boolean, onShown: () -> Unit): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) { shown = true; delay(4500); shown = false; onShown() } else shown = false
    }
    return shown
}

/** CHAIN wordlet for the result footer (chain-link glyph + burst size). */
@Composable
private fun ChainBadge(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(TacitIcons.Chain, contentDescription = null, modifier = Modifier.size(13.dp), tint = OverlayPalette.inkMuted)
        Spacer(Modifier.width(4.dp))
        Text(
            "CHAIN · $count",
            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 1.sp, color = OverlayPalette.inkMuted,
        )
    }
}

/** App wordmark used in the top-left when there's no pager or share control to show. */
@Composable
private fun BrandWordmark() {
    Text(
        "TACIT",
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 1.6.sp,
        color = OverlayPalette.accent,
    )
}

/** Screen-share control (top-left, mic fallback only): the old banner condensed to one tap. */
@Composable
private fun ShareScreenControl(onShare: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onShare)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TacitIcons.ScreenShare, contentDescription = null, tint = OverlayPalette.accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            "Share screen",
            fontFamily = Inter, fontWeight = FontWeight.Medium,
            fontSize = 13.sp, color = OverlayPalette.accent,
        )
    }
}

/** Instagram-stories pager over the chain: the open note is an amber bar, transcribed notes are
 *  filled dots, notes not read yet are faint dots. Tap a node to jump. */
@Composable
private fun ChainPager(count: Int, current: Int, parts: List<String?>, onGoToPart: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val isCur = i == current
            val transcribed = parts.getOrNull(i) != null
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (isCur) 22.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            isCur -> OverlayPalette.accent
                            transcribed -> OverlayPalette.accent.copy(alpha = 0.6f)
                            else -> OverlayPalette.inkFaint
                        }
                    )
                    .clickable { onGoToPart(i) },
            )
        }
    }
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Frame-rate-independent asymmetric follower: chases a rising target fast, a falling one slowly. */
private fun follow(cur: Float, target: Float, dt: Float, tauA: Float, tauR: Float): Float {
    val tau = if (target > cur) tauA else tauR
    return cur + (target - cur) * (1f - exp(-dt / tau))
}

/** Fixed Gaussian lens: 1 at the centre bar, tapering to [sMin] at the edges. */
private fun lensWeight(i: Int, n: Int, sigma: Float, sMin: Float): Float {
    val c = (n - 1) / 2f
    return sMin + (1f - sMin) * exp(-((i - c) * (i - c)) / (2f * sigma * sigma))
}

/**
 * Live listening meter — "Center-Out Bloom" (tuned in the waveform lab). The raw ~10 Hz capture
 * amplitude is conditioned (soft gate → falling-peak AGC → gamma → soft-knee) into a level L,
 * injected at the centre bar and diffused OUTWARD through an overshoot-free one-pole cascade, then
 * shaped by a fixed Gaussian lens. Rises fast (70 ms) and falls slowly (400 ms), and settles to a
 * centred row of gently breathing dots. A withFrameNanos loop drives it at display rate so the
 * 10 Hz feed animates buttery-smooth. Stationary bars; no scrolling.
 */
@Composable
private fun ListeningWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val n = 31
    val c = (n - 1) / 2
    val ring = remember { FloatArray(c + 1) }   // cascade level per distance-from-centre
    val agc = remember { floatArrayOf(0f) }     // falling-peak AGC state
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val a = levels.lastOrNull() ?: 0f                      // latest raw RMS (held between pushes)
                // conditioning — calibrated to the phone's real capture scale (measured on-device:
                // speech RMS ~0.007–0.11, silence/pauses <0.003). The lab's laptop-mic scale ran
                // ~5–10x hotter, which is why its 0.02 gate flat-lined normal-volume speech here.
                val floor = 0.0025f
                val gate = smoothstep(floor, 0.007f, a)               // open by ~0.007 so normal speech shows
                agc[0] = maxOf(a, agc[0] - 0.10f * dt)                // gentle AGC: soft + loud both fill
                val aRef = maxOf(0.015f, agc[0])                      // low reference so quiet speech normalizes up
                val p = ((a - floor) / (aRef - floor)).coerceIn(0f, 1f)
                var lc = p.pow(0.60f)                                  // gamma — gentle onset
                if (lc > 0.75f) lc = 0.75f + 0.25f * tanh((lc - 0.75f) / 0.25f)  // soft-knee ceiling
                val level = gate * lc
                // centre-out cascade: master follower at the centre, diffusing outward 60 ms/ring
                ring[0] = follow(ring[0], level, dt, 0.070f, 0.400f)
                for (d in 1..c) ring[d] += (ring[d - 1] - ring[d]) * (1f - exp(-dt / 0.060f))
                tick = now                                            // drive the redraw
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(46.dp)) {
        val t = tick / 1_000_000_000f                                 // observed read → redraw each frame
        val bw = 4.dp.toPx(); val gap = 4.dp.toPx()
        val clusterW = n * bw + (n - 1) * gap
        val x0 = (size.width - clusterW) / 2f
        val cy = size.height / 2f
        val dot = bw
        val maxH = size.height * 0.92f
        val sigma = n * 0.40f
        // faint warm bloom behind the centre, following the centre level
        val centre = ring[0]
        if (centre > 0.02f) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(OverlayPalette.accent.copy(alpha = 0.18f * centre), Color.Transparent),
                    center = Offset(size.width / 2f, cy),
                    radius = size.width * 0.42f,
                )
            )
        }
        for (i in 0 until n) {
            val d = abs(i - c)
            val w = lensWeight(i, n, sigma, 0.15f)
            val act = (ring[d] * w).coerceIn(0f, 1f)
            val idle = 0.10f * dot * sin(t * 2f * PI.toFloat() * 0.25f + i * 0.3f)
            val shim = 1f + 0.12f * act * sin(t * 1.5f + i * 0.5f)
            var h = dot + act * (maxH - dot) * shim + idle
            if (h < dot) h = dot
            drawRoundRect(
                OverlayPalette.accent,
                topLeft = Offset(x0 + i * (bw + gap), cy - h / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
}

@Composable
private fun ListeningBody(state: OverlayUiState, audioLevels: List<Float>) {
    Column {
        ListeningWaveform(audioLevels)
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = state.statusLine,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
            label = "status",
        ) { line ->
            Text(
                line,
                fontFamily = Inter, fontSize = 13.sp,
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (source == "cloud") {
            Icon(
                TacitIcons.Cloud,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = OverlayPalette.inkMuted,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 1.sp,
            color = OverlayPalette.inkMuted,
        )
    }
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
    current: Int,
    view: Int,
    onViewChange: (Int) -> Unit,
    onGoToPart: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onRequestSummary: () -> Unit,
    onEntityTap: (ActionEntity) -> Unit,
    onAiUsed: () -> Unit,
) {
    val isChain = state.chainCount > 1
    val partText = if (isChain) state.chainParts.getOrNull(current) else state.transcript
    Column {
        // Hero. Slides horizontally when the open chain part changes (swipe direction), and fades
        // when toggling transcript<->summary. Height snaps (`using null`) — no window resize.
        AnimatedContent(
            targetState = current to view,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = {
                if (initialState.first != targetState.first) {
                    val fwd = targetState.first > initialState.first
                    (slideInHorizontally(tween(240)) { w -> if (fwd) w else -w } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(200)) { w -> if (fwd) -w else w } + fadeOut(tween(140))) using null
                } else {
                    (fadeIn(tween(180)) togetherWith fadeOut(tween(90))) using null
                }
            },
            label = "note",
        ) { (cur, v) ->
            val txt = if (isChain) state.chainParts.getOrNull(cur) else state.transcript
            Box(Modifier.verticalScroll(rememberScrollState())) {
                if (v == 0) TranscriptHero(txt, cur, isChain, onCopy, onGoToPart)
                else SummaryPane(state, onEntityTap)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Footer: provenance (+ CHAIN badge) on the left, AI-summary toggle on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Copy confirmation is a quiet text swap (no background colour shift).
            if (state.copied) {
                Text(
                    "COPIED ✓",
                    fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp, letterSpacing = 1.sp,
                    color = OverlayPalette.mint,
                )
            } else {
                SourceMark(state.source)
                if (isChain) {
                    Spacer(Modifier.width(10.dp))
                    ChainBadge(state.chainCount)
                }
            }
            Spacer(Modifier.weight(1f))
            AISummaryButton(
                active = view == 1,
                nudge = view == 0 && (partText?.length ?: 0) > 220,
                onClick = {
                    if (view == 0) {
                        onViewChange(1)
                        onAiUsed()
                        // Lazy: first open with nothing armed kicks generation for THIS note.
                        if (state.summaryState == SummaryState.NONE) onRequestSummary()
                    } else {
                        onViewChange(0)
                    }
                },
            )
        }
    }
}

/** The transcript hero. Tap to copy; horizontal swipe moves across a chain. A null part is the
 *  lazy "transcribing this note" state; a bracket string is a per-part notice. */
@Composable
private fun TranscriptHero(
    text: String?,
    current: Int,
    isChain: Boolean,
    onCopy: (String) -> Unit,
    onGoToPart: (Int) -> Unit,
) {
    if (text == null) {
        Column {
            TranscribingShimmer()
            Spacer(Modifier.height(8.dp))
            Text(
                "Transcribing note ${current + 1}…",
                fontFamily = Inter, fontSize = 11.sp,
                color = OverlayPalette.inkFaint,
            )
        }
        return
    }
    if (text.startsWith("[")) {
        Text(
            humanizeNotice(text),
            fontFamily = Inter, fontSize = 13.sp,
            color = OverlayPalette.inkMuted,
        )
        return
    }
    val swipe = if (isChain) {
        Modifier.pointerInput(current) {
            var dx = 0f
            detectHorizontalDragGestures(
                onDragStart = { dx = 0f },
                onHorizontalDrag = { _, d -> dx += d },
                onDragEnd = {
                    val threshold = 48.dp.toPx()
                    when {
                        dx <= -threshold -> onGoToPart(current + 1)
                        dx >= threshold -> onGoToPart(current - 1)
                    }
                },
            )
        }
    } else Modifier
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(text) }
            .then(swipe)
            .padding(vertical = 4.dp),
        fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp,
        color = OverlayPalette.ink,
    )
}

/** AI-summary toggle (footer right). Highlighted when the summary is showing; accent-tinted as a
 *  quiet nudge on long transcripts. */
@Composable
private fun AISummaryButton(active: Boolean, nudge: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(
                if (active) Modifier
                    .background(OverlayPalette.accent.copy(alpha = 0.22f))
                    .border(1.dp, OverlayPalette.accent, RoundedCornerShape(9.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TacitIcons.Summary,
            contentDescription = if (active) "Show transcript" else "AI summary",
            tint = if (active || nudge) OverlayPalette.accent else OverlayPalette.inkMuted,
            modifier = Modifier.size(18.dp),
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

/**
 * No confident match. Plain recovery: just a "replay the note" prompt. If the screen isn't being
 * shared (we were on the mic), add a nudge + a clear Share-screen button for better accuracy.
 * No "listen again" / "close" buttons, no candidate picker.
 */
@Composable
private fun NoMatchBody(state: OverlayUiState, onShare: () -> Unit) {
    Column {
        Text(
            if (state.micBanner) "Share your screen for surer matches." else "No match yet.",
            fontFamily = Inter, fontWeight = FontWeight.Medium,
            fontSize = 15.sp, color = OverlayPalette.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Replay the voice note and TACIT listens again.",
            fontFamily = Inter, fontSize = 13.sp,
            color = OverlayPalette.inkMuted,
        )
        if (state.micBanner) {
            Spacer(Modifier.height(14.dp))
            CardActionButton("Share screen", solid = true, onClick = onShare)
        }
    }
}

@Composable
private fun NoticeBody(state: OverlayUiState, onAction: (NoticeAction) -> Unit) {
    Column {
        Text(
            state.notice.orEmpty(),
            fontFamily = Inter, fontSize = 13.sp,
            color = OverlayPalette.inkMuted,
        )
        val label = when (state.noticeAction) {
            NoticeAction.FINISH_SETUP -> "Finish setup"
            NoticeAction.TRY_AGAIN -> "Try again"
            NoticeAction.SHARE_SCREEN -> "Share screen"
            NoticeAction.LISTEN_AGAIN -> "Listen again"
            NoticeAction.NONE -> null
        }
        if (label != null) {
            Spacer(Modifier.height(14.dp))
            CardActionButton(label, solid = true) { onAction(state.noticeAction) }
        }
    }
}

/** Compact in-card recovery button: solid amber primary, or amber-outline ghost. */
@Composable
private fun CardActionButton(label: String, solid: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (solid) Modifier.background(OverlayPalette.accentDeep)
                else Modifier.border(1.dp, OverlayPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (solid) Color(0xFFFFF3E9) else OverlayPalette.accent,
        )
    }
}
