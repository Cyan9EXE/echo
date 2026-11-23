package app.echo

import android.content.ClipData
import kotlin.math.abs
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun CitationsBlock(citations: List<Citation>, accent: Color) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Text("来源", color = C.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        citations.forEachIndexed { index, c ->
            Pressable(
                onClick = { runCatching { uri.openUri(c.url) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                scale = .99f,
                description = "打开来源 " + c.title.ifBlank { c.url },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text((index + 1).toString(), color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        c.title.ifBlank { c.url },
                        color = C.Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = C.PromptBg, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)) {
            Text(text, color = C.Ivory, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
internal fun AssistantBubble(m: Msg, accent: Color, state: AppState, live: Boolean = false) {
    val context = LocalContext.current
    var askTts by remember(m.id) { mutableStateOf(false) }
    val readingThis = Reader.activeId == m.id &&
        (Reader.loading || Reader.speaking || Reader.paused)
    // 暂停优先于 loading：缓冲阶段点暂停后 track 已压住、不会再出声，
    // 界面必须给「继续朗读」而不是一直显示「准备中…」（P2-1）。
    val readLabel = when {
        readingThis && Reader.paused -> "继续朗读"
        readingThis && Reader.loading -> "准备中…"
        readingThis -> "暂停朗读"
        else -> "朗读"
    }
    // 新气泡淡入 + 轻微上移：流式回复刚开始时整段出现，硬切会显得突兀。
    val enter = remember { Animatable(if (live) 0f else 1f) }
    LaunchedEffect(m.id, live) {
        if (live) {
            enter.snapTo(0f)
            enter.animateTo(1f, tween(180))
        } else {
            enter.snapTo(1f)
        }
    }
    Column(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = enter.value
            translationY = (1f - enter.value) * 6.dp.toPx()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DeepSeek", color = C.Muted, fontSize = 11.sp)
            if (m.model.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(m.model, color = C.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (m.citations.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("联网", color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(7.dp))
        ThinkingBlock(m, accent, live)
        if (m.text.isNotBlank() || m.thinking.isBlank()) AnswerText(m, live = live, accent = accent)
        if (m.citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CitationsBlock(m.citations, accent)
        }
        // 与官方一致：流式生成期间不显示操作区与计费行，生成结束后再出现。
        if (!live) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("复制") {
                    if (m.text.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek 回复", m.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                ReadControl(
                    m = m,
                    state = state,
                    accent = accent,
                    live = live,
                    readingThis = readingThis,
                    readLabel = readLabel,
                    askTts = askTts,
                    onAskTts = { askTts = it },
                )
                if (readingThis) {
                    ActionChip("停止") { Reader.stopAll() }
                }
                ActionChip("重试") {
                    if (state.streaming) {
                        Toast.makeText(context, "正在生成，请稍后再试", Toast.LENGTH_SHORT).show()
                    } else {
                        state.retryLast()
                    }
                }
            }
            if (state.showBillingHints && (m.inputTokens > 0 || m.outputTokens > 0)) {
                Spacer(Modifier.height(5.dp))
                val shownCost = if (m.costCny > 0.0) m.costCny else state.msgCost(m)
                // 旧版本保存的消息没有输入侧用量，只显示真实存在的输出量，避免「输入 0」这种假数据。
                val usageText = if (m.inputTokens > 0) {
                    " · 输入 " + m.inputTokens + " / 输出 " + m.outputTokens + " tokens"
                } else {
                    " · 输出 " + m.outputTokens + " tokens"
                }
                Text(
                    "本轮 " + fmtCny(shownCost) + usageText,
                    color = C.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
internal fun ReadControl(
    m: Msg,
    state: AppState,
    accent: Color,
    live: Boolean,
    readingThis: Boolean,
    readLabel: String,
    askTts: Boolean,
    onAskTts: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val billed = remember(m.text, live) {
        if (live) 0 else Billing.ttsBilledChars(m.text)
    }
    val readChip = if (readingThis || live || billed <= 0 || !state.showBillingHints) readLabel
        else readLabel + " " + Billing.estimate(Billing.ttsCost(billed))
    val readMax = Billing.quote(Billing.ttsCost(billed))
    Box {
        ActionChip(readChip) {
            when {
                readingThis -> if (Reader.paused) Reader.resume() else Reader.pause()
                live -> Toast.makeText(context, "回答仍在生成，完成后可朗读", Toast.LENGTH_SHORT).show()
                m.text.isBlank() -> Toast.makeText(context, "这条回复还没有内容", Toast.LENGTH_SHORT).show()
                state.apiKey.isBlank() -> {
                    Toast.makeText(context, "填入 API Key 后才能使用官方朗读", Toast.LENGTH_SHORT).show()
                    state.openSettings()
                }
                state.ttsSkipConfirm -> Reader.start(
                    context, m.text, m.id, state.ttsVoice, state.apiKey,
                )
                else -> onAskTts(true)
            }
        }
        BillingConfirm(
            expanded = askTts,
            title = "朗读该段对话需要计费",
            detail = "本段约 " + billed + " 计费字符。",
            note = "最高约 " + readMax + "，按 ¥3 / 万计费字符（汉字计 2 字符、其他计 1 字符）。" +
                "费用由你的 API Key 直接支付给 DeepSeek，本应用不预扣、不代收；" +
                "公开 API 暂不支持断点续播，停止或断网后需重新朗读。",
            confirmLabel = "开始朗读",
            accent = accent,
            onDismiss = { onAskTts(false) },
            onConfirm = { skip ->
                if (skip) state.updateTtsSkipConfirm(true)
                onAskTts(false)
                Reader.start(context, m.text, m.id, state.ttsVoice, state.apiKey)
            },
        )
    }
}

@Composable
internal fun ThinkingBlock(m: Msg, accent: Color, live: Boolean) {
    val thinking = m.thinking
    val answerStarted = m.text.isNotBlank()
    // While waiting for the first token the official app still shows a small
    // "正在思考" header; once the answer starts it collapses to one line.
    if (thinking.isBlank() && (!live || answerStarted)) return
    var open by remember(m.id) { mutableStateOf(true) }
    var collapsedOnce by remember(m.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(live, answerStarted) {
        if (live && answerStarted && !collapsedOnce) {
            collapsedOnce = true
            open = false
        }
    }
    // Pin the panel to its newest line while expanded. The window below is a
    // fixed-height box, so the list item stops resizing on every token.
    LaunchedEffect(live, open) {
        if (live && open) {
            snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
        }
    }
    val title = when {
        !answerStarted -> "正在思考"
        m.thinkingMs > 0L -> "已思考（用时 " + (((m.thinkingMs + 500) / 1000).coerceAtLeast(1)) + " 秒）"
        else -> "已思考"
    }
    Surface(color = C.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (thinking.isNotBlank()) {
                    Text(
                        if (open) "收起" else "展开",
                        color = accent, fontSize = 11.sp,
                        modifier = Modifier.clickable { open = !open }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (open && thinking.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                // A trailing window keeps per-token layout bounded; the fixed max
                // height keeps the bubble from growing/shrinking every frame.
                val body = if (live) thinking.takeLast(1400) else thinking
                Text(
                    body,
                    color = C.Muted, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = if (live) 180.dp else 260.dp)
                        .verticalScroll(scroll),
                )
            }
        }
    }
}

// 首 token 到达前的三点呼吸，替代原来静止的「…」。
@Composable
private fun TypingDots(color: Color) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val t = (phase.value + i * .18f) % 1f
            val tri = 1f - abs(t - .5f) * 2f
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(5.dp)
                    .background(color.copy(alpha = .22f + .6f * tri), CircleShape),
            )
        }
    }
}

@Composable
internal fun AnswerText(m: Msg, live: Boolean = false, accent: Color = C.Blue) {
    val text = m.text
    val thinking = m.thinking
    if (text.isBlank()) {
        if (live) {
            TypingDots(accent)
            return
        }
        Text(if (thinking.isNotBlank()) "（思考中）" else "…", color = C.Ivory, fontSize = 16.sp, lineHeight = 26.sp)
        return
    }
    // 正文交给 Markdown/LaTeX 渲染；流式增量按空行切段并缓存稳定段。
    MarkdownText(text, live = live, accent = accent)
}
