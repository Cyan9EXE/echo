# Echo 评审说明（REVIEW）

> 面向评审者的一页纸：项目定位、关键决策、本轮改动、验证方式、已知问题。
> 代码仓库：app-native/（Android，Kotlin + Jetpack Compose）。撰写时版本 **1.0.5（versionCode 6，applicationId app.echo）**；本文中 0.9.x 与 1.0.0 前后的记录为历史版本证据。

## 1. 这个项目是什么

Echo（回声）是一个**本地优先**的第三方 DeepSeek 客户端：

- 把官方导出的历史会话 ZIP 导入本机；按 `current_message_id → parent_id` 还原当前分支。
- 官方端已失效（>7 天未活跃）的会话，可在本机「复活」：完整原文，或「前部摘要 + 后部原文」——摘要由本机生成，不调用 API、不产生额外 token。
- 对话、朗读、语音识别全部用**用户自己的 API Key 直连 api.deepseek.com**（BYOK），没有中转服务器，也不做额度破解。
- 上下文透明：当前 token / 1M、占比、原文或摘要模式、每轮与累计估算费用。

## 2. 评审重点（按重要性）

1. `State.kt` — 唯一状态源。流式生命周期（send/stop/retry）、持久化（dirtyIds + 单线程 store）、上下文 token 缓存（`contextTokens` + `refreshContextTokens()`）。重点看：并发取消（`streamSeq`）、错误与取消路径是否重复落盘、恢复旧数据时的兼容分支。
2. `MessageViews.kt` — 流式渲染的关键优化：思考块折叠时机、正文按「稳定段 + 纯文本尾行」渲染（稳定段按换行边界切块并缓存解析结果，最后未闭合段只做纯文本绘制，避免每个 token 全量重排）、流式期间隐藏操作区与计费行。
3. `ChatScreen.kt` — 列表跟随：`withFrameNanos` 逐帧 `scrollBy` 追加增量，只在贴底且用户未上滑时跟随；用户上滑即暂停跟随，回到底部自动恢复（`snapshotFlow { isScrollInProgress }` + 末端 gap 判定）。
4. `Secret.kt` — Key 落盘加密（Android Keystore，AES-256-GCM，磁盘只有 `v1:base64(iv||ct)`；解密失败按“无 Key”处理）。
5. `Api.kt` — 手写 SSE 解析（无第三方网络库）：`stream()` 的回调契约、`onError` 与 `onDone` 的先后关系。
6. `SettingsScreen.kt` — API Key 的隐私交互：默认掩码 + 连点 3 次「显示」才可编辑；显示期间 `FLAG_SECURE` 禁止截屏。

## 3. 1.0.5 阶段改了什么

1.0.5 的主要增量：TTS 朗读重写（24 kHz → 48 kHz 升采样，见 §4）、横屏欢迎页紧凑布局 / 旋转不重建 / 输入草稿保留、COLOR-V3 五色强调色、蓝白 1.6:1 命令提示符图标，以及 REVIEW-LOOP 第 1–7 轮对流式渲染、列表跟随与数据一致性的修复（见 §4 REVIEW-LOOP 小节）。下面保留流式观感与交互的既有实现说明，作为当前行为的背景。

**流式观感对齐官方**

- 未出正文时思考块标题为「正在思考」；正文开始后自动折叠为「已思考（用时 N 秒）」（`thinkingMs` 随消息持久化，重开 App 仍成立）。
- 流式生成期间**不显示**操作区（复制/朗读/重试）与「本轮 ¥x · tokens」计费行，结束后再出现。
- 思考正文窗口固定高度、只保留尾部窗口（live 1400 字），避免每个 token 改变 item 高度。
- 去掉底部「正在生成…」占位行（官方没有）。

**卡顿与重组**

- 列表跟随改为帧同步：`withFrameNanos` 每帧计算内容末端与视口的 gap，超过 0 才 `scrollBy` 同等距离；用户拖动期间不抢滚动，停止且已贴底后恢复跟随。
- 流式发布改为 `Choreographer` 帧回调合并：同一帧内的多个 token 只发一次正文更新（正常模式 16ms、性能模式 33ms 上限），`Api.kt` 的 SSE 读循环按 16ms / 4096 B 先聚合再回调。
- Markdown 流式只解析「稳定段」前缀，最后一行未闭合内容以纯文本尾行渲染；回答结束后整体解析一次。已渲染的稳定块不会因追加而重排。
- ContextBar 的 token 估算从组合期计算改为 `contextTokens` 状态 + 流式期间每 500ms 刷新，避免顶栏每帧重算全量文本长度。
- 流式期间思考滚动使用 `snapshotFlow { scroll.maxValue }`，不再每帧主动 scrollTo。

**交互细节**

- 顶栏强调色切换：五个色点常驻（沙金 / 雾蓝 / 黛青 / 陶土 / 灰紫，默认雾蓝），点击即时换肤；未选中 22dp 热区、选中带环。
- 「深度思考 / 联网」Chip 与抽屉搜索模式 Tab 尺寸收敛（高 34dp、圆角 17dp），视觉更贴近官方；此前为满足 48dp 触控做的放大显得笨重；现行口径是「与官方对齐的实际可点尺寸」，不再用通用 48dp 规范判缺陷（见 JUDGMENT NOTES）。
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
- **Markdown / LaTeX 渲染（Markdown.kt）**：标题、粗斜删除、行内代码、代码围栏（语言标签 + 横向滚动）、列表、引用递归、分隔线、可点击链接、行内 `$..$` 与块级 `$$..$$` 公式；真机 1080x2340 验证 H1、3 项列表、引用、python 围栏、`E=mc²`、`∫₀¹x²dx=1/3` 排版正确；流式时按换行拆出稳定段（缓存解析结果），最后未闭合段按纯文本尾行绘制，完成后整体 Markdown 排版。
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
- **停止生成**：长文提问流式至约 1466 tokens 时点「暂停回答」（当时按钮文案；第 4 轮起改为「停止生成」）→ 保留已生成内容、恢复「发送」，无「连接提前结束」报错（用户主动取消不算缺 `message_stop`），底部记录 ≈¥0.0133。
- **协议回归证据（真实 Key）**：`message_start` / `message_delta` 的 usage、`message_stop`、`citations_delta` 均被 Sse.kt 正确解析且无异常；异常 / 截断 / 429 / 500 分支由 SseTest 19 例与 mock 服务 `--selftest` 15/15 覆盖。

### 协议回归 + 本地 CI 演练（路线图 6 / 10，2026-11-23）

- **为什么要抽纯函数**：`ApiClient.stream` 原先把 SSE 循环、请求体拼装和 Android `Handler` 绑死，JVM 单测无法覆盖。现在 `app/src/main/java/app/echo/Api.kt` 顶层有两个 `internal` 纯函数：`buildChatBody(model, thinking, webSearch, systemPrompt, messages, maxTokens): JSONObject`（max_tokens 收敛、stream/thinking、`deepseek_web_search` 工具、空白 system 省略、assistant 历史带 signature 时回放 thinking+text 两块）；`pumpSse(reader, isCancelled, onEvent): Boolean`（逐行解析 event/data、空行触发 `Sse.parse`、记录 message_stop、EOF/取消返回 false）。`ApiClient.stream` 只保留 UI/线程回调，行为不变。
- **ProtocolTest.kt 15 例**：maxTokens 上下限收敛、model/stream/thinking 开关、webSearch 有无工具、空白 system 省略、signature 历史回放、完整轮次事件顺序与 usage `full` 标志、截断缺 message_stop、取消只消费首个事件、error 帧文案、ping/comment 不产事件、`httpErrorMessage` 401/402/429/503/504 映射、400 detail 直出与内容审核文案、未知码 fallback。
- **测试**：本节时点（1.0.3）为 91 例 0 失败：Billing 16 / Export 9 / Format 4 / Markdown 28 / Protocol 15 / Sse 19（此前 76 例）；1.0.5 REVIEW-LOOP 第 2、3 轮后为 99 例（新增 `TtsAudioTest` 8 例）。第 6、7 轮后为 112 例（新增 FollowTail 与 Markdown 流式扫描用例）；第 8 轮后为 118 例（新增 `SessionPersistenceTest` 6 例）；第 9 轮后为 121 例（新增加载门禁、恢复对账、重复 id 合并与代理对边界用例）。
- **本地 CI 演练**：`bash scripts/run_tests.sh --smoke` 全绿 = `compileDebugKotlin` + `testDebugUnitTest`（99 绿）+ `mock_sse_server.py --selftest` 15/15 + 冒烟（normal 有 message_stop、truncated 无、429 状态码正确）；脚本自己探测 python3/python。`.github/workflows/ci.yml` 同步存在，因暂不发布 GitHub 未实跑。
- **真机回归**：1.0.3 安装后真 Key 发 ping →「Pong! 🏓 / What can I help you with today?」，底部「本轮 ¥0.0001 · 输入 5 / 输出 15 tokens」，无「连接提前结束」报错。

### TTS 48k 重采样与静音修复（1.0.4 → 1.0.5，2026-11-23）

- **1.0.4 的问题**：真机朗读有嘶嘶噪声；1.0.5 重写 Reader：官方 TTS 返回 24 kHz 单声道 PCM16，客户端用 Up2 半带 FIR 升采样到 48 kHz 后交给 AudioTrack（48000 Hz / MONO / PCM16 / MODE_STREAM），PREBUFFER 32 KB，track buffer 降到 64 KB。
- **静音根因**：首版 48k 实现把 262,144 B 的 track 缓冲一次性写入 249,600 B 后就停止供数，AudioFlinger 在 play() 后约 0.54 s 判 underrun 并 `BUFFER TIMEOUT: remove track(55) from active list due to underrun on thread 13`，`playbackHeadPosition` 恒 0、实际不出声。降缓冲后写入按实时节奏阻塞，track 不再被移除。
- **修复后取证**（emulator-5554，正式 1.0.5）：AudioFlinger track `FrmCnt=32768`（64 KB），`AT::add` 15:16:19.632 → `AT::remove` 15:16:22.137 正常结束（T，播放约 2.5 s，无 BUFFER TIMEOUT）。
- **QEMU 抓音**（`emu_tts54.wav`，t=6.4–8.2 s 段）：peak 0.00879 / rms 0.00163 / active 34/77；频带 0–300 Hz 10.7%、300–1k 30.3%、1–6k 35.4%、6–10k 14.7%、10–16k 8.4%、16–22k 0.5%；f0≈272 Hz 且存在 272/278/276 与 812/814/816 Hz 谐波，spectral flatness(100–8k)=0.0063 —— 确认为语音而非噪声，静音问题修复。

### REVIEW-LOOP 第 1 轮（外部审查 → 修复，2026-11-23）

- **外部审查**：把 1.0.5（HEAD d30f815，9 files +349/−163）交给独立审查会话做只读评审，报告落在 `review/rounds/echo-1.0.5-review.md`（150 行）。结论：P0 = 0、P1 = 1、P2 = 9、P3 = 9，「有条件通过」；评审口径为功能不能缺 / 代码优美整洁 / 优化真实可行且高效，不按通用 dp 规范扣分。
- **本轮落地**：P1-1 AudioTrack / AudioFocus 生命周期竞态（track 收进 worker 并在 finally 释放、`generation` 改 `AtomicInteger`、焦点申请与放弃做代际校验、永久 LOSS 停止而 transient 暂停 / duck 降音量、`writeFully` 失败不再静默、朗读中提供「停止」、loading 阶段可暂停）；P2-4 录音与朗读互斥；P2-5c `currentId` 与输入草稿持久化（进程被杀重开可恢复会话与草稿）；P2-6 横屏判定改用 `LocalConfiguration`，IME 弹起不再触发紧凑布局；P2-7 播放尾等待抽成 `TtsTailWaiter`，区分「正常播完 / 用户暂停 / 输出超时」三终态；P2-9 拆出 `TtsAudio.kt`（半带 FIR 升采样 + AudioTrack 封装 + 尾等待）并补 7 例 JVM 单测；P3-1 underrun 计数接入 debug 日志、TTS 专属 413 文案、欢迎页入口加 `Role.Button` 语义、根布局改 `safeDrawingPadding`。
- **设备复验**（emulator-5554，1.0.5 正式包）：长会话（778 tokens）朗读 → 暂停（chip 变「继续朗读」）→ 继续 → 停止全流程通过；`dumpsys media.audio_flinger` 为 `0 Tracks`，`requestAudioFocus` / `abandonAudioFocus` 计数 3 / 3 平衡，无 track 与 AudioFocus 泄漏；ping 短会话（4 计费字符）约 1.2 s 正常播完后 chips 复位并提示「朗读完成 · 计费约 ¥0.0012」。
- **JVM 单测**：98 例 0 失败（原 91 例 + `TtsAudioTest` 7 例：直流增益、1 kHz 幅值、长度 2×、分块一致、尾等待三态；第 2 轮后为 99 例，见下节）。

### REVIEW-LOOP 第 2 轮（外部审查 → 修复，2026-11-23）

- **外部审查**：以 HEAD 0d5a9e8（相对 d30f815 12 files +757/−309）为对象做只读复审，报告 `review/rounds/echo-1.0.5-round2-review.md`（151 行）。结论：P0 = 1、P1 = 1、P2 = 4、P3 = 6，**不通过**（P0-1 尾等待双重换算导致尾音截断；P1-1 冷启 / 恢复会话不触发 UI 重组）。
- **本轮落地**（提交 c06392a，8 files +109/−33）：P0-1 `TtsTailWaiter` 判定改为 `headBytes >= writtenBytes`；P1-1 `Models.kt` 的 `ready` 改 `mutableStateOf`，`State.applyLoaded` 恢复 currentId 并公开 `ensureReadyForUi`，`ChatScreen` 增加 `LaunchedEffect(s.id)` 触发加载；P2 loading 阶段允许暂停（`pause()` 接受 loading）、`stopAll` 先取旧代际再自增并 `abandonFocus(my)`、`send()` 返回 Boolean 仅在成功时清空草稿、草稿 300 ms 去抖 + `ON_STOP` 落盘；P3 `checkAlive` 移到 audio 赋值后、`connection` 改 `AtomicReference` + `compareAndSet`、ASR 晚到回调检查 `discard`、`MessageList` 写操作改 `check(ready)`。
- **设备复验**（emulator-5554，1.0.5 正式包 `e7e59e10…`）：播放中「停止」→ `abandonAudioFocus` 17:37:23.911、`removeTrack_l` track 56 @17:37:24.354，随后 0 Tracks；loading 阶段立即点 chip → 变「继续朗读」、创建了 track 57 但 0 次 write、未 play（零帧）；自然播完 → chips 自动复位，focus 17:38:53.948 → 17:39:33.449、track 58 写入 1,843,200 帧（48 kHz ≈ 38.4 s），QEMU WAV 中语音止于 37.75 s，其后 3.25 s 静音、末 150 ms 最大采样差 0.00229（无爆音 / 硬切）。
- **JVM 单测**：99 例 0 失败（本轮新增 `TtsAudioTest` 尾等待 1 例；该文件共 8 例）。

### REVIEW-LOOP 第 3 轮（外部审查 → 修复，2026-11-23）

- **外部审查**：以文档 HEAD 41a541d / 代码 c06392a 为对象做只读复审，报告 `review/rounds/echo-1.0.5-round3-review.md`（183 行）。结论：P0 = 0、P1 = 2、P2 = 7、P3 = 5，**不通过**；两个 P1 均属「刚发的一轮可能丢消息」的数据丢失。
- **本轮落地**（提交 2616cb1，6 files +96/−20）：P1-1 大会话异步解析晚到覆盖本地新消息（`markReady` 加 ready 守卫、同步解析丢弃 parsingIds 标记、`applyLoaded` 保留本地/当前会话、`save` 保留未 ready 会话的 dirty）；P1-2 `retryLast` 先做与 `send` 相同的守卫，发送失败回滚消息；P2-1 `onError` 单终态（`streamSeq++` 使随后的 `onDone(false)` 失效）；P2-2 新增 `liveSessionId` / `isStreaming(sessionId)`，ChatScreen 与 Composer 不再用全局 `streaming` 判断；P2-3 流式每约 2 s 落盘、`evictLoaded` 跳过流式会话、`backHome` 先 flush 并持久化；P2-4 申请音频焦点前先 abandon 旧请求；P2-5/P2-6 文档口径校准（零帧 track、IO 线程、版本/行数）；P2-7 system 提示词每轮发送（官方 Messages API 无状态，空白不发）；P3 空草稿也落盘、`applyLoaded` 冷启动竞态、ASR 取消后不计费、历史消息按消息时间（`isPeakAt`）判峰谷。
- **验证**：`compileDebugKotlin` 通过（仅 3 条既有 deprecation 警告）；JVM 单测 **99 例 0 失败**；release APK 重建为 1,439,033 B、SHA-256 `edca1a7b48ac6337dee9dfa5441586a15c5cfb9a525dda917bf1be52c1fef15f`（第 3 轮时点，最新包见 §8）。
- **仍待**：前台服务（后台长朗读可能被系统回收，P3-5 已知限制）；真机音频路由场景（焦点 / duck / 耳机插拔）。

### REVIEW-LOOP 第 4 轮（外部审查 → 修复，2026-11-23）

- **外部审查**：以 HEAD c658e4d（工作树干净）为对象做只读复审，报告 `review/rounds/echo-1.0.5-round4-review.md`（29,268 B）。结论：P0 = 0、P1 = 3、P2 = 8、P3 = 7，**不通过**；三个 P1 都指向流式手感与长文成本。
- **P1-1 正文刷新频率不足**：`State.kt` 的 publish 门（120/70ms）+ `Api.kt` 的 flush（60/45ms）叠加后，正文可见更新上限只有正常 8.3 fps / 性能 14.3 fps。本轮改为 `Choreographer` 帧回调合并发布（同一帧只 publish 一次，正常 16ms / 性能 33ms 上限），`Api.kt` 的 flush 间隔 60→16（该常量在第 5 轮已删，只保留字节阈值 `FLUSH_BYTES = 4096`），`stream()` 的 `flushMs` 默认 60→16；终态先 `removeFrameCallback` 再强制 publish，避免丢最后一帧。
- **P1-2 跳底不是逐帧**：原实现每 90/60ms `scrollToItem(Int.MAX_VALUE)`，会把用户上滑的意图抢回来。本轮改为 `withFrameNanos` 逐帧计算 `scrollBy` 增量，并用 `snapshotFlow { isScrollInProgress }` + 末端 gap ≤ 2f 的判定维护 `followTail`：拖动时暂停跟随，停止且已贴底后自动恢复。
- **P1-3 长文 O(n²)**：流式时整段 `remember(text) { splitSegments }` + 最后一段每 token 全量 parse，caret 还由 110ms 闪烁重建 AnnotatedString 触发重排。本轮改为：最后一个 segment 以最后一个换行切成稳定前缀（`MarkdownText(live = false)`）与未闭合尾行（新增 `PlainStreamText`，纯文本、静态 caret），稳定段按 segment 文本缓存；删除 `blinkAlpha()`，caret 用固定 alpha；`AssistantBubble` 用 `derivedStateOf { m.text.isNotBlank() }` 避免父层每 token 重组。
- **其余修复**：暂停 → 停止语义统一（`State.kt` / `Composer.kt` 文案与 README）；`MessageList.markReady(list): Boolean`，异步解析晚到时先 markReady 再写摘要/字数，避免元数据回退；入场/呼吸/TypingDots 改有限次动画，`Motion.animationsEnabled=false` 时直接终态；`docs/qa/tts.md`、README、REVIEW、ARCHITECTURE 口径同步（帧同步发布、稳定段、APK/sha）。
- **验证**：`compileDebugKotlin` 通过；JVM 单测 **99 例 0 失败**；release 从 HEAD `5aa0eda` 重建为 1,443,981 B、SHA-256 `881c38aa1b60b27666ca4a736136e60f819e102d5c12d318dc1814f574f4e8ac`（mtime 2026-11-23 22:48）。本轮真机已断开（adb devices 为空），安装与设备复验未执行，设备证据仍来自旧包 `e7e59e10…` / `900ba8c8…`。
- **残留**：P2-3 主题过渡仍由全局 `C.themeProgress` 每帧驱动；P2-4 动画连续性；P2-7 缺流式 benchmark；P2-8 主线程 `ensureReadyBlocking` / 索引保存。第 5 轮复审待发起。

### REVIEW-LOOP 第 5 轮（外部审查 → 修复，2026-11-23）

- **外部审查**：以文档 HEAD 0a6b5d3 / 代码提交 5aa0eda 为对象做只读复审，报告 `review/rounds/echo-1.0.5-round5-review.md`（204 行 / 23,804 B）。结论：P0 = 0、P1 = 1、P2 = 8、P3 = 6，**不通过**；唯一 P1 是「向长会话发送新消息后贴底失效」。
- **P1-1 跟底失效**：帧循环把「最后可见项 == 最后一项」当作是否继续跟随的总开关，而 `send()` 先插入 user + 空 assistant，新消息使旧最后可见项 `index = total-3`，永不等于 `total-1`，overflow 永不计算，也没有任何路径把新尾滚入视口。本轮改为：`followTail` 仍是唯一开关；循环内先算 `tailRescueIndex(last.index, total)`，需要时 `scrollToItem(total-1, Int.MAX_VALUE)` 再继续逐帧 `scrollBy`。新增 `FollowTailTest` 4 例（`app/src/test/java/app/echo/FollowTailTest.kt`）。
- **P2-1 Markdown 尾段**：`Markdown.kt` 新增 `StreamSplit`，只在换行推进或长度回退时重切段；尾行只渲染最后 800 字符窗口（`STREAM_TAIL_MAX`），超出前置省略号；稳定段按 segment 文本缓存。
- **P2-2 / P2-3 跨线程与主线程解析**：异步解析改为 io 线程只读文件文本（`readSessionRecord`），回主线程 `applyParsedRecord` 写状态；`ensureReadyBlocking` 对 >48KB 会话转异步；`send` / `revive` 在正文未 ready 时提示「稍候」而不是同步解析。
- **P2-4 落盘窗口**：`stopGenerating()` 立即 `save()`（不再等 260/700ms 防抖）；思考流按 2s 节流 `markDirty` + `scheduleSave()`。
- **P2-5 / P2-6 动画与主题**：`Motion.animationsEnabled` 改为 Compose 状态并在每次重组用 `SideEffect` 同步；主题改为颜色一次性切换 + 绘制阶段 alpha 淡入（删除逐帧全树读值的 themeProgress 插值）；思考块 `animateContentSize`、历史默认收起、「正在思考」只在 live 显示；操作区 `AnimatedVisibility`；live item 恢复 `animateItem()`；Composer 发送↔停止 `Crossfade`；`Pressable` / `ActionChip` / 入场 / notice 统一走 `Motion`，`animationsEnabled=false` 时 `snap()`。
- **P3-1**：删除已无引用的 `Api.kt` `FLUSH_INTERVAL_MS`，保留 `FLUSH_BYTES = 4096` + 外部 `flushMs` 两道门。
- **验证**：`compileDebugKotlin` 通过；JVM 单测 **103 例 0 失败**（新增 `FollowTailTest` 4 例）；release 从工作树重建为 1,460,365 B、SHA-256 `8b0e5f72313a05e396b8505847bb4ff4453e0b69d5a59cee191a3229210d2918`（mtime 2026-11-23 23:49）。本轮 `adb devices` 为空（真机 LQAVP23928C105TX 未连接），装机冒烟、跟底真机复验与 gfxinfo 性能证据未执行。
- **残留**：P2-7 仍缺 Compose UI 测试与 Macrobenchmark；P3-2 caret 死代码、P3-3 注释与入场动画、P3-4 notice 硬编码时长、P3-5 魔数与程序化滚动判定、P3-6 历史行号引用，留下版清理。第 6 轮复审待发起。

### REVIEW-LOOP 第 6 轮（外部审查 → 修复，2026-11-23 → 24）

- **外部审查**：以 HEAD f9b99d8 为对象做只读复审，报告 `review/rounds/echo-1.0.5-round6-review.md`（193 行 / 23,727 B）。结论：P0 = 0、P1 = 1、P2 = 8、P3 = 6，**不通过**；唯一 P1 是第 5 轮贴底救援修复引入的回归（首帧被 `snapshotFlow` 的初始 false 覆盖）。
- **P1-1 跟底回归**：`ChatScreen.kt` 的 `followTail` 不再被 `isScrollInProgress` 的初始 emission 覆盖；新增 `shouldFollowTail(lastIndex, totalItems, gap)` 只在滚动停止时重算（空列表 / 末项可见 / gap ≤ 2f 才跟随）；程序化 `scrollToItem` / `scrollBy` 不产生 `DragInteraction`，用户上滑时通过 `listState.interactionSource.interactions` 的 `DragInteraction.Start` 暂停跟随，拖回底部松手后自动恢复。
- **P2-1 流式 Markdown 重写**：`StreamSplit` 改为「稳定段只提交到空行或代码围栏闭合处」，尾部全文纯文本渲染、不截断不隐藏（>2400 字符按 1200 字符分块且不切断代理对），全文段按字符串缓存跳过重复排版；删除 `STREAM_TAIL_MAX=800` 窗口与前缀递归重解析。补 `StreamSplit` / `chunkText` 单测 5 例。
- **P2-3 / P2-4 解析与列表发布**：`readSessionRecord` 在 IO 线程完成 JSON→消息列表与摘要解析（`ParsedRecord`），主线程 `applyParsedRecord` 只做 `markReady` 与摘要 / 字数发布；`MessageList.loaded` 改为 `mutableStateOf<List<Msg>>`，结构修改整体发布新列表。
- **P2-5 / P2-6 动画**：主题切换先 `themeFade.snapTo(0)` 再换色、整屏 `graphicsLayer` 淡入（含抽屉与弹层，第 8 轮改为旧底色垫底的真实交叉淡入，见下节）；`motionOn` 改为每 500ms 轮询 `ValueAnimator.areAnimatorsEnabled()` 并写入 `Motion.animationsEnabled`，设置页提示与 `SunMoonMark` 统一走 `Motion.dur`。
- **P2-8 落盘可靠性**：`writeAtomic` 用唯一临时名 + `FileOutputStream` 的 flush / `fd.sync` + rename；`save` 主线程只做快照，索引行在 IO 线程构建；写失败把 id 放回 `dirtyIds`、索引失败置 `forceIndexNext`；`charCount` 改增量维护；`onError` 与 `onDone` 都调用 `finishUsage`（暂停轮次不再漏记费用）。
- **P3 清理**：删除 `Motion.crossfade` / `C.voidLuminanceFor` / `C.shapeProgress`；删除 `IndexRow.systemSent` 与 `Session.systemSent` 及相关读写；`ThinkingBlock` 空思考不再重复「正在思考 + TypingDots」；`retryLast` 未 ready 时提示「会话内容还在载入」；`FollowTailTest` / `MarkdownTest` 补 6 例。
- **验证**：`compileDebugKotlin` 通过；JVM 单测 **109 例 0 失败**（本轮 +6）；release 从工作树重建为 1,460,365 B、SHA-256 `3960aa50331867adce406c7df4d2a70b353004f0261ff008203e7ed92840d3c2`（mtime 2026-11-24 00:24）。真机仍断开，设备复验未执行。
- **残留**：P2-7 仍缺 Compose UI 测试 / Macrobenchmark 与真机性能证据（无设备、且不加依赖的约束下暂无法补）；第 7 轮复审已发起（见下节）。

### REVIEW-LOOP 第 7 轮（外部审查 → 修复，2026-11-24）

- **外部审查**：以代码 HEAD a49e764 + 文档 HEAD 7e39ed7 为对象做只读复审，报告 `review/rounds/echo-1.0.5-round7-review.md`（348 行 / 30,569 B）。结论：P0 = 0、P1 = 0、P2 = 9、P3 = 9，**有条件通过**；第 6 轮唯一 P1（首帧跟底）已在代码层修复，发布前建议先处理 P2-1 / P2-2 / P2-4 / P2-6 / P2-3。
- **A 组修复**（提交 e4e0c2e，9 files +164/−21）：P2-1 新增 `sendTick`，发送成功后 UI 用 `sendScrollTarget(size-1)` 强制回底；P2-2 `StreamSplit` 的扫描指针在 `indexOf `返回 -1 时推进到文本末尾并暴露 `scannedLength`，8K 单行流式不再 O(n²)；P2-3 主题淡入改 `snapTo(0.72f)` + 220ms、系统栏图标延迟到淡入结束切换、`SunMoonMark` 统一 220ms、`EchoMark` / `TypingDots` 以 `Motion.animationsEnabled` 为 key；P2-4 `stopGenerating` 先按已收到内容 `finishUsage` 再递增代际、`cacheCreateTokens` 按未命中价计费、`failed` 写入快照并在恢复时过滤；P2-6 导入时目标文件已存在则跳过不覆盖，坏索引备份为 `sessions.index.bad.<ts>.json` 后扫描消息目录重建。
- **B 组修复**（提交 69d21f9，10 files +425/−27）：P2-5 索引写按 `indexDirty` 节流（结构变更立即、流式文本增长最多每 30s 一次）；P2-8 失效改为按 `updatedAt` 派生（`isExpiredNow` / `refreshExpired`），`MainActivity.onResume` 重新计算；P2-9 提交 Gradle 8.9 wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` / `gradle-wrapper.properties`，含 `distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`），README / CI / baselineprofile / run_tests 改用 `./gradlew`。
- **验证**：`compileDebugKotlin` 通过；JVM 单测 **112 例 0 失败**（Billing 16 / Export 9 / FollowTail 6 / Format 4 / Markdown 35 / Protocol 15 / Sse 19 / TtsAudio 8）；release 从工作树重建为 1,460,365 B、SHA-256 `996804ad2f70d3193da9bbfaee71e91a8de88ad9bbb78583c861cd4924e27b91`（mtime 2026-11-24 01:02）。真机仍断开，设备复验未执行。
- **残留**：P2-7 仍缺 Compose UI 测试 / Macrobenchmark 与真机性能证据；P3 清理项（文档行号、时间线统一、节假日峰谷等）留下版；第 8 轮复审待发起。

### REVIEW-LOOP 第 8 轮（外部审查 → 修复，2026-11-24）

- **外部审查**：以 HEAD f4e519c 为对象做只读复审，报告 `review/rounds/echo-1.0.5-round8-review.md`（235 行 / 20,198 B）。结论：P0 = 0、P1 = 2、P2 = 7、P3 = 8，**不通过**；必修 P1-1（删除会话不重写索引）、P1-2（新建会话 / 索引 30s 窗口竞态）、P2-1（gradlew 缺可执行位），发布前建议同批 P2-2 / P2-5。
- **P1-1 / P1-2 索引必写与代数对账**（提交 `73bfbb3`）：`save()` 的索引判定改为 `indexWriteDue(urgent, indexDirty, ...)`，forceIndex 无条件写索引；`delete()` 置 `indexDirty` 并自增代数；新建会话发送时立即 `save(forceIndex = true)`；新增 `indexEpoch`，IO 成功回调只在代际未变时清脏，否则置 `forceIndexNext` 重试；启动 `loadIndexAsync()` 增加索引与消息文件对账，缺失会话从文件补行并重写索引（最多 50 条）。
- **P2-2 坏索引去重与元数据**：损坏索引先保留可解析行，再与从消息文件重建的行按 id 合并（`PersistencePolicy.mergeRebuilt`，可解析行优先，保留 pinned / revived / imported / cost），不再出现重复会话。
- **P2-5 导入索引失败可恢复**：`finalizeImport` 的索引 JSON 改在 IO 线程构建并写入，失败时保留 `indexDirty` / `forceIndexNext` 并在 notice 里提示「重启后自动从会话文件恢复」，启动对账兜底。
- **P2-3 / P2-4 流式尾部与主题过渡**：`StreamingTail` 只缓存已写满的 1200 字符块（`remember(complete)`），每个 token 只重排最后一段增长中的尾部；主题切换改为旧底色垫在图层外、新底色与内容一起在 `graphicsLayer` 内做 220ms 交叉淡入，系统栏图标在淡入中点翻转。
- **P2-1 wrapper 可执行位**：`gradlew` 以 100755 提交，CI 增加 `chmod +x gradlew` 防御；干净 clone 可直接 `./gradlew`。
- **P2-6 / P2-7 测试与文档**：新增纯 JVM `SessionPersistenceTest` 6 例（索引判定、脏标记代数、孤儿会话、坏索引合并），单测 112 → 118；文档按实际行为修正（`animateItem` 流式项跳过、尾部只缓存整块、索引结构变更立即写）。
- **验证**：`compileDebugKotlin` 通过；JVM 单测 **118 例 0 失败**（Billing 16 / Export 9 / FollowTail 6 / Format 4 / Markdown 35 / Protocol 15 / Sse 19 / TtsAudio 8 / SessionPersistence 6）；release 从工作树重建为 1,460,365 B、SHA-256 `0b6ee8939181296dc6fd94cabf6ac472071865408dbc7fbfa24cf40b09280160`（mtime 2026-11-24 01:31）。真机仍断开，设备复验未执行。
- **残留**：P2-6 仍缺 Compose UI 测试 / Macrobenchmark（无设备）；P2-7 / P3 的文档行号、节假日峰谷、Drawer 删除确认等留下版；第 9 轮复审待发起。

### REVIEW-LOOP 第 9 轮（外部审查 → 修复，2026-11-24）

- **对象与结论**：只读复审 review/rounds/echo-1.0.5-round9-review.md（214 行 / 23,766 B；对象 HEAD 35b5a92，代码 73bfbb3）判 **P0=0 / P1=2 / P2=7 / P3=8 不通过**；代码修复提交 716dfcd（8 files +363/−78）。
- **P1-1 索引加载门禁**：新增 indexLoaded / indexForceAfterLoad 与 PersistencePolicy.indexDueAfterLoad；加载完成前 save 不写索引（消息照常落盘），applyLoaded 之后补一次完整强制写。启动加载窗口内新建会话 / 导入不再用不完整快照覆盖旧索引。
- **P1-2 索引缺失恢复**：索引文件不存在且无 legacy 文件时走 rebuildAllAsync 扫描消息文件重建；已有索引的对账改为按文件最近修改优先、**不再截断 50 条**，并在 IO 完成全量恢复后写回索引。
- **P2-1** finalizeImport 成功清脏前校验 indexEpoch，导入期间的新 markDirty 不会被误清；索引行改在 IO 线程构建。
- **P2-2 删除一致性**：新增 store/tombstones.json 墓碑，删除顺序改为「墓碑 → 删文件 → 写索引」；启动加载过滤墓碑行并补删残留文件，删除失败会提示；Drawer 删除增加确认对话框。
- **P2-3 恢复元数据**：会话级 title/updatedAt/expired/revived/contextMode/imported/pinned/costCny 随消息文件落盘，索引丢失后对账恢复不再重置置顶 / 复活 / 导入 / 费用。
- **P2-4 流式代理对**：新增 streamStableLength，稳定前缀边界落在高代理项之后时回退一格，流式 emoji 不再被两个 Text 各显示半个。
- **P2-5 主题淡入**：旧底色由 AppState 在主题变更前捕获（日/夜、暖黄/淡绿、自定义底色均覆盖），EchoApp 改为「旧底色全屏垫底 → 新底色+内容全屏 alpha 淡入 → 安全区内边距」，状态栏/导航栏不再落后一拍。
- **P2-6 / P2-7**：新增纯 JVM 用例覆盖加载门禁、恢复对账顺序、重复 id 合并、代理对边界，单测 118 → **121**；README / ARCHITECTURE / 本节 / tts.md / RELEASE 同步改成可验证表述。
- **P3**：删除旧注释、主题注释改「中点翻转」、load 写索引同步 lastIndexWriteAt、导入索引行移 IO、对账按最近修改优先、mergeRebuilt 重复 valid id 改 putIfAbsent、删除确认；P3-6（IO 线程构造 Session 并写 Compose 状态）仍留待下版。
- **验证**：compileDebugKotlin 通过；JVM 单测 **121 例 0 失败**（Billing 16 / Export 9 / FollowTail 6 / Format 4 / Markdown 36 / Protocol 15 / Sse 19 / TtsAudio 8 / SessionPersistence 8）；release 从工作树重建为 1,480,077 B、SHA-256 e3acef1f714b91296a412dc8f68069cf2f608ee6276121f86a57343b9de161d5（mtime 2026-11-24 01:55）。真机仍断开，设备复验未执行。
- **残留**：P2-6 仍缺 Compose UI / Macrobenchmark 与集成测试；P2-7 设备观感项待实测；P3-6 留待下版；第 10 轮复审待发起。
### 性能（真机实测，0.9.2 / versionCode 13）

- **上下文条轮询**：流式生成时每 500ms 刷新 token 估算。原实现每次都遍历整段会话（`historyMessages()` 过滤 + `sumOf`，长会话持续产生临时 List/String 垃圾）；现在发送时缓存历史字符数 `contextBaseChars`，轮询只叠加当前回复的 `text.length`（O(1)），`onDone/onError/stopGenerating` 再全量校准一次。`refreshContextTokens()` 移到 `streaming = true` 之后调用，缓存才会生效。
- **冷启动**：baseline profile 引导的 speed-profile AOT 中位 400 ms（verify 489 ms，约 -18%）。
- **对话滚动**（536 轮「意识」会话，连续上/下各 8 次 swipe，`dumpsys gfxinfo`）：Total frames 723–766，modern jank 0.55%–2.65%（多轮中位约 2.2%），90th/99th percentile 11–13 ms。曾出现一次 50%+ 的异常轮：`Slow issue draw commands` 368 而 `Slow UI thread` 仅 29，判断为系统侧 GPU/窗口干扰（Huawei 系统进程），不是应用主线程卡顿。
- **当时的无隐藏热点判断（第 4 轮）**：入场 / 呼吸 / TypingDots 均为有限次动画，系统「移除动画」时 `Motion.animationsEnabled=false` 直接进终态，无逐帧无限动画；第 5 轮复审又指出 Markdown 尾段全量重建与主题逐帧过渡仍属热点，已在本轮修复（见上节）；抽屉列表 `computeDrawerView` 走 `derivedStateOf`；`revision` 只在抽屉派生缓存读取（流式 publish 不会整屏重组）；会话索引在 `echo-store` 单线程解析；`KeyVault` 只在有 Key 时才访问 Keystore；欢迎页入场动画走 `graphicsLayer` lambda（绘制阶段读值，不触发重组）。
- **模式差异**：性能模式（设置 → 运行模式）把存储线程提到 NORM、流式发布帧上限 33ms、保存延迟 260ms、更多会话常驻；正常模式 MIN/16ms、700ms、更积极 evict。

## 5. 已知限制

- Markdown/LaTeX 由自研 `Markdown.kt` 渲染，属手写子集：不支持表格、图片、嵌套列表；块级公式有一定布局开销。流式时最后未闭合段以纯文本尾行渲染，回答完成后整体 Markdown 排版。
- 导入按会话 id 合并去重，只保留当前分支；附件与图片不会重传。
- 全会话 ZIP 导出无进度条、不可取消（< 10 s，可接受）；单会话导出整份 JSON 解析，存在内存峰值。
- CI 工作流已就绪、本地 `scripts/run_tests.sh --smoke` 演练全绿（编译 + 121 单测 + mock 自检 + 冒烟），但尚未在 GitHub 实跑（用户要求暂不发布）；联网搜索真实 Key 全链路已于 1.0.2 打通（见 §4 真 Key 冒烟）。
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
*由 DeepSeek-V4.1-Flash 构建；第三方客户端，与 DeepSeek 官方无隶属关系。*

## 8. 1.x（MIT 版）发布基线（2026-11-23）

**身份与版本**

- applicationId / namespace：**app.echo**；旧包名 app.xuxie 已废弃，禁止再使用（与旧版数据不互通，属已确认的破坏性变更）。
- versionCode / versionName：1.0.0 = 1 / 1.0.0、1.0.1 = 2 / 1.0.1、1.0.2 = 3 / 1.0.2、1.0.3 = 4 / 1.0.3、1.0.4 = 5 / 1.0.4（1.0.4 未单独发 dist 包，改动并入 1.0.5）；当前 **1.0.5 = 6 / 1.0.5**。两者只在 gradle.properties（echo.versionCode / echo.versionName）维护，app/build.gradle 只读取。
- minSdk 26 / targetSdk 35 / compileSdk 35。

**签名**

- Release keystore：D:/desktop/deepseek/keys/echo-release.jks（PKCS12，RSA 4096，别名 echo，有效期 10000 天）；**不入库**。
- 口令与路径存放在 GRADLE_USER_HOME/gradle.properties 的 echo.release.* 中；无配置时自动回退 debug 签名。
- 证书 SHA-256：5E:DA:7A:67:36:BD:0C:75:7A:8D:B4:A1:89:DD:28:04:8F:4B:8B:DC:64:E8:64:DC:1D:30:A8:75:CB:99:78:CA
- SHA-1：34:D7:42:34:BE:83:28:74:AF:DA:B4:9D:29:5A:C7:90:AE:8A:4B:70
- **备份要求**：keystore 与口令至少备份三处异地；丢失将无法再发布同签名更新。

**构建产物**

- **1.0.5 / versionCode 6**（TTS 朗读重写：官方 24 kHz 流经 Up2 半带 FIR 升采样到 48 kHz 输出，track buffer 64 KB / prebuffer 32 KB，修复 1.0.4 的噪声与 1.0.5 首版 48k 播放的缓冲超时静音；横屏欢迎页紧凑布局、旋转不重建 Activity、输入草稿 rememberSaveable；COLOR-V3 五色主题与蓝白 1.6:1 命令提示符图标；REVIEW-LOOP 第 1–9 轮修复）：`app/build/outputs/apk/release/app-release.apk`，1,480,077 B，mtime 2026-11-24 01:55（第 9 轮修复后从工作树重建，含索引加载门禁与缺失重建、删除墓碑、恢复元数据、流式代理对边界、主题全屏交叉淡入；真机断开，设备复验证据仍来自旧包 `e7e59e10…` / `900ba8c8…`，流式与跟底路径只做 JVM 编译与单测验证）；SHA-256 `e3acef1f714b91296a412dc8f68069cf2f608ee6276121f86a57343b9de161d5`；emulator-5554 安装记录 versionCode=6 / versionName=1.0.5（旧包）；AudioFlinger 取证与 QEMU 抓音见 `4「TTS 48k 重采样与静音修复」。
- **1.0.3 / versionCode 4**（协议回归 + 本地 CI 演练：把请求体拼装与 SSE 循环从 `ApiClient.stream` 抽成纯函数 `buildChatBody` / `pumpSse`，新增 `ProtocolTest.kt` 15 例；`scripts/run_tests.sh --smoke` 本地跑通编译 + 91 单测 + mock 自检 + normal/truncated/429 冒烟）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 09:40；SHA-256 `d2c6f62a89f839d6341ca84f196167a24d5fa1ef40e09d762e91d9940e67a42c`；emulator-5554 安装后 dumpsys 显示 versionCode=4 / versionName=1.0.3；重构后真机 ping 冒烟正常（回复「Pong! 🏓」，「本轮 ¥0.0001 · 输入 5 / 输出 15 tokens」，无「连接提前结束」报错）。
- **1.0.2 / versionCode 3**（导入文案纠错：答疑中心与导入 FAQ 由「会覆盖本机全部会话」改为「按会话 ID 合并、已存在跳过、附件不导入、全程不上传」，与导入实现口径一致）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 09:29；SHA-256 `fbb19241fdfd9c8adf211d63e382d45193f3ea6b848264adc2721f9dd8e2f78b`；emulator-5554 安装后 dumpsys 显示 versionCode=3 / versionName=1.0.2（lastUpdateTime 2026-11-23 09:30:06）；导入气泡、导入 FAQ 与答疑中心两条新文案均真机核对。
- **1.0.1 / versionCode 2**（导出打磨：`unsafeNameChars` 去半角逗号、单会话导出 notice 改为「已导出 Markdown 文件」）：`app/build/outputs/apk/release/app-release.apk`，1,439,005 B，mtime 2026-11-23 08:38:17；SHA-256 `b524753d18276cc416c5882cef461125d80138a3e174dd9254659c67b0ec0836`；apksigner 校验 signer 证书 SHA-256 与上节一致；emulator-5554 安装后 dumpsys 显示 versionCode=2 / versionName=1.0.1（lastUpdateTime 2026-11-23 08:38:32）。
- **1.0.0 / versionCode 1**（MIT 版基线）：`app/build/outputs/apk/release/app-release.apk`，1,389,853 B。
- SHA-256：cf47437dac2f7f3cadf4c56d093f43fcff2097aebd255fb229f58c931cdcc0ca
- 安装验证：emulator-5554（1080x2340）全新安装 app.echo 1.0.0 成功，冷启进入欢迎页（空数据），dumpsys 显示 versionCode=1 / versionName=1.0.0。

**许可与文档**

- 新增 LICENSE（MIT，2026 Echo contributors）与 NOTICE（Compose/Kotlin/AndroidX/Apache-2.0 归属；Echo 名称/图标/视觉资产保留所有权利）。
- README 重写：品牌边界、AI 辅助 AS IS、免费非商业但 MIT 允许任何用途、维护边界（不做云同步/账号/后端/广告/内购/非公开接口）、AOT 表述改为“profile 引导、机器码设备本地生成”。
- 已知限制与进度同步：Markdown/LaTeX 渲染、单会话 Markdown 与全会话 ZIP 导出、README 八张实拍截图、导出文件名安全化、协议正确性（Sse.kt / maxTokens / message_stop 校验）、导入健壮性与兼容层文档（docs/protocol-compat.md）均已在 1.0.1 完成；1.0.2 修正导入文案口径并补完真 Key 冒烟（联网搜索 citations 全链路已打通，见 §4）；1.0.3 完成协议回归（抽 `buildChatBody`/`pumpSse` + `ProtocolTest` 15 例，总计 91 单测）与本地 CI 演练；1.0.4 重写 TTS 朗读（48 kHz 输出 + 24k→48k 升采样），1.0.5 修复 48k 播放缓冲超时导致的静音，并补横屏欢迎页、旋转不重建、输入草稿保留，以及落地外部审查 REVIEW-LOOP 第 1–7 轮修复（见 §4）。仍在路线图中的是：CI 在 GitHub 实跑（暂不发布）、旧 UI 三项收尾、COLOR-V3 五色强调色落地后的沙金增亮（1.0.6，用户反馈沙金偏暗、预设面板观感不佳）。

**说明**：本文第 3–5 节中标注 0.9.2 的性能/交互数据来自当时真机（app.xuxie）；1.0.0 仅变更身份与签名，代码行为未变，因此这些数据继续有效；1.0.5 的流式渲染与列表跟随改动及验证见 §3 与 REVIEW-LOOP 小节。
