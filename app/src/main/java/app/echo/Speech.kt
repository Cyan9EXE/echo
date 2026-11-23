package app.echo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object Reader {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var worker: Thread? = null
    @Volatile private var pausedFlag = false

    var activeId by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set

    @Volatile private var track: AudioTrack? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var audioManager: AudioManager? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var lastUnderruns = 0
    private var appContext: Context? = null

    private const val OUT_RATE = 48_000
    private const val PREBUFFER_BYTES = 32_000
    private const val TRACK_BUFFER_BYTES = 64 * 1024
    private const val RESAMPLED_BYTES = 32 * 1024

    fun start(context: Context, text: String, id: String, voice: String, apiKey: String) {
        if (text.isBlank()) return
        stopAll()
        val my = generation
        val app = context.applicationContext
        appContext = app
        activeId = id
        loading = true
        speaking = false
        paused = false
        pausedFlag = false
        worker = Thread {
            playChunks(app, text, voice, apiKey, my)
        }.apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun pause() {
        if (!speaking) return
        pausedFlag = true
        paused = true
        speaking = false
        runCatching { track?.pause() }
    }

    fun resume() {
        if (!paused) return
        pausedFlag = false
        paused = false
        speaking = true
        runCatching { track?.play() }
    }

    fun stopAll() {
        generation++
        pausedFlag = false
        connection?.disconnect()
        connection = null
        releaseAudio()
        worker = null
        activeId = ""
        loading = false
        speaking = false
        paused = false
    }

    private fun speechAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun takeFocus(my: Int) {
        val am = runCatching {
            appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return
        audioManager = am
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAttrs())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        pausedFlag = true
                        runCatching { track?.pause() }
                        main.post {
                            if (my == generation) {
                                paused = true
                                speaking = false
                            }
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (my == generation) {
                            pausedFlag = false
                            runCatching { track?.play() }
                            main.post {
                                if (my == generation) {
                                    paused = false
                                    speaking = true
                                }
                            }
                        }
                    }
                }
            }
            .build()
        val granted = runCatching { am.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun releaseAudio() {
        val am = audioManager
        val request = focusRequest
        focusRequest = null
        if (am != null && request != null) {
            runCatching { am.abandonAudioFocusRequest(request) }
        }
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    private fun playChunks(app: Context, text: String, voice: String, apiKey: String, my: Int) {
        val chunks = Billing.splitForTts(text)
        if (chunks.isEmpty()) {
            finish(my, "没有可朗读的文本")
            return
        }
        val minBytes = AudioTrack.getMinBufferSize(
            OUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it <= 0) 16 * 1024 else it }
        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(speechAttrs())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBytes * 2, TRACK_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            finish(my, "无法创建音频输出：" + (e.message ?: "未知错误"))
            return
        }
        track = audio
        takeFocus(my)

        val resampler = Up2()
        val shorts = ShortArray(8 * 1024)
        val outShorts = ShortArray(16 * 1024)
        val outBytes = ByteArray(RESAMPLED_BYTES)
        val readBuffer = ByteArray(8 * 1024)
        val prebuffer = ByteArrayOutputStream()
        var pendingLow = -1
        var bytesWritten = 0L
        var started = false

        fun push(bytes: ByteArray, len: Int) {
            if (len <= 0) return
            if (!started) {
                prebuffer.write(bytes, 0, len)
                if (prebuffer.size() >= PREBUFFER_BYTES) {
                    val batch = prebuffer.toByteArray()
                    prebuffer.reset()
                    writeFully(audio, batch, batch.size)
                    bytesWritten += batch.size
                    audio.play()
                    started = true
                    main.post {
                        if (my == generation) {
                            loading = false
                            speaking = true
                        }
                    }
                }
            } else {
                writeFully(audio, bytes, len)
                bytesWritten += len
            }
        }

        try {
            for (chunk in chunks) {
                if (my != generation) return
                while (pausedFlag && my == generation) SystemClock.sleep(40)
                if (my != generation) return
                val conn = URL(BuildConfig.API_ORIGIN + "/v1/tools/tts").openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "audio/pcm, application/octet-stream")
                val body = JSONObject().apply {
                    put("text", chunk)
                    put("voice_id", voice)
                    put("format", "pcm")
                    put("filter_markdown", true)
                }.toString()
                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException(httpErrorMessage(code, raw, "朗读请求失败（HTTP " + code + "）"))
                }
                val billed = Billing.ttsBilledChars(chunk)
                UsageLedger.recordTts(billed, Billing.ttsCost(billed), "朗读")
                BufferedInputStream(conn.inputStream).use { input ->
                    while (true) {
                        val n = input.read(readBuffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (my != generation) return
                        while (pausedFlag && my == generation) SystemClock.sleep(40)
                        if (my != generation) return
                        var i = 0
                        var count = 0
                        if (pendingLow >= 0) {
                            val lo = pendingLow
                            val hi = readBuffer[0].toInt() and 0xFF
                            shorts[count++] = ((hi shl 8) or lo).toShort()
                            pendingLow = -1
                            i = 1
                        }
                        val remain = n - i
                        val pairs = remain / 2
                        var q = 0
                        while (q < pairs) {
                            val lo = readBuffer[i + q * 2].toInt() and 0xFF
                            val hi = readBuffer[i + q * 2 + 1].toInt() and 0xFF
                            shorts[count++] = ((hi shl 8) or lo).toShort()
                            q++
                        }
                        if (remain and 1 == 1) pendingLow = readBuffer[i + remain - 1].toInt() and 0xFF
                        val produced = resampler.process(shorts, count, outShorts)
                        var outLen = 0
                        var k = 0
                        while (k < produced) {
                            val v = outShorts[k].toInt()
                            outBytes[outLen++] = (v and 0xFF).toByte()
                            outBytes[outLen++] = ((v shr 8) and 0xFF).toByte()
                            k++
                        }
                        push(outBytes, outLen)
                    }
                }
                runCatching { conn.disconnect() }
                connection = null
                pendingLow = -1
            }
            if (!started && prebuffer.size() > 0) {
                val batch = prebuffer.toByteArray()
                prebuffer.reset()
                writeFully(audio, batch, batch.size)
                bytesWritten += batch.size
                audio.play()
                started = true
                main.post {
                    if (my == generation) {
                        loading = false
                        speaking = true
                    }
                }
            }
            if (started) {
                val expectedMs = bytesWritten / 2 * 1000 / OUT_RATE
                val deadline = SystemClock.uptimeMillis() + expectedMs + 2_000
                while (my == generation) {
                    val head = audio.playbackHeadPosition.toLong()
                    if (!pausedFlag && head * 2 >= bytesWritten) break
                    if (SystemClock.uptimeMillis() > deadline) break
                    SystemClock.sleep(30)
                }
                lastUnderruns = runCatching { audio.underrunCount }.getOrDefault(0)
            }
            if (my == generation) {
                releaseAudio()
                val done = if (started) {
                    "朗读完成 · 计费约 " + Billing.estimate(Billing.ttsCost(Billing.ttsBilledChars(text)))
                } else {
                    "没有可播放的音频"
                }
                finish(my, done)
            }
        } catch (e: Exception) {
            if (my == generation) {
                releaseAudio()
                finish(my, "朗读失败：" + (e.message ?: "网络错误"))
            }
        }
    }

    private fun writeFully(audio: AudioTrack, data: ByteArray, size: Int) {
        var off = 0
        while (off < size) {
            val n = audio.write(data, off, size - off)
            if (n <= 0) return
            off += n
        }
    }

    private fun finish(my: Int, message: String) {
        releaseAudio()
        connection?.disconnect()
        connection = null
        main.post {
            if (my != generation) return@post
            activeId = ""
            loading = false
            speaking = false
            paused = false
            pausedFlag = false
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private class Up2(private val taps: Int = 24) {
        private val a = FloatArray(taps)
        private val b = FloatArray(taps)
        private val hist = FloatArray(taps)

        init {
            val len = 2 * taps
            val mid = (len - 1) / 2.0
            val h = DoubleArray(len)
            var sum = 0.0
            var j = 0
            while (j < len) {
                val t = j - mid
                val sinc = if (t == 0.0) 1.0 else sin(PI * t / 2.0) / (PI * t / 2.0)
                val w = 0.42 - 0.5 * cos(2.0 * PI * j / (len - 1)) + 0.08 * cos(4.0 * PI * j / (len - 1))
                h[j] = 0.5 * sinc * w
                sum += h[j]
                j++
            }
            val gain = 2.0 / sum
            var i = 0
            while (i < taps) {
                a[i] = (h[2 * i] * gain).toFloat()
                b[i] = (h[2 * i + 1] * gain).toFloat()
                i++
            }
        }

        fun process(input: ShortArray, count: Int, out: ShortArray): Int {
            var p = 0
            var n = 0
            while (n < count) {
                var idx = taps - 1
                while (idx > 0) {
                    hist[idx] = hist[idx - 1]
                    idx--
                }
                hist[0] = input[n] / 32768f
                var y0 = 0f
                var y1 = 0f
                var i = 0
                while (i < taps) {
                    val x = hist[i]
                    y0 += a[i] * x
                    y1 += b[i] * x
                    i++
                }
                out[p++] = clamp(y0)
                out[p++] = clamp(y1)
                n++
            }
            return p
        }

        private fun clamp(v: Float): Short {
            val c = if (v > 1f) 1f else if (v < -1f) -1f else v
            return (c * 32767f).roundToInt().toShort()
        }
    }
}

object Recorder {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var discard = false
    private var audioRecord: AudioRecord? = null
    private var appContext: Context? = null

    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var elapsed by mutableStateOf(0L)
        private set

    fun start(
        context: Context,
        apiKey: String,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (recording || transcribing) return
        appContext = context.applicationContext
        recording = true
        transcribing = false
        elapsed = 0
        stopFlag = false
        discard = false
        thread = Thread {
            val pcm = ByteArrayOutputStream(3200 * 120)
            val min = AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).let { if (it > 0) it else 6400 }
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(min, 6400),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    failStart("无法启动麦克风（可能被其他应用占用）")
                    return@Thread
                }
                audioRecord = rec
                rec.startRecording()
                val started = SystemClock.uptimeMillis()
                val buf = ByteArray(3200)
                var lastTick = 0L
                while (!stopFlag) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        pcm.write(buf, 0, n)
                        val ms = SystemClock.uptimeMillis() - started
                        if (ms - lastTick >= 200) {
                            lastTick = ms
                            main.post { elapsed = ms }
                        }
                    }
                    if (SystemClock.uptimeMillis() - started >= Billing.ASR_MAX_SECONDS * 1000L) stopFlag = true
                }
                rec.stop()
                rec.release()
                audioRecord = null
                if (discard) {
                    resetIdle()
                    return@Thread
                }
                if (pcm.size() < 3200) {
                    failStart("录音太短，请重试")
                    return@Thread
                }
                val wav = pcm.toWav()
                main.post {
                    recording = false
                    transcribing = true
                }
                transcribe(apiKey, wav, onText, onError)
            } catch (e: SecurityException) {
                failStart("没有麦克风权限")
            } catch (e: Exception) {
                runCatching { rec?.release() }
                audioRecord = null
                if (!discard) failStart("录音失败：" + (e.message ?: "未知错误"))
            }
        }.apply { priority = Thread.NORM_PRIORITY; start() }
    }

    fun stop() {
        if (!recording) return
        stopFlag = true
    }

    fun cancel() {
        discard = true
        stopFlag = true
        runCatching { audioRecord?.stop() }
        resetIdle()
    }

    private fun resetIdle() {
        main.post {
            recording = false
            transcribing = false
            elapsed = 0
        }
    }

    private fun failStart(message: String) {
        resetIdle()
        main.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun transcribe(apiKey: String, wav: ByteArray, onText: (String) -> Unit, onError: (String) -> Unit) {
        val started = SystemClock.uptimeMillis()
        val boundary = "----echo" + UUID.randomUUID().toString().replace("-", "")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BuildConfig.API_ORIGIN + "/v1/tools/asr").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer " + apiKey)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            val head = (
                "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"echo-voice.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            val tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
            conn.outputStream.use { out ->
                out.write(head)
                out.write(wav)
                out.write(tail)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = httpErrorMessage(code, raw, "语音识别失败（HTTP $code）")
                main.post {
                    transcribing = false
                    onError(msg)
                }
                return
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            val text = obj?.optString("text").orEmpty().trim()
            val durationMs = obj?.optLong("duration_ms", 0L) ?: 0L
            val seconds = if (durationMs > 0) ceil(durationMs / 1000.0).toInt() else 1
            val cost = Billing.asrCost(seconds)
            UsageLedger.recordAsr(seconds, cost, "语音识别")
            main.post {
                transcribing = false
                elapsed = 0
                if (text.isBlank()) {
                    onError("没有识别到语音内容")
                } else {
                    Toast.makeText(appContext, "识别完成 · 计费约 " + Billing.estimate(cost), Toast.LENGTH_SHORT).show()
                    onText(text)
                }
            }
        } catch (e: Exception) {
            val msg = "语音识别失败：" + (e.message ?: "网络错误")
            main.post {
                transcribing = false
                onError(msg)
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun ByteArrayOutputStream.toWav(): ByteArray {
        val data = toByteArray()
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) header[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v ushr 8) and 0xff).toByte()
            header[off + 2] = ((v ushr 16) and 0xff).toByte()
            header[off + 3] = ((v ushr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v ushr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + data.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, 16_000)
        putInt(28, 16_000 * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, data.size)
        return header + data
    }
}
