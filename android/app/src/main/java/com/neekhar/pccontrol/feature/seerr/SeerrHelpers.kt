package com.neekhar.pccontrol.feature.seerr

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class SeerrBadgeTone {
    OK,
    ACCENT,
    WARN,
    BAD,
    DIM,
    FAINT,
}

data class SeerrStatusBadge(
    val label: String,
    val tone: SeerrBadgeTone,
)

object SeerrHelpers {

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private val ISO_DATE_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

    fun isRequestableMovie(status: SeerrStatus): Boolean {
        return status == SeerrStatus.NONE || status == SeerrStatus.DELETED
    }

    fun isRequestableSeason(status: SeerrStatus): Boolean {
        return status == SeerrStatus.NONE || status == SeerrStatus.DELETED
    }

    fun statusBadge(status: SeerrStatus): SeerrStatusBadge? {
        return when (status) {
            SeerrStatus.AVAILABLE -> SeerrStatusBadge("Available", SeerrBadgeTone.OK)
            SeerrStatus.GRABBING -> SeerrStatusBadge("Grabbing", SeerrBadgeTone.ACCENT)
            SeerrStatus.PARTIAL -> SeerrStatusBadge("Partial", SeerrBadgeTone.WARN)
            SeerrStatus.PENDING -> SeerrStatusBadge("Pending", SeerrBadgeTone.WARN)
            SeerrStatus.REQUESTED -> SeerrStatusBadge("Processing", SeerrBadgeTone.DIM)
            SeerrStatus.DELETED -> SeerrStatusBadge("Deleted", SeerrBadgeTone.FAINT)
            SeerrStatus.BLOCKLISTED -> SeerrStatusBadge("Blocklisted", SeerrBadgeTone.WARN)
            SeerrStatus.FAILED -> SeerrStatusBadge("Failed", SeerrBadgeTone.BAD)
            SeerrStatus.DECLINED -> SeerrStatusBadge("Declined", SeerrBadgeTone.BAD)
            SeerrStatus.NONE -> null
        }
    }

    fun formatRuntime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        if (h <= 0) return "${m}m"
        if (m == 0) return "${h}h"
        return "${h}h ${m}m"
    }

    fun formatDate(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val match = ISO_DATE_REGEX.find(iso.trim()) ?: return iso
        val year = match.groupValues[1].toIntOrNull() ?: return iso
        val monthIdx = (match.groupValues[2].toIntOrNull() ?: return iso) - 1
        val day = match.groupValues[3].toIntOrNull() ?: return iso
        if (monthIdx !in 0..11 || day !in 1..31) return iso
        return "${MONTHS[monthIdx]} $day, $year"
    }

    fun computeAge(
        birthday: String?,
        deathday: String? = null,
    ): Int? {
        val cal = Calendar.getInstance()
        return computeAge(
            birthday = birthday,
            deathday = deathday,
            nowYear = cal.get(Calendar.YEAR),
            nowMonth = cal.get(Calendar.MONTH) + 1,
            nowDay = cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun computeAge(
        birthday: String?,
        deathday: String?,
        nowYear: Int,
        nowMonth: Int,
        nowDay: Int,
    ): Int? {
        if (birthday.isNullOrBlank()) return null
        val bm = ISO_DATE_REGEX.find(birthday.trim()) ?: return null
        val by = bm.groupValues[1].toIntOrNull() ?: return null
        val bmo = bm.groupValues[2].toIntOrNull() ?: return null
        val bd = bm.groupValues[3].toIntOrNull() ?: return null

        val ey: Int
        val emo: Int
        val ed: Int
        if (!deathday.isNullOrBlank()) {
            val dm = ISO_DATE_REGEX.find(deathday.trim()) ?: return null
            ey = dm.groupValues[1].toIntOrNull() ?: return null
            emo = dm.groupValues[2].toIntOrNull() ?: return null
            ed = dm.groupValues[3].toIntOrNull() ?: return null
        } else {
            ey = nowYear
            emo = nowMonth
            ed = nowDay
        }

        var age = ey - by
        if (emo < bmo || (emo == bmo && ed < bd)) {
            age -= 1
        }
        return if (age in 0..149) age else null
    }

    fun formatMoney(amount: Long?): String? {
        if (amount == null || amount <= 0L) return null
        return when {
            amount >= 1_000_000_000L -> {
                String.format(Locale.US, "$%.2fB", amount / 1_000_000_000.0)
            }
            amount >= 1_000_000L -> {
                if (amount >= 100_000_000L) {
                    String.format(Locale.US, "$%.0fM", amount / 1_000_000.0)
                } else {
                    String.format(Locale.US, "$%.1fM", amount / 1_000_000.0)
                }
            }
            amount >= 1_000L -> {
                "$${(amount / 1_000.0).roundToLong()}K"
            }
            else -> "$$amount"
        }
    }

    fun formatCompactCount(n: Int?): String? {
        if (n == null || n <= 0) return null
        return when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> {
                if (n >= 10_000) {
                    String.format(Locale.US, "%.0fk", n / 1_000.0)
                } else {
                    String.format(Locale.US, "%.1fk", n / 1_000.0)
                }
            }
            else -> n.toString()
        }
    }

    fun collectionMissingParts(collection: SeerrCollection?): List<SeerrItem> {
        return collection?.parts.orEmpty().filter { isRequestableMovie(it.status) }
    }

    fun collectionAvailableCount(collection: SeerrCollection?): Int {
        return collection?.parts.orEmpty().count { it.status == SeerrStatus.AVAILABLE }
    }

    fun collectionSummary(collection: SeerrCollection?): String {
        val parts = collection?.parts.orEmpty()
        if (parts.isEmpty()) return ""
        val avail = parts.count { it.status == SeerrStatus.AVAILABLE }
        return if (avail > 0) {
            "$avail of ${parts.size} available"
        } else {
            "${parts.size} ${if (parts.size == 1) "movie" else "movies"}"
        }
    }

    fun visibleRequestFilterTabs(counts: Map<String, Int>?): List<SeerrRequestFilter> {
        val tabs = SeerrRequestFilter.BASE_TABS.toMutableList()
        if ((counts?.get("declined") ?: 0) > 0) {
            tabs.add(SeerrRequestFilter.DECLINED)
        }
        if ((counts?.get("blocklisted") ?: 0) > 0) {
            tabs.add(SeerrRequestFilter.BLOCKLISTED)
        }
        return tabs
    }

    fun filterSearchResults(
        results: List<SeerrItem>,
        mediaFilter: SeerrSearchMediaFilter,
    ): List<SeerrItem> {
        return when (mediaFilter) {
            SeerrSearchMediaFilter.ALL -> results
            SeerrSearchMediaFilter.MOVIE -> results.filter { it.isMovie }
            SeerrSearchMediaFilter.TV -> results.filter { it.isTv }
            SeerrSearchMediaFilter.COLLECTION -> results.filter { it.isCollection }
        }
    }

    fun filterAndSortPersonCredits(
        credits: List<SeerrPersonCredit>,
        mediaFilter: SeerrPersonMediaFilter,
        sortMode: SeerrPersonSortMode,
    ): List<SeerrPersonCredit> {
        val filtered = when (mediaFilter) {
            SeerrPersonMediaFilter.ALL -> credits
            SeerrPersonMediaFilter.MOVIE -> credits.filter { it.mediaType == "movie" }
            SeerrPersonMediaFilter.TV -> credits.filter { it.mediaType == "tv" }
        }
        if (sortMode == SeerrPersonSortMode.POPULAR) {
            return filtered
        }
        return filtered.sortedWith(
            compareByDescending<SeerrPersonCredit> { it.releaseDate.orEmpty() }
                .thenByDescending { it.popularity },
        )
    }

    fun formatSeasonTag(mediaType: String, seasons: List<Int>): String {
        return when {
            mediaType == "tv" && seasons.isNotEmpty() -> seasons.joinToString(", ") { "S$it" }
            mediaType == "tv" -> "TV"
            else -> "Movie"
        }
    }

    fun formatIsoAge(iso: String?, nowEpochMs: Long = System.currentTimeMillis()): String {
        val epochMs = parseIsoTimestampMs(iso) ?: return ""
        val diffSec = ((nowEpochMs - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> "${diffSec}s ago"
            diffSec < 3600L -> "${diffSec / 60L}m ago"
            diffSec < 86_400L -> "${diffSec / 3600L}h ago"
            diffSec < 86_400L * 30L -> "${diffSec / 86_400L}d ago"
            diffSec < 86_400L * 365L -> "${(diffSec / (86_400.0 * 30.0)).roundToInt().coerceAtLeast(1)}mo ago"
            else -> "${(diffSec / (86_400.0 * 365.0)).roundToInt().coerceAtLeast(1)}y ago"
        }
    }

    private fun parseIsoTimestampMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val trimmed = iso.trim()
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // Try next format
            }
        }
        return null
    }

    fun formatVoteAverage(voteAverage: Double?): String? {
        if (voteAverage == null || voteAverage <= 0.0 || abs(voteAverage) > 100.0) return null
        return String.format(Locale.US, "%.1f", voteAverage)
    }

    fun watchlistKey(mediaType: String, tmdbId: Int): String {
        val mt = if (mediaType.trim().lowercase() == "tv") "tv" else "movie"
        return "$mt:$tmdbId"
    }

    fun formatRemainingMinutes(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h <= 0 -> "${m}m left"
            m == 0 -> "${h}h left"
            else -> "${h}h ${m}m left"
        }
    }

    fun formatDownloadBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
            else -> "${bytes / 1024L} KB"
        }
    }

    fun pendingRequestsBadgeCount(counts: Map<String, Int>?): Int {
        if (counts == null) return 0
        val pending = counts["pending"] ?: 0
        val processing = counts["processing"] ?: 0
        val grabbing = counts["grabbing"] ?: 0
        return (pending + processing).coerceAtLeast(pending + grabbing)
    }

    const val RESUME_THRESHOLD_TICKS: Long = 10L * 10_000_000L

    /**
     * Formats a Jellyfin playback position in ticks (`10_000_000` ticks = 1 sec) as
     * `M:SS` or `H:MM:SS` when `positionTicks > 10s`, otherwise returns `null`.
     */
    fun formatResumeTimestamp(positionTicks: Long): String? {
        if (positionTicks <= RESUME_THRESHOLD_TICKS) return null
        val totalSeconds = positionTicks / 10_000_000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Builds the primary Play/Resume button label:
     * - `Resume from 14:22` (or `Resume S1 · E3 from 14:22` when [episodeLabel] is provided)
     * - `Play offline` when downloaded and not resuming
     * - `Play` otherwise
     */
    fun formatPlayButtonLabel(
        resumePositionTicks: Long,
        isOffline: Boolean = false,
        episodeLabel: String? = null,
    ): String {
        val ts = formatResumeTimestamp(resumePositionTicks)
        if (ts != null) {
            val ep = episodeLabel?.trim()?.takeIf { it.isNotEmpty() }
            return if (ep != null) "Resume $ep from $ts" else "Resume from $ts"
        }
        return if (isOffline) "Play offline" else "Play"
    }

    fun defaultEpisodeSeason(seasons: List<SeerrSeason>): Int? {
        if (seasons.isEmpty()) return null
        val regular = seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
        val pool = regular.ifEmpty { seasons.sortedBy { it.seasonNumber } }
        return pool.firstOrNull {
            it.status == SeerrStatus.AVAILABLE || it.status == SeerrStatus.PARTIAL
        }?.seasonNumber ?: pool.first().seasonNumber
    }

    fun mediaBadgePills(badges: WatchMediaBadges?): List<String> {
        if (badges == null) return emptyList()
        return buildList {
            badges.resolution?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            badges.videoCodec?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            badges.hdr?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            badges.audioCodec?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            val sizeText = badges.sizeLabel?.trim()?.takeIf { it.isNotEmpty() }
                ?: badges.sizeBytes?.takeIf { it > 0L }?.let { formatDownloadBytes(it) }
            if (!sizeText.isNullOrBlank()) add(sizeText)
        }
    }

    fun boxSetCountLabel(count: Int): String {
        val safe = count.coerceAtLeast(0)
        return if (safe == 1) "1 movie" else "$safe movies"
    }
}
