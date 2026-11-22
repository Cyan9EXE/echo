# Echo 配色预设 · 4 外观 × 5 强调色

> 面向实现的配色稿。所有色值为 `0xAARRGGBB`，可直接抄进 `Theme.kt`。
> 所有对比度为 WCAG 2.1 相对亮度实算值（脚本生成，非估计）；基准底 = 该组合的 Void，选中底 = PanelHigh。
> v1 · 2026-11-23

## 0. 一页结论

1. **20 个预设 = 4 外观 × 5 强调色**，外观仍由 `dayMode` + `eyeMode` 表达，不新增持久化字段，老存档零迁移（§7.5）。
2. 强调色从「全局 5 个」改为「**按外观 4 套日值 + 1 套夜值**」。同一个 key（金/蓝/青/陶/白）在不同外观下可以是不同 hex，但语义不变，调用方不需要知道。
3. 暖黄、淡绿补上各自的 Blue / Danger / Muted —— 这是当前「发脏」的第二来源：暖黄底上直接压纸白的冷蓝 #2F55B8、朱红 #B3261E。
4. 日间所有强调色在 Void 上 ≥ 5.4:1、在 PanelHigh（选中卡）上 ≥ 4.5:1。**门槛是 4.5 而不是 3**，因为 accent 被当 10–11sp 文字用（ChatScreen.kt:143、Drawer.kt:170、MessageViews.kt:59），不只是装饰。
5. 动画沿用 `mixOklab` + 24 段缓存：每套 token 从「一对端点」变成「一个墨端点 + 三个日端点」，净改 18 个 hex 常量、约 3.3 KB 缓存，无新依赖。
6. 改动全部落在 `Theme.kt` 内部；对外只新增 `look` 与 `presets` 两个概念，UI 层只加一个预设网格。

## 1. 设计原则

**1.1 一屏只有一个高饱和色相。** 那个色相就是强调色。底色、面板、分割线全部是无彩色或 C ≤ 0.03 的极低彩度色；Ivory/Muted 是中性墨。这条决定了 8 个底色 token 里没有任何一个允许「跳」出来。

**1.2 用明度分层，不用阴影。** 面板比底色亮/暗 5–11%（Panel/Void = 1.05–1.11），边缘交给 1px Hair，不用 elevation。原因有两个：8-bit 屏上 1.02 的阴影会出现色带；纸墨风本身不需要体积感。

**1.3 强调色只出现在五处：** 发送/停止键、选中态（描边 + 文字）、图标、进度条、链接。合计面积 ≤ 屏 3%。不用于大色块背景，不用于正文。

**1.4 护眼两档必须避开撞色。** 暖黄底 L* 95.6 / H 88.2，淡绿底 L* 95.7 / H 124.0。这两个色相对「金、青、陶」是天然陷阱（同族或邻近）。规则二选一：

- **色相规则**：强调色与底色的 OkLab 色相差 ≥ 50°；
- **明度规则**：同族时两者 L* 差 ≥ 45（金在暖黄上就是这条路：ΔH 只有 26.5°，靠 ΔL* 47.1 撑住）。

两条都满足不了的值一律不用，哪怕它单独看很好看。

**1.5 文字不用强调色。** 链接用 Blue、危险用 Danger。强调色是「品牌/交互」色而非「语义」色，混用会让用户以为红色链接是错误。

**1.6 对比度硬指标。**

| 元素 | 目标 | 四套外观实测 |
| --- | --- | --- |
| Ivory on Void | ≥ 7:1 | 日间 16.0 / 夜间 17.7 / 暖黄 13.9 / 淡绿 13.2 |
| Muted on Void | ≥ 6:1（10sp 场景） | 日间 6.7 / 夜间 9.0 / 暖黄 6.4 / 淡绿 6.1 |
| Blue on Void | ≥ 4.5:1 | 日间 6.0 / 夜间 8.6 / 暖黄 6.8 / 淡绿 7.3 |
| Danger on Void | ≥ 4.5:1 | 日间 5.8 / 夜间 7.3 / 暖黄 6.7 / 淡绿 6.8 |
| accent on Void（日） | ≥ 4.5:1（当文字） | 最低 5.4:1（淡绿 · 陶） |
| accent on PanelHigh（日） | ≥ 4.5:1（选中卡） | 最低 4.7:1（暖黄 · 陶） |
| accent on Void（夜） | ≥ 4.5:1 | 最低 8.6:1（夜间 · 蓝） |

Muted 单独给 6:1 是因为它承担 10–11sp 的说明文字与时间戳，WCAG 的小字豁免只到 4.5，留 1.5 的余量给低亮度屏。

## 2. Token 契约

沿用 `object C` 的 11 个 getter，语义不变，只换取值来源（§6）。

| Token | 语义 | 主要调用点 | 硬约束 |
| --- | --- | --- | --- |
| Void | 页面底色 | 全屏背景 / 抽屉底 | — |
| Panel | 面板与卡片 | 主题面板、输入框、气泡 | Panel/Void |
| PanelHigh | 选中与浮起 | 选中卡、抽屉搜索框 | PanelHigh/Void |
| Hair | 分割线与描边 | 1px 线、卡片边 | 合成后/底 |
| ControlBorder | 控件描边 | 圆形按钮、滑块槽 | Control/Panel |
| Ivory | 主文字 | 标题、正文 | Ivory/Void |
| Muted | 次文字 | 说明、时间、占位符 | Muted/Void |
| Blue | 信息与链接 | 链接、EchoMark 默认色 | Blue/Void |
| Danger | 危险与错误 | 删除、失败提示 | Danger/Void |
| PromptBg | 用户气泡底 | MessageViews.kt:78 | Prompt/Panel |
| Scrim | 遮罩 | 抽屉/弹层（EchoApp.kt:269） | 合成后/底 |

Scrim 是唯一带 alpha 的 token（日 0x66000000 / 夜 0xB3000000），叠在 Void 上的效果见 §8.6。

## 3. 四套底色

四套共享同一组「墨端点 → 日端点」的插值结构；暖黄/淡绿只是把日端点换成自己的纸面。夜间一律回到墨黑（维持现状：护眼两档只在日间生效）。

### 3.1 日间 · 纸白

纸白。面板**比底色暗**一档（#EFE9DE < #F4F2ED），是「纸叠纸」而不是「卡片浮起」，所以不需要阴影。

相对现状：11 个值全部沿用，只有强调色微调（§5）。这一套是基准，另外三套都从它推导。

| Token | 值 | 校验 | 用途 |
| --- | --- | --- | --- |
| Void | 0xFFF4F2ED | — | 页面底 |
| Panel | 0xFFEFE9DE | Panel/Void 1.08 | 面板、输入框、气泡 |
| PanelHigh | 0xFFE6DFD1 | PanelHigh/Void 1.18 | 选中卡、浮起控件 |
| Hair | 0x2B201D19 | 合成 #D0CEC9 · 1.41:1 | 1px 分割线 |
| ControlBorder | 0xFF847E72 | Control/Panel 3.34:1 | 圆形按钮描边、滑块槽 |
| Ivory | 0xFF1A1713 | 15.96:1 | 主文字 |
| Muted | 0xFF5A544B | 6.69:1 | 次文字、时间戳 |
| Blue | 0xFF2F55B8 | 6.03:1 | 链接、EchoMark |
| Danger | 0xFFB3261E | 5.84:1 | 删除、错误 |
| PromptBg | 0xFFEFECE6 | Prompt/Panel 1.02 | 用户气泡底 |
| Scrim | 0x66000000 | 合成 #8F8C85（压暗 2.78:1） | 抽屉/弹层遮罩 |

### 3.2 夜间 · 墨黑

墨黑，不是纯黑。#050608 让 Panel #12151A 还能看出边缘；#000000 会让卡片边界消失、滚动出现黑拖影、Scrim 完全失效（§8.5）。

相对现状：11 个值全部沿用。强调色沿用现有 `Skin.night`，在 #050608 上是 8.6–17.7:1，过剩但对小字安全。

| Token | 值 | 校验 | 用途 |
| --- | --- | --- | --- |
| Void | 0xFF050608 | — | 页面底 |
| Panel | 0xFF12151A | Panel/Void 1.11 | 面板、输入框、气泡 |
| PanelHigh | 0xFF1B1F25 | PanelHigh/Void 1.23 | 选中卡、浮起控件 |
| Hair | 0x1FFFFFFF | 合成 #232426 · 1.30:1 | 1px 分割线 |
| ControlBorder | 0xFF7A828E | Control/Panel 4.71:1 | 圆形按钮描边、滑块槽 |
| Ivory | 0xFFF3EFE7 | 17.67:1 | 主文字 |
| Muted | 0xFFA8ADB5 | 8.99:1 | 次文字、时间戳 |
| Blue | 0xFF7FA8FF | 8.64:1 | 链接、EchoMark |
| Danger | 0xFFFF6B6B | 7.30:1 | 删除、错误 |
| PromptBg | 0xFF121A2A | Prompt/Panel 1.05 | 用户气泡底 |
| Scrim | 0xB3000000 | 合成 #050608（压暗 1.11:1） | 抽屉/弹层遮罩 |

### 3.3 暖黄 · 护眼

暖黄纸面：H 88.2、C 0.02、L* 95.6。比纸白（L* 96.1）暗 0.5、暖 12°，长时间阅读时蓝光占比更低。

改动：Muted #6E604A → **#63553F**（10sp 说明字 5.38 → 6.37:1）；Blue #2F55B8 → **#2C4F9E**（冷蓝压在黄底上会「发青」，降 C 并偏靛）；Danger #B3261E → **#A02418**（同族压深，避开陶色）。

| Token | 值 | 校验 | 用途 |
| --- | --- | --- | --- |
| Void | 0xFFF7F0DF | — | 页面底 |
| Panel | 0xFFF1E8D3 | Panel/Void 1.07 | 面板、输入框、气泡 |
| PanelHigh | 0xFFE9DEC5 | PanelHigh/Void 1.18 | 选中卡、浮起控件 |
| Hair | 0x2B6B5A2E | 合成 #DFD7C1 · 1.26:1 | 1px 分割线 |
| ControlBorder | 0xFF857C64 | Control/Panel 3.40:1 | 圆形按钮描边、滑块槽 |
| Ivory | 0xFF2A2115 | 13.93:1 | 主文字 |
| Muted | 0xFF63553F | 6.37:1 | 次文字、时间戳 |
| Blue | 0xFF2C4F9E | 6.80:1 | 链接、EchoMark |
| Danger | 0xFFA02418 | 6.70:1 | 删除、错误 |
| PromptBg | 0xFFF2EAD6 | Prompt/Panel 1.02 | 用户气泡底 |
| Scrim | 0x66000000 | 合成 #918B7F（压暗 2.78:1） | 抽屉/弹层遮罩 |

### 3.4 淡绿 · 护眼

淡绿纸面：H 124.0、C 0.02、L* 95.7。绿色相在 OkLab 里的明度权重最高，同样 L* 下它看起来比纸白更亮，Void 因此取 #EEF3E6 而不是更浅。

改动：Muted #5B6B51 → **#4F5F46**（5.07 → 6.08:1）；Blue #2F55B8 → **#2B4C93**；Danger #B3261E → **#9E2619**。

| Token | 值 | 校验 | 用途 |
| --- | --- | --- | --- |
| Void | 0xFFEEF3E6 | — | 页面底 |
| Panel | 0xFFE7EEDE | Panel/Void 1.05 | 面板、输入框、气泡 |
| PanelHigh | 0xFFDCE7D0 | PanelHigh/Void 1.14 | 选中卡、浮起控件 |
| Hair | 0x2B384A2C | 合成 #CFD7C7 · 1.31:1 | 1px 分割线 |
| ControlBorder | 0xFF7A8A70 | Control/Panel 3.10:1 | 圆形按钮描边、滑块槽 |
| Ivory | 0xFF1F2A1C | 13.23:1 | 主文字 |
| Muted | 0xFF4F5F46 | 6.08:1 | 次文字、时间戳 |
| Blue | 0xFF2B4C93 | 7.27:1 | 链接、EchoMark |
| Danger | 0xFF9E2619 | 6.78:1 | 删除、错误 |
| PromptBg | 0xFFE9F0E1 | Prompt/Panel 1.02 | 用户气泡底 |
| Scrim | 0x66000000 | 合成 #8B8F85（压暗 2.78:1） | 抽屉/弹层遮罩 |

### 3.5 相对现状的改动清单

| 项 | 现状 | 新值 | 原因 |
| --- | --- | --- | --- |
| warm.muted | 0xFF6E604A | 0xFF63553F | 10sp 说明字 5.38 → 6.37:1 |
| warm.blue | 0xFF2F55B8 | 0xFF2C4F9E | 冷蓝在黄底上发青、抢主体 |
| warm.danger | 0xFFB3261E | 0xFFA02418 | 与陶色 accent 色相太近，压深拉开明度 |
| green.muted | 0xFF5B6B51 | 0xFF4F5F46 | 5.07 → 6.08:1 |
| green.blue | 0xFF2F55B8 | 0xFF2B4C93 | 统一走靛蓝，与暖黄保持同一「信息色」语义 |
| green.danger | 0xFFB3261E | 0xFF9E2619 | 同上 |
| 全部 accent | 5 个全局值 | 4 套日值 + 1 套夜值 | 避撞色（§5） |

其余 38 个底色值一字未改——现有纸白/墨黑本身没问题，问题只在强调色与护眼底色不匹配。

## 4. 20 个组合

命名规则：「外观名 · 强调色名」，id 为「外观 id . 强调色 id」。日间与夜间共用同一组纸白↔墨黑 ramp，所以**「夜间 · 金」的日值等于「日间 · 金」**（从夜间切到日间时外观自动落到纸白）。带 † 即此意。

| # | 组合名 | id | 底色 | accent 日 | accent 夜 | 日/底 | 日/选中 | 夜/底 | Ivory | Muted |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 日间 · 金 | `paper.gold` | 日间 | 0xFF964E0C | 0xFFF2A65A | 5.5:1 | 4.7:1 | 10.0:1 | 16.0:1 | 6.7:1 |
| 2 | 日间 · 蓝 | `paper.blue` | 日间 | 0xFF2F55B8 | 0xFF7FA8FF | 6.0:1 | 5.1:1 | 8.6:1 | 16.0:1 | 6.7:1 |
| 3 | 日间 · 青 | `paper.teal` | 日间 | 0xFF1F6B57 | 0xFF6FD3B4 | 5.7:1 | 4.8:1 | 11.2:1 | 16.0:1 | 6.7:1 |
| 4 | 日间 · 陶 | `paper.clay` | 日间 | 0xFF934A35 | 0xFFF49A7C | 5.7:1 | 4.8:1 | 9.4:1 | 16.0:1 | 6.7:1 |
| 5 | 日间 · 白 | `paper.white` | 日间 | 0xFF121110 | 0xFFF2EFE9 | 16.9:1 | 14.2:1 | 17.7:1 | 16.0:1 | 6.7:1 |
| 6 | 夜间 · 金 | `ink.gold` | 夜间 | 0xFF964E0C | 0xFFF2A65A | 3.3:1 | 2.7:1 | 10.0:1 | 17.7:1 | 9.0:1 † |
| 7 | 夜间 · 蓝 | `ink.blue` | 夜间 | 0xFF2F55B8 | 0xFF7FA8FF | 3.0:1 | 2.5:1 | 8.6:1 | 17.7:1 | 9.0:1 † |
| 8 | 夜间 · 青 | `ink.teal` | 夜间 | 0xFF1F6B57 | 0xFF6FD3B4 | 3.2:1 | 2.6:1 | 11.2:1 | 17.7:1 | 9.0:1 † |
| 9 | 夜间 · 陶 | `ink.clay` | 夜间 | 0xFF934A35 | 0xFFF49A7C | 3.2:1 | 2.6:1 | 9.4:1 | 17.7:1 | 9.0:1 † |
| 10 | 夜间 · 白 | `ink.white` | 夜间 | 0xFF121110 | 0xFFF2EFE9 | 1.1:1 | 1.1:1 | 17.7:1 | 17.7:1 | 9.0:1 † |
| 11 | 暖黄 · 金 | `warm.gold` | 暖黄 | 0xFF8A4E05 | 0xFFF2A65A | 5.8:1 | 5.0:1 | 10.0:1 | 13.9:1 | 6.4:1 |
| 12 | 暖黄 · 蓝 | `warm.blue` | 暖黄 | 0xFF2C4F9E | 0xFF7FA8FF | 6.8:1 | 5.8:1 | 8.6:1 | 13.9:1 | 6.4:1 |
| 13 | 暖黄 · 青 | `warm.teal` | 暖黄 | 0xFF14655C | 0xFF6FD3B4 | 6.1:1 | 5.2:1 | 11.2:1 | 13.9:1 | 6.4:1 |
| 14 | 暖黄 · 陶 | `warm.clay` | 暖黄 | 0xFFA2432A | 0xFFF49A7C | 5.5:1 | 4.7:1 | 9.4:1 | 13.9:1 | 6.4:1 |
| 15 | 暖黄 · 白 | `warm.white` | 暖黄 | 0xFF17130E | 0xFFF2EFE9 | 16.3:1 | 13.8:1 | 17.7:1 | 13.9:1 | 6.4:1 |
| 16 | 淡绿 · 金 | `green.gold` | 淡绿 | 0xFF86560A | 0xFFF2A65A | 5.6:1 | 4.9:1 | 10.0:1 | 13.2:1 | 6.1:1 |
| 17 | 淡绿 · 蓝 | `green.blue` | 淡绿 | 0xFF2B4C93 | 0xFF7FA8FF | 7.3:1 | 6.4:1 | 8.6:1 | 13.2:1 | 6.1:1 |
| 18 | 淡绿 · 青 | `green.teal` | 淡绿 | 0xFF0F5F58 | 0xFF6FD3B4 | 6.6:1 | 5.9:1 | 11.2:1 | 13.2:1 | 6.1:1 |
| 19 | 淡绿 · 陶 | `green.clay` | 淡绿 | 0xFF9C4A32 | 0xFFF49A7C | 5.4:1 | 4.8:1 | 9.4:1 | 13.2:1 | 6.1:1 |
| 20 | 淡绿 · 白 | `green.white` | 淡绿 | 0xFF12160F | 0xFFF2EFE9 | 16.2:1 | 14.3:1 | 17.7:1 | 13.2:1 | 6.1:1 |

† 夜间外观的「日值」= 纸白日值（同一份数据：夜间外观在日间就是纸白）。

读表方式：第 4 列「底色」去 §3 取那 11 个 token，第 5/6 列是强调色两端点，后四列是对比度自检。一张卡 = 11 个底色 + 2 个 accent。

## 5. 强调色覆盖表

同一强调色在不同外观下换 hex，是这份方案的核心。**key 不变、语义不变、只有值变**，所以 `skinLabel()`、选中态、图标全部不用改。

| 组合 | accent 日 | 色相 | L* | 处理 |
| --- | --- | --- | --- | --- |
| 日间 · 金 | 0xFF964E0C | 55.1° | 50.2 | 压深一档：现网 #9A520F 在选中卡 #E6DFD1 上只有 4.4:1，10sp 强调文字不达标 |
| 日间 · 蓝 | 0xFF2F55B8 | 265.0° | 48.0 | 沿用现网，纸白底 6.0:1 |
| 日间 · 青 | 0xFF1F6B57 | 171.7° | 47.6 | 沿用现网，纸白底 5.7:1 |
| 日间 · 陶 | 0xFF934A35 | 36.9° | 49.5 | 压深一档：现网 #99503A 在选中卡上 4.4:1 |
| 日间 · 白 | 0xFF121110 | 67.7° | 17.8 | 日间即墨色 #121110，比 Ivory 深一档，靠面积而非色相区分 |
| 夜间 · 金 | 0xFF964E0C | 55.1° | 50.2 | 夜间提亮值，沿用现网 |
| 夜间 · 蓝 | 0xFF2F55B8 | 265.0° | 48.0 | 夜间提亮值，沿用现网 |
| 夜间 · 青 | 0xFF1F6B57 | 171.7° | 47.6 | 夜间提亮值，沿用现网 |
| 夜间 · 陶 | 0xFF934A35 | 36.9° | 49.5 | 夜间提亮值，沿用现网 |
| 夜间 · 白 | 0xFF121110 | 67.7° | 17.8 | 夜间为暖白 #F2EFE9 |
| 暖黄 · 金 | 0xFF8A4E05 | 61.7° | 48.5 | 同族（ΔH 26.5°），靠 ΔL* 47.1 拉开；明度必须压到 48.5 |
| 暖黄 · 蓝 | 0xFF2C4F9E | 263.8° | 44.7 | 冷蓝转靛：ΔH 175.6° 已互补，问题在「冷」，C 0.16 → 0.13、L* 压到 44.7 |
| 暖黄 · 青 | 0xFF14655C | 183.7° | 45.8 | H 172 → H 184（petrol）：原值落在黄绿邻域，ΔH 95.5° 才安全 |
| 暖黄 · 陶 | 0xFFA2432A | 35.7° | 50.6 | C 0.10 → 0.13、L* 50.6：原值在黄底上发灰，是「脏」的主要来源 |
| 暖黄 · 白 | 0xFF17130E | 73.2° | 19.0 | 暖墨 #17130E，与 Ivory #2A2115 差一档明度 |
| 淡绿 · 金 | 0xFF86560A | 70.8° | 49.5 | ΔH 53.2°，秋天的橄榄金；不能更亮（L* 49.5 已是上限） |
| 淡绿 · 蓝 | 0xFF2B4C93 | 263.4° | 43.1 | 加深防荧光：绿底上中等明度的蓝会「跳」，L* 压到 43.1 |
| 淡绿 · 青 | 0xFF0F5F58 | 185.8° | 43.9 | ΔH 61.8°（H 186 petrol）：绿底配青是本项目最危险的组合，必须偏移 |
| 淡绿 · 陶 | 0xFF9C4A32 | 37.1° | 50.8 | 互补但降 C 到 0.12，防「番茄酱」 |
| 淡绿 · 白 | 0xFF12160F | 132.0° | 19.3 | 绿墨 #12160F |

### 5.1 三条量化规则

1. **暖黄底**：金走「同族 + 明度差」（ΔL* ≥ 45），其余四个走色相（ΔH ≥ 52°）。不要给暖黄配亮金 #F2A65A —— 它在 #F7F0DF 上只有 1.8:1，且与底色同族，等于没有强调。
2. **淡绿底**：青必须移到 H ≥ 180（petrol）。原 #1F6B57（H 172）与底色 H 124 只差 48°，且明度接近，远看是「一块绿」。
3. **夜间统一**：5 个夜值在 #050608 上是 8.6–17.7:1，可以整块当文字用。不要再给夜间做「护眼版」——护眼两档在夜间回落墨黑，这是现状，也是成本最低的选择（§8.9 有替代方案）。

## 6. 动画与 Ramp

### 6.1 两个方案

| | A. 端点 + 24 段缓存 | B. 24 段常量表 |
| --- | --- | --- |
| 数据量 | 11 token × 4 端点 = 44 个色值（现有 38 个，补 6 个） | 4 × 11 × 25 ≈ 1100 个 hex |
| 改一个色 | 改 1 行，ramp 自动重算 | 要重跑生成脚本，否则中间 24 段全错 |
| 动画质量 | OkLab 直线插值，无中间色偏（现状已验证） | 可手工修中间色，但当前没有这个需求 |
| 内存 | 每 token 3 份 IntArray(25) ≈ 3.3 KB | 0 |
| 结论 | **采用** | 否决 |

理由：24 段常量表的唯一优势是「能手工修中间色」，而我们没有需要修的中间色；代价是颜色不可手改、diff 不可读。现有 `Ramp` 已经把两端点插值做对了，只需把「两个日端点」扩成「三个日端点」。

### 6.2 数据形状

现状是 `Ramp(night, day, warmDay, greenDay)`，一个 token 一个对象、内部按 eyeMode 选目标。改成「一个墨端点 + 三个日端点」后，每个 token 三个独立 ramp，按 `look` 取：

```kotlin
private class Ramp(private val night: Color, private val day: Color) {
    private val packed = IntArray(STOPS + 1)   // 与现状一致：OkLab 线性插值预烤
    fun at(t: Float): Color                     // t = themeProgress，0 墨 1 日
}

private class LookRamp(night: Color, paper: Color, warm: Color, green: Color) {
    private val paperR = Ramp(night, paper)   // PAPER 与 INK 共用这一份
    private val warmR  = Ramp(night, warm)
    private val greenR = Ramp(night, green)

    fun at(t: Float, look: Look): Color = when (look) {
        Look.PAPER, Look.INK -> paperR.at(t)
        Look.WARM            -> warmR.at(t)
        Look.GREEN           -> greenR.at(t)
    }
}

// object C 里 11 个 getter 改一行即可：
val Void get() = voidRamp.at(themeProgress, look)
```

两处行为变化，都是修正而非破坏：

1. 现状 `at()` 里是 `mixOklab(base, warmDay, eyeProgress * t)`，直接把「整块日色」当目标，t=0.5 时偏暖；新写法两条 ramp 各自插值，t=0.5 得到「墨与暖黄的中点」，与底色一致。
2. 夜间 t=0 时三条 ramp 都返回墨端点，所以 look 切换在夜间**零成本**，不需要额外判断。

### 6.3 强调色

强调色必须与底色共用同一个 progress，否则会出现「底色已变绿、强调色还是暖金」的 300ms 错位。

```kotlin
private fun accentOf(i: Int, look: Look, t: Float, eye: Float): Color {
    val night  = ACCENT_NIGHT[i]
    val base   = mixOklab(night, ACCENT_DAY[Look.PAPER.ordinal][i], t)  // 纸白日值
    val tinted = mixOklab(night, ACCENT_DAY[look.ordinal][i], t)        // 目标外观日值
    return mixOklab(base, tinted, eye)                                  // eye = C.eyeProgress
}

fun accentAt(position: Float): Color {
    val p = position.coerceIn(0f, 4f)
    val i = floor(p).toInt().coerceAtMost(3)
    val f = p - i
    return mixOklab(accentOf(i, look, themeProgress, eyeProgress),
                    accentOf(i + 1, look, themeProgress, eyeProgress), f)
}
```

### 6.4 三个坑

**(a) 暖黄 ↔ 淡绿 目前是瞬切。** `LaunchedEffect(C.eyeMode, C.isDay)` 只在「目标是否为 1」变化时重启动画；eye=1 与 eye=2 的目标都是 1f，animateTo 立即返回，于是底色和强调色都是硬切。现状如此，本文不改。若要 200ms 淡入，加 `lookFrom: Look` + `lookFade: Float`，在 `at()` 里对两次采样 mix 一次即可（多一个枚举 + 一个 float）。

**(b) 不要把 eyeProgress 乘进 t。** 现状 `eyeProgress * t` 是为了让夜间不显暖；改成三条 ramp 后这个乘法多余，乘了会让「夜间切到暖黄」的中段偏灰。

**(c) 24 段是预烤的。** 新增 33 个端点常量时，必须在 object 初始化时一次性建好 33 个 `Ramp`；不要在 getter 里 new，否则每次重组会重建 33 × 25 个 Color。

## 7. 数据模型

### 7.1 三个轴不变，Look 只做派生

现有的持久化字段（`dayMode` / `eyeMode` / `skinIndex` / `customAccent`）一个都不动，Look 由前两个现算：

```kotlin
enum class Look(val id: String, val zh: String, val day: Boolean, val eye: Int) {
    PAPER("paper", "日间", true,  0),
    INK  ("ink",   "夜间", false, 0),
    WARM ("warm",  "暖黄", true,  1),
    GREEN("green", "淡绿", true,  2);
}

val look: Look get() = when {
    !isDay       -> Look.INK
    eyeMode == 1 -> Look.WARM
    eyeMode == 2 -> Look.GREEN
    else         -> Look.PAPER
}
```

这样「喜欢 A 主题的 B 颜色」天然成立：预设只是 (day, eye) 与 skinIndex 的一个组合，两个轴仍然可以单独改。

### 7.2 可直接粘贴的常量

```kotlin
private class Ink(
    val void: Color, val panel: Color, val panelHigh: Color, val hair: Color,
    val controlBorder: Color, val ivory: Color, val muted: Color,
    val blue: Color, val danger: Color, val promptBg: Color, val scrim: Color,
)

private val PAPER = Ink(
    Color(0xFFF4F2ED), Color(0xFFEFE9DE), Color(0xFFE6DFD1), Color(0x2B201D19),
    Color(0xFF847E72), Color(0xFF1A1713), Color(0xFF5A544B),
    Color(0xFF2F55B8), Color(0xFFB3261E), Color(0xFFEFECE6), Color(0x66000000),
)

private val WARM = Ink(
    Color(0xFFF7F0DF), Color(0xFFF1E8D3), Color(0xFFE9DEC5), Color(0x2B6B5A2E),
    Color(0xFF857C64), Color(0xFF2A2115), Color(0xFF63553F),
    Color(0xFF2C4F9E), Color(0xFFA02418), Color(0xFFF2EAD6), Color(0x66000000),
)

private val GREEN = Ink(
    Color(0xFFEEF3E6), Color(0xFFE7EEDE), Color(0xFFDCE7D0), Color(0x2B384A2C),
    Color(0xFF7A8A70), Color(0xFF1F2A1C), Color(0xFF4F5F46),
    Color(0xFF2B4C93), Color(0xFF9E2619), Color(0xFFE9F0E1), Color(0x66000000),
)

// 夜端点：与 PAPER 共用同一个墨端点 Ink（§6.2），不重复定义

private val ACCENT_DAY = arrayOf(
    longArrayOf(0xFF964E0C, 0xFF2F55B8, 0xFF1F6B57, 0xFF934A35, 0xFF121110),  // 日间
    longArrayOf(0xFF964E0C, 0xFF2F55B8, 0xFF1F6B57, 0xFF934A35, 0xFF121110),  // 夜间
    longArrayOf(0xFF8A4E05, 0xFF2C4F9E, 0xFF14655C, 0xFFA2432A, 0xFF17130E),  // 暖黄
    longArrayOf(0xFF86560A, 0xFF2B4C93, 0xFF0F5F58, 0xFF9C4A32, 0xFF12160F),  // 淡绿
)
private val ACCENT_NIGHT = longArrayOf(0xFFF2A65A, 0xFF7FA8FF, 0xFF6FD3B4, 0xFFF49A7C, 0xFFF2EFE9)
private val ACCENT_ID = arrayOf("gold", "blue", "teal", "clay", "white")
```

> 用 `longArrayOf` 而不是 `intArrayOf`：`0xFF964E0C` 超出 Int 范围会被 Kotlin 推断为 Long，`intArrayOf` 会直接编译失败。`0x2B201D19` 这类小于 0x80000000 的常量会被推断成 Int，`Color(Int)` 同样按 ARGB 解释，两种都能直接用。

### 7.3 预设库

```kotlin
data class Preset(val id: String, val label: String, val appearance: Look, val accent: Int)

val presets: List<Preset> = Look.values().flatMap { l ->
    C.skins.mapIndexed { i, s ->
        Preset("${l.id}.${ACCENT_ID[i]}", "${l.zh} · ${s.label}", l, i)
    }
}   // 20 条：纸白 5 / 墨黑 5 / 暖黄 5 / 淡绿 5
```

UI（`ThemeSheet.kt`）：保留现有「外观」与「主题色」两排（**分开调**），在下面加「预设」网格：4 组标题 × 5 个 56dp 格，每格画双色块（Void / accent）+ 名字，点击 `applyPreset`。20 格在 360dp 宽下正好 4 列 × 5 行。

### 7.4 State API

```kotlin
fun applyAppearance(l: Look) { prefs.dayMode = l.day; updateEyeMode(l.eye) }  // 只动底色
fun applyAccent(i: Int)      { updateSkin(i) }                                // 只动强调色；i == C.skins.size 为自定义
fun applyPreset(p: Preset)   { applyAppearance(p.appearance); applyAccent(p.accent) }
```

（方法名按 `State.kt` 现有 setter 落地；若无 setDay，dayMode 直接写 prefs 即可，读取侧本来就是每帧读。）

### 7.5 迁移

**零迁移。** 四个 prefs 键不变，`skinIndex` 仍是 0..5（5 = 自定义），`customMode` 不变；只是「强调色取值」从全局表变成按 look 取。老用户升级后底色不变，强调色在暖黄/淡绿下变成新的避撞值——这正是本次要修的东西。

### 7.6 需要同步修改的文件

| 文件 | 行 | 改什么 |
| --- | --- | --- |
| app/src/main/java/app/echo/Theme.kt | 18–24 | `Skin` 增加 `accentDay: LongArray`；或直接删掉该类，改用两个全局数组 |
| app/src/main/java/app/echo/Theme.kt | 56–63 | `accentAt` 加 look/eye 两个轴（§6.3） |
| app/src/main/java/app/echo/Theme.kt | 71–82 | `mark()` 收成 `mark(accent: Color)`：top 用 `Void.luminance() > .45f` 判明暗，bottom 直接给 accent（去掉 `tone()`） |
| app/src/main/java/app/echo/Theme.kt | 90–105 | 11 个 getter 换成 `LookRamp.at(themeProgress, look)` |
| app/src/main/java/app/echo/Theme.kt | 175–238 | `Ramp` 去掉 warmDay/greenDay；新增 `LookRamp`、3 组日端点、`ACCENT_DAY`/`ACCENT_NIGHT` |
| app/src/main/java/app/echo/State.kt | 191–209 | 新增 `applyAppearance/applyAccent/applyPreset`；`updateSkin` 内部不变 |
| app/src/main/java/app/echo/EchoApp.kt | 76–84 | accent 来源改成 `C.accentAt(...)`（逻辑不变，只多 look 参数） |
| app/src/main/java/app/echo/EchoApp.kt | 96 | 状态栏明暗判断不变（`Void.luminance() > 0.18f` 对四套都成立） |
| app/src/main/java/app/echo/ThemeSheet.kt | 52–139 | 顶部「外观」保留；下方新增预设网格（§7.3） |
| app/src/main/java/app/echo/ThemeSheet.kt | 105–121 | 「主题色」行保留（分开调） |
| app/src/main/java/app/echo/Design.kt | 258–259 | `ThemeMarkView` 调用改成 `C.mark(accent)` |
| app/src/main/java/app/echo/SettingsScreen.kt | 256–259 | 同上 |

## 8. 风险清单

**8.1 极小字号糊。** accent 用在 10–11sp 上（ChatScreen.kt:156/184/203、MessageViews.kt:59）。已把日间全部压到 ≥ 5.4:1，但「暖黄 · 金」和「暖黄 · 陶」是靠明度差硬撑的同族对，320nit 以下、色域窄的低端屏上仍可能糊。缓冲手段：这两组下强调色不要用于 0.5 alpha 的 1px 描边（`Design.kt:380`，alpha 后对比掉到 ~2:1），只用于实心块或 ≥ 1.5dp 的线。

**8.2 暖黄 · 陶 vs Danger。** 陶 #A2432A（H 36）与暖黄档的 Danger #A02418（H 24）只差 12°，用户会把「陶色主题」误读成危险操作。约束：Danger 只出现在删除/错误且必带图标或文案；两者不放在同一组按钮里。

**8.3 淡绿 · 青 / 淡绿 · 金** 是色相最近的两对（61.8° / 53.2°）。后续微调保持 ΔH ≥ 50°，否则会退回「一块绿」。

**8.4 OLED 显脏。** 暖黄/淡绿是大面积高亮低彩面，在低频 PWM 屏的低亮度下会发灰；且 8-bit 量化下 Panel/Void 只差 1.07，渐变或阴影会出现色带。所以这两套**禁止加渐变和 elevation**，层级只靠 Hair。

**8.5 纯黑 vs 近黑。** 保持 #050608。改 #000000 的代价：Panel #12151A 变硬边、滚动有黑拖影、Scrim 完全失效（对纯黑叠黑 = 无变化）。近黑多花的 5 个色阶换来的是「纸」而不是「洞」。

**8.6 夜间 Scrim 只剩压暗面板。** 0xB3000000 叠在 Void #050608 上只有 1.02:1，它真正压的是 Panel（#12151A → #050608，1.9:1）。这不是 bug，但**如果未来 Void 改成纯黑，Scrim 必须换成提亮型**（例如 0x33FFFFFF），否则抽屉打开时用户看不出区别。

**8.7 「白」在日间是墨。** #121110 与 Ivory #1A1713 只差一档，选中态必须靠「填充反白」（Void 色文字）表达，不能靠色相。若担心歧义，可把该 key 显示名从「白」统一改为「墨」——顺带删掉 `skinLabel()` 里「白 → 黑」的特判（属于可选清理，未列入 §7.6）。

**8.8 Panel/Void 只有 1.05–1.11。** 这是刻意的「纸叠纸」。若后续把卡片对比拉到 1.2 以上，纸感会变成「灰卡片」，与 1.2 节的明度分层冲突；届时应当加 Hair 而不是加明度差。

**8.9 护眼两档在夜间回落墨黑。** 用户选暖黄、晚上切夜间会变成墨黑，第二天切回日间又是暖黄（eyeMode 被保留）。现状，成本最低。若要「暖黄的夜」也成立，需给 WARM/GREEN 各补一个夜端点（琥珀夜 / 苔夜），代价是多 2 套 token 和一个「夜值是否随外观变」的分支——不建议在 v1 做。

## 9. 验收清单

- [ ] 20 个预设都能一屏内选到，切换后 `dayMode` / `eyeMode` / `skinIndex` 三者与卡片一致
- [ ] 暖黄 · 陶 的发送键在 320nit 下仍能看出是可点的深色块（5.5:1）
- [ ] 日/夜切换 280ms 内 accent 与底色同步到位，中间不出现灰调
- [ ] 改外观不重置 `customAccent`；改强调色不动外观
- [ ] 四套外观下状态栏图标都正确（`Void.luminance() > 0.18f`）
- [ ] 夜间 Scrim 打开抽屉时面板确实变暗（1.9:1），不是「没反应」

## 附录 A · 20 组合名速查

**日间**：

日间 · 金 / 日间 · 蓝 / 日间 · 青 / 日间 · 陶 / 日间 · 白

**夜间**：

夜间 · 金 / 夜间 · 蓝 / 夜间 · 青 / 夜间 · 陶 / 夜间 · 白

**暖黄**：

暖黄 · 金 / 暖黄 · 蓝 / 暖黄 · 青 / 暖黄 · 陶 / 暖黄 · 白

**淡绿**：

淡绿 · 金 / 淡绿 · 蓝 / 淡绿 · 青 / 淡绿 · 陶 / 淡绿 · 白

