package app.echo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 导出引擎的纯函数单测（task-42）。不依赖 Android 运行时：
 * 只测 Markdown 渲染、ZIP 结构、磁盘 JSON 解析与命名工具。
 */
class ExportTest {
    private val now = 1_700_000_000_000L

    private fun header(
        id: String,
        title: String,
        updatedAt: Long = now - 3_600_000L,
        expired: Boolean = false,
        revived: Boolean = false,
        contextMode: String = "full",
        summary: String = "",
        covered: Int = 0,
        pinned: Boolean = false,
    ) = ExportHeader(
        id = id, title = title, updatedAt = updatedAt, expired = expired, revived = revived,
        contextMode = contextMode, summary = summary, summaryCovered = covered, pinned = pinned,
    )

    private fun userMsg(text: String, time: Long = now - 60_000L) =
        ExportMsg(role = "user", text = text, time = time)

    private fun assistantMsg(
        text: String,
        thinking: String = "",
        thinkingMs: Long = 0L,
        model: String = "deepseek-flash",
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cacheReadTokens: Int = 0,
        cacheCreateTokens: Int = 0,
        costCny: Double = 0.0,
        citations: List<ExportCitation> = emptyList(),
    ) = ExportMsg(
        role = "assistant", text = text, thinking = thinking, model = model,
        time = now - 30_000L, thinkingMs = thinkingMs, inputTokens = inputTokens,
        outputTokens = outputTokens, cacheReadTokens = cacheReadTokens,
        cacheCreateTokens = cacheCreateTokens, costCny = costCny, citations = citations,
    )

    // ---------- Markdown ----------

    @Test
    fun markdownRendersHeaderMessagesAndStats() {
        val h = header("s1", "三源平方反比平衡点")
        val msgs = listOf(
            userMsg("三力合力为 0 时怎么解？"),
            assistantMsg(
                text = "推导如下：\n\n```kotlin\nval x = 1\n```\n",
                thinking = "第一行思考\n第二行思考",
                thinkingMs = 4_200L,
                inputTokens = 1_200,
                outputTokens = 3_400,
                cacheReadTokens = 1_200,
                costCny = 0.0123,
                citations = listOf(ExportCitation("https://example.com/a", "参考 A")),
            ),
        )
        val md = renderSessionMarkdown(ExportSession(h, msgs), includeThinking = true, now = now)
        assertTrue(md.startsWith("# 三源平方反比平衡点"))
        assertTrue(md.contains("- 导出时间：" + exportTime(now)))
        assertTrue(md.contains("- 消息数：2（你 1 · DeepSeek 1）"))
        assertTrue(md.contains("- 模型：deepseek-flash"))
        assertTrue(md.contains("## 你"))
        assertTrue(md.contains("## DeepSeek"))
        assertTrue(md.contains("三力合力为 0 时怎么解？"))
        // 代码围栏完整保留
        assertTrue(md.contains("```kotlin"))
        assertTrue(md.contains("val x = 1"))
        // 思考以引用块出现，并带用时
        assertTrue(md.contains("> **思考过程**"))
        assertTrue(md.contains("> 第一行思考"))
        assertTrue(md.contains("思考约 4.2 秒"))
        // 引用链接
        assertTrue(md.contains("[参考 A](<https://example.com/a>)"))
        // 文末 token/费用统计
        assertTrue(md.contains("## 文末统计"))
        assertTrue(md.contains("输入 1,200"))
        assertTrue(md.contains("输出 3,400"))
        assertTrue(md.contains("缓存读 1,200"))
        assertTrue(md.contains("本机估算费用：" + fmtCny(0.0123)))
    }

    @Test
    fun markdownCanHideThinking() {
        val h = header("s1", "标题")
        val msgs = listOf(userMsg("问"), assistantMsg("答", thinking = "内部推理", thinkingMs = 1_000L))
        val md = renderSessionMarkdown(ExportSession(h, msgs), includeThinking = false, now = now)
        assertFalse(md.contains("思考过程"))
        assertFalse(md.contains("内部推理"))
        assertFalse(md.contains("思考约"))
        assertTrue(md.contains("答"))
    }

    @Test
    fun markdownIncludesSummaryExpiredFlagAndPin() {
        val h = header(
            "s1", "旧会话", expired = true, contextMode = "summary",
            summary = "前部要点：讨论了受力平衡。", covered = 12, pinned = true,
        )
        val md = renderSessionMarkdown(ExportSession(h, listOf(userMsg("续上"))), now = now)
        assertTrue(md.contains("## 前部摘要"))
        assertTrue(md.contains("覆盖更早 12 条消息"))
        assertTrue(md.contains("前部要点：讨论了受力平衡。"))
        assertTrue(md.contains("已失效（超过 7 天未活跃）"))
        assertTrue(md.contains("- 置顶：是"))
    }

    // ---------- ZIP ----------

    @Test
    fun zipItemsAreIndexedAndKeepTitlesSafe() {
        val items = buildZipItems(listOf(header("s1", "会话/一"), header("s2", "")))
        assertEquals(2, items.size)
        assertEquals("sessions/0001-会话-一.md", items[0].entryPath)
        assertEquals("sessions/0002-未命名会话.md", items[1].entryPath)
        assertEquals(1, items[0].entryPath.count { it == '/' })
        val index = renderIndexMarkdown(items, now)
        assertTrue(index.contains("- 会话数：2"))
        assertTrue(index.contains("sessions/0001-会话-一.md"))
        assertTrue(index.contains("sessions/0002-未命名会话.md"))
    }

    @Test
    fun zipStreamWritesReadableArchive() {
        val h1 = header("s1", "甲会话")
        val h2 = header("s2", "乙会话")
        val items = buildZipItems(listOf(h1, h2))
        val live = mapOf("s1" to ExportSession(h1, listOf(userMsg("你好"))))
        val bos = ByteArrayOutputStream()
        val result = writeZipStream(bos, items, live = live, load = { null }, now = now)
        assertTrue(result.entries == 3)
        assertTrue(result.bytes > 0L)

        val data = LinkedHashMap<String, String>()
        ZipInputStream(bos.toByteArray().inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                data[e.name] = zin.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(3, data.size)
        assertNotNull(data["index.md"])
        assertTrue(data.containsKey(items[0].entryPath))
        assertTrue(data.containsKey(items[1].entryPath))
        assertTrue(data["index.md"]!!.contains(items[0].entryPath))
        assertTrue(data[items[0].entryPath]!!.contains("你好"))
        // 磁盘上不存在的会话只导出元数据，不崩
        assertTrue(data[items[1].entryPath]!!.contains("乙会话"))
    }

    // ---------- 磁盘 JSON 解析 ----------

    @Test
    fun parseStoredSessionReadsMessagesAndCitations() {
        val h = header("s1", "标题")
        val json = JSONObject().apply {
            put("summary", "前部要点")
            put("covered", 3)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "m1")
                            put("role", "user")
                            put("text", "问题")
                            put("thinking", "")
                            put("time", 111L)
                            put(
                                "citations",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("url", "https://example.com")
                                        put("title", "示例")
                                    },
                                ),
                            )
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("id", "m2")
                            put("role", "assistant")
                            put("text", "回答")
                            put("thinking", "想想")
                            put("model", "deepseek-flash")
                            put("time", 222L)
                            put("inputTokens", 10)
                            put("outputTokens", 20)
                            put("cacheReadTokens", 3)
                            put("cacheCreateTokens", 4)
                            put("costCny", 0.5)
                            put("thinkingMs", 1_500L)
                        },
                    )
                    // 空消息应被跳过
                    put(JSONObject().apply { put("id", "m3"); put("role", "assistant"); put("text", ""); put("thinking", "") })
                },
            )
        }.toString()

        val s = parseStoredSession(json, h)
        assertNotNull(s)
        requireNotNull(s)
        assertEquals("前部要点", s.header.summary)
        assertEquals(3, s.header.summaryCovered)
        assertEquals(2, s.messages.size)
        assertEquals("user", s.messages[0].role)
        assertEquals("问题", s.messages[0].text)
        assertEquals(1, s.messages[0].citations.size)
        assertEquals("示例", s.messages[0].citations[0].title)
        assertEquals("assistant", s.messages[1].role)
        assertEquals(10, s.messages[1].inputTokens)
        assertEquals(20, s.messages[1].outputTokens)
        assertEquals(3, s.messages[1].cacheReadTokens)
        assertEquals(4, s.messages[1].cacheCreateTokens)
        assertEquals(0.5, s.messages[1].costCny, 0.0001)
        assertEquals(1_500L, s.messages[1].thinkingMs)
    }

    @Test
    fun parseStoredSessionRejectsGarbage() {
        val h = header("s1", "标题")
        assertNull(parseStoredSession("not json", h))
        val empty = parseStoredSession("{}", h)
        assertNotNull(empty)
        assertEquals(0, empty!!.messages.size)
    }

    // ---------- 命名与汇总 ----------

    @Test
    fun safeNameSanitizesAndFallsBack() {
        assertEquals("会话-一-二", safeName("会话/一:二"))
        assertEquals("未命名会话", safeName("   "))
        assertTrue(safeName("x".repeat(60)).length <= 40)
        assertTrue(safeName("a\nb").contains("a-b") || safeName("a\nb") == "a-b")
    }

    @Test
    fun suggestedFileNamesAndTotals() {
        val h = header("s1", "测试标题")
        val mdName = suggestMarkdownFileName(h, now)
        assertTrue(mdName.startsWith("Echo-测试标题-"))
        assertTrue(mdName.endsWith(".md"))
        assertTrue(suggestZipFileName(now).startsWith("Echo-export-"))
        assertTrue(suggestZipFileName(now).endsWith(".zip"))

        val t = totalsOf(
            listOf(
                userMsg("问"),
                assistantMsg(
                    "答", inputTokens = 1_200, outputTokens = 3_400,
                    cacheReadTokens = 1_200, cacheCreateTokens = 0, costCny = 0.0123,
                ),
            ),
        )
        assertEquals(1_200, t.input)
        assertEquals(3_400, t.output)
        assertEquals(1_200, t.cacheRead)
        assertEquals(0, t.cacheCreate)
        assertEquals(0.0123, t.cost, 1e-9)
    }
}
