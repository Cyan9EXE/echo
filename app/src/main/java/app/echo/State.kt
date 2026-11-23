package app.echo

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class AppState(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("continue-native", Context.MODE_PRIVATE)
    /** 性能模式：优先流畅度，而不是省电与内存占用。 */
    var performanceMode by mutableStateOf(prefs.getBoolean("performanceMode", false))
        private set
    /** 是否显示消息底部的费用/token 与朗读估价。关闭后对话更干净。 */
    var showBillingHints by mutableStateOf(prefs.getBoolean("showBillingHints", true))
        private set
    private val codeFenceRe = Regex("\u0060\u0060\u0060[\\s\\S]*?\u0060\u0060\u0060")
    private val whitespaceRe = Regex("\\s+")
    private val sentenceEndRe = Regex("(?<=[。！？!?])")
    private val keySentenceRe = Regex("(结论|所以|因此|建议|决定|方案|注意|关键|不能|必须|原因|总结|要点)")
    private val constraintRe = Regex("(要求|必须|不能|不要|只能|需要|限制|约束|记住|请先|先别|严禁|优先)")

    private val legacyFile = File(appContext.filesDir, "sessions.json")
    private val storeDir = File(appContext.filesDir, "store")
    private val msgDir = File(storeDir, "msg")
    private val indexFile = File(storeDir, "index.json")
    private var ioWorker: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "echo-store").apply {
            priority = if (performanceMode) Thread.NORM_PRIORITY else Thread.MIN_PRIORITY
            ioWorker = this
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }
    private val dirtyIds = LinkedHashSet<String>()
    private var lastRevisionAt = 0L
    var contextTokens by mutableStateOf(0)
        private set
    // 流式回复期间缓存「历史字符数」，让 500ms 上下文轮询走 O(1) 增量而不是整段求和。
    private var contextBaseChars = -1
    private var contextBaseId: String? = null

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
    /** Output ceiling per request. Defaults to 32K; users can lower it to save tokens. */
    var maxTokens by mutableStateOf(
        prefs.getInt("maxTokens", ApiClient.DEFAULT_MAX_TOKENS)
            .coerceIn(ApiClient.MIN_MAX_TOKENS, ApiClient.MAX_MAX_TOKENS),
    )
        private set
    var systemPrompt by mutableStateOf(prefs.getString("systemPrompt", "").orEmpty())
        private set
    // 0..C.skins.lastIndex = preset skin, C.skins.size = custom accent colour.
    var skinIndex by mutableStateOf(
        prefs.getInt("skinIndex", if (prefs.getBoolean("blueAccent", true)) 1 else 0)
            .coerceIn(0, C.skins.size),
    )
        private set
    var customAccent by mutableStateOf(prefs.getInt("customAccent", 0xFF7FA8FF.toInt()))
        private set
    /** 日间第五种外观：底色由用户自选，存成 ARGB（只换色度，明度仍跟随纸白）。 */
    var customThemeArgb by mutableStateOf(prefs.getInt("customTheme", 0xFFF3E9D8.toInt()))
        private set
    var customThemeOn by mutableStateOf(prefs.getBoolean("customThemeOn", false))
        private set
    // 0 = off, 1 = warm paper, 2 = soft green. Only rendered in day mode.
    var eyeMode by mutableStateOf(prefs.getInt("eyeMode", 0).coerceIn(0, 2))
        private set
    var showTheme by mutableStateOf(false)
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

    // 输入框草稿：跨会话切换与进程重建都能恢复（离开输入区时落盘一次，避免逐键写盘）。
    var draft by mutableStateOf(prefs.getString("draft", "").orEmpty())

    fun persistDraft() {
        prefs.edit().putString("draft", draft).apply()
    }
    var asrSkipConfirm by mutableStateOf(prefs.getBoolean("asrSkipConfirm", false))
        private set
    var notice by mutableStateOf<String?>(null)
    var typing by mutableStateOf(false)
    var streaming by mutableStateOf(false)
    var importing by mutableStateOf(false)
        private set
    var importStatus by mutableStateOf("")
        private set
    var importProgress by mutableStateOf(0f)
        private set
    private var importCancelled = false
    var revision by mutableStateOf(0)
    var currentId by mutableStateOf<String?>(prefs.getString("currentId", null))
        private set

    private fun setCurrentSession(id: String?) {
        currentId = id
        prefs.edit().putString("currentId", id).apply()
    }

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
        C.initEye(prefs.getInt("eyeMode", 0))
        C.initCustomTheme(customThemeArgb, customThemeOn)
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
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushPending()
                // 性能模式把会话留在内存里（后台回来仍然热），正常模式尽早让出内存。
                if (performanceMode) return
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                ) {
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

    fun updateMaxTokens(value: Int) {
        maxTokens = value.coerceIn(ApiClient.MIN_MAX_TOKENS, ApiClient.MAX_MAX_TOKENS)
        prefs.edit().putInt("maxTokens", maxTokens).apply()
    }

    fun updatePerformanceMode(value: Boolean) {
        performanceMode = value
        ioWorker?.priority = if (value) Thread.NORM_PRIORITY else Thread.MIN_PRIORITY
        prefs.edit().putBoolean("performanceMode", value).apply()
    }

    fun updateShowBillingHints(value: Boolean) {
        showBillingHints = value
        prefs.edit().putBoolean("showBillingHints", value).apply()
    }

    fun updateSystemPrompt(value: String) {
        systemPrompt = value.take(2000)
        prefs.edit().putString("systemPrompt", systemPrompt).apply()
    }

    /** 自定义强调色已启用：skinIndex 指向 5 个预设之后的第六格。 */
    val customMode: Boolean get() = skinIndex > C.skins.lastIndex

    /** 自定义底色已启用且处于日间。 */
    val customAppearance: Boolean get() = customThemeOn && C.isDay

    fun updateSkin(index: Int) {
        skinIndex = index.coerceIn(0, C.skins.size)
        prefs.edit().putInt("skinIndex", skinIndex).apply()
    }

    /** 只切外观，强调色保持不变。 */
    fun applyAppearance(look: C.Look) {
        if (look == C.Look.INK) {
            if (C.isDay) C.applyDay(false)
        } else if (!C.isDay) {
            C.applyDay(true)
        }
        val eye = when (look) {
            C.Look.WARM -> 1
            C.Look.GREEN -> 2
            else -> 0
        }
        if (eyeMode != eye) {
            eyeMode = eye
            C.applyEye(eye)
        }
        val custom = look == C.Look.CUSTOM
        if (customThemeOn != custom) {
            customThemeOn = custom
            C.applyCustomTheme(if (custom) customThemeArgb else null)
        }
        prefs.edit()
            .putBoolean("dayMode", C.isDay)
            .putInt("eyeMode", eyeMode)
            .putBoolean("customThemeOn", customThemeOn)
            .apply()
    }

    /** 只切强调色，外观保持不变。 */
    fun applyAccent(index: Int) = updateSkin(index)

    /** 一次落定「外观 × 强调色」组合。 */
    fun applyPreset(preset: Preset) {
        applyAppearance(preset.appearance)
        updateSkin(preset.accent)
    }

    /** 日间自定义底色：传入 HSL 选出的 ARGB，立即生效并持久化。 */
    fun applyCustomTheme(argb: Int) {
        customThemeArgb = argb
        customThemeOn = true
        if (!C.isDay) C.applyDay(true)
        if (eyeMode != 0) {
            eyeMode = 0
            C.applyEye(0)
        }
        C.applyCustomTheme(argb)
        prefs.edit()
            .putInt("customTheme", argb)
            .putBoolean("customThemeOn", true)
            .putBoolean("dayMode", true)
            .putInt("eyeMode", 0)
            .apply()
    }

    /** 关掉自定义底色，回到预设外观。 */
    fun disableCustomTheme() {
        customThemeOn = false
        C.applyCustomTheme(null)
        prefs.edit().putBoolean("customThemeOn", false).apply()
    }

    /** Enters the custom-colour slot and stores the picked colour. */
    fun selectCustomAccent(argb: Int) {
        customAccent = argb
        skinIndex = C.skins.size
        prefs.edit().putInt("customAccent", argb).putInt("skinIndex", skinIndex).apply()
    }

    fun updateEyeMode(mode: Int) {
        eyeMode = mode.coerceIn(0, 2)
        prefs.edit().putInt("eyeMode", eyeMode).apply()
        C.applyEye(eyeMode)
    }

    fun openTheme() {
        showDrawer = false
        showTheme = true
    }

    fun closeTheme() { showTheme = false }

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
        setCurrentSession(s.id)
        markDirty(s.id)
        refreshContextTokens()
        save()
    }

    fun open(session: Session) {
        typing = false
        showDrawer = false
        ensureReadyForUi(session)
        setCurrentSession(session.id)
        touchReady(session)
        refreshContextTokens()
        revision++
    }

    fun backHome() {
        typing = false
        setCurrentSession(null)
        refreshContextTokens()
    }

    fun delete(session: Session) {
        sessions.remove(session)
        invalidateCurrent()
        if (currentId == session.id) setCurrentSession(null)
        dirtyIds.remove(session.id)
        val f = msgFile(session.id)
        io.execute { runCatching { f.delete() } }
        refreshContextTokens()
        // The row is gone from sessions but its index record must be rewritten even
        // though nothing is dirty any more; also bump revision so list-level caches
        // (drawer counts, derived rows) recompute.
        revision++
        save(forceIndex = true)
    }

    /** @return true 表示消息已进入发送流程（调用方可以清空输入框）；false 表示被前置条件拦下。 */
    fun send(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        if (streaming) {
            notice = "正在生成；先点暂停，再发送新的消息。"
            return false
        }
        typing = false
        var session = current
        if (session != null) ensureReadyBlocking(session)
        if (session != null && session.expired && !session.revived) {
            notice = "这段对话已失效：先点「复活并继续」，恢复完整上下文后再发送。"
            return false
        }
        if (apiKey.isBlank()) {
            notice = "先填入 API Key，再开始对话。"
            showSettings = true
            return false
        }
        if (session == null) {
            session = Session(title = body.take(24))
            session.messages.markEmpty()
            sessions.add(0, session)
            setCurrentSession(session.id)
        }
        if (session.messages.isEmpty()) session.title = body.take(24)
        session.messages.add(Msg(role = "user", text = body))
        val effectiveModel = if (webSearch) "deepseek-flash" else model
        val reply = Msg(role = "assistant", text = "", model = effectiveModel)
        session.messages.add(reply)
        session.updatedAt = System.currentTimeMillis()
        streaming = true
        refreshContextTokens()
        notice = null
        markDirty(session.id)
        scheduleSave()

        val includeSystem = systemPrompt.isNotBlank() && !session.systemSent
        val history = buildHistory(session, thinking)
        val sessionId = session.id
        streamSessionId = sessionId
        val mySeq = ++streamSeq
        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        val thinkStarted = SystemClock.uptimeMillis()
        var lastPublish = 0L
        fun publish(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastPublish < (if (performanceMode) 70 else 120)) return
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
            maxTokens = maxTokens,
            flushMs = if (performanceMode) 45 else 60,
            onText = { chunk ->
                if (mySeq == streamSeq) {
                    textBuf.append(chunk)
                    if (thinkingBuf.isNotEmpty() && reply.thinkingMs == 0L) {
                        reply.thinkingMs = SystemClock.uptimeMillis() - thinkStarted
                    }
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
            onSignature = { sig ->
                if (mySeq == streamSeq) reply.signature = sig
            },
            onStopReason = { reason ->
                if (mySeq == streamSeq) reply.stopReason = reason
            },
            onUsage = { u ->
                if (mySeq == streamSeq) {
                    val input = u.inputTokens + u.cacheReadTokens + u.cacheCreateTokens
                    if (u.full) {
                        // message_delta 若带全量 usage，以它为准（覆盖 message_start 的预填值）。
                        if (input > 0) reply.inputTokens = input
                        if (u.outputTokens > 0) reply.outputTokens = u.outputTokens
                        if (u.cacheReadTokens > 0) reply.cacheReadTokens = u.cacheReadTokens
                        if (u.cacheCreateTokens > 0) reply.cacheCreateTokens = u.cacheCreateTokens
                    } else {
                        if (input > 0) reply.inputTokens = maxOf(reply.inputTokens, input)
                        if (u.outputTokens > 0) reply.outputTokens = maxOf(reply.outputTokens, u.outputTokens)
                        if (u.cacheReadTokens > 0) reply.cacheReadTokens = u.cacheReadTokens
                        if (u.cacheCreateTokens > 0) reply.cacheCreateTokens = u.cacheCreateTokens
                    }
                }
            },
            onError = { message ->
                if (mySeq == streamSeq) {
                    textBuf.append(if (textBuf.isEmpty()) message else "\n\n[中断] " + message)
                    reply.failed = true
                    if (thinkingBuf.isNotEmpty() && reply.thinkingMs == 0L) {
                        reply.thinkingMs = SystemClock.uptimeMillis() - thinkStarted
                    }
                    publish(true)
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    refreshContextTokens()
                    markDirty(sessionId)
                    revision++
                    save()
                }
            },
            onDone = { cancelled ->
                if (mySeq == streamSeq) {
                    if (thinkingBuf.isNotEmpty() && reply.thinkingMs == 0L) {
                        reply.thinkingMs = SystemClock.uptimeMillis() - thinkStarted
                    }
                    publish(true)
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    refreshContextTokens()
                    if (includeSystem) session.systemSent = true
                    if (cancelled) {
                        notice = "已暂停生成；已保留当前内容。"
                    } else {
                        if (reply.text.isBlank()) reply.text = "（没有收到内容）"
                        if (reply.stopReason == "max_tokens") {
                            notice = "已达到最大输出长度（可在设置里调高上限）。"
                        }
                        finishUsage(session, reply)
                    }
                    markDirty(sessionId)
                    revision++
                    save()
                }
            },
        )
        if (mySeq == streamSeq) activeStream = handle else handle.cancel()
        return true
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
        refreshContextTokens()
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
        refreshContextTokens()
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

    private fun buildHistory(session: Session, thinking: Boolean): List<ApiMsg> {
        val all = historyMessages(session)
        fun apiMsg(m: Msg): ApiMsg =
            if (thinking && m.role == "assistant" && m.signature.isNotBlank()) {
                // 回传 thinking 签名，保证多轮推理链可被官方校验。
                ApiMsg(m.role, m.text, m.thinking, m.signature)
            } else {
                ApiMsg(m.role, m.text)
            }
        if (session.contextMode != "summary" || session.summary.isBlank()) {
            return all.map(::apiMsg)
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
        return tail.map(::apiMsg)
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

    private fun contextChars(s: Session): Int = if (s.contextMode == "summary" && s.summary.isNotBlank()) {
        s.summary.length + historyMessages(s).drop(s.summaryCovered).sumOf { it.text.length }
    } else if (s.messages.ready) {
        s.messages.sumOf { it.text.length }
    } else {
        s.charCount.toInt()
    }

    fun refreshContextTokens() {
        val s = current
        if (s == null) {
            contextTokens = 0
            contextBaseChars = -1
            contextBaseId = null
            return
        }
        val chars = contextChars(s)
        contextTokens = (chars / 2.5).toInt() + 12
        if (streaming) {
            contextBaseChars = chars
            contextBaseId = s.id
        } else {
            contextBaseChars = -1
            contextBaseId = null
        }
    }

    /**
     * 流式期间上下文条每 500ms 刷新一次。历史部分在发送时已经算过，这里只叠加
     * 当前回复的长度，避免每半秒重新遍历整段会话（几百条消息时也会产生垃圾）。
     */
    fun refreshLiveContextTokens() {
        val s = current ?: return
        val base = if (s.id == contextBaseId) contextBaseChars else -1
        if (base < 0) {
            refreshContextTokens()
            return
        }
        val live = s.messages.lastOrNull()?.text?.length ?: 0
        contextTokens = ((base + live) / 2.5).toInt() + 12
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
        main.postDelayed(saveRunnable, if (performanceMode) 260L else 700L)
    }

    private fun flushPending() {
        main.removeCallbacks(saveRunnable)
        save()
    }

    fun save(forceIndex: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { save(forceIndex) }
            return
        }
        if (dirtyIds.isEmpty() && !forceIndex) return
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
                    thinkingMs = m.thinkingMs,
                    signature = m.signature,
                    stopReason = m.stopReason,
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
        thinkingMs: Long,
        signature: String,
        stopReason: String,
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
        put("thinkingMs", thinkingMs)
        if (signature.isNotBlank()) put("signature", signature)
        if (stopReason.isNotBlank()) put("stopReason", stopReason)
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
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens,
                    m.costCny, m.citations, m.thinkingMs, m.signature, m.stopReason,
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
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens,
                    m.costCny, m.citations, m.thinkingMs, m.signature, m.stopReason,
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
        // 冷启动必须把上次的会话重新装回 current：只恢复 id 不加载消息，聊天页会一直
        // 停在 ready=false 的 stub 列表上（P1-1）。
        val restored = currentId?.let { id -> sessions.firstOrNull { it.id == id } }
        if (restored == null) {
            setCurrentSession(null)
        } else {
            touchReady(restored)
            ensureReadyForUi(restored)
            refreshContextTokens()
        }
        revision++
    }

    private val readyOrder = ArrayDeque<String>()
    private val maxReady: Int get() = if (performanceMode) 12 else 6

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
    fun ensureReadyForUi(s: Session) {
        if (s.messages.ready || parsingIds.contains(s.id)) return
        val f = msgFile(s.id)
        if (!f.exists()) {
            s.messages.markEmpty()
            if (s.id == currentId) refreshContextTokens()
            return
        }
        if (f.length() <= 48_000L) {
            runCatching { parseSessionRecord(f, s) }.onFailure { s.messages.markEmpty() }
            if (s.id == currentId) {
                refreshContextTokens()
                revision++
            }
            return
        }
        parsingIds.add(s.id)
        io.execute {
            val ok = runCatching { parseSessionRecord(f, s) }.isSuccess
            main.post {
                parsingIds.remove(s.id)
                if (!ok) s.messages.markEmpty()
                if (s.id == currentId) {
                    refreshContextTokens()
                    revision++
                }
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
                thinkingMs = m.optLong("thinkingMs", 0L),
                signature = m.optString("signature"),
            ).also {
                it.costCny = m.optDouble("costCny", 0.0)
                it.citations = readCitations(m)
                it.stopReason = m.optString("stopReason")
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
            var thinkingMs = 0L
            var signature = ""
            var stopReason = ""
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
                    "thinkingMs" -> thinkingMs = nextLongLoose(r)
                    "signature" -> signature = nextStringLoose(r)
                    "stopReason" -> stopReason = nextStringLoose(r)
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
                    thinkingMs = thinkingMs,
                    signature = signature,
                ).also {
                    it.costCny = costCny
                    it.stopReason = stopReason
                },
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
                put("thinkingMs", m.thinkingMs)
                if (m.signature.isNotBlank()) put("signature", m.signature)
                if (m.stopReason.isNotBlank()) put("stopReason", m.stopReason)
            })
        }
        o.put("messages", arr)
        return o.toString()
    }

    private fun buildIndexJsonFrom(list: List<Session>): String = indexJson(list)

    private fun contentResolverStream(uri: Uri): java.io.InputStream? {
        return runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private val importMaxSessionBytes = 24L * 1024 * 1024
    private val importMaxSessions = 20_000
    private val importMaxEntries = 60_000
    private val importMaxZipBytes = 2L * 1024 * 1024 * 1024

    fun cancelImport() {
        if (!importing) return
        importCancelled = true
        importStatus = "正在取消…"
    }

    fun importZip(uri: Uri) {
        if (importing) return
        importing = true
        importCancelled = false
        importProgress = 0f
        importStatus = "正在读取 ZIP…"
        io.execute {
            val parsed = ArrayList<Session>()
            val failed = ArrayList<String>()
            val written = ArrayList<String>()
            var duplicates = 0
            var sessionEntries = 0
            try {
                val totalBytes = contentSize(uri)
                if (totalBytes > importMaxZipBytes) {
                    throw java.io.IOException("ZIP 超过 2GB 上限，无法导入。")
                }
                val raw = contentResolverStream(uri)
                    ?: throw java.io.IOException("无法打开所选文件，请重新选择。")
                val counting = CountingInputStream(raw)
                java.io.BufferedInputStream(counting, 64 * 1024).use { input ->
                    ZipInputStream(input).use { zip ->
                        val knownIds = sessions.mapTo(HashSet()) { it.id }
                        var entries = 0
                        while (true) {
                            if (importCancelled) break
                            val entry = zip.nextEntry ?: break
                            entries++
                            if (entries > importMaxEntries) {
                                throw java.io.IOException("ZIP 内文件过多，已停止导入。")
                            }
                            val name = entry.name
                            if (entry.isDirectory || name == null) {
                                zip.closeEntry()
                                continue
                            }
                            if (!name.startsWith("chat_sessions/") || !name.endsWith(".json")) {
                                zip.closeEntry()
                                continue
                            }
                            sessionEntries++
                            if (sessionEntries > importMaxSessions) {
                                throw java.io.IOException("会话数量超过上限（" + importMaxSessions + " 个）。")
                            }
                            val shortName = name.substringAfterLast('/')
                            val s = try {
                                parseSession(readLimited(zip, importMaxSessionBytes))
                            } catch (e: Exception) {
                                failed.add(shortName)
                                zip.closeEntry()
                                continue
                            }
                            zip.closeEntry()
                            if (s == null) {
                                failed.add(shortName)
                                continue
                            }
                            if (!knownIds.add(s.id)) {
                                duplicates++
                                s.messages.unload()
                                continue
                            }
                            val ok = runCatching { writeAtomic(msgFile(s.id), serializeSession(s)) }.isSuccess
                            if (ok) {
                                written.add(s.id)
                                s.messages.unload()
                                parsed.add(s)
                            } else {
                                knownIds.remove(s.id)
                                failed.add(shortName)
                            }
                            if (sessionEntries % 25 == 0) {
                                val p = if (totalBytes > 0L) {
                                    (counting.bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                                } else 0.0
                                val n = parsed.size
                                val ui = (0.08 + p * 0.84).toFloat()
                                main.post {
                                    importProgress = ui
                                    importStatus = "已解析 " + n + " 个会话…"
                                }
                            }
                        }
                    }
                }
                if (importCancelled) {
                    for (id in written) runCatching { msgFile(id).delete() }
                    main.post {
                        importing = false
                        importProgress = 0f
                        importStatus = ""
                        notice = "已取消导入，未写入任何会话。"
                    }
                    return@execute
                }
                if (sessionEntries == 0) {
                    main.post {
                        importing = false
                        importProgress = 0f
                        importStatus = ""
                        notice = "这个 ZIP 里没有 chat_sessions 文件夹，可能不是 DeepSeek 官方导出的数据。"
                    }
                    return@execute
                }
                main.post {
                    importProgress = 0.95f
                    importStatus = "正在整理会话…"
                }
                main.post { finalizeImport(parsed, failed, duplicates) }
            } catch (e: Exception) {
                for (id in written) runCatching { msgFile(id).delete() }
                main.post {
                    importing = false
                    importProgress = 0f
                    importStatus = ""
                    notice = "导入失败：" + importErrorText(e)
                }
            }
        }
    }

    private fun finalizeImport(parsed: List<Session>, failed: List<String>, duplicates: Int) {
        val existingIds = sessions.map { it.id }.toMutableSet()
        val fresh = parsed.filter { existingIds.add(it.id) }
        val merged = (sessions + fresh).sortedByDescending { it.updatedAt }
        Snapshot.withMutableSnapshot {
            sessions.clear()
            sessions.addAll(merged)
        }
        invalidateCurrent()
        importing = false
        importProgress = 0f
        importStatus = ""
        refreshContextTokens()
        revision++
        notice = buildString {
            if (fresh.isEmpty()) append("没有新增会话") else append("已导入 ").append(fresh.size).append(" 个会话")
            if (duplicates > 0) append("，跳过 ").append(duplicates).append(" 个已存在的会话")
            if (failed.isNotEmpty()) {
                append("，").append(failed.size).append(" 个文件无法解析")
                if (failed.size <= 3) append("（").append(failed.joinToString("、")).append("）")
            }
            append("；超过 7 天未活跃的会话标为「已失效」，可逐条复活。")
        }
        val indexJson = buildIndexJson()
        io.execute { writeAtomic(indexFile, indexJson) }
    }

    private fun contentSize(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use 0L
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            val want = minOf(buf.size.toLong(), limit - total).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= limit && input.read() >= 0) throw java.io.IOException("会话文件超过单文件大小上限")
        return out.toByteArray()
    }

    private fun importErrorText(e: Exception): String = when (e) {
        is java.util.zip.ZipException -> "ZIP 无法读取或已损坏，请确认选择的是官方导出的 .zip 文件。"
        is java.io.EOFException -> "ZIP 内容不完整，可能下载时被截断，请重新导出后再试。"
        is SecurityException -> "没有读取该文件的权限，请重新选择文件。"
        else -> e.message ?: "ZIP 无法读取"
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


private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
    var bytes: Long = 0L
        private set

    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) bytes++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) bytes += n
        return n
    }

    override fun close() = inner.close()
}
