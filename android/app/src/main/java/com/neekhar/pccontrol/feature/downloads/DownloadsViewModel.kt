package com.neekhar.pccontrol.feature.downloads

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.feature.home.CancelSpaceJobResponse
import com.neekhar.pccontrol.feature.home.DriveStatusResponse
import com.neekhar.pccontrol.feature.home.SpaceJobsSsePayload
import com.neekhar.pccontrol.feature.home.TransferJob
import com.neekhar.pccontrol.feature.home.activeTransferJobs
import com.neekhar.pccontrol.feature.home.mergeEnqueuedJobs
import com.neekhar.pccontrol.feature.home.visibleDownloadDriveJobs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class DownloadsUiState(
    val baseUrl: String = "",
    val data: DownloadsAllResponse? = null,
    val driveJobs: List<TransferJob> = emptyList(),
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val busyTorrentHash: String? = null,
    val cancellingJobId: String? = null,
    val altSpeedPending: Boolean? = null,
    val altSpeedBusy: Boolean = false,
    val retryingArrApp: String? = null,
    val jellyfinBusy: Boolean = false,
    val jellyfinRefreshed: Boolean = false,
) {
    val altSpeedEnabled: Boolean get() = altSpeedPending ?: data?.altSpeed ?: false
    val torrents: List<TorrentItem> get() = data?.torrents.orEmpty()
    val hasStuckArr: Boolean
        get() = (data?.radarr?.needsAction ?: 0) > 0 || (data?.sonarr?.needsAction ?: 0) > 0
    val visibleDriveJobs: List<TransferJob> get() = visibleDownloadDriveJobs(driveJobs)
    val activeDriveJobs: List<TransferJob> get() = activeTransferJobs(visibleDriveJobs)
    val activeDriveSpeedBps: Double
        get() = activeDriveJobs.sumOf { if (it.status == "copying" && it.speedBps > 0.0) it.speedBps else 0.0 }
}

class DownloadsViewModel(
    private val api: PcApiClient,
    private val cache: LocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadCachedInitialState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val driveSeq = AtomicLong(0L)
    private var pollingJob: Job? = null
    private var sseJob: Job? = null

    private fun loadCachedInitialState(): DownloadsUiState {
        val cachedDownloads = cache.get<DownloadsAllResponse>(CACHE_DOWNLOADS_ALL)
        val cachedDriveJobs = cache.get<DriveStatusResponse>(CACHE_SPACE_DRIVE)?.jobs
            ?: cache.get<List<TransferJob>>(CACHE_DRIVE_JOBS)
            ?: emptyList()
        return DownloadsUiState(
            baseUrl = api.baseUrl,
            data = cachedDownloads,
            driveJobs = cachedDriveJobs,
        )
    }

    fun onResume() {
        _uiState.update { it.copy(baseUrl = api.baseUrl) }
        startPolling()
        startSpaceJobsStream()
        viewModelScope.launch { fetchDriveJobs() }
    }

    fun onPause() {
        pollingJob?.cancel()
        pollingJob = null
        sseJob?.cancel()
        sseJob = null
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, baseUrl = api.baseUrl) }
            try {
                coroutineScope {
                    val d = async { fetchDownloadsAll() }
                    val j = async { fetchDriveJobs() }
                    d.await()
                    j.await()
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun toggleAltSpeed(enabled: Boolean) {
        if (_uiState.value.altSpeedBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(altSpeedPending = enabled, altSpeedBusy = true) }
            when (
                val res = api.postJson<AltSpeedRequest, AltSpeedResponse>(
                    pathOrUrl = "/pc/api/downloads/alt-speed",
                    body = AltSpeedRequest(enabled = enabled),
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.update { cur ->
                        cur.copy(
                            data = cur.data?.copy(altSpeed = res.data.altSpeed),
                            error = null,
                            altSpeedBusy = false,
                            altSpeedPending = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { cur ->
                        cur.copy(
                            error = res.message,
                            altSpeedBusy = false,
                            altSpeedPending = null,
                        )
                    }
                }
            }
        }
    }

    fun togglePause(item: TorrentItem) {
        if (_uiState.value.busyTorrentHash != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyTorrentHash = item.hash) }
            val path = if (item.paused) "/pc/api/downloads/resume" else "/pc/api/downloads/pause"
            when (
                val res = api.postJson<TorrentHashRequest, OkResponse>(
                    pathOrUrl = path,
                    body = TorrentHashRequest(hash = item.hash),
                )
            ) {
                is ApiResult.Success -> fetchDownloadsAll()
                is ApiResult.Error -> _uiState.update { it.copy(error = res.message) }
            }
            _uiState.update { it.copy(busyTorrentHash = null) }
        }
    }

    fun deleteTorrent(hash: String, deleteFiles: Boolean) {
        if (_uiState.value.busyTorrentHash != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyTorrentHash = hash) }
            when (
                val res = api.postJson<TorrentDeleteRequest, OkResponse>(
                    pathOrUrl = "/pc/api/downloads/delete",
                    body = TorrentDeleteRequest(hash = hash, deleteFiles = deleteFiles),
                )
            ) {
                is ApiResult.Success -> fetchDownloadsAll()
                is ApiResult.Error -> _uiState.update { it.copy(error = res.message) }
            }
            _uiState.update { it.copy(busyTorrentHash = null) }
        }
    }

    fun cancelDriveJob(jobId: String) {
        if (_uiState.value.cancellingJobId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(cancellingJobId = jobId) }
            val encoded = URLEncoder.encode(jobId, "UTF-8")
            when (
                val res = api.postJson<CancelSpaceJobResponse>(
                    pathOrUrl = "/pc/api/space/jobs/$encoded/cancel",
                    bodyJson = "{}",
                    timeoutMs = 15_000L,
                )
            ) {
                is ApiResult.Success -> {
                    val updated = res.data.job
                    if (updated != null) {
                        driveSeq.incrementAndGet()
                        _uiState.update { cur ->
                            val merged = mergeEnqueuedJobs(cur.driveJobs, listOf(updated))
                            cur.copy(driveJobs = merged, error = null)
                        }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = res.message) }
                }
            }
            _uiState.update { it.copy(cancellingJobId = null) }
        }
    }

    fun retryArrImport(app: String) {
        if (_uiState.value.retryingArrApp != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(retryingArrApp = app) }
            when (
                val res = api.postJson<ArrRetryRequest, OkResponse>(
                    pathOrUrl = "/pc/api/arr/retry",
                    body = ArrRetryRequest(app = app),
                )
            ) {
                is ApiResult.Success -> fetchDownloadsAll()
                is ApiResult.Error -> _uiState.update { it.copy(error = res.message) }
            }
            _uiState.update { it.copy(retryingArrApp = null) }
        }
    }

    fun refreshJellyfinLibrary() {
        if (_uiState.value.jellyfinBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(jellyfinBusy = true, jellyfinRefreshed = false) }
            when (
                val res = api.postJson<OkResponse>(
                    pathOrUrl = "/pc/api/jellyfin/refresh",
                    bodyJson = "{}",
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(jellyfinBusy = false, jellyfinRefreshed = true, error = null) }
                    delay(2_500L)
                    _uiState.update { it.copy(jellyfinRefreshed = false) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(jellyfinBusy = false, error = res.message) }
                }
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchDownloadsAll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startSpaceJobsStream() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            api.sseFlow("/pc/api/space/jobs/stream")
                .catch { /* sseFlow reconnects automatically */ }
                .collect { event ->
                    if (event.event == "snapshot" || event.event == "jobs") {
                        val payload = runCatching {
                            api.json.decodeFromString<SpaceJobsSsePayload>(event.data)
                        }.getOrNull() ?: return@collect
                        driveSeq.incrementAndGet()
                        cache.putAsync(CACHE_DRIVE_JOBS, payload.jobs)
                        _uiState.update { it.copy(driveJobs = payload.jobs) }
                    }
                }
        }
    }

    private suspend fun fetchDownloadsAll() {
        when (val res = api.getJson<DownloadsAllResponse>("/pc/api/downloads/all", timeoutMs = 15_000L)) {
            is ApiResult.Success -> {
                cache.putAsync(CACHE_DOWNLOADS_ALL, res.data)
                _uiState.update { it.copy(data = res.data, error = null) }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }

    private suspend fun fetchDriveJobs() {
        val seqBefore = driveSeq.get()
        val res = api.getJson<DriveStatusResponse>("/pc/api/space/drive", timeoutMs = 15_000L)
        if (res is ApiResult.Success) {
            cache.putAsync(CACHE_SPACE_DRIVE, res.data)
            if (driveSeq.compareAndSet(seqBefore, seqBefore + 1L)) {
                _uiState.update { it.copy(driveJobs = res.data.jobs) }
            }
        }
    }

    override fun onCleared() {
        onPause()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L
        private const val CACHE_DOWNLOADS_ALL = "downloads:all"
        private const val CACHE_SPACE_DRIVE = "space:drive"
        private const val CACHE_DRIVE_JOBS = "home:driveJobs"

        fun factory(api: PcApiClient, cache: LocalCache): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadsViewModel(api, cache) as T
                }
            }
    }
}
