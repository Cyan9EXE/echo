package app.echo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())

internal fun fmtTime(ts: Long): String = timeFmt.format(Instant.ofEpochMilli(ts))

internal fun fmtCny(v: Double): String = "¥" + String.format(Locale.CHINA, "%.4f", v)

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal fun Modifier.rise(distance: Dp = 12.dp, progress: () -> Float): Modifier =
    graphicsLayer {
        val p = progress()
        alpha = p
        translationY = (1f - p) * distance.toPx()
    }

internal fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
