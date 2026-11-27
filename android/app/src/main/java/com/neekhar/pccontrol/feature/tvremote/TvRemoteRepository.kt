package com.neekhar.pccontrol.feature.tvremote

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient

class TvRemoteRepository(
    private val api: PcApiClient,
) {
    suspend fun getSession(): ApiResult<TvSessionResponse> {
        return api.getJson<TvSessionResponse>(
            pathOrUrl = "/pc/api/jellyfin/tv/session",
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

    fun resolveImageUrl(pathOrUrl: String?): String? {
        val trimmed = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return api.resolveUrl(trimmed)
    }
}
