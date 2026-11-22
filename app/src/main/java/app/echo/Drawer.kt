package app.echo

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class ContentHit(val matched: Int, val total: Int) {
    val ratio: Float get() = if (total == 0) 0f else matched.toFloat() / total
}

private data class DrawerView(
    val dormant: List<Session>,
    val warm: List<Session>,
    val pool: List<Session>,
    val filtered: List<Session>,
    val longDormant: List<Session>,
    val shortDormant: List<Session>,
    val warmSorted: List<Session>,
    val titleHits: List<Session>,
    val contentRows: List<Pair<Session, ContentHit>>,
)

internal val searchAliases: Map<String, List<String>> = mapOf(
    "复活" to listOf("恢复", "续上"),
    "恢复" to listOf("复活", "续上"),
    "出行" to listOf("旅行", "行程", "周末"),
    "行程" to listOf("周末", "安排", "规划", "旅行"),
    "周末" to listOf("行程", "安排", "规划", "休息"),
    "公式" to listOf("数学", "方程"),
    "内存" to listOf("ram", "运存"),
    "性能" to listOf("优化", "卡顿", "帧率"),
    "数学" to listOf("矩阵", "向量", "方程", "公式", "平方", "反比", "证明", "几何", "概率"),
    "物理" to listOf("力学", "磁场", "共振", "平衡点", "速度", "能量"),
    "历史" to listOf("历史", "朝代", "战争", "地缘", "政治"),
    "地理" to listOf("地缘", "地图", "国家", "边界", "世界"),
    "购物" to listOf("推荐", "价格", "品牌", "tf卡", "内存卡", "性价比"),
    "电脑" to listOf("配置", "硬件", "装系统", "python", "环境", "显卡"),
    "情感" to listOf("心理", "焦虑", "难过", "关系", "情绪", "自我"),
    "写作" to listOf("文章", "文案", "表达", "句子", "故事"),
    "工作" to listOf("职场", "求职", "面试", "简历", "同事"),
    "游戏" to listOf("minecraft", "我的世界", "mc", "游戏", "地图"),
    "音乐" to listOf("和弦", "钢琴", "吉他", "乐理", "旋律"),
    "旅行" to listOf("周末", "行程", "出行", "旅游", "城市"),
    "学习" to listOf("考试", "复习", "课程", "讲解", "公式"),
    "代码" to listOf("编程", "python", "kotlin", "agent", "脚本", "函数"),
    "编程" to listOf("代码", "python", "kotlin", "agent", "函数"),
    "健康" to listOf("睡眠", "焦虑", "身体", "医生", "药"),
)

internal fun fuzzyTitleScore(title: String, term: String): Int {
    if (term.isBlank()) return 0
    val lower = title.lowercase()
    val q = term.lowercase()
    if (lower.contains(q)) return 100
    if (q.length < 2) return 0
    var score = 0
    val chars = q.toSet()
    val hit = chars.count { lower.contains(it) }
    score = maxOf(score, hit * 50 / chars.size)
    var bigrams = 0
    for (i in 0 until q.length - 1) if (lower.contains(q.substring(i, i + 2))) bigrams++
    score = maxOf(score, bigrams * 60 / (q.length - 1))
    var j = 0
    for (c in lower) { if (j < q.length && c == q[j]) j++ }
    if (j == q.length) score = maxOf(score, 45)
    return score
}

internal fun searchTerms(query: String, associative: Boolean): List<String> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty() || !associative) return terms
    return terms.flatMap { t -> listOf(t) + (searchAliases[t] ?: emptyList()) }.distinct()
}

internal fun searchSessions(pool: List<Session>, query: String, associative: Boolean): List<Session> {
    val q = query.trim()
    val base = compareByDescending<Session> { it.pinned }.thenByDescending { it.updatedAt }
    if (q.isBlank()) return pool.sortedWith(base)
    val terms = searchTerms(q, associative)
    if (!associative) {
        return pool.filter { s -> terms.all { s.title.contains(it, ignoreCase = true) } }.sortedWith(base)
    }
    return pool.mapNotNull { s ->
        var best = 0
        for (term in terms) best = maxOf(best, fuzzyTitleScore(s.title, term))
        if (best >= 28) s to best else null
    }.sortedWith(
        compareByDescending<Pair<Session, Int>> { it.second }
            .thenByDescending { it.first.pinned }
            .thenByDescending { it.first.updatedAt }
    ).map { it.first }
}

@Composable
internal fun SearchModeTab(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 34.dp)
            .border(1.dp, if (selected) accent.copy(alpha = .55f) else C.Hair, RoundedCornerShape(17.dp)),
        shape = RoundedCornerShape(17.dp),
        scale = .95f,
        description = label,
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) accent else C.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun DrawerSectionHeader(
    title: String,
    count: Int,
    note: String = "",
    expanded: Boolean? = null,
    accent: Color,
    onToggle: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = C.Muted, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = C.Muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (onToggle != null && expanded != null) {
                Text(if (expanded) "收起" else "展开", color = accent, fontSize = 11.sp)
            }
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(note, color = C.Muted.copy(alpha = .78f), fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

internal fun fmtRemaining(updatedAt: Long, now: Long): String {
    val window = 7L * 24L * 60L * 60L * 1000L
    val left = window - (now - updatedAt)
    if (left <= 0L) return "即将被清理"
    val hours = left / 3_600_000L
    if (hours >= 24L) {
        val days = (left + 86_400_000L - 1L) / 86_400_000L
        return "还剩约 " + days + " 天"
    }
    return "还剩约 " + hours.coerceAtLeast(1L) + " 小时"
}

@Composable
internal fun DrawerSessionRow(
    session: Session,
    state: AppState,
    accent: Color,
    expiredMode: Boolean,
    now: Long,
    hitLabel: String = "",
) {
    Pressable(
        onClick = { state.open(session) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        scale = .98f,
        description = session.title.ifBlank { "新对话" },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { "新对话" },
                    color = if (session.id == state.currentId) accent else C.Ivory,
                    fontSize = 15.sp, maxLines = 1,
                )
                Text(
                    (if (session.pinned) "置顶 · " else "") +
                        session.messages.userTurns.toString() + " 轮 · " + fmtTime(session.updatedAt) + " · " +
                        when {
                            expiredMode && session.expired -> "已失效"
                            expiredMode -> "还有时间"
                            session.expired && session.revived -> "已恢复"
                            session.expired -> "已失效"
                            session.imported -> "本地"
                            else -> "可用"
                        },
                    color = C.Muted, fontSize = 11.sp,
                )
                if (hitLabel.isNotBlank()) {
                    Text(hitLabel, color = C.Muted.copy(alpha = .82f), fontSize = 10.sp)
                }
            }
            if (expiredMode) {
                if (session.expired) {
                    Text("复活", color = accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                } else {
                    Text(fmtRemaining(session.updatedAt, now), color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            } else {
                Pressable(
                    onClick = { state.delete(session) },
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    scale = .9f,
                    description = "删除会话",
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CloseGlyph(Modifier.size(11.dp), C.Muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DrawerPanel(
    state: AppState,
    accent: Color,
    contentSortByRatio: Boolean,
    onContentSortChange: (Boolean) -> Unit,
    reviveSort: Int,
    onReviveSortChange: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expiredOnly by remember { mutableStateOf(false) }
    var associative by remember { mutableStateOf(false) }
    var longExpanded by remember { mutableStateOf(false) }
    var shortExpanded by remember { mutableStateOf(false) }
    var warmExpanded by remember { mutableStateOf(false) }
    var contentHits by remember { mutableStateOf<Map<String, ContentHit>>(emptyMap()) }
    var contentSearching by remember { mutableStateOf(false) }
    val panelW = (LocalConfiguration.current.screenWidthDp * .82f).coerceIn(320f, 420f)
    // The drawer recomposes on every keystroke while searching; these counts used
    // to walk all ~3,800 sessions twice per pass just to build the placeholder.
    val shownCount = remember(state.revision, expiredOnly) {
        if (expiredOnly) state.sessions.count { it.expired && !it.revived }
        else state.sessions.count { !it.expired || it.revived }
    }
    Surface(color = C.Panel, modifier = Modifier.fillMaxHeight().width(panelW.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp))
                if (expiredOnly) {
                    Pressable(
                        onClick = { expiredOnly = false; query = "" },
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        scale = .9f,
                        description = "返回可用会话",
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BackGlyph(Modifier.size(15.dp), C.Muted) } }
                    Spacer(Modifier.width(2.dp))
                    Text("对话保留", color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("会话", color = C.Ivory, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                TapTarget(size = 48.dp, visual = 40.dp, onClick = { state.showDrawer = false }, description = "关闭") {
                    CloseGlyph(Modifier.size(14.dp), C.Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        if (expiredOnly) "搜索 " + shownCount + " 个对话" else "搜索 " + shownCount + " 个会话",
                        color = C.Muted, fontSize = 13.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = C.Ivory, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = C.Hair,
                    focusedContainerColor = C.PanelHigh, unfocusedContainerColor = C.PanelHigh,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                SearchModeTab("关键词", !associative, accent) { associative = false }
                Spacer(Modifier.width(6.dp))
                SearchModeTab("联想", associative, accent) { associative = true }
            }
            Spacer(Modifier.height(6.dp))
            if (!expiredOnly) {
                Pressable(
                    onClick = { state.newChat() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    scale = .98f,
                    description = "开启新对话",
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(C.PanelHigh).padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlusGlyph(Modifier.size(13.dp), accent)
                        Spacer(Modifier.width(10.dp))
                        Text("开启新对话", color = C.Ivory, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val now = System.currentTimeMillis()
            // Partition/filter/sort ~3,800 sessions. This ran on every recomposition
            // (each keystroke, each contentHits update); cache it on the inputs that
            // actually change the result.
            val view = remember(
                state.revision, state.sessions, query, associative, expiredOnly,
                reviveSort, contentSortByRatio, contentHits,
            ) {
                val dormant = state.sessions.filter { it.expired && !it.revived }
                val warm = state.sessions.filter { !it.expired && !it.revived }
                val activePool = state.sessions.filter { !it.expired || it.revived }
                val pool = if (expiredOnly) dormant + warm else activePool
                val filtered = searchSessions(pool, query, associative)
                val reviveCmp: Comparator<Session> = when (reviveSort) {
                    1 -> compareByDescending<Session> { it.messages.roleSeq.length }.thenByDescending { it.updatedAt }
                    2 -> compareByDescending<Session> { it.charCount }
                        .thenByDescending { it.messages.roleSeq.length }
                        .thenByDescending { it.updatedAt }
                    else -> compareByDescending { it.updatedAt }
                }
                val contentRows = if (query.isBlank()) emptyList() else contentHits.entries.mapNotNull { entry ->
                    pool.firstOrNull { it.id == entry.key }?.let { it to entry.value }
                }.sortedWith(
                    if (contentSortByRatio) {
                        compareByDescending<Pair<Session, ContentHit>> { it.second.ratio }
                            .thenByDescending { it.second.matched }
                            .thenByDescending { it.first.pinned }
                            .thenByDescending { it.first.updatedAt }
                    } else {
                        compareByDescending<Pair<Session, ContentHit>> { it.second.matched }
                            .thenByDescending { it.second.ratio }
                            .thenByDescending { it.first.pinned }
                            .thenByDescending { it.first.updatedAt }
                    }
                )
                DrawerView(
                    dormant = dormant,
                    warm = warm,
                    pool = pool,
                    filtered = filtered,
                    longDormant = dormant.filter { it.messages.roleSeq.length >= 20 }.sortedWith(reviveCmp),
                    shortDormant = dormant.filter { it.messages.roleSeq.length < 20 }.sortedWith(reviveCmp),
                    warmSorted = warm.sortedWith(reviveCmp),
                    titleHits = if (query.isBlank()) emptyList() else filtered,
                    contentRows = contentRows,
                )
            }
            val dormant = view.dormant
            val warm = view.warm
            val pool = view.pool
            val filtered = view.filtered
            val longDormant = view.longDormant
            val shortDormant = view.shortDormant
            val warmSorted = view.warmSorted
            val titleHits = view.titleHits
            val contentRows = view.contentRows
            LaunchedEffect(query, associative, expiredOnly, state.revision) {
                if (query.isBlank()) {
                    contentHits = emptyMap()
                    contentSearching = false
                    return@LaunchedEffect
                }
                delay(180)
                contentSearching = true
                val terms = searchTerms(query, associative)
                val titleIds = searchSessions(pool, query, associative).mapTo(HashSet()) { it.id }
                val targets = pool.filter { it.id !in titleIds }
                val hits = withContext(Dispatchers.IO) {
                    val out = HashMap<String, ContentHit>()
                    targets.forEachIndexed { index, session ->
                        if (index % 24 == 0) delay(1)
                        val total = session.messages.size
                        if (total <= 0) return@forEachIndexed
                        val texts = state.messageTextsForSearch(session)
                        if (texts.isEmpty()) return@forEachIndexed
                        var matched = 0
                        for (text in texts) {
                            if (terms.any { text.contains(it, ignoreCase = true) }) matched++
                        }
                        if (matched > 0) out[session.id] = ContentHit(matched, total)
                    }
                    out
                }
                contentHits = hits
                contentSearching = false
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (query.isBlank()) {
                    if (!expiredOnly) {
                        if (filtered.isEmpty()) {
                            item {
                                Text("暂无可用会话", color = C.Muted, fontSize = 13.sp)
                            }
                        }
                        items(filtered, key = { it.id }) { session ->
                            DrawerSessionRow(session, state, accent, expiredOnly, now)
                        }
                    } else {
                        if (dormant.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SearchModeTab("按时间", reviveSort == 0, accent) { onReviveSortChange(0) }
                                    Spacer(Modifier.width(6.dp))
                                    SearchModeTab("按条数", reviveSort == 1, accent) { onReviveSortChange(1) }
                                    Spacer(Modifier.width(6.dp))
                                    SearchModeTab("按总字数", reviveSort == 2, accent) { onReviveSortChange(2) }
                                }
                            }
                        }
                        if (longDormant.isNotEmpty()) {
                            item {
                                DrawerSectionHeader(
                                    title = "已不活跃 · 长对话",
                                    count = longDormant.size,
                                    note = "已超过 7 天，本地可复活；默认折叠，避免占满列表。",
                                    expanded = longExpanded,
                                    accent = accent,
                                    onToggle = { longExpanded = !longExpanded },
                                )
                            }
                            if (longExpanded) {
                                items(longDormant, key = { it.id }) { session ->
                                    DrawerSessionRow(session, state, accent, expiredMode = true, now = now)
                                }
                            }
                        }
                        if (shortDormant.isNotEmpty()) {
                            item {
                                DrawerSectionHeader(
                                    title = "已不活跃 · 短对话",
                                    count = shortDormant.size,
                                    note = "较短对话，默认折叠。",
                                    expanded = shortExpanded,
                                    accent = accent,
                                    onToggle = { shortExpanded = !shortExpanded },
                                )
                            }
                            if (shortExpanded) {
                                items(shortDormant, key = { it.id }) { session ->
                                    DrawerSessionRow(session, state, accent, expiredMode = true, now = now)
                                }
                            }
                        }
                        if (warmSorted.isNotEmpty()) {
                            item {
                                DrawerSectionHeader(
                                    title = "还有时间 · 未失效",
                                    count = warmSorted.size,
                                    note = "去 DeepSeek 官方 App 续一句，即可重置 7 天计时。",
                                    expanded = warmExpanded,
                                    accent = accent,
                                    onToggle = { warmExpanded = !warmExpanded },
                                )
                            }
                            if (warmExpanded) {
                                items(warmSorted, key = { it.id }) { session ->
                                    DrawerSessionRow(session, state, accent, expiredMode = true, now = now)
                                }
                            }
                        }
                        if (dormant.isEmpty() && warm.isEmpty()) {
                            item {
                                Text("暂无需要保留的对话", color = C.Muted, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    if (titleHits.isNotEmpty()) {
                        if (contentRows.isNotEmpty() || contentSearching) {
                            item {
                                DrawerSectionHeader("标题命中", titleHits.size, accent = accent)
                            }
                        }
                        items(titleHits, key = { "t-" + it.id }) { session ->
                            DrawerSessionRow(session, state, accent, expiredOnly, now)
                        }
                    }
                    if (contentSearching) {
                        item {
                            Text("正在搜索对话内容…", color = C.Muted, fontSize = 12.sp)
                        }
                    }
                    if (contentRows.isNotEmpty()) {
                        item {
                            DrawerSectionHeader(
                                title = "对话内容命中",
                                count = contentRows.size,
                                note = "可按消息条数或占比排序。",
                                accent = accent,
                            )
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SearchModeTab("按条数", !contentSortByRatio, accent) { onContentSortChange(false) }
                                Spacer(Modifier.width(6.dp))
                                SearchModeTab("按占比", contentSortByRatio, accent) { onContentSortChange(true) }
                            }
                        }
                        items(contentRows, key = { "c-" + it.first.id }) { pair ->
                            val (session, hit) = pair
                            DrawerSessionRow(
                                session = session,
                                state = state,
                                accent = accent,
                                expiredMode = expiredOnly,
                                now = now,
                                hitLabel = hit.matched.toString() + "/" + hit.total + " 条消息命中 · " + (hit.ratio * 100).roundToInt() + "%",
                            )
                        }
                    }
                    if (titleHits.isEmpty() && contentRows.isEmpty() && !contentSearching) {
                        item {
                            Text(
                                if (associative) "没有联想到相关会话" else "没有匹配的会话",
                                color = C.Muted, fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!expiredOnly) {
                Pressable(
                    onClick = { expiredOnly = true; query = "" },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    scale = .98f,
                    description = "查看对话保留，复活已失效对话",
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("对话保留", color = C.Muted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (dormant.isNotEmpty()) {
                            Text(dormant.size.toString() + " 个已不活跃", color = C.Muted, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("复活", color = accent, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Pressable(
                onClick = { state.openHelp() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                scale = .98f,
                description = "打开答疑：API Key、对话文件、复活与计费",
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("答疑", color = C.Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("API Key · 文件 · 复活", color = C.Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Pressable(
                onClick = { state.showDrawer = false; state.openSettings() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                scale = .98f,
                description = "设置",
            ) {
                Text("设置", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
            }
        }
    }
}
