package app.echo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * 路线图 6：协议正确性回归。
 *
 * 覆盖两处此前只能靠真人对着真 Key 看的契约：
 * 1. 请求体形状（max_tokens 收敛、thinking/web_search、system prompt、带 signature 的历史回放）；
 * 2. SSE 流终止语义（message_stop 存在与否、error 帧、取消、ping/comment 忽略）。
 *
 * 全部基于纯 JVM，不依赖 Android 框架或网络。
 */
class ProtocolTest {

    private fun sse(vararg blocks: Pair<String, String>): String =
        blocks.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

    private fun pump(
        text: String,
        cancelAfter: Int = -1,
    ): Pair<Boolean, List<StreamEvent>> {
        val events = mutableListOf<StreamEvent>()
        var cancelled = false
        val stopped = pumpSse(
            reader = BufferedReader(StringReader(text)),
            isCancelled = { cancelled },
            onEvent = { ev ->
                events += ev
                if (cancelAfter > 0 && events.size >= cancelAfter) cancelled = true
            },
        )
        return stopped to events
    }

    // ---------- 请求体 ----------

    @Test
    fun maxTokensIsClampedToSupportedRange() {
        val low = buildChatBody("deepseek-flash", false, false, null, emptyList(), 1_000)
        assertEquals(ApiClient.MIN_MAX_TOKENS, low.getInt("max_tokens"))
        val high = buildChatBody("deepseek-flash", false, false, null, emptyList(), 999_999)
        assertEquals(ApiClient.MAX_MAX_TOKENS, high.getInt("max_tokens"))
        val normal = buildChatBody("deepseek-flash", false, false, null, emptyList(), 32_768)
        assertEquals(32_768, normal.getInt("max_tokens"))
    }

    @Test
    fun bodyAlwaysStreamsAndCarriesModel() {
        val body = buildChatBody("deepseek-v4-pro", true, false, null, emptyList(), 8_192)
        assertEquals("deepseek-v4-pro", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun thinkingCanBeDisabled() {
        val body = buildChatBody("deepseek-flash", false, false, null, emptyList(), 8_192)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun webSearchAddsOfficialToolOnlyWhenEnabled() {
        val withTool = buildChatBody("deepseek-flash", true, true, null, emptyList(), 8_192)
        val tools = withTool.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("deepseek_web_search", tools.getJSONObject(0).getString("type"))
        assertEquals("deepseek_web_search", tools.getJSONObject(0).getString("name"))

        val without = buildChatBody("deepseek-flash", true, false, null, emptyList(), 8_192)
        assertFalse(without.has("tools"))
    }

    @Test
    fun blankSystemPromptIsOmittedButContentIsKept() {
        val blank = buildChatBody("deepseek-flash", false, false, "   ", emptyList(), 8_192)
        assertFalse(blank.has("system"))

        val custom = buildChatBody("deepseek-flash", false, false, "只回答一句话", emptyList(), 8_192)
        assertEquals("只回答一句话", custom.getString("system"))
    }

    @Test
    fun assistantTurnWithSignatureReplaysThinkingBlock() {
        val body = buildChatBody(
            "deepseek-flash", true, false, null,
            listOf(ApiMsg("assistant", "最终答案", "推理过程", "sig-1")),
            8_192,
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("推理过程", content.getJSONObject(0).getString("thinking"))
        assertEquals("sig-1", content.getJSONObject(0).getString("signature"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("最终答案", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun assistantTurnWithoutSignatureStaysPlainString() {
        val body = buildChatBody(
            "deepseek-flash", true, false, null,
            listOf(ApiMsg("assistant", "只有答案"), ApiMsg("user", "你好")),
            8_192,
        )
        val msgs = body.getJSONArray("messages")
        assertEquals("assistant", msgs.getJSONObject(0).getString("role"))
        assertEquals("只有答案", msgs.getJSONObject(0).getString("content"))
        assertEquals("你好", msgs.getJSONObject(1).getString("content"))
    }

    // ---------- SSE 终止语义 ----------

    private val completeTurn = sse(
        "message_start" to """{"message":{"usage":{"input_tokens":31,"output_tokens":1}}}""",
        "content_block_delta" to """{"delta":{"type":"thinking_delta","thinking":"先想"}}""",
        "content_block_delta" to """{"delta":{"type":"text_delta","text":"pong"}}""",
        "content_block_delta" to """{"delta":{"type":"signature_delta","signature":"sig-9"}}""",
        "content_block_delta" to """{"delta":{"type":"citations_delta","citation":{"url":"https://example.com/a","title":"示例"}}}""",
        "message_delta" to """{"delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":64,"output_tokens":2}}""",
        "message_stop" to "{}",
    )

    @Test
    fun completeTurnReportsMessageStopAndDeliversEveryEvent() {
        val (stopped, events) = pump(completeTurn)
        assertTrue("收到 message_stop 应返回 true", stopped)
        assertEquals(1, events.filterIsInstance<StreamEvent.Thinking>().size)
        assertEquals("先想", events.filterIsInstance<StreamEvent.Thinking>().first().text)
        assertEquals("pong", events.filterIsInstance<StreamEvent.Text>().first().text)
        assertEquals("sig-9", events.filterIsInstance<StreamEvent.Signature>().first().signature)
        assertEquals("https://example.com/a", events.filterIsInstance<StreamEvent.CitationFound>().first().citation.url)
        val usages = events.filterIsInstance<StreamEvent.Usage>()
        assertEquals(2, usages.size)
        assertFalse("message_start 是预填值", usages[0].full)
        assertTrue("message_delta 是全量值", usages[1].full)
        assertEquals(64, usages[1].usage.inputTokens)
        assertEquals(StreamEvent.StopReason("end_turn"), events.filterIsInstance<StreamEvent.StopReason>().first())
        assertTrue(events.contains(StreamEvent.Stop))
    }

    @Test
    fun truncatedTurnWithoutMessageStopIsDetected() {
        // 服务端在 content_block_delta 之后直接断流：没有 message_delta / message_stop。
        val truncated = sse(
            "message_start" to """{"message":{"usage":{"input_tokens":10}}}""",
            "content_block_delta" to """{"delta":{"type":"text_delta","text":"半句"}}""",
        )
        val (stopped, events) = pump(truncated)
        assertFalse("缺 message_stop 必须报未完成", stopped)
        assertEquals("半句", events.filterIsInstance<StreamEvent.Text>().single().text)
        assertFalse(events.contains(StreamEvent.Stop))
    }

    @Test
    fun cancelStopsPumpingEarly() {
        // cancelAfter=1：解析完第一个事件后 isCancelled 变 true，剩余帧不再消费。
        val (stopped, events) = pump(completeTurn, cancelAfter = 1)
        assertFalse(stopped)
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Usage)
    }

    @Test
    fun errorFrameSurfacesAsFailureEvent() {
        val errorStream = sse("error" to """{"type":"error","error":{"type":"overloaded_error","message":"服务器繁忙，请稍后重试"}}""")
        val (stopped, events) = pump(errorStream)
        assertFalse(stopped)
        assertEquals("服务器繁忙，请稍后重试", (events.single() as StreamEvent.Failure).message)
    }

    @Test
    fun pingAndCommentsProduceNoEvents() {
        val keepAlive = ": keepalive\n\n" + sse("ping" to """{"type":"ping"}""")
        val (stopped, events) = pump(keepAlive)
        assertFalse(stopped)
        assertTrue(events.isEmpty())
    }

    // ---------- HTTP 错误映射 ----------

    @Test
    fun httpErrorsMapToHumanReadableChinese() {
        assertEquals("API Key 无效或已过期，请在设置里重新填写。", httpErrorMessage(401, "", "x"))
        assertEquals("余额不足，请检查 DeepSeek 账户。", httpErrorMessage(402, "", "x"))
        assertEquals("请求过于频繁或额度受限，请稍后再试。", httpErrorMessage(429, "", "x"))
        assertEquals("官方服务繁忙，请稍后再试。", httpErrorMessage(503, "", "x"))
        assertEquals("官方服务超时，请稍后再试。", httpErrorMessage(504, "", "x"))
    }

    @Test
    fun badRequestPrefersServerDetailButHidesRiskReason() {
        val body = """{"error":{"message":"max_tokens is too large"}}"""
        assertEquals("max_tokens is too large", httpErrorMessage(400, body, "请求失败"))
        val risk = """{"error":{"message":"risk control: content rejected"}}"""
        assertEquals("内容未通过官方审核，无法处理。", httpErrorMessage(400, risk, "请求失败"))
    }

    @Test
    fun unknownHttpCodeFallsBackToGivenText() {
        assertEquals("请求失败（HTTP 418）", httpErrorMessage(418, "", "请求失败（HTTP 418）"))
        assertEquals("服务开小差", httpErrorMessage(500, "", "服务开小差"))
    }
}
