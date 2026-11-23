package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for Billing's pure billing math.
 * No Android framework / network is touched by these functions.
 */
class BillingTest {
    @Test
    fun ttsBilledCharsCountsAsciiAsOne() {
        assertEquals(5, Billing.ttsBilledChars("hello"))
        assertEquals(8, Billing.ttsBilledChars("你好, ok"))
    }

    @Test
    fun ttsBilledCharsCountsHanAsTwo() {
        assertEquals(4, Billing.ttsBilledChars("你好"))
        assertEquals(2, Billing.ttsBilledChars("\uD840\uDC00")) // supplementary CJK, one code point
    }

    @Test
    fun ttsBilledCharsCountsEmojiAsOne() {
        assertEquals(1, Billing.ttsBilledChars("\uD83D\uDE00"))
    }

    @Test
    fun ttsCostIsThreeYuanPerTenThousandBilledChars() {
        assertEquals(3.0, Billing.ttsCost(10_000), 1e-9)
        assertEquals(2.7, Billing.ttsCost(9_000), 1e-9)
        assertEquals(0.0, Billing.ttsCost(0), 1e-9)
    }

    @Test
    fun asrCostIsPointSevenEightYuanPerHour() {
        assertEquals(0.78, Billing.asrCost(3_600), 1e-9)
        assertEquals(0.39, Billing.asrCost(1_800), 1e-9)
        assertEquals(0.0, Billing.asrCost(0), 1e-9)
    }

    @Test
    fun asrMaxCostRoundsUpToTheCent() {
        // 120 s * 0.78 / 3600 = 0.026 -> up to 0.03
        assertEquals(0.03, Billing.asrMaxCost(), 1e-9)
    }

    @Test
    fun ceilCnyRoundsUpToTwoDecimals() {
        assertEquals(0.0, Billing.ceilCny(0.0), 1e-9)
        assertEquals(0.01, Billing.ceilCny(0.001), 1e-9)
        assertEquals(1.24, Billing.ceilCny(1.234), 1e-9)
        assertEquals(0.10, Billing.ceilCny(0.10), 1e-9)
    }

    @Test
    fun quoteAlwaysHasTwoDecimals() {
        assertEquals("\u00A50.00", Billing.quote(0.0))
        assertEquals("\u00A50.01", Billing.quote(0.001))
        assertEquals("\u00A51.24", Billing.quote(1.234))
    }

    @Test
    fun estimateSwitchesPrecisionBelowOneCent() {
        assertEquals("\u00A50.0040", Billing.estimate(0.004))
        assertEquals("\u00A50.01", Billing.estimate(0.01))
        assertEquals("\u00A51.00", Billing.estimate(1.0))
    }

    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("00:00", Billing.clock(0))
        assertEquals("01:30", Billing.clock(90_000))
        assertEquals("59:59", Billing.clock(3_599_000))
        assertEquals("00:00", Billing.clock(-5_000))
    }

    @Test
    fun splitForTtsReturnsEmptyForBlankInput() {
        assertTrue(Billing.splitForTts("").isEmpty())
        assertTrue(Billing.splitForTts("   \n\t ").isEmpty())
    }

    @Test
    fun splitForTtsKeepsEveryCharacter() {
        val text = "你好。世界！第二行\nabc"
        val parts = Billing.splitForTts(text, 100)
        assertEquals(text.trim(), parts.joinToString(""))
    }

    @Test
    fun splitForTtsRespectsBilledLimitPerPiece() {
        val text = "这是第一句话。This is the second. 第三句！"
        for (limit in listOf(6, 8, 12)) {
            val parts = Billing.splitForTts(text, limit)
            assertTrue("limit=$limit produced no parts", parts.isNotEmpty())
            for (p in parts) {
                assertTrue(
                    "piece exceeded limit=$limit: '$p' =" + Billing.ttsBilledChars(p),
                    Billing.ttsBilledChars(p) <= limit,
                )
            }
        }
    }

    @Test
    fun splitForTtsSplitsLongAsciiRun() {
        assertEquals(listOf("abcd", "efgh"), Billing.splitForTts("abcdefgh", 4))
    }

    @Test
    fun splitForTtsSplitsLongChineseRun() {
        assertEquals(listOf("你好世", "界啊"), Billing.splitForTts("你好世界啊", 6))
    }

    @Test
    fun splitForTtsSplitsEmojiBySingleBillableUnit() {
        val parts = Billing.splitForTts("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 1)
        assertEquals(3, parts.size)
        assertEquals("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", parts.joinToString(""))
    }
}
