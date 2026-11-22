package app.echo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
    val readLabel = when {
        readingThis && Reader.loading -> "准备中…"
        readingThis && Reader.paused -> "继续朗读"
        readingThis -> "暂停朗读"
        else -> "朗读"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Echo", color = C.Muted, fontSize = 11.sp)
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
        AnswerText(m)
        if (m.citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CitationsBlock(m.citations, accent)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("复制") {
                if (m.text.isNotBlank()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Echo reply", m.text))
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
            ActionChip("重试") {
                if (state.streaming) {
                    Toast.makeText(context, "正在生成，请稍后再试", Toast.LENGTH_SHORT).show()
                } else {
                    state.retryLast()
                }
            }
        }
        if (m.inputTokens > 0 || m.outputTokens > 0) {
            Spacer(Modifier.height(5.dp))
            val shownCost = if (m.costCny > 0.0) m.costCny else state.msgCost(m)
            Text(
                "本轮 " + fmtCny(shownCost) + " · 输入 " + m.inputTokens + " / 输出 " + m.outputTokens + " tokens",
                color = C.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
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
    val billed = if (live) 0 else Billing.ttsBilledChars(m.text)
    val readChip = if (readingThis || live || billed <= 0) readLabel
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
    if (thinking.isBlank()) return
    var open by remember(m.id) { mutableStateOf(live) }
    val scroll = rememberScrollState()
    val body = if (live) thinking.takeLast(420) else thinking
    LaunchedEffect(live) {
        if (!live) scroll.scrollTo(0)
    }
    Surface(color = C.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        m.text.isBlank() -> "思考过程 · 生成中"
                        live -> "思考过程 · " + thinking.length + " 字 · 显示最新片段"
                        else -> "思考过程 · " + thinking.length + " 字"
                    },
                    color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                Text(
                    if (open) "收起" else "展开",
                    color = accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { open = !open }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (open) {
                Spacer(Modifier.height(5.dp))
                Text(
                    body,
                    color = C.Muted, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = if (live) Modifier.fillMaxWidth()
                    else Modifier.heightIn(max = 260.dp).verticalScroll(scroll),
                )
            }
        }
    }
}

@Composable
internal fun AnswerText(m: Msg) {
    val text = m.text
    val thinking = m.thinking
    Text(text.ifBlank { if (thinking.isNotBlank()) "（思考中）" else "…" }, color = C.Ivory, fontSize = 16.sp, lineHeight = 26.sp)
}
