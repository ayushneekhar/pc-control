package com.neekhar.pccontrol.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.network.getOrNull
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.feature.home.CatCamState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ServicesViewModel(
    private val api: PcApiClient,
    private val cache: LocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadCachedInitialState())
    val uiState: StateFlow<ServicesUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    private fun loadCachedInitialState(): ServicesUiState {
        val cachedHealth = cache.get<HealthResponse>(CACHE_HEALTH)
        val cachedCatCam = cache.get<CatCamState>(CACHE_CATCAM)
        return ServicesUiState(
            health = cachedHealth,
            catCamEnabled = cachedCatCam?.enabled ?: true,
        )
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            var firstTick = true
            while (isActive) {
                fetchHealth(includeCatCam = firstTick)
                firstTick = false
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                fetchHealth(includeCatCam = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun selectFilter(group: ServiceGroup) {
        _uiState.update { it.copy(filter = group) }
    }

    private suspend fun fetchHealth(includeCatCam: Boolean) = coroutineScope {
        val healthDef = async { api.getJson<HealthResponse>("/pc/api/health", timeoutMs = 15_000L) }
        val catCamDef = if (includeCatCam) {
            async { api.getJson<CatCamState>("/pc/api/catcam").getOrNull() }
        } else {
            null
        }

        val healthRes = healthDef.await()
        val catCam = catCamDef?.await()

        if (catCam != null) {
            cache.putAsync(CACHE_CATCAM, catCam)
        }

        when (healthRes) {
            is ApiResult.Success -> {
                cache.putAsync(CACHE_HEALTH, healthRes.data)
                _uiState.update { cur ->
                    cur.copy(
                        health = healthRes.data,
                        error = null,
                        catCamEnabled = catCam?.enabled ?: cur.catCamEnabled,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { cur ->
                    cur.copy(
                        error = healthRes.message,
                        catCamEnabled = catCam?.enabled ?: cur.catCamEnabled,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val CACHE_HEALTH = "health:report"
        private const val CACHE_CATCAM = "catcam:state"

        fun factory(api: PcApiClient, cache: LocalCache): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ServicesViewModel(api, cache) as T
                }
            }
    }
}
