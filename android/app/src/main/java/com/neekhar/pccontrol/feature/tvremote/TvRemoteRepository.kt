package com.neekhar.pccontrol.feature.tvremote

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.feature.seerr.PlayOnTvRequestBody
import com.neekhar.pccontrol.feature.seerr.PlayOnTvResponse
import java.net.URLEncoder

class TvRemoteRepository(
    private val api: PcApiClient,
) {
    suspend fun getDevices(): ApiResult<TvDevicesResponse> {
        return api.getJson<TvDevicesResponse>(
            pathOrUrl = "/pc/api/jellyfin/tv/devices",
            timeoutMs = 8_000L,
        )
    }

    suspend fun getSession(
        sessionId: String? = null,
        deviceName: String? = null,
    ): ApiResult<TvSessionResponse> {
        val params = buildList {
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("sessionId=${URLEncoder.encode(it, "UTF-8")}")
            }
            deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("deviceName=${URLEncoder.encode(it, "UTF-8")}")
            }
        }
        val suffix = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return api.getJson<TvSessionResponse>(
            pathOrUrl = "/pc/api/jellyfin/tv/session$suffix",
            timeoutMs = 8_000L,
        )
    }

    suspend fun sendCommand(request: TvCommandRequest): ApiResult<TvCommandResponse> {
        return api.postJson<TvCommandRequest, TvCommandResponse>(
            pathOrUrl = "/pc/api/jellyfin/tv/command",
            body = request,
            timeoutMs = 10_000L,
        )
    }

    suspend fun playOnTv(
        mediaUrl: String? = null,
        tmdbId: Int? = null,
        mediaType: String = "movie",
        title: String? = null,
        jellyfinItemId: String? = null,
        startPositionTicks: Long? = null,
        sessionId: String? = null,
        deviceName: String? = null,
    ): ApiResult<PlayOnTvResponse> {
        val type = when (mediaType.trim().lowercase()) {
            "episode" -> "episode"
            "tv" -> "tv"
            else -> "movie"
        }
        return api.postJson<PlayOnTvRequestBody, PlayOnTvResponse>(
            pathOrUrl = "/pc/api/jellyfin/play-on-tv",
            body = PlayOnTvRequestBody(
                mediaUrl = mediaUrl?.takeIf { it.isNotBlank() },
                tmdbId = tmdbId?.takeIf { it > 0 },
                mediaType = type,
                title = title?.takeIf { it.isNotBlank() },
                jellyfinItemId = jellyfinItemId?.takeIf { it.isNotBlank() },
                startPositionTicks = startPositionTicks?.takeIf { it > 0L },
                sessionId = sessionId?.takeIf { it.isNotBlank() },
                deviceName = deviceName?.takeIf { it.isNotBlank() },
            ),
            timeoutMs = 12_000L,
        )
    }

    fun resolveImageUrl(pathOrUrl: String?): String? {
        val trimmed = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return api.resolveUrl(trimmed)
    }
}
