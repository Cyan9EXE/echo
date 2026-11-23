package app.echo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun Welcome(
    state: AppState,
    accent: Color,
    onImport: () -> Unit,
    appear: Animatable<Float, AnimationVector1D>,
) {
    fun phase(start: Float, span: Float): Float = smooth01((appear.value - start) / span)

    Column(Modifier.fillMaxSize()) {
        TopBar(state, accent)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 横屏或窗口较矮时，标题区会超过可视高度：允许整体滚动；
            // 空间富余时用 heightIn(min) 撑满，再由 Arrangement.Center 保持原来的垂直居中。
            val roomy = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = roomy)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 横屏只有 ~120dp 内容高度：隐藏图标、压缩标题，并把三个入口提到续聊卡前面，
                // 保证「打开旧会话 / 导入 / 设置」不必滚动就能点到；竖屏保持原样（图标、28sp、卡片在上）。
                // 用窗口配置判断横屏，而不是 maxHeight：输入法弹起会把 maxHeight 压到 ~336dp，
                // 那样聚焦输入框的瞬间会误判为"横屏"，图标与标题突然跳变。
                val configuration = LocalConfiguration.current
                val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                    configuration.screenHeightDp < 520
                val links: @Composable () -> Unit = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.rise(8.dp) { phase(.42f, .58f) },
                    ) {
                        Text("打开旧会话", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { state.showDrawer = true })
                        Text("·", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {})
                        Text("导入历史对话", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { onImport() })
                        Text("·", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {})
                        Text("设置", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { state.openSettings() })
                    }
                }
                if (!compact) {
                    Box(Modifier.rise(16.dp) { phase(0f, .875f) }) {
                        EchoMark(color = accent, blinking = !state.typing)
                    }
                    Spacer(Modifier.height(28.dp))
                }
                Column(Modifier.rise(12.dp) { phase(.125f, .875f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "欢迎回来，随时续上。",
                        color = C.Ivory,
                        fontSize = if (compact) 22.sp else 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(if (compact) 14.dp else 34.dp))
                if (state.indexLoading && state.sessions.isEmpty()) {
                    Box(Modifier.rise(8.dp) { phase(.2f, .8f) }) {
                        Text("正在恢复会话索引…", color = C.Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                }
                if (compact) {
                    links()
                    Spacer(Modifier.height(14.dp))
                    if (state.sessions.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().rise(10.dp) { phase(.29f, .75f) }) { ResumeCard(state, accent) }
                    }
                } else {
                    if (state.sessions.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().rise(10.dp) { phase(.29f, .75f) }) { ResumeCard(state, accent) }
                        Spacer(Modifier.height(18.dp))
                    }
                    links()
                }
            }
        }
        Box(Modifier.rise(10.dp) { phase(.25f, .67f) }) { Composer(state, accent) }
    }
}

@Composable
internal fun ResumeCard(state: AppState, accent: Color) {
    val latest = state.sessions.firstOrNull() ?: return
    Pressable(
        onClick = { state.open(latest) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        scale = .98f,
        description = "继续上次：" + latest.title,
    ) {
        Surface(color = C.Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("继续上次", color = C.Muted, fontSize = 11.sp)
                    Text(latest.title, color = C.Ivory, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        latest.messages.count { it.role == "user" }.toString() + " 轮 · " + fmtTime(latest.updatedAt),
                        color = C.Muted, fontSize = 10.sp,
                    )
                }
                Text("›", color = C.Muted, fontSize = 22.sp)
            }
        }
    }
}
