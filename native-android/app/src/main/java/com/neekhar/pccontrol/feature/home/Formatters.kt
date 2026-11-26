package com.neekhar.pccontrol.feature.home

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

object PcFormatters {
    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)
    private val SYSTEMD_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val LEADING_WEEKDAY = Regex("^[A-Za-z]+\\s+")
    private val TRAILING_TZ = Regex("\\s+[A-Z]{2,5}$")
    private val HOST_PREFIX = Regex("^ayushlive-", RegexOption.IGNORE_CASE)

    fun formatBytes(n: Double?, digits: Int = 1): String {
        if (n == null || !n.isFinite()) return "–"
        var value = n
        var idx = 0
        while (value >= 1024.0 && idx < BYTE_UNITS.lastIndex) {
            value /= 1024.0
            idx++
        }
        val d = if (idx == 0) 0 else digits
        return String.format(Locale.US, "%.${d}f %s", value, BYTE_UNITS[idx])
    }

    fun formatBytes(n: Long?, digits: Int = 1): String = formatBytes(n?.toDouble(), digits)

    fun formatRate(bytesPerSec: Double?): String =
        if (bytesPerSec == null || !bytesPerSec.isFinite()) "–" else "${formatBytes(bytesPerSec)}/s"

    fun formatRate(bytesPerSec: Long?): String = formatRate(bytesPerSec?.toDouble())

    fun formatDuration(secs: Long): String {
        val clamped = max(0L, secs)
        val d = clamped / 86_400L
        val h = (clamped % 86_400L) / 3_600L
        val m = (clamped % 3_600L) / 60L
        return when {
            d > 0L -> "${d}d ${h}h"
            h > 0L -> "${h}h ${m}m"
            m > 0L -> "${m}m"
            else -> "${clamped}s"
        }
    }

    fun formatDuration(secs: Double): String = formatDuration(floor(max(0.0, secs)).toLong())

    fun formatUptimeShort(secs: Double): String {
        val clamped = max(0L, floor(secs).toLong())
        val d = clamped / 86_400L
        if (d > 0L) return if (d == 1L) "1 day" else "$d days"
        val h = (clamped % 86_400L) / 3_600L
        if (h > 0L) return "${h} h"
        val m = (clamped % 3_600L) / 60L
        return "$m min"
    }

    fun formatUptimeFor(secs: Double): String {
        val clamped = max(0L, floor(secs).toLong())
        val d = clamped / 86_400L
        val h = (clamped % 86_400L) / 3_600L
        val m = (clamped % 3_600L) / 60L
        return if (d > 0L) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
    }

    fun formatSeenAgo(tsSeconds: Double, nowSeconds: Double = System.currentTimeMillis() / 1000.0): String {
        val delta = nowSeconds - tsSeconds
        if (delta < 60.0) return "just now"
        val mins = floor(delta / 60.0).toLong()
        if (mins < 60L) return "$mins min ago"
        val hours = mins / 60L
        if (hours < 24L) return "$hours h ago"
        val days = hours / 24L
        return if (days == 1L) "yesterday" else "$days days ago"
    }

    fun formatShortHost(host: String): String =
        HOST_PREFIX.replace(host.trim(), "").lowercase(Locale.US)

    fun formatClock(
        tsSeconds: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val target = ZonedDateTime.ofInstant(Instant.ofEpochSecond(tsSeconds), zoneId)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        return if (target.toLocalDate() == today.toLocalDate()) {
            target.format(TIME_FMT)
        } else {
            target.format(DATE_TIME_FMT)
        }
    }

    /**
     * Parses systemd `ActiveEnterTimestamp` strings like `"Thu 2026-11-26 10:15:00 IST"` or ISO-8601
     * and returns compact elapsed time (`"5m"`, `"3h"`, `"10d"`).
     */
    fun formatServiceSince(
        raw: String,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val epochMillis = parseSinceMillis(trimmed, zoneId) ?: return trimmed
        val secs = (nowMillis - epochMillis) / 1000.0
        if (secs < 0.0) return ""
        return when {
            secs < 3600.0 -> "${max(1L, (secs / 60.0).roundToLong())}m"
            secs < 86400.0 -> "${(secs / 3600.0).roundToLong()}h"
            else -> "${(secs / 86400.0).roundToLong()}d"
        }
    }

    private fun parseSinceMillis(trimmed: String, zoneId: ZoneId): Long? {
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        val cleaned = TRAILING_TZ.replace(LEADING_WEEKDAY.replace(trimmed, ""), "").trim()
        return runCatching {
            LocalDateTime.parse(cleaned, SYSTEMD_TS_FMT).atZone(zoneId).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
