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
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        worker = null
        activeId = ""
        loading = false
        speaking = false
        paused = false
    }

    @Volatile private var track: AudioTrack? = null
    @Volatile private var connection: HttpURLConnection? = null

    private fun playChunks(app: Context, text: String, voice: String, apiKey: String, my: Int) {
        val chunks = Billing.splitForTts(text)
        if (chunks.isEmpty()) {
            finish(my, "没有可朗读的文本")
            return
        }
        val minBytes = AudioTrack.getMinBufferSize(
            24_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else 16 * 1024 }
        val audio = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(24_000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBytes * 2, 96 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (audio == null) {
            finish(my, "本机无法创建音频输出")
            return
        }
        track = audio
        var started = false
        var totalCost = 0.0
        var failure: String? = null
        var bytesWritten = 0L
        fun push(bytes: ByteArray, size: Int) {
            writeFully(audio, bytes, size)
            bytesWritten += size
        }
        val prebufferTarget = max(minBytes, 16 * 1024)
        try {
            for (chunk in chunks) {
                if (my != generation) return
                val billed = Billing.ttsBilledChars(chunk)
                val cost = Billing.ttsCost(billed)
                val conn = (URL(BuildConfig.API_ORIGIN + "/v1/tools/tts").openConnection() as HttpURLConnection)
                connection = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.useCaches = false
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
                    val err = runCatching {
                        conn.errorStream?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
                    }.getOrDefault("")
                    failure = httpErrorMessage(code, err, "朗读请求失败（HTTP $code）")
                    break
                }
                UsageLedger.recordTts(billed, cost, "朗读")
                totalCost += cost
                val input = BufferedInputStream(conn.inputStream, 32 * 1024)
                val buffer = ByteArray(8 * 1024)
                var prebuffer = ByteArrayOutputStream(prebufferTarget)
                while (my == generation) {
                    if (pausedFlag) {
                        Thread.sleep(40)
                        continue
                    }
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (!started) {
                        prebuffer.write(buffer, 0, n)
                        if (prebuffer.size() >= prebufferTarget) {
                            val bytes = prebuffer.toByteArray()
                            prebuffer = ByteArrayOutputStream(0)
                            push(bytes, bytes.size)
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
                        push(buffer, n)
                    }
                }
                if (!started && prebuffer.size() > 0) {
                    val bytes = prebuffer.toByteArray()
                    push(bytes, bytes.size)
                    audio.play()
                    started = true
                    main.post {
                        if (my == generation) {
                            loading = false
                            speaking = true
                        }
                    }
                }
                conn.disconnect()
                connection = null
                if (my != generation) return
            }
            if (my == generation && failure == null) {
                val totalFrames = bytesWritten / 2
                val expectedMs = totalFrames * 1000L / 24_000
                var deadline = SystemClock.elapsedRealtime() + expectedMs + 2_000
                while (my == generation) {
                    if (pausedFlag) {
                        deadline = SystemClock.elapsedRealtime() + expectedMs + 2_000
                        Thread.sleep(60)
                        continue
                    }
                    if (audio.playbackHeadPosition.toLong() * 2 >= bytesWritten) break
                    if (SystemClock.elapsedRealtime() > deadline) break
                    Thread.sleep(50)
                }
                runCatching { audio.stop() }
                runCatching { audio.release() }
                track = null
                main.post {
                    if (my == generation) {
                        activeId = ""
                        loading = false
                        speaking = false
                        paused = false
                        if (totalCost > 0) {
                            Toast.makeText(app, "朗读完成 · 计费约 " + Billing.estimate(totalCost), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else if (my == generation) {
                finish(my, failure ?: "朗读中断")
            }
        } catch (e: Exception) {
            if (my == generation) {
                finish(my, if (e is SecurityException) "没有音频播放权限" else "朗读失败：" + (e.message ?: "网络错误"))
            }
        } finally {
            runCatching { if (my != generation) audio.release() }
        }
    }

    private fun writeFully(track: AudioTrack, data: ByteArray, size: Int) {
        var off = 0
        while (off < size) {
            val n = track.write(data, off, size - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun finish(my: Int, message: String) {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        connection?.disconnect()
        connection = null
        main.post {
            if (my == generation) {
                activeId = ""
                loading = false
                speaking = false
                paused = false
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var appContext: Context? = null
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
