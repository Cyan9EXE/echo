package app.echo

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    /** 外观：底色与面板的一套端点。CUSTOM 是日间第五种外观（用户自选底色）。 */
    enum class Look(val id: String, val zh: String, val day: Boolean, val eye: Int) {
        PAPER("paper", "日间", true, 0),
        INK("ink", "夜间", false, 0),
        WARM("warm", "暖黄", true, 1),
        GREEN("green", "淡绿", true, 2),
        CUSTOM("custom", "自定义", true, 0),
    }

    var themeProgress by mutableFloatStateOf(0f)

    val skins = listOf(
        Skin("沙金", Color(0xFFB4974C), Color(0xFF7F6726)),
        Skin("雾蓝", Color(0xFF759ECC), Color(0xFF4C6C91)),
        Skin("黛青", Color(0xFF60A6A5), Color(0xFF3C7272)),
        Skin("陶土", Color(0xFFD1877B), Color(0xFF965A50)),
        Skin("灰紫", Color(0xFFB38DBE), Color(0xFF7E5F87)),
    )

    var isDay by mutableStateOf(false)
        private set

    /** 5 色均同时具备日/夜值，不再有「白」日间转黑的特例。 */
    fun skinLabel(index: Int): String = skins.getOrNull(index)?.label ?: "自定义"

    var eyeMode by mutableIntStateOf(0)
        private set

    var eyeProgress by mutableFloatStateOf(0f)

    /** 日间自定义底色开关；颜色存在 State，这里只保留渲染用的色相。 */
    var customThemeOn by mutableStateOf(false)
        private set
    private var customTint: Color? = null

    val look: Look
        get() = when {
            !isDay -> Look.INK
            customThemeOn -> Look.CUSTOM
            eyeMode == 1 -> Look.WARM
            eyeMode == 2 -> Look.GREEN
            else -> Look.PAPER
        }

    fun initDay(value: Boolean) {
        isDay = value
        themeProgress = if (value) 1f else 0f
    }

    fun initEye(mode: Int) {
        eyeMode = mode.coerceIn(0, 2)
        eyeProgress = if (isDay && mode != 0) 1f else 0f
    }

    fun applyDay(value: Boolean) { isDay = value }

    fun toggleDay() { applyDay(!isDay) }

    fun applyEye(mode: Int) { eyeMode = mode.coerceIn(0, 2) }

    fun initCustomTheme(argb: Int, enabled: Boolean) {
        applyCustomTheme(if (enabled) argb else null)
    }

    /** argb = null 关闭自定义底色，回到当前外观的预设底色。 */
    fun applyCustomTheme(argb: Int?) {
        customTint = argb?.let { Color(it) }
        customThemeOn = argb != null
        voidRamp.applyCustom(customTint, 0.010f)
        panelRamp.applyCustom(customTint, 0.010f)
        panelHighRamp.applyCustom(customTint, 0.014f)
        hairRamp.applyCustom(customTint, 0.008f)
        promptRamp.applyCustom(customTint, 0.010f)
    }

    /** 面板里展示自选底色的柔和预览。 */
    fun customField(): Color = customTint?.let { tinted(Color(0xFFF4F2ED), it, 0.03f) } ?: Color(0xFFF4F2ED)

    /** 选色时预览「这个 ARGB 当底色会长什么样」，不改全局状态。 */
    fun fieldPreview(argb: Int): Color = tinted(Color(0xFFF4F2ED), Color(argb), 0.03f)

    fun previewVoid(look: Look): Color = when (look) {
        Look.INK -> Color(0xFF050608)
        Look.WARM -> Color(0xFFF7F0DF)
        Look.GREEN -> Color(0xFFEEF3E6)
        else -> Color(0xFFF4F2ED)
    }

    fun previewAccent(look: Look, index: Int): Color {
        val i = index.coerceIn(0, skins.lastIndex)
        return if (look == Look.INK) Color(ACCENT_NIGHT[i]) else Color(ACCENT_DAY[look.ordinal][i])
    }

    fun accentAt(position: Float): Color {
        val last = skins.lastIndex
        val p = position.coerceIn(0f, last.toFloat())
        val i = floor(p).toInt().coerceAtMost(last - 1)
        val f = p - i
        return mixOklab(accentOf(i), accentOf(i + 1), f)
    }

    fun tone(accent: Color): Color = mixOklab(accent, Color.Black, 0.30f)

    /** 日间强调色作小字时的加深版：在 16% 同色底上仍 ≥4.5:1；夜间不变。 */
    fun accentText(accent: Color): Color =
        if (!isDay) accent else mixOklab(accent, Color.Black, 0.09f)

    fun eyeLit(): Color = if (eyeMode == 2) Color(0xFFCBE7BC) else Color(0xFFF5E3A8)

    fun eyeDim(): Color = mixOklab(eyeLit(), Color.Black, 0.38f)

    /**
     * 目标外观起点的底色亮度，用于决定这次外观切换的动画时长：
     * 夜间与日间的亮度差接近 0.9，需要放慢；日间与护眼两档之间只差一点点。
     */
    fun voidLuminanceFor(day: Boolean, eye: Int = 0): Float {
        if (day && eye == 0 && customThemeOn) return voidRamp.customDay().luminance()
        val base = if (day) Color(0xFFF4F2ED) else Color(0xFF050608)
        if (!day || eye == 0) return base.luminance()
        val target = if (eye == 2) Color(0xFFEEF3E6) else Color(0xFFF7F0DF)
        return mixOklab(base, target, 1f).luminance()
    }

    /** 预设强调色在当前的日夜/护眼进度下的实际颜色（自定义色不在此列）。 */
    fun presetAccent(index: Int): Color {
        if (index !in skins.indices) return Color.Transparent
        return accentOf(index)
    }

    fun mark(skinIndex: Int, custom: Color, accent: Color): ThemeMark {
        val customMode = skinIndex > skins.lastIndex
        val eye = isDay && eyeMode != 0
        val top = when {
            customMode && eye -> eyeDim()
            customMode -> Color(0xFF6E6E6E)
            eye -> eyeLit()
            else -> if (isDay) Color(0xFFFFFFFF) else Color(0xFFEDEAE3)
        }
        val bottom = if (customMode) accent else tone(accent)
        return ThemeMark(top, bottom)
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

    val ACCENT_ID = arrayOf("sand", "mist", "slate", "clay", "mauve")

    /** 20 个预设：4 外观 × 5 强调色。外观决定底色，强调色只决定 accent。 */
    val presets: List<Preset> = listOf(Look.PAPER, Look.INK, Look.WARM, Look.GREEN).flatMap { look ->
        skins.mapIndexed { i, s ->
            Preset(
                id = look.id + "." + ACCENT_ID[i],
                label = look.zh + " · " + s.label,
                appearance = look,
                accent = i,
            )
        }
    }
}

data class Skin(val label: String, val night: Color, val day: Color)

data class ThemeMark(val top: Color, val bottom: Color)

data class Preset(val id: String, val label: String, val appearance: C.Look, val accent: Int)

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

/** 只换色相、保留 OkLab 明度：自选底色不会把对比度拖坏。 */
private fun tinted(base: Color, tint: Color, chroma: Float): Color {
    val b = base.toOklab()
    val t = tint.toOklab()
    val c = kotlin.math.sqrt(t[1] * t[1] + t[2] * t[2])
    if (c < 0.001f) return base
    val k = chroma / c
    return oklabToColor(b[0], t[1] * k, t[2] * k, base.alpha)
}

private class Ramp(
    private val night: Color,
    private val day: Color,
) {
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

/**
 * 一个 token 的完整外观轴：纸白 / 暖黄 / 淡绿三条 ramp，外加可选的自定义底色 ramp。
 * 护眼档用 eyeProgress 平滑过渡；夜间三档都回同一墨端点。
 */
private class LookRamp(
    private val night: Color,
    private val paper: Color,
    private val warmDay: Color = paper,
    private val greenDay: Color = paper,
) {
    private val paperRamp = Ramp(night, paper)
    private val warmRamp = Ramp(night, warmDay)
    private val greenRamp = Ramp(night, greenDay)
    private var customRamp: Ramp? = null

    fun applyCustom(tint: Color?, chroma: Float) {
        customRamp = if (tint == null) null else Ramp(night, tinted(paper, tint, chroma))
    }

    fun customDay(): Color = customRamp?.at(1f) ?: paper

    fun at(t: Float): Color {
        if (C.look == C.Look.CUSTOM) customRamp?.let { return it.at(t) }
        val base = paperRamp.at(t)
        val eye = C.eyeMode
        if (C.look == C.Look.INK || eye == 0 || C.eyeProgress <= .001f || t <= .001f) return base
        val target = if (C.look == C.Look.GREEN) greenRamp.at(t) else warmRamp.at(t)
        return mixOklab(base, target, C.eyeProgress * t)
    }
}

/** 夜间强调色（全局共享）。 */
private val ACCENT_NIGHT = longArrayOf(
    0xFFB4974C, // 沙金
    0xFF759ECC, // 雾蓝
    0xFF60A6A5, // 黛青
    0xFFD1877B, // 陶土
    0xFFB38DBE, // 灰紫
)

/** 日间强调色：按外观分四套，行序同 Look。V3 日间值已对三种浅底验证，四行同值。 */
private val ACCENT_DAY = arrayOf(
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // PAPER
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // INK（只作过渡）
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // WARM
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // GREEN
)

private fun accentNight(i: Int): Color = Color(ACCENT_NIGHT[i])

private fun accentDay(i: Int): Color {
    val row = if (C.look == C.Look.CUSTOM) 0 else C.look.ordinal.coerceIn(0, 3)
    return Color(ACCENT_DAY[row][i])
}

/** 当前日夜/护眼/自定义进度下某个强调色的实际颜色。 */
private fun accentOf(i: Int): Color {
    val idx = i.coerceIn(0, C.skins.lastIndex)
    return mixOklab(accentNight(idx), accentDay(idx), C.themeProgress)
}

private val voidRamp = LookRamp(Color(0xFF050608), Color(0xFFF4F2ED), Color(0xFFF7F0DF), Color(0xFFEEF3E6))
private val panelRamp = LookRamp(Color(0xFF12151A), Color(0xFFEFE9DE), Color(0xFFF1E8D3), Color(0xFFE7EEDE))
private val panelHighRamp = LookRamp(Color(0xFF1B1F25), Color(0xFFE6DFD1), Color(0xFFE9DEC5), Color(0xFFDCE7D0))
private val hairRamp = LookRamp(Color(0x1FFFFFFF), Color(0x2B201D19), Color(0x2B6B5A2E), Color(0x2B384A2C))
private val controlRamp = LookRamp(Color(0xFF7A828E), Color(0xFF847E72), Color(0xFF857C64), Color(0xFF7A8A70))

private val ivoryRamp = LookRamp(Color(0xFFF3EFE7), Color(0xFF1A1713), Color(0xFF2A2115), Color(0xFF1F2A1C))
private val mutedRamp = LookRamp(Color(0xFFA8ADB5), Color(0xFF5A544B), Color(0xFF63553F), Color(0xFF4F5F46))

private val blueRamp = LookRamp(Color(0xFF7FA8FF), Color(0xFF2F55B8), Color(0xFF2C4F9E), Color(0xFF2B4C93))

private val dangerRamp = LookRamp(Color(0xFFFF6B6B), Color(0xFFB3261E), Color(0xFFA02418), Color(0xFF9E2619))
private val promptRamp = LookRamp(Color(0xFF121A2A), Color(0xFFEFECE6), Color(0xFFF2EAD6), Color(0xFFE9F0E1))

private val scrimRamp = LookRamp(Color(0xB3000000), Color(0x66000000))
