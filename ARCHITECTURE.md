# Echo / 回声 架构说明

> 适用版本：1.0.6（versionCode 7）。版本号唯一来源是 gradle.properties 的 echo.versionCode / echo.versionName。本文描述当前代码，不是承诺；路径相对 app-native/。

相关文档：能力与限制见 [README.md](README.md)；上游协议见 [docs/protocol-compat.md](docs/protocol-compat.md)；构建、测试与提交规范见 [CONTRIBUTING.md](CONTRIBUTING.md)；明确边界见 [docs/not-doing.md](docs/not-doing.md)；性能与发布基线见 README.md 与 GitHub Actions 记录。

## 1. 运行时形态

- 单 Activity（MainActivity，19 行）：enableEdgeToEdge() 后 setContent { EchoApp(remember { AppState(applicationContext) }) }。
- 无 WebView、无自建后端、无第三方网络库（HttpURLConnection + 手写 SSE）；无 ViewModel、无 DI 框架、无导航库。
- 应用代码不使用协程处理网络与持久化：每个流式请求一个 Thread，持久化走单线程 Executor，回调统一回投主线程 Handler。Compose 内部的协程只用于 effect / 动画。
- 全部 UI 状态集中在 AppState（State.kt，约 2.8k 行）；Compose 直接观察它的 mutableState 与 SnapshotStateList。

## 2. 模块地图

文件 | 职责 | 关键点
MainActivity.kt（28） | 入口 | 只做 setContent
EchoApp.kt（356） | 应用壳/导航/主题切换动画/覆盖层 | AnimatedContent 在 Welcome 与 ChatScreen 间切换；抽屉、设置、主题、用量、答疑、导入向导都是同层覆盖，无路由库
ChatScreen.kt（463） | 会话页 | 上下文条、导出入口、复活横幅、摘要卡、贴底跟随滚动
MessageViews.kt（369） | 消息渲染 | 思考折叠、流式光标、分块渲染、计费行、朗读入口、来源
Composer.kt（352） | 输入区 | 发送/暂停、深度思考/联网、模型标签、语音输入
WelcomeScreen.kt（145） | 欢迎页 | 品牌 mark、继续上次、导入入口
Drawer.kt（706） | 会话抽屉 | 标题/联想/内容搜索、对话保留面板、失效分组
SettingsScreen.kt（622） | 设置与关于 | Key 显示保护、模型、最大输出、系统提示词、语音、外观、导入
HelpDialogs.kt（518） | 答疑与引导 | 12 条 FAQ、4 级导入向导、通用计费确认
UsageSheet.kt（224） | 用量与费用 | 官方余额、TTS/ASR 账本、生成花费
State.kt（2789） | 唯一状态源 | 持久化、导入、复活、本机摘要、计费估算、搜索索引
SessionPersistence.kt（83） | 落盘策略 | 索引必写/节流、加载门禁、脏标记代数、恢复对账、坏索引合并（纯 JVM 可单测）
TombstoneStore.kt（73） | 墓碑存储 | 损坏返回 null 且保留原文件、磁盘为底 merge、原子写（纯 JVM 可单测）
Models.kt（220） | 数据模型 | Msg/Session/快照/索引/懒加载 MessageList
Api.kt（268） | 网络与流式 | 请求体、SSE 读取循环、错误映射
Sse.kt（73） | 协议解析 | 纯函数 parse(event, json)，可 JVM 单测
Secret.kt（113） | KeyVault | Android Keystore + AES-256-GCM
Billing.kt（243） | 计费 | 价格常量、用量账本、余额接口
Speech.kt（742） | 语音 | TTS 状态机与流式播放、ASR 录音与识别
TtsAudio.kt（165） | 音频输出层 | 半带 FIR 升采样、AudioTrack 封装、播放尾等待（可 JVM 单测）
Export.kt（580） | 导出引擎与面板 | 单会话 Markdown / 全会话 ZIP、会话页导出面板；已接入
Design.kt（440） | 设计系统 | 尺寸/颜色/组件、EchoMark（Design.kt:115）
Theme.kt（577） | 主题 | 外观、强调色、Oklab 色阶与过渡
Lightness.kt（99） | 感知明度 | CIE L* 0–100 ↔ HSL 二分反解；HSL 转换自实现，JVM 可单测
ThemeSheet.kt（1090） | 主题面板 | 预设、浅/深两套自定义底色（L* 旋钮、玻璃盘与霓虹色带、光珠指示）、HSL 强调色
Glyphs.kt（300） | 图标字形 | 纯 Compose 绘制，不依赖图片资源
Markdown.kt（1640） | Markdown / LaTeX 渲染 | 自研子集解析 + Compose 渲染、代码块与公式布局（流式稳定段增量解析、未闭合尾行按纯文本）
Format.kt（43） | 小工具 | 时间/金额格式化、Modifier.rise
Motion.kt（24） | 动效参数 | 时长与缓动常量
Browsers.kt（88） | 外链 | 优先已知浏览器，避免裸 WebView 壳

合计 30 个 Kotlin 文件、13432 行（随提交变化）。

## 3. 状态与持久化

存储布局（appContext.filesDir）：

    store/index.json        会话索引（IndexRow 列表），列表页只靠它渲染
    store/msg/<sessionId>.json  单会话快照（SessionSnapshot：摘要、覆盖条数、主题块、消息列表）
    sessions.json           0.x 旧格式，仅在必要时迁移；迁移后改名 store/legacy-sessions.json.bak

- 索引 IndexRow 字段：id / title / updatedAt / expired / revived / contextMode / summaryCovered / imported / pinned / costCny / roles / chars。列表分区、排序、搜索只读索引，不解析正文。
- 写入：主线程只生成快照；io 线程用 writeAtomic（唯一临时文件名 `文件名.<nanoTime>.tmp` + flush / fsync + rename 原子替换）写 msg/<id>.json，索引行也在 io 线程构建后写 index.json，索引写入按 indexDirty 节流：新建/删除/导入等结构变更立即写；标题、消息增长等行内变更最多每 30s 写一次；启动索引尚未加载完成时禁止写索引，加载后补一次完整强制写。保存有防抖：正常模式 700ms、性能模式 260ms，dirtyIds 记录待写会话；写失败把 id 放回 dirtyIds、索引失败置 forceIndexNext 下次重试；停止生成是终态，立即 save() 而不是等防抖，思考流按 2s 节流 markDirty 落盘。charCount 增量维护，落盘时不再扫全文。
- 启动：index.json 存在则 loadIndexAsync() 秒开列表；否则 migrateLegacyAsync() 把旧 sessions.json 拆成新格式。
- 懒加载：MessageList 在 ready=false 时用 roleSeq 字符串撑出占位项；ensureReadyForUi() 对 ≤48KB 的快照在主线程同步解析并即时刷新，更大的快照不阻塞主线程：登记 parsingIds 后在 io 线程完成 JSON→消息列表与摘要解析（ParsedRecord），回主线程 applyParsedRecord 只发布结果（异步结果不会覆盖本地新消息，见 Models.markReady 守卫）；send / revive / retryLast 在正文未 ready 时只提示「稍候」，不再同步解析大会话；常驻内存会话上限 6 个（性能模式 12 个），按最近使用顺序淘汰。
- 容错：writeAtomic 保证单文件不写半截；进程被杀最多丢最后一次防抖窗口内的增量；Key 解密失败按 lost 处理（删除损坏密文，仅内存可用）。

## 4. Key 与加密

- KeyVault（Secret.kt）：AndroidKeyStore，AES-256-GCM，IV 12 字节，TAG 128 bit，别名 echo.apiKey.v1。
- 磁盘上只有 "v1:" + base64(iv || ciphertext)，存放在 SharedPreferences 的 continue-native/apiKey；prefs 里其他键（主题、字号、模型、maxTokens、运行模式等）不加密。
- 旧版明文 Key 第一次读取时自动迁移为密文；Keystore 不可用时删除明文、只保留在内存。
- UI 层：Key 默认掩码且不可编辑，连点 3 次「显示」才短暂展示；展示期间窗口加 FLAG_SECURE 禁止截屏。会话与附件同样只保存在本机（卸载即删）。

## 5. 一次发送的完整链路

1. Composer 调 State.send(text)。
2. 守卫：正在流式 → 提示先停止；会话失效未复活 → 提示复活；无 API Key → 打开设置。
3. 无会话则新建，标题取正文前 24 字；插入 user 消息和空 assistant 占位；联网开启时 effectiveModel 强制 deepseek-flash，否则用所选模型。
4. system 提示词每轮都发：官方 Messages API 无状态、不携带上次上下文，因此 send 时对 systemPrompt 做 trim，非空则随每轮请求发送（空白则不发送，省无效 token）。
5. State.buildHistory 组装 ApiMsg：摘要模式下把「更早对话的摘要」拼进尾部第一条 user 消息；深度思考开启且历史里存有 thinking 签名时，assistant 消息以 thinking + text 两个 content 块回传（官方扩展思考要求签名可校验）。
6. ApiClient.stream 在独立 Thread 中 POST /anthropic/v1/messages；max_tokens 取 state.maxTokens（默认 32,768，钳制在 4,096–65,536）；flushMs 正常 16、性能 33。
7. SSE 循环逐行读 event: / data:，空行触发 Sse.parse（Sse.kt:24）；文本与思考先写入缓冲，累计 4096 字节（FLUSH_BYTES）或距上次 flush 超过 flushMs 才回投主线程，降低重组频率；signature、citation、usage、stop_reason 直接回投。
8. State 的正文发布：用 Choreographer 帧回调合并，同一帧只 publish 一次（正常 16ms、性能 33ms 上限），bumpRevisionThrottled 触发列表刷新；终态先 removeFrameCallback 再强制 publish。
9. usage 合并：message_start 的预填值按 maxOf 合并；message_delta 的全量值覆盖。输入侧 token = input_tokens + cache_read_input_tokens + cache_creation_input_tokens。
10. 结束：onDone 收敛 thinkingMs、写入 stopReason、finishUsage 累加 session.costCny、markDirty + save；onError 也调用 finishUsage，中断轮次的已生成内容同样入账。stop_reason 为 max_tokens 时提示已达到输出上限；用户暂停时提示已保留当前内容。
11. 失败：onError 保留已生成内容并在尾部加「[中断] 原因」，reply.failed=true，UI 提供重试；重试先做与发送相同的守卫（流式中 / 会话失效 / 无 Key 直接提示），通过后才删掉最后一条 assistant 并重发；发送失败会回滚消息并提示（State.send）。
12. 取消：StreamHandle.cancel() 置 volatile 标志并 disconnect 连接；客户端没有「通知服务端取消」的协议，只是断开 SSE。

没有自动重试、没有指数退避：失败后的重试由用户点击触发，避免不可见的重复计费。

## 6. 线程模型

- 主线程：Compose 组合、AppState 的所有可变状态（sessions、currentId、streaming、notice 等）、UI 回调。
- 网络线程：ApiClient 每请求一个 Thread，阻塞读 SSE；所有回调通过 Handler 回主线程。
- 持久化线程：Executors.newSingleThreadExecutor 串行执行写文件、解析快照、导入 ZIP、迁移旧数据、内容搜索、导出。
- 语音线程：Speech.Reader 的 AudioTrack 播放循环、Recorder 的 AudioRecord 采集循环各自在后台线程，状态回主线程。
- 账本：UsageLedger 使用 SharedPreferences.apply() 持久化，在主线程调用，量小、非关键路径。
- 约束：io 线程不得直接改 AppState；需要改状态时用 main.post。网络回调只能通过 onText/onThinking/onUsage 等回调入口进入 State。

## 7. 本机摘要与上下文

- 触发：失效会话点「前部摘要 + 后部原文」且总消息数 > 40 才生成本机摘要；否则退回完整原文。
- 参数：保留最近 40 条原文；其余按 summaryRanges 分块，目标块数 (n/10).coerceIn(1,10)，最多 12 块；每块最多 5 条要点，预算 (3600/块数).coerceIn(320,820) 字；块标题取主题首句。
- 纯本地规则：不调用 API、不额外消耗 token；原文仍保存在本机，摘要卡里可展开查看每个主题块的原文（若原文已被清理会明确提示）。
- 上下文条 token 是估算：chars / 2.5 + 12，不是官方 tokenizer；流式时每 500ms 叠加当前回复长度。UI 明确写「本地估算」。
- 费用估算：按公开牌价与高峰/低谷时段计算，写入每条消息与每个会话；最终以官方账单为准。

## 8. 导入与导出

导入（State.importZip 等）：

- 入口是 SAF OpenDocument，接受 application/zip 与 application/octet-stream；不申请存储权限。
- 只读取 ZIP 内 chat_sessions/*.json；按 current_message_id → parent_id 回溯当前分支再反转成时间顺序；FILE 片段只保留文件名（正文为空时拼「[附件：a、b]」）；空消息跳过。
- expired 判定：now - updatedAt > 7 * 24 小时；导入后不自动复活，由用户逐条选择。
- 每解析 25 个会话更新一次进度；解析结果立即写 msg/<id>.json，最后 finalizeImport 在主线程合并。
- 合并语义：按会话 id 去重，本机已有同 id 会话时保留本机版本，不覆盖；整体按 updatedAt 降序。注意：导入向导文案写「导入会覆盖本机现有对话」，与当前实现不符，待后续统一文案或实现。
- 官方导出 ZIP 不含附件二进制，所以图片/文件不会重传。

导出（Export.kt + 会话页入口，已接入）：

- 会话页上下文条右侧「导出」打开 ExportSheet：可导出当前会话 Markdown（CreateDocument text/markdown），或全部会话 ZIP（application/zip，包内先 index.md 再逐会话）。
- 引擎提供纯渲染函数 renderSessionMarkdown / writeIndexMarkdown，以及回调式 writeSessionMarkdown / writeZipStream（避免把几十 MB 会话拼成一个字符串），通过 SAF Uri 写文件（exportSessionToUri / exportAllToUri），不申请存储权限。
- 导出当前会话先 snapshotSession 生成内存快照；正文尚未加载完时提示「会话内容还在加载，请稍候再导出」；写文件在 Dispatchers.IO 上执行，结束后用 notice 反馈条数或失败原因。
- 读取历史原文按 msgFile 命名规则读 files/store/msg，该规则是私有实现，若 State.kt 改动需同步；大会话峰值内存约等于单个最大会话。

## 9. 渲染与性能策略

- LazyColumn 的 key 优先用消息 id，未就绪时用 stub 索引；animateItem 用于非流式消息；流式 live 项跳过，避免每个 token 触发插入动画。
- 贴底跟随：followTail 由三处决定——用户拖动（interactionSource 的 DragInteraction.Start）暂停；滚动停止时用 shouldFollowTail（最后可见项是否已是尾项且末端 gap ≤2px）重算；发送成功时 sendTick 自增强制回底 sendScrollTarget(size-1)。流式中若最后可见项不再是尾项，先按 tailRescueIndex 把尾项滚入视口，之后 withFrameNanos 每帧计算内容末端与视口的 gap、>0 才 scrollBy；程序化滚动不产生 DragInteraction，不会被误判为用户上滑。
- 流式正文：StreamSplit 只在代码围栏 / 块级公式之外的空行或围栏闭合处提交稳定段并增量解析；未闭合尾部按全文纯文本绘制（超过 2400 字符按 1200 字符分块；全文分块不切断代理对，流式稳定前缀边界遇高代理项会回退一格），稳定前缀按字符串缓存，未提交尾部只缓存已写满的 1200 字符块、每个 token 只重排最后一段增长尾部；回答完成后整体 Markdown 排版。思考流式只渲染尾部 1400 字，折叠时限制最大高度。
- 抽屉：computeDrawerView 用 derivedStateOf 缓存分区、排序与搜索结果；内容搜索延迟 180ms 防抖后在 io 线程遍历，结果按标题命中/内容命中分组。
- 主题：颜色是全局静态 token，切换时一次性写入 themeProgress / eyeProgress，不再逐帧插值全树（避免每个读色组件每帧重组）；过渡为旧底色垫底、新底色与内容一起在 graphicsLayer 内做 220ms 交叉淡入，系统栏图标在淡入中点翻转。
- Baseline Profile：androidx.baselineprofile 1.3.4 + baselineprofile 模块采集，release 内置 app/src/release/generated/baselineProfiles，运行时由 profileinstaller 安装；机器码由设备端 dex2oat 生成。
- 性能数据（0.9.2 真机实测，仅作参考）：冷启动中位 400ms，对比 verify 489ms，约 -18%；jank 中位约 2.2%。

## 10. 设计系统与品牌层

- Design.kt 提供设计 token、通用组件与 EchoMark 品牌标记；Glyphs.kt 用 Compose 画图标字形，不依赖位图。
- Theme.kt：外观 Look = PAPER（日间）/ INK（夜间）/ WARM（暖黄）/ GREEN（淡绿）/ CUSTOM（自选：浅色·日间 / 深色·夜间两套各自保存）；6 种强调色（沙金/雾蓝/黛青/陶土/灰紫/靛蓝）以六张真实欢迎页预览呈现，四档外观下实时重绘；夜间底色 #050608，日间 #F4F2ED；暖黄与淡绿只在日间、由 eyeMode 控制。自选底色分浅色·日间与深色·夜间两套，各存色相 / 饱和度 / CIE L*（0–100 感知明度；Lightness.kt 固定 H/S 后二分反解 HSL lightness，滑杆读数与存储都是 L*）；每套由 CustomSurface 从底色派生 void / panel / panelHigh / hair / controlBorder / ivory / muted 等表面，isDark 取纸白与墨黑中对比更高的一侧，深色底面板提亮、浅色底面板压暗并各自把文本对比度校正到 ≥4.7；打开自选时保持当前日夜，点 tab 整屏切到对应一套，旧单色 pref 启动时迁入匹配的一套。系统栏图标按 isDarkBackground（自选表面或 !isDay）取亮 / 暗。强调色拆三角色：填充用 accent（日/夜各一值），日间小字用 accentText（命中预设时取 dayText，否则同时对照纸白 / 面板 / 面板高亮 / 16% 强调色叠底，在最坏底色上自动加深到 ≥4.7），填充上的字用 onAccent（在近黑与纸白中取对 accent 对比度更高者）；夜间 accent 主要承担填充，小字同样经 accentText 按最坏底色校验。自定义强调色在写入前先过 readableAccent：若 onAccent 对原色已达 4.7 则原样保留，否则按 ink / paper 哪侧更优，向黑或白逐 6% 迭代压深 / 提亮（上限 32 步），中灰也不会出现“黑字压不住、白字读不出”的两难；选中边框与勾选统一用 accentText，非文本对比度 ≥ 3.0。
- Android launcher 图标在 app/src/main/res/mipmap-*/ic_launcher* 与 adaptive 资源里，当前是蓝白命令提示符（1.6:1 圆角瓦片，提交 ccdb156）；应用内 EchoMark 与 launcher mark 是两套绘制，需保持形状语言同源。
- 文案语气：短句、克制、不带官方背书；欢迎页「欢迎回来，随时续上。」，关于页 slogan「把没说完的话，接回来。」，输入区页脚「内容由 AI 生成，请仔细甄别 · 数据只保存在本机」。

## 11. 测试与验证

- JVM 单测 158 例：SseTest（协议解析与容错）、ProtocolTest（请求体与 SSE 循环回归）、BillingTest（计费与切分）、FormatTest（格式化）、ExportTest（导出渲染）、MarkdownTest（解析、流式切段、代理对边界与 8K 单行扫描推进，36 例）、TtsAudioTest（48k 半带重采样与尾等待）、FollowTailTest（贴底跟随判定、救援与发送回底目标，6 例）、SessionPersistenceTest（索引必写、加载门禁、脏标记代数、恢复对账、墓碑剔除、坏索引合并与墓碑回滚与持久化过滤 persistableIds，13 例）、TombstoneStoreTest（墓碑损坏返回 null 并保留原文件、round-trip、磁盘为底 merge、remove 保留其他、加载清理 merge，6 例）、ThemeContrastTest（六强调色日/夜小字与自定义极端色对比度均 ≥4.7、任意灰阶经 readableAccent 归一后填充字仍 ≥4.7、预设色不被改写、选中 pill 文字、HelpDialogs 勾选、预设预览卡边框按卡底取前景、选中标签与边框、预设自映射、旧 skinSchema 迁移幂等、16% 强调色叠底与选中态非文本 ≥3.0、浅/深两套自定义底下的表面明暗与文本对比度、L* 与 H/S 独立性，19 例）、LightnessTest（L* 换算与二分反解、色相/饱和度保持、极端值、三条旋钮色带采样命中目标 L* 与 L* 带单调，7 例）。命令见 CONTRIBUTING.md。
- 本地协议联调：scripts/mock_sse_server.py 提供 normal / truncated / error_sse / 429 / 500 五种模式，默认 18080 端口；debug 构建加 -PapiOrigin 指向它。
- 构建验证：gradle :app:compileDebugKotlin（日常）；release 构建、签名与 Baseline Profile 见 README.md。
- CI：.github/workflows/ci.yml（GitHub Actions）依次跑 Kotlin 编译、JVM 单测与 scripts/mock_sse_server.py --selftest，环境为 JDK 17 / Gradle 8.9 / Android SDK 35。

## 12. 已知架构债（按影响排序）

1. State.kt 单文件约 2.6k 行，承担状态、持久化、导入、摘要、计费估算与搜索，继续增长会有维护成本。
2. Markdown / LaTeX 渲染层（Markdown.kt）已接线到 MessageViews.AnswerText，但为手写子集：不支持表格 / 图片 / 嵌套列表，块级公式有布局开销；流式时未闭合尾行按纯文本渲染，完成后整体解析。
3. 导入只保存附件文件名，不重传二进制；重复导入依赖会话 ID 去重（FAQ 与向导文案已改为「合并、已存在跳过」口径）。
4. launcher 图标已换成蓝白命令提示符（1.6:1 圆角瓦片，提交 ccdb156）；应用内 EchoMark 与 launcher 仍是两套绘制，未来需保持同源。
5. 无仪器测试 / 截图回归；协议回归靠 JVM 单测 + mock server，外加一轮真实 Key 人工冒烟（见 docs/protocol-compat.md 第 12 节）。
6. 无 ViewModel/DI：进程重建后靠磁盘索引恢复，恢复期间列表用 stub 占位。

## 13. 修改入口速查

想改什么 | 去哪里
上游请求字段 / 超时 / 错误文案 | Api.kt，同时更新 docs/protocol-compat.md
SSE 事件解析 | Sse.kt + SseTest.kt
保存格式与索引 | State.kt（serializeSnapshot / indexJson / writeAtomic）+ Models.kt
Key 加密 | Secret.kt
价格、账本、余额 | Billing.kt / UsageSheet.kt
输出上限 | ApiClient.MIN/MAX/DEFAULT_MAX_TOKENS + State.maxTokens + SettingsSheet「最大输出」分段
TTS / ASR | Speech.kt + 计费确认文案
本机摘要、上下文估算 | State.kt（buildLocalSummary / summaryRanges / refreshContextTokens）
导入 ZIP | State.kt（importZip / parseSession / finalizeImport）
导出 | Export.kt（引擎 + ExportSheet），入口在 ChatScreen 上下文条；大文件走 SAF，不拼整串
视觉与动画 | Design.kt / Theme.kt / ThemeSheet.kt / Motion.kt
品牌标记 | Design.kt 的 EchoMark 与 res 下的 launcher 资源

## 14. 架构约束（改动前先确认不越过）

- 不引入 WebView，不做内嵌官方网页壳。
- 不引入自建后端、中转或云同步；用户 Key 与会话不出本机。
- 不加第三方网络库；协议层保持可单测。
- 不代理官方登录、不绕过人机验证、不调用未公开接口。
- 所有费用可核对：不预扣、不代收、不加价；新增计费能力必须先在 UI 明示。
- 版本号只改 gradle.properties；签名材料、local.properties 与构建产物不入库。
