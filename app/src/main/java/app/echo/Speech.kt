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
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朗读：从 /v1/tools/tts 取 24kHz PCM16，半带 FIR 升采样到 48kHz 交给 AudioTrack 播放。
 *
 * 线程与所有权：
 * - UI 线程只改 [paused]/[speaking] 等状态与 [uiSink]（暂停、继续、调音量、解除阻塞写）；
 *   创建/释放 AudioTrack 与阻塞写全部发生在朗读线程内，AudioTrack 归该线程所有并在 finally 释放，
 *   旧任务不可能释放新任务的 track，也不会在新任务开始后仍申请音频焦点。
 * - 每次 start/stop 递增 [generation]（AtomicInteger），陈旧线程的任何副作用都先校验 generation。
 */
object Reader {
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val stateLock = Any()
    private var worker: Thread? = null

    /** UI 线程可安全访问的音频出口：只用于暂停/继续/调音量/解除阻塞写。 */
    @Volatile
    private var uiSink: TtsSink? = null

    @Volatile
    private var pausedFlag = false

    @Volatile
    private var connection: HttpURLConnection? = null

    private var audioManager: AudioManager? = null
    private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = -1

    private var pausedByUser = false
    private var pausedByFocus = false

    var activeId by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set

    private const val OUT_RATE = 48_000
    private const val PREBUFFER_BYTES = 32_000
    private const val TRACK_BUFFER_BYTES = 64 * 1024
    private const val RESAMPLED_BYTES = 32 * 1024
    private const val DUCK_VOLUME = 0.2f

    /** 用户停止或重新开始导致的提前退出，不弹失败提示。 */
    private class StoppedException : Exception()

    fun start(context: Context, text: String, id: String, voice: String, apiKey: String) {
        if (text.isBlank()) return
        if (Recorder.recording || Recorder.transcribing) Recorder.cancel()
        stopAll()
        val my = generation.get()
        val app = context.applicationContext
        appContext = app
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        activeId = id
        loading = true
        speaking = false
        paused = false
        pausedByUser = false
        pausedByFocus = false
        pausedFlag = false
        val thread = Thread { playChunks(text, voice, apiKey, my) }
        thread.priority = Thread.NORM_PRIORITY - 1
        worker = thread
        thread.start()
    }

    /** 暂停；准备阶段（还没出声）也允许暂停，避免按钮变成死控件。 */
    fun pause() {
        if (!speaking && !loading) return
        pausedByUser = true
        pausedFlag = true
        paused = true
        speaking = false
        runCatching { uiSink?.pause() }
    }

    fun resume() {
        if (!paused) return
        if (pausedByFocus && !requestFocus(generation.get())) {
            stopAllWithToast("无法恢复音频焦点，可能有其他应用正在播放")
            return
        }
        pausedByUser = false
        pausedByFocus = false
        pausedFlag = false
        paused = false
        speaking = true
        runCatching {
            uiSink?.setVolume(1f)
            uiSink?.play()
        }
    }

    /** 停止朗读并清空状态；AudioTrack 由朗读线程在 finally 里释放。 */
    fun stopAll() {
        val stale = generation.incrementAndGet()
        pausedFlag = false
        pausedByUser = false
        pausedByFocus = false
        val conn = connection
        connection = null
        runCatching { conn?.disconnect() }
        val sink = synchronized(stateLock) {
            val s = uiSink
            uiSink = null
            s
        }
        // 解除可能阻塞在 write() 上的旧线程：play 后写入会立即返回，旧线程随即发现 generation 变化
        runCatching { sink?.setVolume(1f) }
        runCatching { sink?.play() }
        abandonFocus(stale)
        worker = null
        activeId = ""
        loading = false
        speaking = false
        paused = false
    }

    private fun stopAllWithToast(message: String) {
        val app = appContext ?: return
        stopAll()
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    private fun playChunks(text: String, voice: String, apiKey: String, my: Int) {
        val chunks = Billing.splitForTts(text)
        if (chunks.isEmpty()) {
            finish(my, "没有可朗读的文本")
            return
        }
        val minBytes = runCatching {
            AudioTrack.getMinBufferSize(
                OUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }.getOrDefault(0).let { if (it <= 0) 16 * 1024 else it }

        var audio: TtsSink? = null
        var started = false
        var written = 0L
        var timedOut = false
        val resampler = HalfBandInterpolator()
        val shorts = ShortArray(8 * 1024)
        val outShorts = ShortArray(16 * 1024)
        val outBytes = ByteArray(RESAMPLED_BYTES)
        var shortCount = 0
        var carry = -1

        val prebuffer = ByteArrayOutputStream()

        fun emit(outCount: Int) {
            if (outCount <= 0) return
            val sink = audio ?: return
            val len = encode(outShorts, outCount, outBytes)
            if (!started) {
                prebuffer.write(outBytes, 0, len)
                if (prebuffer.size() < PREBUFFER_BYTES) return
                val batch = prebuffer.toByteArray()
                prebuffer.reset()
                if (!writeFully(sink, batch, batch.size, my)) failWrite(my)
                written += batch.size
                runCatching { sink.play() }
                started = true
                main.post {
                    if (my == generation.get()) {
                        loading = false
                        speaking = true
                    }
                }
            } else {
                if (!writeFully(sink, outBytes, len, my)) failWrite(my)
                written += len
            }
        }

        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(speechAttrs())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBytes * 2, TRACK_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            checkAlive(my)
            val sink = AudioTrackSink(track)
            audio = sink
            synchronized(stateLock) {
                if (my != generation.get()) throw StoppedException()
                uiSink = sink
            }
            if (!requestFocus(my)) {
                finish(my, "无法获取音频焦点，可能有其他应用正在播放")
                return
            }

            for (chunk in chunks) {
                checkAlive(my)
                waitIfPaused(my)
                streamChunk(chunk, voice, apiKey, my) { buffer, n ->
                    var i = 0
                    while (i < n) {
                        val b = buffer[i].toInt() and 0xFF
                        if (carry < 0) {
                            carry = b
                        } else {
                            shorts[shortCount++] = ((b shl 8) or carry).toShort()
                            carry = -1
                            if (shortCount == shorts.size) {
                                emit(resampler.process(shorts, shortCount, outShorts))
                                shortCount = 0
                            }
                        }
                        i++
                    }
                }
                carry = -1
                if (shortCount > 0) {
                    emit(resampler.process(shorts, shortCount, outShorts))
                    shortCount = 0
                }
            }

            if (!started && prebuffer.size() > 0) {
                val sink: TtsSink? = audio
                val batch = prebuffer.toByteArray()
                prebuffer.reset()
                if (sink != null && !writeFully(sink, batch, batch.size, my)) failWrite(my)
                written += batch.size
                runCatching { sink?.play() }
                started = true
                main.post {
                    if (my == generation.get()) {
                        loading = false
                        speaking = true
                    }
                }
            }

            if (started) {
                checkAlive(my)
                val sink: TtsSink? = audio
                if (sink != null) {
                    val waiter = TtsTailWaiter(written / 2L * 1000L / OUT_RATE)
                    while (my == generation.get()) {
                        when (waiter.step(sink.playbackHeadBytes(), written, pausedFlag)) {
                            TtsTailWaiter.Result.COMPLETED -> break
                            TtsTailWaiter.Result.TIMED_OUT -> {
                                timedOut = true
                                break
                            }
                            TtsTailWaiter.Result.WAITING -> Thread.sleep(30)
                        }
                    }
                    checkAlive(my)
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            "EchoTts",
                            "written=" + written + " underruns=" + sink.underrunCount() +
                                " paused=" + pausedFlag
                        )
                    }
                }
            }

            when {
                !started -> finish(my, "没有可播放的音频")
                timedOut -> finish(my, "朗读中断：音频输出超时")
                else -> finish(
                    my,
                    "朗读完成 · 计费约 " + Billing.estimate(
                        Billing.ttsCost(Billing.ttsBilledChars(text))
                    )
                )
            }
        } catch (stopped: StoppedException) {
            // 用户停止或重新开始：静默收尾
        } catch (e: Exception) {
            if (my == generation.get()) finish(my, "朗读失败：" + (e.message ?: "网络异常"))
        } finally {
            releaseSink(audio, my)
        }
    }

    private fun streamChunk(
        chunk: String,
        voice: String,
        apiKey: String,
        my: Int,
        onBytes: (ByteArray, Int) -> Unit,
    ) {
        val readBuffer = ByteArray(8 * 1024)
        val conn = URL(BuildConfig.API_ORIGIN + "/v1/tools/tts").openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
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
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(ttsErrorMessage(code, raw))
            }
            val billed = Billing.ttsBilledChars(chunk)
            UsageLedger.recordTts(billed, Billing.ttsCost(billed), "朗读")
            val input = BufferedInputStream(conn.inputStream)
            while (true) {
                val n = input.read(readBuffer)
                if (n < 0) break
                if (n == 0) continue
                checkAlive(my)
                waitIfPaused(my)
                onBytes(readBuffer, n)
            }
        } finally {
            runCatching { conn.disconnect() }
            if (connection === conn) connection = null
        }
    }

    private fun waitIfPaused(my: Int) {
        while (pausedFlag) {
            checkAlive(my)
            Thread.sleep(40)
        }
    }

    private fun checkAlive(my: Int) {
        if (my != generation.get()) throw StoppedException()
    }

    private fun failWrite(my: Int) {
        checkAlive(my)
        throw IllegalStateException("音频写入失败，朗读已停止")
    }

    /** 阻塞写入；generation 变化或写入失败返回 false，由调用方区分停止与真实错误。 */
    private fun writeFully(sink: TtsSink, data: ByteArray, size: Int, my: Int): Boolean {
        var off = 0
        while (off < size) {
            if (my != generation.get()) return false
            val n = sink.write(data, off, size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun encode(samples: ShortArray, count: Int, out: ByteArray): Int {
        var p = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
        }
        return p
    }

    private fun releaseSink(sink: TtsSink?, my: Int) {
        if (sink != null) {
            synchronized(stateLock) {
                if (uiSink === sink) uiSink = null
            }
            runCatching { sink.pause() }
            runCatching { sink.flush() }
            runCatching { sink.release() }
        }
        abandonFocus(my)
    }

    private fun requestFocus(my: Int): Boolean {
        val am = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAttrs())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change -> onFocusChange(my, change) }
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false
        synchronized(stateLock) {
            focusRequest = request
            focusGeneration = my
        }
        return true
    }

    private fun abandonFocus(my: Int) {
        val am = audioManager ?: return
        val request: AudioFocusRequest?
        synchronized(stateLock) {
            if (focusGeneration != my) return
            request = focusRequest
            focusRequest = null
            focusGeneration = -1
        }
        if (request != null) runCatching { am.abandonAudioFocusRequest(request) }
    }

    private fun onFocusChange(my: Int, change: Int) {
        if (my != generation.get()) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedFlag = true
                runCatching { uiSink?.pause() }
                main.post {
                    if (my == generation.get()) {
                        stopAllWithToast("朗读已停止：其他应用占用了音频")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByFocus = true
                pausedFlag = true
                paused = true
                speaking = false
                runCatching { uiSink?.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runCatching { uiSink?.setVolume(DUCK_VOLUME) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { uiSink?.setVolume(1f) }
                if (pausedByFocus && !pausedByUser) {
                    pausedByFocus = false
                    pausedFlag = false
                    paused = false
                    speaking = true
                    runCatching { uiSink?.play() }
                }
            }
        }
    }

    private fun speechAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun ttsErrorMessage(code: Int, raw: String): String = when (code) {
        413 -> "这段文字超出官方单次朗读上限，请分段后重试"
        else -> httpErrorMessage(code, raw, "朗读请求失败（HTTP " + code + "）")
    }

    private fun finish(my: Int, message: String) {
        val app = appContext ?: return
        val conn = connection
        connection = null
        runCatching { conn?.disconnect() }
        main.post {
            if (my != generation.get()) return@post
            activeId = ""
            loading = false
            speaking = false
            paused = false
            pausedFlag = false
            pausedByUser = false
            pausedByFocus = false
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
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
