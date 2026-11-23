# Echo 强调色 V3 落地映射（COLOR-V3-impl）

> 交付：本文件（只写文档）。目标：把 COLOR-V3.md §6 变成可照做的代码改动映射，供 task-45 实施。
> 基线：2026-11-23 工作区（echo.versionName=1.0.5；Lead 的 20 项评审修复未提交）。行号以当前未提交工作区为准，1.0.5 提交后请按 §8 命令重新取证再改。
> 约束：本轮不改任何 .kt / .html / COLOR-V3.md；.kt 实施等 Lead 提交 1.0.5 后另发 task-45。

## 0. 结论（照做顺序）

1. **槽位顺序保持不变，只换颜色值**：0 金→沙金、1 蓝→雾蓝、2 青→黛青、3 陶→陶土、4 白→灰紫。skinIndex 是整数索引，旧数据天然完成迁移，不需要额外的迁移函数或版本键。
2. **改 3 个文件、5 个区块**：Theme.kt（skins / skinLabel / ACCENT_ID / ACCENT_NIGHT / ACCENT_DAY）、State.kt（skinIndex 默认值与非法值回退）、ThemeSheet.kt（swatch 增加 accent 参数、选中边框 1.5dp、两处文案）。
3. **默认强调色 = 雾蓝（skinIndex=1）**；设置页仍只显示 5 个 swatch，8 个候选里未入选的 3 色（靛蓝/苔绿/藕粉）只留在 COLOR-V3 存档，不进 UI。
4. **派生 token**：onAccent 继续用 C.Void（实测日 4.84–4.89、夜 7.20–7.26）；accentWeak 继续用 `accent.copy(alpha=.16f)`；选中边框用全色 accent（≥4.09/≥5.88，非文本 3:1 通过）；**日间 accent 作小字时必须加 `accentText`（accent 加深 9%）**，否则在 Panel 上仅 4.49–4.52、在同色 16% 底上仅 3.68–3.71，不达 4.5。
5. **验收 = 30 张截图（2 模式 × 5 色 × 3 屏）+ 6 组迁移回归 + 对比度复核表**；不改版本号、不加依赖、不动自定义强调色与自定义底色逻辑。

## 1. 现状取证（2026-11-23 未提交工作区）

| 文件:行号 | 内容 | 作用 |
|---|---|---|
| Theme.kt:27-33 | `skins = listOf(Skin("金"...))` 5 个 `Skin(label, night, day)` | 强调色名称与日/夜值（注意：第 2 参是夜间色） |
| Theme.kt:38-42 | `skinLabel(index)`，「白」日间自动改名「黑」 | 需要删除的特例 |
| Theme.kt:107-110 | `previewAccent(look,index)` | 预设矩阵小圆点用 |
| Theme.kt:112-118 | `accentAt(position)` | 顶栏/发送键/聊天 accent 的插值 |
| Theme.kt:120 | `tone(accent) = mixOklab(accent, Black, 0.30f)` | ThemeMark 下色 |
| Theme.kt:126-136 | `voidLuminanceFor` | 外观切换动画时长用，与色板无关 |
| Theme.kt:138-142 | `presetAccent(index)` | 切换动画取亮度差用 |
| Theme.kt:144-155 | `mark(...)` | 顶栏/设置页两色主题标记 |
| Theme.kt:180 | `ACCENT_ID = arrayOf("gold","blue","teal","clay","white")` | 预设 id 后缀 |
| Theme.kt:183-192 | `presets` 4 外观 × 5 色 = 20 | 设置页矩阵 |
| Theme.kt:345-347 | `ACCENT_NIGHT` longArrayOf 5 | 夜间强调色 |
| Theme.kt:350-355 | `ACCENT_DAY` 4 行 × 5（PAPER/INK/WARM/GREEN） | 日间强调色 |
| Theme.kt:357-368 | `accentNight / accentDay / accentOf` | 取色与插值入口 |
| Theme.kt:379 | `blueRamp`（C.Blue 链接蓝，独立于皮肤） | 见 §3.6 |
| EchoApp.kt:84-99 | `accentAnim`、`accent = if (customMode) customAccent else C.accentAt(...)` | 结构不变，只换数组 |
| ThemeSheet.kt:119-124 | `SheetSection("预设")` + 矩阵说明 | 文案「蓝色强调」需改「雾蓝」 |
| ThemeSheet.kt:126-137 | 强调色 Row，`C.skins.forEachIndexed` + ThemeSwatch | 5 swatch 入口 |
| ThemeSheet.kt:139-142 | 「白」只在夜间生效的说明 | 需重写 |
| ThemeSheet.kt:210-244 | PresetGrid：4×5 矩阵 | 不变 |
| ThemeSheet.kt:246-268 | PresetChip，265 行 previewAccent 画小圆点 | 不变 |
| ThemeSheet.kt:270-293 | ThemeSwatch，284 行 `border(1.dp, if(selected) C.Ivory.copy(alpha=.55f)...)` | 改 1.5dp accent 边框 |
| State.kt:29 | prefs 文件名 `continue-native` | 迁移与测试用 |
| State.kt:85-90 | skinIndex 初始化 + blueAccent 旧布尔 + coerceIn(0, skins.size) | 默认/非法值改这里 |
| State.kt:91 | customAccent 默认 `0xFF7FA8FF`（旧蓝） | 可选改为雾蓝夜值 |
| State.kt:180 | `prefs.edit().remove("blueAccent")` | 保留，不再读取 |
| State.kt:258-267 | customMode / updateSkin | 逻辑不变 |
| State.kt:297-304 | applyAccent / applyPreset | 逻辑不变 |
| State.kt:331-336 | selectCustomAccent（customAccent + skinIndex=skins.size） | 不动 |
| SettingsScreen.kt:355-385 | 设置页「外观/主题」行，376 用 C.mark | 不改，仅验收 |
| Design.kt:262-264 | ThemeSwitcher label = C.skinLabel | 不改（自动跟随新名） |
| Design.kt:380/387 | Chip 选中色/边框 accent(.5) | 不改，验收 |
| Composer.kt:153/177/225-229 | 聚焦边框 accent、光标 accent、发送键 accent 填充 + C.Void 字 | 不改，验收 |

## 2. 目标色板（唯一数据源）

### 2.1 槽位与色值（顺序不可变）

| idx | 中文名 | id | 夜间值 | 日间值 | 日最低对比 | 夜最低对比 | 定位 |
|---|---|---|---|---|---|---|---|
| 0 | 沙金 | sand | #B4974C | #7F6726 | 4.73 | 7.21 | 暖·贵重，承接原金 |
| 1 | 雾蓝 | mist | #759ECC | #4C6C91 | 4.74 | 7.26 | 冷·安静，默认色，承接原蓝 |
| 2 | 黛青 | slate | #60A6A5 | #3C7272 | 4.77 | 7.23 | 冷·沉静，承接原青 |
| 3 | 陶土 | clay | #D1877B | #965A50 | 4.73 | 7.20 | 暖·活跃，承接原陶 |
| 4 | 灰紫 | mauve | #B38DBE | #7E5F87 | 4.73 | 7.21 | 中性·克制，承接原白 |

对比口径：日间值对 paper #F3EFE7 / warm #F7F0DF / green #EEF3E6 取最小；夜间值对墨黑 #050608。App 实际 paper token 为 #F4F2ED，实测会再高约 0.1（§6.5）。

### 2.2 Theme.kt:27-33 替换

```kotlin
val skins = listOf(
    Skin("沙金", Color(0xFFB4974C), Color(0xFF7F6726)),
    Skin("雾蓝", Color(0xFF759ECC), Color(0xFF4C6C91)),
    Skin("黛青", Color(0xFF60A6A5), Color(0xFF3C7272)),
    Skin("陶土", Color(0xFFD1877B), Color(0xFF965A50)),
    Skin("灰紫", Color(0xFFB38DBE), Color(0xFF7E5F87)),
)
```

注意构造顺序是 `Skin(label, night, day)`：第一个颜色是夜间值（亮），第二个是日间值（深）。

### 2.3 Theme.kt:38-42 删除「白→黑」特例

```kotlin
/** 5 色均同时具备日/夜值，不再有「白」日间转黑的特例。 */
fun skinLabel(index: Int): String = skins.getOrNull(index)?.label ?: "自定义"
```

### 2.4 Theme.kt:180 ACCENT_ID

```kotlin
val ACCENT_ID = arrayOf("sand", "mist", "slate", "clay", "mauve")
```

Preset.id 只在 Theme.kt:186 构造、ThemeSheet 不使用（已 grep 确认无持久化），改名无数据风险。

### 2.5 Theme.kt:345-355 颜色数组

```kotlin
/** 夜间强调色（全局共享）。 */
private val ACCENT_NIGHT = longArrayOf(
    0xFFB4974C, // 沙金
    0xFF759ECC, // 雾蓝
    0xFF60A6A5, // 黛青
    0xFFD1877B, // 陶土
    0xFFB38DBE, // 灰紫
)

/** 日间强调色：按外观分四套，行序同 Look。 */
private val ACCENT_DAY = arrayOf(
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // PAPER
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // INK（只作过渡）
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // WARM
    longArrayOf(0xFF7F6726, 0xFF4C6C91, 0xFF3C7272, 0xFF965A50, 0xFF7E5F87), // GREEN
)
```

说明：V3 的日间值已对 paper/warm/green 三种浅底全部验证（最低 4.73），四行用同一组值即可；旧代码的逐外观微调是审美差异，不是对比度要求。若后续想保留微调，只允许在 §6.5 门槛内小范围改。INK 行在 themeProgress=0 时不会渲染，保持一致即可。

### 2.6 Skin 的 night/day 字段

grep 确认当前没有任何代码读取 `skin.night` / `skin.day`（只有 `skin.label` 被读），实际取色走 ACCENT_NIGHT / ACCENT_DAY。但 2.2 仍按语义填对，避免将来预览功能踩坑。

## 3. 派生 token 重算

### 3.1 onAccent（强调色上的文字/图标）

现有实现已是主题感知的 `C.Void`：
- Composer.kt:228 `SendGlyph(..., C.Void.copy(alpha = ignite))`
- SettingsScreen.kt:225 保存按钮 `Text(..., color = C.Void)`
- HelpDialogs/UsageSheet 等处同模式

5 色实测（文字= C.Void 日 #F4F2ED / 夜 #050608）：日 4.84–4.89:1、夜 7.20–7.26:1，全部 ≥4.5。**结论：不改代码，只做验收**。余量最小的日间约 4.84，不要为了「更亮」再调浅日间值。

### 3.2 accentWeak（浅色底）现状与复合色

现有代码用 `accent.copy(alpha = .16f)`（设置页选中项、模型/输出/运行模式选项、主题面板部分选中态）。在 Panel 上按 sRGB alpha 合成后：

| 色 | 日间复合底(16% over Panel #EFE9DE) | 日间 accent 作 10sp 小字 | 夜间复合底(16% over Panel #12151A) | 夜间 accent 作小字 |
|---|---|---|---|---|
| 沙金 | #DDD4C1 | 3.69:1（不过） | #2C2A22 | 5.12:1 |
| 雾蓝 | #D5D5D2 | 3.70:1（不过） | #222B36 | 5.13:1 |
| 黛青 | #D2D6CD | 3.71:1（不过） | #1E2C30 | 5.12:1 |
| 陶土 | #E1D2C7 | 3.68:1（不过） | #31272A | 5.13:1 |
| 灰紫 | #DDD3D0 | 3.69:1（不过） | #2C2834 | 5.11:1 |

**这是本次落地唯一的对比度硬伤**；旧代码同一位置也不达标（旧日间金 #964E0C 实测 4.12，旧蓝 4.44、旧青 4.23、旧陶 4.26；新 5 色更吃紧，3.68–3.71）。必须在 task-45 一起修，否则验收「日 ≥4.5」不过。

### 3.3 accentText（新增派生 token，修复 §3.2）

在 Theme.kt 的 `tone(accent)` 之后新增（同在 object C 内，可直接用私有 mixOklab）：

```kotlin
/** 日间强调色作小字时的加深版：在 16% 同色底上仍 ≥4.5:1；夜间不变。 */
fun accentText(accent: Color): Color =
    if (!isDay) accent else mixOklab(accent, Color.Black, 0.09f)
```

加深 9%（OkLab）后实测：

| 色 | accentText（日间） | 对 16% 底 | 对 Panel | 对 paper | 夜间 accentText=accent | 对 16% 底 |
|---|---|---|---|---|---|---|
| 沙金 | #705A20 | 4.50 | 5.48 | 5.91 | #B4974C | 5.12 |
| 雾蓝 | #425F80 | 4.50 | 5.48 | 5.92 | #759ECC | 5.13 |
| 黛青 | #346464 | 4.50 | 5.49 | 5.93 | #60A6A5 | 5.12 |
| 陶土 | #844E46 | 4.50 | 5.49 | 5.93 | #D1877B | 5.13 |
| 灰紫 | #6F5377 | 4.50 | 5.47 | 5.91 | #B38DBE | 5.11 |

使用规则（task-45 只改「小字」调用点，边框/填充/图标仍用 accent）：
- 设置页与所有 Panel 底 sheet 上的 accent 小字（10–14sp）改用 `C.accentText(accent)`：SettingsScreen.kt:245/270/327/348/362-363/395/409-415/447/478/577/581；ThemeSheet.kt:322；Drawer.kt:149/175/223/247 等（Drawer 底为 Panel）。
- 聊天区（Void 底）的 accent 小字日间 4.84–4.89 已达标，可保留 accent；若想统一，也可全部走 accentText（视觉差异很小）。
- 不做全局替换也能过验收的最低要求：改 SettingsScreen 的 4 组选中项 + 外观「暗/亮」+ 主题面板「编辑」，即上列前两组。

### 3.4 边框与选中态

| 用途 | 现有代码 | 新色实测 | 结论 |
|---|---|---|---|
| ThemeSheet swatch 选中 | ThemeSheet.kt:284 `1.dp, C.Ivory.copy(.55)` | 需改为 `1.5.dp, accent`：对 PanelHigh 日 4.09–4.12、夜 5.88–5.92 | 非文本 3:1 通过，改 |
| 设置项选中边框 | SettingsScreen.kt:243/268/325/346 `1.dp accent` | 对 Panel 日 4.49–4.52、夜 6.50–6.55 | 通过（非文本），不改 |
| Chip 选中边框 | Design.kt:387 `accent.copy(.5)` | 合成后仍与底有别，≥3:1 视觉可辨 | 不改 |
| 抽屉行选中边框 | Drawer.kt:141 `accent.copy(.55)` | 同上 | 不改 |

### 3.5 talk tone(accent) / ThemeMark

`tone(accent)` 不变（mixOklab 30% 黑）。5 色结果：

| 色 | 夜间 tone(下色) | 夜间 tone 对 Void | 日间 tone(下色) | 日间 tone 对 Void |
|---|---|---|---|---|
| 沙金 | #6E5C2C | 3.11 | #4C3D13 | 9.46 |
| 雾蓝 | #46607E | 3.12 | #2C4058 | 9.47 |
| 黛青 | #386565 | 3.12 | #214444 | 9.50 |
| 陶土 | #81514A | 3.10 | #5B352E | 9.45 |
| 灰紫 | #6E5575 | 3.11 | #4C3851 | 9.45 |

夜间 tone 对 Void 约 3.1，只用于 18–26dp 的双色标记下色（非文本，3:1 门槛刚好通过）；上色仍为 #EDEAE3（夜）/白（日），形状对比不受影响。

### 3.6 C.Blue（链接/行内代码蓝，独立 token）

C.Blue 来自 Theme.kt:379 `blueRamp`，被 Design.kt:116、MessageViews.kt:326、Markdown.kt:980/1237 用作链接/行内代码色，**不是皮肤 accent**。两个选择：

| 方案 | 夜间 | 日间 | 对 Void 夜/日 | 说明 |
|---|---|---|---|---|
| A 保持现状（推荐） | #7FA8FF | #2F55B8 | 8.64 / 6.03 | 链接语义独立、对比更足；选非蓝 accent 时它是画面唯一蓝 |
| B 统一为雾蓝 | #759ECC | #4C6C91 | 7.26 / 4.86 | 色相与默认 accent 一致，但日间余量从 6.03 降到 4.86，链接可读性下降 |

建议 A；若 Lead 要求「全界面单一蓝」，再切 B，并在 §6.5 加一条「链接日间 ≥4.86」验收。本轮不改。

### 3.7 24px 图形判定

5 色日间对三浅底最低 4.73、夜间对墨黑最低 7.20，均远高于非文本 3:1；两两 OKLab 最小距离 0.062（COLOR-V3.md §1），24px 色块可分辨。

## 4. ThemeSheet.kt 改动点（行号）

### 4.1 swatch 列表（126-137 行）结构不变

```kotlin
SheetSection("强调色")
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    C.skins.forEachIndexed { index, _ ->
        ThemeSwatch(
            modifier = Modifier.weight(1f),
            label = C.skinLabel(index),
            mark = C.mark(index, Color.Transparent, C.accentAt(index.toFloat())),
            accent = C.accentAt(index.toFloat()), // 新增参数
            selected = !state.customMode && state.skinIndex == index,
            onClick = { state.applyAccent(index) },
        )
    }
}
```

- 顺序 = skins 顺序（沙金/雾蓝/黛青/陶土/灰紫），固定不变。
- 默认选中：`state.skinIndex == index`，默认值由 State.kt 改为 1（§5），即首屏选中雾蓝。
- 自定义模式（skinIndex=5）时 5 个 swatch 都不选中，由下方 CustomAccentRow 显示，保持现状。

### 4.2 ThemeSwatch（270-293 行）

- 函数签名加 `accent: Color` 参数（271-277）。
- 284 行替换：

```kotlin
.border(1.5.dp, if (selected) accent else C.Hair, shape)
```

- 选中背景仍 `C.PanelHigh`、文字仍 `C.Ivory`（285-291），不改。
- 1.5dp 是全色边框，对 PanelHigh 日 4.09–4.12 / 夜 5.88–5.92，非文本 3:1 通过；不做半透明。

### 4.3 日/夜预览（现有机制，无需新代码）

- `mark = C.mark(index, ..., C.accentAt(index))`（132 行）：`accentAt` 按 `themeProgress` 插值，所以 swatch 在日间显示日值、夜间显示夜值；切「外观」即可在同一张面板里看到两套。
- 预设矩阵小圆点（265 行 `C.previewAccent(preset.appearance, preset.accent)`）固定显示该外观对应的值，不需改。
- 验收时分别截图「日间·某色」和「夜间·某色」的面板即可（§6.2）。

### 4.4 文案（两处）

- 122 行：「例如暖黄底配蓝色强调」→「例如暖黄底配雾蓝强调」。
- 140-142 行：删除「白」说明，改为：「色块上方是底色、下方是主题色；灰紫承接原来的中性位，夜间更亮、日间更沉。」
- 49 行注释「4 × 5」保留（数量未变）。

## 5. State.kt 持久化与迁移

### 5.1 存储键与语义

- 键：SharedPreferences `continue-native`（State.kt:29）里的 int `skinIndex`。
- 语义：0..4 = 预设槽位（顺序见 §2.1）；5 = `C.skins.size` = 自定义强调色（customMode）；范围外为非法值。
- 附带键：`customAccent`（int ARGB，自定义色，不动）、`blueAccent`（旧版布尔，仅清理）。

### 5.2 默认值与非法值（替换 85-90 行）

```kotlin
// 0..C.skins.lastIndex = preset skin, C.skins.size = custom accent colour.
// V3：槽位顺序不变（0 金→沙金 … 4 白→灰紫），旧整数直接沿用；缺省/非法值回退雾蓝（1）。
var skinIndex by mutableStateOf(
    prefs.getInt("skinIndex", 1).let { if (it in 0..C.skins.size) it else 1 },
)
    private set
```

- 删除对 `blueAccent` 的读取（旧版遗留 boolean，已不写入）。
- State.kt:180 的 `prefs.edit().remove("blueAccent").apply()` **保留**，做遗留清理。

### 5.3 旧值 → 新值迁移表

| 旧 skinIndex | 旧名 | 新 skinIndex | 新名 | 新夜间/日间 | 说明 |
|---|---|---|---|---|---|
| 0 | 金 | 0 | 沙金 | #B4974C / #7F6726 | 同槽位，暖金记忆 |
| 1 | 蓝 | 1 | 雾蓝 | #759ECC / #4C6C91 | 同槽位，保留蓝 |
| 2 | 青 | 2 | 黛青 | #60A6A5 / #3C7272 | 同槽位 |
| 3 | 陶 | 3 | 陶土 | #D1877B / #965A50 | 同槽位 |
| 4 | 白（日间显示「黑」） | 4 | 灰紫 | #B38DBE / #7E5F87 | 白不是色相，用灰紫承中性位 |
| 5 | 自定义 | 5 | 自定义 | 保持 customAccent | 不变 |

**结论：无需迁移函数**，只要槽位顺序不变，旧整数即新整数；唯一变化是 index 4 的用户从「白/黑」变为「灰紫」。

### 5.4 自定义强调色逻辑（不改）

- `customMode`（258-259）、`selectCustomAccent`（331-336）、`updateSkin` 的 `coerceIn(0, C.skins.size)`（264-267）均保持。
- 可选（低优先）：State.kt:91 的 `customAccent` 默认 `0xFF7FA8FF`（旧蓝）改为 `0xFF759ECC`（雾蓝夜值），只影响首次新建自定义色；已选用户不受影响。建议做，视觉连贯。

## 6. 验收清单

### 6.1 构建与范围约束

- 不改 `app-native/gradle.properties`：`echo.versionName=1.0.5`、`echo.versionCode=6` 保持。
- 不加依赖、不加资源（颜色全部在 Kotlin 常量）。
- 只改 Theme.kt / ThemeSheet.kt / State.kt 三个文件；不动自定义底色、Help、Usage、Markdown 逻辑（除 §3.3 的 accentText 小字替换）。

### 6.2 截图矩阵（30 张 = 2 模式 × 5 色 × 3 屏）

设备：1080×2388（现有 LQAVP23928C105TX）或等价模拟器；路径建议 `app-native/shots/color-v3-impl/`；命名 `v3-<mode>-<id>-<screen>.png`。

| 维度 | 取值 | 张数 |
|---|---|---|
| mode | day / night | 2 |
| id | sand / mist / slate / clay / mauve | 5 |
| screen | settings / topbar / chat | 3 |

- `settings`：主题面板展开到「强调色」区，5 个 swatch 全部可见、当前色选中（1.5dp accent 边框）、预设矩阵可见。
- `topbar`：欢迎页/聊天页顶栏，能同时看到主题按钮（mark 双色）、日/夜按钮、当前强调色的细微提示（EchoMark 呼吸点）。
- `chat`：聊天页输入区（含已输入文字以点亮发送键）+ 深度思考/联网 chips（on/off 各一）+ 一条含行内代码/链接的消息。
- 例：`v3-day-mist-settings.png`、`v3-night-clay-chat.png`。

### 6.3 每张必查（6 条）

1. 标签文字为沙金/雾蓝/黛青/陶土/灰紫；无「白/黑」残留。
2. settings 图：选中 swatch 边框 1.5dp 全色 accent，颜色与色值表一致；mark 上浅下深（夜间）/上白下深（日间）。
3. topbar 图：mark 与 accent 一致；主题按钮标签正确；切换外观后颜色随 themeProgress 平滑变化，不硬切。
4. chat 图：发送键填充色 = accent、图标为 C.Void；聚焦边框 1.5dp accent；chips 选中态文字/边框 = accent。
5. 日间小字走 `accentText`（若按 §3.3 修）：设置页选中项文字可读；未修则记录残留风险。
6. 无渐变/阴影/新图标混入；同一屏只有一支强调色（灰紫不替换语义色）。

### 6.4 迁移回归（6 组）

前置：用当前 1.0.5 旧包（或旧 1.0.4）依次选择 5 个颜色与 1 个自定义色，记录 skinIndex；再 `adb install -r` 新包（同签名，数据保留），逐项核对：

| 用例 | 操作 | 预期 |
|---|---|---|
| M1 | 旧金（index 0）升级 | 沙金，不再显示「金」 |
| M2 | 旧蓝（index 1）升级 | 雾蓝，默认色；不出现两条蓝 |
| M3 | 旧青（index 2）升级 | 黛青 |
| M4 | 旧陶（index 3）升级 | 陶土 |
| M5 | 旧白（index 4）升级 | 灰紫（日间不再变「黑」） |
| M6 | 旧自定义（index 5）升级 | 自定义色原样保留，可继续滑杆编辑 |
| M7（选做） | 非法值（skinIndex=9） | 启动回退雾蓝（index 1），不崩溃 |

验证命令（debug 包）：

```bash
adb shell run-as app.echo cat shared_prefs/continue-native.xml | grep skinIndex
```

### 6.5 对比度复核

| 项 | 门槛 | 实测 | 结论 |
|---|---|---|---|
| 日间 accent 对三种浅底（24px 块/图形） | ≥3（非文本） | 4.73–4.84 | 通过 |
| 日间 accent 对 paper 作小字 | ≥4.5 | 4.84–4.89（Void 底）/ 4.73–4.77（paper 底） | 通过 |
| 日间 accentText 对 16% 同色底 | ≥4.5 | 4.50 | 通过（边界） |
| 日间 accent 直接对 Panel 作小字 | ≥4.5 | 4.49–4.52 | 3 色差 0.01–0.02，故改用 accentText |
| 夜间 accent 对墨黑 | ≥6 | 7.20–7.26 | 通过 |
| 夜间 accent 对 Panel / PanelHigh | ≥4.5 | 6.50–6.55 / 5.88–5.92 | 通过 |
| onAccent（C.Void 作字） | ≥4.5 | 日 4.84–4.89 / 夜 7.20–7.26 | 通过 |
| 选中边框（accent on PanelHigh） | ≥3 | 日 4.09–4.12 / 夜 5.88–5.92 | 通过 |

### 6.6 不改项（防回归）

- 自定义强调色 HSL 逻辑、自定义底色 HSL、外观 4 档（日间/夜间/暖黄/淡绿/自选）全部不动。
- C.Blue 链接蓝按 §3.6 方案 A 保持不动。
- presets 矩阵仍是 4×5=20；8 候选中的靛蓝/苔绿/藕粉不进 UI。
- 版本号、签名、依赖、资源文件不动。

## 7. 实施顺序与风险

建议顺序（约 1–2 小时，不含截图）：
1. Theme.kt：§2.2 → §2.3 → §2.4 → §2.5 →（P1）§3.3 accentText。
2. State.kt：§5.2（+ 可选 5.4 默认值）。
3. ThemeSheet.kt：§4.1/4.2 边框 → §4.4 文案。
4. 编译 + 冒烟（切换 5 色 / 日夜 / 预设矩阵 / 自定义色）→ 30 张截图。
5. 迁移回归 M1–M6。

风险：
- 行号漂移：Lead 1.0.5 提交后所有行号可能变化，先重跑 §8 命令再改。
- 日间小字对比度：不修 accentText 会在大面积 Panel 页面（设置、抽屉、帮助）留下 4.49–4.52 与 3.68–3.71 两档问题。
- 白→灰紫：部分用户会感知「中性色不见了」；可在更新说明里写「中性位改为灰紫，日间不再变黑」。
- C.Blue 与雾蓝并存：选非蓝 accent 时是唯一蓝，可接受；选雾蓝时两个蓝（#4C6C91 vs #2F55B8）并存，若 Lead 介意，切 §3.6 方案 B。

## 8. 取证命令与基线说明

```bash
grep -n "skins = listOf\|skinLabel\|ACCENT_NIGHT\|ACCENT_DAY\|ACCENT_ID\|previewAccent\|accentAt\|blueRamp" \
  app-native/app/src/main/java/app/echo/Theme.kt
grep -n "skinIndex\|customAccent\|customMode\|selectCustomAccent" \
  app-native/app/src/main/java/app/echo/State.kt
grep -n "强调色\|ThemeSwatch\|ThemeSwitcher\|PRESET" \
  app-native/app/src/main/java/app/echo/ThemeSheet.kt
```

当前行号来自 2026-11-23 未提交工作区；1.0.5 提交后行号、文件均可能变动，以 grep 结果为准，§1 表仅作定位参考。

## 9. 待 Lead 决断（3 项）

1. **accentText 是否纳入 task-45**：不改则「日间小字 ≥4.5」验收不过（§3.2/§3.3）；纳入则只多一个函数 + 6 处调用点。
2. **C.Blue 是否统一为雾蓝**：推荐保持现状（方案 A），链接对比度更高；方案 B 会让日间链接降到 4.86。
3. **customAccent 默认值是否改为 #759ECC**：只影响首次使用自定义色的用户，建议改，可反对。
