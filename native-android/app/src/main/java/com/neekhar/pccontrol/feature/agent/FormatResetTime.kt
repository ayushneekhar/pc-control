package com.neekhar.pccontrol.feature.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Pure date & duration formatters for AI usage reset countdowns,
 * ported from src/lib/formatResetTime.ts.
 */
object FormatResetTime {
    private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DURATION_PART_RE = Regex("""(\d+)\s*([dhms])""", RegexOption.IGNORE_CASE)

    private fun ordinal(day: Int): String {
        val mod100 = day % 100
        if (mod100 in 11..13) return "${day}th"
        return when (day % 10) {
            1 -> "${day}st"
            2 -> "${day}nd"
            3 -> "${day}rd"
            else -> "${day}th"
        }
    }

    private fun formatClock12h(zdt: ZonedDateTime): String {
        val h24 = zdt.hour
        val mins = zdt.minute.toString().padStart(2, '0')
        val ampm = if (h24 >= 12) "pm" else "am"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "$h12:$mins $ampm"
    }

    fun parseIsoInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso.trim())
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso.trim()).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Format an ISO 8601 UTC reset timestamp in the given timezone:
     * - Under 1 hour: "Resets in 42 min"
     * - Today: "Resets today, 12:20 pm"
     * - Tomorrow: "Resets tomorrow, 9:05 am"
     * - Later: "Resets Sun 29th Nov, 6:30 am"
     */
    fun formatResetTime(
        resetsAt: String?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val target = parseIsoInstant(resetsAt) ?: return null
        val diffMs = target.toEpochMilli() - now.toEpochMilli()
        if (diffMs <= 0L) return "Resets now"
        if (diffMs < 60L * 60L * 1000L) {
            val mins = max(1L, ceil(diffMs / 60_000.0).toLong())
            return "Resets in $mins min"
        }

        val targetZdt = target.atZone(zone)
        val nowZdt = now.atZone(zone)
        val timeStr = formatClock12h(targetZdt)
        val dayDiff = ChronoUnit.DAYS.between(nowZdt.toLocalDate(), targetZdt.toLocalDate())
        if (dayDiff == 0L) return "Resets today, $timeStr"
        if (dayDiff == 1L) return "Resets tomorrow, $timeStr"

        // java.time DayOfWeek: MONDAY=1..SUNDAY=7 -> Sunday=0..Saturday=6
        val wdIdx = targetZdt.dayOfWeek.value % 7
        val wd = WEEKDAYS[wdIdx]
        val dayOrd = ordinal(targetZdt.dayOfMonth)
        val mon = MONTHS[targetZdt.monthValue - 1]
        return "Resets $wd $dayOrd $mon, $timeStr"
    }

    /**
     * Parse a compact duration string such as "155h 9m", "4d 10h", "2h 38m", or "45m" into milliseconds.
     */
    fun parseDurationMs(dur: String?): Long? {
        if (dur.isNullOrBlank()) return null
        var totalSec = 0L
        var matched = false
        for (m in DURATION_PART_RE.findAll(dur)) {
            matched = true
            val n = m.groupValues[1].toLongOrNull() ?: continue
            when (m.groupValues[2].lowercase()) {
                "d" -> totalSec += n * 86_400L
                "h" -> totalSec += n * 3_600L
                "m" -> totalSec += n * 60L
                "s" -> totalSec += n
            }
        }
        if (!matched || totalSec <= 0L) return null
        return totalSec * 1000L
    }

    /**
     * Format a positive duration in milliseconds into a compact string:
     * - >= 24h: "6d 11h" (or "2d" when exact days)
     * - 1h..24h: "2h 38m" (or "4h" when exact hours)
     * - < 1h: "42m" (minimum "1m")
     */
    fun formatCompactDuration(ms: Double): String? {
        if (!ms.isFinite() || ms <= 0.0) return null
        val totalMins = max(1L, floor(ms / 60_000.0).toLong())
        val days = totalMins / (24L * 60L)
        val hours = (totalMins % (24L * 60L)) / 60L
        val mins = totalMins % 60L
        if (days > 0L) {
            return if (hours > 0L) "${days}d ${hours}h" else "${days}d"
        }
        if (hours > 0L) {
            return if (mins > 0L) "${hours}h ${mins}m" else "${hours}h"
        }
        return "${mins}m"
    }

    fun formatCompactDuration(ms: Long): String? = formatCompactDuration(ms.toDouble())

    /**
     * Format the remaining time until an ISO 8601 UTC reset timestamp as a compact duration ("6d 11h", "2h 44m", "18m").
     */
    fun formatCompactResetDuration(
        resetsAt: String?,
        now: Instant = Instant.now(),
    ): String? {
        val target = parseIsoInstant(resetsAt) ?: return null
        return formatCompactDuration(target.toEpochMilli() - now.toEpochMilli())
    }
}
