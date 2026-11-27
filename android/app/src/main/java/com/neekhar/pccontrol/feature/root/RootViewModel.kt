package com.neekhar.pccontrol.feature.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RootAlert(
    val title: String,
    val message: String,
    val requiresReenroll: Boolean = false,
)

data class RootUiState(
    val rootdState: RootdState = RootdState.UNKNOWN,
    val isLive: Boolean = false,
    val enrolled: Boolean = false,
    val deviceFingerprint: String? = null,
    val pending: List<RootRequest> = emptyList(),
    val history: List<RootRequest> = emptyList(),
    val historyError: String? = null,
    val isRefreshing: Boolean = false,
    val busyKey: String? = null,
    val selectedId: String? = null,
    val detailRequest: RootRequest? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val alert: RootAlert? = null,
) {
    val waitingCount: Int get() = pending.count { it.status == "pending" }
}

class RootViewModel(
    private val api: PcApiClient,
    private val signer: RootSignerBridge,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RootUiState(
            enrolled = !signer.deviceFingerprint().isNullOrBlank() && signer.hasKey(),
            deviceFingerprint = signer.deviceFingerprint(),
        ),
    )
    val uiState: StateFlow<RootUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPolling()
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshEnrollmentState()
                fetchPending()
                if (_uiState.value.rootdState == RootdState.OK) {
                    fetchHistory()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                refreshEnrollmentState()
                fetchPending()
                if (_uiState.value.rootdState == RootdState.OK) {
                    fetchHistory()
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refreshEnrollmentState() {
        val fp = signer.deviceFingerprint()
        val enrolled = !fp.isNullOrBlank() && signer.hasKey()
        _uiState.update { it.copy(enrolled = enrolled, deviceFingerprint = fp) }
    }

    private suspend fun fetchPending() {
        when (val res = api.getJson<RootPendingResponse>("/pc/api/root/pending")) {
            is ApiResult.Success -> {
                val sorted = res.data.requests
                    .filter { it.status == "pending" || it.status == "running" }
                    .sortedByDescending { it.created }
                _uiState.update {
                    it.copy(
                        rootdState = RootdState.OK,
                        isLive = true,
                        pending = sorted,
                    )
                }
            }
            is ApiResult.Error -> {
                val nextState = when {
                    res.statusCode == 503 && res.message.contains("not_installed", ignoreCase = true) ->
                        RootdState.NOT_INSTALLED
                    res.statusCode == 503 -> RootdState.DOWN
                    else -> _uiState.value.rootdState
                }
                _uiState.update {
                    it.copy(
                        rootdState = nextState,
                        isLive = false,
                        pending = if (res.statusCode == 503) emptyList() else it.pending,
                    )
                }
            }
        }
    }

    private suspend fun fetchHistory() {
        when (val res = api.getJson<RootHistoryResponse>("/pc/api/root/history?limit=60")) {
            is ApiResult.Success -> {
                _uiState.update { it.copy(history = res.data.history, historyError = null) }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(historyError = res.message) }
            }
        }
    }

    fun openDetail(id: String) {
        _uiState.update {
            val cached = it.pending.find { r -> r.id == id } ?: it.history.find { r -> r.id == id }
            it.copy(
                selectedId = id,
                detailRequest = cached,
                detailLoading = cached == null,
                detailError = null,
            )
        }
        viewModelScope.launch {
            when (val res = api.getJson<RootRequestDetailResponse>("/pc/api/root/request/$id")) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        if (state.selectedId == id) {
                            state.copy(
                                detailRequest = res.data.request,
                                detailLoading = false,
                                detailError = null,
                            )
                        } else {
                            state
                        }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { state ->
                        if (state.selectedId == id) {
                            state.copy(detailLoading = false, detailError = res.message)
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    fun closeDetail() {
        _uiState.update {
            it.copy(selectedId = null, detailRequest = null, detailLoading = false, detailError = null)
        }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(alert = null) }
    }

    fun decide(req: RootRequest, decision: Decision) {
        if (_uiState.value.busyKey != null) return
        val fp = signer.deviceFingerprint()
        if (fp.isNullOrBlank() || !signer.hasKey()) {
            _uiState.update {
                it.copy(
                    alert = RootAlert(
                        title = if (decision == Decision.APPROVE) "Not approved" else "Not denied",
                        message = "This phone isn't set up for root approvals yet. Open Settings > Root approvals.",
                        requiresReenroll = true,
                    ),
                )
            }
            return
        }
        if ((req.expires * 1000.0).toLong() <= System.currentTimeMillis()) {
            _uiState.update {
                it.copy(
                    alert = RootAlert(
                        title = if (decision == Decision.APPROVE) "Not approved" else "Not denied",
                        message = "This request already expired.",
                    ),
                )
            }
            return
        }
        if (!RootPayload.argvLooksSafe(req.argv)) {
            _uiState.update {
                it.copy(
                    alert = RootAlert(
                        title = "Refused to sign",
                        message = "argv contains characters that cannot be shown faithfully.",
                    ),
                )
            }
            return
        }

        val key = "${req.id}:${decision.wireValue}"
        _uiState.update { it.copy(busyKey = key) }
        viewModelScope.launch {
            try {
                when (val signed = signer.signDecision(decision, req)) {
                    is SignOutcome.Cancelled -> Unit
                    is SignOutcome.Error -> {
                        _uiState.update {
                            it.copy(
                                alert = RootAlert(
                                    title = if (decision == Decision.APPROVE) "Not approved" else "Not denied",
                                    message = signed.message,
                                    requiresReenroll = signed.requiresReenroll,
                                ),
                            )
                        }
                    }
                    is SignOutcome.Signed -> {
                        val payload = RootDecideRequest(
                            id = req.id,
                            device = fp,
                            signature = signed.signatureBase64,
                        )
                        when (val res = api.postJson<RootDecideRequest, RootDecideResponse>(
                            "/pc/api/root/${decision.wireValue}",
                            payload,
                        )) {
                            is ApiResult.Success -> {
                                val updated = res.data.request
                                if (updated != null) {
                                    applyRequestUpdate(updated)
                                }
                                fetchPending()
                                fetchHistory()
                            }
                            is ApiResult.Error -> {
                                val reenroll = res.statusCode == 403
                                val msg = if (reenroll) {
                                    "rootd rejected the signature. Is this phone still enrolled? (sudo rootd-admin devices)"
                                } else {
                                    res.message
                                }
                                _uiState.update {
                                    it.copy(
                                        alert = RootAlert(
                                            title = if (decision == Decision.APPROVE) "Not approved" else "Not denied",
                                            message = msg,
                                            requiresReenroll = reenroll,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(busyKey = null) }
            }
        }
    }

    private fun applyRequestUpdate(updated: RootRequest) {
        _uiState.update { state ->
            val nextPending = state.pending
                .map { if (it.id == updated.id) updated else it }
                .filter { it.status == "pending" || it.status == "running" }
            val nextDetail = if (state.selectedId == updated.id) updated else state.detailRequest
            state.copy(pending = nextPending, detailRequest = nextDetail)
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
