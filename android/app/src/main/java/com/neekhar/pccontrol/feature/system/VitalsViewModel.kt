package com.neekhar.pccontrol.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VitalsViewModel(
    private val api: PcApiClient,
    private val cache: LocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadCachedInitialState())
    val uiState: StateFlow<VitalsUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var consecutiveFailures = 0

    private fun loadCachedInitialState(): VitalsUiState {
        val cached = cache.get<VitalsResponse>(CACHE_KEY)
        return VitalsUiState(
            vitals = cached,
            history = cached?.hist?.takeLast(MAX_HISTORY).orEmpty(),
        )
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            var requestFull = true
            while (isActive) {
                val wantProcs = _uiState.value.showProcesses
                val query = buildList {
                    if (requestFull) add("full=1")
                    if (wantProcs) add("procs=1")
                }.joinToString("&")
                val path = if (query.isNotEmpty()) "/vitals/api?$query" else "/vitals/api"

                when (val res = api.getJson<VitalsResponse>(path, timeoutMs = 10_000L)) {
                    is ApiResult.Success -> {
                        consecutiveFailures = 0
                        val data = res.data
                        val nextHist = if (requestFull) {
                            requestFull = false
                            data.hist.takeLast(MAX_HISTORY)
                        } else {
                            val point = data.hist.firstOrNull()
                            val prev = _uiState.value.history
                            if (point != null && (prev.isEmpty() || prev.last().t != point.t)) {
                                (prev + point).takeLast(MAX_HISTORY)
                            } else {
                                prev
                            }
                        }
                        cache.putAsync(CACHE_KEY, data.copy(hist = nextHist, procs = null))
                        _uiState.update { cur ->
                            cur.copy(
                                vitals = data,
                                history = nextHist,
                                error = null,
                                isLive = true,
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        consecutiveFailures += 1
                        if (consecutiveFailures > 2) {
                            _uiState.update { cur ->
                                cur.copy(
                                    error = res.message,
                                    isLive = false,
                                )
                            }
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun toggleProcesses(enabled: Boolean) {
        _uiState.update { it.copy(showProcesses = enabled) }
        if (enabled && pollingJob?.isActive == true) {
            // Trigger an immediate sample with procs=1
            viewModelScope.launch {
                val res = api.getJson<VitalsResponse>("/vitals/api?procs=1", timeoutMs = 10_000L)
                if (res is ApiResult.Success) {
                    _uiState.update { cur -> cur.copy(vitals = res.data, error = null, isLive = true) }
                }
            }
        }
    }

    fun setProcessQuery(query: String) {
        _uiState.update { it.copy(processQuery = query) }
    }

    fun selectSort(key: ProcessSortKey) {
        _uiState.update { cur ->
            if (cur.sortKey == key) {
                cur.copy(sortDescending = !cur.sortDescending)
            } else {
                cur.copy(sortKey = key, sortDescending = key.defaultDescending)
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_HISTORY = 300
        private const val CACHE_KEY = "vitals:snapshot"

        fun factory(api: PcApiClient, cache: LocalCache): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VitalsViewModel(api, cache) as T
                }
            }
    }
}
