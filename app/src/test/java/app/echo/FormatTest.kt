package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * JVM unit tests for the tiny formatting helpers used across the UI.
 * fmtTime uses the device's default time zone, so the expected value is
 * recomputed with the same rule instead of hard-coding a wall-clock string.
 */
class FormatTest {
    private val expectedTimeFmt =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())

    @Test
    fun fmtTimeMatchesExpectedPatternAndValue() {
        val ts = 1_700_000_000_000L
        assertEquals(expectedTimeFmt.format(Instant.ofEpochMilli(ts)), fmtTime(ts))
    }

    @Test
    fun fmtTimeHasStableShape() {
        val s = fmtTime(0L)
        assertTrue("unexpected fmtTime output: $s", Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(s))
    }

    @Test
    fun fmtCnyAlwaysHasFourDecimals() {
        assertEquals("\u00A50.0000", fmtCny(0.0))
        assertEquals("\u00A50.1000", fmtCny(0.1))
        assertEquals("\u00A512.0000", fmtCny(12.0))
        assertEquals("\u00A51234.5678", fmtCny(1234.5678))
    }

    @Test
    fun fmtCnyRoundsToFourDecimals() {
        assertEquals("\u00A51.2346", fmtCny(1.23456))
    }
}
