package app.echo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun MenuGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = d * .30f
        for (i in -1..1) {
            val y = cy + i * d * .20f
            drawLine(color, Offset(cx - half, y), Offset(cx + half, y), strokeWidth = d * .105f, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun BackGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val apexX = cx - d * .09f
        val w = d * .11f
        drawLine(color, Offset(apexX, cy), Offset(apexX + d * .37f, cy - d * .29f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(apexX, cy), Offset(apexX + d * .37f, cy + d * .29f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
internal fun PlusGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = d * .38f
        val w = d * .115f
        drawLine(color, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
internal fun CloseGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = d * .31f
        val w = d * .10f
        drawLine(color, Offset(cx - r, cy - r), Offset(cx + r, cy + r), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - r, cy + r), Offset(cx + r, cy - r), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
internal fun MoreGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (i in -1..1) drawCircle(color, radius = d * .085f, center = Offset(cx + i * d * .24f, cy))
    }
}

@Composable
internal fun SendGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val w = d * .13f
        val top = cy - d * .31f
        val bottom = cy + d * .31f
        drawLine(color, Offset(cx, bottom), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - d * .24f, cy - d * .06f), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx + d * .24f, cy - d * .06f), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
internal fun PauseGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val d = size.minDimension
        val barW = d * .17f
        val barH = d * .54f
        val gap = d * .16f
        val cy = size.height / 2f
        val left = size.width / 2f - gap / 2f - barW
        val right = size.width / 2f + gap / 2f
        val radius = CornerRadius(barW / 2f)
        drawRoundRect(color, topLeft = Offset(left, cy - barH / 2f), size = Size(barW, barH), cornerRadius = radius)
        drawRoundRect(color, topLeft = Offset(right, cy - barH / 2f), size = Size(barW, barH), cornerRadius = radius)
    }
}

@Composable
internal fun MicGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * .09f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * .34f, h * .08f),
            size = Size(w * .32f, h * .44f),
            cornerRadius = CornerRadius(w * .16f, w * .16f),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * .2f, h * .2f),
            size = Size(w * .6f, h * .52f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(w * .5f, h * .72f),
            end = Offset(w * .5f, h * .86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * .32f, h * .88f),
            end = Offset(w * .68f, h * .88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
