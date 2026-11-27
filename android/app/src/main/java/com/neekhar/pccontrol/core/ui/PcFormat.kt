package com.neekhar.pccontrol.core.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared display formatters for PC Control screens.
 *
 * Keeps metric formatting consistent across Home, Vitals, Space, Downloads, and Settings:
 * - Memory: `6.7 / 15 GB`
 * - Storage: `837.6 GB`
 * - Temperature: `64°C`
 * - Short hostname: `hp-pavilion`
 */
object PcFormat {
    const val PLACEHOLDER = "–"

    private const val KIB = 1024.0
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private val STORAGE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
    private val HOST_USER_PREFIX = Regex("^ayushlive-", RegexOption.IGNORE_CASE)

    /**
     * Formats used/total memory bytes as `"6.7 / 15 GB"`.
     */
    fun memory(usedBytes: Double?, totalBytes: Double?): String {
        if (usedBytes == null || totalBytes == null) return PLACEHOLDER
        if (!usedBytes.isFinite() || !totalBytes.isFinite() || totalBytes <= 0.0) return PLACEHOLDER
        return memoryGb(usedBytes / GIB, totalBytes / GIB)
    }

    /**
     * Formats used/total memory bytes as `"6.7 / 15 GB"`.
     */
    fun memory(usedBytes: Long?, totalBytes: Long?): String =
        memory(usedBytes?.toDouble(), totalBytes?.toDouble())

    /**
     * Formats used/total memory reported in MiB (e.g. GPU VRAM) as `"2.4 / 6 GB"`.
     */
    fun memoryFromMiB(usedMiB: Double?, totalMiB: Double?): String {
        if (usedMiB == null || totalMiB == null) return PLACEHOLDER
        if (!usedMiB.isFinite() || !totalMiB.isFinite() || totalMiB <= 0.0) return PLACEHOLDER
        return memoryGb(usedMiB / KIB, totalMiB / KIB)
    }

    /**
     * Formats used/total memory already in GiB as `"6.7 / 15 GB"`.
     */
    fun memoryGb(usedGb: Double?, totalGb: Double?): String {
        val (value, unit) = memoryPartsGb(usedGb, totalGb)
        return if (unit != null) "$value $unit" else value
    }

    /**
     * Splits formatted memory into `(value, unit)` pairs such as `("6.7", "/ 15 GB")`
     * for two-tone metric tiles and stat strips.
     */
    fun memoryParts(usedBytes: Double?, totalBytes: Double?): Pair<String, String?> {
        if (usedBytes == null || totalBytes == null) return PLACEHOLDER to null
        if (!usedBytes.isFinite() || !totalBytes.isFinite() || totalBytes <= 0.0) return PLACEHOLDER to null
        return memoryPartsGb(usedBytes / GIB, totalBytes / GIB)
    }

    /**
     * Splits MiB memory readings into `(value, unit)` pairs such as `("2.4", "/ 6 GB")`.
     */
    fun memoryPartsFromMiB(usedMiB: Double?, totalMiB: Double?): Pair<String, String?> {
        if (usedMiB == null || totalMiB == null) return PLACEHOLDER to null
        if (!usedMiB.isFinite() || !totalMiB.isFinite() || totalMiB <= 0.0) return PLACEHOLDER to null
        return memoryPartsGb(usedMiB / KIB, totalMiB / KIB)
    }

    private fun memoryPartsGb(usedGb: Double?, totalGb: Double?): Pair<String, String?> {
        if (usedGb == null || totalGb == null) return PLACEHOLDER to null
        if (!usedGb.isFinite() || !totalGb.isFinite() || usedGb < 0.0 || totalGb <= 0.0) {
            return PLACEHOLDER to null
        }
        val usedStr = String.format(Locale.US, "%.1f", usedGb)
        val totalStr = if (totalGb >= 1.0) {
            String.format(Locale.US, "%.0f", totalGb)
        } else {
            String.format(Locale.US, "%.1f", totalGb)
        }
        return usedStr to "/ $totalStr GB"
    }

    /**
     * Formats a byte count into a human-readable storage string such as `"837.6 GB"`.
     */
    fun storage(bytes: Double?, digits: Int = 1): String {
        if (bytes == null || !bytes.isFinite()) return PLACEHOLDER
        val negative = bytes < 0.0
        var value = abs(bytes)
        var idx = 0
        while (value >= KIB && idx < STORAGE_UNITS.lastIndex) {
            value /= KIB
            idx++
        }
        val safeDigits = if (idx == 0) 0 else digits.coerceAtLeast(0)
        val formatted = String.format(Locale.US, "%.${safeDigits}f", value)
        return "${if (negative) "-" else ""}$formatted ${STORAGE_UNITS[idx]}"
    }

    /**
     * Formats a byte count into a human-readable storage string such as `"837.6 GB"`.
     */
    fun storage(bytes: Long?, digits: Int = 1): String = storage(bytes?.toDouble(), digits)

    /**
     * Formats a Celsius temperature reading consistently with `°C` (e.g. `"64°C"`).
     */
    fun temperature(celsius: Double?): String {
        if (celsius == null || !celsius.isFinite()) return PLACEHOLDER
        return "${celsius.roundToInt()}°C"
    }

    /**
     * Formats a Celsius temperature reading consistently with `°C` (e.g. `"64°C"`).
     */
    fun temperature(celsius: Float?): String = temperature(celsius?.toDouble())

    /**
     * Formats an integer Celsius temperature reading consistently with `°C` (e.g. `"64°C"`).
     */
    fun temperature(celsius: Int?): String = if (celsius == null) PLACEHOLDER else "${celsius}°C"

    /**
     * Normalizes a raw machine hostname (e.g. `"ayushlive-hp-pavilion.tail1234.ts.net"`) into a
     * concise lowercase short hostname (`"hp-pavilion"`).
     */
    fun shortHostname(host: String?, fallback: String = ""): String {
        val raw = host?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        val withoutDomain = raw.substringBefore('.')
        val stripped = HOST_USER_PREFIX.replace(withoutDomain, "").lowercase(Locale.US).trim()
        return stripped.ifEmpty { fallback }
    }
}
