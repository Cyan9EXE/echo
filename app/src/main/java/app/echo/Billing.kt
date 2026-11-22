package app.echo

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.ceil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object Billing {
    const val TTS_YUAN_PER_10K = 3.0
    const val ASR_YUAN_PER_HOUR = 0.78
    const val ASR_MAX_SECONDS = 120
    const val TTS_MAX_BILLED_PER_REQUEST = 9_000

    fun ttsBilledChars(text: String): Int {
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            n += if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) 2 else 1
            i += Character.charCount(cp)
        }
        return n
    }

    fun ttsCost(billedChars: Int): Double = billedChars / 10_000.0 * TTS_YUAN_PER_10K

    fun asrCost(seconds: Int): Double = seconds * ASR_YUAN_PER_HOUR / 3600.0

    fun asrMaxCost(): Double = ceil(asrCost(ASR_MAX_SECONDS) * 100.0) / 100.0

    fun ceilCny(v: Double): Double = ceil(v * 100.0 - 1e-9) / 100.0

    fun quote(v: Double): String = "¥" + String.format(Locale.CHINA, "%.2f", ceilCny(v))

    fun estimate(v: Double): String =
        if (v < 0.01) "¥" + String.format(Locale.CHINA, "%.4f", v)
        else "¥" + String.format(Locale.CHINA, "%.2f", v)

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60)
    }

    fun splitForTts(text: String, limit: Int = TTS_MAX_BILLED_PER_REQUEST): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        var currentCost = 0
        fun flush() {
            if (current.isNotBlank()) out.add(current.toString())
            current.setLength(0)
            currentCost = 0
        }
        val sentences = clean.split(Regex("(?<=[。！？!?；;\\n])"))
        for (sentence in sentences) {
            var i = 0
            while (i < sentence.length) {
                val piece = StringBuilder()
                var pieceCost = 0
                while (i < sentence.length) {
                    val cp = sentence.codePointAt(i)
                    val w = if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) 2 else 1
                    if (pieceCost + w > limit) break
                    piece.appendCodePoint(cp)
                    pieceCost += w
                    i += Character.charCount(cp)
                }
                if (pieceCost == 0) break
                if (currentCost > 0 && currentCost + pieceCost > limit) flush()
                current.append(piece)
                currentCost += pieceCost
                if (currentCost >= limit) flush()
            }
        }
        flush()
        return out
    }
}

data class UsageEntry(
    val kind: String,
    val at: Long,
    val units: Int,
    val costCny: Double,
    val note: String,
)

object UsageLedger {
    private var prefs: SharedPreferences? = null
    private var loaded = false

    var ttsChars by mutableStateOf(0)
        private set
    var ttsRequests by mutableStateOf(0)
        private set
    var ttsCostCny by mutableStateOf(0.0)
        private set
    var asrSeconds by mutableStateOf(0)
        private set
    var asrRequests by mutableStateOf(0)
        private set
    var asrCostCny by mutableStateOf(0.0)
        private set
    var recent by mutableStateOf<List<UsageEntry>>(emptyList())
        private set

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences("voice-usage", Context.MODE_PRIVATE)
        prefs = p
        ttsChars = p.getInt("ttsChars", 0)
        ttsRequests = p.getInt("ttsRequests", 0)
        ttsCostCny = p.getString("ttsCost", "0").orEmpty().toDoubleOrNull() ?: 0.0
        asrSeconds = p.getInt("asrSeconds", 0)
        asrRequests = p.getInt("asrRequests", 0)
        asrCostCny = p.getString("asrCost", "0").orEmpty().toDoubleOrNull() ?: 0.0
        recent = runCatching {
            val arr = JSONArray(p.getString("recent", "[]") ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        UsageEntry(
                            kind = o.optString("kind", "TTS"),
                            at = o.optLong("at", 0L),
                            units = o.optInt("units", 0),
                            costCny = o.optDouble("cost", 0.0),
                            note = o.optString("note", ""),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordTts(billedChars: Int, cost: Double, note: String = "") {
        if (billedChars <= 0) return
        val p = prefs ?: return
        ttsChars += billedChars
        ttsRequests += 1
        ttsCostCny += cost
        push(UsageEntry("TTS", System.currentTimeMillis(), billedChars, cost, note))
        p.edit()
            .putInt("ttsChars", ttsChars)
            .putInt("ttsRequests", ttsRequests)
            .putString("ttsCost", ttsCostCny.toString())
            .putString("recent", recentJson())
            .apply()
    }

    fun recordAsr(seconds: Int, cost: Double, note: String = "") {
        if (seconds <= 0) return
        val p = prefs ?: return
        asrSeconds += seconds
        asrRequests += 1
        asrCostCny += cost
        push(UsageEntry("ASR", System.currentTimeMillis(), seconds, cost, note))
        p.edit()
            .putInt("asrSeconds", asrSeconds)
            .putInt("asrRequests", asrRequests)
            .putString("asrCost", asrCostCny.toString())
            .putString("recent", recentJson())
            .apply()
    }

    private fun push(e: UsageEntry) {
        recent = (listOf(e) + recent).take(40)
    }

    private fun recentJson(): String {
        val arr = JSONArray()
        for (e in recent) {
            arr.put(JSONObject().apply {
                put("kind", e.kind)
                put("at", e.at)
                put("units", e.units)
                put("cost", e.costCny)
                put("note", e.note)
            })
        }
        return arr.toString()
    }
}

object BalanceApi {
    private val main = Handler(Looper.getMainLooper())
    fun fetch(apiKey: String, onResult: (String?, String?) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(BuildConfig.API_ORIGIN + "/user/balance").openConnection() as HttpURLConnection)
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    main.post { onResult(null, httpErrorMessage(code, raw, "余额查询失败（HTTP $code）")) }
                    return@Thread
                }
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                if (obj == null) {
                    main.post { onResult(null, "余额返回格式无法识别") }
                    return@Thread
                }
                val available = obj.optBoolean("is_available", true)
                val infos = obj.optJSONArray("balance_infos") ?: JSONArray()
                var text: String? = null
                for (i in 0 until infos.length()) {
                    val b = infos.optJSONObject(i) ?: continue
                    if (b.optString("currency") != "CNY" && text != null) continue
                    val total = b.optString("total_balance")
                    val granted = b.optString("granted_balance")
                    val topped = b.optString("topped_up_balance")
                    text = "¥" + total + "（赠金 ¥" + granted + " · 充值 ¥" + topped + "）"
                    if (b.optString("currency") == "CNY") break
                }
                main.post {
                    if (!available) onResult(text, "账户当前不可用（余额不足或受限）")
                    else onResult(text ?: "暂无 CNY 余额信息", null)
                }
            } catch (e: Exception) {
                main.post { onResult(null, "余额查询失败：" + (e.message ?: "网络错误")) }
            } finally {
                conn?.disconnect()
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }
}

