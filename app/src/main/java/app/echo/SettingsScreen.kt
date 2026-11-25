package app.echo

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun SettingsSheet(state: AppState, accent: Color, onImport: () -> Unit) {
    var key by remember { mutableStateOf(state.apiKey) }
    var prompt by remember { mutableStateOf(state.systemPrompt) }
    var keyShown by remember { mutableStateOf(false) }
    var keyTaps by remember { mutableStateOf(0) }
    var firstTapAt by remember { mutableStateOf(0L) }
    var keyTip by remember { mutableStateOf(false) }
    var keyTipAt by remember { mutableStateOf(0L) }
    var aboutOpen by remember { mutableStateOf(false) }
    val sheetView = LocalView.current
    DisposableEffect(keyShown) {
        val window = sheetView.context.findActivity()?.window
        if (keyShown) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Surface(
        color = C.Panel,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                System.currentTimeMillis() - keyTipAt > 160L
                            ) {
                                keyTip = false
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(C.ControlBorder))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp).padding(bottom = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", color = C.Ivory, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TapTarget(size = 48.dp, visual = 40.dp, onClick = { state.closeSettings() }, description = "关闭") {
                        CloseGlyph(Modifier.size(14.dp), C.Muted)
                    }
                }
                Spacer(Modifier.height(18.dp))
                // 首次填写时可直接输入（仍以掩码显示）；已有 Key 时保持只读，
                // 连点 3 次「显示」后才解锁查看/更换，避免误改。
                val editing = keyShown || state.apiKey.isBlank()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("API Key", color = C.Muted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    if (state.apiKey.isNotBlank()) {
                        TapTarget(
                            size = 48.dp,
                            visual = 48.dp,
                            onClick = {
                                if (keyShown) {
                                    keyShown = false
                                    keyTip = false
                                } else {
                                    keyTip = true
                                    keyTipAt = System.currentTimeMillis()
                                    val now = System.currentTimeMillis()
                                    keyTaps = if (now - firstTapAt > 1400L) 1 else keyTaps + 1
                                    firstTapAt = if (keyTaps == 1) now else firstTapAt
                                    if (keyTaps >= 3) {
                                        keyTaps = 0
                                        keyShown = true
                                    }
                                }
                            },
                            description = if (keyShown) "隐藏 API Key" else "连续点击三次显示 API Key",
                        ) {
                            Text(
                                if (keyShown) "隐藏" else "显示",
                                color = C.accentText(accent), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        Text("待填写", color = C.Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { if (editing) key = it },
                    readOnly = !editing,
                    placeholder = { Text("sk-...", color = C.Muted, fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (focus.isFocused && keyShown) {
                                keyTip = true
                                keyTipAt = System.currentTimeMillis()
                            }
                        },
                    singleLine = true,
                    visualTransformation = if (keyShown) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = TextStyle(color = C.Ivory, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.accentText(accent), unfocusedBorderColor = C.Hair,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    ),
                )
                AnimatedVisibility(
                    visible = keyTip,
                    enter = fadeIn(tween(Motion.dur(120, Motion.animationsEnabled))),
                    exit = fadeOut(tween(Motion.dur(120, Motion.animationsEnabled))),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(C.PanelHigh)
                            .border(1.dp, C.Hair, RoundedCornerShape(9.dp))
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "请勿共用或泄露 Key：他人会消耗你的额度与账单；泄露请立即在官方后台删除重建。",
                            color = C.Ivory.copy(alpha = .86f), fontSize = 10.sp, lineHeight = 15.sp,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "请求由你的 Key 直连 api.deepseek.com，按官方用量从平台余额扣费。",
                    color = C.Muted, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "默认隐藏，连续点 3 次「显示」；显示时系统禁止截屏。Key 经系统密钥库 AES-GCM 加密后只保存在本机、不上传。",
                    color = C.Muted, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                Pressable(
                    onClick = {
                        if (key.isBlank()) {
                            state.notice = "请先填写 API Key"
                        } else if (state.updateApiKey(key)) {
                            state.closeSettings()
                            state.notice = "API Key 已保存"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    scale = .97f,
                    description = if (state.apiKey.isBlank()) "保存并开始" else "保存",
                ) {
                    Box(
                        Modifier.fillMaxWidth().background(accent).padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (state.apiKey.isBlank()) "保存并开始" else "保存", color = C.onAccent(accent), fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("模型", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("deepseek-flash", "deepseek-v4-pro").forEach { m ->
                        val active = state.model == m
                        Pressable(
                            onClick = { state.updateModel(m) },
                            shape = RoundedCornerShape(9.dp),
                            scale = .95f,
                            description = m,
                        ) {
                            Box(
                                Modifier
                                    .background(if (active) accent.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(9.dp))
                                    .border(1.dp, if (active) C.accentText(accent) else C.Hair, RoundedCornerShape(9.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) { Text(m, color = if (active) C.accentText(accent) else C.Muted, fontSize = 10.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("回答风格 / 系统提示词", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        "默认" to "",
                        "简洁" to "回答简洁，先给结论，只保留必要信息。",
                        "详细" to "回答详细，先给结论，再分点解释，必要时举例。",
                        "学术" to "回答严谨，区分事实与推断，给出依据，不夸大。",
                    ).forEach { (label, value) ->
                        Pressable(
                            onClick = { prompt = value; state.updateSystemPrompt(value) },
                            shape = RoundedCornerShape(8.dp),
                            scale = .94f,
                            description = "回答风格：" + label,
                        ) {
                            Box(
                                Modifier
                                    .background(if (prompt == value) accent.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (prompt == value) C.accentText(accent) else C.Hair, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 9.dp, vertical = 7.dp),
                            ) { Text(label, color = if (prompt == value) C.accentText(accent) else C.Muted, fontSize = 10.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(2000); state.updateSystemPrompt(prompt) },
                    placeholder = { Text("留空即默认；也可以写自定义指令", color = C.Muted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    textStyle = TextStyle(color = C.Ivory, fontSize = 12.sp, lineHeight = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.accentText(accent), unfocusedBorderColor = C.Hair,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "默认不注入，0 额外 token；填写后只在新对话或上下文被压缩后的首次请求发送，避免每轮重复计费。",
                    color = C.Muted, fontSize = 9.sp, lineHeight = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深度思考", color = C.Ivory, fontSize = 15.sp)
                        Text("默认开启，优先质量；可随时关闭", color = C.Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = state.thinking,
                        onCheckedChange = state::updateThinking,
                        colors = SwitchDefaults.colors(checkedThumbColor = C.onAccent(accent), checkedTrackColor = accent, uncheckedThumbColor = C.Muted, uncheckedTrackColor = C.PanelHigh),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("最大输出", color = C.Ivory, fontSize = 14.sp)
                        Text("单次回复上限；调低可控制费用，调高适合长文", color = C.Muted, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8192 to "8K", 16384 to "16K", 32768 to "32K", 65536 to "64K").forEach { (value, label) ->
                        val on = state.maxTokens == value
                        Pressable(
                            onClick = { state.updateMaxTokens(value) },
                            shape = RoundedCornerShape(9.dp),
                            scale = .95f,
                            description = "最大输出：" + label,
                        ) {
                            Box(
                                Modifier
                                    .background(if (on) accent.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(9.dp))
                                    .border(1.dp, if (on) C.accentText(accent) else C.Hair, RoundedCornerShape(9.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            ) { Text(label, color = if (on) C.accentText(accent) else C.Muted, fontSize = 10.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("运行模式", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "正常", true to "性能").forEach { (perf, label) ->
                        val on = state.performanceMode == perf
                        Pressable(
                            onClick = { state.updatePerformanceMode(perf) },
                            shape = RoundedCornerShape(9.dp),
                            scale = .95f,
                            description = "运行模式：" + label,
                        ) {
                            Box(
                                Modifier
                                    .background(if (on) accent.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(9.dp))
                                    .border(1.dp, if (on) C.accentText(accent) else C.Hair, RoundedCornerShape(9.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) { Text(label, color = if (on) C.accentText(accent) else C.Muted, fontSize = 10.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "正常：省电优先，后台让出内存。性能：存储线程提速、流式刷新更密、更多会话常驻内存，后台尽量保活。",
                    color = C.Muted, fontSize = 9.sp, lineHeight = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("外观", color = C.Ivory, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "暗",
                            color = if (C.isDarkBackground) C.accentText(accent) else C.Muted,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                // 自选底色时「暗/亮」只切到对应那一套，不退出自选。
                                if (C.isDarkBackground) return@clickable
                                if (state.customAppearance) state.setCustomDay(false)
                                else state.applyAppearance(C.Look.INK)
                            },
                        )
                        Text(
                            "亮",
                            color = if (C.isDarkBackground) C.Muted else C.accentText(accent),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                if (!C.isDarkBackground) return@clickable
                                if (state.customAppearance) state.setCustomDay(true)
                                else state.applyAppearance(C.Look.PAPER)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        state.closeSettings()
                        state.openTheme()
                    },
                ) {
                    Text("主题", color = C.Ivory, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    ThemeMarkView(C.mark(state.skinIndex, accent), 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.customMode) "自定义" else C.skinLabel(state.skinIndex),
                        color = C.Muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("›", color = C.Muted, fontSize = 16.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", color = C.Ivory, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("小" to .92f, "标准" to 1f, "大" to 1.10f).forEach { (label, scale) ->
                            val on = kotlin.math.abs(state.fontScale - scale) < .01f
                            Text(
                                label,
                                color = if (on) C.accentText(accent) else C.Muted,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable { state.updateFontScale(scale) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("朗读音色", color = C.Ivory, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "海星",
                            color = if (state.ttsVoice == "stella") C.accentText(accent) else C.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { state.updateTtsVoice("stella") },
                        )
                        Text(
                            "暗潮",
                            color = if (state.ttsVoice == "tide") C.accentText(accent) else C.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { state.updateTtsVoice("tide") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "官方音色：海星（女声）/ 暗潮（男声）。朗读由公开 API 合成，按 ¥3 / 万计费字符，费用走你的 API Key。",
                    color = C.Muted, fontSize = 9.sp, lineHeight = 13.sp,
                )
                Spacer(Modifier.height(22.dp))
                Text("费用", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Pressable(
                    onClick = { state.openUsage() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    scale = .98f,
                    description = "用量与费用",
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(C.PanelHigh, RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("用量与费用", color = C.Ivory, fontSize = 15.sp)
                            Text(
                                "朗读与语音识别估算、官方账户余额、平台用量页",
                                color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        }
                        Text("›", color = accent, fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示计费提示", color = C.Ivory, fontSize = 14.sp)
                        Text("消息底部费用、token 与朗读估价；关闭后对话更干净", color = C.Muted, fontSize = 10.sp)
                    }
                    Switch(
                        checked = state.showBillingHints,
                        onCheckedChange = state::updateShowBillingHints,
                        colors = SwitchDefaults.colors(checkedThumbColor = C.onAccent(accent), checkedTrackColor = accent),
                    )
                }
                Spacer(Modifier.height(22.dp))
                Text("数据", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Surface(color = C.PanelHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("导入历史对话", color = C.Ivory, fontSize = 15.sp)
                                Text("选择 DeepSeek 导出的 ZIP；只读取会话，不读取图片。", color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                            Pressable(
                                onClick = onImport,
                                shape = RoundedCornerShape(9.dp),
                                scale = .94f,
                                description = "选择 ZIP",
                            ) {
                                Text("选择 ZIP", color = C.accentText(accent), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("本机会话：" + state.sessions.size.toString() + " 个", color = C.Muted, fontSize = 10.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("导入前显示操作指南", color = C.Ivory, fontSize = 13.sp)
                                Text("关闭后直接打开系统文件选择器", color = C.Muted, fontSize = 10.sp)
                            }
                            Switch(
                                checked = !state.importGuideOff,
                                onCheckedChange = { state.updateImportGuide(!it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = C.onAccent(accent), checkedTrackColor = accent),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Pressable(
                    onClick = { aboutOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    scale = .98f,
                    description = "关于 Echo",
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .background(C.PanelHigh, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("关于 Echo", color = C.Ivory, fontSize = 15.sp)
                            Text("版本、隐私、路线图与署名", color = C.Muted, fontSize = 11.sp)
                        }
                        Text("›", color = accent, fontSize = 20.sp)
                    }
                }
            }
        }
    }
    if (aboutOpen) AboutDialog(accent) { aboutOpen = false }
}

@Composable
internal fun AboutDialog(accent: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = C.Panel,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).navigationBarsPadding(),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Echo", color = C.Ivory, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TapTarget(size = 48.dp, visual = 48.dp, onClick = onDismiss, description = "关闭关于") {
                        CloseGlyph(Modifier.size(15.dp), C.Muted)
                    }
                }
                Text("把没说完的话，接回来。", color = C.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                AboutSection("版本")
                AboutRow("当前版本", BuildConfig.VERSION_NAME + " · versionCode " + BuildConfig.VERSION_CODE)
                AboutRow("构建", "Kotlin · Jetpack Compose · 无 WebView · 无自建后端")
                Spacer(Modifier.height(16.dp))
                AboutSection("网络与隐私")
                AboutRow("直连", "请求由你的 API Key 直连 api.deepseek.com，不经过本应用服务器。")
                AboutRow("本机", "会话、附件与 Key 只保存在本机；Key 经系统密钥库 AES-GCM 加密，磁盘上不含明文，默认隐藏且显示时禁止截屏。")
                Spacer(Modifier.height(16.dp))
                AboutSection("费用")
                AboutRow("朗读", "¥3 / 万计费字符（汉字计 2 字符）；由官方按量扣费。")
                AboutRow("语音识别", "¥0.78 / 小时，不足 1 秒按 1 秒；本应用不预扣、不保存音频。")
                Spacer(Modifier.height(16.dp))
                AboutSection("这一版做了什么")
                AboutRow("已完成", "导入向导与答疑中心 · 7 天失效会话复活 · 前部摘要 + 后部原文 · token 与上下文占比 · 主题色切换 · 联网搜索 · 用量账本")
                AboutRow("还没做", "附件与图片重传 · 导出全部历史 · Markdown/LaTeX · 正式签名仓库")
                Spacer(Modifier.height(16.dp))
                AboutSection("GitHub")
                Pressable(
                    onClick = { Browsers.open(context, "https://github.com/Cyan9EXE/echo") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    scale = .98f,
                    description = "打开 GitHub 源码仓库",
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(C.PanelHigh, RoundedCornerShape(10.dp))
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("源码仓库", color = C.Ivory, fontSize = 13.sp)
                            Text("github.com/Cyan9EXE/echo", color = C.Muted, fontSize = 10.sp)
                        }
                        Text("打开", color = C.accentText(accent), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("由 DeepSeek-V4.1Flash 构建", color = C.accentText(accent), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "第三方客户端，与 DeepSeek 官方无隶属关系；名称与商标归其所有者。",
                    color = C.Muted.copy(alpha = .75f), fontSize = 9.sp, lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
internal fun AboutSection(title: String) {
    Text(title, color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
internal fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Text(value, color = C.Ivory, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}
