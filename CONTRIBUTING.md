# 参与贡献

Echo 是纯原生 Android 客户端（Kotlin + Jetpack Compose），无 WebView、无后端、无第三方网络库。提交前请先读 [docs/not-doing.md](docs/not-doing.md)：有些方向不是待办，而是明确不做。

## 1. 环境

- JDK 17（本机示例：JAVA_HOME=/d/jdk-17.0.20.1+1）。
- Android SDK 35。
- Gradle 8.9。仓库没有 gradlew / gradle-wrapper.jar，请安装 Gradle 8.9 并确保 gradle 在 PATH，或直接用 Android Studio 自带的 Gradle 工具窗口。
- 不需要模拟器也能跑 JVM 单测；整包构建需要 SDK 35。

设置 JAVA_HOME 的示例（Git Bash）：

    export JAVA_HOME=/path/to/jdk-17
    cd app-native

## 2. 构建与运行

    gradle :app:compileDebugKotlin      # 最快验证 Kotlin 能编译
    gradle :app:assembleDebug           # 生成 debug APK（app/build/outputs/apk/debug/）
    gradle :app:testDebugUnitTest       # 运行 JVM 单元测试
    gradle :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

- 默认 API 是 https://api.deepseek.com。若要指向本地 mock：-PapiOrigin=http://10.0.2.2:18080（模拟器访问宿主机）或 http://<宿主机 IP>:18080（真机同网）。不要用 mock 构建对外分发。
- release 签名材料不入库：在 GRADLE_USER_HOME/gradle.properties 配置 echo.release.storeFile / storePassword / keyAlias / keyPassword；缺失时自动回退 debug 签名，仅适合本地内测。不要把 keystore、口令、证书私钥提交进仓库。
- Baseline Profile 的生成文件（app/src/release/generated/）与手工生成产物不要提交。

## 3. 测试

- 现有 JVM 单测：SseTest（SSE 事件解析与容错）、BillingTest（计费与长文本切分）、FormatTest（格式化）、ExportTest（导出渲染）；MarkdownTest 随 Markdown 渲染层落地。
- 新增协议解析、计费或纯函数逻辑时请补单测；抓网络/时序问题优先写成 Sse.parse 的输入输出用例，而不是只看 UI。
- 本地协议联调：python3 scripts/mock_sse_server.py --port 18080（stdlib，无需第三方依赖）。支持 ?mode=normal / truncated / error_sse / 429 / 500；--selftest 可先自检。构建时加 -PapiOrigin 指向它。
- CI：.github/workflows/ci.yml（GitHub Actions）会跑 Kotlin 编译、JVM 单测与 mock server 自检，环境为 JDK 17 / Gradle 8.9 / Android SDK 35；本地命令与 CI 一致，PR 里请贴出本地执行过的命令与关键输出。

## 4. 代码风格与约定

- Kotlin 官方风格（kotlin.code.style=official），4 空格缩进；中文 UI 文案保持短句、克制、不夸大。
- 状态集中在 AppState（State.kt）：跨屏状态不要散落到 Composable 局部；新增持久化字段要考虑旧数据兼容与原子写。
- 网络只走 ApiClient / Sse，不要在 UI 层直接开 HttpURLConnection；SSE 解析保持纯函数、可单测。
- 计费只走 Billing / UsageLedger，新增任何会产生费用的调用必须在 UI 先明示计费口径。
- API Key 只经 KeyVault 读写；任何时候不得把 Key 写日志、写崩溃信息或上传。
- 不新增第三方依赖；确有必要时在 PR 里说明体积、许可与替代方案（当前连网络库都被刻意去掉）。
- 不做 WebView 壳、不抓未公开接口、不做账号代理——详见 docs/not-doing.md。

## 5. 提交信息

格式：type(scope): 简述

    feat(sse): 支持 signature_delta 并按轮次回传思考签名
    fix(state): 导入后同 id 会话保留本机版本
    docs(readme): 补充导出入口与最大输出设置
    test(billing): 增加长文本切分边界用例

- type：feat / fix / docs / refactor / test / chore（revert 可用 revert）。
- scope：api、sse、state、ui、drawer、billing、speech、export、docs、build 等；不确定可省。
- 一行不超过 72 字符；正文写动机、影响面与验证方式。不要把无关改动混进同一个提交。

## 6. Pull Request

- 先开 issue 或在已有 issue 下说明动机；纯文档修正可直接提 PR。
- PR 描述包含：改了什么、为什么、影响面（协议 / 存储 / 计费 / UI）、执行过的验证命令与结果、已知风险。
- UI 改动附真机截图（不要含 API Key、会话正文、个人数据；不要用合成截图冒充真机）。
- 涉及上游协议、字段或错误码：同步更新 docs/protocol-compat.md。
- 涉及能力或限制变化：同步更新 README.md；涉及架构变化：同步更新 ARCHITECTURE.md。
- 不提交构建产物、签名材料、local.properties、mock 生成的会话文件与任何用户数据。

## 7. 提问与反馈

- 缺陷：使用 issue 模板 Bug report；请写明应用版本（设置 → 关于 里的 versionName / versionCode）、Android 版本、机型、复现步骤与预期/实际。
- 功能建议：使用 Feature request 模板，先确认没有与 docs/not-doing.md 的边界冲突。
- 涉及 Key、账户与账单的问题，不要在公开 issue 里贴 Key 或完整账单截图。
