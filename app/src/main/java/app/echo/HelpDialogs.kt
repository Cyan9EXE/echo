package app.echo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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

private data class ImportFaq(
    val question: String,
    val answer: String,
    val linkLabel: String = "",
    val linkUrl: String = "",
)

private val importFaqs = listOf(
    ImportFaq(
        "ZIP 文件在哪里下载？",
        "官方 App 或网页：设置 → 账号 → 导出数据，下载 ZIP。手机端可用系统「文件」App 搜索 .zip，" +
            "或从下载通知点开；找不到时先在官方侧重新导出一次。",
        "打开 DeepSeek 官网", "https://chat.deepseek.com",
    ),
    ImportFaq(
        "导入会覆盖官方账号里的数据吗？",
        "不会。导入只写本机，不上传、不改官方账号、也不删除云端数据，官方 App 里的对话仍然在。",
    ),
    ImportFaq(
        "会覆盖本机哪些内容？",
        "会替换本机 Echo 现有的会话与附件。建议把原始 ZIP 另存一份再导入；后悔或失败时，用原 ZIP 重新导入即可恢复。",
    ),
    ImportFaq(
        "导入后看不到会话怎么办？",
        "先确认 ZIP 根目录有 chat_session_index.json（官方导出的 ZIP 一般在根目录）。重新导入后等几秒刷新；" +
            "仍为空，说明 ZIP 不是官方导出或已损坏，请重新导出后再试。",
    ),
    ImportFaq(
        "导入很慢或中途失败？",
        "1 GB 以上的 ZIP 需要几分钟，请保持屏幕常亮并留足空间。失败时先强制关闭应用再重试；" +
            "仍然失败，用「我还没懂」里的方法带上截图去问。",
    ),
)

@Composable
private fun GuideHeader(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TapTarget(size = 40.dp, visual = 40.dp, onClick = onBack, description = "返回") {
                BackGlyph(Modifier.size(16.dp), C.Muted)
            }
            Spacer(Modifier.width(2.dp))
        }
        Text(
            title,
            color = C.Ivory, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TapTarget(size = 40.dp, visual = 40.dp, onClick = onClose, description = "关闭导入提示") {
            CloseGlyph(Modifier.size(15.dp), C.Muted)
        }
    }
}

@Composable
private fun divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.Hair.copy(alpha = .55f)))

@Suppress("UNUSED_PARAMETER")
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
    // 轻量气泡：无遮罩、不挡操作；点外部或右上角 ✕ 关闭。
    // 气泡内自带三级帮助：导入说明 → 答疑列表 → 问题详情 / 我还没懂。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var level by remember { mutableStateOf(0) }
    var faqIndex by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val escalateText = "我现在的情况是：导入 ZIP 后看不到会话 / 提示导入失败 / 卡在某一步。\n" +
        "需要：确认这个 ZIP 是否来自官方导出、根目录是否有 chat_session_index.json，以及怎么重新导出。"
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(bottom = navBottom).padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = C.Panel,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                    when (level) {
                        0 -> {
                            GuideHeader("导入历史对话", onBack = null, onClose = onClose)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "下一步打开系统文件选择器，请选择 DeepSeek 官方导出的 ZIP。",
                                color = C.Ivory, fontSize = 13.sp, lineHeight = 20.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "导入会覆盖本机现有对话和附件，不会上传，也不动官方账号里的数据；建议先保留原始 ZIP。" +
                                    "ZIP 获取：官方 App/网页 → 设置 → 账号 → 导出数据。",
                                color = C.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSkipChange(!skip) }
                                    .padding(vertical = 6.dp, horizontal = 2.dp),
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
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { copied = false; level = 1 },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) { Text("答疑", color = accent, fontSize = 13.sp) }
                                TextButton(
                                    onClick = onClose,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) { Text("取消", color = C.Muted, fontSize = 13.sp) }
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(accent)
                                        .clickable(onClick = onPick)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("去选择", color = C.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        1 -> {
                            GuideHeader("导入答疑", onBack = { level = 0 }, onClose = onClose)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "先看这几条，还没解决就点进问题里选「我还没懂」。",
                                color = C.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                                importFaqs.forEachIndexed { index, faq ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { faqIndex = index; level = 2 }
                                            .padding(vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(faq.question, color = C.Ivory, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        Text("›", color = C.Muted, fontSize = 16.sp)
                                    }
                                    divider()
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "看完还是没解决，就进任意问题点「我还没懂」。",
                                color = C.Muted, fontSize = 10.sp, lineHeight = 15.sp,
                            )
                        }

                        2 -> {
                            val faq = importFaqs[faqIndex.coerceIn(0, importFaqs.lastIndex)]
                            GuideHeader("导入答疑", onBack = { level = 1 }, onClose = onClose)
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    faq.question,
                                    color = C.Ivory, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    lineHeight = 22.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(faq.answer, color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp)
                                if (faq.linkUrl.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        faq.linkLabel + "  ↗",
                                        color = accent, fontSize = 12.sp,
                                        modifier = Modifier.clickable { uri.openUri(faq.linkUrl) },
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { level = 1 },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) { Text("返回答疑", color = C.Muted, fontSize = 13.sp) }
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(accent)
                                        .clickable { copied = false; level = 3 }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("我还没懂", color = C.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        else -> {
                            GuideHeader("还没解决", onBack = { level = 2 }, onClose = onClose)
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    "去官方 App 问 DeepSeek，或找其他人问，或者就在这里问。",
                                    color = C.Ivory, fontSize = 14.sp, lineHeight = 22.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "如果要发给官方或他人，请截图并写清楚下面两段：",
                                    color = C.Muted, fontSize = 12.sp, lineHeight = 19.sp,
                                )
                                Spacer(Modifier.height(10.dp))
                                Surface(color = C.PanelHigh, shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("你现在的情况是：", color = C.Muted, fontSize = 11.sp)
                                        Text(
                                            "导入 ZIP 后看不到会话 / 提示导入失败 / 卡在某一步。",
                                            color = C.Ivory, fontSize = 12.sp, lineHeight = 19.sp,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text("需要：", color = C.Muted, fontSize = 11.sp)
                                        Text(
                                            "确认这个 ZIP 是否来自官方导出、根目录是否有 chat_session_index.json，以及怎么重新导出。",
                                            color = C.Ivory, fontSize = 12.sp, lineHeight = 19.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "也可以直接说「就在这里问」，把截图发到对话里。" +
                                        "附上：当前屏幕截图、ZIP 文件名与大小、报错文字。",
                                    color = C.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        clip.setPrimaryClip(ClipData.newPlainText("Echo 导入问题", escalateText))
                                        copied = true
                                        Toast.makeText(context, "已复制，可直接粘贴给官方或他人", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) { Text(if (copied) "已复制" else "复制这段说明", color = C.Muted, fontSize = 13.sp) }
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(accent)
                                        .clickable(onClick = onPick)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("去选择", color = C.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
internal fun HelpCenter(state: AppState, accent: Color) {
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf(-1) }
    val faqs = remember {
        listOf(
            Triple("开始使用", "怎么获取 API Key？", "打开 platform.deepseek.com 登录，进入「API keys」创建，复制 sk- 开头的 Key，回到 设置 → API Key 粘贴保存。Key 经系统密钥库加密后只保存在本机。"),
            Triple("开始使用", "需要先充值吗？", "本应用只有 BYOK：用你自己的 API Key 调官方接口，从 platform.deepseek.com 的余额按量扣费。余额为 0 时请求会失败；用量与充值都在官方平台，应用不代收、不预扣。"),
            Triple("开始使用", "只有一台手机，怎么扫码或登录？", "本应用不需要登录，也没有免费模式。扫码只出现在官方平台充值页（给 API 账户充值），可以截图后用相册识别。使用只需把 platform.deepseek.com 创建的 Key 粘贴到 设置，一台手机即可完成。"),
            Triple("对话与文件", "对话文件在哪？", "在 DeepSeek 官方 App/网页进入 设置 → 账号 → 导出数据，下载 ZIP。回到 Echo：设置 → 导入历史对话 → 选择 ZIP。找不到文件时，用系统「文件」App 搜索 .zip，或从下载通知里点开。"),
            Triple("对话与文件", "导入会覆盖现有对话吗？", "会覆盖本机全部会话与附件，不会上传；请保留原始 ZIP 再导入。"),
            Triple("对话与文件", "超过 7 天的对话怎么复活？", "打开侧栏 →「对话保留」→ 选择已失效会话 → 复活。可选完整原文，或前部摘要 + 后部原文；费用由你的 API Key 直付。"),
            Triple("费用与隐私", "API Key 存在哪里？", "只存本机，默认隐藏。连续点 3 次「显示」才可见，显示时系统禁止截屏。请求由 Key 直连 DeepSeek，不经过第三方。"),
            Triple("费用与隐私", "朗读和语音识别怎么计费？", "朗读 3 元/万计费字符；语音识别 0.78 元/小时，按实际秒数计。均从官方平台余额扣，本应用不预扣、不代收。"),
            Triple("费用与隐私", "联网搜索额外收费吗？", "搜索由官方 deepseek_web_search 执行，按官方 API 用量计入账单，本应用不额外加价。"),
            Triple("出问题时", "提示 401 或 402？", "401 是 Key 无效，重新复制完整的 sk- Key；402 是余额不足，去官方平台充值后重试。"),
            Triple("出问题时", "发送失败或要求人机验证？", "本应用不代替官方账号登录。若官方接口返回人机验证或风控，请到官方 App 或网页完成验证后重试；也请检查 Key 是否有效、余额是否充足。"),
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
                            Text("先自助；还没解决就换个方式：去官方 App 问 DeepSeek，或找其他人问，或者就在这里问。", color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp)
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