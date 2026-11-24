package app.echo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun TopBar(state: AppState, accent: Color, title: String? = null, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TapTarget(
            size = 48.dp,
            visual = 44.dp,
            onClick = { if (onBack != null) onBack() else state.showDrawer = true },
            description = if (onBack != null) "返回" else "会话列表",
        ) {
            if (onBack != null) {
                BackGlyph(Modifier.size(20.dp), C.Ivory)
            } else {
                MenuGlyph(Modifier.size(20.dp), C.Ivory)
            }
        }
        Spacer(Modifier.width(4.dp))
        if (title != null) {
            Text(title, color = C.Ivory, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("Echo", color = C.Ivory, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (onBack != null) {
            ThemeSwitcher(state, accent)
            Spacer(Modifier.width(6.dp))
            TapTarget(size = 48.dp, visual = 48.dp, onClick = { state.openSettings() }, description = "设置") {
                MoreGlyph(Modifier.size(20.dp), C.Muted)
            }
        } else {
            ThemeSwitcher(state, accent)
            Spacer(Modifier.width(10.dp))
            TapTarget(size = 48.dp, visual = 44.dp, onClick = { state.newChat() }, description = "新建对话") {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).border(1.dp, C.ControlBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PlusGlyph(Modifier.size(18.dp), C.Ivory) }
            }
        }
    }
}

@Composable
internal fun EchoMark(
    modifier: Modifier = Modifier,
    color: Color = C.Blue,
    blinking: Boolean = true,
) {
    val alpha = remember { Animatable(1f) }
    // 动画开关变化也要重新评估：关闭系统动画后立即停在常亮（P2-6）。
    val motion = Motion.animationsEnabled
    LaunchedEffect(blinking, motion) {
        if (!blinking || !motion) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        // 入场只呼吸两轮，之后保持常亮：无限动画会被读成 loading，
        // 也阻止页面进入 idle（R4-P2-2）。
        repeat(2) {
            delay(1100)
            alpha.animateTo(.10f, tween(durationMillis = 120, easing = FastOutSlowInEasing))
            delay(660)
            alpha.animateTo(1f, tween(durationMillis = 120, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier
            .width(200.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(C.Panel.copy(alpha = .35f))
            .border(1.dp, C.Hair, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = 12.dp)
                .width(2.dp)
                .height(18.dp)
                .background(color.copy(alpha = alpha.value), RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
internal fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    scale: Float = .96f,
    description: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按压反馈也必须尊重系统的「关闭动画」；开启时统一用 Motion 的时长/曲线（P2-6）。
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (Motion.animationsEnabled) spring(dampingRatio = .6f, stiffness = 900f) else snap(),
        label = "pressScale",
    )
    val wash by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(Motion.dur(110, Motion.animationsEnabled)),
        label = "pressWash",
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = contentAlignment,
    ) {
        content()
        if (wash > .001f) {
            Box(Modifier.matchParentSize().background(C.Ivory.copy(alpha = .05f * wash)))
        }
    }
}

/** 主题指示块：像冒号那样上下两块 —— 上块是底色提示（白亮/灰暗/护眼色），下块是主题色。四态由 [C.mark] 给出。 */
@Composable
internal fun ThemeMarkView(mark: ThemeMark, size: Dp) {
    val block = size * .42f
    val radius = RoundedCornerShape(block * .34f)
    Column(
        Modifier.size(size),
        verticalArrangement = Arrangement.spacedBy(size * .16f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(block).clip(radius).background(mark.top))
        Box(Modifier.size(block).clip(radius).background(mark.bottom))
    }
}

/** 日/夜图标：月亮的挖洞逐渐合上、光线散开，形状连续 morph 成太阳。 */
@Composable
internal fun SunMoonMark(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        val d = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val close = smooth01(p / .52f)
        val flare = smooth01((p - .50f) / .50f)
        val core = d * (.345f - .120f * smooth01(p))
        val bite = d * .312f * (1f - close)
        val body = Path().apply { addOval(Rect(c.x - core, c.y - core, c.x + core, c.y + core)) }
        val disc = if (bite > .002f) {
            val hole = Path().apply {
                addOval(
                    Rect(
                        c.x + d * .155f - bite,
                        c.y - d * .095f - bite,
                        c.x + d * .155f + bite,
                        c.y - d * .095f + bite,
                    ),
                )
            }
            Path.combine(PathOperation.Difference, body, hole)
        } else {
            body
        }
        withTransform({
            rotate(degrees = 18f * (1f - close), pivot = c)
            scale(scaleX = .88f + .12f * close, scaleY = .88f + .12f * close, pivot = c)
        }) {
            drawPath(disc, accent)
        }
        if (flare > .002f) {
            val inner = core + d * .048f
            val outer = inner + d * .175f * flare
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                drawLine(
                    color = accent.copy(alpha = .35f + .65f * flare),
                    start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                    strokeWidth = d * .062f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 顶栏主题区：日/夜按钮（点=切换，按住下滑=打开面板）+ 冒号指示块（点=打开面板）。 */
@Composable
internal fun ThemeSwitcher(state: AppState, accent: Color) {
    val mark = C.mark(state.skinIndex, accent)
    val label = if (state.customMode) "自定义" else C.skinLabel(state.skinIndex)
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 26.dp.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    state.toggleDay()
                }
                .pointerInput(Unit) {
                    var travelled = 0f
                    var opened = false
                    detectVerticalDragGestures(
                        onDragStart = { travelled = 0f; opened = false },
                        onDragEnd = { travelled = 0f },
                        onDragCancel = { travelled = 0f },
                    ) { _, dy ->
                        if (!opened && dy > 0f) {
                            travelled += dy
                            if (travelled > threshold) {
                                opened = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.openTheme()
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = "日夜间切换，当前" + (if (C.isDay) "日间" else "夜间") +
                        "；按住下滑打开主题面板"
                },
            contentAlignment = Alignment.Center,
        ) {
            val sunMoonProgress by animateFloatAsState(
                targetValue = C.themeProgress,
                animationSpec = tween(Motion.dur(220, Motion.animationsEnabled)),
                label = "sunMoon",
            )
            SunMoonMark(progress = sunMoonProgress, accent = accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    state.openTheme()
                }
                .semantics { contentDescription = "主题 $label；点按打开主题面板" },
            contentAlignment = Alignment.Center,
        ) {
            ThemeMarkView(mark, 20.dp)
        }
    }
}

@Composable
internal fun TapTarget(
    size: Dp,
    visual: Dp,
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = if (Motion.animationsEnabled) spring(dampingRatio = .58f, stiffness = 900f) else snap(),
        label = "tapScale",
    )
    val haze by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(Motion.dur(110, Motion.animationsEnabled)),
        label = "tapHaze",
    )
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(visual).clip(CircleShape).background(C.Ivory.copy(alpha = .07f * haze)))
        content()
    }
}

@Composable
internal fun Chip(
    icon: @Composable (Color) -> Unit,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val color = if (active) C.accentText(accent) else C.Muted
    // 比例对齐官方 App：高 24dp、全圆角、字号 12sp，图标 12dp、间距 4dp。
    // 官方实测（1080x2388 / 474dpi）：chip 高 72px=24.3dp、内文 12sp、左右内距约 10dp。
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 24.dp)
            .border(1.dp, if (active) C.accentText(accent) else C.Hair, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        scale = .95f,
        description = label,
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) { icon(color) }
            Spacer(Modifier.width(4.dp))
            Text(
                label, color = color, fontSize = 12.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
internal fun ActionChip(label: String, onClick: () -> Unit) {
    // 宽度足够，只收紧高度；文字用固定行高保证视觉垂直居中。
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(C.Panel.copy(alpha = 0.65f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = C.Ivory.copy(alpha = 0.82f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.offset(y = 0.5.dp),
        )
    }
}

@Composable
internal fun Scrim(alpha: Float, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)).clickable(onClick = onClick))
}
