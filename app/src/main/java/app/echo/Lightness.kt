package app.echo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.cbrt

/**
 * 自定义底色的明度：界面显示的是 CIE L*（0 黑、100 白），不是 HSL 的 lightness。
 * 固定色相与饱和度后，HSL lightness 与 L* 严格单调，所以二分反解即可。
 *
 * 直接沿用 HSL lightness 会让同一根 0-100 的滑杆在不同色相下看起来差 65 点
 * （见 review/research/perceptual-lightness.md），因此存储与展示统一用 L*。
 *
 * HSL 转换自己实现，不依赖 android.graphics.ColorUtils：后者在 JVM 单测里是空桩。
 */
internal object Lightness {
    const val DEFAULT_LIGHT_LSTAR = 92.7f
    const val DEFAULT_DARK_LSTAR = 13.2f
    const val DEFAULT_LIGHT_HUE = 38f
    const val DEFAULT_LIGHT_SAT = 53f
    const val DEFAULT_DARK_SAT = 31f

    /** sRGB 相对亮度转 CIE L*，范围 0..100。 */
    fun lstarOf(color: Color): Float {
        val y = color.luminance()
        return if (y > 0.008856f) 116f * cbrt(y.toDouble()).toFloat() - 16f else 903.3f * y
    }

    fun lstarOf(argb: Int): Float = lstarOf(Color(argb))

    /** 固定 H（0..360）与 S（0..100），返回命中 L*（0..100）的 ARGB。 */
    fun argbFor(hue: Float, sat: Float, lstar: Float): Int {
        val target = lstar.coerceIn(0f, 100f)
        if (target <= 0f) return 0xFF000000.toInt()
        if (target >= 100f) return 0xFFFFFFFF.toInt()
        val h = hue.coerceIn(0f, 360f)
        val s = sat.coerceIn(0f, 100f) / 100f
        var lo = 0f
        var hi = 1f
        repeat(26) {
            val mid = (lo + hi) / 2f
            val current = lstarOf(hslToColor(h, s, mid))
            if (current < target) lo = mid else hi = mid
        }
        return hslToColor(h, s, (lo + hi) / 2f).toArgb()
    }

    /** ARGB -> H（0..360）、S（0..100）、L*（0..100），用于旧数据迁移与回显。 */
    fun triple(argb: Int): FloatArray {
        val hsl = colorToHsl(Color(argb))
        return floatArrayOf(hsl[0], hsl[1] * 100f, lstarOf(argb))
    }

    fun hueSat(argb: Int): FloatArray {
        val hsl = colorToHsl(Color(argb))
        return floatArrayOf(hsl[0], hsl[1] * 100f)
    }

    /** HSL（H 0..360、S/L 0..1）-> 不透明 sRGB。 */
    private fun hslToColor(hue: Float, sat: Float, light: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val s = sat.coerceIn(0f, 1f)
        val l = light.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(r1 + m, g1 + m, b1 + m)
    }

    /** 不透明 sRGB -> HSL（H 0..360、S/L 0..1）。 */
    private fun colorToHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val light = (max + min) / 2f
        if (delta == 0f) return floatArrayOf(0f, 0f, light)
        val sat = delta / (1f - abs(2f * light - 1f))
        val rawHue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return floatArrayOf((rawHue + 360f) % 360f, sat.coerceIn(0f, 1f), light)
    }
}
