package app.echo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Motion {
    /** 系统「减弱动态」/关闭动画时为 false；Compose 状态，切换后读它的界面会重组。 */
    var animationsEnabled by mutableStateOf(true)

    const val BASE = 200
    const val SLOW = 320
    const val HERO = 480

    val STANDARD = CubicBezierEasing(.4f, 0f, .2f, 1f)

    val EXIT = CubicBezierEasing(.4f, 0f, 1f, 1f)

    /** 大跨度明暗切换用的更柔缓曲线，避免整屏换色时出现「闪一下」。 */
    val CALM = CubicBezierEasing(.32f, 0f, .24f, 1f)

    fun dur(ms: Int, enabled: Boolean): Int = if (enabled) ms else 0

    /**
     * 明暗差越大，过渡越长：整屏从墨黑切到纸白（或反过来）时，
     * 眼睛需要更长的适应时间，硬切 280ms 会显突兀；小幅换色保持轻快。
     * delta 为 0..1 的感知亮度差，base 是小差值时的时长。
     */
    fun crossfade(base: Int, delta: Float, enabled: Boolean): Int {
        if (!enabled) return 0
        val d = delta.coerceIn(0f, 1f)
        val eased = d * d * (3f - 2f * d)
        return (base * (1f + 0.65f * eased)).toInt()
    }
}
