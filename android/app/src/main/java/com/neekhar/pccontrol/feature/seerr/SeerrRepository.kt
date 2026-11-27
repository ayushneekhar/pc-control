package com.neekhar.pccontrol.feature.seerr

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
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
    }

    suspend fun search(query: String): ApiResult<List<SeerrItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ApiResult.Success(emptyList())
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return when (val res = api.getJson<SeerrSearchResponse>("/pc/api/seerr/search?q=$encoded")) {
            is ApiResult.Success -> ApiResult.Success(res.data.results)
            is ApiResult.Error -> res
        }
    }

    suspend fun getDetails(mediaType: String, id: Int): ApiResult<SeerrItem> {
        val type = if (mediaType == "tv") "tv" else "movie"
        return api.getJson<SeerrItem>("/pc/api/seerr/details?type=$type&id=$id")
    }

    suspend fun getCollection(id: Int): ApiResult<SeerrCollection> {
        return api.getJson<SeerrCollection>("/pc/api/seerr/collection?id=$id")
    }

    suspend fun getPerson(id: Int): ApiResult<SeerrPerson> {
        return api.getJson<SeerrPerson>("/pc/api/seerr/person?id=$id")
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
        val result = api.getJson<SeerrRequestsResponse>("/pc/api/seerr/requests$suffix")
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
