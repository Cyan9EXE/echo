package app.echo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure SSE parser in Sse.kt.
 * No Android framework or network is involved: event name + JSON data in, events out.
 */
class SseTest {
    private fun parse(event: String, data: String): List<StreamEvent> = Sse.parse(event, data)

    @Test
    fun emptyDataProducesNoEvents() {
        assertTrue(parse("message_stop", "").isEmpty())
    }

    @Test
    fun malformedJsonProducesNoEvents() {
        assertTrue(parse("content_block_delta", "{not json").isEmpty())
        assertTrue(parse("message_start", "").isEmpty())
    }

    @Test
    fun unknownEventProducesNoEvents() {
        assertTrue(parse("ping", "{\"type\":\"ping\"}").isEmpty())
        assertTrue(parse("", "{\"type\":\"what_is_this\"}").isEmpty())
    }

    @Test
    fun eventNameFallsBackToJsonTypeWhenBlank() {
        assertEquals(listOf<StreamEvent>(StreamEvent.Stop), parse("", "{\"type\":\"message_stop\"}"))
    }

    @Test
    fun textDeltaIsParsed() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}"""
        assertEquals(listOf<StreamEvent>(StreamEvent.Text("你好")), parse("content_block_delta", data))
    }

    @Test
    fun emptyTextDeltaProducesNoEvents() {
        val data = """{"delta":{"type":"text_delta","text":""}}"""
        assertTrue(parse("content_block_delta", data).isEmpty())
    }

    @Test
    fun thinkingDeltaIsParsed() {
        val data = """{"delta":{"type":"thinking_delta","thinking":"先分析一下"}}"""
        assertEquals(listOf<StreamEvent>(StreamEvent.Thinking("先分析一下")), parse("content_block_delta", data))
    }

    @Test
    fun signatureDeltaIsParsed() {
        val data = """{"delta":{"type":"signature_delta","signature":"sig-abc"}}"""
        assertEquals(listOf<StreamEvent>(StreamEvent.Signature("sig-abc")), parse("content_block_delta", data))
    }

    @Test
    fun citationDeltaIsParsed() {
        val data = """{"delta":{"type":"citations_delta","citation":{"url":"https://example.com/a","title":"示例"}}}"""
        val events = parse("content_block_delta", data)
        assertEquals(1, events.size)
        val c = (events[0] as StreamEvent.CitationFound).citation
        assertEquals("https://example.com/a", c.url)
        assertEquals("示例", c.title)
    }

    @Test
    fun citationWithoutUrlIsIgnored() {
        val data = """{"delta":{"type":"citations_delta","citation":{"title":"无链接"}}}"""
        assertTrue(parse("content_block_delta", data).isEmpty())
    }

    @Test
    fun messageStartPartialUsageIsParsed() {
        val data = """{"message":{"usage":{"input_tokens":120,"cache_read_input_tokens":80,"output_tokens":2}}}"""
        val events = parse("message_start", data)
        assertEquals(1, events.size)
        val u = events[0] as StreamEvent.Usage
        assertEquals(false, u.full)
        assertEquals(120, u.usage.inputTokens)
        assertEquals(80, u.usage.cacheReadTokens)
        assertEquals(0, u.usage.cacheCreateTokens)
        assertEquals(2, u.usage.outputTokens)
    }

    @Test
    fun messageStartWithoutUsageIsIgnored() {
        assertTrue(parse("message_start", """{"message":{}}""").isEmpty())
    }

    @Test
    fun messageDeltaCarriesFullUsageAndStopReason() {
        val data = """{"delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":200,"output_tokens":42,"cache_read_input_tokens":150,"cache_creation_input_tokens":10}}"""
        val events = parse("message_delta", data)
        assertEquals(2, events.size)
        val u = events[0] as StreamEvent.Usage
        assertEquals(true, u.full)
        assertEquals(200, u.usage.inputTokens)
        assertEquals(42, u.usage.outputTokens)
        assertEquals(150, u.usage.cacheReadTokens)
        assertEquals(10, u.usage.cacheCreateTokens)
        assertEquals(StreamEvent.StopReason("end_turn"), events[1])
    }

    @Test
    fun messageDeltaWithoutUsageStillEmitsStopReason() {
        val data = """{"delta":{"stop_reason":"max_tokens"}}"""
        assertEquals(listOf<StreamEvent>(StreamEvent.StopReason("max_tokens")), parse("message_delta", data))
    }

    @Test
    fun messageDeltaWithNullStopReasonEmitsOnlyUsage() {
        val data = """{"delta":{"stop_reason":null},"usage":{"output_tokens":7}}"""
        val events = parse("message_delta", data)
        assertEquals(1, events.size)
        assertTrue(events[0] is StreamEvent.Usage)
    }

    @Test
    fun messageStopIsParsed() {
        assertEquals(listOf<StreamEvent>(StreamEvent.Stop), parse("message_stop", "{}"))
    }

    @Test
    fun errorFrameIsParsed() {
        val data = """{"type":"error","error":{"type":"overloaded_error","message":"服务器繁忙，请稍后重试"}}"""
        assertEquals(listOf<StreamEvent>(StreamEvent.Failure("服务器繁忙，请稍后重试")), parse("error", data))
    }

    @Test
    fun errorFrameWithoutMessageFallsBackToDefaultText() {
        val data = """{"type":"error","error":{"type":"overloaded_error"}}"""
        val events = parse("error", data)
        assertEquals(1, events.size)
        assertEquals("生成失败", (events[0] as StreamEvent.Failure).message)
    }

    @Test
    fun usageOfDefaultsMissingFieldsToZero() {
        val usage = Sse.usageOf(JSONObject("{}"))
        assertEquals(ApiUsage(), usage)
    }
}
