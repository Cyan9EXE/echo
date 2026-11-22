package app.echo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ChatScreen(state: AppState, accent: Color) {
    val s = state.current ?: return
    Column(Modifier.fillMaxSize()) {
        TopBar(state, accent, s.title, onBack = state::backHome)
        ContextBar(state, accent)
        if (s.expired && !s.revived) RevivalBanner(state, s, accent)
        if (s.revived && s.contextMode == "summary" && s.summary.isNotBlank()) {
            SummaryCard(state, s, accent)
        }
        val listState = rememberLazyListState()
        val liveId = if (state.streaming) s.messages.lastOrNull()?.id else null
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                s.messages,
                key = { index, m -> if (s.messages.ready) m.id else "stub" + index },
            ) { _, m ->
                Box(if (liveId == null) Modifier.animateItem() else Modifier) {
                    if (m.role == "user") UserBubble(m.text) else AssistantBubble(m, accent, state, live = m.id == liveId)
                }
            }
            if (state.streaming) {
                item { Text("正在生成…", color = C.Muted, fontSize = 12.sp) }
            }
        }
        LaunchedEffect(s.messages.size) {
            if (s.messages.isNotEmpty()) {
                listState.scrollToItem(s.messages.size - 1)
            }
        }
        LaunchedEffect(liveId) {
            if (liveId == null) return@LaunchedEffect
            while (true) {
                delay(80)
                if (!listState.isScrollInProgress && s.messages.isNotEmpty()) {
                    runCatching { listState.scrollToItem(s.messages.size, 0) }
                }
            }
        }
        if (state.apiKey.isBlank() && s.messages.any { it.role == "assistant" && it.failed }) {
            Pressable(
                onClick = { state.openSettings() },
                modifier = Modifier.padding(horizontal = 14.dp),
                shape = RoundedCornerShape(9.dp),
                scale = .97f,
                description = "去设置里填写 API Key",
            ) {
                Text("去设置里填写 API Key", color = accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
        Composer(state, accent)
    }
}

@Composable
internal fun SummaryCard(state: AppState, s: Session, accent: Color) {
    var expanded by remember(s.id, s.summary) { mutableStateOf(false) }
    val blocks = s.summaryBlocks
    val blockCount = blocks.size
    Surface(
        color = C.Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("前部摘要 · 当前不是完整上下文", color = C.Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (blockCount == 0) "更早的对话已整理成要点" else "更早的对话已整理为 " + blockCount + " 个主题块",
                        color = C.Ivory,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Pressable(onClick = { state.revive(s) }, description = "切回完整原文") {
                    Text("切回完整原文", color = accent, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (blockCount == 0) {
                Text(
                    s.summary.take(260) + if (s.summary.length > 260) "…" else "",
                    color = C.Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Pressable(onClick = { state.revive(s, "summary") }, description = "重新生成本机主题摘要") {
                    Text("重新生成主题块", color = accent, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            } else if (!expanded) {
                val first = blocks.first()
                Text(first.title, color = C.Ivory, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                first.bullets.take(2).forEach { bullet ->
                    Spacer(Modifier.height(3.dp))
                    Text("- " + bullet, color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2)
                }
                Spacer(Modifier.height(4.dp))
                Pressable(onClick = { expanded = true }, description = "展开全部主题块") {
                    Text(
                        if (blockCount == 1) "展开主题块" else "展开全部 " + blockCount + " 个主题块",
                        color = accent,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                blocks.forEachIndexed { index, block ->
                    SummaryBlockView(state, s, block, accent)
                    if (index != blockCount - 1) {
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Hair.copy(alpha = .5f)))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Pressable(onClick = { expanded = false }, description = "收起主题块") {
                    Text("收起主题块", color = accent, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("摘要模式只发送摘要+后部原文；原文仍保存在本机。", color = C.Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
internal fun SummaryBlockView(state: AppState, session: Session, block: SummaryBlock, accent: Color) {
    var showOriginal by remember(session.id, block.title) { mutableStateOf(false) }
    val messages = remember(session.id, block.title, showOriginal) {
        if (showOriginal) state.summaryBlockMessages(session, block) else emptyList()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(block.title, color = C.Ivory, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Pressable(onClick = { showOriginal = !showOriginal }, description = if (showOriginal) "收起该主题的原文" else "查看该主题的原文") {
                Text(if (showOriginal) "收起原文" else "查看原文", color = accent, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp))
            }
        }
        block.bullets.forEach { bullet ->
            Spacer(Modifier.height(3.dp))
            Text("- " + bullet, color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (block.bullets.isEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text("该主题没有可展示的要点。", color = C.Muted, fontSize = 10.sp)
        }
        if (showOriginal) {
            Spacer(Modifier.height(6.dp))
            Surface(color = C.Hair.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("该主题的原文 · 仅本机可见", color = C.Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    if (messages.isEmpty()) {
                        Text("这些原文已不在本机，可能已被清理。", color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    } else {
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            messages.forEachIndexed { index, message ->
                                if (index > 0) Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (message.role == "user") "你" else "助手",
                                        color = if (message.role == "user") C.Muted else accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(fmtTime(message.time), color = C.Muted, fontSize = 9.sp)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(message.text, color = C.Ivory, fontSize = 11.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RevivalBanner(state: AppState, s: Session, accent: Color) {
    Surface(
        color = C.Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("这段对话已超过 7 天未活跃", color = C.Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text("本地 ZIP 记录仍在。复活后，当前分支完整原文会作为后续上下文。", color = C.Muted, fontSize = 11.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pressable(
                    onClick = { state.revive(s) },
                    shape = RoundedCornerShape(9.dp),
                    scale = .96f,
                    description = "完整原文复活",
                ) {
                    Text("完整原文复活", color = C.Void, fontSize = 12.sp, modifier = Modifier.background(accent, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 7.dp))
                }
                Spacer(Modifier.width(10.dp))
                Pressable(
                    onClick = { state.revive(s, "summary") },
                    shape = RoundedCornerShape(9.dp),
                    scale = .96f,
                    description = "前部摘要 + 后部原文",
                ) {
                    Text("前部摘要 + 后部原文", color = accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text("摘要模式保留最近 20 轮原文，更早内容由本机压成要点；不调用 API、不额外消耗 token。", color = C.Muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
internal fun ContextBar(state: AppState, accent: Color) {
    val s = state.current
    val tokens = state.estimateTokens()
    val mode = if (s?.contextMode == "summary" && !s.summary.isNullOrBlank()) "摘要" else "原文"
    val fraction = (tokens / 1_000_000f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = accent,
                trackColor = C.Hair,
            )
            Spacer(Modifier.width(10.dp))
            Text(tokens.toString() + " / 1M · " + (((fraction * 1000).toInt()) / 10).toString() + "% · " + mode, color = C.Muted, fontSize = 10.sp)
            if ((s?.costCny ?: 0.0) > 0.0) {
                Spacer(Modifier.width(6.dp))
                Text("≈" + fmtCny(s!!.costCny), color = C.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
            Text("详情", color = C.Muted, fontSize = 10.sp, modifier = Modifier.clickable {
                state.notice = if (s?.contextMode == "summary" && !s.summary.isNullOrBlank()) {
                    "摘要模式：更早 " + s.summaryCovered + " 条由本机压成要点，后部原文保留；只统计会进入请求的文本，不额外消耗 token。"
                } else {
                    "上下文为本地估算，只统计会进入请求的原文，不额外消耗 token。"
                }
            })
        }
        Spacer(Modifier.height(6.dp))
    }
}
