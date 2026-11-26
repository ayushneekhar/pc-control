package com.neekhar.pccontrol.feature.space

import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

enum class SpaceMode(val label: String) {
    WATCHED("Watched"),
    ALL("All Local"),
    DRIVE("On Drive"),
}

enum class SpaceTab(val label: String) {
    MOVIES("Movies"),
    TV_SHOWS("TV Shows"),
    OFFLOADED("Offloaded"),
}

enum class SpaceFilter(val label: String) {
    ALL("All"),
    MOVIE("Movies"),
    SEASON("TV"),
}

enum class SpaceSort(val label: String) {
    SIZE("Size"),
    TITLE("Name"),
    WATCHED("Watched"),
}

enum class LocalStatusChip(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    UNWATCHED("Unwatched"),
}

data class LocalSelectionSummary(
    val selected: List<SpaceCandidate>,
    val count: Int,
    val totalBytes: Long,
    val unwatched: List<SpaceCandidate>,
)

data class DriveSelectionSummary(
    val selected: List<DriveItem>,
    val count: Int,
    val totalBytes: Long,
)

data class VisibleSelectionState(
    val visibleCount: Int,
    val selectedVisibleCount: Int,
    val allSelected: Boolean,
    val someSelected: Boolean,
)

data class SpaceJobsSseEvent(
    val event: String,
    val jobs: List<TransferJob>,
)

data class SpaceJobsSseParseResult(
    val events: List<SpaceJobsSseEvent>,
    val rest: String,
)

val ACTIVE_JOB_STATUSES: Set<String> = setOf("queued", "copying", "verifying", "cleaning")

private val ACTIVE_STATUS_PRIORITY: Map<String, Int> = mapOf(
    "cleaning" to 4,
    "verifying" to 3,
    "copying" to 2,
    "queued" to 1,
)

private const val RECENT_TERMINAL_WINDOW_SEC: Long = 1800L // 30 minutes
private const val MAX_RECENT_TERMINAL_JOBS: Int = 6

private val SOURCE_LABELS: Map<String, String> = mapOf(
    "radarr" to "Radarr",
    "sonarr" to "Sonarr",
    "jellyfin" to "Jellyfin",
    "qbt" to "qBittorrent",
    "backend" to "Backend",
    "quota" to "Drive quota",
    "movies" to "Drive movies",
    "shows" to "Drive shows",
)

val SpaceJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = false
}

fun filterAndSortCandidates(
    items: List<SpaceCandidate>,
    filter: SpaceFilter,
    sort: SpaceSort,
    query: String = "",
    statusChip: LocalStatusChip = LocalStatusChip.ALL,
): List<SpaceCandidate> {
    val needle = query.trim()
    val filtered = items.filter { item ->
        val matchesKind = when (filter) {
            SpaceFilter.ALL -> true
            SpaceFilter.MOVIE -> item.kind == "movie"
            SpaceFilter.SEASON -> item.kind == "season"
        }
        val matchesStatus = when (statusChip) {
            LocalStatusChip.ALL -> true
            LocalStatusChip.WATCHED -> item.isWatched
            LocalStatusChip.UNWATCHED -> !item.isWatched
        }
        val matchesQuery = needle.isEmpty() ||
            item.title.contains(needle, ignoreCase = true) ||
            (item.subtitle?.contains(needle, ignoreCase = true) == true) ||
            (item.year?.toString()?.contains(needle) == true)
        matchesKind && matchesStatus && matchesQuery
    }

    return filtered.sortedWith { a, b ->
        when (sort) {
            SpaceSort.TITLE -> {
                val cmp = a.title.compareTo(b.title, ignoreCase = true)
                if (cmp != 0) return@sortedWith cmp
                if (a.kind == "season" && b.kind == "season") {
                    return@sortedWith (a.seasonNumber ?: 0).compareTo(b.seasonNumber ?: 0)
                }
                b.sizeBytes.compareTo(a.sizeBytes)
            }
            SpaceSort.WATCHED -> {
                val watchedCmp = b.watchedCount.compareTo(a.watchedCount)
                if (watchedCmp != 0) return@sortedWith watchedCmp
                val sizeCmp = b.sizeBytes.compareTo(a.sizeBytes)
                if (sizeCmp != 0) return@sortedWith sizeCmp
                a.title.compareTo(b.title, ignoreCase = true)
            }
            SpaceSort.SIZE -> {
                val sizeCmp = b.sizeBytes.compareTo(a.sizeBytes)
                if (sizeCmp != 0) return@sortedWith sizeCmp
                a.title.compareTo(b.title, ignoreCase = true)
            }
        }
    }
}

fun filterAndSortDriveItems(
    items: List<DriveItem>,
    filter: SpaceFilter,
    sort: SpaceSort,
    query: String = "",
): List<DriveItem> {
    val needle = query.trim()
    val filtered = items.filter { item ->
        val matchesKind = when (filter) {
            SpaceFilter.ALL -> true
            SpaceFilter.MOVIE -> item.kind == "movie"
            SpaceFilter.SEASON -> item.kind == "season"
        }
        val matchesQuery = needle.isEmpty() ||
            item.title.contains(needle, ignoreCase = true) ||
            (item.subtitle?.contains(needle, ignoreCase = true) == true) ||
            (item.year?.toString()?.contains(needle) == true)
        matchesKind && matchesQuery
    }

    return filtered.sortedWith { a, b ->
        when (sort) {
            SpaceSort.TITLE -> {
                val cmp = a.title.compareTo(b.title, ignoreCase = true)
                if (cmp != 0) return@sortedWith cmp
                if (a.kind == "season" && b.kind == "season") {
                    return@sortedWith (a.seasonNumber ?: 0).compareTo(b.seasonNumber ?: 0)
                }
                b.sizeBytes.compareTo(a.sizeBytes)
            }
            SpaceSort.WATCHED -> {
                val offloadedCmp = (b.offloadedAt ?: 0L).compareTo(a.offloadedAt ?: 0L)
                if (offloadedCmp != 0) return@sortedWith offloadedCmp
                val sizeCmp = b.sizeBytes.compareTo(a.sizeBytes)
                if (sizeCmp != 0) return@sortedWith sizeCmp
                a.title.compareTo(b.title, ignoreCase = true)
            }
            SpaceSort.SIZE -> {
                val sizeCmp = b.sizeBytes.compareTo(a.sizeBytes)
                if (sizeCmp != 0) return@sortedWith sizeCmp
                a.title.compareTo(b.title, ignoreCase = true)
            }
        }
    }
}

fun summarizeSelection(
    items: List<SpaceCandidate>,
    selectedIds: Set<String>,
): LocalSelectionSummary {
    val selected = items.filter { it.id in selectedIds }
    var totalBytes = 0L
    val unwatched = mutableListOf<SpaceCandidate>()
    for (item in selected) {
        totalBytes += item.sizeBytes
        if (item.warnUnwatched && item.unwatchedUsers.isNotEmpty()) {
            unwatched.add(item)
        }
    }
    return LocalSelectionSummary(
        selected = selected,
        count = selected.size,
        totalBytes = totalBytes,
        unwatched = unwatched,
    )
}

fun summarizeDriveSelection(
    items: List<DriveItem>,
    selectedPaths: Set<String>,
): DriveSelectionSummary {
    val selected = items.filter { it.relPath in selectedPaths }
    val totalBytes = selected.sumOf { it.sizeBytes }
    return DriveSelectionSummary(
        selected = selected,
        count = selected.size,
        totalBytes = totalBytes,
    )
}

fun toFreeRequest(items: List<SpaceCandidate>): List<SpaceFreeItemRequest> =
    items.map { item ->
        when {
            item.kind == "movie" && item.radarrId != null ->
                SpaceFreeItemRequest(kind = "movie", radarrId = item.radarrId)
            item.kind == "season" && item.seriesId != null && item.seasonNumber != null ->
                SpaceFreeItemRequest(
                    kind = "season",
                    seriesId = item.seriesId,
                    seasonNumber = item.seasonNumber,
                )
            else -> SpaceFreeItemRequest(id = item.id)
        }
    }

private fun pickHigherPriorityJob(existing: TransferJob?, candidate: TransferJob): TransferJob {
    if (existing == null) return candidate
    val candPrio = ACTIVE_STATUS_PRIORITY[candidate.status] ?: 0
    val existPrio = ACTIVE_STATUS_PRIORITY[existing.status] ?: 0
    return if (candPrio > existPrio) candidate else existing
}

fun activeOffloadJobsByLocalId(
    candidates: List<SpaceCandidate>,
    jobs: List<TransferJob>,
): Map<String, TransferJob> {
    val activeOffloads = jobs.filter { it.op == "offload" && it.status in ACTIVE_JOB_STATUSES }
    if (activeOffloads.isEmpty() || candidates.isEmpty()) return emptyMap()

    val byItemId = HashMap<String, TransferJob>()
    val byRelPath = HashMap<String, TransferJob>()
    for (job in activeOffloads) {
        if (job.itemId.isNotEmpty()) {
            byItemId[job.itemId] = pickHigherPriorityJob(byItemId[job.itemId], job)
        }
        if (job.relPath.isNotEmpty()) {
            byRelPath[job.relPath] = pickHigherPriorityJob(byRelPath[job.relPath], job)
        }
    }

    val result = LinkedHashMap<String, TransferJob>()
    for (item in candidates) {
        var matched = byItemId[item.id] ?: byRelPath[item.id]
        val rp = item.relPath
        if (matched == null && !rp.isNullOrEmpty()) {
            matched = byRelPath[rp] ?: byItemId[rp]
        }
        if (matched != null) {
            result[item.id] = matched
        }
    }
    return result
}

fun activeRestoreJobsByDrivePath(
    items: List<DriveItem>,
    jobs: List<TransferJob>,
): Map<String, TransferJob> {
    val activeRestores = jobs.filter { it.op == "restore" && it.status in ACTIVE_JOB_STATUSES }
    if (activeRestores.isEmpty() || items.isEmpty()) return emptyMap()

    val byItemId = HashMap<String, TransferJob>()
    val byRelPath = HashMap<String, TransferJob>()
    for (job in activeRestores) {
        if (job.itemId.isNotEmpty()) {
            byItemId[job.itemId] = pickHigherPriorityJob(byItemId[job.itemId], job)
        }
        if (job.relPath.isNotEmpty()) {
            byRelPath[job.relPath] = pickHigherPriorityJob(byRelPath[job.relPath], job)
        }
    }

    val result = LinkedHashMap<String, TransferJob>()
    for (item in items) {
        val matched = byRelPath[item.relPath]
            ?: byItemId[item.relPath]
            ?: item.id.takeIf { it.isNotEmpty() }?.let { byItemId[it] ?: byRelPath[it] }
        if (matched != null) {
            result[item.relPath] = matched
        }
    }
    return result
}

fun inFlightBadgeLabel(job: TransferJob): String = when (job.status) {
    "queued" -> "Queued"
    "copying" -> if (job.op == "restore") "Restoring" else "Uploading"
    "verifying" -> "Verifying"
    "cleaning" -> "Finishing"
    else -> transferStatusLabel(job)
}

fun transferStatusLabel(job: TransferJob): String = when (job.status) {
    "queued" -> "Queued"
    "copying" -> if (job.op == "restore") "Restoring" else "Offloading"
    "verifying" -> "Verifying"
    "cleaning" -> "Finishing"
    "done" -> "Done"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    else -> job.status
}

fun pruneDisabledSelection(
    selectedKeys: Set<String>,
    disabledKeys: Set<String>,
): Set<String> {
    if (selectedKeys.isEmpty() || disabledKeys.isEmpty()) return selectedKeys
    val hasOverlap = selectedKeys.any { it in disabledKeys }
    if (!hasOverlap) return selectedKeys
    return selectedKeys.filterTo(LinkedHashSet()) { it !in disabledKeys }
}

fun selectionStateForVisible(
    visibleKeys: List<String>,
    selectedKeys: Set<String>,
    disabledKeys: Set<String> = emptySet(),
): VisibleSelectionState {
    val selectable = if (disabledKeys.isNotEmpty()) {
        visibleKeys.filter { it !in disabledKeys }
    } else {
        visibleKeys
    }
    val visibleCount = selectable.size
    val selectedVisibleCount = selectable.count { it in selectedKeys }
    return VisibleSelectionState(
        visibleCount = visibleCount,
        selectedVisibleCount = selectedVisibleCount,
        allSelected = visibleCount > 0 && selectedVisibleCount == visibleCount,
        someSelected = selectedVisibleCount > 0 && selectedVisibleCount < visibleCount,
    )
}

fun toggleSelectAllVisible(
    visibleKeys: List<String>,
    selectedKeys: Set<String>,
    disabledKeys: Set<String> = emptySet(),
): Set<String> {
    val next = LinkedHashSet(selectedKeys)
    if (disabledKeys.isNotEmpty()) {
        next.removeAll(disabledKeys)
    }
    val selectable = if (disabledKeys.isNotEmpty()) {
        visibleKeys.filter { it !in disabledKeys }
    } else {
        visibleKeys
    }
    if (selectable.isEmpty()) return next
    val allSelected = selectable.all { it in next }
    if (allSelected) {
        next.removeAll(selectable.toSet())
    } else {
        next.addAll(selectable)
    }
    return next
}

fun mergeEnqueuedJobs(
    existing: List<TransferJob>,
    created: List<TransferJob>,
): List<TransferJob> {
    if (created.isEmpty()) return existing
    val byId = LinkedHashMap<String, TransferJob>()
    for (job in existing) byId[job.id] = job
    for (job in created) byId[job.id] = job
    return byId.values.toList()
}

fun activeTransferJobs(jobs: List<TransferJob>): List<TransferJob> =
    jobs.filter { it.status in ACTIVE_JOB_STATUSES }

fun visibleDownloadDriveJobs(
    jobs: List<TransferJob>,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): List<TransferJob> {
    val active = mutableListOf<TransferJob>()
    val terminal = mutableListOf<TransferJob>()
    for (job in jobs) {
        if (job.status in ACTIVE_JOB_STATUSES) {
            active.add(job)
        } else if (
            job.status == "failed" ||
            nowSec - (job.finishedAt ?: job.createdAt) <= RECENT_TERMINAL_WINDOW_SEC
        ) {
            terminal.add(job)
        }
    }
    terminal.sortByDescending { it.finishedAt ?: it.createdAt }
    return active + terminal.take(MAX_RECENT_TERMINAL_JOBS)
}

fun parseSpaceJobsSseBlocks(
    buffer: String,
    json: Json = SpaceJson,
): SpaceJobsSseParseResult {
    val events = mutableListOf<SpaceJobsSseEvent>()
    var buf = buffer
    while (true) {
        val idx = buf.indexOf("\n\n")
        if (idx < 0) break
        val block = buf.substring(0, idx)
        buf = buf.substring(idx + 2)
        var event = "message"
        val dataLines = mutableListOf<String>()
        for (rawLine in block.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.startsWith(':')) continue
            when {
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    val value = line.substring(5)
                    dataLines.add(if (value.startsWith(" ")) value.substring(1) else value)
                }
            }
        }
        if (dataLines.isEmpty()) continue
        runCatching {
            val payload = json.decodeFromString<SpaceJobsSsePayload>(dataLines.joinToString("\n"))
            val parsedJobs = payload.jobs
            if (parsedJobs != null) {
                events.add(SpaceJobsSseEvent(event = event, jobs = parsedJobs))
            }
        }
    }
    return SpaceJobsSseParseResult(events = events, rest = buf)
}

fun formatUpstreamErrors(errors: Map<String, String>?): String? {
    if (errors.isNullOrEmpty()) return null
    val names = errors.keys
        .filter { it != "hdd" }
        .map { SOURCE_LABELS[it] ?: it }
    if (names.isEmpty()) return null
    return "${names.joinToString(", ")} unavailable"
}

fun formatBytes(bytes: Long?, digits: Int = 1): String {
    if (bytes == null) return "–"
    val negative = bytes < 0L
    var value = if (negative) (-bytes).toDouble() else bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    val formatted = if (idx == 0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.${digits}f", value)
    }
    return "${if (negative) "-" else ""}$formatted ${units[idx]}"
}

fun formatRate(bytesPerSec: Long?): String {
    if (bytesPerSec == null) return "–"
    return "${formatBytes(bytesPerSec)}/s"
}

fun formatDuration(seconds: Long): String {
    val secs = max(0L, seconds)
    val d = floor(secs / 86400.0).toLong()
    val h = floor((secs % 86400L) / 3600.0).toLong()
    val m = floor((secs % 3600L) / 60.0).toLong()
    return when {
        d > 0L -> "${d}d ${h}h"
        h > 0L -> "${h}h ${m}m"
        m > 0L -> "${m}m"
        else -> "${secs}s"
    }
}

private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w185"

/**
 * Resolves an optional poster URL or TMDB relative path (e.g. "/abc.jpg") into a loadable HTTP(S) URL.
 */
fun resolveSpacePosterUrl(
    poster: String? = null,
    posterUrl: String? = null,
    posterPath: String? = null,
): String? {
    val raw = listOf(poster, posterUrl, posterPath)
        .firstNotNullOfOrNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?: return null
    return when {
        raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
        raw.startsWith("/") -> "$TMDB_POSTER_BASE$raw"
        else -> null
    }
}
