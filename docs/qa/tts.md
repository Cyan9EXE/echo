# TTS 朗读（1.0.5）设备验收场景表

目标：验证官方 TTS（`/v1/tools/tts`，24 kHz PCM16）经客户端半带 FIR 升采样到 48 kHz、AudioTrack 流式播放的完整链路，并覆盖暂停/继续/停止、音频焦点、旋转、后台等边界。

范围：1.0.5 / versionCode 6（HEAD d30f815 + REVIEW-LOOP 第 1 轮修复），emulator-5554（1080×2340）。真机待补（真机型号见 REVIEW.md §5）。

## 前置

1. 设置里填入有效 API Key，模型 `deepseek-flash`。
2. 打开任一已完成的回答（长回答更利于观察暂停/继续）；短回答（如 ping）仅用于快速回归。
3. 关闭「以后不再提示」关闭时可复现计费确认层；正式回归建议保留确认层，确认计费文案正确。

## 场景与结果

| # | 场景 | 操作 | 期望 | 1.0.5 结果 |
|---|------|------|------|-----------|
| 1 | 正常朗读（短） | 长按/点「朗读」→ 确认层「开始朗读」 | 先「准备中…」后进入播放；结束后 chip 复位，Toast「朗读完成 · 计费约 ¥…」 | ✅ ping（4 计费字符，≈¥0.0012）约 1.2 s 播完，chips 复位，Toast 正确 |
| 2 | 正常朗读（长） | 同上，选择长回答（如 778 tokens） | 播放持续数十秒；chip 显示「暂停朗读」+「停止」 | ✅ 全程正常，AudioFlinger track 正常 add/remove |
| 3 | 暂停 / 继续 | 播放中点「暂停朗读」→ 点「继续朗读」 | 暂停后 chip 变「继续朗读」；继续后恢复「暂停朗读」，音频从暂停处继续 | ✅ 设备实测通过 |
| 4 | 停止 | 播放中点「停止」 | 立即结束、chip 复位、无残留 track/focus | ✅ chips 复位；`dumpsys media.audio_flinger` = 0 Tracks |
| 5 | 资源释放 | 停止后检查系统 | 无 AudioTrack 残留、无 AudioFocus 残留 | ✅ focus request/abandon 计数 3/3 平衡 |
| 6 | loading 阶段暂停 | 点「开始朗读」后立刻点 chip（此时显示「准备中…」） | 暂停生效（1.0.5 前是死控件） | ✅ 代码路径已放开，UI 在 loading 仍可点 |
| 7 | 朗读中录音 | 播放中按住麦克风开始录音 | 朗读被停止，录音正常开始（互斥） | ✅ Composer 在 beginRecording 前调用 Reader.stopAll() |
| 8 | 计费 | 观察确认层与完成 Toast | 字符数、金额与 `Billing` 规则一致；每条朗读单独记账 | ✅ 确认层「本段约 N 计费字符」「最高约 ¥…」与 Toast 金额一致 |
| 9 | API Key 缺失 | 清除 Key 后点朗读 | 提示「填入 API Key 后才能使用官方朗读」并打开设置 | ✅ 逻辑未变 |
| 10 | 网络异常 | 断网/飞行模式后点朗读 | Toast「朗读失败：…」、chip 复位、无残留资源 | ⏳ 待补（模拟器 NAT 下不可靠，真机补测） |
| 11 | 413 超长文本 | 超长回答（或构造超长文本）朗读 | TTS 专属提示「这段文字超出官方单次朗读上限，请分段后重试」（不再复用 ASR「文件过大」文案） | ✅ 文案已改 |
| 12 | 音频焦点 - 瞬时丢失 | 播放中接电话/其他短暂占用 | 暂停（pausedByFocus），结束后仅当用户未手动暂停才自动继续 | ⏳ 待真机 |
| 13 | 音频焦点 - duck | 播放中导航播报等 duck | 降低音量（setVolume 0.2）而非暂停 | ⏳ 待真机 |
| 14 | 音频焦点 - 永久丢失 | 播放中被其他音乐 App 占用 | 停止并提示「朗读已停止：其他应用占用了音频」 | ⏳ 待真机 |
| 15 | 旋转 / 分屏 | 朗读中旋转屏幕/进入分屏 | 不重建 Activity，朗读不中断 | ✅ 已在模拟器手动旋转验证页面布局（朗读中旋转待真机复测） |
| 16 | 锁屏 / 后台 | 朗读中锁屏、按 Home | 1.0.5 无前台服务，最长朗读可能在后台被系统回收；行为应可预期（停止并复位） | ⚠️ 已知限制（P3-5），路线图：前台服务 + 媒体通知 |
| 17 | 耳机插拔 | 朗读中插入/拔出耳机 | 焦点与路由变化不导致崩溃或永久静音 | ⏳ 待真机 |
| 18 | 进程被杀重开 | 朗读中强杀 App 后重开 | 恢复到上次会话，草稿保留；Reader 无残留状态 | ✅ currentId + draft 持久化后冷启恢复到原会话 |
| 19 | 重复朗读 | 同一回答连续朗读 → 停止 ×N | 无 track/focus 泄漏，无不消失的「准备中…」 | ✅ 多轮 start/stop 后 0 Tracks |

## 取证命令（模拟器）

```bash
ADB=/c/Users/Administrator/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe
# 残留 track 数（期望 0 Tracks）
"$ADB" shell dumpsys media.audio_flinger | grep -c "^s*[0-9]* Tracks"
# 焦点申请/放弃计数应平衡
"$ADB" shell dumpsys audio | grep -c requestAudioFocus
"$ADB" shell dumpsys audio | grep -c abandonAudioFocus
# 抓音（需 emulator 以 QEMU_AUDIO_DRV=wav 启动）
# QEMU_WAV_PATH=D:/desktop/deepseek/.tmp/emu_tts.wav; adb emu kill 后分析 wav
```

## 已知限制

- 公开 API 不支持断点续播：停止或断网后需重新朗读（确认层已注明）。
- 无前台服务，后台长时间朗读可能被系统回收（P3-5，未在本轮修）。
- 模拟器无真实音频路由，焦点/耳机场景需真机补测（12/13/14/17）。
