package com.neekhar.pccontrol.feature.seerr

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.feature.watch.player.WatchSubtitleDownloadRequest
import com.neekhar.pccontrol.feature.watch.player.WatchSubtitleDownloadResponse
import com.neekhar.pccontrol.feature.watch.player.WatchSubtitleSearchResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

class SeerrRepository(
    private val api: PcApiClient,
    private val cache: LocalCache? = null,
) {
    companion object {
        private const val CACHE_KEY_REQUESTS = "seerr:requests"
        private const val CACHE_KEY_COUNTS = "seerr:requestCounts"
        private const val CACHE_KEY_WATCH_HOME = "watch:home"
        private const val SEERR_TIMEOUT_MS = 25_000L
    }

    fun resolveImageUrl(pathOrUrl: String?): String? {
        val trimmed = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return api.resolveUrl(trimmed)
    }

    suspend fun getWatchHome(forceRefresh: Boolean = false): ApiResult<WatchHomeResponse> {
        val suffix = if (forceRefresh) "?refresh=1" else ""
        val result = api.getJson<WatchHomeResponse>(
            "/pc/api/watch/home$suffix",
            timeoutMs = SEERR_TIMEOUT_MS,
        )
        if (result is ApiResult.Success) {
            saveCachedWatchHome(result.data)
        }
        return result
    }

    suspend fun getWatchDiscover(
        category: String,
        mediaType: String? = null,
        genreId: Int? = null,
        networkId: Int? = null,
        page: Int = 1,
        sort: String? = null,
    ): ApiResult<WatchDiscoverResponse> {
        val params = mutableListOf("category=${URLEncoder.encode(category, "UTF-8")}")
        if (!mediaType.isNullOrBlank()) {
            params.add("mediaType=${URLEncoder.encode(mediaType, "UTF-8")}")
        }
        if (genreId != null && genreId > 0) {
            params.add("genreId=$genreId")
        }
        if (networkId != null && networkId > 0) {
            params.add("networkId=$networkId")
        }
        if (page > 1) {
            params.add("page=$page")
        }
        if (!sort.isNullOrBlank()) {
            params.add("sort=${URLEncoder.encode(sort, "UTF-8")}")
        }
        return api.getJson<WatchDiscoverResponse>(
            "/pc/api/watch/discover?${params.joinToString("&")}",
            timeoutMs = SEERR_TIMEOUT_MS,
        )
    }

    suspend fun toggleWatchlist(body: WatchWatchlistRequestBody): ApiResult<WatchWatchlistResponse> {
        val res = api.postJson<WatchWatchlistRequestBody, WatchWatchlistResponse>(
            pathOrUrl = "/pc/api/watch/watchlist",
            body = body,
        )
        if (res is ApiResult.Success) {
            updateCachedWatchlistIds(res.data.ids)
        }
        return res
    }

    suspend fun getWatchEpisodes(tmdbId: Int, season: Int): ApiResult<WatchEpisodesResponse> {
        return api.getJson<WatchEpisodesResponse>(
            "/pc/api/watch/episodes?tmdbId=$tmdbId&season=$season",
            timeoutMs = SEERR_TIMEOUT_MS,
        )
    }

    suspend fun setWatched(jellyfinItemId: String, played: Boolean): ApiResult<WatchWatchedResponse> {
        return api.postJson<WatchWatchedRequestBody, WatchWatchedResponse>(
            pathOrUrl = "/pc/api/watch/watched",
            body = WatchWatchedRequestBody(
                jellyfinItemId = jellyfinItemId.trim(),
                played = played,
            ),
            timeoutMs = 12_000L,
        )
    }

    suspend fun getSurpriseMovie(): ApiResult<WatchSurpriseResponse> {
        return api.getJson<WatchSurpriseResponse>(
            "/pc/api/watch/surprise",
            timeoutMs = 15_000L,
        )
    }

    suspend fun getWatchCollections(): ApiResult<WatchBoxSetsResponse> {
        return api.getJson<WatchBoxSetsResponse>(
            "/pc/api/watch/collections",
            timeoutMs = 15_000L,
        )
    }

    suspend fun getWatchCollectionDetail(boxSetId: String): ApiResult<WatchBoxSetDetailResponse> {
        val encId = URLEncoder.encode(boxSetId.trim(), "UTF-8")
        return api.getJson<WatchBoxSetDetailResponse>(
            "/pc/api/watch/collections/$encId",
            timeoutMs = 15_000L,
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

    suspend fun getWatchDownloads(
        tmdbId: Int? = null,
        mediaType: String? = null,
    ): ApiResult<WatchDownloadsResponse> {
        val params = mutableListOf<String>()
        if (tmdbId != null && tmdbId > 0) {
            params.add("tmdbId=$tmdbId")
        }
        if (!mediaType.isNullOrBlank()) {
            params.add("mediaType=${URLEncoder.encode(mediaType, "UTF-8")}")
        }
        val suffix = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return api.getJson<WatchDownloadsResponse>(
            "/pc/api/watch/downloads$suffix",
            timeoutMs = 12_000L,
        )
    }

    fun getCachedWatchHome(): WatchHomeResponse? {
        val raw = cache?.getString(CACHE_KEY_WATCH_HOME) ?: return null
        return runCatching {
            api.json.decodeFromString(WatchHomeResponse.serializer(), raw)
        }.getOrNull()
    }

    fun getCachedWatchlistIds(): Set<String> {
        return getCachedWatchHome()?.watchlistIds?.toSet().orEmpty()
    }

    private fun saveCachedWatchHome(home: WatchHomeResponse) {
        val localCache = cache ?: return
        runCatching {
            val encoded = api.json.encodeToString(WatchHomeResponse.serializer(), home)
            localCache.putString(CACHE_KEY_WATCH_HOME, encoded)
        }
    }

    private fun updateCachedWatchlistIds(ids: List<String>) {
        val prev = getCachedWatchHome() ?: return
        val idSet = ids.toSet()
        val updated = prev.copy(
            watchlistIds = ids,
            hero = prev.hero?.let { h ->
                h.copy(inWatchlist = SeerrHelpers.watchlistKey(h.mediaType, h.id) in idSet)
            },
            heroItems = prev.heroItems.map { h ->
                h.copy(inWatchlist = SeerrHelpers.watchlistKey(h.mediaType, h.id) in idSet)
            },
        )
        saveCachedWatchHome(updated)
    }

    suspend fun search(query: String): ApiResult<List<SeerrItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ApiResult.Success(emptyList())
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return when (val res = api.getJson<SeerrSearchResponse>("/pc/api/seerr/search?q=$encoded", timeoutMs = SEERR_TIMEOUT_MS)) {
            is ApiResult.Success -> ApiResult.Success(res.data.results)
            is ApiResult.Error -> res
        }
    }

    suspend fun getDetails(mediaType: String, id: Int): ApiResult<SeerrItem> {
        val type = if (mediaType == "tv") "tv" else "movie"
        return api.getJson<SeerrItem>("/pc/api/seerr/details?type=$type&id=$id", timeoutMs = SEERR_TIMEOUT_MS)
    }

    suspend fun getCollection(id: Int): ApiResult<SeerrCollection> {
        return api.getJson<SeerrCollection>("/pc/api/seerr/collection?id=$id", timeoutMs = SEERR_TIMEOUT_MS)
    }

    suspend fun getPerson(id: Int): ApiResult<SeerrPerson> {
        return api.getJson<SeerrPerson>("/pc/api/seerr/person?id=$id", timeoutMs = SEERR_TIMEOUT_MS)
    }

    suspend fun getRequests(
        filter: SeerrRequestFilter = SeerrRequestFilter.ALL,
        take: Int = 50,
    ): ApiResult<SeerrRequestsResponse> {
        val params = mutableListOf<String>()
        if (filter != SeerrRequestFilter.ALL) {
            params.add("filter=${URLEncoder.encode(filter.wire, "UTF-8")}")
        }
        if (take != 50) {
            params.add("take=$take")
        }
        val suffix = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        val result = api.getJson<SeerrRequestsResponse>("/pc/api/seerr/requests$suffix", timeoutMs = SEERR_TIMEOUT_MS)
        if (result is ApiResult.Success && filter == SeerrRequestFilter.ALL) {
            saveCachedRequests(result.data.requests)
            result.data.counts?.let { saveCachedCounts(it) }
        }
        return result
    }

    suspend fun createMovieRequest(mediaId: Int): ApiResult<SeerrCreateRequestResponse> {
        return api.postJson<SeerrCreateMovieRequestBody, SeerrCreateRequestResponse>(
            pathOrUrl = "/pc/api/seerr/request",
            body = SeerrCreateMovieRequestBody(mediaType = "movie", mediaId = mediaId),
        )
    }

    suspend fun createTvRequest(
        mediaId: Int,
        seasons: List<Int>? = null,
    ): ApiResult<SeerrCreateRequestResponse> {
        val seasonsElement = if (seasons.isNullOrEmpty()) {
            JsonPrimitive("all")
        } else {
            JsonArray(seasons.map { JsonPrimitive(it) })
        }
        return api.postJson<SeerrCreateTvRequestBody, SeerrCreateRequestResponse>(
            pathOrUrl = "/pc/api/seerr/request",
            body = SeerrCreateTvRequestBody(
                mediaType = "tv",
                mediaId = mediaId,
                seasons = seasonsElement,
            ),
        )
    }

    suspend fun resolveUrl(url: String): ApiResult<SeerrItem> {
        return api.postJson<SeerrResolveRequestBody, SeerrItem>(
            pathOrUrl = "/pc/api/seerr/resolve",
            body = SeerrResolveRequestBody(url = url),
            timeoutMs = SEERR_TIMEOUT_MS,
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
        )
    }

    fun getCachedRequests(): List<SeerrRequestItem>? {
        val raw = cache?.getString(CACHE_KEY_REQUESTS) ?: return null
        return runCatching {
            api.json.decodeFromString(ListSerializer(SeerrRequestItem.serializer()), raw)
        }.getOrNull()
    }

    fun getCachedCounts(): Map<String, Int>? {
        val raw = cache?.getString(CACHE_KEY_COUNTS) ?: return null
        return runCatching {
            api.json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), raw)
        }.getOrNull()
    }

    private fun saveCachedRequests(requests: List<SeerrRequestItem>) {
        val localCache = cache ?: return
        runCatching {
            val encoded = api.json.encodeToString(ListSerializer(SeerrRequestItem.serializer()), requests)
            localCache.putString(CACHE_KEY_REQUESTS, encoded)
        }
    }

    private fun saveCachedCounts(counts: Map<String, Int>) {
        val localCache = cache ?: return
        runCatching {
            val encoded = api.json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), counts)
            localCache.putString(CACHE_KEY_COUNTS, encoded)
        }
    }
}
