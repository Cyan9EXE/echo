package app.echo

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun UsageSheet(state: AppState, accent: Color) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var balance by remember { mutableStateOf<String?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true
        balanceError = null
        val key = state.apiKey
        if (key.isBlank()) {
            loading = false
            balanceError = "先在设置里填入 API Key，才能查询官方余额。"
        } else {
            BalanceApi.fetch(key) { value, error ->
                loading = false
                balance = value
                balanceError = error
            }
        }
    }
    val chatCost = state.totalChatCost()
    Surface(
        color = C.Panel,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f),
    ) {
        Column(Modifier.fillMaxSize()) {
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
                    Text("用量与费用", color = C.Ivory, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TapTarget(size = 48.dp, visual = 40.dp, onClick = { state.closeUsage() }, description = "关闭") {
                        CloseGlyph(Modifier.size(14.dp), C.Muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("本机账本只是估算，最终以 DeepSeek 官方账单为准。", color = C.Muted, fontSize = 10.sp)
                Spacer(Modifier.height(16.dp))

                Surface(color = C.PanelHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("官方账户余额", color = C.Ivory, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "刷新",
                                color = C.accentText(accent),
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    if (!loading) refresh += 1
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            loading -> Text("正在查询…", color = C.Muted, fontSize = 13.sp)
                            balanceError != null -> Text(balanceError!!, color = if (balance != null) C.Danger else C.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            else -> Text(balance ?: "暂无余额信息", color = C.Ivory, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "来自 GET /user/balance，只读；与 API Key 同账户。",
                            color = C.Muted, fontSize = 9.sp, lineHeight = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = C.PanelHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("朗读 TTS", color = C.Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(Billing.estimate(UsageLedger.ttsCostCny), color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                UsageLedger.ttsChars.toString() + " 计费字符 · " + UsageLedger.ttsRequests + " 次",
                                color = C.Muted, fontSize = 9.sp,
                            )
                        }
                    }
                    Surface(color = C.PanelHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("语音识别 ASR", color = C.Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(Billing.estimate(UsageLedger.asrCostCny), color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                UsageLedger.asrSeconds.toString() + " 秒 · " + UsageLedger.asrRequests + " 次",
                                color = C.Muted, fontSize = 9.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Surface(color = C.PanelHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("对话生成花费", color = C.Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("本机全部会话累计估算", color = C.Muted, fontSize = 9.sp)
                        }
                        Text(
                            Billing.estimate(chatCost),
                            color = C.Ivory, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))

                Text("最近计费", color = C.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                if (UsageLedger.recent.isEmpty()) {
                    Text("还没有朗读或语音识别记录。", color = C.Muted, fontSize = 11.sp)
                } else {
                    UsageLedger.recent.take(12).forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (e.kind == "TTS") "朗读" else "识别",
                                color = if (e.kind == "TTS") C.accentText(accent) else C.Ivory,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (e.kind == "TTS") e.units.toString() + " 字符" else e.units.toString() + " 秒",
                                color = C.Muted, fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Billing.estimate(e.costCny), color = C.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(fmtTime(e.at), color = C.Muted.copy(alpha = .65f), fontSize = 8.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text(
                    "计价说明：TTS 按 ¥3 / 万计费字符（汉字计 2 字符），ASR 按 ¥0.78 / 小时；" +
                        "官方限时 5 折没有公布截止日期，这里统一按刊例价估算，避免少报。实际扣费以官方账单为准。",
                    color = C.Muted, fontSize = 9.sp, lineHeight = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                Pressable(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/usage")))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    scale = .97f,
                    description = "打开官方用量页",
                ) {
                    Box(
                        Modifier.fillMaxWidth().border(1.dp, C.accentText(accent), RoundedCornerShape(10.dp))
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("打开官方用量页", color = C.accentText(accent), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
