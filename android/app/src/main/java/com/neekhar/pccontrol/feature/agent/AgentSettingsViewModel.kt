package com.neekhar.pccontrol.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AgentSettingsUiState(
    val agentState: AgentState? = null,
    val pendingEngine: String? = null,
    val agentError: String? = null,
    val usage: AgentUsageResponse? = null,
    val usageLoading: Boolean = true,
    val usageError: String? = null,
    val settings: AgentSettings? = null,
    val settingsError: String? = null,
    val saving: Boolean = false,
    val restartingClaude: Boolean = false,
    val resetting: Boolean = false,
    val noticeMessage: String? = null,
    val isRefreshing: Boolean = false,
) {
    val selectedEngine: String
        get() = pendingEngine ?: agentState?.engine ?: "claude"

    val isSwitching: Boolean
        get() = pendingEngine != null || agentState?.switching == true

    val isUnavailable: Boolean
        get() = agentState != null && !agentState.available

    val isSelectedActive: Boolean
        get() = agentState?.let {
            if (selectedEngine == "claude") it.claudeActive else it.geminiActive
        } ?: false
}

class AgentSettingsViewModel(
    private val api: PcApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentSettingsUiState())
    val uiState: StateFlow<AgentSettingsUiState> = _uiState.asStateFlow()

    private var switchPollJob: Job? = null
    private var staleRepollJob: Job? = null
    private var staleAttempts = 0

    init {
        refreshAll(forceUsage = false)
    }

    fun refreshAll(forceUsage: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                coroutineScope {
                    launch { loadAgentStateSuspend() }
                    launch { loadSettingsSuspend() }
                    launch { loadUsageSuspend(force = forceUsage, silent = false) }
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadAgentState() {
        viewModelScope.launch { loadAgentStateSuspend() }
    }

    private suspend fun loadAgentStateSuspend() {
        if (_uiState.value.pendingEngine != null) return
        when (val res = api.getJson<AgentState>("/pc/api/agent")) {
            is ApiResult.Success -> {
                if (_uiState.value.pendingEngine == null) {
                    _uiState.update { it.copy(agentState = res.data, agentError = null) }
                }
            }
            is ApiResult.Error -> {
                if (_uiState.value.pendingEngine == null) {
                    _uiState.update { it.copy(agentError = res.message) }
                }
            }
        }
    }

    fun loadSettings() {
        viewModelScope.launch { loadSettingsSuspend() }
    }

    private suspend fun loadSettingsSuspend() {
        when (val res = api.getJson<AgentSettings>("/pc/api/agent/settings")) {
            is ApiResult.Success -> _uiState.update { it.copy(settings = res.data, settingsError = null) }
            is ApiResult.Error -> _uiState.update { it.copy(settingsError = res.message) }
        }
    }

    fun loadUsage(force: Boolean = false, silent: Boolean = false) {
        viewModelScope.launch { loadUsageSuspend(force = force, silent = silent) }
    }

    private suspend fun loadUsageSuspend(force: Boolean = false, silent: Boolean = false) {
        staleRepollJob?.cancel()
        if (force) staleAttempts = 0
        if (!silent) _uiState.update { it.copy(usageLoading = true) }
        val path = if (force) "/pc/api/agent/usage?refresh=1" else "/pc/api/agent/usage"
        when (val res = api.getJson<AgentUsageResponse>(path)) {
            is ApiResult.Success -> {
                _uiState.update {
                    it.copy(usage = res.data, usageLoading = false, usageError = null)
                }
                // Reload settings so newly discovered emails appear in the Gemini account picker.
                loadSettingsSuspend()
                if (res.data.stale == true && staleAttempts < STALE_REPOLL_MAX) {
                    staleAttempts++
                    staleRepollJob = viewModelScope.launch {
                        delay(STALE_REPOLL_MS)
                        loadUsageSuspend(force = false, silent = true)
                    }
                } else if (res.data.stale != true) {
                    staleAttempts = 0
                }
            }
            is ApiResult.Error -> {
                _uiState.update {
                    it.copy(usageLoading = false, usageError = res.message)
                }
            }
        }
    }

    fun selectEngine(target: String) {
        val cur = _uiState.value.agentState ?: return
        if (!cur.available || _uiState.value.pendingEngine != null || cur.switching) return
        val targetActive = if (target == "claude") cur.claudeActive else cur.geminiActive
        if (target == cur.engine && targetActive) return

        switchPollJob?.cancel()
        _uiState.update { it.copy(pendingEngine = target, agentError = null) }
        switchPollJob = viewModelScope.launch {
            when (val postRes = api.postJson<SetAgentEngineRequest, AgentState>("/pc/api/agent", SetAgentEngineRequest(target))) {
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(pendingEngine = null, agentError = postRes.message)
                    }
                    return@launch
                }
                is ApiResult.Success -> Unit
            }

            var attempts = 0
            while (isActive && attempts < AGENT_POLL_MAX_ATTEMPTS) {
                delay(AGENT_POLL_MS)
                attempts++
                when (val pollRes = api.getJson<AgentState>("/pc/api/agent")) {
                    is ApiResult.Success -> {
                        val next = pollRes.data
                        if (!next.available) {
                            _uiState.update {
                                it.copy(agentState = next, pendingEngine = null, agentError = "Agent switch not installed")
                            }
                            return@launch
                        }
                        val active = if (target == "claude") next.claudeActive else next.geminiActive
                        if (active) {
                            _uiState.update {
                                it.copy(agentState = next, pendingEngine = null, agentError = null)
                            }
                            return@launch
                        }
                    }
                    is ApiResult.Error -> Unit // transient error during switch; keep polling
                }
            }

            val label = if (target == "claude") "Claude" else "Gemini"
            _uiState.update {
                it.copy(pendingEngine = null, agentError = "Switch to $label timed out")
            }
        }
    }

    fun updateSettings(patch: AgentSettingsPatch) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, settingsError = null) }
        viewModelScope.launch {
            when (val res = api.postJson<AgentSettingsPatch, AgentSettings>("/pc/api/agent/settings", patch)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(settings = res.data, saving = false, settingsError = null)
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(saving = false, settingsError = res.message)
                }
            }
        }
    }

    fun restartClaudeAgent() {
        if (_uiState.value.restartingClaude) return
        _uiState.update { it.copy(restartingClaude = true) }
        viewModelScope.launch {
            when (val res = api.postJson<Map<String, String>, Map<String, String>>(
                "/pc/api/services/pc-agent.service/restart",
                emptyMap(),
            )) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(restartingClaude = false, noticeMessage = "Claude agent restarted.") }
                    delay(1800L)
                    loadAgentState()
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(restartingClaude = false, settingsError = "Restart failed: ${res.message}") }
                }
            }
        }
    }

    fun resetConversation() {
        if (_uiState.value.resetting) return
        _uiState.update { it.copy(resetting = true) }
        viewModelScope.launch {
            when (val res = api.postJson<Map<String, String>, ResetAgentResponse>("/pc/api/agent/reset", emptyMap())) {
                is ApiResult.Success -> {
                    val msg = res.data.message.ifBlank { "Next message will start fresh." }
                    _uiState.update { it.copy(resetting = false, noticeMessage = msg) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(resetting = false, settingsError = "Reset failed: ${res.message}") }
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    override fun onCleared() {
        switchPollJob?.cancel()
        staleRepollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val AGENT_POLL_MS = 2_000L
        private const val AGENT_POLL_MAX_ATTEMPTS = 15
        private const val STALE_REPOLL_MS = 2_500L
        private const val STALE_REPOLL_MAX = 4
    }
}
