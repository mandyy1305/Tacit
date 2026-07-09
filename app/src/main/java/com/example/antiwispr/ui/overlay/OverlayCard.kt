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
    onCommitCandidate: (Int) -> Unit = {},
    onNoneOfThese: () -> Unit = {},
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
        Column(
            Modifier
                .graphicsLayer {
                    translationY = dragY
                    alpha = (1f + dragY / (dismissPx * 3f)).coerceIn(0f, 1f)
                }
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .shadow(12.dp, corner, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(corner)
                .background(Brush.verticalGradient(listOf(OverlayPalette.surfaceHi, OverlayPalette.surface)))
                .border(1.dp, OverlayPalette.hairline, corner)
                .padding(18.dp)
        ) {
            // Hoisted so the top-bar pager and the transcript body agree on which chain part is
            // open; resets to the matched part whenever a new result lands (chainPart changes).
            var current by remember(state.chainPart, state.chainCount) {
                mutableIntStateOf((state.chainPart - 1).coerceAtLeast(0))
            }
            var hasSwiped by remember(state.chainPart, state.chainCount) { mutableStateOf(false) }
            val goToPart: (Int) -> Unit = { i ->
                val clamped = i.coerceIn(0, (state.chainCount - 1).coerceAtLeast(0))
                if (clamped != current) { current = clamped; hasSwiped = true; onPartVisible(clamped) }
            }

            OverlayTopBar(state, current, hasSwiped, onClose, onShare, goToPart, dragModifier)

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
                    OverlayPhase.LISTENING -> ListeningBody(state, audioLevels)
                    OverlayPhase.MATCHED -> MatchedBody(state)
                    OverlayPhase.TRANSCRIBING -> TranscribingBody(state)
                    OverlayPhase.TRANSCRIPT -> TranscriptBody(state, current, goToPart, onCopy, onRequestSummary, onEntityTap)
                    OverlayPhase.CLOSE_MATCHES -> CloseMatchesBody(state, onCommitCandidate, onNoneOfThese)
                    OverlayPhase.NO_MATCH -> NoMatchBody(onListenAgain, onClose)
                    OverlayPhase.NOTICE -> NoticeBody(state, onNoticeAction)
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
    hasSwiped: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onGoToPart: (Int) -> Unit,
    dragModifier: Modifier,
) {
    val listening = state.phase == OverlayPhase.LISTENING
    val chain = state.chainCount > 1 && !listening
    Column(dragModifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 30.dp),
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
        // Teach the swipe once, until the user moves across the chain.
        if (chain && !hasSwiped) {
            Text(
                "Chain of ${state.chainCount} notes. Swipe to read the next.",
                fontFamily = Inter, fontSize = 11.sp, color = OverlayPalette.inkFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
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
    onGoToPart: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onRequestSummary: () -> Unit,
    onEntityTap: (ActionEntity) -> Unit,
) {
    val isChain = state.chainCount > 1
    // The note currently shown: a chain part (lazy — null while transcribing) or the single note.
    val partText = if (isChain) state.chainParts.getOrNull(current) else state.transcript
    // 0 = transcript, 1 = summary. Resets to transcript whenever the open note changes.
    var view by remember(current) { mutableIntStateOf(0) }
    Column {
        // Hero: transcript (tap to copy, swipe to move across the chain) or the summary pane.
        // Fade only, size snaps (`using null`) — the no-window-resize-animation rule.
        AnimatedContent(
            targetState = view,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) using null },
            label = "view",
        ) { v ->
            Box(Modifier.verticalScroll(rememberScrollState())) {
                if (v == 0) {
                    TranscriptHero(partText, current, isChain, onCopy, onGoToPart)
                } else {
                    SummaryPane(state, onEntityTap)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Footer: provenance on the left, AI-summary toggle on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Copy confirmation is a quiet text swap (no background colour shift): the card bg
            // stays the same dark espresso throughout.
            if (state.copied) {
                Text(
                    "COPIED ✓",
                    fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp, letterSpacing = 1.sp,
                    color = OverlayPalette.mint,
                )
            } else {
                SourceMark(state.source)
            }
            Spacer(Modifier.weight(1f))
            AISummaryButton(
                active = view == 1,
                nudge = view == 0 && (partText?.length ?: 0) > 220,
                onClick = {
                    if (view == 0) {
                        view = 1
                        // Lazy: first open with nothing armed kicks generation for THIS note.
                        if (state.summaryState == SummaryState.NONE) onRequestSummary()
                    } else {
                        view = 0
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
private fun NoMatchBody(onListenAgain: () -> Unit, onClose: () -> Unit) {
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
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardActionButton("Listen again", solid = true, onClick = onListenAgain)
            CardActionButton("Close", solid = false, onClick = onClose)
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
