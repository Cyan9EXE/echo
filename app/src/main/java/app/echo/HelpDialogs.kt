package app.echo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun BillingConfirm(
    expanded: Boolean,
    title: String,
    detail: String,
    note: String,
    confirmLabel: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (skipNextTime: Boolean) -> Unit,
) {
    var skip by remember(expanded) { mutableStateOf(false) }
    if (!expanded) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(color = C.Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(title, color = C.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                Text(detail, color = C.Ivory, fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(14.dp))
                Text(note, color = C.Muted.copy(alpha = 0.72f), fontSize = 10.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { skip = !skip }
                        .padding(vertical = 2.dp),
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (skip) accent.copy(alpha = 0.16f) else Color.Transparent)
                            .border(1.dp, if (skip) accent else C.ControlBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (skip) Text("✓", color = accent, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("以后不再提示", color = C.Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("取消", color = C.Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(accent)
                            .clickable { onConfirm(skip) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(confirmLabel, color = C.Void, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportGuide(
    page: Int,
    accent: Color,
    skip: Boolean,
    onSkipChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPage: (Int) -> Unit,
    onPick: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(color = C.Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("操作指南", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TapTarget(size = 48.dp, visual = 48.dp, onClick = onClose, description = "关闭操作指南") {
                            CloseGlyph(Modifier.size(16.dp), C.Muted)
                        }
                    }
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }) togetherWith
                                fadeOut(tween(140))
                        },
                        label = "importGuide",
                    ) { p ->
                        Column {
                            when (p) {
                                1 -> {
                                    Text("对话文件在哪？", color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "① 官方 App/网页 → 设置 → 账号 → 导出数据，下载 ZIP。\n" +
                                            "② 用系统「文件」App 搜索 .zip，或从下载通知点开。\n" +
                                            "③ 回到这里，选择该 ZIP 即可。",
                                        color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        GuideButton("返回上级", accent, true) { onPage(0) }
                                        GuideButton("去选文件", accent, false, Modifier.weight(1f), onPick)
                                    }
                                }
                                2 -> {
                                    Text("会覆盖现有对话吗？", color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "会。导入会覆盖本机全部会话与附件，不会上传，也不动官方账号里的数据。建议先保留原始 ZIP；导入完成后若后悔，只能从 ZIP 再导一次。",
                                        color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    GuideButton("返回上级", accent, true, Modifier.fillMaxWidth()) { onPage(0) }
                                }
                                3 -> {
                                    Text("ZIP 从哪里导出？", color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "打开 DeepSeek 官方 App 或网页版，进入 设置 → 账号 → 导出数据；官方会准备一个 ZIP 供下载。下载完成后回到 Echo 选择它。",
                                        color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    GuideButton("返回上级", accent, true, Modifier.fillMaxWidth()) { onPage(0) }
                                }
                                else -> {
                                    Text("导入历史对话", color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "下一步会打开系统文件选择器。请选择 DeepSeek 导出的 ZIP；导入会覆盖本机现有对话和附件，不会上传。",
                                        color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        GuideButton("对话文件在哪？", accent, true, Modifier.weight(1f)) { onPage(1) }
                                        GuideButton("我明白了", accent, false, Modifier.weight(1f), onPick)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GuideChip("会覆盖吗？", accent) { onPage(2) }
                                        GuideChip("ZIP 怎么导出？", accent) { onPage(3) }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSkipChange(!skip) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                    ) {
                                        Box(
                                            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                                .background(if (skip) accent.copy(alpha = .16f) else Color.Transparent)
                                                .border(1.dp, if (skip) accent else C.ControlBorder, RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) { if (skip) Text("✓", color = accent, fontSize = 12.sp) }
                                        Spacer(Modifier.width(8.dp))
                                        Text("以后不再提示", color = C.Muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuideButton(
    label: String,
    accent: Color,
    secondary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (secondary) {
        TextButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, C.ControlBorder),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text(label, color = C.Ivory, fontSize = 13.sp) }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = C.Void),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
internal fun GuideChip(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        label,
        color = accent,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, accent.copy(alpha = .45f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
internal fun HelpCenter(state: AppState, accent: Color) {
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf(-1) }
    val faqs = remember {
        listOf(
            Triple("开始使用", "怎么获取 API Key？", "打开 platform.deepseek.com 登录，进入「API keys」创建，复制 sk- 开头的 Key，回到 设置 → API Key 粘贴保存。Key 经系统密钥库加密后只保存在本机。"),
            Triple("开始使用", "需要先充值吗？", "免费模式用官方账号原有额度；付费 BYOK 从官方平台余额按量扣费。余额为 0 时请求会失败，可在 platform.deepseek.com/usage 查看用量与充值。"),
            Triple("开始使用", "只有一台手机，怎么扫码或登录？", "不需要扫码。免费模式在 设置 里点「用官方网页登录」，按短信验证码完成；付费模式把电脑上创建的 Key 复制到手机粘贴即可，同一台设备就能完成。"),
            Triple("对话与文件", "对话文件在哪？", "在 DeepSeek 官方 App/网页进入 设置 → 账号 → 导出数据，下载 ZIP。回到 Echo：设置 → 导入历史对话 → 选择 ZIP。找不到文件时，用系统「文件」App 搜索 .zip，或从下载通知里点开。"),
            Triple("对话与文件", "导入会覆盖现有对话吗？", "会覆盖本机全部会话与附件，不会上传；请保留原始 ZIP 再导入。"),
            Triple("对话与文件", "超过 7 天的对话怎么复活？", "打开侧栏 →「对话保留」→ 选择已失效会话 → 复活。可选完整原文，或前部摘要 + 后部原文；费用由你的 API Key 直付。"),
            Triple("费用与隐私", "API Key 存在哪里？", "只存本机，默认隐藏。连续点 3 次「显示」才可见，显示时系统禁止截屏。请求由 Key 直连 DeepSeek，不经过第三方。"),
            Triple("费用与隐私", "朗读和语音识别怎么计费？", "朗读 3 元/万计费字符；语音识别 0.78 元/小时，按实际秒数计。均从官方平台余额扣，本应用不预扣、不代收。"),
            Triple("费用与隐私", "联网搜索额外收费吗？", "搜索由官方 deepseek_web_search 执行，按官方 API 用量计入账单，本应用不额外加价。"),
            Triple("出问题时", "提示 401 或 402？", "401 是 Key 无效，重新复制完整的 sk- Key；402 是余额不足，去官方平台充值后重试。"),
            Triple("出问题时", "发送失败或要求人机验证？", "免费网页登录被官方风控时会要求验证，请在官方网页完成验证后重试；付费 BYOK 不走网页登录。"),
            Triple("出问题时", "导入后看不到会话？", "先确认 ZIP 根目录有 chat_session_index.json；重新导入后等刷新完成。仍为空时检查 ZIP 是否来自 DeepSeek 官方导出。"),
        )
    }
    Dialog(
        onDismissRequest = state::closeHelp,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = C.Panel,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).fillMaxHeight(.84f),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("答疑", color = C.Ivory, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Text("先自助；找不到就直接在对话里问 Echo。", color = C.Muted, fontSize = 11.sp)
                        }
                        TapTarget(size = 48.dp, visual = 48.dp, onClick = state::closeHelp, description = "关闭答疑") {
                            CloseGlyph(Modifier.size(17.dp), C.Muted)
                        }
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                    ) {
                        var lastGroup = ""
                        faqs.forEachIndexed { index, (group, question, answer) ->
                            if (group != lastGroup) {
                                lastGroup = group
                                Text(group, color = accent, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                            }
                            Column {
                                Pressable(
                                    onClick = { open = if (open == index) -1 else index },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    scale = .99f,
                                    description = question,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(question, color = C.Ivory, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        Text(if (open == index) "收起" else "展开", color = C.Muted, fontSize = 11.sp)
                                    }
                                }
                                if (open == index) {
                                    Text(answer, color = C.Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 10.dp))
                                    val link = when (index) {
                                        0 -> "打开 API Key 页面" to "https://platform.deepseek.com/api_keys"
                                        1 -> "打开用量与充值" to "https://platform.deepseek.com/usage"
                                        3 -> "打开 DeepSeek 官网" to "https://chat.deepseek.com"
                                        else -> null
                                    }
                                    if (link != null) {
                                        Text(
                                            link.first + "  ↗",
                                            color = accent,
                                            fontSize = 12.sp,
                                            modifier = Modifier.clickable { uri.openUri(link.second) }.padding(bottom = 10.dp),
                                        )
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(C.Hair.copy(alpha = .55f)))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}
