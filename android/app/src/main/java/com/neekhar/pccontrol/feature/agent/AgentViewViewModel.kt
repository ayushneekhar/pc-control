package com.neekhar.pccontrol.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

enum class AgentViewTab(val label: String) {
    URLS("URLs"),
    RULES("Rules"),
}

data class AgentViewUiState(
    val tab: AgentViewTab = AgentViewTab.URLS,
    val master: AgentViewMasterState? = null,
    val viewState: AgentViewState? = null,
    val urls: List<AgentUrl>? = null,
    val searchQuery: String = "",
    val switching: Boolean = false,
    val switchingTarget: Boolean? = null,
    val busy: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class AgentRuleUiState(
    val loading: Boolean = true,
    val rule: AgentRule? = null,
    val draft: RuleDraft? = null,
    val ruleUrl: String = "",
    val originalMeta: OriginalMeta? = null,
    val busyAction: String? = null,
    val error: String? = null,
    val savedAndDone: Boolean = false,
)

class AgentViewViewModel(
    private val api: PcApiClient,
    autoLoad: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentViewUiState())
    val uiState: StateFlow<AgentViewUiState> = _uiState.asStateFlow()

    private val _ruleState = MutableStateFlow(AgentRuleUiState())
    val ruleState: StateFlow<AgentRuleUiState> = _ruleState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        if (autoLoad) {
            load()
        }
    }

    fun onScreenResume() {
        if (_uiState.value.switching || loadJob?.isActive == true) return
        load()
    }

    fun selectTab(tab: AgentViewTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        val master = _uiState.value.master
        if (master?.enabled != true || !master.webReachable) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250L)
            fetchUrls(query)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                loadInternal()
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun load() {
        if (_uiState.value.switching) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        when (val masterRes = api.getJson<AgentViewMasterState>("/pc/api/agentview/state")) {
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = "Agent view status unavailable: ${masterRes.message}") }
                return
            }
            is ApiResult.Success -> {
                val master = masterRes.data
                _uiState.update { it.copy(master = master) }
                if (!master.enabled) {
                    _uiState.update { it.copy(viewState = null, urls = null, error = null) }
                    return
                }
                if (!master.webReachable) {
                    _uiState.update { it.copy(error = null) }
                    return
                }
                fetchWebData(_uiState.value.searchQuery)
            }
        }
    }

    private suspend fun fetchWebData(query: String) {
        val stateRes = api.getJson<AgentViewState>("/agentview/api/state")
        val urlsRes = fetchUrlsResult(query)
        when {
            stateRes is ApiResult.Success && urlsRes is ApiResult.Success -> {
                _uiState.update { it.copy(viewState = stateRes.data, urls = urlsRes.data, error = null) }
            }
            stateRes is ApiResult.Error -> {
                _uiState.update { it.copy(error = "Agent view web UI is unreachable: ${stateRes.message}") }
            }
            urlsRes is ApiResult.Error -> {
                _uiState.update { it.copy(error = "Agent view web UI is unreachable: ${urlsRes.message}") }
            }
        }
    }

    private suspend fun fetchUrls(query: String) {
        when (val res = fetchUrlsResult(query)) {
            is ApiResult.Success -> _uiState.update { it.copy(urls = res.data, error = null) }
            is ApiResult.Error -> _uiState.update { it.copy(error = res.message) }
        }
    }

    private suspend fun fetchUrlsResult(query: String): ApiResult<List<AgentUrl>> {
        val trimmed = query.trim()
        val path = if (trimmed.isNotEmpty()) {
            val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(trimmed, "UTF-8") }
            "/agentview/api/urls?q=$encoded&limit=200"
        } else {
            "/agentview/api/urls?limit=200"
        }
        return api.getJson(path)
    }

    fun toggleMaster(enabled: Boolean) {
        if (_uiState.value.switching) return
        _uiState.update { it.copy(switching = true, switchingTarget = enabled, error = null) }
        viewModelScope.launch {
            try {
                when (val res = api.postJson<AgentViewToggleRequest, AgentViewMasterState>(
                    "/pc/api/agentview/toggle",
                    AgentViewToggleRequest(enabled),
                )) {
                    is ApiResult.Success -> {
                        val next = res.data
                        _uiState.update { it.copy(master = next) }
                        if (!next.enabled) {
                            _uiState.update { it.copy(viewState = null, urls = null, error = null) }
                        } else if (next.webReachable) {
                            fetchWebData(_uiState.value.searchQuery)
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update { it.copy(error = res.message) }
                    }
                }
            } finally {
                _uiState.update { it.copy(switching = false, switchingTarget = null) }
            }
        }
    }

    fun toggleRuleEnabled(rule: AgentRule, enabled: Boolean) {
        viewModelScope.launch {
            val result = agentViewWrite<AgentRule>(
                method = "PUT",
                path = "/agentview/api/rules/${urlEncode(rule.id)}",
                jsonBody = api.json.encodeToString(RuleUpdatePayload(enabled = enabled)),
            )
            result.onSuccess { updated ->
                _uiState.update { st ->
                    val vs = st.viewState ?: return@update st
                    st.copy(
                        viewState = vs.copy(rules = vs.rules.map { if (it.id == updated.id) updated else it }),
                        error = null,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = "Could not update rule: ${e.message}") }
            }
        }
    }

    fun moveRule(index: Int, delta: Int) {
        val currentRules = _uiState.value.viewState?.rules ?: return
        val target = index + delta
        if (target !in currentRules.indices || _uiState.value.busy) return
        val reordered = currentRules.toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = agentViewWrite<AgentViewState>(
                    method = "POST",
                    path = "/agentview/api/rules/order",
                    jsonBody = api.json.encodeToString(ReorderRulesRequest(reordered.map { it.id })),
                )
                result.onSuccess { updated ->
                    _uiState.update { it.copy(viewState = updated, error = null) }
                }.onFailure { e ->
                    _uiState.update { it.copy(error = "Could not reorder rules: ${e.message}") }
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun deleteRule(ruleId: String, onDeleted: (() -> Unit)? = null) {
        viewModelScope.launch {
            val result = agentViewWriteRaw(
                method = "DELETE",
                path = "/agentview/api/rules/${urlEncode(ruleId)}",
                jsonBody = null,
            )
            result.onSuccess {
                _uiState.update { st ->
                    val vs = st.viewState ?: return@update st
                    st.copy(viewState = vs.copy(rules = vs.rules.filter { it.id != ruleId }), error = null)
                }
                onDeleted?.invoke()
            }.onFailure { e ->
                _uiState.update { it.copy(error = "Could not delete rule: ${e.message}") }
                _ruleState.update { it.copy(error = "Could not delete rule: ${e.message}") }
            }
        }
    }

    // ---- Rule editor ----

    fun loadRuleEditor(url: String?, ruleId: String?) {
        _ruleState.value = AgentRuleUiState(loading = true, ruleUrl = url ?: "")
        viewModelScope.launch {
            if (ruleId.isNullOrBlank()) {
                if (url.isNullOrBlank()) {
                    _ruleState.update { it.copy(loading = false, error = "No URL or rule to edit.") }
                } else {
                    _ruleState.update { it.copy(loading = false, rule = null, draft = null, ruleUrl = url, error = null) }
                }
                return@launch
            }
            when (val res = api.getJson<AgentViewState>("/agentview/api/rules")) {
                is ApiResult.Error -> _ruleState.update { it.copy(loading = false, error = res.message) }
                is ApiResult.Success -> {
                    val found = res.data.rules.find { it.id == ruleId }
                    if (found == null) {
                        _ruleState.update { it.copy(loading = false, error = "That rule no longer exists.") }
                    } else {
                        val knownUrl = url ?: AgentViewRules.urlFromMatch(found.match, found.matchType) ?: ""
                        _ruleState.update {
                            it.copy(
                                loading = false,
                                rule = found,
                                ruleUrl = knownUrl,
                                draft = draftFrom(found, knownUrl.ifBlank { null }),
                                error = null,
                            )
                        }
                    }
                }
            }
        }
    }

    fun chooseActionForNewRule(action: String) {
        val url = _ruleState.value.ruleUrl
        if (url.isBlank() || _ruleState.value.busyAction != null) return
        _ruleState.update { it.copy(busyAction = action, error = null) }
        viewModelScope.launch {
            try {
                val res = agentViewWrite<FromUrlResponse>(
                    method = "POST",
                    path = "/agentview/api/rules/from-url",
                    jsonBody = api.json.encodeToString(FromUrlRequest(url = url, action = action)),
                )
                res.onSuccess { created ->
                    _ruleState.update {
                        it.copy(
                            rule = created.rule,
                            draft = draftFrom(created.rule, url),
                            originalMeta = created.original ?: OriginalMeta(available = false),
                            error = null,
                        )
                    }
                }.onFailure { e ->
                    _ruleState.update { it.copy(error = e.message) }
                }
            } finally {
                _ruleState.update { it.copy(busyAction = null) }
            }
        }
    }

    fun updateDraft(transform: (RuleDraft) -> RuleDraft) {
        _ruleState.update { st ->
            val d = st.draft ?: return@update st
            st.copy(draft = transform(d))
        }
    }

    fun resetHtmlToOriginal() {
        val url = _ruleState.value.ruleUrl
        if (url.isBlank() || _ruleState.value.busyAction != null) return
        _ruleState.update { it.copy(busyAction = "original", error = null) }
        viewModelScope.launch {
            try {
                val encoded = urlEncode(url)
                when (val res = api.getJson<OriginalHtml>("/agentview/api/original?url=$encoded")) {
                    is ApiResult.Success -> {
                        val o = res.data
                        _ruleState.update { st ->
                            st.copy(
                                draft = st.draft?.copy(html = o.html),
                                originalMeta = OriginalMeta(
                                    available = true,
                                    capturedAt = o.capturedAt,
                                    size = o.size,
                                    truncated = o.truncated,
                                ),
                                error = null,
                            )
                        }
                    }
                    is ApiResult.Error -> _ruleState.update { it.copy(error = res.message) }
                }
            } finally {
                _ruleState.update { it.copy(busyAction = null) }
            }
        }
    }

    fun saveRule() {
        val rule = _ruleState.value.rule ?: return
        val draft = _ruleState.value.draft ?: return
        if (_ruleState.value.busyAction != null) return
        _ruleState.update { it.copy(busyAction = "save", error = null) }
        viewModelScope.launch {
            try {
                val payload = AgentViewRules.buildRulePayload(
                    draft.copy(url = _ruleState.value.ruleUrl.ifBlank { null }),
                )
                val res = agentViewWrite<AgentRule>(
                    method = "PUT",
                    path = "/agentview/api/rules/${urlEncode(rule.id)}",
                    jsonBody = api.json.encodeToString(payload),
                )
                res.onSuccess {
                    _ruleState.update { it.copy(savedAndDone = true, error = null) }
                    load()
                }.onFailure { e ->
                    _ruleState.update { it.copy(error = e.message) }
                }
            } finally {
                _ruleState.update { it.copy(busyAction = null) }
            }
        }
    }

    private fun draftFrom(rule: AgentRule, url: String?): RuleDraft = RuleDraft(
        action = rule.action,
        name = rule.name,
        matchMode = AgentViewRules.matchModeFor(rule.match, url),
        url = url,
        customMatch = rule.match,
        customMatchType = rule.matchType,
        html = rule.html ?: "",
        replacements = rule.replacements ?: emptyList(),
        selectors = rule.selectors ?: emptyList(),
        blockMode = if (rule.blockMode == "reset") "reset" else "404",
        enabled = rule.enabled,
    )

    private suspend fun urlEncode(value: String): String =
        withContext(Dispatchers.IO) { URLEncoder.encode(value, "UTF-8") }

    private suspend inline fun <reified T> agentViewWrite(
        method: String,
        path: String,
        jsonBody: String?,
    ): Result<T> {
        return agentViewWriteRaw(method, path, jsonBody).mapCatching { bodyText ->
            api.json.decodeFromString<T>(bodyText)
        }
    }

    private suspend fun agentViewWriteRaw(
        method: String,
        path: String,
        jsonBody: String?,
    ): Result<String> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val reqBody = jsonBody?.toRequestBody(mediaType)
        return when (
            val res = api.executeRaw(
                method = method,
                pathOrUrl = path,
                requestBody = reqBody,
                headers = mapOf("X-Requested-With" to "pc-control"),
            )
        ) {
            is ApiResult.Success -> Result.success(res.data)
            is ApiResult.Error -> Result.failure(IllegalStateException(res.message))
        }
    }
}
