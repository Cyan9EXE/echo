package app.echo

import org.json.JSONObject

/**
 * One parsed SSE event from the Anthropic-compatible stream.
 *
 * Kept separate from the network loop so it can be unit tested without an
 * emulator: [Sse.parse] is pure (event name + data line in, events out).
 */
internal sealed interface StreamEvent {
    data class Text(val text: String) : StreamEvent
    data class Thinking(val text: String) : StreamEvent
    data class Signature(val signature: String) : StreamEvent
    data class CitationFound(val citation: Citation) : StreamEvent
    /** [full] means the event carries complete usage (message_delta), not a partial prefill. */
    data class Usage(val usage: ApiUsage, val full: Boolean) : StreamEvent
    data class StopReason(val reason: String) : StreamEvent
    data object Stop : StreamEvent
    data class Failure(val message: String) : StreamEvent
}

internal object Sse {
    fun parse(event: String, json: String): List<StreamEvent> {
        if (json.isEmpty()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return when (val type = event.ifEmpty { obj.optString("type") }) {
            "content_block_delta" -> parseDelta(obj)
            "message_start" -> {
                val u = obj.optJSONObject("message")?.optJSONObject("usage")
                if (u == null) emptyList() else listOf(StreamEvent.Usage(usageOf(u), full = false))
            }
            "message_delta" -> buildList {
                obj.optJSONObject("usage")?.let { add(StreamEvent.Usage(usageOf(it), full = true)) }
                obj.optJSONObject("delta")?.optString("stop_reason")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { add(StreamEvent.StopReason(it)) }
            }
            "message_stop" -> listOf(StreamEvent.Stop)
            "error" -> {
                val message = obj.optJSONObject("error")?.optString("message").orEmpty()
                listOf(StreamEvent.Failure(message.ifBlank { "生成失败" }))
            }
            else -> emptyList()
        }
    }

    fun usageOf(u: JSONObject): ApiUsage = ApiUsage(
        inputTokens = u.optInt("input_tokens", 0),
        outputTokens = u.optInt("output_tokens", 0),
        cacheReadTokens = u.optInt("cache_read_input_tokens", 0),
        cacheCreateTokens = u.optInt("cache_creation_input_tokens", 0),
    )

    private fun parseDelta(obj: JSONObject): List<StreamEvent> {
        val delta = obj.optJSONObject("delta") ?: return emptyList()
        return when (delta.optString("type")) {
            "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamEvent.Text(it)) }.orEmpty()
            "thinking_delta" -> delta.optString("thinking").takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamEvent.Thinking(it)) }.orEmpty()
            "signature_delta" -> delta.optString("signature").takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamEvent.Signature(it)) }.orEmpty()
            "citations_delta" -> {
                val c = delta.optJSONObject("citation")
                val url = c?.optString("url").orEmpty()
                if (url.isBlank()) emptyList()
                else listOf(StreamEvent.CitationFound(Citation(url, c.optString("title"))))
            }
            else -> emptyList()
        }
    }
}
