# Echo 评审说明（REVIEW）

> 面向评审者的一页纸：项目定位、关键决策、本轮改动、验证方式、已知问题。
> 代码仓库：app-native/（Android，Kotlin + Jetpack Compose）。撰写时版本 0.9.2（versionCode 13）。

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
  - 模拟器冷启动（软件渲染，`am start -W -S`）AOT 后 954 / 1001 / 1014 ms，与 verify 状态基本持平——x86 模拟器启动瓶颈在 Compose 布局/窗口而非 dex 校验；真机收益需在真机同版本前后对比。

## 5. 已知限制

- Markdown / LaTeX 未渲染，公式按纯文本显示（下一步接入轻量 Markdown）。
- 导入只保留当前分支；附件与图片不会重传；不提供「导出全部历史」。
- release 使用 debug 签名，仅适合内测；上架需正式签名与隐私政策。
- 联网搜索（deepseek_web_search）尚未用真实 Key 跑全链路；UI 与请求结构已就绪（`web_search` 工具 + 引用渲染）。
- 性能数据：模拟器为软件渲染（swiftshader），gfxinfo 的 jank 比例不适合作为最终指标；以真机帧率与主观流畅度为准。

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
