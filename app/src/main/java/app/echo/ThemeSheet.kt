package app.echo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

// 主题面板：外观（日间 / 夜间 / 暖黄 / 淡绿 / 自选）+ 预设矩阵（4 × 5）+ 强调色与两个 HSL 编辑器。
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
                    "改完立即生效；日间为纸白，夜间为墨黑。",
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
                        !C.isDay, accent,
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
                        if (C.customThemeOn) C.customField() else C.fieldPreview(state.customThemeArgb),
                        C.isDay && C.customThemeOn, accent,
                    ) { state.applyAppearance(C.Look.CUSTOM) }
                }
                Text(
                    "暖黄与淡绿是两档护眼配色，只在日间生效；底色、面板、分割线都会跟着走，强调色不变。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                )

                if (C.isDay && C.customThemeOn) {
                    HslEditor(
                        seed = state.customThemeArgb,
                        title = "自定义底色",
                        hint = "松开滑杆后生效；色块与「自选」瓦片同步预览。",
                        previewColor = { C.fieldPreview(it) },
                        onCommit = { state.applyCustomTheme(it) },
                    )
                }

                SheetSection("预设")
                PresetGrid(state, accent)
                Text(
                    "预设只是「外观 × 强调色」的组合，可以交叉使用，例如暖黄底配雾蓝强调。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                )

                SheetSection("强调色")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    C.skins.forEachIndexed { index, _ ->
                        ThemeSwatch(
                            modifier = Modifier.weight(1f),
                            label = C.skinLabel(index),
                            mark = C.mark(index, Color.Transparent, C.accentAt(index.toFloat())),
                            accent = C.accentAt(index.toFloat()),
                            selected = !state.customMode && state.skinIndex == index,
                            onClick = { state.applyAccent(index) },
                        )
                    }
                }
                CustomAccentRow(state, accent)
                Text(
                    "色块上方是底色、下方是主题色；灰紫承接原来的中性位，夜间更亮、日间更沉。",
                    color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
                )

                if (state.customMode) {
                    HslEditor(
                        seed = state.customAccent,
                        title = "自定义强调色",
                        hint = "松开滑杆后生效；指示块会同步显示自定义配色。",
                        previewColor = { Color(it) },
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
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 70.dp)
            .clip(shape)
            .background(if (selected) C.PanelHigh else C.Panel)
            .border(1.dp, if (selected) accent else C.Hair, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(26.dp, 30.dp).clip(RoundedCornerShape(8.dp)).background(field)
                .border(1.dp, C.ControlBorder, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            label, color = if (selected) C.Ivory else C.Muted, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

// 4 行外观 × 5 列强调色的矩阵：色块是底色，右下角小点是该组合的强调色。
@Composable
private fun PresetGrid(state: AppState, accent: Color) {
    val looks = listOf(C.Look.PAPER, C.Look.INK, C.Look.WARM, C.Look.GREEN)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(32.dp))
            C.skins.forEach { skin ->
                Text(
                    skin.label, color = C.Muted, fontSize = 9.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        looks.forEach { look ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(look.zh, color = C.Muted, fontSize = 9.sp, modifier = Modifier.width(32.dp))
                C.presets.filter { it.appearance == look }.forEach { preset ->
                    val selected = !state.customThemeOn && !state.customMode &&
                        C.look == preset.appearance && state.skinIndex == preset.accent
                    PresetChip(Modifier.weight(1f), preset, selected, accent) {
                        state.applyPreset(preset)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    modifier: Modifier,
    preset: Preset,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(30.dp)
            .clip(shape)
            .background(C.previewVoid(preset.appearance))
            .border(1.dp, if (selected) accent else C.Hair, shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(4.dp).size(9.dp).clip(CircleShape)
                .background(C.previewAccent(preset.appearance, preset.accent)),
        )
    }
}

@Composable
private fun ThemeSwatch(
    modifier: Modifier = Modifier,
    label: String,
    mark: ThemeMark,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 78.dp)
            .clip(shape)
            .background(if (selected) C.PanelHigh else C.Panel)
            .border(1.5.dp, if (selected) accent else C.Hair, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeMarkView(mark, 26.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) C.Ivory else C.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CustomAccentRow(state: AppState, accent: Color) {
    val shape = RoundedCornerShape(14.dp)
    val color = Color(state.customAccent)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .background(if (state.customMode) C.PanelHigh else C.Panel)
            .border(1.dp, if (state.customMode) accent else C.Hair, shape)
            .clickable { state.selectCustomAccent(state.customAccent) }
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
            Text("HSL 滑杆选色；冒号指示块同步显示自定义配色。", color = C.Muted, fontSize = 10.sp)
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
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), .04f))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), .5f))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), .96f))),
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
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onChange((offset.x / widthPx).coerceIn(0f, 1f)) },
                        onDragEnd = { onCommit() },
                        onDragCancel = { onCommit() },
                    ) { change, _ ->
                        onChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(brush, CircleShape))
            Box(
                Modifier
                    .offset { IntOffset((fraction.coerceIn(0f, 1f) * (widthPx - thumbPx)).roundToInt(), 0) }
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(thumb)
                    .border(2.dp, C.Ivory.copy(alpha = .82f), CircleShape),
            )
        }
    }
}
