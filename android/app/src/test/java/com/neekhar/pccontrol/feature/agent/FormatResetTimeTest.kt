package com.neekhar.pccontrol.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FormatResetTimeTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun parseDurationMsParsesCompoundAndSimpleDurations() {
        assertEquals((4L * 86_400L + 10L * 3_600L) * 1000L, FormatResetTime.parseDurationMs("4d 10h"))
        assertEquals((2L * 3_600L + 38L * 60L) * 1000L, FormatResetTime.parseDurationMs("2h 38m"))
        assertEquals(45L * 60L * 1000L, FormatResetTime.parseDurationMs("45m"))
        assertNull(FormatResetTime.parseDurationMs(""))
        assertNull(FormatResetTime.parseDurationMs(null))
        assertNull(FormatResetTime.parseDurationMs("invalid"))
    }

    @Test
    fun formatCompactDurationFormatsDaysHoursMinutes() {
        assertEquals("6d 11h", FormatResetTime.formatCompactDuration((6L * 24 + 11) * 3_600_000L))
        assertEquals("2d", FormatResetTime.formatCompactDuration(2L * 24 * 3_600_000L))
        assertEquals("2h 38m", FormatResetTime.formatCompactDuration((2L * 60 + 38) * 60_000L))
        assertEquals("4h", FormatResetTime.formatCompactDuration(4L * 3_600_000L))
        assertEquals("42m", FormatResetTime.formatCompactDuration(42L * 60_000L))
        assertEquals("1m", FormatResetTime.formatCompactDuration(5_000L))
        assertNull(FormatResetTime.formatCompactDuration(0L))
        assertNull(FormatResetTime.formatCompactDuration(-1000L))
    }

    @Test
    fun formatResetTimeHandlesUnderHourTodayTomorrowAndLater() {
        val now = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, utc).toInstant()

        val past = ZonedDateTime.of(2026, 11, 25, 9, 59, 0, 0, utc).toInstant()
        assertEquals("Resets now", FormatResetTime.formatResetTime(past.toString(), now, utc))

        val in42m = now.plusSeconds(42 * 60L)
        assertEquals("Resets in 42 min", FormatResetTime.formatResetTime(in42m.toString(), now, utc))

        val today = ZonedDateTime.of(2026, 11, 25, 12, 20, 0, 0, utc).toInstant()
        assertEquals("Resets today, 12:20 pm", FormatResetTime.formatResetTime(today.toString(), now, utc))

        val tomorrow = ZonedDateTime.of(2026, 11, 26, 9, 5, 0, 0, utc).toInstant()
        assertEquals("Resets tomorrow, 9:05 am", FormatResetTime.formatResetTime(tomorrow.toString(), now, utc))

        val later = ZonedDateTime.of(2026, 11, 29, 6, 30, 0, 0, utc).toInstant()
        assertEquals("Resets Sun 29th Nov, 6:30 am", FormatResetTime.formatResetTime(later.toString(), now, utc))
    }
}
