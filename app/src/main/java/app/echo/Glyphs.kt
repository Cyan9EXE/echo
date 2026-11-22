package app.echo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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

@Composable
internal fun ThinkGlyph(color: Color, modifier: Modifier = Modifier) {
    // 官方 App res/drawable-anydpi/ic_think_outline_16.xml 原样转写（16×16 viewport）。
    val body = remember {
        Path().apply {
            fillType = PathFillType.EvenOdd
                moveTo(9.936f, 1.307f)
                cubicTo(11.54f, 0.73f, 13.239f, 0.645f, 14.297f, 1.703f)
                cubicTo(15.355f, 2.761f, 15.27f, 4.461f, 14.693f, 6.065f)
                cubicTo(14.467f, 6.691f, 14.154f, 7.342f, 13.763f, 8.0f)
                cubicTo(14.154f, 8.658f, 14.467f, 9.309f, 14.693f, 9.935f)
                cubicTo(15.27f, 11.539f, 15.355f, 13.239f, 14.297f, 14.297f)
                cubicTo(13.239f, 15.355f, 11.54f, 15.269f, 9.936f, 14.692f)
                cubicTo(9.31f, 14.467f, 8.658f, 14.153f, 8.0f, 13.763f)
                cubicTo(7.343f, 14.153f, 6.692f, 14.467f, 6.066f, 14.692f)
                cubicTo(4.462f, 15.27f, 2.762f, 15.355f, 1.703f, 14.297f)
                cubicTo(0.645f, 13.238f, 0.731f, 11.539f, 1.308f, 9.935f)
                cubicTo(1.533f, 9.309f, 1.845f, 8.658f, 2.236f, 8.0f)
                cubicTo(1.845f, 7.342f, 1.533f, 6.691f, 1.308f, 6.065f)
                cubicTo(0.731f, 4.461f, 0.645f, 2.761f, 1.703f, 1.703f)
                cubicTo(2.762f, 0.645f, 4.462f, 0.73f, 6.066f, 1.307f)
                cubicTo(6.692f, 1.533f, 7.343f, 1.845f, 8.0f, 2.235f)
                cubicTo(8.658f, 1.845f, 9.31f, 1.533f, 9.936f, 1.307f)
                close()
                moveTo(3.11f, 9.307f)
                cubicTo(2.914f, 9.685f, 2.753f, 10.055f, 2.625f, 10.409f)
                cubicTo(2.091f, 11.893f, 2.243f, 12.858f, 2.693f, 13.307f)
                cubicTo(3.143f, 13.757f, 4.107f, 13.909f, 5.591f, 13.375f)
                cubicTo(5.945f, 13.247f, 6.314f, 13.084f, 6.693f, 12.889f)
                cubicTo(6.041f, 12.402f, 5.395f, 11.845f, 4.775f, 11.225f)
                cubicTo(4.154f, 10.605f, 3.596f, 9.959f, 3.11f, 9.307f)
                close()
                moveTo(12.889f, 9.307f)
                cubicTo(12.403f, 9.959f, 11.846f, 10.605f, 11.226f, 11.225f)
                cubicTo(10.606f, 11.846f, 9.959f, 12.402f, 9.307f, 12.889f)
                cubicTo(9.686f, 13.084f, 10.055f, 13.247f, 10.41f, 13.375f)
                cubicTo(11.894f, 13.909f, 12.858f, 13.757f, 13.308f, 13.307f)
                cubicTo(13.758f, 12.858f, 13.91f, 11.893f, 13.375f, 10.409f)
                cubicTo(13.248f, 10.055f, 13.085f, 9.685f, 12.889f, 9.307f)
                close()
                moveTo(7.999f, 3.897f)
                cubicTo(7.253f, 4.409f, 6.495f, 5.035f, 5.765f, 5.765f)
                cubicTo(5.035f, 6.494f, 4.409f, 7.252f, 3.898f, 7.999f)
                cubicTo(4.409f, 8.746f, 5.035f, 9.505f, 5.765f, 10.235f)
                cubicTo(6.495f, 10.965f, 7.253f, 11.59f, 7.999f, 12.101f)
                cubicTo(8.746f, 11.59f, 9.506f, 10.965f, 10.236f, 10.235f)
                cubicTo(10.966f, 9.505f, 11.59f, 8.746f, 12.102f, 7.999f)
                cubicTo(11.59f, 7.252f, 10.965f, 6.494f, 10.236f, 5.765f)
                cubicTo(9.506f, 5.034f, 8.746f, 4.409f, 7.999f, 3.897f)
                close()
                moveTo(5.591f, 2.625f)
                cubicTo(4.107f, 2.091f, 3.142f, 2.243f, 2.693f, 2.692f)
                cubicTo(2.243f, 3.142f, 2.091f, 4.106f, 2.625f, 5.591f)
                cubicTo(2.753f, 5.945f, 2.914f, 6.314f, 3.11f, 6.692f)
                cubicTo(3.596f, 6.04f, 4.154f, 5.395f, 4.775f, 4.774f)
                cubicTo(5.395f, 4.154f, 6.04f, 3.596f, 6.693f, 3.109f)
                cubicTo(6.314f, 2.914f, 5.945f, 2.752f, 5.591f, 2.625f)
                close()
                moveTo(13.308f, 2.692f)
                cubicTo(12.858f, 2.243f, 11.894f, 2.091f, 10.41f, 2.625f)
                cubicTo(10.055f, 2.752f, 9.686f, 2.914f, 9.307f, 3.109f)
                cubicTo(9.959f, 3.596f, 10.606f, 4.154f, 11.226f, 4.774f)
                cubicTo(11.846f, 5.394f, 12.403f, 6.04f, 12.889f, 6.692f)
                cubicTo(13.085f, 6.314f, 13.248f, 5.945f, 13.375f, 5.591f)
                cubicTo(13.91f, 4.107f, 13.757f, 3.142f, 13.308f, 2.692f)
                close()
        }
    }
    val dot = remember {
        Path().apply {
                moveTo(8.002f, 6.769f)
                cubicTo(8.682f, 6.769f, 9.232f, 7.321f, 9.232f, 8.0f)
                cubicTo(9.232f, 8.679f, 8.682f, 9.229f, 8.002f, 9.229f)
                cubicTo(7.323f, 9.229f, 6.772f, 8.679f, 6.772f, 8.0f)
                cubicTo(6.772f, 7.321f, 7.323f, 6.769f, 8.002f, 6.769f)
                close()
        }
    }
    Canvas(modifier) {
        val s = size.minDimension / 16f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(body, color)
            drawPath(dot, color)
        }
    }
}

@Composable
internal fun WebGlyph(color: Color, modifier: Modifier = Modifier) {
    // 官方 App res/drawable-anydpi/ic_search_web.xml 原样转写（22×22 viewport）。
    val paths = remember {
        listOf(
            Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(19.178f, 11.77f)
                lineTo(2.31f, 11.77f)
                lineTo(2.31f, 10.23f)
                lineTo(19.178f, 10.23f)
                lineTo(19.178f, 11.77f)
                close()
            },
            Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(11.31f, 2.944f)
                cubicTo(9.691f, 4.689f, 8.58f, 7.616f, 8.58f, 11.0f)
                cubicTo(8.58f, 14.384f, 9.691f, 17.311f, 11.31f, 19.056f)
                lineTo(10.18f, 20.104f)
                cubicTo(8.244f, 18.015f, 7.04f, 14.685f, 7.04f, 11.0f)
                cubicTo(7.04f, 7.315f, 8.244f, 3.985f, 10.18f, 1.896f)
                lineTo(11.31f, 2.944f)
                close()
            },
            Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(10.8f, 2.944f)
                cubicTo(12.419f, 4.689f, 13.53f, 7.616f, 13.53f, 11.0f)
                cubicTo(13.53f, 14.384f, 12.419f, 17.311f, 10.8f, 19.056f)
                lineTo(11.93f, 20.104f)
                cubicTo(13.866f, 18.015f, 15.07f, 14.685f, 15.07f, 11.0f)
                cubicTo(15.07f, 7.315f, 13.866f, 3.985f, 11.93f, 1.896f)
                lineTo(10.8f, 2.944f)
                close()
            },
            Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(11.055f, 18.92f)
                cubicTo(15.399f, 18.92f, 18.92f, 15.399f, 18.92f, 11.055f)
                cubicTo(18.92f, 6.711f, 15.399f, 3.19f, 11.055f, 3.19f)
                cubicTo(6.711f, 3.19f, 3.19f, 6.711f, 3.19f, 11.055f)
                cubicTo(3.19f, 15.399f, 6.711f, 18.92f, 11.055f, 18.92f)
                close()
                moveTo(11.055f, 20.35f)
                cubicTo(16.188f, 20.35f, 20.35f, 16.188f, 20.35f, 11.055f)
                cubicTo(20.35f, 5.921f, 16.188f, 1.76f, 11.055f, 1.76f)
                cubicTo(5.922f, 1.76f, 1.76f, 5.921f, 1.76f, 11.055f)
                cubicTo(1.76f, 16.188f, 5.922f, 20.35f, 11.055f, 20.35f)
                close()
            },
        )
    }
    Canvas(modifier) {
        val s = size.minDimension / 22f
        scale(s, s, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color) }
        }
    }
}
