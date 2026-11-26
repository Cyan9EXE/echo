# Echo（回声）

原生 Android（Kotlin + Compose）DeepSeek 客户端：导入官方导出的历史会话，逐会话复活超过 7 天未活跃的对话；对话直接走你自己的 API Key（BYOK），不经过任何中转服务器。

版本 1.0.6（versionCode 7）· MIT · 由 DeepSeek-V4.1-Flash 构建
第三方客户端，与 DeepSeek 官方无隶属关系。

## 下载

- GitHub Release **v1.0.6**：APK / baseline-profile zip / SHA256SUMS
- minSdk 26 / targetSdk 35；签名 V2 + V3

## 功能

- **历史导入**：官方导出 ZIP，按 current_message_id → parent_id 还原当前分支，同 id 去重合并。
- **逐会话复活**：失效会话可选完整原文，或「前部摘要 + 后部原文」；摘要本机生成，不调 API。
- **对话**：流式输出、思考折叠、停止 / 重试 / 复制 / 朗读、联网搜索；单次输出 8K / 16K / 32K / 64K。
- **外观**：日间 / 夜间 / 暖黄 / 淡绿 × 六强调色；自定义底色分「浅色·日间」「深色·夜间」两套，明度按 CIE L*（0–100 感知明度），三只无极色带旋钮。
- **导出与透明**：单会话 Markdown、全会话 ZIP；token / 上下文占比与本轮费用估算。
- **隐私**：API Key 经 Android Keystore（AES-256-GCM）加密后只存本机；会话与附件不上传。

## 技术

纯原生，无 WebView、无自建后端、无第三方网络库（HttpURLConnection + 手写 SSE）。30 个 Kotlin 文件、13,432 行；JVM 单测 158 例 0 失败。

## 构建

需要 JDK 17、Android SDK 35：

    export JAVA_HOME=/path/to/jdk-17
    ./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

正式签名配置见 CONTRIBUTING.md；未配置时回退 debug 签名，仅适合本地内测。

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：模块、线程、持久化与一次发送的链路
- [docs/protocol-compat.md](docs/protocol-compat.md)：上游接口、SSE 事件与错误码
- [CONTRIBUTING.md](CONTRIBUTING.md)：构建、测试与代码风格

## 许可

代码：MIT（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)）。Echo / 回声 名称、图标与视觉资产保留所有权利，不在 MIT 范围内。

## 数据

本仓库不包含任何用户会话、附件或 API Key。
