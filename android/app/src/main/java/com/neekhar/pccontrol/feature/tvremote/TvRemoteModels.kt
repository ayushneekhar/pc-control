package com.neekhar.pccontrol.feature.tvremote

import com.neekhar.pccontrol.feature.watch.player.WatchMediaSegment
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class TvNowPlaying(
    val itemId: String = "",
    val name: String = "",
    val type: String = "",
    val seriesName: String? = null,
    val seasonName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
) {
    val isEpisode: Boolean get() = type.equals("Episode", ignoreCase = true)
}

@Serializable
data class TvAudioTrack(
    val index: Int,
    val displayTitle: String = "",
    val language: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class TvSubtitleTrack(
    val index: Int,
    val displayTitle: String = "",
    val language: String? = null,
    val codec: String? = null,
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
)

@Serializable
data class TvDeviceInfo(
    val sessionId: String = "",
    val deviceName: String = "",
    val client: String? = null,
    val userName: String? = null,
    val isTv: Boolean = false,
    val isPlaying: Boolean = false,
    val nowPlayingTitle: String? = null,
)

@Serializable
data class TvDevicesResponse(
    val ok: Boolean = false,
    val devices: List<TvDeviceInfo> = emptyList(),
    val defaultDevice: String? = null,
)

@Serializable
data class TvSessionResponse(
    val connected: Boolean = false,
    val sessionId: String? = null,
    val deviceName: String = "TV",
    val client: String? = null,
    val appVersion: String? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isMuted: Boolean = false,
    val volumeLevel: Int? = null,
    val positionTicks: Long = 0L,
    val runTimeTicks: Long = 0L,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val canSeek: Boolean = false,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val nowPlaying: TvNowPlaying? = null,
    val audioTracks: List<TvAudioTrack> = emptyList(),
    val subtitleTracks: List<TvSubtitleTrack> = emptyList(),
    val segments: List<WatchMediaSegment> = emptyList(),
    val supportedCommands: List<String> = emptyList(),
)

@Serializable
data class TvCommandRequest(
    val sessionId: String? = null,
    val deviceName: String? = null,
    val command: String,
    val seekPositionTicks: Long? = null,
    val positionSeconds: Double? = null,
    val deltaSeconds: Double? = null,
    val index: Int? = null,
    val volume: Int? = null,
)

@Serializable
data class TvCommandResponse(
    val ok: Boolean = false,
    val command: String? = null,
    val sessionId: String? = null,
    val positionTicks: Long? = null,
    val positionSeconds: Double? = null,
    val code: String? = null,
    val error: String? = null,
)

object TvRemoteFormatters {
    const val TICKS_PER_SECOND: Long = 10_000_000L

    fun formatDuration(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val secs = safe % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    fun formatRemaining(positionSeconds: Long, durationSeconds: Long): String {
        if (durationSeconds <= 0L) return "--:--"
        val remaining = (durationSeconds - positionSeconds).coerceAtLeast(0L)
        return "-${formatDuration(remaining)}"
    }

    fun primaryTitle(nowPlaying: TvNowPlaying): String {
        if (nowPlaying.isEpisode && !nowPlaying.seriesName.isNullOrBlank()) {
            return nowPlaying.seriesName
        }
        return nowPlaying.name.ifBlank { "Now playing" }
    }

    fun secondarySubtitle(nowPlaying: TvNowPlaying): String? {
        if (nowPlaying.isEpisode) {
            val code = buildList {
                nowPlaying.seasonNumber?.let { add(String.format(Locale.US, "S%02d", it)) }
                nowPlaying.episodeNumber?.let { add(String.format(Locale.US, "E%02d", it)) }
            }.joinToString(" · ")
            val epName = nowPlaying.name.takeIf { it.isNotBlank() && !nowPlaying.seriesName.isNullOrBlank() }
            return when {
                code.isNotEmpty() && epName != null -> "$code — $epName"
                code.isNotEmpty() -> code
                epName != null -> epName
                else -> nowPlaying.year?.toString()
            }
        }
        return nowPlaying.year?.toString()
    }

    fun miniBarTitle(nowPlaying: TvNowPlaying): String {
        if (nowPlaying.isEpisode && !nowPlaying.seriesName.isNullOrBlank()) {
            val code = buildList {
                nowPlaying.seasonNumber?.let { add(String.format(Locale.US, "S%02d", it)) }
                nowPlaying.episodeNumber?.let { add(String.format(Locale.US, "E%02d", it)) }
            }.joinToString(" ")
            return if (code.isNotEmpty()) "${nowPlaying.seriesName} · $code" else nowPlaying.seriesName
        }
        return nowPlaying.name.ifBlank { "Now playing" }
    }

    fun activeAudioLabel(session: TvSessionResponse): String {
        val current = session.audioStreamIndex?.let { idx ->
            session.audioTracks.firstOrNull { it.index == idx }
        }
        val fallback = current
            ?: session.audioTracks.firstOrNull { it.isDefault }
            ?: session.audioTracks.firstOrNull()
        return fallback?.displayTitle?.takeIf { it.isNotBlank() } ?: "Default"
    }

    fun activeSubtitleLabel(session: TvSessionResponse): String {
        val idx = session.subtitleStreamIndex
        if (idx == null || idx < 0) return "Off"
        val track = session.subtitleTracks.firstOrNull { it.index == idx }
        return track?.displayTitle?.takeIf { it.isNotBlank() } ?: "Track $idx"
    }

    fun supportsCommand(session: TvSessionResponse, commandName: String): Boolean {
        if (session.supportedCommands.isEmpty()) return true
        return session.supportedCommands.any { it.equals(commandName, ignoreCase = true) }
    }

    fun isVolumeSupported(session: TvSessionResponse): Boolean {
        if (session.supportedCommands.isEmpty()) return true
        return supportsCommand(session, "VolumeUp") ||
            supportsCommand(session, "VolumeDown") ||
            supportsCommand(session, "ToggleMute") ||
            supportsCommand(session, "SetVolume")
    }

    fun formatAudioTrackMeta(track: TvAudioTrack): String? {
        val parts = buildList {
            track.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
            track.channels?.takeIf { it > 0 }?.let { ch ->
                add(
                    when (ch) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${ch}ch"
                    },
                )
            }
            if (track.isDefault) add("Default")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun formatSubtitleTrackMeta(track: TvSubtitleTrack): String? {
        val parts = buildList {
            track.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
            if (track.isExternal) add("External")
            if (track.isDefault) add("Default")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
