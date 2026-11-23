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
    /** 失败/中断的回复：落盘但不进后续上下文（P2-4）。 */
    val failed: Boolean = false,
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
    /** 会话级元数据随消息文件落盘，索引丢失时对账恢复不丢置顶/复活/导入/费用（R9-P2-3）。 */
    val title: String = "",
    val updatedAt: Long = 0L,
    val expired: Boolean = false,
    val revived: Boolean = false,
    val contextMode: String = "full",
    val imported: Boolean = false,
    val pinned: Boolean = false,
    val costCny: Double = 0.0,
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
    val chars: Long,
)

class MessageList : AbstractMutableList<Msg>() {
    /**
     * 整体发布而不是原地改 ArrayList：ready 之后 add/remove/set 也会让读 size/get
     * 的 Compose 内容自动失效，不再依赖调用方手动碰 ready/revision（P2-4）。
     */
    private var loaded by mutableStateOf<List<Msg>>(emptyList())
    var roleSeq: String = ""
        private set

    /** Compose 可观察：异步解析完成后 UI 必须能重组。 */
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
        requireLoaded()
        val next = ArrayList<Msg>(loaded.size + 1)
        next.addAll(loaded.subList(0, index))
        next.add(element)
        next.addAll(loaded.subList(index, loaded.size))
        loaded = next
        roleSeq = roleSeq.substring(0, index) + roleOf(element) + roleSeq.substring(index)
    }

    override fun removeAt(index: Int): Msg {
        requireLoaded()
        val next = ArrayList<Msg>(loaded.size - 1)
        next.addAll(loaded.subList(0, index))
        next.addAll(loaded.subList(index + 1, loaded.size))
        val removed = loaded[index]
        loaded = next
        roleSeq = roleSeq.substring(0, index) + roleSeq.substring(index + 1)
        return removed
    }

    override fun set(index: Int, element: Msg): Msg {
        requireLoaded()
        val next = ArrayList<Msg>(loaded)
        val old = next.set(index, element)
        loaded = next
        roleSeq = roleSeq.substring(0, index) + roleOf(element) + roleSeq.substring(index + 1)
        return old
    }

    /** @return true when this parse was applied; false when a newer state already won. */
    fun markReady(list: List<Msg>): Boolean {
        // A late async parse must not overwrite messages added after ready (P1-1).
        if (ready) return false
        loaded = ArrayList(list)
        val sb = StringBuilder(list.size)
        for (m in list) sb.append(roleOf(m))
        roleSeq = sb.toString()
        ready = true
        return true
    }

    fun markEmpty() = markReady(emptyList())

    fun markUnloaded(roles: String) {
        loaded = emptyList()
        roleSeq = roles
        ready = false
    }

    fun unload() {
        loaded = emptyList()
        ready = false
    }

    /** 写路径的显式前置条件；与 UI 可见性解耦，但仍不允许在未载入时写入（P2-4）。 */
    private fun requireLoaded() {
        check(ready) { "MessageList 尚未载入，禁止写入" }
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
) {
    var expired: Boolean by mutableStateOf(expired)
    var revived: Boolean by mutableStateOf(revived)
    val messages = MessageList()
    val summaryBlocks = mutableStateListOf<SummaryBlock>()
}
