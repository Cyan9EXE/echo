package app.echo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, val body: String) : Exception("HTTP " + code + ": " + body.take(300))

/** One chat message sent upstream. [signature] is the thinking signature for assistant turns. */
data class ApiMsg(
    val role: String,
    val content: String,
    val thinking: String = "",
    val signature: String = "",
)

data class ApiUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreateTokens: Int = 0,
    /** true when this usage is the complete final report (message_delta), not a prefill. */
    val full: Boolean = false,
)

data class Citation(val url: String, val title: String)

class StreamHandle internal constructor() {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    internal var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }
}

internal fun httpErrorMessage(code: Int, body: String, fallback: String): String {
    val detail = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull().orEmpty()
    return when {
        code == 400 && (detail.contains("Content", true) || detail.contains("risk", true) || detail.contains("审核")) ->
            "内容未通过官方审核，无法处理。"
        code == 400 && detail.isNotBlank() -> detail
        code == 401 -> "API Key 无效或已过期，请在设置里重新填写。"
        code == 402 -> "余额不足，请检查 DeepSeek 账户。"
        code == 413 -> "文件过大（上限 8MB / 120 秒）。"
        code == 429 -> "请求过于频繁或额度受限，请稍后再试。"
        code == 503 -> "官方服务繁忙，请稍后再试。"
        code == 504 -> "官方服务超时，请稍后再试。"
        else -> detail.ifBlank { fallback }
    }
}

/**
 * Builds the /anthropic/v1/messages request body.
 * Pure (no Android / network) so request shaping is covered by JVM tests.
 */
internal fun buildChatBody(
    model: String,
    thinking: Boolean,
    webSearch: Boolean,
    systemPrompt: String?,
    messages: List<ApiMsg>,
    maxTokens: Int,
): JSONObject = JSONObject().apply {
    put("model", model)
    put("max_tokens", maxTokens.coerceIn(ApiClient.MIN_MAX_TOKENS, ApiClient.MAX_MAX_TOKENS))
    put("stream", true)
    put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
    if (webSearch) {
        put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("type", "deepseek_web_search")
                    .put("name", "deepseek_web_search"),
            ),
        )
    }
    systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
    val arr = JSONArray()
    messages.forEach { m ->
        arr.put(JSONObject().apply {
            put("role", m.role)
            // Reasoning continuity: an assistant turn with a stored signature is replayed as a
            // thinking block + text block so the model can verify the chain of thought it produced
            // (required by extended thinking).
            if (m.role == "assistant" && m.signature.isNotBlank() && m.thinking.isNotBlank()) {
                put("content", JSONArray().apply {
                    put(
                        JSONObject()
                            .put("type", "thinking")
                            .put("thinking", m.thinking)
                            .put("signature", m.signature),
                    )
                    put(JSONObject().put("type", "text").put("text", m.content))
                })
            } else {
                put("content", m.content)
            }
        })
    }
    put("messages", arr)
}

/**
 * Consumes an SSE stream: reads "event:"/"data:" blocks, parses them with [Sse.parse] and hands
 * every event to [onEvent]. Returns true only when the server sent message_stop (complete turn).
 * Free of Android APIs so the stream contract has JVM protocol regression tests.
 */
internal fun pumpSse(
    reader: BufferedReader,
    isCancelled: () -> Boolean,
    onEvent: (StreamEvent) -> Unit,
): Boolean {
    var event = ""
    val data = StringBuilder(256)
    var gotMessageStop = false
    while (true) {
        if (isCancelled()) break
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                for (ev in Sse.parse(event, data.toString())) {
                    if (ev is StreamEvent.Stop) gotMessageStop = true
                    onEvent(ev)
                }
                event = ""
                data.setLength(0)
            }
        } else if (line.startsWith("event:")) {
            event = line.removePrefix("event:").trim()
        } else if (line.startsWith("data:")) {
            data.append(line.removePrefix("data:").trim())
        }
    }
    return gotMessageStop
}

object ApiClient {
    private val main = Handler(Looper.getMainLooper())

    private const val FLUSH_INTERVAL_MS = 16L
    private const val FLUSH_BYTES = 4096
    const val DEFAULT_MAX_TOKENS = 32_768
    const val MIN_MAX_TOKENS = 4_096
    const val MAX_MAX_TOKENS = 65_536

    fun stream(
        apiKey: String,
        model: String,
        thinking: Boolean,
        webSearch: Boolean = false,
        systemPrompt: String? = null,
        messages: List<ApiMsg>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        flushMs: Int = 16,
        onText: (String) -> Unit,
        onThinking: (String) -> Unit,
        onSignature: ((String) -> Unit)? = null,
        onUsage: (ApiUsage) -> Unit,
        onStopReason: ((String) -> Unit)? = null,
        onCitation: ((Citation) -> Unit)? = null,
        onError: (String) -> Unit,
        onDone: (cancelled: Boolean) -> Unit,
    ): StreamHandle {
        val handle = StreamHandle()
        Thread {
            val textBuf = StringBuilder(256)
            val thinkBuf = StringBuilder(128)
            var lastFlushAt = SystemClock.uptimeMillis()

            fun flush() {
                if (textBuf.isEmpty() && thinkBuf.isEmpty()) return
                val t = textBuf.toString()
                val th = thinkBuf.toString()
                textBuf.setLength(0)
                thinkBuf.setLength(0)
                lastFlushAt = SystemClock.uptimeMillis()
                main.post {
                    if (th.isNotEmpty()) onThinking(th)
                    if (t.isNotEmpty()) onText(t)
                }
            }

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(BuildConfig.API_ORIGIN + "/anthropic/v1/messages").openConnection() as HttpURLConnection)
                handle.connection = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 180000
                conn.useCaches = false
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("accept", "text/event-stream")
                conn.setRequestProperty("accept-encoding", "identity")
                val body = buildChatBody(model, thinking, webSearch, systemPrompt, messages, maxTokens)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = runCatching {
                        conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    }.getOrDefault("")
                    throw ApiException(code, text)
                }
                val reader = conn.inputStream.reader(Charsets.UTF_8).buffered(32 * 1024)
                val gotMessageStop = pumpSse(reader, { handle.cancelled }) { ev ->
                    when (ev) {
                        is StreamEvent.Text -> textBuf.append(ev.text)
                        is StreamEvent.Thinking -> thinkBuf.append(ev.text)
                        is StreamEvent.Signature -> main.post { onSignature?.invoke(ev.signature) }
                        is StreamEvent.CitationFound -> main.post { onCitation?.invoke(ev.citation) }
                        is StreamEvent.Usage -> main.post { onUsage(ev.usage.copy(full = ev.full)) }
                        is StreamEvent.StopReason -> main.post { onStopReason?.invoke(ev.reason) }
                        StreamEvent.Stop -> Unit
                        is StreamEvent.Failure -> textBuf.append("\n\n[错误] ").append(ev.message)
                    }
                    if (textBuf.length + thinkBuf.length >= FLUSH_BYTES ||
                        SystemClock.uptimeMillis() - lastFlushAt >= flushMs.coerceIn(16, 200)
                    ) {
                        flush()
                    }
                }
                flush()
                if (!handle.cancelled && !gotMessageStop) {
                    // The server hung up mid-stream. Content is kept, but the turn is marked
                    // failed so the UI offers retry instead of pretending it completed.
                    main.post {
                        onError("连接提前结束：没有收到 message_stop，内容可能不完整。")
                        onDone(false)
                    }
                } else {
                    main.post { onDone(handle.cancelled) }
                }
            } catch (e: Exception) {
                flush()
                if (handle.cancelled) {
                    main.post { onDone(true) }
                } else {
                    val msg = if (e is ApiException) {
                        httpErrorMessage(e.code, e.body, "请求失败（HTTP " + e.code + "）")
                    } else {
                        e.message ?: "网络连接失败"
                    }
                    main.post {
                        onError(msg)
                        onDone(false)
                    }
                }
            }
        }.start()
        return handle
    }
}
