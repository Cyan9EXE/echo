package app.echo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Rect
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
            AccentSwitcher(state)
            Spacer(Modifier.width(6.dp))
            TapTarget(size = 48.dp, visual = 48.dp, onClick = { state.openSettings() }, description = "设置") {
                MoreGlyph(Modifier.size(20.dp), C.Muted)
            }
        } else {
            DayNightToggle(progress = C.themeProgress, accent = accent, onClick = state::toggleDay)
            Spacer(Modifier.width(14.dp))
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
    LaunchedEffect(blinking) {
        if (!blinking) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
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
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spring(dampingRatio = .6f, stiffness = 900f),
        label = "pressScale",
    )
    val wash by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(110),
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
    ) {
        content()
        if (wash > .001f) {
            Box(Modifier.matchParentSize().background(C.Ivory.copy(alpha = .05f * wash)))
        }
    }
}

@Composable
internal fun DayNightToggle(progress: Float, accent: Color, onClick: () -> Unit) {
    val p = progress.coerceIn(0f, 1f)
    val isDay = p > .5f
    TapTarget(
        size = 48.dp,
        visual = 44.dp,
        onClick = onClick,
        description = if (isDay) "当前日间，点击切换到夜间" else "当前夜间，点击切换到日间",
    ) {
        Canvas(Modifier.size(30.dp)) {
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
        animationSpec = spring(dampingRatio = .58f, stiffness = 900f),
        label = "tapScale",
    )
    val haze by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(110),
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
internal fun Chip(icon: String, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val color = if (active) accent else C.Muted
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 34.dp)
            .border(1.dp, if (active) accent.copy(alpha = .5f) else C.Hair, RoundedCornerShape(17.dp)),
        shape = RoundedCornerShape(17.dp),
        scale = .95f,
        description = label,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = color, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun AccentSwitcher(state: AppState) {
    Row(
        Modifier.height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        C.skins.forEachIndexed { i, s ->
            val on = state.skinIndex == i
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { state.updateSkin(i) }
                    .semantics { contentDescription = "强调色 " + s.label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (on) 13.dp else 9.dp)
                        .clip(CircleShape)
                        .background(if (C.isDay) s.day else s.night)
                        .then(if (on) Modifier.border(1.5.dp, C.Ivory, CircleShape) else Modifier),
                )
            }
        }
    }
}

@Composable
internal fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(C.Panel.copy(alpha = 0.65f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = C.Ivory.copy(alpha = 0.82f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Scrim(alpha: Float, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)).clickable(onClick = onClick))
}
