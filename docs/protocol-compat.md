# DeepSeek API 兼容基线

> 适用版本：1.0.5（versionCode 6）。本文记录 Echo 实际调用的上游接口、字段、事件与容错口径，不是官方文档。
> 上游以 https://api.deepseek.com 的公开接口与官方文档为准；若本文与官方不一致，以官方为准，并回来修正本文与 Api.kt / Sse.kt。

## 1. 目的与边界

- 只使用公开 API。不调用未公开端点、不抓取网页接口、不代理账号登录。
- 传输层自实现：HttpURLConnection + 手写 SSE（无第三方网络库），解析逻辑集中在 Sse.kt，纯函数、可 JVM 单测。
- 本文件区分两类内容：**官方契约**（端点、请求字段、事件名、字段语义）与 **本应用容错**（超时、错误文案、截断判定、重试策略）。容错行为不改变上游协议。

## 2. 端点总览

接口 | 方法 / 路径 | 鉴权 | 响应 | 用途
对话流式 | POST {API_ORIGIN}/anthropic/v1/messages | x-api-key | text/event-stream | 发送消息、接收流式回答与思考
账户余额 | GET {API_ORIGIN}/user/balance | Authorization: Bearer | application/json | 设置/用量面板只读展示
语音合成 | POST {API_ORIGIN}/v1/tools/tts | Authorization: Bearer | audio/pcm 流 | 朗读消息
语音识别 | POST {API_ORIGIN}/v1/tools/asr | Authorization: Bearer | application/json | 录音转文字

- API_ORIGIN 默认 https://api.deepseek.com，编译期由 app/build.gradle 注入 BuildConfig.API_ORIGIN；可用 -PapiOrigin=... 覆盖（本地 mock / 联调）。
- 仅当 apiOrigin 是 http:// 时才会在 manifest 打开 cleartext；正式构建默认全 https。
- 应用不使用 OpenAI Chat Completions 兼容端点；对话固定走 Anthropic Messages 兼容面。

## 3. 对话请求（POST /anthropic/v1/messages）

请求头：

    x-api-key: <用户 Key>            （不使用 Authorization: Bearer）
    anthropic-version: 2023-06-01
    content-type: application/json
    accept: text/event-stream
    accept-encoding: identity
    （超时：connect 15 秒，read 180 秒；不使用缓存）

请求体字段：

字段 | 本应用取值 | 说明
model | deepseek-flash 或 deepseek-v4-pro | 联网搜索开启时强制 deepseek-flash（补充：官方搜索工具只在 flash 上工作）
max_tokens | 默认 32,768 | 代码常量 DEFAULT_MAX_TOKENS；设置页可选 8K / 16K / 32K / 64K；发送时钳制到 4,096–65,536
stream | true | 始终流式
thinking | {"type":"enabled"} 或 {"type":"disabled"} | 用户「深度思考」开关；不发送 budget_tokens
tools | [{"type":"deepseek_web_search","name":"deepseek_web_search"}] | 仅「联网」开启时发送；应用不发送 tool_choice
system | 字符串 | 仅当系统提示词非空，且是本会话首次发送或上下文压缩后的首次发送
messages[].role | user / assistant | 不发送 system 角色消息
messages[].content | 字符串，或 thinking 内容块数组 | 见下

assistant 历史消息的 content 形式：

- 普通情况：纯字符串（消息正文）。
- 深度思考开启，且该历史回复保存了 thinking 签名（signature 非空、thinking 非空）时：内容块数组

      [{"type":"thinking","thinking":"...","signature":"..."},
       {"type":"text","text":"..."}]

  这是官方扩展思考的推理链校验要求；签名来自流中的 signature_delta，落盘保存，之后按需回传。

本应用不发送的可选参数：temperature、top_p、top_k、stop_sequences、metadata、tool_choice、anthropic-beta、cache_control。提示缓存命中由上游服务端行为产生，客户端只读取 usage 里的 cache_read_input_tokens / cache_creation_input_tokens，不做主动控制。

消息裁剪（由 State 层决定，不是协议要求）：

- 只发送当前分支（按导入时的 parent 链还原），不发送被删除的旁支。
- 前部摘要模式：把本机生成的主题摘要拼到尾部第一条 user 消息前面，用 "（仅作背景，直接继续对话；不要向用户复述本摘要或提及内部标注。）" 约束模型；原文仍在本地。
- 上下文 token 是本地估算（字符数 / 2.5 + 12），不是官方 tokenizer。

## 4. SSE 事件处理

流按行解析：event: 行记事件名，data: 行拼接数据，空行触发解析。若 event 名为空，回退使用 data JSON 里的 type 字段。

事件 | 本应用行为 | 说明
message_start | 读取 message.usage，作为预填 usage（full=false） | 输入 token 预填值
content_block_delta (text_delta) | 追加正文缓冲 | 批量回投 UI
content_block_delta (thinking_delta) | 追加思考缓冲 | 思考区折叠展示
content_block_delta (signature_delta) | 保存 thinking 签名 | 用于后续轮次推理链校验
content_block_delta (citations_delta) | 收集 citation（url 非空才收） | 联网搜索来源，去重后展示
message_delta | 读取 usage（full=true）与 delta.stop_reason | 全量 usage 覆盖预填值
message_stop | 标记「正常结束」 | 唯一成功判据
error | 在回复尾部追加 "[错误] <message>"，标记失败 | SSE 级错误
content_block_start / content_block_stop / ping / 其他 | 忽略 | 本应用只消费增量与文本

未处理：tool_use 内容块、服务端工具调用循环、消息级多模态内容块。联网搜索由官方服务端执行，客户端只消费最终文本与 citations_delta；不渲染工具调用过程。

## 5. 连接结束与中断判定

- 收到 message_stop：视为完成，落盘并标记成功。
- 未收到 message_stop 且未主动取消：保留已生成内容，追加错误「连接提前结束：没有收到 message_stop，内容可能不完整。」，标记该轮失败并给出重试。
- 用户点暂停：置取消标志并断开连接；已生成内容保留，提示「已暂停生成；已保留当前内容。」；不发送任何「取消请求」到服务端（没有该协议，仅断开 SSE）。
- 客户端不做自动重试、不做指数退避、不静默重发；重试由用户点击触发（删除最后一条 assistant 后重发），避免不可见过的重复计费。

## 6. HTTP 错误映射

非 2xx 直接读取错误 JSON（error.message 记为 detail）并映射为中文提示：

状态 | 应用提示
400 且 detail 含 Content / risk / 审核 | 内容未通过官方审核，无法处理。
400 且 detail 非空 | 原样展示 detail
401 | API Key 无效或已过期，请在设置里重新填写。
402 | 余额不足，请检查 DeepSeek 账户。
413 | 文件过大（上限 8MB / 120 秒）。
429 | 请求过于频繁或额度受限，请稍后再试。
503 | 官方服务繁忙，请稍后再试。
504 | 官方服务超时，请稍后再试。
其他 | 有 detail 用 detail，否则「请求失败（HTTP <code>）」

网络异常（DNS、断网、读超时）展示异常 message；没有 message 时显示「网络连接失败」。

## 7. usage 与费用口径

- usage 字段：input_tokens、output_tokens、cache_read_input_tokens、cache_creation_input_tokens。
- 输入侧 token（用于展示与估价）= input_tokens + cache_read_input_tokens + cache_creation_input_tokens。
- 预算口径：按官方公开牌价估算；高峰为北京时间工作日 09:00–11:59 与 14:00–17:59，其余为低谷。
  - deepseek-v4-pro（元 / 百万 token）：缓存命中 0.30 高峰 / 0.15 低谷；未命中 9.0 / 4.5；输出 27.0 / 13.5。
  - deepseek-flash：缓存命中 0.04 / 0.02；未命中 2.0 / 1.0；输出 8.0 / 4.0。
- 应用内金额一律标注「估算」，最终以官方账单为准；不预扣、不代收、不加价。

## 8. 余额、TTS、ASR 契约

余额：GET /user/balance，Authorization: Bearer <Key>，超时 connect 10 秒 / read 15 秒。

- 读取 is_available；balance_infos[] 中优先 currency=CNY。
- 展示：¥总额（赠金 ¥x · 充值 ¥y）；is_available=false 时提示「账户当前不可用（余额不足或受限）」。
- 只读，不提供充值/转账。

TTS：POST /v1/tools/tts，Authorization: Bearer，Content-Type: application/json，Accept: audio/pcm, application/octet-stream。

- 请求体：{text, voice_id, format:"pcm", filter_markdown:true}；voice_id 为 stella（海星，女声）/ tide（暗潮，男声）。
- 响应：24kHz、单声道、PCM16 音频流；客户端用 AudioTrack 边收边播，预缓冲约 16KB。
- 计费：¥3 / 万计费字符；汉字计 2 字符、其他计 1；单请求计费字符上限 9000，长文本按句读切分多次请求；公开 API 不支持断点续播（停止或断网后需重读）。
- 每块播放成功才记一次本机账本；结束提示「朗读完成 · 计费约 ¥x」。

ASR：POST /v1/tools/asr，multipart/form-data，字段 file（文件名 echo-voice.wav，audio/wav），Authorization: Bearer。

- 本地录音 16kHz 单声道 PCM16，最长 120 秒，自动停；音频不落盘、不保存。
- 响应：{text, duration_ms}；秒数 = ceil(duration_ms/1000)，缺失时按 1 秒。
- 计费：¥0.78 / 小时，不足 1 秒按 1 秒；单次最高约 ¥0.03；识别失败不计费。
- 语音识别结果填入输入框，不自动发送。

TTS/ASR 与对话使用同一个 API Key、同一个账户；调用前弹计费确认，可勾选不再提示。官方限时 5 折未公布截止日期，应用一律按刊例价估算（偏保守），不按折扣价展示。

## 9. 已知不兼容 / 未实现（明确清单）

- 无 OpenAI Chat Completions 兼容层；不请求 /chat/completions。
- 不发送 anthropic-beta 头；不使用 prompt caching 主动控制（不设 cache_control）。
- 不发送 temperature / top_p / top_k / stop_sequences / metadata / tool_choice。
- 不处理 tool_use 内容块、不实现客户端工具循环；联网搜索由服务端工具完成。
- 不处理 content_block_start / content_block_stop / ping（静默忽略，不影响结果）。
- 不支持多模态消息（图片、文件、音频消息）；历史导入只保存附件文件名，不重传二进制。
- 无流式断点续传、无服务端取消协议、无自动重试与退避。
- 余额接口只读；不实现充值、账单明细、发票等官方平台能力。
- 不代理官方登录、不绕过人机验证、不调用非公开接口。

## 10. 协议变更时怎么维护

1. 先改 Sse.kt / Api.kt（解析与请求体）。
2. 在 app/src/test/java/app/echo/SseTest.kt 补事件样例单测（正常、缺 message_stop、SSE error、未知事件）；请求体拼装与 SSE 循环已抽成 `Api.kt` 的纯函数 `buildChatBody` / `pumpSse`，对应用例在 app/src/test/java/app/echo/ProtocolTest.kt（maxTokens 收敛、tools、system 省略、signature 历史回放、截断/取消/错误映射）。
3. 用 scripts/mock_sse_server.py 做本地联调：normal / truncated / error_sse / 429 / 500 五种模式，默认 18080 端口。
4. 同步更新本文件与 README.md 的能力/限制描述。
5. 若涉及新的必填字段或错误码，先确认官方文档，再决定是否降级兼容（本应用目前只做容错，不做多版本协议适配）。
6. 跑 `bash scripts/run_tests.sh --smoke`：编译 + JVM 单测（当前 146 例）+ mock SSE 自检 + 冒烟，全绿才算改完。

## 11. 版本与构建基线（与本协议相关）

- applicationId / namespace：app.echo；旧包名 app.xuxie 已废弃（不同 applicationId 不能覆盖升级）。
- minSdk 26 / compileSdk 35 / targetSdk 35；JDK 17；Gradle 8.9（仓库内含 wrapper：`./gradlew`，distributionSha256Sum 固定）。
- Kotlin 2.0.21、AGP 8.7.3、Compose BOM 2024.09.00。
- 版本号唯一来源：gradle.properties 的 echo.versionCode / echo.versionName（当前 6 / 1.0.5）。
- 发布签名与证书 SHA-256 指纹见 README.md；签名材料不入库。

## 12. 真 Key 实测记录（2026-11-23，emulator-5554）

- 对话流式、深度思考、联网搜索、TTS、ASR 五项均用真实 Key 跑通；以下为逐项记录。
- 联网搜索：`deepseek_web_search` 由服务端执行，客户端收到 `citations_delta` 后在回答末尾渲染「来源」列表（实测 6 条，编号 + 标题 + URL），与第 4 节描述一致。
- 用量字段：`message_start` 预填 `input_tokens`、`message_delta` 覆盖为全量 usage（含缓存读写），与第 7 节口径一致；余额来自 `GET /user/balance`，只读。
- TTS 实际返回 24 kHz 单声道 PCM16；客户端用 Up2 半带 FIR 升采样到 48 kHz 后交给 AudioTrack（48000 Hz / MONO / PCM16），1.0.5 起播放缓冲 64 KB。ASR 上传后按实际时长计费（实测 64 秒 / 1 次），模拟器无麦克风输入时识别文本为空属预期。
- 用户主动暂停不视为缺 `message_stop`：已生成内容保留、无「连接提前结束」报错，与第 5 节一致。
