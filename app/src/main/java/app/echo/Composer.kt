package app.echo

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
internal fun Composer(state: AppState, accent: Color) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(state.draft) }
    var focused by remember { mutableStateOf(false) }
    var askAsr by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 草稿不能只在离开输入区时落盘：进程被系统杀掉前不会有 onDispose（P2-3）。
    // 输入停顿 300ms 写一次 prefs，再在退到后台时补写一次，最坏丢最近 300ms 的输入。
    // Persist even an empty draft: clearing then killing the app must not restore old text (P3-1).
    LaunchedEffect(state.draft) {
        delay(300)
        state.persistDraft()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.persistDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun micGranted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun fillFromSpeech(spoken: String) {
        if (spoken.isBlank()) return
        text = if (text.isBlank()) spoken else text.trimEnd() + "\n" + spoken
        state.draft = text
        state.typing = true
        focus.requestFocus()
    }

    val beginRecording: () -> Unit = {
        if (state.apiKey.isBlank()) {
            Toast.makeText(context, "填入 API Key 后才能使用官方语音识别", Toast.LENGTH_SHORT).show()
            state.openSettings()
        } else {
            // 朗读与录音共用音频焦点：开始录音前先停止正在进行的朗读。
            Reader.stopAll()
            Recorder.start(
                context = context,
                apiKey = state.apiKey,
                onText = { spoken -> fillFromSpeech(spoken) },
                onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginRecording()
        else Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }

    val onMic: () -> Unit = {
        when {
            Recorder.recording -> Recorder.stop()
            Recorder.transcribing -> Toast.makeText(context, "正在识别上一段语音…", Toast.LENGTH_SHORT).show()
            state.apiKey.isBlank() -> {
                Toast.makeText(context, "填入 API Key 后才能使用官方语音识别", Toast.LENGTH_SHORT).show()
                state.openSettings()
            }
            !micGranted() && state.asrSkipConfirm -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !micGranted() -> askAsr = true
            state.asrSkipConfirm -> beginRecording()
            else -> askAsr = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Recorder.cancel()
            Reader.stopAll()
            state.persistDraft()
        }
    }

    val ready = text.isNotBlank()
    val ignite by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = if (Motion.animationsEnabled) spring(dampingRatio = .8f, stiffness = 420f) else snap(),
        label = "sendIgnite",
    )
    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            color = C.Panel,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, if (focused) accent else C.Hair),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text("请输入消息", color = C.Muted, fontSize = 17.sp)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                state.draft = it
                                state.typing = it.isNotBlank()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused },
                            textStyle = TextStyle(color = C.Ivory, fontSize = 17.sp, lineHeight = 26.sp),
                            cursorBrush = SolidColor(accent),
                            maxLines = 6,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 发送/停止按钮以前是硬切；用极短的交叉淡入切换（P2-6）。
                    Crossfade(
                        targetState = state.isStreaming(state.currentId),
                        animationSpec = tween(Motion.dur(140, Motion.animationsEnabled)),
                        label = "sendStop",
                    ) { streamingNow ->
                    if (streamingNow) {
                        Pressable(
                            onClick = { state.stopGenerating() },
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            scale = .92f,
                            description = "停止生成",
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(1.dp, C.ControlBorder, CircleShape)
                                    .background(C.PanelHigh.copy(alpha = .8f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                PauseGlyph(Modifier.size(20.dp), C.Ivory)
                            }
                        }
                    } else {
                        Pressable(
                            onClick = {
                                val value = text.trim()
                                if (value.isNotEmpty()) {
                                    // send 返回 false 表示被前置条件拦下（生成中/未复活/缺 Key）；
                                    // 这时不能清空输入框，否则用户刚打的字会丢（P2-3）。
                                    if (state.send(value)) {
                                        text = ""
                                        state.draft = ""
                                        state.persistDraft()
                                    } else {
                                        state.typing = text.isNotBlank()
                                    }
                                }
                            },
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            scale = .92f,
                            description = "发送",
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(1.dp, if (ignite > .5f) Color.Transparent else C.ControlBorder, CircleShape)
                                    .background(accent.copy(alpha = ignite), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                SendGlyph(Modifier.size(20.dp), C.Void.copy(alpha = ignite))
                                if (ignite < .5f) SendGlyph(Modifier.size(20.dp), C.Muted)
                            }
                        }
                    }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        icon = { color -> ThinkGlyph(color, Modifier.size(14.dp)) },
                        label = "深度思考",
                        active = state.thinking,
                        accent = accent,
                    ) { state.updateThinking(!state.thinking) }
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        icon = { color -> WebGlyph(color, Modifier.size(14.dp)) },
                        label = "联网",
                        active = state.webSearch,
                        accent = accent,
                    ) {
                        state.updateWebSearch(!state.webSearch)
                        state.notice = if (state.webSearch) {
                            "联网搜索已开启：由 DeepSeek 官方搜索服务执行，本轮使用 deepseek-flash。"
                        } else {
                            "联网搜索已关闭。"
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (state.webSearch) "deepseek-flash" else state.model)
                            .removePrefix("deepseek-").uppercase(Locale.ROOT),
                        color = C.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    )
                    if (state.showBillingHints) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "语音 " + Billing.quote(Billing.asrMaxCost()),
                            color = C.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(2.dp))
                    } else {
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        TapTarget(size = 48.dp, visual = 40.dp, onClick = onMic, description = "语音输入") {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(
                                        if (Recorder.recording) C.Danger.copy(alpha = .16f) else Color.Transparent,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                MicGlyph(Modifier.size(18.dp), if (Recorder.recording) C.Danger else C.Muted)
                            }
                        }
                        BillingConfirm(
                            expanded = askAsr,
                            title = "语音识别需要计费",
                            detail = "单次录音最长 120 秒，音频会发送给 DeepSeek 做识别。",
                            note = "最高约 " + Billing.quote(Billing.asrMaxCost()) +
                                "，按 ¥0.78 / 小时计费（不足 1 秒按 1 秒）。" +
                                "费用由你的 API Key 直接支付，本应用不预扣、不保存音频；识别失败不计费。",
                            confirmLabel = "开始录音",
                            accent = accent,
                            onDismiss = { askAsr = false },
                            onConfirm = { skip ->
                                if (skip) state.updateAsrSkipConfirm(true)
                                askAsr = false
                                if (micGranted()) {
                                    beginRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (Recorder.recording || Recorder.transcribing) {
            Spacer(Modifier.height(8.dp))
            Surface(color = C.Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (Recorder.recording) C.Danger else accent, CircleShape),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (Recorder.recording) "正在聆听 · " + Billing.clock(Recorder.elapsed) + " / 02:00"
                        else "正在识别…",
                        color = C.Ivory, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (Recorder.recording) "再次点击结束" else "计费中",
                        color = C.Muted, fontSize = 10.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "内容由 AI 生成，请仔细甄别 · 数据只保存在本机",
            color = C.Muted, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}
