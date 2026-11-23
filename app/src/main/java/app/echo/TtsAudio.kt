package app.echo

import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2 倍半带 FIR 升采样：24 kHz PCM16 -> 48 kHz PCM16。
 *
 * 两个多相分支交替输出；系数与 1.0.4 的 Up2 完全一致（TAPS=24、Hann 窗），
 * 零相增益归一到 1：0.5 幅度正弦在 100 Hz – 8 kHz 输出 0.4998 – 0.5000，
 * 10 kHz 约 -0.1 dB，11 kHz 约 -1.4 dB，14 kHz 镜像约 -38 dB。
 */
internal class HalfBandInterpolator {
    private val even = FloatArray(TAPS)
    private val odd = FloatArray(TAPS)
    private val hist = FloatArray(TAPS)

    init {
        val len = 2 * TAPS
        val mid = (len - 1) / 2.0
        val h = FloatArray(len)
        var sum = 0f
        for (j in 0 until len) {
            val x = PI * (j - mid) / 2.0
            val sinc = sin(x) / x
            val window = WINDOW_A - WINDOW_B * cos(2.0 * PI * j / (len - 1)) +
                WINDOW_C * cos(4.0 * PI * j / (len - 1))
            val v = (0.5 * sinc * window).toFloat()
            h[j] = v
            sum += v
        }
        val gain = 2f / sum
        for (i in 0 until TAPS) {
            even[i] = h[2 * i] * gain
            odd[i] = h[2 * i + 1] * gain
        }
    }

    /** 清空滤波器历史，准备开始新一段音频。 */
    fun reset() {
        hist.fill(0f)
    }

    /**
     * 处理 [count] 个输入样本，向 [out] 写入 2*count 个输出样本。
     * [out] 至少要有 2*count 的空间。
     * @return 写入 [out] 的样本数（恒为 2*count）。
     */
    fun process(input: ShortArray, count: Int, out: ShortArray): Int {
        var p = 0
        for (n in 0 until count) {
            for (i in TAPS - 1 downTo 1) hist[i] = hist[i - 1]
            hist[0] = input[n] / 32768f
            var y0 = 0f
            var y1 = 0f
            for (i in 0 until TAPS) {
                y0 += even[i] * hist[i]
                y1 += odd[i] * hist[i]
            }
            out[p++] = clamp(y0)
            out[p++] = clamp(y1)
        }
        return p
    }

    private fun clamp(v: Float): Short {
        val x = if (v > 1f) 1f else if (v < -1f) -1f else v
        return (x * 32767f).roundToInt().toShort()
    }

    companion object {
        const val TAPS = 24
        private const val WINDOW_A = 0.42
        private const val WINDOW_B = 0.5
        private const val WINDOW_C = 0.08
    }
}

/**
 * 朗读音频输出的最小接口。
 *
 * 生产实现是 [AudioTrackSink]；抽出来后 Reader 只依赖这里，
 * 便于对播放/暂停/写失败/收尾逻辑做 JVM 单测。
 */
internal interface TtsSink {
    fun play()
    fun pause()
    fun write(data: ByteArray, offset: Int, size: Int): Int

    /** 已播放的字节数（帧数 * 2，mono PCM16）。 */
    fun playbackHeadBytes(): Long
    fun underrunCount(): Int
    fun setVolume(volume: Float)
    fun flush()
    fun release()
}

/** [TtsSink] 的 AudioTrack 实现；所有权归创建它的朗读线程。 */
internal class AudioTrackSink(private val track: AudioTrack) : TtsSink {
    override fun play() {
        track.play()
    }

    override fun pause() {
        track.pause()
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int =
        track.write(data, offset, size)

    override fun playbackHeadBytes(): Long = track.playbackHeadPosition.toLong() * 2

    override fun underrunCount(): Int = track.underrunCount

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override fun flush() {
        track.pause()
        track.flush()
    }

    override fun release() {
        track.release()
    }
}

/**
 * 等待 AudioTrack 播完已写入的数据，用于区分三种收尾：
 * 正常播完 / 设备音频输出停住（超时）/ 仍在进行中。
 *
 * 暂停期间不推进 deadline，避免用户「暂停一会儿再继续」被误判为播放超时。
 */
internal class TtsTailWaiter(
    expectedMs: Long,
    private val graceMs: Long = 2_000,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    enum class Result { WAITING, COMPLETED, TIMED_OUT }

    private var deadline = clock() + expectedMs + graceMs
    private var pausedAt: Long? = null

    fun step(headBytes: Long, writtenBytes: Long, paused: Boolean): Result {
        val now = clock()
        if (paused) {
            if (pausedAt == null) pausedAt = now
            return Result.WAITING
        }
        pausedAt?.let {
            deadline += now - it
            pausedAt = null
        }
        // headBytes 已经是字节数（playbackHeadPosition 帧数 * 2），不能再乘 2；
        // 否则播放到一半就会被判成「播完」，提前释放播放器截掉尾部音频。
        if (writtenBytes > 0 && headBytes >= writtenBytes) return Result.COMPLETED
        if (now > deadline) return Result.TIMED_OUT
        return Result.WAITING
    }
}
