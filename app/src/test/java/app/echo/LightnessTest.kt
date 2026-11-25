package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自定义底色的明度用 CIE L*（0 黑、100 白）而不是 HSL lightness：
 * 固定 H/S 后二分反解，保证用户看到「50 就是中灰」。
 */
class LightnessTest {

    private fun close(expected: Float, actual: Float, tolerance: Float, label: String) {
        assertTrue(
            label + " 期望 " + expected + " 实际 " + actual,
            abs(expected - actual) <= tolerance,
        )
    }

    @Test
    fun anchorsMatchCieLstar() {
        close(100f, Lightness.lstarOf(0xFFFFFFFF.toInt()), 0.05f, "白")
        close(0f, Lightness.lstarOf(0xFF000000.toInt()), 0.05f, "黑")
        close(53.59f, Lightness.lstarOf(0xFF808080.toInt()), 0.05f, "中灰 #808080")
        close(92.7f, Lightness.lstarOf(0xFFF3E9D8.toInt()), 0.1f, "默认浅色")
        close(13.2f, Lightness.lstarOf(0xFF282115.toInt()), 0.2f, "默认深色")
    }

    @Test
    fun argbForHitsRequestedLstarAcrossHues() {
        for (hue in 0..330 step 30) {
            for (lstar in listOf(0f, 13.2f, 50f, 92.7f, 100f)) {
                val argb = Lightness.argbFor(hue.toFloat(), 100f, lstar)
                close(lstar, Lightness.lstarOf(argb), 0.6f, "hue=" + hue + " L*=" + lstar)
            }
        }
    }

    @Test
    fun lightnessIsMonotonicForFixedHueAndSaturation() {
        var previous = -1f
        for (step in 0..20) {
            val argb = Lightness.argbFor(38f, 53f, step * 5f)
            val value = Lightness.lstarOf(argb)
            assertTrue("L* 应随档位单调递增：step=" + step + " value=" + value, value >= previous)
            previous = value
        }
    }

    @Test
    fun tripleRoundTripsHueAndSaturation() {
        val argb = Lightness.argbFor(212f, 62f, 50f)
        val triple = Lightness.triple(argb)
        close(212f, triple[0], 1.5f, "色相")
        close(62f, triple[1], 1.5f, "饱和度")
        close(50f, triple[2], 0.6f, "L*")
    }
}
