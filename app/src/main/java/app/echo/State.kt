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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** 自定义强调色的归一写入规则版本；改动可读性算法时递增以触发一次显式迁移。 */
private const val CUSTOM_ACCENT_SCHEMA = 1

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
    /** 已删会话的墓碑：删除中会话的索引行保留到 commitDelete，墓碑落盘后才删消息文件；启动对账跳过墓碑并补删文件（R9-P2-2 / R13-P1-1）。 */
    private val tombstoneFile = File(storeDir, "tombstones.json")
    private val tombstoneStore = TombstoneStore(tombstoneFile)
    private val tombstones = HashSet<String>()
    /** 墓碑文件损坏：保留原文件、拒绝继续删除，避免空集覆盖旧墓碑（R13-P2-4）。 */
    @Volatile private var tombstonesCorrupt = false
    /** 删除事务进行中的会话：入口置位，墓碑/文件/索引全部落盘后才清（R12-P1-1）。 */
    private val deletingIds = HashSet<String>()
    @Volatile private var deletingSnapshot: Set<String> = emptySet()
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
    /**
     * 旧 5 色存档（skinSchema 0）里 skinIndex==5 表示「自定义色」槽位；
     * V3 新增靛蓝后预设占 0..5，自定义槽位后移到 6，因此这里按 schema 迁移一次。
     */
    private fun initialSkinIndex(): Int {
        val schema = prefs.getInt("skinSchema", 0)
        val stored = prefs.getInt("skinIndex", 1)
        val (valid, nextSchema) = migrateSkinIndex(schema, stored, C.skins.size)
        if (nextSchema != schema || valid != stored) {
            prefs.edit().putInt("skinIndex", valid).putInt("skinSchema", nextSchema).apply()
        }
        return valid
    }

    // 0..C.skins.lastIndex = preset skin, C.skins.size = custom accent colour.
    // 槽位顺序不变（0 沙金 … 4 灰紫，5 靛蓝）；缺省/非法值回退雾蓝（1）。
    var skinIndex by mutableStateOf(initialSkinIndex())
        private set
    /**
     * 自定义强调色每次启动按当前可读性算法归一，并把归一结果写回 prefs，
     * 避免同一份原始输入被反复重解释；内存渲染值与落盘值保持一致。
     */
    private fun initialCustomAccent(): Int {
        val stored = prefs.getInt("customAccent", 0xFF759ECC.toInt())
        val schema = prefs.getInt("customAccentSchema", 0)
        val readable = C.readableAccent(Color(stored)).toArgb()
        // 显式迁移：schema 0 表示旧数据，统一按当前可读性算法归一一次并打标；
        // 之后只在用户改色导致重新归一时才再写入，避免每次启动反复解释同一份输入。
        if (schema < CUSTOM_ACCENT_SCHEMA || readable != stored) {
            prefs.edit()
                .putInt("customAccent", readable)
                .putInt("customAccentSchema", CUSTOM_ACCENT_SCHEMA)
                .apply()
        }
        return readable
    }

    var customAccent by mutableStateOf(initialCustomAccent())
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
    /** 每次发送成功后自增：UI 用它触发一次“发送后回底”，不依赖列表尺寸变化（P2-1）。 */
    var sendTick by mutableStateOf(0)
        private set
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
    private var lastStreamSaveAt = 0L

    /** Session id currently streaming; UI uses it to pick the live message (P2-2). */
    val liveSessionId: String? get() = if (streaming) streamSessionId else null

    fun isStreaming(sessionId: String?): Boolean =
        streaming && sessionId != null && sessionId == streamSessionId

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

    /** 主线程维护的墓碑快照：IO 线程只读这份不可变副本，避免 HashSet 并发读写（R10-P2-3）。 */
    @Volatile private var tombstonesSnapshot: Set<String> = emptySet()
    /** 首次启动扫描/重建索引中：欢迎页显示恢复态（R10-P2-2）。 */
    var indexLoading by mutableStateOf(false)
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
        val initialTombstones = readTombstonesOrNull()
        if (initialTombstones == null) {
            // 损坏时不静默当空集：保留原文件并禁止继续删除，等待修复/重建（R13-P2-4）。
            tombstonesCorrupt = true
        } else {
            tombstones.addAll(initialTombstones)
        }
        syncTombstones()
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

    /** 自定义强调色已启用：skinIndex 指向六个预设之后的第七格。 */
    val customMode: Boolean get() = skinIndex > C.skins.lastIndex

    /** 自定义底色已启用且处于日间。 */
    val customAppearance: Boolean get() = customThemeOn && C.isDay

    fun updateSkin(index: Int) {
        skinIndex = index.coerceIn(0, C.skins.size)
        prefs.edit().putInt("skinIndex", skinIndex).apply()
    }

    /** 主题变化前先记住旧底色，供整屏交叉淡入垫底；必须在改任何 C.* 之前调用（R9-P2-5）。 */
    private fun captureVoid() { themePrevVoid = C.Void }

    /** 只切外观，强调色保持不变。 */
    fun applyAppearance(look: C.Look) {
        captureVoid()
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

    /** 日间自定义底色：传入 HSL 选出的 ARGB，立即生效并持久化。 */
    fun applyCustomTheme(argb: Int) {
        captureVoid()
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
        captureVoid()
        customThemeOn = false
        C.applyCustomTheme(null)
        prefs.edit().putBoolean("customThemeOn", false).apply()
    }

    /** Enters the custom-colour slot and stores the picked colour. */
    fun selectCustomAccent(argb: Int) {
        val readable = C.readableAccent(Color(argb)).toArgb()
        customAccent = readable
        skinIndex = C.skins.size
        prefs.edit().putInt("customAccent", readable).putInt("skinIndex", skinIndex).apply()
    }

    fun updateEyeMode(mode: Int) {
        captureVoid()
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
        captureVoid()
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
        save(forceIndex = true)
    }

    fun open(session: Session) {
        if (session.id in deletingSnapshot) {
            notice = "会话正在删除，请稍后再试。"
            return
        }
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
        // Keep streaming; flush buffered text and persist before leaving the chat (P2-3).
        flushLive?.invoke()
        flushPending()
        setCurrentSession(null)
        refreshContextTokens()
    }

    /**
     * 删除会话四步事务（R10-P1-1 / R13-P1-1）：① 原子写墓碑成功才动列表；② 删消息文件（失败则回滚墓碑并提示「会话未删除」）；
     * ③ 写过滤后的索引；④ 索引写盘成功后才撤墓碑。墓碑 / 索引失败保留墓碑与脏标记，下次启动继续清理，不会复活或留幽灵。
     */
    fun delete(session: Session) {
        val id = session.id
        if (id in deletingIds) return
        if (tombstonesCorrupt) {
            notice = "删除失败：删除记录文件损坏且原文件已保留，修复或重建后才能继续删除会话。"
            return
        }
        markDeleting(id)
        // 不提前清 dirty：删除失败必须保留未落盘内容并重排保存（R13-P2-3）。
        // 流式中删除先真正停流，但不要顺带写索引：删除屏障建立前先写过滤索引正是 R13-P1-1 的窗口。
        if (id == streamSessionId) stopGenerating(persist = false)
        // 正在朗读该会话时同步停读，避免删完后朗读器还在后台出声（R10-P3-4）。
        if (Reader.activeId == id) Reader.stopAll()
        indexEpoch++
        indexDirty = true
        forceIndexNext = true
        val f = msgFile(id)
        // 墓碑是删除提交点：读-改-写全在单线程 IO 内用磁盘做底 merge，并发删除不互相覆盖（R13-P2-1）。
        io.execute {
            val merged = tombstoneStore.mergeAndWrite(listOf(id))
            if (merged == null) {
                clearDeleting(id)
                markDirty(id)
                scheduleSave()
                main.post { notice = "删除失败：删除记录损坏（原文件已保留），请修复或重建后重试。" }
                return@execute
            }
            main.post {
                tombstones.clear()
                tombstones.addAll(merged)
                syncTombstones()
            }
            val deleted = runCatching { f.delete() || !f.exists() }.getOrDefault(false)
            if (!deleted) {
                // 回滚同样以磁盘为底 merge：只移除本次 id，不覆盖别人刚写的墓碑（R13-P2-1）。
                val rolledBack = tombstoneStore.removeAndWrite(merged, id)
                clearDeleting(id)
                markDirty(id)
                scheduleSave()
                main.post {
                    if (rolledBack != null) {
                        tombstones.clear()
                        tombstones.addAll(rolledBack)
                        syncTombstones()
                        notice = "会话未删除：消息文件删除失败，请稍后再试。"
                    } else {
                        notice = "会话未删除，但回滚失败：该会话已标记为待清理，重启后会完成删除。"
                    }
                }
                return@execute
            }
            main.post { commitDelete(id) }
        }
    }

    /** 墓碑落盘、消息文件已删后的内存提交与索引写；索引成功后才撤墓碑（R10-P1-1 修法②）。 */
    private fun commitDelete(id: String) {
        tombstones.add(id)
        syncTombstones()
        sessions.removeAll { it.id == id }
        invalidateCurrent()
        if (currentId == id) setCurrentSession(null)
        refreshContextTokens()
        revision++
        if (!indexLoaded) {
            indexForceAfterLoad = true
            dirtyIds.remove(id)
            clearDeleting(id)
            return
        }
        // 提交点：此时才允许索引里没有该会话；此前 save() 一直保留它的索引行（R13-P1-1）。
        val rows = ArrayList(sessions.filterNot { it.id in deletingSnapshot })
        val epochAtStart = indexEpoch
        io.execute {
            val ok = runCatching {
                writeAtomic(indexFile, serializeIndex(rows.map(::sessionIndexRow)))
            }.isSuccess
            main.post {
                if (ok) {
                    dirtyIds.remove(id)
                    // 索引已不含该会话；再删一次消息文件并确认，墓碑才能撤（R12-P1-1）。
                    io.execute {
                        val f = msgFile(id)
                        val gone = runCatching { f.delete() || !f.exists() }.getOrDefault(false)
                        if (!gone) {
                            clearDeleting(id)
                            main.post { notice = "已从列表删除，但消息文件仍存在；重启后会继续清理。" }
                            return@execute
                        }
                        val after = tombstoneStore.removeAndWrite(tombstonesSnapshot, id)
                        main.post {
                            clearDeleting(id)
                            if (after == null) {
                                notice = "会话已删除，但删除记录文件损坏；重启后会继续清理。"
                            } else {
                                tombstones.clear()
                                tombstones.addAll(after)
                                syncTombstones()
                            }
                        }
                    }
                    if (PersistencePolicy.mayClearIndexDirty(epochAtStart, indexEpoch)) {
                        indexDirty = false
                        lastIndexWriteAt = System.currentTimeMillis()
                    }
                } else {
                    // 索引失败：保留墓碑并重试完整索引（下次 save 写过滤后版本），不能丢脏。
                    indexDirty = true
                    indexEpoch++
                    forceIndexNext = true
                    clearDeleting(id)
                    scheduleSave()
                    notice = "会话已删除，但索引写入失败；下次启动会自动清理。"
                }
            }
        }
    }

    /** @return true 表示消息已进入发送流程（调用方可以清空输入框）；false 表示被前置条件拦下。 */
    fun send(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val cur = current
        if (cur != null && cur.id in deletingSnapshot) {
            notice = "会话正在删除，请稍后再试。"
            return false
        }
        if (streaming) {
            notice = "正在生成；先点停止，再发送新的消息。"
            return false
        }
        typing = false
        var session = current
        if (session != null) ensureReadyBlocking(session)
        if (session != null && !session.messages.ready) {
            notice = "会话内容还在载入，请稍候再发送。"
            return false
        }
        if (session != null && isExpiredNow(session)) {
            session.expired = true
            notice = "这段对话已失效：先点「复活并继续」，恢复完整上下文后再发送。"
            return false
        }
        if (apiKey.isBlank()) {
            notice = "先填入 API Key，再开始对话。"
            showSettings = true
            return false
        }
        var createdSession = false
        if (session == null) {
            session = Session(title = body.take(24))
            session.messages.markEmpty()
            sessions.add(0, session)
            setCurrentSession(session.id)
            createdSession = true
        }
        if (session.messages.isEmpty()) session.title = body.take(24)
        session.messages.add(Msg(role = "user", text = body))
        session.charCount += body.length.toLong()
        val effectiveModel = if (webSearch) "deepseek-flash" else model
        val reply = Msg(role = "assistant", text = "", model = effectiveModel)
        session.messages.add(reply)
        session.updatedAt = System.currentTimeMillis()
        streaming = true
        sendTick++
        refreshContextTokens()
        notice = null
        markDirty(session.id)
        scheduleSave()
        // 新会话只存在于内存行：若沿用 30s 索引窗口不落盘，进程被杀后这个会话永久不可见（R8-P1-2）。
        if (createdSession) save(forceIndex = true)

        // The official Messages API is stateless: send system every turn (P2-7).
        val sysPrompt = systemPrompt.trim().takeIf { it.isNotBlank() }
        val includeSystem = sysPrompt != null
        val history = buildHistory(session, thinking)
        val sessionId = session.id
        streamSessionId = sessionId
        val mySeq = ++streamSeq
        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        val thinkStarted = SystemClock.uptimeMillis()
        // 正文按屏幕帧提交：SSE 可能每几毫秒到一次，但一帧最多写一次 Compose 状态，
        // 让打字机效果跟刷新率对齐，而不是被固定 120 ms 节流成 8 fps (R4-P1-1)。
        val choreographer = android.view.Choreographer.getInstance()
        var publishScheduled = false
        fun publishNow() {
            val prev = reply.text.length
            reply.text = textBuf.toString()
            reply.thinking = thinkingBuf.toString()
            // 索引/上下文条读 charCount，流式正文增长时增量维护，避免落盘时主线程扫全文（P2-8）。
            val delta = reply.text.length - prev
            if (delta != 0) session.charCount += delta.toLong()
        }
        val publishFrame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                publishScheduled = false
                if (mySeq == streamSeq) publishNow()
            }
        }
        fun schedulePublish() {
            if (publishScheduled) return
            publishScheduled = true
            choreographer.postFrameCallback(publishFrame)
        }
        flushLive = { publishNow() }
        val citations = ArrayList<Citation>()
        val handle = ApiClient.stream(
            apiKey = apiKey,
            model = effectiveModel,
            thinking = thinking,
            webSearch = webSearch,
            systemPrompt = sysPrompt,
            messages = history,
            maxTokens = maxTokens,
            flushMs = if (performanceMode) 33 else 16,
            onText = { chunk ->
                if (mySeq == streamSeq) {
                    textBuf.append(chunk)
                    if (thinkingBuf.isNotEmpty() && reply.thinkingMs == 0L) {
                        reply.thinkingMs = SystemClock.uptimeMillis() - thinkStarted
                    }
                    session.updatedAt = System.currentTimeMillis()
                    schedulePublish()
                    bumpRevisionThrottled()

                    // Flush streaming text to disk every ~2s so a kill loses at most 2s (P2-3).
                    val nowUptime = android.os.SystemClock.uptimeMillis()
                    if (nowUptime - lastStreamSaveAt >= 2_000L) {
                        lastStreamSaveAt = nowUptime
                        markDirty(sessionId)
                        scheduleSave()
                    }
                }
            },
            onThinking = { chunk ->
                if (mySeq == streamSeq) {
                    thinkingBuf.append(chunk)
                    schedulePublish()
                    bumpRevisionThrottled()
                    // 思考内容以前从不落盘，思考中杀进程会整段丢失（P2-4）。
                    val nowUptime = android.os.SystemClock.uptimeMillis()
                    if (nowUptime - lastStreamSaveAt >= 2_000L) {
                        lastStreamSaveAt = nowUptime
                        markDirty(sessionId)
                        scheduleSave()
                    }
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
                    choreographer.removeFrameCallback(publishFrame)
                    publishNow()
                    // 失败也要为已产生的 token 计费，否则账本长期偏低（P2-8）。
                    finishUsage(session, reply)
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    refreshContextTokens()
                    markDirty(sessionId)
                    // Single terminal state: invalidate the onDone(false) that follows (P2-1).
                    streamSeq++
                    revision++
                    save(forceIndex = true)
                }
            },
            onDone = { _ ->
                // 用户点停止时 stopGenerating 先自增 streamSeq 并给终态，这里只处理正常结束（P3-3）。
                if (mySeq == streamSeq) {
                    if (thinkingBuf.isNotEmpty() && reply.thinkingMs == 0L) {
                        reply.thinkingMs = SystemClock.uptimeMillis() - thinkStarted
                    }
                    choreographer.removeFrameCallback(publishFrame)
                    publishNow()
                    val before = reply.text.length
                    if (reply.text.isBlank()) reply.text = "（没有收到内容）"
                    if (reply.text.length != before) session.charCount += (reply.text.length - before).toLong()
                    if (reply.stopReason == "max_tokens") {
                        notice = "已达到最大输出长度（可在设置里调高上限）。"
                    }
                    streaming = false
                    activeStream = null
                    flushLive = null
                    streamSessionId = null
                    refreshContextTokens()
                    finishUsage(session, reply)
                    markDirty(sessionId)
                    revision++
                    save(forceIndex = true)
                }
            },
        )
        if (mySeq == streamSeq) activeStream = handle else handle.cancel()
        return true
    }

    fun stopGenerating(persist: Boolean = true) {
        if (!streaming && activeStream == null) return
        flushLive?.invoke()
        flushLive = null
        // 停止是终态：按已收到的内容先结算费用，再用代际递增让迟到的 onDone/onError 失效（P2-4）。
        val stoppedSession = streamSessionId?.let { id -> sessions.firstOrNull { it.id == id } } ?: current
        val stoppedReply = stoppedSession?.messages?.lastOrNull()
            ?.takeIf { it.role == "assistant" && !it.failed }
        if (stoppedSession != null && stoppedReply != null) finishUsage(stoppedSession, stoppedReply)
        streamSeq++
        activeStream?.cancel()
        activeStream = null
        streaming = false
        notice = "已停止生成；已保留当前内容。"
        (streamSessionId ?: current?.id)?.let { markDirty(it) }
        streamSessionId = null
        refreshContextTokens()
        revision++
        // 停止是终态：立即落盘（含索引），而不是等 260/700ms 的防抖（P2-4）。
        // 删除路径传 persist=false：删除屏障建立前不写过滤索引（R13-P1-1）。
        if (persist) save(forceIndex = true)
    }

    fun revive(session: Session, mode: String = "full") {
        if (session.id in deletingSnapshot) {
            notice = "会话正在删除，请稍后再试。"
            return
        }
        ensureReadyBlocking(session)
        if (!session.messages.ready) {
            notice = "会话内容还在载入，请稍候再复活。"
            return
        }
        session.revived = true
        val total = historyMessages(session).size
        if (mode == "summary" && total > 40) {
            val plan = buildLocalSummary(session)
            session.contextMode = "summary"
            session.summary = plan.text
            session.summaryCovered = plan.covered
            session.summaryBlocks.clear()
            session.summaryBlocks.addAll(plan.blocks)
            notice = "已用摘要模式恢复：早期对话由本机压成要点，后部原文保留；不调用 API、不额外消耗 token。"
        } else {
            session.contextMode = "full"
            session.summary = ""
            session.summaryCovered = 0
            session.summaryBlocks.clear()
            notice = if (mode == "summary") "这段会话较短，无需摘要；已按完整原文恢复。" else "已恢复完整上下文：当前分支全部原文会作为后续上下文，由你的 API Key 直连支付。"
        }
        markDirty(session.id)
        save(forceIndex = true)
        refreshContextTokens()
        revision++
    }

    fun retryLast() {
        if (streaming) {
            notice = "正在生成；先点停止，再发送新的消息。"
            return
        }
        val session = current ?: return
        if (session.id in deletingSnapshot) {
            notice = "会话正在删除，请稍后再试。"
            return
        }
        if (isExpiredNow(session)) {
            session.expired = true
            notice = "这段对话已失效：先点「复活并继续」，恢复完整上下文后再发送。"
            return
        }
        if (apiKey.isBlank()) {
            notice = "先填入 API Key，再开始对话。"
            showSettings = true
            return
        }
        ensureReadyBlocking(session)
        if (!session.messages.ready) {
            notice = "会话内容还在载入，请稍候再重试。"
            return
        }
        val lastUserIndex = session.messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) return
        val lastUser = session.messages[lastUserIndex]
        val lastAssistant = if (lastUserIndex + 1 < session.messages.size &&
            session.messages[lastUserIndex + 1].role == "assistant"
        ) session.messages[lastUserIndex + 1] else null
        if (lastAssistant != null) session.messages.removeAt(lastUserIndex + 1)
        session.messages.removeAt(lastUserIndex)
        val removedChars = lastUser.text.length.toLong() + (lastAssistant?.text?.length ?: 0).toLong()
        session.charCount = (session.charCount - removedChars).coerceAtLeast(0L)
        // Deleting before sending is unsafe: guards may reject, so restore on failure (P1-2).
        if (!send(lastUser.text)) {
            session.messages.add(lastUser)
            if (lastAssistant != null) session.messages.add(lastAssistant)
            session.charCount += removedChars
            notice = "发送失败，已恢复上一轮消息。"
        }
    }

    private fun historyMessages(session: Session): List<Msg> =
        session.messages.filter { it.text.isNotBlank() && !it.failed && (it.role == "user" || it.role == "assistant") }

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

    fun isPeakNow(): Boolean = isPeakAt(System.currentTimeMillis())

    /** Peak pricing is computed at message time, never re-priced at display time (P3-4). */
    private fun isPeakAt(epochMillis: Long): Boolean {
        val at = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.of("Asia/Shanghai"))
        if (at.dayOfWeek == java.time.DayOfWeek.SATURDAY || at.dayOfWeek == java.time.DayOfWeek.SUNDAY) return false
        val minute = at.hour * 60 + at.minute
        return (minute in 540..719) || (minute in 840..1079)
    }

    fun msgCostForDisplay(m: Msg): Double = msgCost(m, if (m.time > 0L) isPeakAt(m.time) else isPeakNow())

    fun msgCost(m: Msg): Double = msgCost(m, isPeakNow())

    private fun msgCost(m: Msg, peak: Boolean): Double {
        if (m.inputTokens == 0 && m.outputTokens == 0) return 0.0
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
            // 缓存写入按未命中价计费；漏掉会让会话费用偏低（P2-4）。
            m.cacheCreateTokens / 1_000_000.0 * miss +
            m.outputTokens / 1_000_000.0 * out
    }

    /** 失效以 updatedAt 现场派生，避免冷启动后按索引里的旧 expired 字段误判（P2-8）。 */
    private fun isExpiredNow(s: Session): Boolean =
        !s.revived && System.currentTimeMillis() - s.updatedAt > 604_800_000L

    /** 进入前台/启动后重算 7 天失效标记；长开进程也能及时变灰（P2-8）。 */
    fun refreshExpired() {
        val now = System.currentTimeMillis()
        var changed = false
        for (s in sessions) {
            val expired = !s.revived && now - s.updatedAt > 604_800_000L
            if (s.expired != expired) {
                s.expired = expired
                changed = true
            }
        }
        if (changed) revision++
    }

    private fun finishUsage(session: Session, reply: Msg) {
        val cost = msgCost(reply)
        reply.costCny = cost
        if (cost > 0.0) session.costCny += cost
    }

    private fun markDirty(id: String) {
        dirtyIds.add(id)
        // 索引脏但不必每次落盘都全量重写；由 save() 按 30s 节流，终态事件再 forceIndex（P2-5）。
        indexDirty = true
        // 写盘期间可能又来了新的脏变更：epoch 变化时 IO 成功回调不能清掉 indexDirty（R8-P1-2）。
        indexEpoch++
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, if (performanceMode) 260L else 700L)
    }

    private fun flushPending() {
        main.removeCallbacks(saveRunnable)
        save()
    }

    private var forceIndexNext = false
    private var indexDirty = false
    private var lastIndexWriteAt = 0L
    /** 脏标记代数：save() 开始时快照，IO 成功后只有代数未变才清 indexDirty（R8-P1-2）。 */
    private var indexEpoch = 0L
    /** 启动索引是否加载完成；加载完成前禁止写索引，避免用不完整列表覆盖旧索引（R9-P1-1）。 */
    private var indexLoaded = false
    private var indexForceAfterLoad = false
    /** 主题切换前的旧底色：EchoApp 用它做全屏交叉淡入垫底（R9-P2-5）。 */
    var themePrevVoid by mutableStateOf(C.Void)
        private set

    fun save(forceIndex: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { save(forceIndex) }
            return
        }
        val urgent = forceIndex || forceIndexNext
        forceIndexNext = false
        // 启动索引加载完成前内存里可能只有新建会话：此时写索引会覆盖旧索引（R9-P1-1）。
        if (!indexLoaded && (urgent || indexDirty)) indexForceAfterLoad = true
        val now = System.currentTimeMillis()
        val indexDue = PersistencePolicy.indexDueAfterLoad(
            indexLoaded, urgent || indexForceAfterLoad, indexDirty, now, lastIndexWriteAt,
        )
        // 删除中的会话不落盘、不进索引：否则更晚的保存会用旧快照把已删会话写回来（R12-P1-1）。
        val deleting = deletingSnapshot
        val ids = PersistencePolicy.persistableIds(ArrayList(dirtyIds), deleting)
        if (ids.isEmpty() && !indexDue) return
        val epochAtStart = indexEpoch
        dirtyIds.clear()
        val pairs = ArrayList<Pair<String, SessionSnapshot>>(ids.size)
        val notReady = ArrayList<String>(ids.size)
        for (id in ids) {
            val s = sessions.firstOrNull { it.id == id } ?: continue
            if (!s.messages.ready) {
                notReady.add(id)
                continue
            }
            pairs.add(id to snapshotSession(s))
        }
        // Keep dirty flags for not-yet-loaded sessions so they persist later (P2-3).
        dirtyIds.addAll(notReady)
        // 删除屏障：删除中会话的索引行保留到 commitDelete 写过滤索引为止；
        // 否则「索引先丢、墓碑未落盘」的崩溃窗口会被 orphan 扫描复活（R13-P1-1）。
        val allSessions = ArrayList(sessions)
        io.execute {
            val failed = ArrayList<String>()
            for ((id, snap) in pairs) {
                // 快照之后才进入删除的会话直接跳过，避免旧内容写回文件（R12-P1-1）。
                if (id in deletingSnapshot) continue
                if (runCatching { writeAtomic(msgFile(id), serializeSnapshot(snap)) }.isFailure) failed.add(id)
            }
            val indexOk = if (indexDue) {
                runCatching {
                    writeAtomic(indexFile, serializeIndex(allSessions.map(::sessionIndexRow)))
                }.isSuccess
            } else {
                true
            }
            if (failed.isNotEmpty() || !indexOk) {
                main.post {
                    dirtyIds.addAll(failed)
                    if (!indexOk) forceIndexNext = true
                }
            } else if (indexDue) {
                main.post {
                    if (PersistencePolicy.mayClearIndexDirty(epochAtStart, indexEpoch)) {
                        indexDirty = false
                        lastIndexWriteAt = System.currentTimeMillis()
                    } else {
                        forceIndexNext = true
                    }
                }
            }
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
                    failed = m.failed,
                ),
            )
        }
        return SessionSnapshot(
            s.id, s.summary, s.summaryCovered, s.summaryBlocks.toList(), messages,
            title = s.title, updatedAt = s.updatedAt, expired = s.expired, revived = s.revived,
            contextMode = s.contextMode, imported = s.imported, pinned = s.pinned, costCny = s.costCny,
        )
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
        s.summaryCovered, s.imported, s.pinned, s.costCny, s.messages.roleSeq,
        s.charCount,
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
        failed: Boolean,
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
        // 失败标记必须落盘：否则重启后错误文本会作为正常助手消息进入上下文（P2-4）。
        if (failed) put("failed", true)
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
        title: String = "",
        updatedAt: Long = 0L,
        expired: Boolean = false,
        revived: Boolean = false,
        contextMode: String = "full",
        imported: Boolean = false,
        pinned: Boolean = false,
        costCny: Double = 0.0,
    ): String = JSONObject().apply {
        put("summary", summary)
        put("covered", covered)
        putSummaryBlocks(this, blocks)
        put("messages", messages)
        // 会话级元数据与消息同文件：索引丢失时可从消息文件恢复置顶/复活/导入/费用（R9-P2-3）。
        put("title", title)
        put("updatedAt", updatedAt)
        put("expired", expired)
        put("revived", revived)
        put("contextMode", contextMode)
        put("imported", imported)
        put("pinned", pinned)
        put("costCny", costCny)
    }.toString()

    private fun snapshotIndex(): List<IndexRow> = sessions.map(::sessionIndexRow)

    private fun serializeSnapshot(s: SessionSnapshot): String {
        val arr = JSONArray()
        for (m in s.messages) {
            arr.put(
                msgJson(
                    m.id, m.role, m.text, m.thinking, m.model, m.time,
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens,
                    m.costCny, m.citations, m.thinkingMs, m.signature, m.stopReason, m.failed,
                ),
            )
        }
        return sessionJson(
            s.summary, s.covered, s.blocks, arr,
            title = s.title, updatedAt = s.updatedAt, expired = s.expired, revived = s.revived,
            contextMode = s.contextMode, imported = s.imported, pinned = s.pinned, costCny = s.costCny,
        )
    }

    private fun serializeIndex(rows: List<IndexRow>): String {
        val arr = JSONArray()
        rows.forEach { arr.put(indexRowJson(it)) }
        return arr.toString()
    }


    private fun serializeSession(s: Session): String {
        val arr = JSONArray()
        for (m in s.messages) {
            arr.put(
                msgJson(
                    m.id, m.role, m.text, m.thinking, m.model, m.time,
                    m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreateTokens,
                    m.costCny, m.citations, m.thinkingMs, m.signature, m.stopReason, m.failed,
                ),
            )
        }
        return sessionJson(
            s.summary, s.summaryCovered, s.summaryBlocks, arr,
            title = s.title, updatedAt = s.updatedAt, expired = s.expired, revived = s.revived,
            contextMode = s.contextMode, imported = s.imported, pinned = s.pinned, costCny = s.costCny,
        )
    }

    /** @return null 表示墓碑文件损坏（原文件保留、不 rename）；调用方必须拒绝用空集覆盖（R13-P2-4）。 */
    private fun readTombstonesOrNull(): List<String>? = tombstoneStore.readOrNull()?.toList()

    private fun readTombstones(): List<String> = readTombstonesOrNull() ?: emptyList()

    /** 墓碑落盘；只接受不可变快照，避免 IO 线程与主线程共享 HashSet（R10-P1-1 / R11-P2-3）。 */
    private fun saveTombstones(ids: Collection<String> = tombstonesSnapshot): Boolean = writeTombstones(ids)

    private fun writeTombstones(ids: Collection<String>): Boolean = tombstoneStore.write(ids)

    /** 主线程维护墓碑快照；IO 线程只读不可变副本，避免 HashSet 并发读写（R10-P2-3）。 */
    private fun syncTombstones() {
        tombstonesSnapshot = tombstones.toSet()
    }

    /** 标记删除中：主线程维护集合，IO 线程读不可变快照（R12-P1-1）。 */
    private fun markDeleting(id: String) {
        deletingIds.add(id)
        deletingSnapshot = deletingIds.toSet()
    }

    private fun clearDeleting(id: String) {
        deletingIds.remove(id)
        deletingSnapshot = deletingIds.toSet()
    }

    /** 把消息文件里索引/内存/墓碑都没有的会话补进 list；candidates 按最近修改优先（R9-P1-2）。 */
    private fun appendOrphans(list: MutableList<Session>) {
        val indexed = list.mapTo(HashSet()) { it.id }
        val candidates = (msgDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { it.name.substringBeforeLast('-') }
            .filter { it.isNotBlank() }
        for (id in PersistencePolicy.reconcileIds(indexed, emptySet(), tombstonesSnapshot, candidates)) {
            sessionFromMessageFile(msgFile(id))?.let { list.add(it) }
        }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        // 唯一 tmp 名避免并发写互相覆盖；fsync 后再 rename，崩溃不会留下半截文件（P2-8）。
        val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
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

    /** 索引损坏时从本地消息文件重建：只认「文件名能反解出 id」的文件（P2-6）。 */
    private fun rebuildIndexFromMessages(): List<Session> {
        val files = msgDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<Session>(files.size)
        for (f in files) {
            sessionFromMessageFile(f)?.let(out::add)
        }
        return out
    }

    /** 单个消息文件 → 会话行；文件名反解不出 id 或内容为空时返回 null（R8-P2-2 复用）。 */
    private fun sessionFromMessageFile(f: File): Session? {
        val record = readSessionRecord(f) ?: return null
        if (record.messages.isEmpty()) return null
        val id = f.name.substringBeforeLast('-')
        if (id.isBlank() || msgFile(id) != f) return null
        val now = System.currentTimeMillis()
        val last = record.messages.lastOrNull()?.time?.takeIf { it > 0L } ?: f.lastModified()
        val s = Session(
            id = id,
            title = record.title.ifBlank {
                record.messages.firstOrNull { it.role == "user" }?.text?.trim()?.take(24).orEmpty()
                    .ifBlank { "未命名会话" }
            },
            updatedAt = record.updatedAt.takeIf { it > 0L } ?: last,
            expired = record.expired || now - last > 604_800_000L,
            // 元数据随消息文件落盘：对账恢复的会话不丢置顶/复活/导入/费用（R9-P2-3）。
            revived = record.revived,
            contextMode = record.contextMode.ifBlank { if (record.summary.isNotBlank()) "summary" else "full" },
            summaryCovered = record.covered.coerceAtLeast(0),
            imported = record.imported,
            pinned = record.pinned,
            costCny = record.costCny,
        )
        s.messages.markReady(record.messages)
        s.messages.unload()
        s.summary = record.summary
        s.summaryBlocks.addAll(record.blocks)
        s.charCount = record.messages.sumOf { it.text.length.toLong() }
        return s
    }

    private fun loadIndexAsync(inMemoryIds: Set<String> = emptySet()) {
        indexLoading = true
        val epochAtStart = indexEpoch
        // 主线程先取一份「不能因为文件缺失就丢」的 id 快照：脏会话/流式/当前会话（R10-P1-1 修法④）。
        val keepIds = HashSet<String>(inMemoryIds)
        currentId?.let(keepIds::add)
        streamSessionId?.let(keepIds::add)
        keepIds.addAll(dirtyIds)
        io.execute {
            try {
                loadIndexBody(keepIds, inMemoryIds, epochAtStart)
            } catch (t: Throwable) {
                // 索引恢复抛异常也必须复位加载态，否则欢迎页永远停在「正在恢复会话索引…」（R11-P2-2）。
                // 索引恢复异常不能清空内存会话：保留本地数据并提示，下次启动重试（R12-P2-3）。
                main.post {
                    indexLoading = false
                    indexForceAfterLoad = true
                    forceIndexNext = true
                    revision++
                    notice = "会话索引恢复失败：" + (t.message ?: "未知错误") + "；本机会话已保留，重启后会重试。"
                }
            }
        }
    }

    /**
     * loadIndexAsync 的 IO 主体：读索引 → 墓碑/幽灵行过滤 → 过滤后索引先落盘 → 清理墓碑 →
     * 与消息文件对账补孤儿 → 发布到 UI。每次索引写都分别记账：任一失败都保留脏标记（R11-P2-1）。
     */
    private fun loadIndexBody(keepIds: Set<String>, inMemoryIds: Set<String>, epochAtStart: Long) {
        val list = ArrayList<Session>()
        var corrupt = false
        var droppedTombstoned = 0
        var droppedMissing = 0
        var indexWriteAttempts = 0
        var indexWriteFailures = 0

        fun writeIndexNow() {
            indexWriteAttempts++
            val ok = runCatching {
                writeAtomic(indexFile, serializeIndex(list.map(::sessionIndexRow)))
            }.isSuccess
            if (!ok) indexWriteFailures++
        }

        runCatching {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = sessionFromIndex(o) ?: continue
                // 已删除但索引还没来得及重写的行不能复活成空会话（R9-P2-2）。
                if (s.id in tombstonesSnapshot) { droppedTombstoned++; continue }
                list.add(s)
            }
        }.onFailure { corrupt = true }
        if (corrupt) {
            // 坏索引不能静默当空列表：备份原文件，尽量从消息文件重建（P2-6）。
            runCatching {
                indexFile.renameTo(File(storeDir, "sessions.index.bad." + System.currentTimeMillis() + ".json"))
            }
            val valid = ArrayList(list)
            list.clear()
            // 合并去重，保留坏索引里还能读出的 pinned/revived/imported/cost（R8-P2-2）；
            // 重建行同样过墓碑，避免把已删会话建回来（R10-P2-1）。
            list.addAll(
                PersistencePolicy.mergeRebuilt(valid, rebuildIndexFromMessages()) { it.id }
                    .filterNot { it.id in tombstonesSnapshot }
            )
        }
        // 索引行对应的消息文件若已丢失（崩溃窗口 b 遗留的幽灵行），丢弃并补写索引（R10-P1-1 修法④）。
        val iter = list.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            if (s.id !in keepIds && !msgFile(s.id).exists()) {
                iter.remove()
                droppedMissing++
            }
        }
        // 墓碑过滤后的索引必须先落盘，才能删残留消息文件/清墓碑；空索引也要显式写一次（R11-P3-2）。
        val needsIndexWrite = corrupt || droppedTombstoned > 0 || droppedMissing > 0
        if (needsIndexWrite) {
            writeIndexNow()
            main.post {
                if (corrupt) {
                    notice = if (list.isEmpty()) {
                        "会话索引损坏且无法从本地文件重建；可用「导入历史对话」恢复。"
                    } else {
                        "会话索引损坏，已从本地会话文件重建 " + list.size + " 个会话。"
                    }
                }
            }
        }
        // 只有「过滤后索引确实写盘」才允许清理墓碑与残留文件（R10-P1-1 修法③ / R11-P2-1）。
        val filterWritten = indexWriteAttempts > 0 && indexWriteFailures == 0
        if (PersistencePolicy.startupCleanupAllowed(filterWritten, needsIndexWrite) &&
            tombstonesSnapshot.isNotEmpty()
        ) {
            val tombsAtStart = tombstonesSnapshot
            val left = ArrayList<String>()
            for (id in tombsAtStart) {
                val f = msgFile(id)
                if (!runCatching { f.delete() || !f.exists() }.getOrDefault(false)) left.add(id)
            }
            main.post {
                // 加载期新增的墓碑必须保留：内存先合并，落盘再用磁盘做底 merge（R13-P2-5）。
                val kept = (tombstonesSnapshot - tombsAtStart) + left
                tombstones.clear()
                tombstones.addAll(tombstonesSnapshot - tombsAtStart)
                tombstones.addAll(left)
                syncTombstones()
                io.execute {
                    val merged = tombstoneStore.mergeAndWrite(kept)
                    main.post {
                        if (merged == null) {
                            notice = "删除记录文件损坏；原文件已保留，清理会在修复后继续。"
                        } else {
                            tombstones.clear()
                            tombstones.addAll(merged)
                            syncTombstones()
                        }
                    }
                }
            }
        }
        // 索引与消息文件对账：索引丢行/导入后索引写失败时补齐；按最近修改优先、不截断（R9-P1-2）。
        val indexed = list.mapTo(HashSet()) { it.id }
        val candidates = (msgDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { it.name.substringBeforeLast('-') }
            .filter { it.isNotBlank() }
        val orphans = PersistencePolicy.reconcileIds(indexed, inMemoryIds, tombstonesSnapshot, candidates)
        if (orphans.isNotEmpty()) {
            var added = 0
            for (id in orphans) {
                sessionFromMessageFile(msgFile(id))?.let { list.add(it); added++ }
            }
            if (added > 0) {
                // 补入孤儿后再写一次完整索引；失败时下面统一置脏重试（R11-P2-1）。
                writeIndexNow()
                main.post { notice = "已从本机会话文件补回 " + added + " 个索引里缺失的会话。" }
            }
        }
        val indexWritten = indexWriteAttempts > 0 && indexWriteFailures == 0
        main.post {
            indexLoading = false
            if (indexWritten) {
                if (PersistencePolicy.mayClearIndexDirty(epochAtStart, indexEpoch)) {
                    indexDirty = false
                    lastIndexWriteAt = System.currentTimeMillis()
                } else {
                    forceIndexNext = true
                }
            } else if (needsIndexWrite || indexWriteFailures > 0) {
                // 本该写却没写成或写入失败：保留脏标记，下一次 save/启动重试（R11-P2-1）。
                indexDirty = true
                forceIndexNext = true
            }
            applyLoaded(list, indexWritten)
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
        ).also {
            it.messages.markUnloaded(o.optString("roles"))
            it.charCount = o.optLong("chars", 0L)
        }
    }

    private fun applyLoaded(list: List<Session>, indexWritten: Boolean = false) {
        indexLoaded = true
        // 加载/重建期间主线程可能刚删除会话：应用前再用最新墓碑过滤一次，否则刚删的会话会回来（R11-P2-4）。
        val deleted = tombstonesSnapshot
        val sorted = list.sortedByDescending { it.updatedAt }.filterNot { it.id in deleted }
        // The cold-start index may lag behind memory: keep local/dirty/current sessions (P3-2).
        val loadedIds = sorted.mapTo(HashSet()) { it.id }
        val localOnly = sessions.filter {
            it.id !in loadedIds && it.id !in deleted &&
                (it.id in dirtyIds || it.id == streamSessionId || it.id == currentId)
        }
        Snapshot.withMutableSnapshot {
            sessions.clear()
            sessions.addAll(localOnly)
            sessions.addAll(sorted)
        }
        invalidateCurrent()
        refreshExpired()
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
        // 加载期间被拦下的强制写索引在这里补写：此时 sessions 才是完整列表（R9-P1-1）。
        if (indexForceAfterLoad || indexDirty) {
            indexForceAfterLoad = false
            save(forceIndex = true)
        } else if (indexWritten) {
            // 只有索引确实写盘才更新时间戳；写失败保持脏标记，下一次 save 重试（R10-P2-2）。
            lastIndexWriteAt = System.currentTimeMillis()
        }
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
            if (s.id == currentId || s.id == streamSessionId || dirtyIds.contains(s.id)) return@forEach
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
            // Sync parse wins: drop the async marker; a late async result is dropped by markReady's guard (P1-1).
            parsingIds.remove(s.id)
            runCatching { parseSessionRecord(f, s) }.onFailure { s.messages.markEmpty() }
            if (s.id == currentId) {
                refreshContextTokens()
                revision++
            }
            return
        }
        parsingIds.add(s.id)
        io.execute {
            // 解析（JSON → Msg 列表）在 IO 线程；把结果提交给 Compose 状态必须在主线程，
            // 否则 UI 可能读到写了一半的 MessageList（P2-2）。
            val record = readSessionRecord(f)
            main.post {
                parsingIds.remove(s.id)
                if (record == null) {
                    s.messages.markEmpty()
                } else {
                    runCatching { applyParsedRecord(record, s) }.onFailure { s.messages.markEmpty() }
                }
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
        // 大会话不在主线程同步解析：转异步载入，调用方看到 ready=false 后提示稍候（P2-3）。
        if (f.length() > 48_000L) {
            ensureReadyForUi(s)
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

    /** IO 线程完成的解析产物：JSON→Msg 列表与摘要都在后台构造，主线程只发布（P2-3）。 */
    private data class ParsedRecord(
        val messages: List<Msg>,
        val summary: String,
        val covered: Int,
        val blocks: List<SummaryBlock>,
        val title: String = "",
        val updatedAt: Long = 0L,
        val expired: Boolean = false,
        val revived: Boolean = false,
        val contextMode: String = "",
        val imported: Boolean = false,
        val pinned: Boolean = false,
        val costCny: Double = 0.0,
    )

    /** 只做 IO 解析，不碰 Compose 状态；结果交给主线程 [applyParsedRecord]。 */
    private fun readSessionRecord(f: File): ParsedRecord? = runCatching {
        val o = JSONObject(f.readText())
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
                it.failed = m.optBoolean("failed")
            })
        }
        ParsedRecord(
            messages = list,
            summary = o.optString("summary"),
            covered = if (o.has("covered")) o.optInt("covered") else -1,
            blocks = readSummaryBlocks(o),
            title = o.optString("title"),
            updatedAt = o.optLong("updatedAt"),
            expired = o.optBoolean("expired"),
            revived = o.optBoolean("revived"),
            contextMode = o.optString("contextMode"),
            imported = o.optBoolean("imported"),
            pinned = o.optBoolean("pinned"),
            costCny = o.optDouble("costCny", 0.0),
        )
    }.getOrNull()

    private fun parseSessionRecord(f: File, s: Session) {
        val record = readSessionRecord(f) ?: run { s.messages.markEmpty(); return }
        applyParsedRecord(record, s)
    }

    /** 主线程只做一次发布：MessageList 的 loaded 只在主线程写，避免 UI 读普通 ArrayList 竞争（P2-4）。 */
    private fun applyParsedRecord(record: ParsedRecord, s: Session) {
        // 先提交消息列表；晚到的异步解析在这里被丢弃时，摘要/覆盖范围/字数
        // 也必须一起丢弃，不能覆盖已加载会话的较新状态（R4-P2-5）。
        if (!s.messages.markReady(record.messages)) return
        s.summary = record.summary
        s.summaryCovered = if (record.covered >= 0) record.covered else s.summaryCovered
        s.summaryBlocks.clear()
        s.summaryBlocks.addAll(record.blocks)
        s.charCount = record.messages.sumOf { it.text.length.toLong() }
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

    /** 索引缺失时也必须扫消息文件，否则会话全部不可见（R9-P1-2）；重建结果同样过墓碑（R10-P2-1）。 */
    private fun rebuildAllAsync() {
        indexLoading = true
        io.execute {
            val rebuilt = rebuildIndexFromMessages().filterNot { it.id in tombstonesSnapshot }
            val wrote = if (rebuilt.isNotEmpty()) {
                runCatching { writeAtomic(indexFile, serializeIndex(rebuilt.map(::sessionIndexRow))) }.isSuccess
            } else {
                false
            }
            main.post {
                indexLoading = false
                if (!wrote) {
                    // 空索引也要落盘，避免每次启动重复扫 3800 个文件；失败则下次 save 重试（R10-P2-2）。
                    indexDirty = true
                    forceIndexNext = true
                }
                applyLoaded(rebuilt, wrote)
                notice = if (rebuilt.isEmpty()) {
                    "没有找到本机会话文件；可用「导入历史对话」恢复。"
                } else {
                    "会话索引缺失，已从本地会话文件恢复 " + rebuilt.size + " 个会话。"
                }
            }
        }
    }

    private fun migrateLegacyAsync() {
        if (!legacyFile.exists()) {
            rebuildAllAsync()
            return
        }
        indexLoading = true
        io.execute {
            val list = ArrayList<Session>()
            var failed = false
            runCatching {
                JsonReader(legacyFile.reader(Charsets.UTF_8)).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        val p = readLegacySession(r) ?: continue
                        val s = p.toSession()
                        // 已删除/删除中的会话不再迁移写盘（R13-P3-2）。
                        if (s.id in tombstonesSnapshot || s.id in deletingSnapshot) continue
                        // 先就绪再序列化：serializeSession 带上标题/置顶/复活/费用等元数据，
                        // 索引再丢也能从消息文件恢复（R10-P2-4）；失败计入 failed 以便下次重试。
                        s.messages.markReady(p.messages)
                        runCatching { writeAtomic(msgFile(s.id), serializeSession(s)) }
                            .onFailure { failed = true }
                        s.messages.unload()
                        list.add(s)
                    }
                    r.endArray()
                }
                appendOrphans(list)
                runCatching { writeAtomic(indexFile, buildIndexJsonFrom(list)) }
                    .onFailure { failed = true }
                // 部分失败时不改 legacy 文件名：下次启动还能重试迁移（R10-P3-2）。
                if (!failed) runCatching { legacyFile.renameTo(File(storeDir, "legacy-sessions.json.bak")) }
            }.onFailure {
                failed = true
                main.post { notice = "旧版会话数据迁移失败：" + (it.message ?: "未知错误") }
            }
            main.post {
                indexLoading = false
                if (failed) {
                    indexDirty = true
                    forceIndexNext = true
                }
                val hidden = tombstonesSnapshot + deletingSnapshot
                applyLoaded(list.filterNot { it.id in hidden }, !failed)
            }
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
                            // 已删除/删除中的会话跳过，避免用导入文件把墓碑里的会话写回来（R13-P3-2）。
                            if (s.id in tombstonesSnapshot || s.id in deletingSnapshot) {
                                duplicates++
                                s.messages.unload()
                                continue
                            }
                            // 索引未载入时 knownIds 可能不全；绝不覆盖本机已有会话文件（P2-6）。
                            val target = msgFile(s.id)
                            if (target.exists()) {
                                duplicates++
                                s.messages.unload()
                                continue
                            }
                            val ok = runCatching { writeAtomic(target, serializeSession(s)) }.isSuccess
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
        val deleted = tombstonesSnapshot + deletingSnapshot
        // 已删除/删除中的会话不能再被导入，否则会绕过墓碑复活（R13-P3-2）。
        val fresh = parsed.filter { it.id !in deleted && existingIds.add(it.id) }
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
        // 索引加载完成前导入：消息文件已写，但此刻写索引会用不完整列表覆盖旧索引（R9-P1-1）。
        // 把导入项标脏让 applyLoaded 保留它们，加载完成后统一强制写一次完整索引。
        if (!indexLoaded) {
            fresh.forEach { markDirty(it.id) }
            indexForceAfterLoad = true
            return
        }
        // 索引 JSON 在 IO 线程构建并写入（主线程不再遍历 3800 行，R9-P3-4）；失败必须告知用户；
        // 成功清脏要校验 epoch，导入期间新 markDirty 的变更不能被误清（R9-P2-1）。
        val epochAtStart = indexEpoch
        val snapshotSessions = ArrayList(sessions)
        io.execute {
            val rows = snapshotSessions.map(::sessionIndexRow)
            val ok = runCatching { writeAtomic(indexFile, serializeIndex(rows)) }.isSuccess
            main.post {
                if (ok) {
                    if (PersistencePolicy.mayClearIndexDirty(epochAtStart, indexEpoch)) {
                        indexDirty = false
                        lastIndexWriteAt = System.currentTimeMillis()
                    } else {
                        forceIndexNext = true
                    }
                } else {
                    indexDirty = true
                    indexEpoch++
                    forceIndexNext = true
                    notice = notice.orEmpty() + "（索引保存失败，重启后会自动从会话文件恢复）"
                }
            }
        }
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

/**
 * 旧配色存档迁移的纯函数版本（JVM 可单测）：
 * schema 0 的 skinIndex==5 表示旧自定义槽，V3 六色后槽位后移到 [skinCount]。
 * 返回 (有效 skinIndex, 新 schema)；非法值回退雾蓝 1。
 */
internal fun migrateSkinIndex(schema: Int, stored: Int, skinCount: Int): Pair<Int, Int> {
    var slot = stored
    if (schema < 1 && slot == 5) slot = skinCount
    val valid = if (slot in 0..skinCount) slot else 1
    return valid to if (schema < 1) 1 else schema
}

