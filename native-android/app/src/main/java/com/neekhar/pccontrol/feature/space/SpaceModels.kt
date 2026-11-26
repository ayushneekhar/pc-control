package com.neekhar.pccontrol.feature.space

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpaceHdd(
    val mount: String = "",
    val mounted: Boolean = false,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("used_bytes") val usedBytes: Long? = null,
    @SerialName("free_bytes") val freeBytes: Long? = null,
    @SerialName("pending_bytes") val pendingBytes: Long = 0L,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("pending_unknown") val pendingUnknown: Int = 0,
    @SerialName("predicted_free_bytes") val predictedFreeBytes: Long? = null,
    @SerialName("threshold_bytes") val thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES,
) {
    companion object {
        const val DEFAULT_THRESHOLD_BYTES: Long = 150L * 1024L * 1024L * 1024L
    }
}

enum class SpaceScope(val apiValue: String) {
    WATCHED("watched"),
    ALL("all"),
}

@Serializable
data class SpaceCandidate(
    val id: String,
    val kind: String,
    @SerialName("rel_path") val relPath: String? = null,
    @SerialName("radarr_id") val radarrId: Int? = null,
    @SerialName("series_id") val seriesId: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    val title: String,
    val subtitle: String? = null,
    val year: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val watched: Boolean? = null,
    @SerialName("watched_by") val watchedBy: List<String> = emptyList(),
    @SerialName("watched_count") val watchedCount: Int = 0,
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("watched_users") val watchedUsers: List<String> = emptyList(),
    @SerialName("unwatched_users") val unwatchedUsers: List<String> = emptyList(),
    @SerialName("warn_unwatched") val warnUnwatched: Boolean = false,
    val monitored: Boolean = false,
    val poster: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val isMovie: Boolean get() = kind == "movie"
    val isSeason: Boolean get() = kind == "season"
    val isWatched: Boolean get() = watched ?: (watchedCount > 0)
    val resolvedPosterUrl: String? get() = resolveSpacePosterUrl(poster, posterUrl, posterPath)
}

@Serializable
data class SpaceResponse(
    val hdd: SpaceHdd = SpaceHdd(),
    val candidates: List<SpaceCandidate> = emptyList(),
    val scope: String? = null,
    val errors: Map<String, String> = emptyMap(),
    val ts: Long = 0L,
)

@Serializable
data class SpaceFreeItemRequest(
    val id: String? = null,
    val kind: String? = null,
    @SerialName("radarr_id") val radarrId: Int? = null,
    @SerialName("series_id") val seriesId: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
)

@Serializable
data class SpaceItemsRequestBody(
    val items: List<SpaceFreeItemRequest>,
)

@Serializable
data class SpaceFreeItemResult(
    val id: String,
    val kind: String = "movie",
    val label: String = "",
    val ok: Boolean = false,
    @SerialName("freed_bytes") val freedBytes: Long = 0L,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SpaceFreeResponse(
    val ok: Boolean = false,
    @SerialName("freed_bytes") val freedBytes: Long = 0L,
    val results: List<SpaceFreeItemResult> = emptyList(),
    val warnings: List<String> = emptyList(),
    val hdd: SpaceHdd? = null,
)

@Serializable
data class DriveQuota(
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("used_bytes") val usedBytes: Long? = null,
    @SerialName("free_bytes") val freeBytes: Long? = null,
    @SerialName("trashed_bytes") val trashedBytes: Long? = null,
)

@Serializable
data class DriveItem(
    val id: String,
    val kind: String,
    @SerialName("rel_path") val relPath: String,
    val title: String,
    val subtitle: String? = null,
    val year: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("radarr_id") val radarrId: Int? = null,
    @SerialName("series_id") val seriesId: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("offloaded_at") val offloadedAt: Long? = null,
    val poster: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val isMovie: Boolean get() = kind == "movie"
    val isSeason: Boolean get() = kind == "season"
    val resolvedPosterUrl: String? get() = resolveSpacePosterUrl(poster, posterUrl, posterPath)
}

@Serializable
data class TransferJob(
    val id: String,
    val op: String,
    @SerialName("item_id") val itemId: String = "",
    val kind: String = "movie",
    val title: String = "",
    val subtitle: String? = null,
    @SerialName("rel_path") val relPath: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val status: String,
    @SerialName("bytes_done") val bytesDone: Long = 0L,
    @SerialName("bytes_total") val bytesTotal: Long = 0L,
    val pct: Double = 0.0,
    @SerialName("speed_bps") val speedBps: Long = 0L,
    @SerialName("eta_s") val etaS: Long? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
) {
    val isOffload: Boolean get() = op == "offload"
    val isRestore: Boolean get() = op == "restore"
    val isActive: Boolean get() = status in ACTIVE_JOB_STATUSES
    val canCancel: Boolean get() = isActive && status != "cleaning"
}

@Serializable
data class DriveStatusResponse(
    val configured: Boolean = false,
    val mounted: Boolean = false,
    @SerialName("mount_point") val mountPoint: String = "",
    val quota: DriveQuota? = null,
    val items: List<DriveItem> = emptyList(),
    val jobs: List<TransferJob> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val ts: Long = 0L,
)

@Serializable
data class SpaceJobEnqueueError(
    val id: String = "",
    val error: String = "",
)

@Serializable
data class SpaceJobEnqueueResponse(
    val ok: Boolean = true,
    val jobs: List<TransferJob> = emptyList(),
    val errors: List<SpaceJobEnqueueError> = emptyList(),
)

@Serializable
data class DriveRelPathRequest(
    @SerialName("rel_path") val relPath: String,
)

@Serializable
data class DriveRelPathItemsBody(
    val items: List<DriveRelPathRequest>,
)

@Serializable
data class DriveDeleteItemResult(
    @SerialName("rel_path") val relPath: String,
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
data class DriveDeleteResponse(
    val ok: Boolean = true,
    val results: List<DriveDeleteItemResult> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CancelSpaceJobResponse(
    val ok: Boolean = true,
    val job: TransferJob? = null,
)

@Serializable
data class SpaceJobsSsePayload(
    val jobs: List<TransferJob>? = null,
    val ts: Long? = null,
)
