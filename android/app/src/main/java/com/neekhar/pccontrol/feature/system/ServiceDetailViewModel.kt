package com.neekhar.pccontrol.feature.system

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder

enum class ServiceAction(val pathSegment: String, val label: String) {
    START("start", "Start"),
    STOP("stop", "Stop"),
    RESTART("restart", "Restart"),
}

@Immutable
data class ServiceDetailUiState(
    val unit: String,
    val service: ServiceUnit? = null,
    val logs: List<String>? = null,
    val logsError: String? = null,
    val logsLoading: Boolean = false,
    val logFilter: String = "",
    val busyAction: ServiceAction? = null,
    val actionError: String? = null,
) {
    val displayName: String get() = unit.removeSuffix(".service")
    val isProtected: Boolean get() = ServiceHelpers.isProtectedUnit(unit)
    val isActive: Boolean get() = service?.active == "active"

    val filteredLogs: List<String>
        get() {
            val all = logs ?: return emptyList()
            val q = logFilter.trim()
            if (q.isEmpty()) return all
            return all.filter { it.contains(q, ignoreCase = true) }
        }
}

class ServiceDetailViewModel(
    private val unit: String,
    private val api: PcApiClient,
    private val cache: LocalCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadCachedInitialState())
    val uiState: StateFlow<ServiceDetailUiState> = _uiState.asStateFlow()

    private fun loadCachedInitialState(): ServiceDetailUiState {
        val cachedService = cache.get<HealthResponse>("health:report")
            ?.services
            ?.units
            ?.firstOrNull { it.unit == unit }
        return ServiceDetailUiState(unit = unit, service = cachedService)
    }

    fun loadAll() {
        if (unit.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(logsLoading = true) }
            coroutineScope {
                val statusJob = async { fetchStatusInternal() }
                val logsJob = async { fetchLogsInternal() }
                statusJob.await()
                logsJob.await()
            }
            _uiState.update { it.copy(logsLoading = false) }
        }
    }

    fun refreshLogs() {
        if (unit.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(logsLoading = true) }
            fetchLogsInternal()
            _uiState.update { it.copy(logsLoading = false) }
        }
    }

    fun setLogFilter(query: String) {
        _uiState.update { it.copy(logFilter = query) }
    }

    fun runAction(action: ServiceAction) {
        if (unit.isBlank() || _uiState.value.busyAction != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyAction = action, actionError = null) }
            val encoded = URLEncoder.encode(unit, "UTF-8")
            when (
                val res = api.postJson<Unit>(
                    pathOrUrl = "/pc/api/services/$encoded/${action.pathSegment}",
                    bodyJson = "{}",
                )
            ) {
                is ApiResult.Success -> {
                    delay(1_500L)
                    coroutineScope {
                        val s = async { fetchStatusInternal() }
                        val l = async { fetchLogsInternal() }
                        s.await()
                        l.await()
                    }
                    _uiState.update { it.copy(busyAction = null) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(busyAction = null, actionError = res.message) }
                }
            }
        }
    }

    private suspend fun fetchStatusInternal() {
        val res = api.getJson<ServicesListResponse>("/pc/api/services")
        if (res is ApiResult.Success) {
            val found = res.data.services.firstOrNull { it.unit == unit }
            if (found != null) {
                _uiState.update { it.copy(service = found) }
            }
        }
    }

    private suspend fun fetchLogsInternal() {
        val encoded = URLEncoder.encode(unit, "UTF-8")
        when (val res = api.getJson<ServiceLogsResponse>("/pc/api/services/$encoded/logs?n=120")) {
            is ApiResult.Success -> {
                _uiState.update { it.copy(logs = res.data.lines, logsError = null) }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(logsError = res.message) }
            }
        }
    }

    companion object {
        fun factory(unit: String, api: PcApiClient, cache: LocalCache): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ServiceDetailViewModel(unit, api, cache) as T
                }
            }
    }
}
