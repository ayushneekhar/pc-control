package com.neekhar.pccontrol.feature.watch.player

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.feature.seerr.PlayOnTvRequestBody
import com.neekhar.pccontrol.feature.seerr.PlayOnTvResponse

class WatchPlayerRepository(
    private val api: PcApiClient,
) {
    fun resolveTailscaleUrl(pathOrUrl: String?): String {
        val raw = pathOrUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val base = api.tailscaleBaseUrl.trimEnd('/')
        val norm = if (raw.startsWith("/")) raw else "/$raw"
        return "$base$norm"
    }

    suspend fun getPlaybackInfo(
        jellyfinItemId: String? = null,
        tmdbId: Int? = null,
        mediaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
        maxBitrate: Long? = null,
        audioStreamIndex: Int? = null,
    ): ApiResult<WatchPlaybackInfoResponse> {
        return api.postJson<WatchPlaybackInfoRequest, WatchPlaybackInfoResponse>(
            pathOrUrl = "/pc/api/watch/playback-info",
            body = WatchPlaybackInfoRequest(
                jellyfinItemId = jellyfinItemId?.takeIf { it.isNotBlank() },
                tmdbId = tmdbId?.takeIf { it > 0 },
                mediaType = mediaType?.takeIf { it.isNotBlank() },
                season = season,
                episode = episode,
                maxBitrate = maxBitrate,
                audioStreamIndex = audioStreamIndex,
            ),
            timeoutMs = 20_000L,
            forceTailscale = true,
        )
    }

    suspend fun reportProgress(request: WatchProgressRequest): ApiResult<Unit> {
        return api.postJson<WatchProgressRequest, Unit>(
            pathOrUrl = "/pc/api/watch/progress",
            body = request,
            timeoutMs = 8_000L,
        )
    }

    suspend fun handoffToTv(
        jellyfinItemId: String,
        mediaType: String,
        title: String,
        positionTicks: Long,
        sessionId: String? = null,
        deviceName: String? = null,
    ): ApiResult<PlayOnTvResponse> {
        val normType = if (mediaType.equals("episode", ignoreCase = true)) "episode" else "movie"
        return api.postJson<PlayOnTvRequestBody, PlayOnTvResponse>(
            pathOrUrl = "/pc/api/jellyfin/play-on-tv",
            body = PlayOnTvRequestBody(
                jellyfinItemId = jellyfinItemId,
                mediaType = normType,
                title = title,
                startPositionTicks = positionTicks.takeIf { it > 0L },
                sessionId = sessionId?.takeIf { it.isNotBlank() },
                deviceName = deviceName?.takeIf { it.isNotBlank() },
            ),
            timeoutMs = 12_000L,
        )
    }
}
