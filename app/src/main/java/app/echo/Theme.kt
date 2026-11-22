package app.echo

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.pow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object C {
    var themeProgress by mutableFloatStateOf(0f)

    val skins = listOf(
        Skin("金", Color(0xFFF2A65A), Color(0xFF9A520F)),
        Skin("蓝", Color(0xFF7FA8FF), Color(0xFF2F55B8)),
        Skin("青", Color(0xFF6FD3B4), Color(0xFF1F6B57)),
        Skin("陶", Color(0xFFF49A7C), Color(0xFF99503A)),
    )

    var isDay by mutableStateOf(false)
        private set

    fun initDay(value: Boolean) {
        isDay = value
        themeProgress = if (value) 1f else 0f
    }

    fun applyDay(value: Boolean) { isDay = value }

    fun toggleDay() { applyDay(!isDay) }

    fun accentAt(position: Float): Color {
        val p = position.coerceIn(0f, skins.lastIndex.toFloat())
        val i = floor(p).toInt().coerceAtMost(skins.lastIndex - 1)
        val f = p - i
        val a = mixOklab(skins[i].night, skins[i].day, themeProgress)
        val b = mixOklab(skins[i + 1].night, skins[i + 1].day, themeProgress)
        return mixOklab(a, b, f)
    }

    fun shapeProgress(raw: Float): Float {
        val x = raw.coerceIn(0f, 1f) * 2f - 1f
        val w = 1f - x * x
        return ((x * (1f + 0.45f * w * w)) + 1f) / 2f
    }

    val Void get() = voidRamp.at(themeProgress)
    val Panel get() = panelRamp.at(themeProgress)
    val PanelHigh get() = panelHighRamp.at(themeProgress)
    val Hair get() = hairRamp.at(themeProgress)

    val ControlBorder get() = controlRamp.at(themeProgress)

    val Ivory get() = ivoryRamp.at(inkFlip(Void.luminance()))
    val Muted get() = mutedRamp.at(inkFlip(Void.luminance()))

    val Blue get() = blueRamp.at(themeProgress)

    val Danger get() = dangerRamp.at(themeProgress)
    val PromptBg get() = promptRamp.at(themeProgress)

    val Scrim get() = scrimRamp.at(themeProgress)
}

data class Skin(val label: String, val night: Color, val day: Color)

private fun inkFlip(bgLuminance: Float): Float {
    val t = ((bgLuminance - 0.16f) / 0.10f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val STOPS = 24

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float =
    if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

private fun cubeRoot(x: Float): Float = cbrt(x.toDouble()).toFloat()

private fun Color.toOklab(): FloatArray {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)
    val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
    val lr = cubeRoot(l)
    val mr = cubeRoot(m)
    val sr = cubeRoot(s)
    return floatArrayOf(
        0.2104542553f * lr + 0.7936177850f * mr - 0.0040720468f * sr,
        1.9779984951f * lr - 2.4285922050f * mr + 0.4505937099f * sr,
        0.0259040371f * lr + 0.7827717662f * mr - 0.8086757660f * sr,
    )
}

private fun mixOklab(a: Color, b: Color, t: Float): Color {
    if (t <= 0f) return a
    if (t >= 1f) return b
    val x = a.toOklab()
    val y = b.toOklab()
    return oklabToColor(
        x[0] + (y[0] - x[0]) * t,
        x[1] + (y[1] - x[1]) * t,
        x[2] + (y[2] - x[2]) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}

private fun oklabToColor(l: Float, a: Float, b: Float, alpha: Float): Color {
    val lr = l + 0.3963377774f * a + 0.2158037573f * b
    val mr = l - 0.1055613458f * a - 0.0638541728f * b
    val sr = l - 0.0894841775f * a - 1.2914855480f * b
    val l3 = lr * lr * lr
    val m3 = mr * mr * mr
    val s3 = sr * sr * sr
    val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
    val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
    val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    return Color(
        linearToSrgb(r.coerceIn(0f, 1f)),
        linearToSrgb(g.coerceIn(0f, 1f)),
        linearToSrgb(bl.coerceIn(0f, 1f)),
        alpha,
    )
}

private class Ramp(private val night: Color, private val day: Color) {
    private val packed = IntArray(STOPS + 1)

    init {
        val n = night.toOklab()
        val d = day.toOklab()
        for (i in 1 until STOPS) {
            val t = i.toFloat() / STOPS
            packed[i] = oklabToColor(
                n[0] + (d[0] - n[0]) * t,
                n[1] + (d[1] - n[1]) * t,
                n[2] + (d[2] - n[2]) * t,
                night.alpha + (day.alpha - night.alpha) * t,
            ).toArgb()
        }
        packed[0] = night.toArgb()
        packed[STOPS] = day.toArgb()
    }

    fun at(t: Float): Color {
        if (t <= 0f) return night
        if (t >= 1f) return day
        val x = t * STOPS
        val i = x.toInt().coerceIn(0, STOPS - 1)
        val f = x - i
        val a = packed[i]
        val b = packed[i + 1]
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * f).toInt().coerceIn(0, 255)
        }
        return Color(ch(16), ch(8), ch(0), ch(24))
    }
}

private val voidRamp = Ramp(Color(0xFF050608), Color(0xFFF4F2ED))
private val panelRamp = Ramp(Color(0xFF12151A), Color(0xFFEFE9DE))
private val panelHighRamp = Ramp(Color(0xFF1B1F25), Color(0xFFE6DFD1))
private val hairRamp = Ramp(Color(0x1FFFFFFF), Color(0x2B201D19))
private val controlRamp = Ramp(Color(0xFF7A828E), Color(0xFF847E72))

private val ivoryRamp = Ramp(Color(0xFFF3EFE7), Color(0xFF1A1713))
private val mutedRamp = Ramp(Color(0xFFA8ADB5), Color(0xFF5A544B))

private val blueRamp = Ramp(Color(0xFF7FA8FF), Color(0xFF2F55B8))

private val dangerRamp = Ramp(Color(0xFFFF6B6B), Color(0xFFB3261E))
private val promptRamp = Ramp(Color(0xFF121A2A), Color(0xFFEFECE6))

private val scrimRamp = Ramp(Color(0xB3000000), Color(0x66000000))
