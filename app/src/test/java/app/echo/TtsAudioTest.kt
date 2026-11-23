package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** JVM 单测：TTS 升采样与收尾判定的纯逻辑（不依赖 AudioTrack 设备）。 */
class TtsAudioTest {

    private fun sine(count: Int, hz: Double, amplitude: Double, sampleRate: Double = 24_000.0): ShortArray =
        ShortArray(count) { i ->
            (amplitude * 32767.0 * sin(2.0 * PI * hz * i / sampleRate)).toInt().toShort()
        }

    @Test
    fun dcHalfScaleKeepsAmplitude() {
        val input = ShortArray(400) { 16_384 }
        val out = ShortArray(800)
        val n = HalfBandInterpolator().process(input, input.size, out)
        assertEquals(800, n)
        // 滤波收敛后（跳过前 100 个输出样本），0.5 的直流应保持 0.5。
        val tail = out.copyOfRange(100, out.size)
        val avg = tail.sumOf { it.toLong() }.toDouble() / tail.size
        assertTrue("avg=$avg", abs(avg - 16_383.5) < 40.0)
    }

    @Test
    fun sineHalfScaleKeepsPeak() {
        val input = sine(2_000, 1_000.0, 0.5)
        val out = ShortArray(4_000)
        HalfBandInterpolator().process(input, input.size, out)
        val peak = out.copyOfRange(200, out.size).maxOf { abs(it.toInt()) }
        assertTrue("peak=$peak", abs(peak - 16_384) < 16_384 * 0.02)
    }

    @Test
    fun outputLengthIsExactlyDouble() {
        val input = ShortArray(257)
        val out = ShortArray(514)
        assertEquals(514, HalfBandInterpolator().process(input, input.size, out))
    }

    @Test
    fun chunkedProcessingMatchesWholePass() {
        val input = sine(1_500, 440.0, 0.4)
        val whole = ShortArray(3_000)
        HalfBandInterpolator().process(input, input.size, whole)

        val filter = HalfBandInterpolator()
        val chunked = ShortArray(3_000)
        var pos = 0
        var off = 0
        while (off < input.size) {
            val count = minOf(64, input.size - off)
            val part = ShortArray(2 * count)
            filter.process(input.copyOfRange(off, off + count), count, part)
            System.arraycopy(part, 0, chunked, pos, part.size)
            pos += part.size
            off += count
        }
        assertEquals(3_000, pos)
        for (i in whole.indices) assertEquals("i=$i", whole[i].toInt(), chunked[i].toInt())
    }

    @Test
    fun tailWaiterCompletesWhenPlayedOut() {
        var now = 0L
        val waiter = TtsTailWaiter(expectedMs = 100, graceMs = 100, clock = { now })
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 500, writtenBytes = 2_000, paused = false))
        now = 50
        assertEquals(TtsTailWaiter.Result.COMPLETED, waiter.step(headBytes = 2_000, writtenBytes = 2_000, paused = false))
    }

    @Test
    fun tailWaiterDoesNotCompleteAtHalfPlayed() {
        var now = 0L
        val waiter = TtsTailWaiter(expectedMs = 100, graceMs = 100, clock = { now })
        now = 30
        // 播放进度到一半（曾因 headBytes 被再乘 2 而在这里提前判「播完」）。
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 1_000, writtenBytes = 2_000, paused = false))
        now = 60
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 1_999, writtenBytes = 2_000, paused = false))
    }

    @Test
    fun tailWaiterTimesOutWhenOutputStalls() {
        var now = 0L
        val waiter = TtsTailWaiter(expectedMs = 100, graceMs = 100, clock = { now })
        now = 150
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 0, writtenBytes = 2_000, paused = false))
        now = 201
        assertEquals(TtsTailWaiter.Result.TIMED_OUT, waiter.step(headBytes = 0, writtenBytes = 2_000, paused = false))
    }

    @Test
    fun tailWaiterDoesNotCountPausedTime() {
        var now = 0L
        val waiter = TtsTailWaiter(expectedMs = 1_000, graceMs = 0, clock = { now })
        now = 400
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 0, writtenBytes = 2_000, paused = true))
        // 暂停 5 秒：这段时间不推进 deadline。
        now = 5_400
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 0, writtenBytes = 2_000, paused = true))
        assertEquals(TtsTailWaiter.Result.WAITING, waiter.step(headBytes = 0, writtenBytes = 2_000, paused = false))
        now = 5_500
        assertEquals(TtsTailWaiter.Result.COMPLETED, waiter.step(headBytes = 2_000, writtenBytes = 2_000, paused = false))
    }
}
