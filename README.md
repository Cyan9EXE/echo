# Echo

原生 Android 客户端（Kotlin + Jetpack Compose）。把 DeepSeek 导出的历史会话 ZIP 导入本机，逐会话复活已超过 7 天未活跃的对话；对话直接走用户自己的 API Key（BYOK），不经过任何中转服务器。

> 由 **DeepSeek-V4.1Flash** 构建。第三方客户端，与 DeepSeek 官方无隶属关系；名称与商标归其所有者。

## 功能

- **历史导入**：读取官方导出的 chat_sessions/*.json，按 current_message_id → parent_id 还原当前分支；带气泡式导入向导与置顶答疑入口。
- **逐会话复活**：失效会话可选「完整原文」或「前部摘要 + 后部原文」；摘要由本机生成，不调用 API、不产生额外 token。
- **对话保留面板**：按时间 / 条数 / 总字数排序；长对话默认折叠。
- **上下文透明**：显示当前 token / 1M、占比、原文或摘要模式，以及每轮与累计估算费用。
- **流式对话**：思考过程折叠；支持暂停、重试、复制、朗读。
- **官方语音**：TTS 朗读（¥3 / 万计费字符）与语音识别（¥0.78 / 小时），调用前弹计费确认，可勾选不再提示；本机用量账本。
- **联网搜索**：官方 deepseek_web_search 工具，回答附带来源链接。
- **外观**：日间 / 夜间 / 跟随系统，金 · 蓝 · 青 · 陶四种强调色，字号可调，切换有过渡动画。
- **隐私**：API Key 默认隐藏，连续点 3 次「显示」才展示，展示期间禁止截屏；Key、会话与附件只存在本机。
- **自适应**：720–3840 像素宽度的屏幕均可正常使用，关键操作热区不小于 48dp。

## 技术

纯原生，无 WebView、无自建后端、无第三方网络库（HttpURLConnection + 手写 SSE 解析）。20 个 Kotlin 文件、约 6k 行：

    app/src/main/java/app/echo/
      MainActivity / EchoApp     入口与导航
      ChatScreen / MessageViews  对话流、消息与操作
      Composer / WelcomeScreen   输入区与欢迎页
      Drawer / SettingsScreen    会话抽屉、设置、关于
      HelpDialogs / UsageSheet   答疑、导入向导、计费确认、用量
      State / Models / Api       状态、持久化、流式请求
      Billing / Speech           计费、TTS、ASR、用量账本
      Design / Theme / Glyphs / Format / Motion

- namespace = app.echo；applicationId = app.xuxie 为兼容老用户本地数据而保留。
- minSdk 26 / targetSdk 35。

## 构建

需要 JDK 17、Android SDK 35 与 Gradle 8.9（或直接用 Android Studio 打开本目录）：

    export JAVA_HOME=/path/to/jdk-17
    gradle :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

产物：app/build/outputs/apk/release/app-release.apk。默认 API：https://api.deepseek.com；如需指向本地 mock，可加 -PapiOrigin=http://10.0.2.2:8099。

## 隐私与费用

- 会话、附件、API Key 全部保存在本机；卸载即删除。
- 对话、朗读、语音识别的费用由你的 API Key 直接支付给 DeepSeek，本应用不预扣、不代收、不额外加价。
- 应用内金额为按官方公开价估算，以官方账单为准。

## 已知限制

- Markdown / LaTeX 尚未渲染，公式按纯文本显示。
- 导入只保留当前分支；附件与图片不会重传。
- 尚未提供「导出全部历史」与正式签名仓库（当前 release 使用 debug 签名，仅适合内测）。
- 联网搜索与真实 API 全链路仍在用真实 Key 验证中。

## 数据

本仓库不包含任何用户会话、附件或 API Key。
