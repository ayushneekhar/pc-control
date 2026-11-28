package com.neekhar.pccontrol.feature.watch.player

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.feature.seerr.PlayOnTvRequestBody
import com.neekhar.pccontrol.feature.seerr.PlayOnTvResponse
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WatchPlayerRepository(
    private val api: PcApiClient,
    private val cacheTtlMs: Long = PLAYBACK_INFO_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class CachedPlaybackInfo(
        val response: WatchPlaybackInfoResponse,
        val cachedAtMs: Long,
    )

    private val playbackCache = ConcurrentHashMap<String, CachedPlaybackInfo>()
    private val inFlightPrefetches = ConcurrentHashMap.newKeySet<String>()

    companion object {
        const val PLAYBACK_INFO_CACHE_TTL_MS = 20_000L
        private const val MAX_CACHE_ENTRIES = 24
    }

    internal fun cacheKey(
        jellyfinItemId: String?,
        tmdbId: Int?,
        mediaType: String?,
        season: Int?,
        episode: Int?,
    ): String? {
        val jf = jellyfinItemId?.trim()?.takeIf { it.isNotEmpty() }
        if (jf != null) return "jf:$jf"
        if (tmdbId != null && tmdbId > 0) {
            val mt = mediaType?.trim()?.lowercase(Locale.US).orEmpty()
            return "tmdb:$tmdbId:$mt:${season ?: -1}:${episode ?: -1}"
        }
        return null
    }

    private fun getFreshCached(key: String): WatchPlaybackInfoResponse? {
        val entry = playbackCache[key] ?: return null
        val age = clockMs() - entry.cachedAtMs
        if (age in 0L..cacheTtlMs) {
            return entry.response
        }
        playbackCache.remove(key, entry)
        return null
    }

    private fun storeCached(requestKey: String?, response: WatchPlaybackInfoResponse) {
        val now = clockMs()
        if (playbackCache.size >= MAX_CACHE_ENTRIES) {
            playbackCache.entries.removeIf { (_, v) ->
                val age = now - v.cachedAtMs
                age < 0L || age > cacheTtlMs
            }
            if (playbackCache.size >= MAX_CACHE_ENTRIES) {
                playbackCache.clear()
            }
        }
        val entry = CachedPlaybackInfo(response = response, cachedAtMs = now)
        if (requestKey != null) {
            playbackCache[requestKey] = entry
        }
        val jfId = response.jellyfinItemId.trim()
        if (jfId.isNotEmpty()) {
            playbackCache["jf:$jfId"] = entry
        }
    }

    fun clearPlaybackCache() {
        playbackCache.clear()
    }

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

    /**
     * Quietly warms the short-TTL playback-info cache over Tailscale so tapping Play starts
     * buffering frame 1 immediately without waiting on `/pc/api/watch/playback-info`.
     * Never throws (except cancellation) and never mutates UI or transport failure state.
     */
    suspend fun prefetchPlaybackInfo(
        jellyfinItemId: String? = null,
        tmdbId: Int? = null,
        mediaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) {
        val key = cacheKey(jellyfinItemId, tmdbId, mediaType, season, episode) ?: return
        if (getFreshCached(key) != null) return
        if (!inFlightPrefetches.add(key)) return
        try {
            val res = fetchPlaybackInfoRaw(
                jellyfinItemId = jellyfinItemId,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                maxBitrate = null,
                audioStreamIndex = null,
            )
            if (res is ApiResult.Success) {
                storeCached(key, res.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best-effort prefetch; swallow network/serialization errors silently.
        } finally {
            inFlightPrefetches.remove(key)
        }
    }

    suspend fun getPlaybackInfo(
        jellyfinItemId: String? = null,
        tmdbId: Int? = null,
        mediaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
        maxBitrate: Long? = null,
        audioStreamIndex: Int? = null,
        useCache: Boolean = true,
    ): ApiResult<WatchPlaybackInfoResponse> {
        val hasExplicitOverride = maxBitrate != null || audioStreamIndex != null
        val key = if (!hasExplicitOverride) {
            cacheKey(jellyfinItemId, tmdbId, mediaType, season, episode)
        } else {
            null
        }
        if (useCache && key != null) {
            getFreshCached(key)?.let { cached ->
                return ApiResult.Success(cached, statusCode = 200)
            }
        }
        val res = fetchPlaybackInfoRaw(
            jellyfinItemId = jellyfinItemId,
            tmdbId = tmdbId,
            mediaType = mediaType,
            season = season,
            episode = episode,
            maxBitrate = maxBitrate,
            audioStreamIndex = audioStreamIndex,
        )
        if (res is ApiResult.Success && !hasExplicitOverride) {
            storeCached(key, res.data)
        }
        return res
    }

    private suspend fun fetchPlaybackInfoRaw(
        jellyfinItemId: String?,
        tmdbId: Int?,
        mediaType: String?,
        season: Int?,
        episode: Int?,
        maxBitrate: Long?,
        audioStreamIndex: Int?,
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

    suspend fun searchRemoteSubtitles(
        jellyfinItemId: String,
        language: String = "eng",
    ): ApiResult<WatchSubtitleSearchResponse> {
        val encId = URLEncoder.encode(jellyfinItemId.trim(), "UTF-8")
        val encLang = URLEncoder.encode(language.trim().ifBlank { "eng" }, "UTF-8")
        return api.getJson<WatchSubtitleSearchResponse>(
            "/pc/api/watch/subtitles/search?jellyfinItemId=$encId&language=$encLang",
            timeoutMs = 20_000L,
        )
    }

    suspend fun downloadRemoteSubtitle(
        jellyfinItemId: String,
        subtitleId: String,
    ): ApiResult<WatchSubtitleDownloadResponse> {
        return api.postJson<WatchSubtitleDownloadRequest, WatchSubtitleDownloadResponse>(
            pathOrUrl = "/pc/api/watch/subtitles/download",
            body = WatchSubtitleDownloadRequest(
                jellyfinItemId = jellyfinItemId.trim(),
                subtitleId = subtitleId.trim(),
            ),
            timeoutMs = 20_000L,
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
