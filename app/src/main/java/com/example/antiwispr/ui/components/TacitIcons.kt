package com.example.antiwispr.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn glyphs for the few icons material-icons-core doesn't ship
 * (we deliberately avoid material-icons-extended — release build has no shrinker).
 * All 24x24, stroke-styled to sit next to the core set.
 */
object TacitIcons {

    /** Microphone (outline). */
    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.mic", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero
            ) {
                // Capsule body
                moveTo(12f, 14.5f)
                curveTo(13.93f, 14.5f, 15.5f, 12.93f, 15.5f, 11f)
                lineTo(15.5f, 5.5f)
                curveTo(15.5f, 3.57f, 13.93f, 2f, 12f, 2f)
                curveTo(10.07f, 2f, 8.5f, 3.57f, 8.5f, 5.5f)
                lineTo(8.5f, 11f)
                curveTo(8.5f, 12.93f, 10.07f, 14.5f, 12f, 14.5f)
                close()
                // Inner cutout
                moveTo(10f, 5.5f)
                curveTo(10f, 4.4f, 10.9f, 3.5f, 12f, 3.5f)
                curveTo(13.1f, 3.5f, 14f, 4.4f, 14f, 5.5f)
                lineTo(14f, 11f)
                curveTo(14f, 12.1f, 13.1f, 13f, 12f, 13f)
                curveTo(10.9f, 13f, 10f, 12.1f, 10f, 11f)
                close()
                // Cradle + stand
                moveTo(17.75f, 11f)
                curveTo(17.75f, 10.59f, 17.41f, 10.25f, 17f, 10.25f)
                curveTo(16.59f, 10.25f, 16.25f, 10.59f, 16.25f, 11f)
                curveTo(16.25f, 13.35f, 14.35f, 15.25f, 12f, 15.25f)
                curveTo(9.65f, 15.25f, 7.75f, 13.35f, 7.75f, 11f)
                curveTo(7.75f, 10.59f, 7.41f, 10.25f, 7f, 10.25f)
                curveTo(6.59f, 10.25f, 6.25f, 10.59f, 6.25f, 11f)
                curveTo(6.25f, 13.93f, 8.44f, 16.35f, 11.25f, 16.7f)
                lineTo(11.25f, 20f)
                lineTo(8.5f, 20f)
                curveTo(8.09f, 20f, 7.75f, 20.34f, 7.75f, 20.75f)
                curveTo(7.75f, 21.16f, 8.09f, 21.5f, 8.5f, 21.5f)
                lineTo(15.5f, 21.5f)
                curveTo(15.91f, 21.5f, 16.25f, 21.16f, 16.25f, 20.75f)
                curveTo(16.25f, 20.34f, 15.91f, 20f, 15.5f, 20f)
                lineTo(12.75f, 20f)
                lineTo(12.75f, 16.7f)
                curveTo(15.56f, 16.35f, 17.75f, 13.93f, 17.75f, 11f)
                close()
            }
        }.build()
    }

    /** Copy-to-clipboard (two offset rounded rectangles, stroked). */
    val Copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.copy", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Front sheet
                moveTo(9f, 9.5f)
                lineTo(17.5f, 9.5f)
                curveTo(18.05f, 9.5f, 18.5f, 9.95f, 18.5f, 10.5f)
                lineTo(18.5f, 19f)
                curveTo(18.5f, 19.55f, 18.05f, 20f, 17.5f, 20f)
                lineTo(9f, 20f)
                curveTo(8.45f, 20f, 8f, 19.55f, 8f, 19f)
                lineTo(8f, 10.5f)
                curveTo(8f, 9.95f, 8.45f, 9.5f, 9f, 9.5f)
                close()
                // Back sheet
                moveTo(5.5f, 14.5f)
                curveTo(4.95f, 14.5f, 4.5f, 14.05f, 4.5f, 13.5f)
                lineTo(4.5f, 5f)
                curveTo(4.5f, 4.45f, 4.95f, 4f, 5.5f, 4f)
                lineTo(14f, 4f)
                curveTo(14.55f, 4f, 15f, 4.45f, 15f, 5f)
            }
        }.build()
    }

    /** Cloud (filled) — the TACIT Cloud provenance mark. Reads clean at badge sizes. */
    val Cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.cloud", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero
            ) {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
                lineTo(19f, 20f)
                curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
                curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
                close()
            }
        }.build()
    }

    /** Chain link (filled) — the CHAIN badge mark for grouped-note bursts. */
    val Chain: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.chain", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero
            ) {
                // Left hook
                moveTo(3.9f, 12f)
                curveTo(3.9f, 10.29f, 5.29f, 8.9f, 7f, 8.9f)
                lineTo(11f, 8.9f)
                lineTo(11f, 7f)
                lineTo(7f, 7f)
                curveTo(4.24f, 7f, 2f, 9.24f, 2f, 12f)
                curveTo(2f, 14.76f, 4.24f, 17f, 7f, 17f)
                lineTo(11f, 17f)
                lineTo(11f, 15.1f)
                lineTo(7f, 15.1f)
                curveTo(5.29f, 15.1f, 3.9f, 13.71f, 3.9f, 12f)
                close()
                // Middle bar
                moveTo(8f, 13f)
                lineTo(16f, 13f)
                lineTo(16f, 11f)
                lineTo(8f, 11f)
                close()
                // Right hook
                moveTo(17f, 7f)
                lineTo(13f, 7f)
                lineTo(13f, 8.9f)
                lineTo(17f, 8.9f)
                curveTo(18.71f, 8.9f, 20.1f, 10.29f, 20.1f, 12f)
                curveTo(20.1f, 13.71f, 18.71f, 15.1f, 17f, 15.1f)
                lineTo(13f, 15.1f)
                lineTo(13f, 17f)
                lineTo(17f, 17f)
                curveTo(19.76f, 17f, 22f, 14.76f, 22f, 12f)
                curveTo(22f, 9.24f, 19.76f, 7f, 17f, 7f)
                close()
            }
        }.build()
    }

    /** Sound wave — five vertical bars, the TACIT listening motif. */
    val Wave: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.wave", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4f, 10f); lineTo(4f, 14f)
                moveTo(8f, 7f); lineTo(8f, 17f)
                moveTo(12f, 4f); lineTo(12f, 20f)
                moveTo(16f, 7f); lineTo(16f, 17f)
                moveTo(20f, 10f); lineTo(20f, 14f)
            }
        }.build()
    }

    /** AI summary — three text lines with a sparkle bottom-right (the "summarize" glyph). */
    val Summary: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.summary", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            // Text lines (stroked)
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 11f); lineTo(18f, 11f)
                moveTo(4f, 16f); lineTo(11f, 16f)
            }
            // Sparkle (filled 4-point star, bottom-right)
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(18.5f, 13.3f)
                curveTo(18.7f, 15.2f, 19.6f, 16.1f, 21.5f, 16.3f)
                curveTo(19.6f, 16.5f, 18.7f, 17.4f, 18.5f, 19.3f)
                curveTo(18.3f, 17.4f, 17.4f, 16.5f, 15.5f, 16.3f)
                curveTo(17.4f, 16.1f, 18.3f, 15.2f, 18.5f, 13.3f)
                close()
            }
        }.build()
    }

    /** Screen-share — a monitor outline with a stand (the Share-screen control). */
    val ScreenShare: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.screenshare", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Screen (rounded rect)
                moveTo(4.5f, 4f)
                lineTo(19.5f, 4f)
                curveTo(20.6f, 4f, 21.5f, 4.9f, 21.5f, 6f)
                lineTo(21.5f, 15f)
                curveTo(21.5f, 16.1f, 20.6f, 17f, 19.5f, 17f)
                lineTo(4.5f, 17f)
                curveTo(3.4f, 17f, 2.5f, 16.1f, 2.5f, 15f)
                lineTo(2.5f, 6f)
                curveTo(2.5f, 4.9f, 3.4f, 4f, 4.5f, 4f)
                close()
                // Stand
                moveTo(12f, 17f); lineTo(12f, 21f)
                moveTo(8.5f, 21f); lineTo(15.5f, 21f)
            }
        }.build()
    }

    /** House (outline) — the Home tab. */
    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.home", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Roof
                moveTo(4f, 10.5f)
                lineTo(12f, 4f)
                lineTo(20f, 10.5f)
                // Walls
                moveTo(6f, 9.2f)
                lineTo(6f, 20f)
                lineTo(18f, 20f)
                lineTo(18f, 9.2f)
            }
        }.build()
    }

    /** A card over a shrinking stack — the Library tab. */
    val Library: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.library", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Top card
                moveTo(6f, 4.5f)
                lineTo(16f, 4.5f)
                curveTo(16.55f, 4.5f, 17f, 4.95f, 17f, 5.5f)
                lineTo(17f, 15f)
                curveTo(17f, 15.55f, 16.55f, 16f, 16f, 16f)
                lineTo(6f, 16f)
                curveTo(5.45f, 16f, 5f, 15.55f, 5f, 15f)
                lineTo(5f, 5.5f)
                curveTo(5f, 4.95f, 5.45f, 4.5f, 6f, 4.5f)
                close()
                // Stack beneath
                moveTo(7.5f, 18.5f)
                lineTo(16.5f, 18.5f)
                moveTo(9f, 21f)
                lineTo(15f, 21f)
            }
        }.build()
    }

    /** Speech bubble with a tail — the AI Ask tab. */
    val Ask: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.ask", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 5f)
                lineTo(16f, 5f)
                curveTo(17.66f, 5f, 19f, 6.34f, 19f, 8f)
                lineTo(19f, 12f)
                curveTo(19f, 13.66f, 17.66f, 15f, 16f, 15f)
                lineTo(9f, 15f)
                lineTo(6f, 18.5f)
                lineTo(6.9f, 15f)
                curveTo(5.85f, 14.9f, 5f, 13.55f, 5f, 12f)
                lineTo(5f, 8f)
                curveTo(5f, 6.34f, 6.34f, 5f, 8f, 5f)
                close()
            }
        }.build()
    }

    /** Power symbol (arc + break) — the medallion's dormant/off face. */
    val Power: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.power", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Vertical break
                moveTo(12f, 3.5f)
                lineTo(12f, 11.5f)
                // Ring, open at the top
                moveTo(8.4f, 6.7f)
                curveTo(6.2f, 8.2f, 4.8f, 10.7f, 4.8f, 13.6f)
                curveTo(4.8f, 17.6f, 8f, 20.8f, 12f, 20.8f)
                curveTo(16f, 20.8f, 19.2f, 17.6f, 19.2f, 13.6f)
                curveTo(19.2f, 10.7f, 17.8f, 8.2f, 15.6f, 6.7f)
            }
        }.build()
    }

    /** Upward arrow — the Ask composer's send action. */
    val Send: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.send", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 20f)
                lineTo(12f, 5f)
                moveTo(6f, 11f)
                lineTo(12f, 5f)
                lineTo(18f, 11f)
            }
        }.build()
    }

    /** Sort — a down arrow beside decreasing lines (the Library sort control). */
    val Sort: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.sort", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Down arrow (left)
                moveTo(6f, 5f)
                lineTo(6f, 18.4f)
                moveTo(3f, 15.2f)
                lineTo(6f, 18.4f)
                lineTo(9f, 15.2f)
                // Decreasing lines (right)
                moveTo(12f, 7f); lineTo(21f, 7f)
                moveTo(12f, 12f); lineTo(18.5f, 12f)
                moveTo(12f, 17f); lineTo(16f, 17f)
            }
        }.build()
    }

    /** Plain vertical arrow — the sort direction toggle (rotate 180 for descending). */
    val ArrowUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.arrowup", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 19f)
                lineTo(12f, 5f)
                moveTo(6.5f, 10.5f)
                lineTo(12f, 5f)
                lineTo(17.5f, 10.5f)
            }
        }.build()
    }

    /** Two people (outline) — the group/chat mark on a note card. */
    val People: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.people", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Front person: head + shoulders
                moveTo(14f, 5.9f)
                curveTo(15.44f, 5.9f, 16.6f, 7.06f, 16.6f, 8.5f)
                curveTo(16.6f, 9.94f, 15.44f, 11.1f, 14f, 11.1f)
                curveTo(12.56f, 11.1f, 11.4f, 9.94f, 11.4f, 8.5f)
                curveTo(11.4f, 7.06f, 12.56f, 5.9f, 14f, 5.9f)
                close()
                moveTo(9.5f, 18.5f)
                curveTo(9.5f, 15.6f, 11.5f, 13.2f, 14f, 13.2f)
                curveTo(16.5f, 13.2f, 18.5f, 15.6f, 18.5f, 18.5f)
                // Back person: head + partial shoulder
                moveTo(7.5f, 5.9f)
                curveTo(8.66f, 5.9f, 9.6f, 6.84f, 9.6f, 8f)
                curveTo(9.6f, 9.16f, 8.66f, 10.1f, 7.5f, 10.1f)
                curveTo(6.34f, 10.1f, 5.4f, 9.16f, 5.4f, 8f)
                curveTo(5.4f, 6.84f, 6.34f, 5.9f, 7.5f, 5.9f)
                close()
                moveTo(3.6f, 17.8f)
                curveTo(3.6f, 15.2f, 5.2f, 13f, 7.4f, 12.5f)
            }
        }.build()
    }

    /** Calendar (outline) — the note-card date mark. */
    val Calendar: ImageVector by lazy {
        ImageVector.Builder(
            name = "tacit.calendar", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Body
                moveTo(6f, 5.5f)
                lineTo(18f, 5.5f)
                curveTo(19.1f, 5.5f, 20f, 6.4f, 20f, 7.5f)
                lineTo(20f, 18f)
                curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
                lineTo(6f, 20f)
                curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
                lineTo(4f, 7.5f)
                curveTo(4f, 6.4f, 4.9f, 5.5f, 6f, 5.5f)
                close()
                // Header rule
                moveTo(4f, 9.5f)
                lineTo(20f, 9.5f)
                // Hangers
                moveTo(8f, 3.5f); lineTo(8f, 6.8f)
                moveTo(16f, 3.5f); lineTo(16f, 6.8f)
            }
        }.build()
    }
}
