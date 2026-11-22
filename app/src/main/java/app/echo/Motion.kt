package app.echo

import androidx.compose.animation.core.CubicBezierEasing

object Motion {
    const val BASE = 200
    const val SLOW = 320
    const val HERO = 480

    val STANDARD = CubicBezierEasing(.4f, 0f, .2f, 1f)

    val EXIT = CubicBezierEasing(.4f, 0f, 1f, 1f)

    fun dur(ms: Int, enabled: Boolean): Int = if (enabled) ms else 0
}
