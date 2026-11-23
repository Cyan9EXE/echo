package app.echo

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class Msg(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    text: String,
    thinking: String = "",
    val model: String = "",
    val time: Long = System.currentTimeMillis(),
    inputTokens: Int = 0,
    outputTokens: Int = 0,
    cacheReadTokens: Int = 0,
    cacheCreateTokens: Int = 0,
    thinkingMs: Long = 0L,
    signature: String = "",
) {
    var text by mutableStateOf(text)
    var thinking by mutableStateOf(thinking)
    var inputTokens by mutableStateOf(inputTokens)
    var outputTokens by mutableStateOf(outputTokens)
    var cacheReadTokens by mutableStateOf(cacheReadTokens)
    var cacheCreateTokens by mutableStateOf(cacheCreateTokens)
    var thinkingMs by mutableStateOf(thinkingMs)
    var costCny by mutableStateOf(0.0)
    var citations by mutableStateOf<List<Citation>>(emptyList())
    var failed by mutableStateOf(false)
    /** Thinking signature for reasoning continuity; only set when the server sent one. */
    var signature by mutableStateOf(signature)
    /** Upstream stop_reason (end_turn / max_tokens / tool_use ...). */
    var stopReason by mutableStateOf("")
}

internal data class MsgSnapshot(
    val id: String,
    val role: String,
    val text: String,
    val thinking: String,
    val model: String,
    val time: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheCreateTokens: Int,
    val costCny: Double,
    val citations: List<Citation> = emptyList(),
    val thinkingMs: Long = 0L,
    val signature: String = "",
    val stopReason: String = "",
)

data class SummaryBlock(
    val title: String = "",
    val bullets: List<String> = emptyList(),
    val startId: String = "",
    val endId: String = "",
    val startIndex: Int = 0,
    val endIndex: Int = 0,
    val count: Int = 0,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
)

internal data class SummaryPlan(
    val text: String,
    val covered: Int,
    val blocks: List<SummaryBlock>,
)

internal data class SummaryCandidate(
    val priority: Int,
    val order: Int,
    val text: String,
)

internal data class SessionSnapshot(
    val id: String,
    val summary: String,
    val covered: Int,
    val blocks: List<SummaryBlock>,
    val messages: List<MsgSnapshot>,
)

internal data class IndexRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val expired: Boolean,
    val revived: Boolean,
    val contextMode: String,
    val summaryCovered: Int,
    val imported: Boolean,
    val pinned: Boolean,
    val costCny: Double,
    val roles: String,
    val systemSent: Boolean,
    val chars: Long,
)

class MessageList : AbstractMutableList<Msg>() {
    private val loaded = ArrayList<Msg>()
    var roleSeq: String = ""
        private set
    /** Compose 可观察：异步解析完成后 UI 必须能重组，不能再用普通布尔。 */
    var ready by mutableStateOf(false)
        private set
    private val stubUser = Msg(role = "user", text = "")
    private val stubAssistant = Msg(role = "assistant", text = "")

    override val size: Int get() = if (ready) loaded.size else roleSeq.length

    val userTurns: Int get() = roleSeq.count { it == 'u' }

    override fun get(index: Int): Msg {
        if (ready) return loaded[index]
        return if (roleSeq[index] == 'u') stubUser else stubAssistant
    }

    override fun add(index: Int, element: Msg) {
        check(ready) { "MessageList 尚未载入，禁止写入" }
        loaded.add(index, element)
        roleSeq = roleSeq.substring(0, index) + roleOf(element) + roleSeq.substring(index)
    }

    override fun removeAt(index: Int): Msg {
        check(ready) { "MessageList 尚未载入，禁止写入" }
        roleSeq = roleSeq.substring(0, index) + roleSeq.substring(index + 1)
        return loaded.removeAt(index)
    }

    override fun set(index: Int, element: Msg): Msg {
        check(ready) { "MessageList 尚未载入，禁止写入" }
        roleSeq = roleSeq.substring(0, index) + roleOf(element) + roleSeq.substring(index + 1)
        return loaded.set(index, element)
    }

    fun markReady(list: List<Msg>) {
        // A late async parse must not overwrite messages added after ready (P1-1).
        if (ready) return
        loaded.clear()
        loaded.addAll(list)
        val sb = StringBuilder(list.size)
        for (m in list) sb.append(roleOf(m))
        roleSeq = sb.toString()
        ready = true
    }

    fun markEmpty() = markReady(emptyList())

    fun markUnloaded(roles: String) {
        loaded.clear()
        roleSeq = roles
        ready = false
    }

    fun unload() {
        loaded.clear()
        ready = false
    }

    private fun roleOf(m: Msg): Char = if (m.role == "user") 'u' else 'a'
}

class Session(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var updatedAt: Long = System.currentTimeMillis(),
    expired: Boolean = false,
    revived: Boolean = false,
    var contextMode: String = "full",
    var summary: String = "",
    var summaryCovered: Int = 0,
    var imported: Boolean = false,
    var pinned: Boolean = false,
    var costCny: Double = 0.0,
    var charCount: Long = 0L,
    var systemSent: Boolean = false,
) {
    var expired: Boolean by mutableStateOf(expired)
    var revived: Boolean by mutableStateOf(revived)
    val messages = MessageList()
    val summaryBlocks = mutableStateListOf<SummaryBlock>()
}
