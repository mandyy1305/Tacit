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
}
