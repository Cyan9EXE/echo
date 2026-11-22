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

data class ApiMsg(val role: String, val content: String)

data class ApiUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreateTokens: Int = 0,
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

object ApiClient {
    private val main = Handler(Looper.getMainLooper())

    private const val FLUSH_INTERVAL_MS = 60L
    private const val FLUSH_BYTES = 8192

    fun stream(
        apiKey: String,
        model: String,
        thinking: Boolean,
        webSearch: Boolean = false,
        systemPrompt: String? = null,
        messages: List<ApiMsg>,
        flushMs: Int = 60,
        onText: (String) -> Unit,
        onThinking: (String) -> Unit,
        onUsage: (ApiUsage) -> Unit,
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
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 8192)
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
                            put("content", m.content)
                        })
                    }
                    put("messages", arr)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = runCatching {
                        conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    }.getOrDefault("")
                    throw ApiException(code, text)
                }
                val reader = conn.inputStream.reader(Charsets.UTF_8).buffered(32 * 1024)
                var event = ""
                val data = StringBuilder(256)
                var inputTokens = 0
                var outputTokens = 0
                var cacheRead = 0
                var cacheCreate = 0
                while (true) {
                    if (handle.cancelled) break
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val raw = data.toString()
                            parseEvent(event, raw, textBuf, thinkBuf, onCitation ?: {}) {
                                inputTokens = maxOf(inputTokens, it.inputTokens)
                                outputTokens = maxOf(outputTokens, it.outputTokens)
                                cacheRead = maxOf(cacheRead, it.cacheReadTokens)
                                cacheCreate = maxOf(cacheCreate, it.cacheCreateTokens)
                            }
                            event = ""
                            data.setLength(0)
                            if (textBuf.length + thinkBuf.length >= FLUSH_BYTES ||
                                SystemClock.uptimeMillis() - lastFlushAt >= flushMs.coerceIn(16, 200)
                            ) {
                                flush()
                            }
                        }
                    } else if (line.startsWith("event:")) {
                        event = line.removePrefix("event:").trim()
                    } else if (line.startsWith("data:")) {
                        data.append(line.removePrefix("data:").trim())
                    }
                }
                flush()
                main.post {
                    onUsage(ApiUsage(inputTokens, outputTokens, cacheRead, cacheCreate))
                    onDone(false)
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

    private inline fun parseEvent(
        event: String,
        json: String,
        textBuf: StringBuilder,
        thinkBuf: StringBuilder,
        noinline onCitation: (Citation) -> Unit,
        onUsage: (ApiUsage) -> Unit,
    ) {
        if (json.isEmpty()) return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (event.ifEmpty { obj.optString("type") }) {
            "content_block_delta" -> {
                val delta = obj.optJSONObject("delta") ?: return
                when (delta.optString("type")) {
                    "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }?.let { textBuf.append(it) }
                    "thinking_delta" -> delta.optString("thinking").takeIf { it.isNotEmpty() }?.let { thinkBuf.append(it) }
                    "citations_delta" -> {
                        val c = delta.optJSONObject("citation") ?: return
                        val url = c.optString("url")
                        if (url.isNotBlank()) onCitation(Citation(url, c.optString("title")))
                    }
                }
            }
            "message_start" -> {
                val u = obj.optJSONObject("message")?.optJSONObject("usage") ?: return
                onUsage(
                    ApiUsage(
                        inputTokens = u.optInt("input_tokens", 0),
                        cacheReadTokens = u.optInt("cache_read_input_tokens", 0),
                        cacheCreateTokens = u.optInt("cache_creation_input_tokens", 0),
                    )
                )
            }
            "message_delta" -> {
                val u = obj.optJSONObject("usage") ?: return
                onUsage(ApiUsage(outputTokens = u.optInt("output_tokens", 0)))
            }
            "error" -> {
                val message = obj.optJSONObject("error")?.optString("message") ?: "生成失败"
                textBuf.append("\n\n[错误] ").append(message)
            }
        }
    }
}
