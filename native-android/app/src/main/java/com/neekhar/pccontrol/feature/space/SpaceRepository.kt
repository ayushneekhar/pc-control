package com.neekhar.pccontrol.feature.space

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.network.SseMessage
import com.neekhar.pccontrol.core.storage.LocalCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.net.URLEncoder

sealed interface SpaceJobsStreamUpdate {
    data object Connected : SpaceJobsStreamUpdate
    data object Disconnected : SpaceJobsStreamUpdate
    data class Jobs(val jobs: List<TransferJob>) : SpaceJobsStreamUpdate
}

class SpaceRepository(
    private val api: PcApiClient,
    private val cache: LocalCache? = null,
) {
    companion object {
        const val CACHE_KEY_WATCHED = "space:overview"
        const val CACHE_KEY_ALL = "space:overview:all"
        const val CACHE_KEY_DRIVE = "space:drive"
        const val SSE_JOBS_PATH = "/pc/api/space/jobs/stream"

        private const val GET_SPACE_TIMEOUT_MS = 30_000L
        private const val FREE_SPACE_TIMEOUT_MS = 120_000L
        private const val GET_DRIVE_TIMEOUT_MS = 20_000L
        private const val ENQUEUE_TIMEOUT_MS = 30_000L
        private const val DELETE_DRIVE_TIMEOUT_MS = 120_000L
        private const val CANCEL_JOB_TIMEOUT_MS = 15_000L
        private const val SSE_INITIAL_RETRY_MS = 1_500L
        private const val SSE_MAX_RETRY_MS = 15_000L
    }

    suspend fun getSpace(scope: SpaceScope = SpaceScope.WATCHED): ApiResult<SpaceResponse> {
        val path = if (scope == SpaceScope.ALL) "/pc/api/space?scope=all" else "/pc/api/space"
        val result = api.getJson<SpaceResponse>(
            pathOrUrl = path,
            timeoutMs = GET_SPACE_TIMEOUT_MS,
        )
        if (result is ApiResult.Success) {
            saveCachedSpace(scope, result.data)
        }
        return result
    }

    suspend fun freeSpace(items: List<SpaceFreeItemRequest>): ApiResult<SpaceFreeResponse> {
        return api.postJson<SpaceItemsRequestBody, SpaceFreeResponse>(
            pathOrUrl = "/pc/api/space/free",
            body = SpaceItemsRequestBody(items = items),
            timeoutMs = FREE_SPACE_TIMEOUT_MS,
        )
    }

    suspend fun getSpaceDrive(refresh: Boolean = false): ApiResult<DriveStatusResponse> {
        val path = if (refresh) "/pc/api/space/drive?refresh=1" else "/pc/api/space/drive"
        val result = api.getJson<DriveStatusResponse>(
            pathOrUrl = path,
            timeoutMs = GET_DRIVE_TIMEOUT_MS,
        )
        if (result is ApiResult.Success) {
            saveCachedDrive(result.data)
        }
        return result
    }

    suspend fun offloadSpaceItems(items: List<SpaceFreeItemRequest>): ApiResult<SpaceJobEnqueueResponse> {
        return api.postJson<SpaceItemsRequestBody, SpaceJobEnqueueResponse>(
            pathOrUrl = "/pc/api/space/offload",
            body = SpaceItemsRequestBody(items = items),
            timeoutMs = ENQUEUE_TIMEOUT_MS,
        )
    }

    suspend fun restoreDriveItems(relPaths: List<String>): ApiResult<SpaceJobEnqueueResponse> {
        return api.postJson<DriveRelPathItemsBody, SpaceJobEnqueueResponse>(
            pathOrUrl = "/pc/api/space/restore",
            body = DriveRelPathItemsBody(items = relPaths.map { DriveRelPathRequest(relPath = it) }),
            timeoutMs = ENQUEUE_TIMEOUT_MS,
        )
    }

    suspend fun deleteDriveItems(relPaths: List<String>): ApiResult<DriveDeleteResponse> {
        return api.postJson<DriveRelPathItemsBody, DriveDeleteResponse>(
            pathOrUrl = "/pc/api/space/drive/delete",
            body = DriveRelPathItemsBody(items = relPaths.map { DriveRelPathRequest(relPath = it) }),
            timeoutMs = DELETE_DRIVE_TIMEOUT_MS,
        )
    }

    suspend fun cancelSpaceJob(jobId: String): ApiResult<CancelSpaceJobResponse> {
        val encoded = URLEncoder.encode(jobId, "UTF-8")
        return api.postJson<CancelSpaceJobResponse>(
            pathOrUrl = "/pc/api/space/jobs/$encoded/cancel",
            bodyJson = "{}",
            timeoutMs = CANCEL_JOB_TIMEOUT_MS,
        )
    }

    /**
     * Streams live Google Drive transfer job snapshots/updates from `/pc/api/space/jobs/stream`
     * with connection lifecycle events and exponential reconnect backoff.
     */
    fun streamSpaceJobs(): Flow<SpaceJobsStreamUpdate> {
        return api.sseStatusFlow(
            url = SSE_JOBS_PATH,
            autoReconnect = true,
            initialRetryMs = SSE_INITIAL_RETRY_MS,
            maxRetryMs = SSE_MAX_RETRY_MS,
        ).mapNotNull { msg ->
            when (msg) {
                is SseMessage.Connected -> SpaceJobsStreamUpdate.Connected
                is SseMessage.Reconnecting -> SpaceJobsStreamUpdate.Disconnected
                is SseMessage.Event -> {
                    val ev = msg.event
                    if (ev.event != "snapshot" && ev.event != "jobs") {
                        return@mapNotNull null
                    }
                    if (ev.data.isBlank()) return@mapNotNull null
                    val jobs = runCatching {
                        api.json.decodeFromString(SpaceJobsSsePayload.serializer(), ev.data).jobs
                    }.getOrNull() ?: return@mapNotNull null
                    SpaceJobsStreamUpdate.Jobs(jobs)
                }
            }
        }
    }

    fun getCachedSpace(scope: SpaceScope): SpaceResponse? {
        val key = if (scope == SpaceScope.ALL) CACHE_KEY_ALL else CACHE_KEY_WATCHED
        val raw = cache?.getString(key) ?: return null
        return runCatching {
            api.json.decodeFromString(SpaceResponse.serializer(), raw)
        }.getOrNull()
    }

    fun getCachedDrive(): DriveStatusResponse? {
        val raw = cache?.getString(CACHE_KEY_DRIVE) ?: return null
        return runCatching {
            api.json.decodeFromString(DriveStatusResponse.serializer(), raw)
        }.getOrNull()
    }

    private fun saveCachedSpace(scope: SpaceScope, data: SpaceResponse) {
        val localCache = cache ?: return
        val key = if (scope == SpaceScope.ALL) CACHE_KEY_ALL else CACHE_KEY_WATCHED
        runCatching {
            localCache.putString(key, api.json.encodeToString(SpaceResponse.serializer(), data))
        }
    }

    private fun saveCachedDrive(data: DriveStatusResponse) {
        val localCache = cache ?: return
        runCatching {
            localCache.putString(CACHE_KEY_DRIVE, api.json.encodeToString(DriveStatusResponse.serializer(), data))
        }
    }
}
