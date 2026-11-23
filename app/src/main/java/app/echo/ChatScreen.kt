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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatScreen(state: AppState, accent: Color) {
    val s = state.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pendingMarkdown by remember { mutableStateOf<ExportSession?>(null) }
    var pendingZipHeaders by remember { mutableStateOf<List<ExportHeader>>(emptyList()) }
    var pendingZipLive by remember { mutableStateOf<Map<String, ExportSession>>(emptyMap()) }

    val markdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val snap = pendingMarkdown
        pendingMarkdown = null
        if (uri == null || snap == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (exporting) return@launch
            exporting = true
            state.notice = "正在导出 Markdown…"
            state.notice = runCatching { withContext(Dispatchers.IO) { exportSessionToUri(context, uri, snap) } }
                .fold(
                    onSuccess = { "已导出 Markdown 文件" },
                    onFailure = { "导出失败：" + (it.message ?: "无法写入所选位置") },
                )
            exporting = false
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val headers = pendingZipHeaders
        val live = pendingZipLive
        pendingZipHeaders = emptyList()
        pendingZipLive = emptyMap()
        if (uri == null || headers.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            if (exporting) return@launch
            exporting = true
            state.notice = "正在导出 " + headers.size + " 个会话…"
            state.notice = runCatching { withContext(Dispatchers.IO) { exportAllToUri(context, uri, headers, live) } }
                .fold(
                    onSuccess = { "已导出 " + headers.size + " 个会话的 ZIP" },
                    onFailure = { "导出失败：" + (it.message ?: "无法写入所选位置") },
                )
            exporting = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(state, accent, s.title, onBack = state::backHome)
        ContextBar(state, accent, onExport = { exportMenu = true })
        if (s.expired && !s.revived) RevivalBanner(state, s, accent)
        if (s.revived && s.contextMode == "summary" && s.summary.isNotBlank()) {
            SummaryCard(state, s, accent)
        }
        val listState = rememberLazyListState()
        // Only the streaming session is live; the global flag would mark B live too (P2-2).
        val liveId = if (state.liveSessionId == s.id) s.messages.lastOrNull()?.id else null
        // 用户一旦主动滑动就暂停跟底，重新看到最后一项时恢复；
        // 这样流式滚动不会抢上滑手势（R4-P1-2）。
        var followTail by remember(s.id) { mutableStateOf(true) }
        LaunchedEffect(s.id, listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (scrolling) {
                    followTail = false
                } else {
                    // 只有真正停在底部（最后一项末端贴住视口）才恢复跟底；
                    // 在超长回复内部上滑后不会立刻被拽回（R4-P1-2）。
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    val gap = if (last == null) Float.MAX_VALUE
                        else (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
                    followTail = last != null && last.index >= info.totalItemsCount - 1 && gap <= 2f
                }
            }
        }
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
                Box(Modifier.animateItem()) {
                    if (m.role == "user") UserBubble(m.text) else AssistantBubble(m, accent, state, live = m.id == liveId)
                }
            }
        }
        // 冷启动或从后台回来时消息可能还没装回内存；主动补一次，
        // ready 变成 true 会触发重组，避免聊天页永远停在空 stub 上（P1-1）。
        LaunchedEffect(s.id) {
            state.ensureReadyForUi(s)
        }
        LaunchedEffect(s.id, s.messages.ready) {
            if (s.messages.isNotEmpty()) {
                runCatching { listState.scrollToItem(s.messages.size - 1, Int.MAX_VALUE) }
            }
        }
        // 帧同步跟底：每帧只补“最后一项超出视口”的那几像素，文本增长和列表
        // 滚动发生在同一帧，取代每 90/60 ms 一次 scrollToItem 跳变（R4-P1-2）。
        LaunchedEffect(s.id, liveId) {
            if (liveId == null) return@LaunchedEffect
            while (true) {
                withFrameNanos { }
                if (!followTail || !state.isStreaming(s.id)) continue
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                if (total == 0) continue
                val last = info.visibleItemsInfo.lastOrNull() ?: continue
                // 新消息插入后 tail 变了，但“最后可见项”仍是旧的尾部；此时直接
                // 跳到新尾部，不能把「最后可见项 == 最后一项」当成继续跟底的开关，
                // 否则永远不会滚到新消息（P1-1）。
                val rescue = tailRescueIndex(last.index, total)
                if (rescue != null) {
                    runCatching { listState.scrollToItem(rescue, Int.MAX_VALUE) }
                    continue
                }
                val overflow = last.offset + last.size -
                    (info.viewportEndOffset - info.afterContentPadding)
                if (overflow > 0) runCatching { listState.scrollBy(overflow.toFloat()) }
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
    if (exportMenu) {
        ExportSheet(
            accent = accent,
            sessionTitle = s.title,
            messageCount = s.messages.size,
            sessionCount = state.sessions.size,
            onExportSession = {
                exportMenu = false
                val snap = snapshotSession(s)
                if (snap == null) {
                    state.notice = "会话内容还在加载，请稍候再导出"
                } else {
                    pendingMarkdown = snap
                    markdownLauncher.launch(suggestMarkdownFileName(snap.header))
                }
            },
            onExportAll = {
                exportMenu = false
                pendingZipHeaders = state.sessions.map { headerOf(it) }
                pendingZipLive = snapshotSession(s)?.let { mapOf(it.header.id to it) } ?: emptyMap()
                if (pendingZipHeaders.isEmpty()) {
                    state.notice = "本机还没有可导出的会话"
                } else {
                    zipLauncher.launch(suggestZipFileName())
                }
            },
            onClose = { exportMenu = false },
        )
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
internal fun ContextBar(state: AppState, accent: Color, onExport: () -> Unit = {}) {
    val s = state.current
    val tokens = state.contextTokens
    LaunchedEffect(state.streaming, s?.id) {
        if (!state.streaming) return@LaunchedEffect
        while (true) {
            delay(500)
            state.refreshLiveContextTokens()
        }
    }
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
            Spacer(Modifier.width(8.dp))
            Text("导出", color = C.Muted, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onExport))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 跟底时是否需要跳到新尾部：只要「最后可见项」不是最后一项，目标就是最后一项。
 * 抽成纯函数便于 JVM 单测覆盖「发送后不滚底」「旧尾部残留」等回归（P1-1/P2-7）。
 */
internal fun tailRescueIndex(lastVisibleIndex: Int, totalItems: Int): Int? =
    if (totalItems > 0 && lastVisibleIndex in 0 until totalItems - 1) totalItems - 1 else null
