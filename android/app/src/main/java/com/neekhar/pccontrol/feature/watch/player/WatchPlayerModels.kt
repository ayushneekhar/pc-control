package com.neekhar.pccontrol.feature.watch.player

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class WatchPlaybackInfoRequest(
    val jellyfinItemId: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val maxBitrate: Long? = null,
    val audioStreamIndex: Int? = null,
)

@Serializable
data class WatchQualityOption(
    val id: String = "original",
    val label: String = "Original (Direct)",
    val maxBitrate: Long = 0L,
    val maxWidth: Int? = null,
    val streamUrl: String = "",
    val isHls: Boolean = false,
)

@Serializable
data class WatchAudioTrack(
    val index: Int = 0,
    val language: String = "",
    val displayTitle: String = "",
    val codec: String = "",
    val channels: Int? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class WatchSubtitleTrack(
    val index: Int = 0,
    val language: String = "",
    val displayTitle: String = "",
    val codec: String = "",
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val deliveryUrl: String = "",
)

@Serializable
data class WatchChapter(
    val name: String = "",
    val startPositionTicks: Long = 0L,
)

@Serializable
data class WatchNextEpisode(
    val jellyfinItemId: String = "",
    val title: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val stillUrl: String? = null,
) {
    val label: String
        get() = buildList {
            if (seasonNumber != null && episodeNumber != null) {
                add("S$seasonNumber · E$episodeNumber")
            } else if (episodeNumber != null) {
                add("E$episodeNumber")
            }
            if (title.isNotBlank()) add(title)
        }.joinToString(" · ")
}

@Serializable
data class WatchPlaybackInfoResponse(
    val jellyfinItemId: String = "",
    val mediaSourceId: String = "",
    val playSessionId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val mediaType: String = "movie",
    val tmdbId: Int? = null,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val runtimeTicks: Long = 0L,
    val resumePositionTicks: Long = 0L,
    val hlsUrl: String = "",
    val directStreamUrl: String = "",
    val recommendedQualityId: String = "original",
    val qualityOptions: List<WatchQualityOption> = emptyList(),
    val audioTracks: List<WatchAudioTrack> = emptyList(),
    val subtitleTracks: List<WatchSubtitleTrack> = emptyList(),
    val chapters: List<WatchChapter> = emptyList(),
    val nextEpisode: WatchNextEpisode? = null,
)

@Serializable
data class WatchProgressRequest(
    val jellyfinItemId: String,
    val mediaSourceId: String = "",
    val playSessionId: String = "",
    val positionTicks: Long = 0L,
    val runtimeTicks: Long = 0L,
    val event: String = "progress",
    val isPaused: Boolean = false,
)

enum class WatchPlayerSheet {
    NONE,
    QUALITY,
    TRACKS,
    SPEED,
}

object WatchPlayerHelpers {
    const val TICKS_PER_MS = 10_000L
    const val NEXT_EPISODE_THRESHOLD_MS = 15_000L
    val SPEED_OPTIONS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private val UNSUPPORTED_DIRECT_AUDIO_CODECS: Set<String> = setOf(
        "truehd",
        "true-hd",
        "mlp",
        "dts",
        "dca",
        "dtshd",
        "dts-hd",
        "dts_hd",
        "dts-hd ma",
        "dts-hd hra",
        "dts:x",
    )

    fun isUnsupportedDirectAudioCodec(codec: String?): Boolean {
        val norm = codec?.trim()?.lowercase(Locale.US)?.removePrefix("audio/") ?: return false
        if (norm.isEmpty()) return false
        return norm in UNSUPPORTED_DIRECT_AUDIO_CODECS ||
            norm.startsWith("truehd") ||
            norm.startsWith("true-hd") ||
            norm.startsWith("dts") ||
            norm == "dca" ||
            norm == "mlp"
    }

    fun selectHlsFallbackQuality(options: List<WatchQualityOption>): WatchQualityOption? {
        return options.firstOrNull { it.isHls && it.id == "1080p" }
            ?: options.firstOrNull { it.isHls }
    }

    /**
     * Decide which subtitle tracks should be attached as ExoPlayer `SubtitleConfiguration` sidecars.
     *
     * - External tracks (`isExternal == true`) and local offline files (`file://`) serve
     *   instantaneously with zero server-side ffmpeg extraction and are always attached.
     * - Embedded container tracks (`isExternal == false`) are NEVER attached via HTTP in Direct Play
     *   (ExoPlayer demuxes MKV/MP4 text subtitles natively at 0ms latency).
     * - In HLS mode, embedded tracks are NOT attached eagerly on startup; only the single embedded
     *   track explicitly chosen by the user (`hlsAttachedSubtitleId`) is attached on demand.
     */
    fun subtitlesToAttach(
        tracks: List<PlayerSubtitleEntry>,
        isHls: Boolean,
        hlsAttachedSubtitleId: String?,
    ): List<PlayerSubtitleEntry> {
        return tracks.filter { sub ->
            if (sub.uri.isBlank()) return@filter false
            if (sub.isExternal || sub.uri.startsWith("file://", ignoreCase = true)) {
                true
            } else {
                isHls && hlsAttachedSubtitleId != null && sub.id == hlsAttachedSubtitleId
            }
        }
    }

    fun languagesMatch(a: String?, b: String?): Boolean {
        val na = a?.trim()?.lowercase(Locale.US).orEmpty()
        val nb = b?.trim()?.lowercase(Locale.US).orEmpty()
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val isoA = runCatching { Locale.forLanguageTag(na).isO3Language.lowercase(Locale.US) }.getOrNull()
        val isoB = runCatching { Locale.forLanguageTag(nb).isO3Language.lowercase(Locale.US) }.getOrNull()
        if (!isoA.isNullOrBlank() && !isoB.isNullOrBlank() && isoA == isoB) return true
        return na.take(2) == nb.take(2) && na.length >= 2 && nb.length >= 2
    }

    fun ticksToMs(ticks: Long): Long = if (ticks <= 0L) 0L else ticks / TICKS_PER_MS

    fun msToTicks(ms: Long): Long = if (ms <= 0L) 0L else ms * TICKS_PER_MS

    fun formatTimeMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatSpeed(speed: Float): String = when (speed) {
        0.75f -> "0.75x"
        1.0f -> "1x"
        1.25f -> "1.25x"
        1.5f -> "1.5x"
        2.0f -> "2x"
        else -> String.format(Locale.US, "%.2gx", speed)
    }

    fun shouldShowNextEpisodeOverlay(
        positionMs: Long,
        durationMs: Long,
        isEnded: Boolean,
        hasNextEpisode: Boolean,
        dismissed: Boolean,
    ): Boolean {
        if (!hasNextEpisode || dismissed) return false
        if (isEnded) return true
        if (durationMs <= 0L) return false
        val remaining = durationMs - positionMs
        return remaining in 0L..NEXT_EPISODE_THRESHOLD_MS
    }

    fun selectInitialQuality(
        options: List<WatchQualityOption>,
        recommendedId: String?,
    ): WatchQualityOption? {
        if (options.isEmpty()) return null
        if (!recommendedId.isNullOrBlank()) {
            options.firstOrNull { it.id == recommendedId }?.let { return it }
        }
        return options.firstOrNull { it.id == "1080p" } ?: options.first()
    }

    fun withAudioStreamIndex(url: String, audioIndex: Int): String {
        if (url.isBlank()) return url
        val regex = Regex("""([?&])AudioStreamIndex=\d+""", RegexOption.IGNORE_CASE)
        return if (regex.containsMatchIn(url)) {
            regex.replace(url) { m -> "${m.groupValues[1]}AudioStreamIndex=$audioIndex" }
        } else {
            val sep = if ('?' in url) "&" else "?"
            "$url${sep}AudioStreamIndex=$audioIndex"
        }
    }

    fun computeDragBrightness(startBrightness: Float, verticalDeltaFraction: Float): Float {
        // Dragging up (negative dy -> positive upward fraction) increases brightness
        return (startBrightness + verticalDeltaFraction).coerceIn(0.05f, 1.0f)
    }

    fun computeDragVolume(startVolume: Int, maxVolume: Int, verticalDeltaFraction: Float): Int {
        if (maxVolume <= 0) return 0
        val startFrac = startVolume.toFloat() / maxVolume.toFloat()
        val nextFrac = (startFrac + verticalDeltaFraction).coerceIn(0f, 1f)
        return (nextFrac * maxVolume).toInt().coerceIn(0, maxVolume)
    }
}
