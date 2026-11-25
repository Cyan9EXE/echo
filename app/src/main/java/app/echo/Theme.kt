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
        Skin("沙金", Color(0xFFE2C070), Color(0xFFC9A227), Color(0xFF66520D)),
        Skin("雾蓝", Color(0xFF759ECC), Color(0xFF5E86B8), Color(0xFF375577)),
        Skin("黛青", Color(0xFF60A6A5), Color(0xFF3F8F8C), Color(0xFF2A5B58)),
        Skin("陶土", Color(0xFFD1877B), Color(0xFFC58573), Color(0xFF85473C)),
        Skin("灰紫", Color(0xFFB38DBE), Color(0xFFA987B8), Color(0xFF6B4E75)),
        Skin("靛蓝", Color(0xFFA9B4E8), Color(0xFF6E7BE0), Color(0xFF434B8F)),
    )

    var isDay by mutableStateOf(false)
        private set

    /** 6 色均同时具备日/夜值；日间 12sp 小字另取 dayText，填充直接用日/夜值。 */
    fun skinLabel(index: Int): String = skins.getOrNull(index)?.label ?: "自定义"

    var eyeMode by mutableIntStateOf(0)
        private set

    var eyeProgress by mutableFloatStateOf(0f)

    /** 自定义底色开关：日间/夜间各存一套，互不影响。 */
    var customThemeOn by mutableStateOf(false)
        private set
    private var customLightTint: Color? = null
    private var customDarkTint: Color? = null

    /** 两套自选底色各自派生的表面色；按当前日夜取用，null 表示走预设外观的 ramp。 */
    private var customLightSurface: CustomSurface? by mutableStateOf(null)
    private var customDarkSurface: CustomSurface? by mutableStateOf(null)
    private val customSurface: CustomSurface?
        get() = if (isDay) customLightSurface else customDarkSurface

    val look: Look
        get() = when {
            // 自选底色两套各自接管日间/夜间，不能再被夜间与否挡掉。
            customThemeOn -> Look.CUSTOM
            !isDay -> Look.INK
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

    fun initCustomThemes(lightArgb: Int, darkArgb: Int, enabled: Boolean) {
        applyCustomThemes(if (enabled) lightArgb else null, if (enabled) darkArgb else null)
    }

    /** 两套自选底色一起装进渲染层；任意一套为 null 即视为关闭。 */
    fun applyCustomThemes(lightArgb: Int?, darkArgb: Int?) {
        customLightTint = lightArgb?.let { Color(it) }
        customDarkTint = darkArgb?.let { Color(it) }
        customLightSurface = customLightTint?.let { CustomSurface(it) }
        customDarkSurface = customDarkTint?.let { CustomSurface(it) }
        customThemeOn = lightArgb != null && darkArgb != null
    }

    /** 当前生效那一套的底色；关闭时回纸白。 */
    fun customField(): Color = customSurface?.void ?: Color(0xFFF4F2ED)

    /** 指定那一套（日/夜）的底色，预览色块与自选瓦片用。 */
    fun customFieldFor(day: Boolean): Color =
        (if (day) customLightSurface else customDarkSurface)?.void ?: Color(0xFFF4F2ED)

    /** 面板里拖旋钮时的草稿色：不写入全局，只拿来渲染实时预览。 */
    internal fun previewSurface(hue: Float, sat: Float, lstar: Float): CustomSurface =
        CustomSurface(Color(Lightness.argbFor(hue, sat, lstar)))

    /** 实时预览里的正文对比度，用于「正文对比 12.2:1」徽标。 */
    internal fun bodyContrast(surface: CustomSurface): Float = contrastOn(surface.ivory, surface.void)

    /** 选色时预览「这个 ARGB 当底色会长什么样」，不改全局状态。 */
    fun fieldPreview(argb: Int): Color = Color(argb)

    /** 当前界面按实际底色判断明暗；预设外观跟随日夜。 */
    val isDarkBackground: Boolean get() = customSurface?.isDark ?: !isDay

    fun previewVoid(look: Look): Color = when (look) {
        Look.INK -> Color(0xFF050608)
        Look.WARM -> Color(0xFFF7F0DF)
        Look.GREEN -> Color(0xFFEEF3E6)
        else -> Color(0xFFF4F2ED)
    }

    fun accentAt(position: Float): Color {
        val last = skins.lastIndex
        val p = position.coerceIn(0f, last.toFloat())
        val i = floor(p).toInt().coerceAtMost(last - 1)
        val f = p - i
        return mixOklab(accentOf(i), accentOf(i + 1), f)
    }

    fun tone(accent: Color): Color = mixOklab(accent, Color.Black, 0.30f)

    /**
     * 填充上的文字：在近黑与纸白里取对比度高的一侧。
     * 自定义强调色可能是任意明度，固定近黑放在深色填充上会失读。
     */
    fun onAccent(accent: Color): Color =
        if (contrastOn(INK_ON_ACCENT, accent) >= contrastOn(PAPER_ON_ACCENT, accent)) {
            INK_ON_ACCENT
        } else {
            PAPER_ON_ACCENT
        }

    /**
     * 强调色作填充底色前先把明度收进可读区间：中灰既不近黑也不近白，
     * 固定墨/纸都到不了 4.7:1。已达标则原样返回；否则朝 onAccent 对比
     * 更优的一侧压深（墨更优）或提亮（纸更优）。
     */
    fun readableAccent(accent: Color): Color {
        var c = accent
        var guard = 0
        while (guard++ < 32) {
            val ink = contrastOn(INK_ON_ACCENT, c)
            val paper = contrastOn(PAPER_ON_ACCENT, c)
            val best = kotlin.math.max(ink, paper)
            if (best >= TEXT_CONTRAST) return c
            c = if (ink >= paper) {
                mixOklab(c, Color.Black, 0.06f)
            } else {
                mixOklab(c, Color.White, 0.06f)
            }
        }
        return c
    }

    /**
     * 强调色作 12sp 小字时用的文字色：预设色取设计好的 dayText；
     * 自定义色按最坏底色迭代加深（日间）或提亮（夜间），直到 4.7:1。
     * 夜间亮色本身够读时保持原样。
     */
    fun accentText(accent: Color): Color {
        val backgrounds = textBackgrounds(accent)
        val custom = customSurface
        val darkUi = custom?.isDark ?: !isDay
        val preset = nearestSkin(accent)
        var c = when {
            custom != null && preset >= 0 -> if (darkUi) skins[preset].night else skins[preset].dayText
            custom != null -> accent
            isDay && preset >= 0 -> skins[preset].dayText
            else -> accent
        }
        var guard = 0
        val toward = if (darkUi) Color.White else Color.Black
        while (minContrastOn(c, backgrounds) < TEXT_CONTRAST && guard++ < 32) {
            c = mixOklab(c, toward, 0.06f)
        }
        return c
    }

    /**
     * 强调色作非文本前景（选中边框、指示点）时的颜色：在给定底色上保证 target 对比度。
     * 预设芯片的底色随外观变化（纸、墨、暖黄、淡绿），同一个 accentText 在墨黑卡上会失读，
     * 所以这里按实际底色决定是保留原色、压深，还是朝白提亮。
     */
    internal fun accentOn(background: Color, accent: Color, target: Float = 3f): Color {
        if (contrastOn(accent, background) >= target) return accent
        val toward = if (background.luminance() < 0.5f) Color.White else Color.Black
        var c = accent
        var guard = 0
        while (contrastOn(c, background) < target && guard++ < 32) {
            c = mixOklab(c, toward, 0.06f)
        }
        return c
    }

    /**
     * 小字可能落到的全部底色：卡片、高亮卡片，以及 16% 强调色叠在卡片上的选中态。
     * 取其中最坏的一档做校验，避免只对着 Void 调深浅。
     */
    internal fun textBackgrounds(accent: Color): List<Color> = buildList {
        add(Void)
        add(Panel)
        add(PanelHigh)
        // 旧 OKLab 近似，保留以兼容既有判定的历史结论。
        add(mixOklab(Panel, accent, 0.16f))
        // 实际绘制：accent 以 16% alpha 叠在三级底色上（sRGB alpha 合成）。
        add(blendSrgb(Void, accent, 0.16f))
        add(blendSrgb(Panel, accent, 0.16f))
        add(blendSrgb(PanelHigh, accent, 0.16f))
    }.distinct()

    /**
     * 与 Compose 实际绘制一致的 sRGB alpha 合成：overlay 以 alpha 叠在 base 上。
     * 测试与生产共用这一份实现，避免两套叠底算法给出不同结论。
     */
    internal fun blendSrgb(base: Color, overlay: Color, alpha: Float): Color {
        val a = alpha.coerceIn(0f, 1f)
        return Color(
            red = overlay.red * a + base.red * (1f - a),
            green = overlay.green * a + base.green * (1f - a),
            blue = overlay.blue * a + base.blue * (1f - a),
            alpha = 1f,
        )
    }

    private fun minContrastOn(c: Color, backgrounds: List<Color>): Float =
        backgrounds.minOf { contrastOn(c, it) }

    /** OKLab 距离最近的预设强调色；超过阈值视为自定义色。 */
    internal fun nearestSkin(accent: Color): Int {
        val x = accent.toOklab()
        var best = -1
        var bestDistance = 0.033f
        skins.forEachIndexed { i, skin ->
            val y = skin.day.toOklab()
            val dl = x[0] - y[0]
            val da = x[1] - y[1]
            val db = x[2] - y[2]
            val d = kotlin.math.sqrt(dl * dl + da * da + db * db)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun contrastOn(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = kotlin.math.max(la, lb)
        val lo = kotlin.math.min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    fun eyeLit(): Color = if (eyeMode == 2) Color(0xFFCBE7BC) else Color(0xFFF5E3A8)

    fun eyeDim(): Color = mixOklab(eyeLit(), Color.Black, 0.38f)

    /** 预设强调色在当前的日夜/护眼进度下的实际颜色（自定义色不在此列）。 */
    fun presetAccent(index: Int): Color {
        if (index !in skins.indices) return Color.Transparent
        if (customSurface?.isDark == true) return skins[index].night
        return accentOf(index)
    }

    /** 主题指示块：上块表示底色（白亮/灰暗/护眼/自定义灰），下块就是强调色本身。 */
    fun mark(skinIndex: Int, accent: Color): ThemeMark {
        val customMode = skinIndex > skins.lastIndex
        val eye = isDay && eyeMode != 0
        val top = when {
            customMode -> customField()
            eye -> eyeLit()
            else -> if (isDay) Color(0xFFFFFFFF) else Color(0xFFEDEAE3)
        }
        val bottom = accent
        return ThemeMark(top, bottom)
    }

    val Void get() = customSurface?.void ?: voidRamp.at(themeProgress)
    val Panel get() = customSurface?.panel ?: panelRamp.at(themeProgress)
    val PanelHigh get() = customSurface?.panelHigh ?: panelHighRamp.at(themeProgress)
    val Hair get() = customSurface?.hair ?: hairRamp.at(themeProgress)

    val ControlBorder get() = customSurface?.controlBorder ?: controlRamp.at(themeProgress)

    val Ivory get() = customSurface?.ivory ?: ivoryRamp.at(inkFlip(Void.luminance()))
    val Muted get() = customSurface?.muted ?: mutedRamp.at(inkFlip(Void.luminance()))

    val Blue get() = customSurface?.blue ?: blueRamp.at(themeProgress)

    val Danger get() = customSurface?.danger ?: dangerRamp.at(themeProgress)
    val PromptBg get() = customSurface?.promptBg ?: promptRamp.at(themeProgress)

    val Scrim get() = customSurface?.scrim ?: scrimRamp.at(themeProgress)

}

/**
 * 一套自选底色派生出的整套表面色：明度决定界面是深色还是浅色，
 * 面板/分割线/正文/强调色都按实际底色的极性重新取值，而不是只做一层几不可见的染色。
 * 日间/夜间各有一份，互不影响。
 */
internal class CustomSurface(val void: Color) {
    val isDark: Boolean = contrastOf(PAPER_ON_ACCENT, void) >= contrastOf(INK_ON_ACCENT, void)

    private val toward = if (isDark) Color.White else Color.Black

    // 中段深色底（L* 40-50）上，面板再提亮就会让白字跌破 4.7：
    // 这里按「白字仍能到 TEXT_CONTRAST+余量」封顶，宁可少提亮也不牺牲可读性。
    private fun darkStep(amount: Float): Color {
        var color = mixOklab(void, Color.White, amount)
        var guard = 0
        while (contrastOf(Color.White, color) < TEXT_CONTRAST + 0.35f && guard++ < 40) {
            color = mixOklab(color, void, 0.10f)
        }
        return color
    }

    // 浅色分支同理：中段底色上把面板再压暗，黑字同样到不了 4.7。
    private fun lightStep(amount: Float): Color {
        var color = mixOklab(void, Color.Black, amount)
        var guard = 0
        while (contrastOf(Color.Black, color) < TEXT_CONTRAST + 0.45f && guard++ < 40) {
            color = mixOklab(color, Color.White, 0.10f)
        }
        return color
    }

    val panel: Color =
        if (isDark) darkStep(0.10f) else lightStep(0.045f)
    val panelHigh: Color =
        if (isDark) darkStep(0.18f) else lightStep(0.08f)
    val hair: Color =
        if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val controlBorder: Color =
        if (isDark) mixOklab(void, Color.White, 0.42f) else mixOklab(void, Color.Black, 0.34f)

    val ivory: Color = readableOn(
        start = if (isDark) mixOklab(Color.White, void, 0.06f) else mixOklab(Color(0xFF1A1713), void, 0.08f),
        backgrounds = listOf(void, panel, panelHigh),
        toward = toward,
        target = TEXT_CONTRAST,
    )
    val muted: Color = readableOn(
        start = mixOklab(ivory, void, 0.34f),
        backgrounds = listOf(void, panel, panelHigh),
        toward = toward,
        target = 3.2f,
    )

    val blue: Color = if (isDark) Color(0xFF7FA8FF) else Color(0xFF2F55B8)
    val danger: Color = if (isDark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
    val promptBg: Color =
        if (isDark) mixOklab(void, Color.White, 0.13f) else mixOklab(void, Color.Black, 0.03f)
    val scrim: Color = if (isDark) Color(0xB3000000) else Color(0x66000000)
}

private fun contrastOf(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = kotlin.math.max(la, lb)
    val lo = kotlin.math.min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** 把 start 朝 toward 逐步推，直到在全部 backgrounds 上都达到 target。 */
private fun readableOn(
    start: Color,
    backgrounds: List<Color>,
    toward: Color,
    target: Float,
): Color {
    var c = start
    var guard = 0
    while (guard++ < 32 && backgrounds.minOf { contrastOf(c, it) } < target) {
        c = mixOklab(c, toward, 0.06f)
    }
    return c
}

/**
 * @param night 夜间强调色（亮，可作小字）
 * @param day 日间填充色（亮）
 * @param dayText 日间 12sp 小字色（深）
 */
data class Skin(val label: String, val night: Color, val day: Color, val dayText: Color)

data class ThemeMark(val top: Color, val bottom: Color)

private val INK_ON_ACCENT = Color(0xFF0C0A07)
private val PAPER_ON_ACCENT = Color(0xFFF3EFE7)
private const val TEXT_CONTRAST = 4.7f

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
 * 护眼档由 eyeProgress 直接取端点（外层做一次整屏淡入）；夜间三档都回同一墨端点。
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

    fun at(t: Float): Color {
        val base = paperRamp.at(t)
        val eye = C.eyeMode
        if (C.look == C.Look.INK || eye == 0 || C.eyeProgress <= .001f || t <= .001f) return base
        val target = if (C.look == C.Look.GREEN) greenRamp.at(t) else warmRamp.at(t)
        return mixOklab(base, target, C.eyeProgress * t)
    }
}

/** 夜间强调色（全局共享）。 */
private val ACCENT_NIGHT = longArrayOf(
    0xFFE2C070, // 沙金
    0xFF759ECC, // 雾蓝
    0xFF60A6A5, // 黛青
    0xFFD1877B, // 陶土
    0xFFB38DBE, // 灰紫
    0xFFA9B4E8, // 靛蓝
)

/** 日间强调色＝填充色：按外观分四套，行序同 Look。六色已对三种浅底验证，四行同值。 */
private val ACCENT_DAY = arrayOf(
    longArrayOf(0xFFC9A227, 0xFF5E86B8, 0xFF3F8F8C, 0xFFC58573, 0xFFA987B8, 0xFF6E7BE0), // PAPER
    longArrayOf(0xFFC9A227, 0xFF5E86B8, 0xFF3F8F8C, 0xFFC58573, 0xFFA987B8, 0xFF6E7BE0), // INK（只作过渡）
    longArrayOf(0xFFC9A227, 0xFF5E86B8, 0xFF3F8F8C, 0xFFC58573, 0xFFA987B8, 0xFF6E7BE0), // WARM
    longArrayOf(0xFFC9A227, 0xFF5E86B8, 0xFF3F8F8C, 0xFFC58573, 0xFFA987B8, 0xFF6E7BE0), // GREEN
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
