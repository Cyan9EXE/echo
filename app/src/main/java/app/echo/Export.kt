package app.echo

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出引擎：单会话 Markdown + 全会话 ZIP。
 *
 * 设计约束（task-42）：
 * - 渲染函数都是纯函数（不碰 Android），JVM 单测直接断言文本。
 * - 大会话不拼超大字符串：统一走回调式 [writeSessionMarkdown] / [writeIndexMarkdown]，
 *   写 SAF Uri 与写 ZIP entry 复用同一套渲染，峰值内存 ≈ 单个最大会话。
 * - 写文件通过 SAF 的 Uri（[exportSessionToUri] / [exportAllToUri]），不申请存储权限。
 * - 读取历史会话原文时按 State.kt 的 msgFile 命名规则（State.kt:1076-1079）直接读
 *   files/store/msg；该命名规则是私有实现，若 State.kt 改动需同步这里（本任务未获准改 State.kt）。
 */
// ---------- 数据模型 ----------

internal data class ExportCitation(val url: String, val title: String)

internal data class ExportMsg(
    val role: String,
    val text: String,
    val thinking: String = "",
    val model: String = "",
    val time: Long = 0L,
    val thinkingMs: Long = 0L,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreateTokens: Int = 0,
    val costCny: Double = 0.0,
    val citations: List<ExportCitation> = emptyList(),
)

/** 会话元数据（可从 Compose 状态在主线程序列化成纯数据，之后随意跨线程）。 */
internal data class ExportHeader(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val expired: Boolean = false,
    val revived: Boolean = false,
    val contextMode: String = "full",
    val summary: String = "",
    val summaryCovered: Int = 0,
    val pinned: Boolean = false,
    val costCny: Double = 0.0,
)

internal data class ExportSession(val header: ExportHeader, val messages: List<ExportMsg>)

internal data class ExportResult(val entries: Int, val bytes: Long)

internal data class ZipItem(val entryPath: String, val header: ExportHeader)

internal data class ExportTotals(
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheCreate: Int,
    val cost: Double,
)

// ---------- 时间与命名 ----------

private val exportTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())

private val fileStampFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).withZone(ZoneId.systemDefault())

internal fun exportTime(ts: Long): String =
    if (ts <= 0L) "未知时间" else exportTimeFmt.format(Instant.ofEpochMilli(ts))

private fun fileStamp(now: Long): String = fileStampFmt.format(Instant.ofEpochMilli(now))

private val unsafeNameChars = Regex("[\\\\/:*?\"<>|%#()\\[\\],\\p{Cntrl}]")
private val whitespaceRun = Regex("\\s+")

/** 文件名/标题清洗：去掉路径与 Markdown/ZIP 不友好字符，空标题给默认值。 */
internal fun safeName(raw: String, max: Int = 40): String {
    var s = raw.trim().replace(unsafeNameChars, "-").replace(whitespaceRun, "-")
    s = s.trim('-', '.', ' ')
    if (s.length > max) s = s.take(max).trim('-', '.', ' ')
    return s.ifBlank { "未命名会话" }
}

private fun oneLine(raw: String): String = raw.replace('\n', ' ').replace('\r', ' ').trim()

private fun mdLinkText(raw: String): String = oneLine(raw)
    .replace("\\", "\\\\")
    .replace("[", "\\[")
    .replace("]", "\\]")

internal fun suggestMarkdownFileName(header: ExportHeader, now: Long = System.currentTimeMillis()): String =
    "Echo-" + safeName(header.title) + "-" + fileStamp(now) + ".md"

internal fun suggestZipFileName(now: Long = System.currentTimeMillis()): String =
    "Echo-export-" + fileStamp(now) + ".zip"

// ---------- 数字格式 ----------

private fun count(v: Int): String = String.format(Locale.CHINA, "%,d", v)

private fun seconds(ms: Long): String = String.format(Locale.CHINA, "%.1f", ms / 1000.0)

internal fun totalsOf(messages: List<ExportMsg>): ExportTotals {
    var input = 0
    var output = 0
    var cacheRead = 0
    var cacheCreate = 0
    var cost = 0.0
    messages.forEach { m ->
        input += m.inputTokens
        output += m.outputTokens
        cacheRead += m.cacheReadTokens
        cacheCreate += m.cacheCreateTokens
        cost += m.costCny
    }
    return ExportTotals(input, output, cacheRead, cacheCreate, cost)
}

// ---------- 单会话 Markdown ----------

/**
 * 纯渲染入口（单测用）。大会话不要调用它再写入，因为会得到完整 String；
 * 真正落盘请用 [writeSessionMarkdown] + 输出流。
 */
internal fun renderSessionMarkdown(
    session: ExportSession,
    includeThinking: Boolean = true,
    now: Long = System.currentTimeMillis(),
): String {
    val sb = StringBuilder()
    writeSessionMarkdown(session, includeThinking, now) { sb.append(it) }
    return sb.toString()
}

/** 回调式逐段渲染，避免把整个会话拼成一个可能几十 MB 的字符串。 */
internal fun writeSessionMarkdown(
    session: ExportSession,
    includeThinking: Boolean = true,
    now: Long = System.currentTimeMillis(),
    out: (String) -> Unit,
) {
    val h = session.header
    val msgs = session.messages
    out("# " + oneLine(h.title).ifBlank { "未命名会话" } + "\n\n")
    out("- 导出时间：" + exportTime(now) + "\n")
    out("- 最后活跃：" + exportTime(h.updatedAt) + "\n")
    val userCount = msgs.count { it.role == "user" }
    out("- 消息数：" + msgs.size + "（你 " + userCount + " · DeepSeek " + (msgs.size - userCount) + "）\n")
    val models = msgs.mapNotNull { m -> m.model.takeIf { it.isNotBlank() } }.distinct()
    if (models.isNotEmpty()) out("- 模型：" + models.joinToString(" / ") + "\n")
    out("- 上下文：" + contextLine(h) + "\n")
    if (h.pinned) out("- 置顶：是\n")
    out("\n")
    if (h.summary.isNotBlank()) {
        out("## 前部摘要\n\n")
        if (h.summaryCovered > 0) {
            out("_覆盖更早 " + h.summaryCovered + " 条消息；由本机压缩生成，不调用 API。本文件仍收录本机全部原文。_\n\n")
        }
        out(h.summary.trimEnd() + "\n\n")
    }
    msgs.forEach { m -> writeMessageMarkdown(m, includeThinking, out) }
    val t = totalsOf(msgs)
    out("---\n\n## 文末统计\n\n")
    out(
        "> token：输入 " + count(t.input) + " · 输出 " + count(t.output) +
            " · 缓存读 " + count(t.cacheRead) + " · 缓存写 " + count(t.cacheCreate) + "\n",
    )
    out("> 本机估算费用：" + fmtCny(t.cost) + "（仅供对账，实际以 DeepSeek 官方账单为准）\n")
}

private fun contextLine(h: ExportHeader): String {
    val mode = if (h.contextMode == "summary" && h.summary.isNotBlank()) {
        "摘要模式（前部 " + h.summaryCovered + " 条折叠为要点；本文件收录本机全部原文）"
    } else {
        "原文"
    }
    val state = when {
        h.expired && h.revived -> "；状态：已失效后复活"
        h.expired -> "；状态：已失效（超过 7 天未活跃）"
        else -> ""
    }
    return mode + state
}

private fun writeMessageMarkdown(m: ExportMsg, includeThinking: Boolean, out: (String) -> Unit) {
    out("---\n\n")
    out("## " + (if (m.role == "user") "你" else "DeepSeek") + "\n\n")
    val meta = StringBuilder("_").append(exportTime(m.time))
    if (m.role != "user" && m.model.isNotBlank()) meta.append(" · ").append(m.model)
    if (includeThinking && m.thinkingMs > 0L) meta.append(" · 思考约 ").append(seconds(m.thinkingMs)).append(" 秒")
    meta.append("_\n\n")
    out(meta.toString())
    if (includeThinking && m.thinking.isNotBlank()) {
        out("> **思考过程**\n>\n")
        m.thinking.trimEnd().split('\n').forEach { line ->
            out(if (line.isBlank()) ">\n" else "> " + line + "\n")
        }
        out("\n")
    }
    val body = m.text.trimEnd()
    if (body.isBlank()) out("_（无正文）_\n\n") else out(body + "\n\n")
    if (m.citations.isNotEmpty()) {
        out("**参考链接**\n\n")
        m.citations.forEach { c ->
            val label = c.title.ifBlank { c.url }
            out("- [" + mdLinkText(label) + "](<" + c.url + ">)\n")
        }
        out("\n")
    }
}

// ---------- 全会话 ZIP ----------

/**
 * 生成 ZIP 内路径与顺序：sessions/0001-标题.md，标题重复时追加 -2、-3。
 * 索引里的链接直接使用这里的 entryPath，保证一一对应。
 */
internal fun buildZipItems(headers: List<ExportHeader>): List<ZipItem> {
    val used = HashSet<String>()
    val out = ArrayList<ZipItem>(headers.size)
    headers.forEachIndexed { index, h ->
        val base = String.format(Locale.ROOT, "%04d", index + 1) + "-" + safeName(h.title)
        var name = base
        var n = 1
        while (!used.add(name)) {
            n++
            name = base + "-" + n
        }
        out.add(ZipItem("sessions/" + name + ".md", h))
    }
    return out
}

internal fun renderIndexMarkdown(items: List<ZipItem>, now: Long = System.currentTimeMillis()): String {
    val sb = StringBuilder()
    writeIndexMarkdown(items, now) { sb.append(it) }
    return sb.toString()
}

internal fun writeIndexMarkdown(items: List<ZipItem>, now: Long = System.currentTimeMillis(), out: (String) -> Unit) {
    out("# Echo 会话导出\n\n")
    out("- 导出时间：" + exportTime(now) + "\n")
    val expired = items.count { it.header.expired && !it.header.revived }
    val suffix = if (expired > 0) "（其中已失效 " + expired + "）" else ""
    out("- 会话数：" + items.size + suffix + "\n")
    out("- 结构：本文件是索引；sessions/ 目录下每个会话一个 Markdown 文件。\n")
    out("- 隐私：内容来自本机存储；导出过程不联网、不上传。\n\n")
    out("## 会话列表\n\n")
    items.forEach { item ->
        val h = item.header
        val flag = if (h.expired && !h.revived) " · 已失效" else ""
        out("- [" + exportTime(h.updatedAt) + " · " + mdLinkText(h.title) + "](" + item.entryPath + ")" + flag + "\n")
    }
}

/**
 * 把 [items] 流式写成 ZIP（先 index.md，再逐会话）。
 * [live] 提供内存中较新的会话快照（优先）；否则用 [load] 从磁盘读；都没有则只导出元数据。
 * 不关闭 [out]，由调用方负责。
 */
internal fun writeZipStream(
    out: OutputStream,
    items: List<ZipItem>,
    live: Map<String, ExportSession> = emptyMap(),
    load: (String) -> ExportSession? = { null },
    now: Long = System.currentTimeMillis(),
    includeThinking: Boolean = true,
): ExportResult {
    val counting = CountingOutputStream(BufferedOutputStream(out, 64 * 1024))
    val zip = ZipOutputStream(counting)
    fun entry(path: String, body: ((String) -> Unit) -> Unit) {
        zip.putNextEntry(ZipEntry(path))
        val sink: (String) -> Unit = { s -> if (s.isNotEmpty()) zip.write(s.toByteArray(Charsets.UTF_8)) }
        body(sink)
        zip.closeEntry()
    }
    entry("index.md") { sink -> writeIndexMarkdown(items, now, sink) }
    items.forEach { item ->
        val session = live[item.header.id] ?: load(item.header.id) ?: ExportSession(item.header, emptyList())
        entry(item.entryPath) { sink -> writeSessionMarkdown(session, includeThinking, now, sink) }
    }
    zip.finish()
    zip.flush()
    return ExportResult(items.size + 1, counting.count)
}

// ---------- 写 SAF Uri ----------

internal fun exportSessionToUri(
    context: Context,
    uri: Uri,
    session: ExportSession,
    now: Long = System.currentTimeMillis(),
): ExportResult {
    val raw = context.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入所选位置")
    try {
        val counting = CountingOutputStream(raw)
        val writer = BufferedWriter(OutputStreamWriter(counting, Charsets.UTF_8), 64 * 1024)
        writeSessionMarkdown(session, true, now) { s -> writer.write(s) }
        writer.flush()
        return ExportResult(1, counting.count)
    } finally {
        runCatching { raw.close() }
    }
}

internal fun exportAllToUri(
    context: Context,
    uri: Uri,
    headers: List<ExportHeader>,
    live: Map<String, ExportSession> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): ExportResult {
    val items = buildZipItems(headers)
    val headerById = headers.associateBy { it.id }
    val raw = context.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入所选位置")
    try {
        return writeZipStream(
            out = raw,
            items = items,
            live = live,
            load = { id -> headerById[id]?.let { h -> readStoredSession(context, h) } },
            now = now,
        )
    } finally {
        runCatching { raw.close() }
    }
}

// ---------- 读取本机历史（只读，不改 State.kt） ----------

/**
 * 与 State.kt:1076-1079 的 msgFile() 保持同一命名：store/msg/<safe>-<hashCode>.json。
 * 这是跨模块的隐式契约；State.kt 改命名时必须同步这里。
 */
internal fun storedSessionFile(context: Context, id: String): File {
    val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    val name = safe + "-" + Integer.toHexString(id.hashCode()) + ".json"
    return File(File(File(context.filesDir, "store"), "msg"), name)
}

internal fun readStoredSession(context: Context, header: ExportHeader): ExportSession? {
    val f = storedSessionFile(context, header.id)
    if (!f.isFile) return null
    return runCatching { parseStoredSession(f.readText(Charsets.UTF_8), header) }.getOrNull()
}

/** 解析 State.kt 的会话记录 JSON（summary/covered/messages[]）。纯函数，便于单测。 */
internal fun parseStoredSession(json: String, header: ExportHeader): ExportSession? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val h = header.copy(
        summary = o.optString("summary", header.summary),
        summaryCovered = o.optInt("covered", header.summaryCovered),
    )
    val arr = o.optJSONArray("messages") ?: return ExportSession(h, emptyList())
    val msgs = ArrayList<ExportMsg>(arr.length())
    for (i in 0 until arr.length()) {
        val m = arr.optJSONObject(i) ?: continue
        val text = m.optString("text")
        val thinking = m.optString("thinking")
        if (text.isBlank() && thinking.isBlank()) continue
        val citations = ArrayList<ExportCitation>()
        m.optJSONArray("citations")?.let { carr ->
            for (j in 0 until carr.length()) {
                val c = carr.optJSONObject(j) ?: continue
                val url = c.optString("url")
                if (url.isNotBlank()) citations.add(ExportCitation(url, c.optString("title")))
            }
        }
        msgs.add(
            ExportMsg(
                role = if (m.optString("role") == "user") "user" else "assistant",
                text = text,
                thinking = thinking,
                model = m.optString("model"),
                time = m.optLong("time"),
                thinkingMs = m.optLong("thinkingMs"),
                inputTokens = m.optInt("inputTokens"),
                outputTokens = m.optInt("outputTokens"),
                cacheReadTokens = m.optInt("cacheReadTokens"),
                cacheCreateTokens = m.optInt("cacheCreateTokens"),
                costCny = m.optDouble("costCny", 0.0),
                citations = citations,
            ),
        )
    }
    return ExportSession(h, msgs)
}

// ---------- Compose 状态 → 纯数据 ----------

/** 必须在主线程调用：读 Compose snapshot 状态。会话消息尚未加载时返回 null。 */
internal fun snapshotSession(s: Session): ExportSession? {
    if (!s.messages.ready) return null
    val msgs = ArrayList<ExportMsg>(s.messages.size)
    for (m in s.messages) {
        msgs.add(
            ExportMsg(
                role = m.role,
                text = m.text,
                thinking = m.thinking,
                model = m.model,
                time = m.time,
                thinkingMs = m.thinkingMs,
                inputTokens = m.inputTokens,
                outputTokens = m.outputTokens,
                cacheReadTokens = m.cacheReadTokens,
                cacheCreateTokens = m.cacheCreateTokens,
                costCny = m.costCny,
                citations = m.citations.map { ExportCitation(it.url, it.title) },
            ),
        )
    }
    return ExportSession(headerOf(s), msgs)
}

/** 主线程调用：会话元数据快照，之后可跨线程读。 */
internal fun headerOf(s: Session): ExportHeader = ExportHeader(
    id = s.id,
    title = s.title,
    updatedAt = s.updatedAt,
    expired = s.expired,
    revived = s.revived,
    contextMode = s.contextMode,
    summary = s.summary,
    summaryCovered = s.summaryCovered,
    pinned = s.pinned,
    costCny = s.costCny,
)

// ---------- 字节计数 ----------

private class CountingOutputStream(private val inner: OutputStream) : FilterOutputStream(inner) {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len.toLong()
    }

    override fun flush() {
        out.flush()
    }
}

// ---------- 导出小弹层（紧凑底部气泡，与 ImportGuide 同风格；Popup 不另开 Activity） ----------

@Composable
internal fun ExportSheet(
    accent: Color,
    sessionTitle: String,
    messageCount: Int,
    sessionCount: Int,
    onExportSession: () -> Unit,
    onExportAll: () -> Unit,
    onClose: () -> Unit,
) {
    // Popup 是独立窗口，拿不到系统栏 inset，需在主窗口先算好底部导航栏高度。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(bottom = navBottom).padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = C.Panel,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "导出",
                            color = C.Ivory, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TapTarget(size = 40.dp, visual = 40.dp, onClick = onClose, description = "关闭导出菜单") {
                            CloseGlyph(Modifier.size(15.dp), C.Muted)
                        }
                    }
                    Text(
                        "只写入你选择的位置；不上传，也不需要存储权限。",
                        color = C.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    ExportSheetRow(
                        title = "导出本会话 Markdown",
                        desc = safeName(sessionTitle) + " · " + messageCount + " 条消息 · 含思考、代码与 token 统计",
                        accent = accent,
                        onClick = onExportSession,
                    )
                    Spacer(Modifier.height(4.dp))
                    ExportSheetRow(
                        title = "导出全部会话 ZIP",
                        desc = sessionCount.toString() + " 个会话 · index.md + 每会话一个 .md · 解压即用",
                        accent = accent,
                        onClick = onExportAll,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "会话多时 ZIP 需要几秒；导出期间请不要切走。取消文件选择不会产生任何文件。",
                        color = C.Muted, fontSize = 10.sp, lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportSheetRow(title: String, desc: String, accent: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Ivory, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(desc, color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("›", color = accent, fontSize = 18.sp)
        }
    }
}
