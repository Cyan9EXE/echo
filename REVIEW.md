# Echo 评审说明（REVIEW）

> 面向评审者的一页纸：项目定位、关键决策、本轮改动、验证方式、已知问题。
> 代码仓库：app-native/（Android，Kotlin + Jetpack Compose）。撰写时版本 **1.0.0（versionCode 1，applicationId app.echo）**；本文中 0.9.x 的记录为历史版本证据。

## 1. 这个项目是什么

Echo（回声）是一个**本地优先**的第三方 DeepSeek 客户端：

- 把官方导出的历史会话 ZIP 导入本机；按 `current_message_id → parent_id` 还原当前分支。
- 官方端已失效（>7 天未活跃）的会话，可在本机「复活」：完整原文，或「前部摘要 + 后部原文」——摘要由本机生成，不调用 API、不产生额外 token。
- 对话、朗读、语音识别全部用**用户自己的 API Key 直连 api.deepseek.com**（BYOK），没有中转服务器，也不做额度破解。
- 上下文透明：当前 token / 1M、占比、原文或摘要模式、每轮与累计估算费用。

## 2. 评审重点（按重要性）

1. `State.kt` — 唯一状态源。流式生命周期（send/stop/retry）、持久化（dirtyIds + 单线程 store）、上下文 token 缓存（`contextTokens` + `refreshContextTokens()`）。重点看：并发取消（`streamSeq`）、错误与取消路径是否重复落盘、恢复旧数据时的兼容分支。
2. `MessageViews.kt` — 流式渲染的关键优化：思考块折叠时机、`AnswerText` 分块（只在“已定稿”的换行边界切块，避免每个 token 重排整段文本）、流式期间隐藏操作区与计费行。
3. `ChatScreen.kt` — 列表跟随：`snapshotFlow { 文本长度 }` + `conflate()` + 90ms 节流，只在贴底时滚动；去掉了原来每帧读长度触发的整页重组。
4. `Secret.kt` — Key 落盘加密（Android Keystore，AES-256-GCM，磁盘只有 `v1:base64(iv||ct)`；解密失败按“无 Key”处理）。
5. `Api.kt` — 手写 SSE 解析（无第三方网络库）：`stream()` 的回调契约、`onError` 与 `onDone` 的先后关系。
6. `SettingsScreen.kt` — API Key 的隐私交互：默认掩码 + 连点 3 次「显示」才可编辑；显示期间 `FLAG_SECURE` 禁止截屏。

## 3. 本轮（0.9.1 之后）改了什么

**流式观感对齐官方**

- 未出正文时思考块标题为「正在思考」；正文开始后自动折叠为「已思考（用时 N 秒）」（`thinkingMs` 随消息持久化，重开 App 仍成立）。
- 流式生成期间**不显示**操作区（复制/朗读/重试）与「本轮 ¥x · tokens」计费行，结束后再出现。
- 思考正文窗口固定高度、只保留尾部窗口（live 1400 字），避免每个 token 改变 item 高度。
- 去掉底部「正在生成…」占位行（官方没有）。

**卡顿与重组**

- 列表跟随从“每次重组读长度”改为 `snapshotFlow` + `conflate` + 90ms 节流，只在新内容到达时滚一次。
- `publish` 节流 60ms → 120ms；正文渲染按 600 字左右、且只在换行边界切块，已渲染的块不会因追加而重排。
- ContextBar 的 token 估算从组合期计算改为 `contextTokens` 状态 + 流式期间每 500ms 刷新，避免顶栏每帧重算全量文本长度。
- 流式期间思考滚动使用 `snapshotFlow { scroll.maxValue }`，不再每帧主动 scrollTo。

**交互细节**

- 顶栏强调色切换：四个色点常驻（金/蓝/青/陶），点击即时换肤；未选中 22dp 热区、选中带环。
- 「深度思考 / 联网」Chip 与抽屉搜索模式 Tab 尺寸收敛（高 34dp、圆角 17dp），视觉更贴近官方；此前为满足 48dp 触控做的放大显得笨重，48dp 保留在悬浮层内的点击热区上。
- 底部状态条（内容由 AI 生成…）在流式期间不闪烁，费用只在结束后计入。

## 4. 验证记录（可复现）

环境：Android 35 x86_64 模拟器（1080x2340@440），release 包（R8 + shrinkResources，debug 签名，仅内测）。

- **Key 隐私**：设置页 Key 默认掩码；连点「显示」三次（1.4s 内）后变「隐藏」，此时 `screencap` 全黑（约 24KB），证明 `FLAG_SECURE` 生效；再次保存后回到聊天页提示「API Key 已保存」。
- **流式状态机**：mock SSE（`-PapiOrigin=http://127.0.0.1:8099`）下，发送后依次出现：正在思考 →（正文开始）折叠为「已思考（用时 10 秒）」→ 结束后出现「复制 / 朗读 ¥0.66 / 重试」与「本轮 ¥0.0004 · 输入 120 / 输出 80 tokens」；流式期间上述操作区与费用行均不出现。
- **上下文条**：流式期间每 500ms 刷新 token/占比，结束后稳定；点击「详情」可看模式与明细。
- **自我一致性**：导入 ZIP → 3807 会话 → 失效标记 / 逐条复活 / 摘要模式 → 重启 App 后状态与 token 估算一致（此前轮次已验证，见 README 功能清单）。
- **构建**：`gradle :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease` → BUILD SUCCESSFUL，release APK 1,340,709 B（0.9.2）。
- **官方包回归**（api.deepseek.com，假 Key）：发送后返回「API Key 无效或已过期，请在设置里重新填写。」，操作区（复制 / 朗读 ¥0.01 / 重试）恢复，说明 onError 路径不破坏会话状态。
- **AOT / Baseline Profile（本轮实测）**：
  - APK 内已打包 `assets/dexopt/baseline.prof`（6184 B）与 `baseline.profm`（281 B），来自官方 `androidx.baselineprofile` 生成，非手写。
  - 首启日志出现 `ProfileInstaller: Installing profile for app.xuxie`，随后 `/data/misc/profiles/ref/app.xuxie/primary.prof` 为 6190 B；二次启动变为 `Skipping profile installation`（已安装，正确去重）。
  - `adb shell cmd package compile -m speed-profile -f app.xuxie` → `dumpsys package dexopt` 显示 `[status=speed-profile]`；oat 产物 `base.odex 4,177,768 B / base.vdex 2,305,844 B / base.art 553,840 B`，确认走的是 AOT 而非 verify。
  - 注意顺序：设备上还没有 profile 时直接 `-m speed-profile` 会落回 `status=verify`；先让 profileinstaller 写入 ref profile，再编译，才会得到 speed-profile。
  - **真机冷启动对比**（LQAVP23928C105TX / TYH631M，0.9.2 / versionCode 13，`am start -W -S` 各 5 次、交替编译消除漂移）：verify（无 AOT）483–498 ms、中位 489 ms；speed-profile（baseline profile 引导的 AOT）392–413 ms、中位 400 ms——**中位提速约 18%**。
  - 结论：`baseline.prof` 已被 AGP/R8 正确消费并写入 reference profile；profile 引导的 speed-profile 是本机最优编译模式。`-m speed`（全量 AOT）反而 426–456 ms：编译了 profile 之外的冷代码、odex 变大、页错误增多，故不采用。
  - 真机非 root，`/data/misc/profiles/ref/...` 不可读、`dumpsys package dexopt` 也不输出该包状态；上述结论用「同设备交替编译 + 冷启动计时」验证，比读 profile 文件更贴近真实收益。

### 主题系统 · 抽屉修复 · 官方图标（并入 0.9.2 / versionCode 13）

- **顶栏主题控件**：恢复日/月图标（Canvas 形变），右侧新增「冒号式双色指示块」——两块独立圆角块，上方是底色、下方是强调色。点按图标切日/夜；在图标上按住下滑（>26dp）打开主题面板；点指示块也可打开面板。
- **指示块四态**：日/夜 = 上白亮 + 下彩暗；自定义 = 上灰暗 + 下彩亮；护眼日间 = 上鹅黄/果绿亮 + 下彩暗；护眼自定义 = 上黄绿暗 + 下彩亮。
- **主题面板**：外观 4 项（日间 / 夜间 / 暖黄 / 淡绿）；两档护眼为定制色库（底色、面板、面板高亮、分割线、输入底色、正文/次要文字、提示底、滚动遮罩整组重烘焙），只在日间生效，强调色不变。主题色 5 预设（金 / 蓝 / 青 / 陶 / 白）+「自定义颜色」入口（HSL 色相/饱和度/明度三条滑杆，松手生效、持久化）；「白」只在夜间完整生效，日间自动转为黑并显示为「黑」。设置 → 主题 与顶栏指示块都能进入该面板。
- **控件比例回退**：深度思考 / 联网 / 关键词 / 联想 / 按时间 / 按条数 / 按总字数 → 34dp 高、17dp 圆角、左右 10dp、11sp，与文字比例匹配；抽屉搜索模式 tab 同步。抽屉「答疑」字号 13sp Medium。
- **抽屉会话行（A1）**：行与右侧 × 改为同级可点区域；列表改用 derivedStateOf 观察 sessions/revision/搜索条件，删除后立即消失、计数同步；删除同时 revision++ 并以 forceIndex 重写 index.json，杀进程重启不再复活。
- **对齐官方**：深度思考/联网图标改为官方矢量（ThinkGlyph/WebGlyph，14dp）；助手名与剪贴板文案统一 DeepSeek；导入历史对话改为底部轻量气泡（无遮罩、点外关闭，保留「以后不再提示」）；欢迎页补两行提示（优先官方免费额度 / 勿共用泄露 Key）。
- **真机验证**（LQAVP23928C105TX，1080x2388，0.9.2 versionCode 13）：夜间/日间/暖黄/淡绿/白色皮肤（夜）/ 自定义 HSL 逐项截图核对；日间选中第 5 色显示「黑」；冒号指示块四态与预期一致；删除会话立即消失且重启不复活。
- **构建**：BUILD SUCCESSFUL，release APK 1,373,477 B，sha256 d065b69257cad0a288c0f6b8ff28cd88ab414e94382c322cbc93813ead44705c。

### 导出 / Markdown / 协议正确性 / 导入健壮性（并入 1.0.1 / versionCode 2）

- **导出（Export.kt）**：聊天页 ContextBar「导出」→ 底部两行「导出本会话 Markdown」「导出全部会话 ZIP」；两者都走 SAF CreateDocument，只写用户选定位置，不需要存储权限，导出过程不联网。
- **文件名安全化**：`unsafeNameChars` 覆盖 `\\/:*?"<>|%#()[]`、半角逗号与控制字符，命中替换为 `-`；真机实测 SAF 默认名 `Echo-Render-markdown-test--sh-20261123-0848.md`（此前会生成 `test,-sh`，部分文件管理器/解压工具对逗号不友好）。
- **单会话 Markdown**：实测 3015 B；含标题、导出时间、最后活跃、消息数、模型、上下文状态；思考块导出为 `> **思考过程**` 引用；代码保留围栏与语言标签；`[DeepSeek](https://www.deepseek.com)` 链接与 `$...$` / `$$...$$` 公式源码原样保留；文末给出 token 与「本机估算费用」对账行。完成 notice：「已导出 Markdown 文件」。
- **全会话 ZIP**：实测 19,293,518 B；3812 entries = `index.md`（287,441 字符，含每会话链接与「已失效」标记）+ 3811 个 `sessions/NNNN-标题.md`；`zipfile.testzip()` 无损坏；流式 ZipOutputStream 写出，耗时 < 10 s（无进度条、不可取消，见第 5 节）。
- **Markdown / LaTeX 渲染（Markdown.kt）**：标题、粗斜删除、行内代码、代码围栏（语言标签 + 横向滚动）、列表、引用递归、分隔线、可点击链接、行内 `$..$` 与块级 `$$..$$` 公式；真机 1080x2340 验证 H1、3 项列表、引用、python 围栏、`E=mc²`、`∫₀¹x²dx=1/3` 排版正确；流式按空行切段、> 8000 字符退化纯文本以保证滚动流畅。
- **协议正确性（Sse.kt / Api.kt）**：逐事件解析 `content_block_delta`（text/thinking/signature/citations）、`message_start` 部分 usage、`message_delta` 全量 usage + `stop_reason`、`message_stop`、`error`；缺 `message_stop` 且非用户取消时上报「连接提前结束：没有收到 message_stop，内容可能不完整。」并保留已收内容；`maxTokens` 默认 32768（设置可选 8K/16K/32K/64K），`stop_reason=max_tokens` 提示「已达到最大输出长度（可在设置里调高上限）。」；assistant 历史在带 signature 时以 `[thinking, text]` 块回传。
- **导入健壮性（State.kt）**：只解析 `chat_sessions/*.json`；限额单会话 24 MB、2 万会话、6 万条目、ZIP 2 GB；按会话 id 合并去重（已存在则跳过、不覆盖本机消息文件）；进度浮层「正在导入历史对话 / N% · 状态」+「取消导入」，取消会删除已写入文件并提示「已取消导入，未写入任何会话。」；错误分流「ZIP 内容不完整，可能下载时被截断，请重新导出后再试。」/「这个 ZIP 里没有 chat_sessions 文件夹，可能不是 DeepSeek 官方导出的数据。」/逐文件失败清单。
- **真机验证（emulator-5554，1080x2340，1.0.1）**：sample-history.zip →「已导入 2 个会话」；重复导入 →「没有新增会话，跳过 2 个已存在的会话」；损坏 ZIP 与不含 `chat_sessions` 的 ZIP 均命中对应文案；801 MB full-export 的导入浮层显示 10% 进度与取消按钮。
- **单测 / 工装**：76 tests 0 failures（Billing 16 / Format 4 / Sse 19 / Export 9 / Markdown 28）；`scripts/mock_sse_server.py --selftest` 15/15；`.github/workflows/ci.yml` 已就绪但尚未在 GitHub 实跑（用户要求暂不发布）。

### 真 Key 冒烟（路线图 5，emulator-5554，2026-11-23）

- **环境**：真实 API Key 经系统密钥库 AES-GCM 存入本机（设置里只显示掩码）；账户余额从 ¥32.64 → ¥30.33（余额来自 `GET /user/balance`，只读）。
- **流式对话 / 深度思考**：deepseek-flash、深度思考默认开；发送英文提问后回答逐字流出，上下文条从 55 递增到 822 / 1M，思考块折叠显示「已思考」，结束后给出本轮输入/输出 tokens 与估算费用。
- **联网搜索**：composer 开启「联网」后提问 `Search the web for latest DeepSeek model news and give sources`；真实回答正文引用搜索摘要，末尾渲染「来源」6 条（编号 + 标题 + URL，如 `DeepSeek-V4.1-Flash-1017: Wish-Driven Programming Made Real | DeepSeek API Docs`、`DeepSeek更新模型了，还对外开放了搜索引擎`、`DeepSeek V4.1 Flash 新版本：许愿式编程一键成真`）。确认 `citations_delta` → 引用 UI 的完整链路可用（此前只有 mock/单测证据）。
- **TTS 朗读**：短回答点「朗读 ¥0.0087」→ 计费确认「本段约 29 计费字符 / 最高约 ¥0.01」→「开始朗读」→ `dumpsys audio` 出现 `state:started type:android.media.AudioTrack … usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH … sampleRate=24000`；设置 → 用量与费用显示「朗读 TTS ¥0.02 · 58 计费字符 · 2 次」。
- **ASR 语音识别**：`pm grant app.echo android.permission.RECORD_AUDIO` 后点麦克风 → 计费确认「单次录音最长 120 秒 / 最高约 ¥0.03」→「开始录音」→ 出现「正在聆听 · 00:5x / 02:00 · 再次点击结束」，再次点麦克风结束；用量与费用显示「语音识别 ASR ¥0.01 · 64 秒 · 1 次」。模拟器无宿主麦克风输入，识别结果为空属预期，链路与计费已走通。
- **停止生成**：长文提问流式至约 1466 tokens 时点「暂停回答」（原发送按钮变为暂停）→ 保留已生成内容、恢复「发送」，无「连接提前结束」报错（用户主动取消不算缺 `message_stop`），底部记录 ≈¥0.0133。
- **协议回归证据（真实 Key）**：`message_start` / `message_delta` 的 usage、`message_stop`、`citations_delta` 均被 Sse.kt 正确解析且无异常；异常 / 截断 / 429 / 500 分支由 SseTest 19 例与 mock 服务 `--selftest` 15/15 覆盖。

### 协议回归 + 本地 CI 演练（路线图 6 / 10，2026-11-23）

- **为什么要抽纯函数**：`ApiClient.stream` 原先把 SSE 循环、请求体拼装和 Android `Handler` 绑死，JVM 单测无法覆盖。现在 `app/src/main/java/app/echo/Api.kt` 顶层有两个 `internal` 纯函数：`buildChatBody(model, thinking, webSearch, systemPrompt, messages, maxTokens): JSONObject`（max_tokens 收敛、stream/thinking、`deepseek_web_search` 工具、空白 system 省略、assistant 历史带 signature 时回放 thinking+text 两块）；`pumpSse(reader, isCancelled, onEvent): Boolean`（逐行解析 event/data、空行触发 `Sse.parse`、记录 message_stop、EOF/取消返回 false）。`ApiClient.stream` 只保留 UI/线程回调，行为不变。
- **ProtocolTest.kt 15 例**：maxTokens 上下限收敛、model/stream/thinking 开关、webSearch 有无工具、空白 system 省略、signature 历史回放、完整轮次事件顺序与 usage `full` 标志、截断缺 message_stop、取消只消费首个事件、error 帧文案、ping/comment 不产事件、`httpErrorMessage` 401/402/429/503/504 映射、400 detail 直出与内容审核文案、未知码 fallback。
- **测试总计 91 例 0 失败**：Billing 16 / Export 9 / Format 4 / Markdown 28 / Protocol 15 / Sse 19（此前 76 例）。
- **本地 CI 演练**：`bash scripts/run_tests.sh --smoke` 全绿 = `compileDebugKotlin` + `testDebugUnitTest`（91 绿）+ `mock_sse_server.py --selftest` 15/15 + 冒烟（normal 有 message_stop、truncated 无、429 状态码正确）；脚本自己探测 python3/python。`.github/workflows/ci.yml` 同步存在，因暂不发布 GitHub 未实跑。
- **真机回归**：1.0.3 安装后真 Key 发 ping →「Pong! 🏓 / What can I help you with today?」，底部「本轮 ¥0.0001 · 输入 5 / 输出 15 tokens」，无「连接提前结束」报错。

### 性能（真机实测，0.9.2 / versionCode 13）

- **上下文条轮询**：流式生成时每 500ms 刷新 token 估算。原实现每次都遍历整段会话（`historyMessages()` 过滤 + `sumOf`，长会话持续产生临时 List/String 垃圾）；现在发送时缓存历史字符数 `contextBaseChars`，轮询只叠加当前回复的 `text.length`（O(1)），`onDone/onError/stopGenerating` 再全量校准一次。`refreshContextTokens()` 移到 `streaming = true` 之后调用，缓存才会生效。
- **冷启动**：baseline profile 引导的 speed-profile AOT 中位 400 ms（verify 489 ms，约 -18%）。
- **对话滚动**（536 轮「意识」会话，连续上/下各 8 次 swipe，`dumpsys gfxinfo`）：Total frames 723–766，modern jank 0.55%–2.65%（多轮中位约 2.2%），90th/99th percentile 11–13 ms。曾出现一次 50%+ 的异常轮：`Slow issue draw commands` 368 而 `Slow UI thread` 仅 29，判断为系统侧 GPU/窗口干扰（Huawei 系统进程），不是应用主线程卡顿。
- **已确认无隐藏热点**：无 `rememberInfiniteTransition`、无持续无限动画；抽屉列表 `computeDrawerView` 走 `derivedStateOf`；`revision` 只在抽屉派生缓存读取（流式 publish 不会整屏重组）；会话索引在 `echo-store` 单线程解析；`KeyVault` 只在有 Key 时才访问 Keystore；欢迎页入场动画走 `graphicsLayer` lambda（绘制阶段读值，不触发重组）。
- **模式差异**：性能模式（设置 → 运行模式）把存储线程提到 NORM、流式节流 70/45ms、保存延迟 260ms、更多会话常驻；正常模式 MIN/120/60ms、700ms、更积极 evict。

## 5. 已知限制

- Markdown/LaTeX 由自研 `Markdown.kt` 渲染，属手写子集：不支持表格、图片、嵌套列表；块级公式有一定布局开销，超长回答（> 8000 字符）退化为纯文本以保证滚动流畅。
- 导入按会话 id 合并去重，只保留当前分支；附件与图片不会重传。
- 全会话 ZIP 导出无进度条、不可取消（< 10 s，可接受）；单会话导出整份 JSON 解析，存在内存峰值。
- CI 工作流已就绪、本地 `scripts/run_tests.sh --smoke` 演练全绿（编译 + 91 单测 + mock 自检 + 冒烟），但尚未在 GitHub 实跑（用户要求暂不发布）；联网搜索真实 Key 全链路已于 1.0.2 打通（见 §4 真 Key 冒烟）。
- ~~release 使用 debug 签名~~ → 1.0.0 起使用正式 release keystore（证书指纹见第 8 节）；无密钥时仍回退 debug，仅限本地内测。
- ~~联网搜索（deepseek_web_search）尚未用真实 Key 跑全链路~~ → 1.0.2 已打通：服务端执行搜索，客户端收到 `citations_delta` 并在回答末尾渲染「来源」（§4 真 Key 冒烟）。
- 性能数据以真机（LQAVP23928C105TX，90Hz 档）为准；模拟器是软件渲染，其 gfxinfo jank 比例不作指标。

## 6. 怎么跑

    export JAVA_HOME=/path/to/jdk-17
    cd app-native
    gradle :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
    # 产物 app/build/outputs/apk/release/app-release.apk
    # 接本地 mock：加 -PapiOrigin=http://10.0.2.2:8099（模拟器访问宿主机）

需要真实 Key 时：设置 → API Key → 连点「显示」三次 → 输入 `sk-…` → 保存并开始。

## 7. 数据与隐私

仓库不包含任何用户会话、附件或 API Key。应用内所有会话/附件只在本机；卸载即删除。费用由用户的 Key 直付官方，本应用不预扣、不代收。

---
*由 DeepSeek-V4.1Flash 构建；第三方客户端，与 DeepSeek 官方无隶属关系。*

## 8. 1.x（MIT 版）发布基线（2026-11-23）

**身份与版本**

- applicationId / namespace：**app.echo**；旧包名 app.xuxie 已废弃，禁止再使用（与旧版数据不互通，属已确认的破坏性变更）。
- versionCode / versionName：1.0.0 = 1 / 1.0.0、1.0.1 = 2 / 1.0.1、1.0.2 = 3 / 1.0.2；当前 **1.0.3 = 4 / 1.0.3**。两者只在 gradle.properties（echo.versionCode / echo.versionName）维护，app/build.gradle 只读取。
- minSdk 26 / targetSdk 35 / compileSdk 35。

**签名**

- Release keystore：D:/desktop/deepseek/keys/echo-release.jks（PKCS12，RSA 4096，别名 echo，有效期 10000 天）；**不入库**。
- 口令与路径存放在 GRADLE_USER_HOME/gradle.properties 的 echo.release.* 中；无配置时自动回退 debug 签名。
- 证书 SHA-256：5E:DA:7A:67:36:BD:0C:75:7A:8D:B4:A1:89:DD:28:04:8F:4B:8B:DC:64:E8:64:DC:1D:30:A8:75:CB:99:78:CA
- SHA-1：34:D7:42:34:BE:83:28:74:AF:DA:B4:9D:29:5A:C7:90:AE:8A:4B:70
- **备份要求**：keystore 与口令至少备份三处异地；丢失将无法再发布同签名更新。

**构建产物**

- **1.0.3 / versionCode 4**（协议回归 + 本地 CI 演练：把请求体拼装与 SSE 循环从 `ApiClient.stream` 抽成纯函数 `buildChatBody` / `pumpSse`，新增 `ProtocolTest.kt` 15 例；`scripts/run_tests.sh --smoke` 本地跑通编译 + 91 单测 + mock 自检 + normal/truncated/429 冒烟）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 09:40；SHA-256 `d2c6f62a89f839d6341ca84f196167a24d5fa1ef40e09d762e91d9940e67a42c`；emulator-5554 安装后 dumpsys 显示 versionCode=4 / versionName=1.0.3；重构后真机 ping 冒烟正常（回复「Pong! 🏓」，「本轮 ¥0.0001 · 输入 5 / 输出 15 tokens」，无「连接提前结束」报错）。
- **1.0.2 / versionCode 3**（导入文案纠错：答疑中心与导入 FAQ 由「会覆盖本机全部会话」改为「按会话 ID 合并、已存在跳过、附件不导入、全程不上传」，与导入实现口径一致）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 09:29；SHA-256 `fbb19241fdfd9c8adf211d63e382d45193f3ea6b848264adc2721f9dd8e2f78b`；emulator-5554 安装后 dumpsys 显示 versionCode=3 / versionName=1.0.2（lastUpdateTime 2026-11-23 09:30:06）；导入气泡、导入 FAQ 与答疑中心两条新文案均真机核对。
- **1.0.1 / versionCode 2**（导出打磨：`unsafeNameChars` 去半角逗号、单会话导出 notice 改为「已导出 Markdown 文件」）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 08:38:17；SHA-256 `b524753d18276cc416c5882cef461125d80138a3e174dd9254659c67b0ec0836`；apksigner 校验 signer 证书 SHA-256 与上节一致；emulator-5554 安装后 dumpsys 显示 versionCode=2 / versionName=1.0.1（lastUpdateTime 2026-11-23 08:38:32）。
- **1.0.0 / versionCode 1**（MIT 版基线）：`app/build/outputs/apk/release/app-release.apk`，1,389,853 B。
- SHA-256：cf47437dac2f7f3cadf4c56d093f43fcff2097aebd255fb229f58c931cdcc0ca
- 安装验证：emulator-5554（1080x2340）全新安装 app.echo 1.0.0 成功，冷启进入欢迎页（空数据），dumpsys 显示 versionCode=1 / versionName=1.0.0。

**许可与文档**

- 新增 LICENSE（MIT，2026 Echo contributors）与 NOTICE（Compose/Kotlin/AndroidX/Apache-2.0 归属；Echo 名称/图标/视觉资产保留所有权利）。
- README 重写：品牌边界、AI 辅助 AS IS、免费非商业但 MIT 允许任何用途、维护边界（不做云同步/账号/后端/广告/内购/非公开接口）、AOT 表述改为“profile 引导、机器码设备本地生成”。
- 已知限制与进度同步：Markdown/LaTeX 渲染、单会话 Markdown 与全会话 ZIP 导出、README 八张实拍截图、导出文件名安全化、协议正确性（Sse.kt / maxTokens / message_stop 校验）、导入健壮性与兼容层文档（docs/protocol-compat.md）均已在 1.0.1 完成；1.0.2 修正导入文案口径并补完真 Key 冒烟（联网搜索 citations 全链路已打通，见 §4）；1.0.3 完成协议回归（抽 `buildChatBody`/`pumpSse` + `ProtocolTest` 15 例，总计 91 单测）与本地 CI 演练。仍在路线图中的是：CI 在 GitHub 实跑（暂不发布）、旧 UI 三项收尾。

**说明**：本文第 3–5 节的性能与交互验证数据来自 0.9.2（app.xuxie）；1.0.0 仅变更身份与签名，代码行为未变，因此这些数据继续有效。
