package app.echo

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class AppState(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("continue-native", Context.MODE_PRIVATE)
    private val codeFenceRe = Regex("\u0060\u0060\u0060[\\s\\S]*?\u0060\u0060\u0060")
    private val whitespaceRe = Regex("\\s+")
    private val sentenceEndRe = Regex("(?<=[。！？!?])")
    private val keySentenceRe = Regex("(结论|所以|因此|建议|决定|方案|注意|关键|不能|必须|原因|总结|要点)")
    private val constraintRe = Regex("(要求|必须|不能|不要|只能|需要|限制|约束|记住|请先|先别|严禁|优先)")

    private val legacyFile = File(appContext.filesDir, "sessions.json")
    private val storeDir = File(appContext.filesDir, "store")
    private val msgDir = File(storeDir, "msg")
    private val indexFile = File(storeDir, "index.json")
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "echo-store").apply { priority = Thread.MIN_PRIORITY }
    }

    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }
    private val dirtyIds = LinkedHashSet<String>()
    private var lastRevisionAt = 0L
    private var estimateRevision = -1
    private var estimateValue = 0
    private var estimateAt = 0L

    // Plain-text prefs are gone: the key is sealed with an AES-256-GCM key that
    // lives in the Android Keystore (see KeyVault). load() also rewrites a legacy
    // plain-text value in place, or drops it when the Keystore entry is gone.
    private val storedApiKey = KeyVault.load(appContext)

    var apiKey by mutableStateOf(storedApiKey.key)
        private set
    var model by mutableStateOf(prefs.getString("model", "deepseek-flash").orEmpty())
        private set
    var thinking by mutableStateOf(prefs.getBoolean("thinking", true))
        private set
    var webSearch by mutableStateOf(prefs.getBoolean("webSearch", false))
        private set
    var systemPrompt by mutableStateOf(prefs.getString("systemPrompt", "").orEmpty())
        private set
    var skinIndex by mutableStateOf(
        prefs.getInt("skinIndex", if (prefs.getBoolean("blueAccent", true)) 1 else 0)
            .coerceIn(0, C.skins.lastIndex),
    )
        private set
    var fontScale by mutableStateOf(prefs.getFloat("fontScale", 1f).coerceIn(0.9f, 1.2f))
        private set
    var showDrawer by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
        private set
    var showHelp by mutableStateOf(false)
        private set
    var importGuideOff by mutableStateOf(prefs.getBoolean("importGuideOff", false))
        private set
    var showUsage by mutableStateOf(false)
        private set
    var ttsVoice by mutableStateOf(prefs.getString("ttsVoice", "stella").orEmpty())
        private set
    var ttsSkipConfirm by mutableStateOf(prefs.getBoolean("ttsSkipConfirm", false))
        private set
    var asrSkipConfirm by mutableStateOf(prefs.getBoolean("asrSkipConfirm", false))
        private set
    var notice by mutableStateOf<String?>(null)
    var typing by mutableStateOf(false)
    var streaming by mutableStateOf(false)
    var importing by mutableStateOf(false)
        private set
    var importStatus by mutableStateOf("")
        private set
    var revision by mutableStateOf(0)
    var currentId by mutableStateOf<String?>(null)
        private set

    private var streamSeq = 0
    private var streamSessionId: String? = null
    private var activeStream: StreamHandle? = null
    private var flushLive: (() -> Unit)? = null

    val sessions = mutableStateListOf<Session>()
    // current is read by most composables and by token estimation; scanning the
    // full 3,800-session list on every read was a main-thread hotspot. Cache by id
    // and drop the cache whenever membership or the current selection changes.
    private var currentCache: Session? = null
    private var currentCacheId: String? = null
    val current: Session?
        get() {
            val id = currentId ?: return null
            if (currentCacheId == id) currentCache?.let { return it }
            val found = sessions.firstOrNull { it.id == id }
            currentCache = found
            currentCacheId = id
            return found
        }
    private fun invalidateCurrent() {
        currentCache = null
        currentCacheId = null
    }

    init {
        if (storedApiKey.lost) notice = "API Key 无法解密（系统密钥库已变更），请重新填写。"
        prefs.edit().remove("blueAccent").apply()
        C.initDay(prefs.getBoolean("dayMode", false))
        if (prefs.contains("officialToken") || prefs.contains("officialName") || prefs.contains("officialPhone") || prefs.contains("officialDeviceId") || prefs.contains("mode")) {
            prefs.edit()
                .remove("officialToken").remove("officialName").remove("officialPhone")
                .remove("officialDeviceId").remove("mode")
                .apply()
        }
        storeDir.mkdirs()
        msgDir.mkdirs()
        UsageLedger.init(appContext)
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                flushPending()
                evictLoaded()
            }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    flushPending()
                    evictLoaded()
                } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    evictLoaded()
                }
            }
        })
        if (indexFile.exists()) loadIndexAsync() else migrateLegacyAsync()
    }

    /** Returns false when the key is only valid for this process (Keystore unusable). */
    fun updateApiKey(value: String): Boolean {
        val clean = value.trim()
        apiKey = clean
        val saved = KeyVault.save(appContext, clean)
        notice = if (saved) null else "无法安全保存 Key：系统密钥库不可用。本次运行临时有效。"
        return saved
    }

    fun updateModel(value: String) {
        model = value
        prefs.edit().putString("model", model).apply()
    }

    fun updateWebSearch(value: Boolean) {
        webSearch = value
        prefs.edit().putBoolean("webSearch", webSearch).apply()
    }

    fun updateThinking(value: Boolean) {
        thinking = value
        prefs.edit().putBoolean("thinking", value).apply()
    }

    fun updateSystemPrompt(value: String) {
        systemPrompt = value.take(2000)
        prefs.edit().putString("systemPrompt", systemPrompt).apply()
    }

    fun updateSkin(index: Int) {
        skinIndex = index.coerceIn(0, C.skins.lastIndex)
        prefs.edit().putInt("skinIndex", skinIndex).apply()
    }

    fun updateFontScale(value: Float) {
        fontScale = value.coerceIn(0.9f, 1.2f)
        prefs.edit().putFloat("fontScale", fontScale).apply()
    }

    fun updateTtsVoice(value: String) {
        ttsVoice = if (value == "tide") "tide" else "stella"
        prefs.edit().putString("ttsVoice", ttsVoice).apply()
    }

    fun updateTtsSkipConfirm(value: Boolean) {
        ttsSkipConfirm = value
        prefs.edit().putBoolean("ttsSkipConfirm", value).apply()
    }

    fun updateAsrSkipConfirm(value: Boolean) {
        asrSkipConfirm = value
        prefs.edit().putBoolean("asrSkipConfirm", value).apply()
    }

    val dayMode: Boolean get() = C.isDay

    fun toggleDay() {
        C.toggleDay()
        prefs.edit().putBoolean("dayMode", C.isDay).apply()
    }

    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }

    fun openHelp() {
        showDrawer = false
        showHelp = true
    }

    fun closeHelp() { showHelp = false }

    fun updateImportGuide(value: Boolean) {
        importGuideOff = value
        prefs.edit().putBoolean("importGuideOff", value).apply()
    }

    fun openUsage() {
        showSettings = false
        showUsage = true
    }

    fun closeUsage() { showUsage = false }

    fun totalChatCost(): Double = sessions.sumOf { it.costCny }

    fun newChat() {
        typing = false
        showDrawer = false
        val s = Session(title = "新对话")
        s.messages.markEmpty()
        revision++
        sessions.add(0, s)
        currentId = s.id
        markDirty(s.id)
        save()
    }

    fun open(session: Session) {
        typing = false
        showDrawer = false
        ensureReadyForUi(session)
        currentId = session.id
        touchReady(session)
        revision++
    }

    fun backHome() {
        typing = false
        currentId = null
    }

    fun delete(session: Session) {
        sessions.remove(session)
        invalidateCurrent()
        if (currentId == session.id) currentId = null
        dirtyIds.remove(session.id)
        val f = msgFile(session.id)
        io.execute { runCatching { f.delete() } }
        save()
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (streaming) {
            notice = "正在生成；先点暂停，再发送新的消息。"
            return
        }
        typing = false
        var session = current
        if (session != null) ensureReadyBlocking(session)
        if (session != null && session.expired && !session.revived) {
            notice = "这段对话已失效：先点「复活并继续」，恢复完整上下文后再发送。"
            return
        }
        if (apiKey.isBlank()) {
            notice = "先填入 API Key，再开始对话。"
            showSettings = true
            return
        }
        if (session == null) {
            session = Session(title = body.take(24))
            session.messages.markEmpty()
            sessions.add(0, session)
            currentId = session.id
        }
        if (session.messages.isEmpty()) session.title = body.take(24)
        session.messages.add(Msg(role = "user", text = body))
        val effectiveModel = if (webSearch) "deepseek-flash" else model
        val reply = Msg(role = "assistant", text = "", model = effectiveModel)
        session.messages.add(reply)
        session.updatedAt = System.currentTimeMillis()
        streaming = true
        notice = null
        markDirty(session.id)
        scheduleSave()

        val includeSystem = systemPrompt.isNotBlank() && !session.systemSent
        val history = buildHistory(session)
        val sessionId = session.id
        streamSessionId = sessionId
        val mySeq = ++streamSeq
        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        var lastPublish = 0L
        fun publish(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastPublish < 60) return
            lastPublish = now
            reply.text = textBuf.toString()
            reply.thinking = thinkingBuf.toString()
        }
        flushLive = { publish(true) }
        val citations = ArrayList<Citation>()
        val handle = ApiClient.stream(
            apiKey = apiKey,
            model = effectiveModel,
            thinking = thinking,
            webSearch = webSearch,
            systemPrompt = if (includeSystem) systemPrompt.trim() else null,
            messages = history,
            onText = { chunk ->
                if (mySeq == streamSeq) {
                    textBuf.append(chunk)
                    session.updatedAt = System.currentTimeMillis()
                    publish(false)
                    bumpRevisionThrottled()
                }
            },
            onThinking = { chunk ->
                if (mySeq == streamSeq) {
                    thinkingBuf.append(chunk)
                    publish(false)
                    bumpRevisionThrottled()
                }
            },
            onCitation = { c ->
                if (mySeq == streamSeq && citations.none { it.url == c.url }) {
                    citations.add(c)
                    reply.citations = citations.toList()
                }
            },
            onUsage = { u ->
                if (mySeq == streamSeq) {
                    reply.inputTokens = u.inputTokens + u.cacheReadTokens + u.cacheCreateTokens
                    reply.outputTokens = u.outputTokens
                    reply.cacheReadTokens = u.cacheReadTokens
                    reply.cacheCreateTokens = u.cacheCreateTokens
                }
            },
            onError = { message ->
                if (mySeq == streamSeq) {
                    textBuf.append(if (textBuf.isEmpty()) message else "\n\n[中断] " + message)
                    reply.failed = true
                    publish(true)
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    markDirty(sessionId)
                    revision++
                    save()
                }
            },
            onDone = { cancelled ->
                if (mySeq == streamSeq) {
                    publish(true)
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    if (includeSystem) session.systemSent = true
                    if (cancelled) {
                        notice = "已暂停生成；已保留当前内容。"
                    } else {
                        if (reply.text.isBlank()) reply.text = "（没有收到内容）"
                        finishUsage(session, reply)
                    }
                    markDirty(sessionId)
                    revision++
                    save()
                }
            },
        )
        if (mySeq == streamSeq) activeStream = handle else handle.cancel()
    }

    fun stopGenerating() {
        if (!streaming && activeStream == null) return
        flushLive?.invoke()
        flushLive = null
        streamSeq++
        activeStream?.cancel()
        activeStream = null
        streaming = false
        notice = "已暂停生成；已保留当前内容。"
        (streamSessionId ?: current?.id)?.let { markDirty(it) }
        streamSessionId = null
        revision++
        scheduleSave()
    }

    fun revive(session: Session, mode: String = "full") {
        ensureReadyBlocking(session)
        session.revived = true
        val total = historyMessages(session).size
        if (mode == "summary" && total > 40) {
            val plan = buildLocalSummary(session)
            session.contextMode = "summary"
            session.summary = plan.text
            session.summaryCovered = plan.covered
            session.summaryBlocks.clear()
            session.summaryBlocks.addAll(plan.blocks)
            session.systemSent = false
            notice = "已用摘要模式恢复：早期对话由本机压成要点，后部原文保留；不调用 API、不额外消耗 token。"
        } else {
            session.contextMode = "full"
            session.summary = ""
            session.summaryCovered = 0
            session.summaryBlocks.clear()
            notice = if (mode == "summary") "这段会话较短，无需摘要；已按完整原文恢复。" else "已恢复完整上下文：当前分支全部原文会作为后续上下文，由你的 API Key 直连支付。"
        }
        markDirty(session.id)
        save()
        revision++
    }

    fun retryLast() {
        val session = current ?: return
        ensureReadyBlocking(session)
        val lastUser = session.messages.lastOrNull { it.role == "user" } ?: return
        if (session.messages.lastOrNull()?.role == "assistant") session.messages.removeAt(session.messages.size - 1)
        session.messages.remove(lastUser)
        send(lastUser.text)
    }

    private fun historyMessages(session: Session): List<Msg> =
        session.messages.filter { it.text.isNotBlank() && (it.role == "user" || it.role == "assistant") }

    private fun buildHistory(session: Session): List<ApiMsg> {
        val all = historyMessages(session)
        if (session.contextMode != "summary" || session.summary.isBlank()) {
            return all.map { ApiMsg(it.role, it.text) }
        }
        val tail = all.drop(session.summaryCovered.coerceIn(0, all.size)).toMutableList()
        val firstUser = tail.indexOfFirst { it.role == "user" }
        if (firstUser >= 0) {
            val merged = tail[firstUser]
            val header = "[更早对话的摘要 · 本机生成]\n" +
                "（仅作背景，直接继续对话；不要向用户复述本摘要或提及内部标注。）\n\n"
            tail[firstUser] = Msg(
                role = "user",
                text = header + session.summary + "\n\n[以下是最近原文]\n" + merged.text,
                time = merged.time,
            )
        }
        return tail.map { ApiMsg(it.role, it.text) }
    }

    fun summaryBlockMessages(session: Session, block: SummaryBlock): List<Msg> {
        val all = historyMessages(session)
        if (all.isEmpty()) return emptyList()
        var start = all.indexOfFirst { it.id == block.startId }
        var end = all.indexOfFirst { it.id == block.endId }
        if (start < 0) start = block.startIndex.coerceIn(0, all.lastIndex)
        if (end < start) end = (start + block.count - 1).coerceAtMost(all.lastIndex)
        return all.subList(start, end + 1).toList()
    }

    private fun cleanSummaryText(text: String): String =
        text.replace(codeFenceRe, "（含代码）").replace(whitespaceRe, " ").trim()

    private fun clipSummary(text: String, max: Int): String {
        val clean = cleanSummaryText(text)
        if (clean.length <= max) return clean
        val cut = clean.substring(0, max)
        val p = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '；', ';'))
        val body = if (p >= max / 2) cut.substring(0, p + 1) else cut
        return body.trimEnd() + "…"
    }

    private fun summaryTopic(text: String): String {
        val first = clipSummary(text, 26).trimEnd('…', '。', '，', '、', '；', ';', ' ')
        return if (first.isBlank()) "主题：早期对话" else "主题：" + first
    }

    private fun assistantKeySentence(text: String): String {
        val clean = cleanSummaryText(text)
        if (clean.isEmpty()) return ""
        val sentences = clean.split(sentenceEndRe).map { it.trim() }.filter { it.isNotEmpty() }
        return sentences.firstOrNull { keySentenceRe.containsMatchIn(it) }
            ?: sentences.firstOrNull().orEmpty()
    }

    private fun summaryRanges(early: List<Msg>): List<IntRange> {
        val n = early.size
        if (n == 0) return emptyList()
        val target = (n / 10).coerceIn(1, 10).coerceAtMost(n)
        val chunk = (n + target - 1) / target
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < n) {
            var end = (start + chunk - 1).coerceAtMost(n - 1)
            if (end < n - 1) {
                var nextUser = end + 1
                while (nextUser < n && early[nextUser].role != "user") nextUser++
                if (nextUser < n) end = nextUser - 1
            }
            if (n - 1 - end in 1..2 && ranges.isNotEmpty()) end = n - 1
            ranges.add(start..end)
            start = end + 1
        }
        while (ranges.size > 12) {
            val last = ranges.removeAt(ranges.lastIndex)
            val prev = ranges.removeAt(ranges.lastIndex)
            ranges.add(prev.first..last.last)
        }
        return ranges
    }

    private fun buildSummaryBlock(early: List<Msg>, range: IntRange, budget: Int): SummaryBlock {
        val msgs = early.subList(range.first, range.last + 1)
        val firstUser = msgs.firstOrNull { it.role == "user" }
        val title = summaryTopic(firstUser?.text ?: msgs.firstOrNull()?.text.orEmpty())
        val candidates = ArrayList<SummaryCandidate>(msgs.size)
        var order = 0
        var userSeen = 0
        for (m in msgs) {
            order++
            if (m.role == "user") {
                val clean = cleanSummaryText(m.text)
                if (clean.isEmpty()) continue
                val priority = when {
                    constraintRe.containsMatchIn(clean) -> 98
                    userSeen == 0 -> 100
                    userSeen == 1 -> 94
                    userSeen == 2 -> 90
                    else -> 78
                }
                userSeen++
                candidates.add(SummaryCandidate(priority, order, "用户：" + clipSummary(clean, 180)))
            } else {
                val key = assistantKeySentence(m.text)
                if (key.isNotBlank()) {
                    val priority = if (keySentenceRe.containsMatchIn(key) || constraintRe.containsMatchIn(key)) 86 else 52
                    candidates.add(SummaryCandidate(priority, order, "助手：" + clipSummary(key, 150)))
                }
            }
        }
        if (candidates.none { it.text.startsWith("助手：") }) {
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
            if (lastAssistant != null) {
                candidates.add(SummaryCandidate(30, msgs.size + 1, "助手：" + clipSummary(lastAssistant.text, 120)))
            }
        }
        val selected = ArrayList<SummaryCandidate>(5)
        var used = 0
        for (c in candidates.sortedWith(compareByDescending<SummaryCandidate> { it.priority }.thenBy { it.order })) {
            if (selected.size >= 5) break
            if (selected.isNotEmpty() && used + c.text.length > budget) continue
            selected.add(c)
            used += c.text.length
        }
        selected.sortBy { it.order }
        return SummaryBlock(
            title = title,
            bullets = selected.map { it.text },
            startId = msgs.first().id,
            endId = msgs.last().id,
            startIndex = range.first,
            endIndex = range.last,
            count = msgs.size,
            startTime = msgs.first().time,
            endTime = msgs.last().time,
        )
    }

    private fun buildLocalSummary(session: Session): SummaryPlan {
        val all = historyMessages(session)
        val keep = 40
        val covered = (all.size - keep).coerceAtLeast(0)
        if (covered == 0) return SummaryPlan("", 0, emptyList())
        val early = all.take(covered)
        val ranges = summaryRanges(early)
        val perBlock = (3600 / ranges.size.coerceAtLeast(1)).coerceIn(320, 820)
        val blocks = ArrayList<SummaryBlock>(ranges.size)
        for (range in ranges) blocks.add(buildSummaryBlock(early, range, perBlock))
        val sb = StringBuilder()
        for (b in blocks) {
            if (b.bullets.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("【").append(b.title).append("】").append("\n")
            for (line in b.bullets) sb.append("- ").append(line).append("\n")
        }
        return SummaryPlan(sb.toString().trim(), covered, blocks)
    }

    fun estimateTokens(): Int {
        val s = current ?: return 0
        if (estimateRevision == revision) return estimateValue
        val now = SystemClock.uptimeMillis()
        if (now - estimateAt < 200) return estimateValue
        val chars = if (s.contextMode == "summary" && s.summary.isNotBlank()) {
            s.summary.length + historyMessages(s).drop(s.summaryCovered).sumOf { it.text.length }
        } else {
            s.messages.sumOf { it.text.length }
        }
        val v = (chars / 2.5).toInt() + 12
        estimateRevision = revision
        estimateValue = v
        estimateAt = now
        return v
    }

    fun isPeakNow(): Boolean {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        if (now.dayOfWeek == java.time.DayOfWeek.SATURDAY || now.dayOfWeek == java.time.DayOfWeek.SUNDAY) return false
        val minute = now.hour * 60 + now.minute
        return (minute in 540..719) || (minute in 840..1079)
    }

    fun msgCost(m: Msg): Double {
        if (m.inputTokens == 0 && m.outputTokens == 0) return 0.0
        val peak = isPeakNow()
        val hit: Double
        val miss: Double
        val out: Double
        if (m.model.ifBlank { model } == "deepseek-v4-pro") {
            hit = if (peak) 0.30 else 0.15
            miss = if (peak) 9.0 else 4.5
            out = if (peak) 27.0 else 13.5
        } else {
            hit = if (peak) 0.04 else 0.02
            miss = if (peak) 2.0 else 1.0
            out = if (peak) 8.0 else 4.0
        }
        val missTokens = (m.inputTokens - m.cacheReadTokens - m.cacheCreateTokens).coerceAtLeast(0)
        return m.cacheReadTokens / 1_000_000.0 * hit +
            missTokens / 1_000_000.0 * miss +
            m.outputTokens / 1_000_000.0 * out
    }

    private fun finishUsage(session: Session, reply: Msg) {
        val cost = msgCost(reply)
        reply.costCny = cost
        if (cost > 0.0) session.costCny += cost
    }

    private fun markDirty(id: String) {
        dirtyIds.add(id)
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 700)
    }

    private fun flushPending() {
        main.removeCallbacks(saveRunnable)
        save()
    }

    fun save() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { save() }
            return
        }
        if (dirtyIds.isEmpty()) return
        val ids = ArrayList(dirtyIds)
        dirtyIds.clear()
        val snapshots = ArrayList<SessionSnapshot>(ids.size)
        for (id in ids) {
            val s = sessions.firstOrNull { it.id == id } ?: continue
            if (!s.messages.ready) continue
            snapshots.add(snapshotSession(s))
        }
        // Index rows touch Compose state and message lists, so they are still built on
        // the main thread; only JSON encoding and disk writes happen on the store thread.
        val rows = sessions.map(::sessionIndexRow)
        io.execute {
            for (snap in snapshots) {
                runCatching { writeAtomic(msgFile(snap.id), serializeSnapshot(snap)) }
            }
            runCatching { writeAtomic(indexFile, serializeIndex(rows)) }
        }
    }

    private fun snapshotSession(s: Session): SessionSnapshot {
        val messages = ArrayList<MsgSnapshot>(s.messages.size)
        for (m in s.messages) {
            messages.add(
                MsgSnapshot(
                    id = m.id,
                    role = m.role,
                    text = m.text,
                    thinking = m.thinking,
                    model = m.model,
                    time = m.time,
                    inputTokens = m.inputTokens,
                    outputTokens = m.outputTokens,
                    cacheReadTokens = m.cacheReadTokens,
                    cacheCreateTokens = m.cacheCreateTokens,
                    costCny = m.costCny,
                    citations = m.citations.toList(),
                ),
            )
        }
        return SessionSnapshot(s.id, s.summary, s.summaryCovered, s.summaryBlocks.toList(), messages)
    }

    private fun putSummaryBlocks(o: JSONObject, blocks: List<SummaryBlock>) {
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(JSONObject().apply {
                put("title", b.title)
                put("bullets", JSONArray().apply { b.bullets.forEach { put(it) } })
                put("startId", b.startId)
                put("endId", b.endId)
                put("startIndex", b.startIndex)
                put("endIndex", b.endIndex)
                put("count", b.count)
                put("startTime", b.startTime)
                put("endTime", b.endTime)
            })
        }
        o.put("blocks", arr)
    }

    private fun readSummaryBlocks(o: JSONObject): List<SummaryBlock> {
        val arr = o.optJSONArray("blocks") ?: return emptyList()
        val out = ArrayList<SummaryBlock>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val bulletsArr = b.optJSONArray("bullets")
            val bullets = ArrayList<String>()
            if (bulletsArr != null) for (j in 0 until bulletsArr.length()) bullets.add(bulletsArr.optString(j))
            out.add(
                SummaryBlock(
                    title = b.optString("title"),
                    bullets = bullets,
                    startId = b.optString("startId"),
                    endId = b.optString("endId"),
                    startIndex = b.optInt("startIndex"),
                    endIndex = b.optInt("endIndex"),
                    count = b.optInt("count"),
                    startTime = b.optLong("startTime"),
                    endTime = b.optLong("endTime"),
                ),
            )
        }
        return out
    }

    private fun sessionIndexRow(s: Session): IndexRow = IndexRow(
        s.id, s.title, s.updatedAt, s.expired, s.revived, s.contextMode,
        s.summaryCovered, s.imported, s.pinned, s.costCny, s.messages.roleSeq, s.systemSent,
        if (s.messages.ready) s.messages.sumOf { it.text.length.toLong() } else s.charCount,
    )

    private fun indexRowJson(r: IndexRow): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("updatedAt", r.updatedAt)
        put("expired", r.expired)
        put("revived", r.revived)
        put("contextMode", r.contextMode)
        put("summaryCovered", r.summaryCovered)
        put("imported", r.imported)
        put("pinned", r.pinned)
        put("costCny", r.costCny)
        put("roles", r.roles)
        put("systemSent", r.systemSent)
        put("chars", r.chars)
    }

    private fun indexJson(list: List<Session>): String {
        val arr = JSONArray()
        list.forEach { arr.put(indexRowJson(sessionIndexRow(it))) }
        return arr.toString()
    }

    private fun msgJson(
        id: String,
        role: String,
        text: String,
        thinking: String,
        model: String,
        time: Long,
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int,
        cacheCreateTokens: Int,
        costCny: Double,
        citations: List<Citation>,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        put("text", text)
        put("thinking", thinking)
        put("model", model)
        put("time", time)
        put("inputTokens", inputTokens)
        put("outputTokens", outputTokens)
        put("cacheReadTokens", cacheReadTokens)
        put("cacheCreateTokens", cacheCreateTokens)
        put("costCny", costCny)
        put(
            "citations",
            JSONArray().apply {
                citations.forEach { c ->
                    put(JSONObject().apply {
                        put("url", c.url)
                        put("title", c.title)
                    })
                }
            },
        )
    }

    private fun sessionJson(
        summary: String,
        covered: Int,
        blocks: List<SummaryBlock>,
        messages: JSONArray,
    ): String = JSONObject().apply {
        put("summary", summary)
        put("covered", covered)
        putSummaryBlocks(this, blocks)
        put("messages", messages)
    }.toString()

    private fun snapshotIndex(): List<IndexRow> = sessions.map(::sessionIndexRow)

    private fun serializeSnapshot(s: SessionSnapshot): String {
        val arr = JSONArray()
        for (m in s.messages) {
            arr.put(
                msgJson(
                    m.id, m.role, m.text, m.thinking, m.model, m.time,
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens, m.costCny, m.citations,
                ),
            )
        }
        return sessionJson(s.summary, s.covered, s.blocks, arr)
    }

    private fun serializeIndex(rows: List<IndexRow>): String {
        val arr = JSONArray()
        rows.forEach { arr.put(indexRowJson(it)) }
        return arr.toString()
    }

    private fun buildIndexJson(): String = indexJson(sessions)

    private fun serializeSession(s: Session): String {
        val arr = JSONArray()
        for (m in s.messages) {
            arr.put(
                msgJson(
                    m.id, m.role, m.text, m.thinking, m.model, m.time,
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens, m.costCny, m.citations,
                ),
            )
        }
        return sessionJson(s.summary, s.summaryCovered, s.summaryBlocks, arr)
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        // Same-directory rename replaces atomically on Linux; deleting the target
        // first opened a window where a crash left no file at all.
        if (tmp.renameTo(file)) return
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    private fun msgFile(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return File(msgDir, safe + "-" + Integer.toHexString(id.hashCode()) + ".json")
    }

    private fun loadIndexAsync() {
        io.execute {
            val list = ArrayList<Session>()
            runCatching {
                val arr = JSONArray(indexFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val s = sessionFromIndex(o) ?: continue
                    list.add(s)
                }
            }
            main.post { applyLoaded(list) }
        }
    }

    private fun sessionFromIndex(o: JSONObject): Session? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        return Session(
            id = id,
            title = o.optString("title"),
            updatedAt = o.optLong("updatedAt"),
            expired = o.optBoolean("expired"),
            revived = o.optBoolean("revived"),
            contextMode = o.optString("contextMode", "full"),
            summaryCovered = o.optInt("summaryCovered"),
            imported = o.optBoolean("imported"),
            pinned = o.optBoolean("pinned"),
            costCny = o.optDouble("costCny", 0.0),
            systemSent = o.optBoolean("systemSent", false),
        ).also {
            it.messages.markUnloaded(o.optString("roles"))
            it.charCount = o.optLong("chars", 0L)
        }
    }

    private fun applyLoaded(list: List<Session>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        Snapshot.withMutableSnapshot {
            sessions.clear()
            sessions.addAll(sorted)
        }
        invalidateCurrent()
        revision++
    }

    private val readyOrder = ArrayDeque<String>()
    private val maxReady = 6

    private fun touchReady(s: Session) {
        readyOrder.remove(s.id)
        readyOrder.addLast(s.id)
        while (readyOrder.size > maxReady) {
            val oldest = readyOrder.removeFirst()
            if (oldest == currentId || dirtyIds.contains(oldest)) {
                readyOrder.addLast(oldest)
                if (readyOrder.all { it == currentId || dirtyIds.contains(it) }) break
            } else {
                sessions.firstOrNull { it.id == oldest }?.messages?.unload()
            }
        }
    }

    private fun evictLoaded() {
        sessions.forEach { s ->
            if (s.id == currentId || dirtyIds.contains(s.id)) return@forEach
            if (s.messages.ready) s.messages.unload()
        }
        readyOrder.clear()
        currentId?.let { readyOrder.addLast(it) }
    }

    private val parsingIds = HashSet<String>()

    // Anything beyond a small conversation is parsed off the main thread. Parsing a
    // multi-hundred-KB JSON record during a tap was the main source of jank when
    // opening old sessions. parsingIds also prevents queuing the same parse twice.
    private fun ensureReadyForUi(s: Session) {
        if (s.messages.ready || parsingIds.contains(s.id)) return
        val f = msgFile(s.id)
        if (!f.exists()) {
            s.messages.markEmpty()
            return
        }
        if (f.length() <= 48_000L) {
            runCatching { parseSessionRecord(f, s) }.onFailure { s.messages.markEmpty() }
            return
        }
        parsingIds.add(s.id)
        io.execute {
            val ok = runCatching { parseSessionRecord(f, s) }.isSuccess
            main.post {
                parsingIds.remove(s.id)
                if (!ok) s.messages.markEmpty()
                if (s.id == currentId) revision++
            }
        }
    }

    private fun ensureReadyBlocking(s: Session) {
        if (s.messages.ready) return
        val f = msgFile(s.id)
        if (!f.exists()) {
            s.messages.markEmpty()
            return
        }
        runCatching { parseSessionRecord(f, s) }.onFailure { s.messages.markEmpty() }
    }

    private fun readCitations(o: JSONObject): List<Citation> {
        val arr = o.optJSONArray("citations") ?: return emptyList()
        val out = ArrayList<Citation>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val url = c.optString("url")
            if (url.isNotBlank()) out.add(Citation(url, c.optString("title")))
        }
        return out
    }

    private fun parseSessionRecord(f: File, s: Session) {
        val o = JSONObject(f.readText())
        s.summary = o.optString("summary")
        s.summaryCovered = o.optInt("covered", s.summaryCovered)
        s.summaryBlocks.clear()
        s.summaryBlocks.addAll(readSummaryBlocks(o))
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val list = ArrayList<Msg>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            list.add(Msg(
                id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
                role = m.optString("role"),
                text = m.optString("text"),
                thinking = m.optString("thinking"),
                model = m.optString("model"),
                time = m.optLong("time"),
                inputTokens = m.optInt("inputTokens", 0),
                outputTokens = m.optInt("outputTokens", 0),
                cacheReadTokens = m.optInt("cacheReadTokens", 0),
                cacheCreateTokens = m.optInt("cacheCreateTokens", 0),
            ).also {
                it.costCny = m.optDouble("costCny", 0.0)
                it.citations = readCitations(m)
            })
        }
        s.messages.markReady(list)
        s.charCount = list.sumOf { it.text.length.toLong() }
    }

    fun messageTextsForSearch(s: Session): List<String> {
        if (s.messages.ready) return s.messages.map { it.text + "\n" + it.thinking }
        val f = msgFile(s.id)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("messages") ?: JSONArray()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                out.add(m.optString("text") + "\n" + m.optString("thinking"))
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun migrateLegacyAsync() {
        if (!legacyFile.exists()) return
        io.execute {
            val list = ArrayList<Session>()
            runCatching {
                JsonReader(legacyFile.reader(Charsets.UTF_8)).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        val p = readLegacySession(r) ?: continue
                        val s = p.toSession()
                        runCatching { writeAtomic(msgFile(s.id), serializeLoadedSession(p)) }
                        s.messages.markReady(p.messages)
                        s.messages.unload()
                        list.add(s)
                    }
                    r.endArray()
                }
                writeAtomic(indexFile, buildIndexJsonFrom(list))
                runCatching { legacyFile.renameTo(File(storeDir, "legacy-sessions.json.bak")) }
            }.onFailure {
                main.post { notice = "旧版会话数据迁移失败：" + (it.message ?: "未知错误") }
            }
            main.post { applyLoaded(list) }
        }
    }

    private fun readLegacySession(r: JsonReader): ParsedSession? {
        var id = ""
        var title = ""
        var updatedAt = 0L
        var expired = false
        var revived = false
        var contextMode = "full"
        var summary = ""
        var summaryCovered = 0
        var imported = false
        var pinned = false
        var costCny = 0.0
        var messages: List<Msg> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = nextStringLoose(r)
                "title" -> title = nextStringLoose(r)
                "updatedAt" -> updatedAt = nextLongLoose(r)
                "expired" -> expired = nextBooleanLoose(r)
                "revived" -> revived = nextBooleanLoose(r)
                "contextMode" -> contextMode = nextStringLoose(r)
                "summary" -> summary = nextStringLoose(r)
                "summaryCovered" -> summaryCovered = nextIntLoose(r)
                "imported" -> imported = nextBooleanLoose(r)
                "pinned" -> pinned = nextBooleanLoose(r)
                "costCny" -> costCny = nextDoubleLoose(r)
                "messages" -> messages = readLegacyMessages(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id.isBlank()) return null
        return ParsedSession(
            id = id,
            title = title,
            updatedAt = updatedAt,
            expired = expired,
            revived = revived,
            contextMode = contextMode,
            summary = summary,
            summaryCovered = summaryCovered,
            imported = imported,
            pinned = pinned,
            costCny = costCny,
            messages = messages,
        )
    }

    private fun readLegacyMessages(r: JsonReader): List<Msg> {
        val out = ArrayList<Msg>()
        r.beginArray()
        while (r.hasNext()) {
            var id = ""
            var role = ""
            var text = ""
            var thinking = ""
            var model = ""
            var time = 0L
            var inputTokens = 0
            var outputTokens = 0
            var cacheRead = 0
            var cacheCreate = 0
            var costCny = 0.0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "id" -> id = nextStringLoose(r)
                    "role" -> role = nextStringLoose(r)
                    "text" -> text = nextStringLoose(r)
                    "thinking" -> thinking = nextStringLoose(r)
                    "model" -> model = nextStringLoose(r)
                    "time" -> time = nextLongLoose(r)
                    "inputTokens" -> inputTokens = nextIntLoose(r)
                    "outputTokens" -> outputTokens = nextIntLoose(r)
                    "cacheReadTokens" -> cacheRead = nextIntLoose(r)
                    "cacheCreateTokens" -> cacheCreate = nextIntLoose(r)
                    "costCny" -> costCny = nextDoubleLoose(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (text.isBlank() && thinking.isBlank()) continue
            out.add(
                Msg(
                    id = id.ifBlank { UUID.randomUUID().toString() },
                    role = role,
                    text = text,
                    thinking = thinking,
                    model = model,
                    time = time,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    cacheReadTokens = cacheRead,
                    cacheCreateTokens = cacheCreate,
                ).also { it.costCny = costCny },
            )
        }
        r.endArray()
        return out
    }

    private fun nextStringLoose(r: JsonReader): String {
        return when (r.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean().toString()
            JsonToken.NULL -> { r.nextNull(); "" }
            else -> { r.skipValue(); "" }
        }
    }

    private fun nextLongLoose(r: JsonReader): Long {
        return when (r.peek()) {
            JsonToken.NUMBER -> r.nextLong()
            JsonToken.STRING -> r.nextString().toLongOrNull() ?: 0L
            JsonToken.NULL -> { r.nextNull(); 0L }
            else -> { r.skipValue(); 0L }
        }
    }

    private fun nextIntLoose(r: JsonReader): Int {
        return when (r.peek()) {
            JsonToken.NUMBER -> r.nextLong().toInt()
            JsonToken.STRING -> r.nextString().toIntOrNull() ?: 0
            JsonToken.NULL -> { r.nextNull(); 0 }
            else -> { r.skipValue(); 0 }
        }
    }

    private fun nextBooleanLoose(r: JsonReader): Boolean {
        return when (r.peek()) {
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.STRING -> r.nextString().toBooleanStrictOrNull() ?: false
            JsonToken.NUMBER -> r.nextLong() != 0L
            JsonToken.NULL -> { r.nextNull(); false }
            else -> { r.skipValue(); false }
        }
    }

    private fun nextDoubleLoose(r: JsonReader): Double {
        return when (r.peek()) {
            JsonToken.NUMBER -> r.nextDouble()
            JsonToken.STRING -> r.nextString().toDoubleOrNull() ?: 0.0
            JsonToken.NULL -> { r.nextNull(); 0.0 }
            else -> { r.skipValue(); 0.0 }
        }
    }

    private fun serializeLoadedSession(p: ParsedSession): String {
        val o = JSONObject()
        o.put("summary", p.summary)
        o.put("covered", p.summaryCovered)
        val arr = JSONArray()
        for (m in p.messages) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role)
                put("text", m.text)
                put("thinking", m.thinking)
                put("model", m.model)
                put("time", m.time)
                put("inputTokens", m.inputTokens)
                put("outputTokens", m.outputTokens)
                put("cacheReadTokens", m.cacheReadTokens)
                put("cacheCreateTokens", m.cacheCreateTokens)
                put("costCny", m.costCny)
            })
        }
        o.put("messages", arr)
        return o.toString()
    }

    private fun buildIndexJsonFrom(list: List<Session>): String = indexJson(list)

    private fun contentResolverStream(uri: Uri): java.io.InputStream? {
        return runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
    }

    fun importZip(uri: Uri) {
        if (importing) return
        importing = true
        importStatus = "正在读取 ZIP…"
        io.execute {
            try {
                val parsed = ArrayList<Session>()
                contentResolverStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            if (!entry.isDirectory &&
                                name != null &&
                                name.startsWith("chat_sessions/") &&
                                name.endsWith(".json")
                            ) {
                                val bytes = zip.readBytes()
                                val s = parseSession(bytes)
                                if (s != null) {
                                    runCatching { writeAtomic(msgFile(s.id), serializeSession(s)) }
                                    s.messages.unload()
                                    parsed.add(s)
                                    if (parsed.size % 25 == 0) {
                                        val n = parsed.size
                                        main.post { importStatus = "已解析 " + n + " 个会话…" }
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                main.post { finalizeImport(parsed) }
            } catch (e: Exception) {
                main.post {
                    importing = false
                    importStatus = ""
                    notice = "导入失败：" + (e.message ?: "ZIP 无法读取")
                }
            }
        }
    }

    private fun finalizeImport(parsed: List<Session>) {
        val existingIds = sessions.map { it.id }.toMutableSet()
        val fresh = parsed.filter { existingIds.add(it.id) }
        val merged = (sessions + fresh).sortedByDescending { it.updatedAt }
        Snapshot.withMutableSnapshot {
            sessions.clear()
            sessions.addAll(merged)
        }
        invalidateCurrent()
        importing = false
        importStatus = ""
        revision++
        notice = "已导入 " + fresh.size + " 个会话；超过 7 天未活跃的会话标为「已失效」，可逐条复活。"
        val indexJson = buildIndexJson()
        io.execute { writeAtomic(indexFile, indexJson) }
    }

    private fun parseSession(bytes: ByteArray): Session? {
        return runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val id = o.optString("id")
            if (id.isBlank()) return@runCatching null
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val byId = HashMap<Long, JSONObject>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                byId[m.optLong("id")] = m
            }
            val chain = ArrayList<JSONObject>()
            var cur = byId[o.optLong("current_message_id")]
            val seen = HashSet<Long>()
            while (cur != null && seen.add(cur.optLong("id"))) {
                chain.add(cur)
                val parent = cur.optLong("parent_id", -1L)
                cur = if (parent > 0L) byId[parent] else null
            }
            chain.reverse()
            val msgs = ArrayList<Msg>()
            for (m in chain) {
                val role = m.optString("role")
                val body = StringBuilder()
                val think = StringBuilder()
                val fileNames = ArrayList<String>()
                val frags = m.optJSONArray("fragments") ?: JSONArray()
                for (j in 0 until frags.length()) {
                    val f = frags.optJSONObject(j) ?: continue
                    when (f.optString("type")) {
                        "REQUEST" -> if (role == "USER") body.append(f.optString("content"))
                        "RESPONSE" -> if (role == "ASSISTANT") body.append(f.optString("content"))
                        "THINK" -> think.append(f.optString("content"))
                        "FILE" -> {
                            val files = f.optJSONArray("files") ?: JSONArray()
                            for (k in 0 until files.length()) {
                                val fn = files.optJSONObject(k)?.optString("file_name").orEmpty()
                                if (fn.isNotBlank()) fileNames.add(fn)
                            }
                        }
                    }
                }
                if (body.isBlank() && fileNames.isNotEmpty()) {
                    body.append("[附件：").append(fileNames.joinToString("、")).append("]")
                }
                if (body.isBlank() && think.isBlank()) continue
                msgs.add(
                    Msg(
                        role = if (role == "USER") "user" else "assistant",
                        text = body.toString(),
                        thinking = think.toString(),
                        time = parseIso(m.optString("inserted_at")),
                    ),
                )
            }
            if (msgs.isEmpty()) return@runCatching null
            val updated = parseIso(o.optString("updated_at"))
            val title = o.optString("title").ifBlank { "未命名会话" }
            val s = Session(
                id = id,
                title = title,
                updatedAt = updated,
                expired = System.currentTimeMillis() - updated > 604_800_000L,
                revived = false,
                contextMode = "full",
                summary = "",
                summaryCovered = 0,
                imported = true,
                pinned = o.optBoolean("pinned"),
                costCny = 0.0,
            )
            s.messages.markReady(msgs)
            s.charCount = msgs.sumOf { it.text.length.toLong() }
            s
        }.getOrNull()
    }

    private fun parseIso(s: String): Long {
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    }

    private fun bumpRevisionThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRevisionAt >= 80L) {
            lastRevisionAt = now
            revision++
        }
    }

    private class ParsedSession(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val expired: Boolean,
        val revived: Boolean,
        val contextMode: String,
        val summary: String,
        val summaryCovered: Int,
        val imported: Boolean,
        val pinned: Boolean,
        val costCny: Double,
        val messages: List<Msg>,
    ) {
        fun toSession(): Session = Session(
            id = id,
            title = title.ifBlank { "未命名会话" },
            updatedAt = updatedAt,
            expired = expired,
            revived = revived,
            contextMode = contextMode,
            summary = summary,
            summaryCovered = summaryCovered,
            imported = imported,
            pinned = pinned,
            costCny = costCny,
        ).also { it.charCount = messages.sumOf { m -> m.text.length.toLong() } }
    }
}
