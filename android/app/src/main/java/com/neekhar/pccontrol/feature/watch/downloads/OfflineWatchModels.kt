package com.neekhar.pccontrol.feature.watch.downloads

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
enum class OfflineDownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

@Serializable
data class OfflineSubtitleTrack(
    val index: Int = 0,
    val language: String = "",
    val displayTitle: String = "",
    val format: String = "vtt",
    val localFileName: String = "",
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

@Serializable
data class OfflineWatchItem(
    val jellyfinItemId: String,
    val mediaSourceId: String = "",
    val tmdbId: Int = 0,
    val mediaType: String = "movie",
    val title: String = "Untitled",
    val subtitle: String? = null,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val fileName: String = "video.mp4",
    val mimeType: String = "video/mp4",
    val downloadUrl: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val localVideoPath: String = "",
    val localPosterPath: String? = null,
    val localStillPath: String? = null,
    val remotePosterUrl: String? = null,
    val remoteStillUrl: String? = null,
    val subtitles: List<OfflineSubtitleTrack> = emptyList(),
    val runtimeTicks: Long = 0L,
    val resumePositionTicks: Long = 0L,
    val resumeDirty: Boolean = false,
    val addedAtMs: Long = 0L,
    val completedAtMs: Long? = null,
) {
    val isCompleted: Boolean
        get() = status == OfflineDownloadStatus.COMPLETED && localVideoPath.isNotBlank()

    val isActive: Boolean
        get() = status == OfflineDownloadStatus.QUEUED || status == OfflineDownloadStatus.DOWNLOADING

    val isInProgressOrPaused: Boolean
        get() = status == OfflineDownloadStatus.QUEUED ||
            status == OfflineDownloadStatus.DOWNLOADING ||
            status == OfflineDownloadStatus.PAUSED ||
            status == OfflineDownloadStatus.FAILED

    val progressFraction: Float
        get() = when {
            status == OfflineDownloadStatus.COMPLETED -> 1f
            totalBytes > 0L -> (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).roundToInt().coerceIn(0, 100)

    val resumeProgressFraction: Float
        get() = if (runtimeTicks > 0L && resumePositionTicks > 0L) {
            (resumePositionTicks.toDouble() / runtimeTicks.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

@Serializable
data class WatchDownloadSubtitleInfo(
    val index: Int = 0,
    val language: String = "",
    val displayTitle: String = "",
    val codec: String = "",
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val format: String = "vtt",
    val fileName: String = "",
    val downloadUrl: String = "",
)

@Serializable
data class WatchDownloadInfoResponse(
    val jellyfinItemId: String = "",
    val mediaSourceId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val mediaType: String = "movie",
    val tmdbId: Int? = null,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val runtimeTicks: Long = 0L,
    val fileName: String = "video.mp4",
    val sizeBytes: Long = 0L,
    val mimeType: String = "video/mp4",
    val downloadUrl: String = "",
    val posterUrl: String? = null,
    val stillUrl: String? = null,
    val subtitles: List<WatchDownloadSubtitleInfo> = emptyList(),
)

sealed interface WatchDownloadEnqueueResult {
    data class Enqueued(val item: OfflineWatchItem) : WatchDownloadEnqueueResult
    data class AlreadyDownloaded(val item: OfflineWatchItem) : WatchDownloadEnqueueResult
    data object TailscaleRequired : WatchDownloadEnqueueResult
    data class Error(val message: String) : WatchDownloadEnqueueResult
}

object WatchOfflineHelpers {
    private const val KB = 1024.0
    private const val MB = 1024.0 * 1024.0
    private const val GB = 1024.0 * 1024.0 * 1024.0

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes >= GB -> String.format(Locale.US, "%.1f GB", bytes / GB)
            bytes >= MB -> String.format(Locale.US, "%.0f MB", bytes / MB)
            bytes >= KB -> String.format(Locale.US, "%.0f KB", bytes / KB)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        return when {
            bytesPerSec >= MB -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / MB)
            bytesPerSec >= KB -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / KB)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatStorageSummary(totalBytes: Long, completedCount: Int): String {
        val noun = if (completedCount == 1) "title" else "titles"
        return "${formatBytes(totalBytes)} downloaded · $completedCount $noun"
    }

    fun sanitizeFileName(raw: String, fallback: String = "video.mp4"): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+\."""), ".")
            .trim()
            .trim('.')
        return cleaned.ifEmpty { fallback }.take(160)
    }
}
