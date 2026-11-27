package com.neekhar.pccontrol.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class FormattersTest {
    @Test
    fun `formatBytes formats across units and handles null and non-finite`() {
        assertEquals("–", PcFormatters.formatBytes(null as Double?))
        assertEquals("–", PcFormatters.formatBytes(Double.NaN))
        assertEquals("–", PcFormatters.formatBytes(Double.POSITIVE_INFINITY))
        assertEquals("0 B", PcFormatters.formatBytes(0L))
        assertEquals("512 B", PcFormatters.formatBytes(512L))
        assertEquals("1.0 KB", PcFormatters.formatBytes(1024L))
        assertEquals("1.5 MB", PcFormatters.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("12 GB", PcFormatters.formatBytes(12L * 1024 * 1024 * 1024, digits = 0))
        assertEquals("2.0 TB", PcFormatters.formatBytes(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatRate appends per second`() {
        assertEquals("–", PcFormatters.formatRate(null as Double?))
        assertEquals("10.5 MB/s", PcFormatters.formatRate(10.5 * 1024 * 1024))
    }

    @Test
    fun `formatDuration formats seconds minutes hours days`() {
        assertEquals("0s", PcFormatters.formatDuration(-5L))
        assertEquals("45s", PcFormatters.formatDuration(45L))
        assertEquals("12m", PcFormatters.formatDuration(12L * 60 + 20))
        assertEquals("3h 14m", PcFormatters.formatDuration(3L * 3600 + 14 * 60))
        assertEquals("2d 5h", PcFormatters.formatDuration(2L * 86400 + 5 * 3600 + 30 * 60))
    }

    @Test
    fun `formatUptimeShort and formatUptimeFor`() {
        assertEquals("15 min", PcFormatters.formatUptimeShort(15 * 60.0))
        assertEquals("4 h", PcFormatters.formatUptimeShort(4 * 3600.0))
        assertEquals("1 day", PcFormatters.formatUptimeShort(86400.0))
        assertEquals("10 days", PcFormatters.formatUptimeShort(10 * 86400.0))

        assertEquals("4h 30m", PcFormatters.formatUptimeFor(4 * 3600.0 + 30 * 60.0))
        assertEquals("3d 2h 15m", PcFormatters.formatUptimeFor(3 * 86400.0 + 2 * 3600.0 + 15 * 60.0))
    }

    @Test
    fun `formatSeenAgo handles all time buckets`() {
        val now = 1_000_000.0
        assertEquals("just now", PcFormatters.formatSeenAgo(now - 30.0, now))
        assertEquals("5 min ago", PcFormatters.formatSeenAgo(now - 5 * 60.0, now))
        assertEquals("3 h ago", PcFormatters.formatSeenAgo(now - 3 * 3600.0, now))
        assertEquals("yesterday", PcFormatters.formatSeenAgo(now - 26 * 3600.0, now))
        assertEquals("4 days ago", PcFormatters.formatSeenAgo(now - 4 * 86400.0, now))
    }

    @Test
    fun `formatShortHost strips ayushlive prefix and tailnet suffix`() {
        assertEquals("hp-pavilion", PcFormatters.formatShortHost("ayushlive-HP-Pavilion"))
        assertEquals("hp-pavilion", PcFormatters.formatShortHost("ayushlive-hp-pavilion.tail65e4a4.ts.net"))
        assertEquals("workstation", PcFormatters.formatShortHost("workstation"))
    }

    @Test
    fun `formatServiceSince parses systemd timestamp`() {
        val nowMillis = java.time.ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        assertEquals(
            "3h",
            PcFormatters.formatServiceSince("Thu 2026-11-26 09:00:00 UTC", nowMillis, ZoneOffset.UTC),
        )
        assertEquals(
            "2d",
            PcFormatters.formatServiceSince("Tue 2026-11-24 12:00:00 UTC", nowMillis, ZoneOffset.UTC),
        )
        assertEquals(
            "15m",
            PcFormatters.formatServiceSince("Thu 2026-11-26 11:45:00 UTC", nowMillis, ZoneOffset.UTC),
        )
        assertEquals("", PcFormatters.formatServiceSince("", nowMillis, ZoneOffset.UTC))
    }
}
