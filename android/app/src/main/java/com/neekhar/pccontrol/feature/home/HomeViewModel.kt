package com.neekhar.pccontrol.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.network.getOrNull
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.feature.system.HealthResponse
import com.neekhar.pccontrol.feature.system.VitalsResponse
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

@Immutable
data class HomeUiState(
    val baseUrl: String = "",
    val info: InfoResponse? = null,
    val vitals: VitalsResponse? = null,
    val health: HealthResponse? = null,
    val routes: List<RouteItem> = emptyList(),
    val detectState: DetectState? = null,
    val downloads: DownloadsReply? = null,
    val driveJobs: List<TransferJob> = emptyList(),
    val catCam: CatCamState = CatCamState(enabled = true),
    val catCamBusy: Boolean = false,
    val catCamError: String? = null,
    val keyring: KeyringStatus? = null,
    val camTick: Long = 1L,
    val loaded: Boolean = false,
    val offlineError: Boolean = false,
    val isRefreshing: Boolean = false,
) {
    val catCamEnabled: Boolean get() = catCam.enabled
    val isOffline: Boolean get() = loaded && offlineError
    val problemCount: Int get() = health?.problems?.size ?: 0
    val transfersSummary: HomeTransfersSummary
        get() = summarizeHomeTransfers(downloads, driveJobs)
    val latestSighting: CatSighting?
        get() = lastCatSighting(detectState)
    val hddFreeBytes: Long?
        get() = health?.disks?.disks
            ?.firstOrNull { it.kind.equals("HDD", ignoreCase = true) }
            ?.let { (it.total - it.used).coerceAtLeast(0L) }
}

class HomeViewModel(
    private val api: PcApiClient,
    private val cache: LocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadCachedInitialState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var camTickJob: Job? = null
    private var sseJobsJob: Job? = null
    private var refreshJob: Job? = null

    private fun loadCachedInitialState(): HomeUiState {
        val cachedInfo = cache.get<InfoResponse>(CACHE_INFO)
        val cachedVitals = cache.get<VitalsResponse>(CACHE_VITALS)
        val cachedHealth = cache.get<HealthResponse>(CACHE_HEALTH)
        val cachedRoutes = cache.get<List<RouteItem>>(CACHE_ROUTES).orEmpty()
        val cachedDet = cache.get<DetectState>(CACHE_DET)
        val cachedDownloads = cache.get<DownloadsReply>(CACHE_DOWNLOADS)
        val cachedCatCam = cache.get<CatCamState>(CACHE_CATCAM) ?: CatCamState(enabled = true)
        val cachedJobs = cache.get<List<TransferJob>>(CACHE_DRIVE_JOBS)
            ?: cache.get<DriveStatusResponse>(CACHE_SPACE_DRIVE)?.jobs
            ?: emptyList()
        return HomeUiState(
            baseUrl = api.baseUrl,
            info = cachedInfo,
            vitals = cachedVitals,
            health = cachedHealth,
            routes = cachedRoutes,
            detectState = cachedDet,
            downloads = cachedDownloads,
            driveJobs = cachedJobs,
            catCam = cachedCatCam,
        )
    }

    fun onResume() {
        _uiState.update { it.copy(baseUrl = api.baseUrl) }
        refresh(isPullToRefresh = false)
        startCamTicker()
        startSpaceJobsStream()
    }

    fun onPause() {
        camTickJob?.cancel()
        camTickJob = null
        sseJobsJob?.cancel()
        sseJobsJob = null
    }

    fun refresh(isPullToRefresh: Boolean = true) {
        if (refreshJob?.isActive == true && !isPullToRefresh) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (isPullToRefresh) {
                _uiState.update { it.copy(isRefreshing = true, baseUrl = api.baseUrl) }
            }
            try {
                fetchAll()
            } finally {
                if (isPullToRefresh) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun enableCatCams() {
        if (_uiState.value.catCamBusy) return
        viewModelScope.launch {
            val previous = _uiState.value.catCam
            _uiState.update {
                it.copy(
                    catCam = previous.copy(enabled = true),
                    catCamBusy = true,
                    catCamError = null,
                )
            }
            when (
                val res = api.postJson<CatCamToggleRequest, CatCamState>(
                    pathOrUrl = "/pc/api/catcam",
                    body = CatCamToggleRequest(enabled = true),
                    timeoutMs = 30_000L,
                )
            ) {
                is ApiResult.Success -> {
                    cache.putAsync(CACHE_CATCAM, res.data)
                    _uiState.update {
                        it.copy(
                            catCam = res.data,
                            catCamBusy = false,
                            catCamError = null,
                            camTick = it.camTick + 1L,
                        )
                    }
                    refresh(isPullToRefresh = false)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            catCam = previous,
                            catCamBusy = false,
                            catCamError = res.message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchAll() = coroutineScope {
        val wasCamEnabled = _uiState.value.catCamEnabled

        val infoDef = async { api.getJson<InfoResponse>("/pc/api/info").getOrNull() }
        val vitalsDef = async { api.getJson<VitalsResponse>("/pc/api/vitals").getOrNull() }
        val healthDef = async { api.getJson<HealthResponse>("/pc/api/health").getOrNull() }
        val routesDef = async { api.getJson<RoutesResponse>("/pc/api/routes").getOrNull() }
        val downloadsDef = async { api.getJson<DownloadsReply>("/pc/api/downloads").getOrNull() }
        val catCamDef = async { api.getJson<CatCamState>("/pc/api/catcam").getOrNull() }
        val keyringDef = async { api.getJson<KeyringStatus>("/pc/api/keyring").getOrNull() }
        val driveDef = async { api.getJson<DriveStatusResponse>("/pc/api/space/drive", timeoutMs = 15_000L).getOrNull() }
        val detDef = async {
            if (wasCamEnabled) api.getJson<DetectState>("/pc/api/detect/state").getOrNull() else null
        }

        val info = infoDef.await()
        val vitals = vitalsDef.await()
        val health = healthDef.await()
        val routesResp = routesDef.await()
        val downloads = downloadsDef.await()
        val catCam = catCamDef.await()
        val keyring = keyringDef.await()
        val drive = driveDef.await()
        val det = detDef.await()

        val visibleRoutes = routesResp?.routes?.filter { !it.hidden }

        if (info != null) cache.putAsync(CACHE_INFO, info)
        if (vitals != null) cache.putAsync(CACHE_VITALS, vitals)
        if (health != null) cache.putAsync(CACHE_HEALTH, health)
        if (visibleRoutes != null) cache.putAsync(CACHE_ROUTES, visibleRoutes)
        if (det != null) cache.putAsync(CACHE_DET, det)
        if (downloads != null) cache.putAsync(CACHE_DOWNLOADS, downloads)
        if (catCam != null) cache.putAsync(CACHE_CATCAM, catCam)
        if (drive != null) {
            cache.putAsync(CACHE_DRIVE_JOBS, drive.jobs)
        }

        val offline = info == null && vitals == null && routesResp == null

        _uiState.update { cur ->
            cur.copy(
                baseUrl = api.baseUrl,
                info = info ?: cur.info,
                vitals = vitals ?: cur.vitals,
                health = health ?: cur.health,
                routes = visibleRoutes ?: cur.routes,
                detectState = det ?: cur.detectState,
                downloads = downloads ?: cur.downloads,
                driveJobs = drive?.jobs ?: cur.driveJobs,
                catCam = if (cur.catCamBusy) cur.catCam else (catCam ?: cur.catCam),
                keyring = keyring ?: cur.keyring,
                loaded = true,
                offlineError = offline,
            )
        }
    }

    private fun startCamTicker() {
        camTickJob?.cancel()
        camTickJob = viewModelScope.launch {
            while (isActive) {
                delay(CAM_REFRESH_MS)
                if (_uiState.value.catCamEnabled) {
                    _uiState.update { it.copy(camTick = it.camTick + 1L) }
                }
            }
        }
    }

    private fun startSpaceJobsStream() {
        sseJobsJob?.cancel()
        sseJobsJob = viewModelScope.launch {
            api.sseFlow("/pc/api/space/jobs/stream")
                .catch { /* sseFlow handles automatic reconnects; ignore terminal errors */ }
                .collect { event ->
                    if (event.event == "snapshot" || event.event == "jobs") {
                        val payload = runCatching {
                            api.json.decodeFromString<SpaceJobsSsePayload>(event.data)
                        }.getOrNull() ?: return@collect
                        cache.putAsync(CACHE_DRIVE_JOBS, payload.jobs)
                        _uiState.update { it.copy(driveJobs = payload.jobs) }
                    }
                }
        }
    }

    override fun onCleared() {
        onPause()
        refreshJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val CAM_REFRESH_MS = 1_500L
        private const val CACHE_INFO = "home:info"
        private const val CACHE_VITALS = "home:vitals"
        private const val CACHE_HEALTH = "health:report"
        private const val CACHE_ROUTES = "home:routes"
        private const val CACHE_DET = "home:det"
        private const val CACHE_DOWNLOADS = "home:downloads"
        private const val CACHE_CATCAM = "catcam:state"
        private const val CACHE_DRIVE_JOBS = "home:driveJobs"
        private const val CACHE_SPACE_DRIVE = "space:drive"

        fun factory(api: PcApiClient, cache: LocalCache): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(api, cache) as T
                }
            }
    }
}
