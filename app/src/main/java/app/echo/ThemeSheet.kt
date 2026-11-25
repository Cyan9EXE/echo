package app.echo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// 主题面板：外观（日间 / 夜间 / 暖黄 / 淡绿 / 自选）+ 六个强调色的真实界面预览 + 自定义强调色（HSL）。
@Composable
internal fun ThemeSheet(state: AppState, accent: Color) {
    Surface(
        color = C.Panel,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(C.ControlBorder))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp).padding(bottom = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主题", color = C.Ivory, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TapTarget(size = 48.dp, visual = 40.dp, onClick = { state.closeTheme() }, description = "关闭") {
                        CloseGlyph(Modifier.size(14.dp), C.Muted)
                    }
                }
                Text(
                    "改完立即生效；日间与夜间各存一套底色，互不影响。",
                    color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                )

                SheetSection("外观")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppearanceOption(
                        Modifier.weight(1f), "日间", Color(0xFFF4F2ED),
                        C.isDay && !C.customThemeOn && C.eyeMode == 0, accent,
                    ) { state.applyAppearance(C.Look.PAPER) }
                    AppearanceOption(
                        Modifier.weight(1f), "夜间", Color(0xFF0B0D10),
                        !C.isDay && !C.customThemeOn, accent,
                    ) { state.applyAppearance(C.Look.INK) }
                    AppearanceOption(
                        Modifier.weight(1f), "暖黄", Color(0xFFF5E3A8),
                        C.isDay && !C.customThemeOn && C.eyeMode == 1, accent,
                    ) { state.applyAppearance(C.Look.WARM) }
                    AppearanceOption(
                        Modifier.weight(1f), "淡绿", Color(0xFFCBE7BC),
                        C.isDay && !C.customThemeOn && C.eyeMode == 2, accent,
                    ) { state.applyAppearance(C.Look.GREEN) }
                    AppearanceOption(
                        Modifier.weight(1f), "自选",
                        if (C.customThemeOn) C.customFieldFor(true)
                        else C.fieldPreview(Lightness.argbFor(state.customLightHue, state.customLightSat, state.customLightLstar)),
                        C.customThemeOn, accent,
                        splitSecond = if (C.customThemeOn) C.customFieldFor(false) else null,
                    ) { state.applyAppearance(C.Look.CUSTOM) }
                }
                Text(
                    "暖黄与淡绿是两档护眼配色，只在日间生效；底色、面板、分割线都会跟着走，强调色不变。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                )

                if (C.customThemeOn) {
                    CustomBaseEditor(state, accent)
                }

                if (!C.customThemeOn) {
                    SheetSection("预设")
                    PresetGrid(state)
                    Text(
                        "按当前外观实时渲染：每张卡就是一块缩小的欢迎页，点卡即切换强调色。",
                        color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                SheetSection("自定义强调色")
                CustomAccentRow(state, accent)
                Text(
                    "六个预设已经放进上面的真实界面预览；想要列表外的颜色，用下面这条自定义。强调色负责填充，日间小字另取深一档，填充上的字取近黑。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                )

                if (state.customMode) {
                    HslEditor(
                        seed = state.customAccent,
                        title = "自定义强调色",
                        hint = "松开滑杆后生效；松手时自动压深或提亮，保证填充上的字可读。",
                        previewColor = { C.readableAccent(Color(it)) },
                        onCommit = { state.selectCustomAccent(it) },
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "长按顶栏的主题按钮并下滑，也可以直接打开这里。",
                    color = C.Muted, fontSize = 10.sp,
                )
                Text(
                    "强调色只改界面，不改变请求结构、模型与 token。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetSection(label: String) {
    Text(
        label, color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun AppearanceOption(
    modifier: Modifier,
    label: String,
    field: Color,
    selected: Boolean,
    accent: Color,
    splitSecond: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 70.dp)
            .clip(shape)
            .background(if (selected) C.PanelHigh else C.Panel)
            .border(2.dp, if (selected) C.accentText(accent) else C.Hair, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(26.dp, 30.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, C.ControlBorder, RoundedCornerShape(8.dp)),
        ) {
            if (splitSecond == null) {
                Box(Modifier.fillMaxSize().background(field))
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(field))
                    Box(Modifier.weight(1f).fillMaxHeight().background(splitSecond))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label, color = if (selected) C.Ivory else C.Muted, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

// 六个强调色的真实界面预览：每张卡是缩小的欢迎页，按当前外观实时渲染。
@Composable
private fun PresetGrid(state: AppState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 420.dp) 3 else 2
        val gap = 10.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            C.skins.indices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { index ->
                        PresetPreview(
                            modifier = Modifier.weight(1f),
                            index = index,
                            accent = C.presetAccent(index),
                            selected = !state.customMode && state.skinIndex == index,
                            onClick = { state.applyAccent(index) },
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PresetPreview(
    modifier: Modifier,
    index: Int,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .height(112.dp)
            .clip(shape)
            .background(C.Void)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) C.accentOn(C.Void, accent) else C.Hair,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "预设 ${C.skinLabel(index)}" }
            .padding(9.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                .background(C.Panel).padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Echo", color = C.Ivory, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "欢迎回来，随时续上。",
                color = C.Ivory, fontSize = 9.sp, maxLines = 1, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(C.PanelHigh),
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("请输入消息", color = C.Muted, fontSize = 7.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            C.skinLabel(index),
            color = if (selected) C.Ivory else C.Muted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun CustomAccentRow(state: AppState, accent: Color) {
    val shape = RoundedCornerShape(14.dp)
    val color = Color(state.customAccent)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .background(if (state.customMode) C.PanelHigh else C.Panel)
            .border(2.dp, if (state.customMode) C.accentText(accent) else C.Hair, shape)
            .selectable(selected = state.customMode, role = Role.RadioButton) {
                state.selectCustomAccent(state.customAccent)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(color)
                .border(1.dp, C.ControlBorder, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (state.customMode) "自定义强调色 · 已启用" else "自定义强调色",
                color = C.Ivory, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(1.dp))
            Text("HSL 滑杆选色；色块同步显示自定义配色，极端色自动压深或提亮，保证填充上的字仍可读。", color = C.Muted, fontSize = 10.sp)
        }
        Text(
            if (state.customMode) "编辑" else "选择",
            color = if (state.customMode) C.accentText(accent) else C.Muted, fontSize = 11.sp,
        )
    }
}

// 两个编辑器共用：色块实时预览候选色，松手才提交。
@Composable
private fun HslEditor(
    seed: Int,
    title: String,
    hint: String,
    previewColor: (Int) -> Color,
    onCommit: (Int) -> Unit,
) {
    val hsl = remember(seed) { FloatArray(3).also { ColorUtils.colorToHSL(seed, it) } }
    var hue by remember(seed) { mutableFloatStateOf(hsl[0]) }
    var sat by remember(seed) { mutableFloatStateOf(hsl[1]) }
    var light by remember(seed) { mutableFloatStateOf(hsl[2]) }
    val candidate = {
        ColorUtils.HSLToColor(
            floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f)),
        )
    }
    val preview = previewColor(candidate())
    val commit: () -> Unit = { onCommit(candidate()) }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                .border(1.dp, C.ControlBorder, RoundedCornerShape(16.dp))
                .background(preview),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(hint, color = C.Muted, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
    GradientSlider(
        label = "色相",
        fraction = hue / 360f,
        brush = Brush.horizontalGradient(
            listOf(
                Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
                Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935),
            ),
        ),
        thumb = preview,
        onChange = { hue = it * 360f },
        onCommit = commit,
    )
    GradientSlider(
        label = "饱和度",
        fraction = sat,
        brush = Brush.horizontalGradient(
            listOf(
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), 0f, light.coerceIn(.08f, .92f)))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), .5f, light.coerceIn(.08f, .92f)))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), 1f, light.coerceIn(.08f, .92f)))),
            ),
        ),
        thumb = preview,
        onChange = { sat = it },
        onCommit = commit,
    )
    GradientSlider(
        label = "明度",
        fraction = light,
        brush = Brush.horizontalGradient(
            listOf(
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), 0f))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), .5f))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), 1f))),
            ),
        ),
        thumb = preview,
        onChange = { light = it },
        onCommit = commit,
    )
}

@Composable
private fun GradientSlider(
    label: String,
    fraction: Float,
    brush: Brush,
    thumb: Color,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val thumbPx = with(density) { 18.dp.toPx() }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, color = C.Muted, fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                    setProgress { value ->
                        onChange(value.coerceIn(0f, 1f))
                        onCommit()
                        true
                    }
                }
                .pointerInput(widthPx, thumbPx) {
                    val usable = (widthPx - thumbPx).coerceAtLeast(1f)
                    fun at(x: Float): Float = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                    val slop = viewConfiguration.touchSlop
                    // 垂直滚动优先：只有确认是水平拖动才改值；单击轨道仍直接跳转。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!cancelled) {
                                    if (!dragging) onChange(at(change.position.x))
                                    onCommit()
                                }
                                break
                            }
                            if (!change.positionChanged()) continue
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (dragging) {
                                onChange(at(change.position.x))
                                change.consume()
                            } else if (kotlin.math.abs(dx) > slop) {
                                dragging = true
                                onChange(at(change.position.x))
                                change.consume()
                            } else if (kotlin.math.abs(dy) > slop) {
                                // 纵向手势交给外层滚动，不提交、不改值。
                                cancelled = true
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(brush, CircleShape))
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (fraction.coerceIn(0f, 1f) * (widthPx - thumbPx).coerceAtLeast(0f)).roundToInt(),
                            0,
                        )
                    }
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(thumb)
                    .border(2.dp, C.Ivory.copy(alpha = .82f), CircleShape),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 自定义底色：浅色 / 深色各存一套 H / S / L*，旋钮粗调 + ±（每次 1 档）细调。
// ---------------------------------------------------------------------------

@Composable
private fun CustomBaseEditor(state: AppState, accent: Color) {
    val storedHue = state.customHue
    val storedSat = state.customSat
    val storedLstar = state.customLstar
    var hue by remember(storedHue) { mutableFloatStateOf(storedHue) }
    var sat by remember(storedSat) { mutableFloatStateOf(storedSat) }
    var lstar by remember(storedLstar) { mutableFloatStateOf(storedLstar) }
    var undo by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(C.isDay) { undo = null }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(5000)
            undo = null
        }
    }
    val palette = C.previewSurface(hue, sat, lstar)
    val previewAccent = when {
        state.customMode -> Color(state.customAccent)
        state.skinIndex in C.skins.indices ->
            if (palette.isDark) C.skins[state.skinIndex].night else C.skins[state.skinIndex].day
        else -> accent
    }
    val commit: () -> Unit = { state.applyCustomBase(hue, sat, lstar) }

    Text(
        "自定义底色", color = C.Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 18.dp),
    )
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("两套独立", color = C.Muted, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "当前：${if (C.isDay) "浅色 · 日间" else "深色 · 夜间"}",
            color = C.Muted, fontSize = 10.sp,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CustomTab(Modifier.weight(1f), "浅色 · 日间", C.isDay, accent) { state.setCustomDay(true) }
        CustomTab(Modifier.weight(1f), "深色 · 夜间", !C.isDay, accent) { state.setCustomDay(false) }
    }
    Text(
        "浅色用于日间与亮色外观，深色用于夜间；两套互不影响。明度按人眼感知（CIE L*）等距划分，50 约等于中灰。",
        color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(10.dp))
    CustomPreviewCard(palette, previewAccent, Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        KnobColumn(
            Modifier.weight(1f), "色相", "${hue.roundToInt() % 360}°", hue / 360f, true, accent,
            onFraction = { hue = it * 360f },
            onStep = { d ->
                hue = ((hue + d) % 360f + 360f) % 360f
                state.applyCustomBase(hue, sat, lstar)
            },
            onCommit = commit,
        )
        KnobColumn(
            Modifier.weight(1f), "饱和度", "${sat.roundToInt()}%", sat / 100f, false, accent,
            onFraction = { sat = it * 100f },
            onStep = { d ->
                sat = (sat + d).coerceIn(0f, 100f)
                state.applyCustomBase(hue, sat, lstar)
            },
            onCommit = commit,
        )
        KnobColumn(
            Modifier.weight(1f), "明度", "L*${lstar.roundToInt()}", lstar / 100f, false, accent,
            onFraction = { lstar = it * 100f },
            onStep = { d ->
                lstar = (lstar + d).coerceIn(0f, 100f)
                state.applyCustomBase(hue, sat, lstar)
            },
            onCommit = commit,
        )
    }
    Text(
        "旋钮粗调；− / + 每次 1 档（H 1° / S 1% / L* 1），长按可连续微调。",
        color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
    )

    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (C.isDay) "复制浅色这套 → 深色" else "复制深色这套 → 浅色",
            color = C.accentText(accent), fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    state.applyCustomBase(hue, sat, lstar)
                    state.copyCustomToOther()
                }
                .padding(vertical = 5.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "恢复这套默认", color = C.Muted, fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    val previous = floatArrayOf(hue, sat, lstar)
                    val d = if (C.isDay) {
                        floatArrayOf(
                            Lightness.DEFAULT_LIGHT_HUE,
                            Lightness.DEFAULT_LIGHT_SAT,
                            Lightness.DEFAULT_LIGHT_LSTAR,
                        )
                    } else {
                        floatArrayOf(
                            Lightness.DEFAULT_LIGHT_HUE,
                            Lightness.DEFAULT_DARK_SAT,
                            Lightness.DEFAULT_DARK_LSTAR,
                        )
                    }
                    state.applyCustomBaseFor(C.isDay, d[0], d[1], d[2])
                    undo = previous
                }
                .padding(vertical = 5.dp, horizontal = 4.dp),
        )
    }
    if (undo != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.PanelHigh)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已恢复这套默认", color = C.Muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "撤销 5s", color = C.accentText(accent), fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        undo?.let { u ->
                            state.applyCustomBaseFor(C.isDay, u[0], u[1], u[2])
                            undo = null
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    AccentStrip(state, palette, previewAccent)
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(accent).clickable { state.closeTheme() },
        contentAlignment = Alignment.Center,
    ) {
        Text("完成", color = C.onAccent(accent), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Text(
        "底色只影响外观，不改对话内容，也不额外调用 API。改动自动保存。",
        color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CustomTab(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(38.dp).clip(shape)
            .background(if (selected) C.PanelHigh else C.Panel)
            .border(2.dp, if (selected) C.accentText(accent) else C.Hair, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) C.Ivory else C.Muted, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

// 当前这套底色的实时预览：迷你欢迎页 + 正文对比徽标 + 自动派生的面板 / 正文色值。
@Composable
private fun CustomPreviewCard(palette: CustomSurface, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(palette.void)
            .border(1.dp, palette.hair, shape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Echo", color = palette.ivory, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "正文对比 %.1f:1".format(C.bodyContrast(palette)),
                color = palette.muted, fontSize = 9.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(palette.panel)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("欢迎回来，随时续上。", color = palette.ivory, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("今天想继续聊点什么？", color = palette.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.panelHigh)
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("写下第一句话，或续一页旧话…", color = palette.muted, fontSize = 9.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = C.onAccent(accent), fontSize = 10.sp)
            }
        }
        Text(
            "自动派生：面板 ${hex(palette.panel)} · 正文 ${hex(palette.ivory)} · 强调色不变",
            color = palette.muted, fontSize = 8.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// 自定义底色里的强调色入口：六个预设点，按当前预览的明暗取对应那一档。
@Composable
private fun AccentStrip(state: AppState, palette: CustomSurface, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("强调色", color = C.Muted, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        C.skins.forEachIndexed { index, skin ->
            val color = if (palette.isDark) skin.night else skin.day
            val selected = !state.customMode && state.skinIndex == index
            Box(
                Modifier.padding(end = 10.dp).size(22.dp).clip(CircleShape).background(color)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) C.accentOn(palette.void, color) else palette.hair,
                        shape = CircleShape,
                    )
                    .clickable { state.applyAccent(index) }
                    .semantics { contentDescription = "强调色 ${C.skinLabel(index)}" },
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (state.customMode) "自定义" else C.skinLabel(state.skinIndex),
            color = palette.ivory, fontSize = 10.sp,
        )
    }
}

@Composable
private fun KnobColumn(
    modifier: Modifier,
    label: String,
    valueText: String,
    fraction: Float,
    wrap: Boolean,
    accent: Color,
    onFraction: (Float) -> Unit,
    onStep: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Dial(
            valueText = valueText,
            fraction = fraction,
            wrap = wrap,
            accent = accent,
            description = "调整${label}",
            onFraction = onFraction,
            onCommit = onCommit,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton("−", "${label}减 1", -1, onStep)
            StepButton("+", "${label}加 1", 1, onStep)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = C.Muted, fontSize = 11.sp)
    }
}

// 270° 环形旋钮：按下捕获后按圆心角累加，跨 0°/360° 解缠；手指可以移到旋钮外继续拖。
@Composable
private fun Dial(
    valueText: String,
    fraction: Float,
    wrap: Boolean,
    accent: Color,
    description: String,
    onFraction: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val f = if (wrap) ((fraction % 1f) + 1f) % 1f else fraction.coerceIn(0f, 1f)
    val currentF = rememberUpdatedState(f)
    val currentOnFraction = rememberUpdatedState(onFraction)
    val currentOnCommit = rememberUpdatedState(onCommit)
    Box(
        Modifier
            .size(80.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { value ->
                    currentOnFraction.value(value.coerceIn(0f, 1f))
                    currentOnCommit.value()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    fun angleOf(p: Offset): Float = Math.toDegrees(
                        atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble()),
                    ).toFloat()
                    val base = currentF.value
                    var last = angleOf(down.position)
                    var total = 0f
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) break
                        if (!change.positionChanged()) continue
                        val a = angleOf(change.position)
                        var delta = a - last
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        last = a
                        total += delta
                        if (abs(total) > 1.5f) moved = true
                        var nf = base + total / 270f
                        nf = if (wrap) ((nf % 1f) + 1f) % 1f else nf.coerceIn(0f, 1f)
                        currentOnFraction.value(nf)
                        change.consume()
                    }
                    if (moved) currentOnCommit.value()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val radius = (size.minDimension - 16.dp.toPx()) / 2f
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            drawArc(
                color = C.ControlBorder.copy(alpha = .55f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = 135f,
                sweepAngle = 270f * f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
            val rad = Math.toRadians((135f + 270f * f).toDouble())
            drawCircle(
                color = C.Ivory,
                radius = 5.dp.toPx(),
                center = Offset(
                    center.x + cos(rad).toFloat() * radius,
                    center.y + sin(rad).toFloat() * radius,
                ),
            )
        }
        Text(
            valueText, color = C.Ivory, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

// 单击 1 档；按住 380ms 后每 70ms 连发，松手立刻停。
@Composable
private fun StepButton(label: String, description: String, delta: Int, onStep: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val currentOnStep = rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(380)
        while (pressed) {
            currentOnStep.value(delta)
            delay(70)
        }
    }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(C.PanelHigh)
            .border(1.dp, C.Hair, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pressedAt = System.currentTimeMillis()
                    pressed = true
                    var held = true
                    while (held) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            held = false
                        } else if (change.positionChanged()) {
                            change.consume()
                        }
                    }
                    pressed = false
                    if (System.currentTimeMillis() - pressedAt < 380) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnStep.value(delta)
                    }
                }
            }
            .semantics {
                contentDescription = description
                onClick(label = description) {
                    currentOnStep.value(delta)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = C.Ivory, fontSize = 17.sp)
    }
}

private fun hex(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

