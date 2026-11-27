package com.neekhar.pccontrol.feature.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeerrCollectionSheetState(
    val visible: Boolean = false,
    val collection: SeerrCollection? = null,
    val seedTitle: String = "",
    val seedPoster: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val busyMovieId: Int? = null,
    val batchSubmitting: Boolean = false,
)

data class SeerrUiState(
    val query: String = "",
    val searchResults: List<SeerrItem>? = null,
    val searchMediaFilter: SeerrSearchMediaFilter = SeerrSearchMediaFilter.ALL,
    val searching: Boolean = false,
    val statusFilter: SeerrRequestFilter = SeerrRequestFilter.ALL,
    val requests: List<SeerrRequestItem>? = null,
    val counts: Map<String, Int>? = null,
    val loadingFilter: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val busyMovieId: Int? = null,
    val collectionSheet: SeerrCollectionSheetState = SeerrCollectionSheetState(),
) {
    val visibleFilterTabs: List<SeerrRequestFilter>
        get() = SeerrHelpers.visibleRequestFilterTabs(counts)

    val filteredSearchResults: List<SeerrItem>
        get() = SeerrHelpers.filterSearchResults(searchResults.orEmpty(), searchMediaFilter)
}

@OptIn(FlowPreview::class)
class SeerrViewModel(
    private val repository: SeerrRepository,
    autoLoad: Boolean = true,
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MIN_AUTO_SEARCH_LEN = 3
    }

    private val _uiState = MutableStateFlow(
        SeerrUiState(
            requests = repository.getCachedRequests(),
            counts = repository.getCachedCounts(),
        ),
    )
    val uiState: StateFlow<SeerrUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var requestsJob: Job? = null
    private var collectionJob: Job? = null

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.length >= MIN_AUTO_SEARCH_LEN) {
                        executeSearch(trimmed)
                    }
                }
        }
        if (autoLoad) {
            loadRequests(SeerrRequestFilter.ALL, showSpinner = false)
        }
    }

    fun onScreenResume() {
        if (requestsJob?.isActive == true) return
        loadRequests(_uiState.value.statusFilter, showSpinner = false)
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            queryFlow.value = ""
            _uiState.update {
                it.copy(
                    searching = false,
                    searchResults = null,
                    searchMediaFilter = SeerrSearchMediaFilter.ALL,
                )
            }
            return
        }
        queryFlow.value = trimmed
    }

    fun submitSearch() {
        val trimmed = _uiState.value.query.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        executeSearch(trimmed)
    }

    fun clearSearch() {
        searchJob?.cancel()
        queryFlow.value = ""
        _uiState.update {
            it.copy(
                query = "",
                searching = false,
                searchResults = null,
                searchMediaFilter = SeerrSearchMediaFilter.ALL,
            )
        }
    }

    fun selectSearchMediaFilter(filter: SeerrSearchMediaFilter) {
        _uiState.update { it.copy(searchMediaFilter = filter) }
    }

    fun selectStatusFilter(filter: SeerrRequestFilter) {
        if (filter == _uiState.value.statusFilter) return
        _uiState.update { it.copy(statusFilter = filter) }
        loadRequests(filter, showSpinner = true)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val currentFilter = _uiState.value.statusFilter
                fetchRequestsInternal(currentFilter)
                val q = _uiState.value.query.trim()
                if (q.isNotEmpty()) {
                    fetchSearchInternal(q)
                }
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun reloadRequests() {
        loadRequests(_uiState.value.statusFilter, showSpinner = false)
    }

    fun requestMovie(movieId: Int) {
        if (_uiState.value.busyMovieId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyMovieId = movieId, error = null) }
            try {
                when (val res = repository.createMovieRequest(movieId)) {
                    is ApiResult.Success -> {
                        syncMovieStatus(movieId, SeerrStatus.REQUESTED)
                        loadRequests(_uiState.value.statusFilter, showSpinner = false)
                    }
                    is ApiResult.Error -> {
                        _uiState.update { it.copy(error = res.message) }
                    }
                }
            } finally {
                _uiState.update { it.copy(busyMovieId = null) }
            }
        }
    }

    fun openCollection(collectionId: Int, seedTitle: String = "", seedPoster: String? = null) {
        collectionJob?.cancel()
        _uiState.update {
            it.copy(
                collectionSheet = SeerrCollectionSheetState(
                    visible = true,
                    collection = null,
                    seedTitle = seedTitle,
                    seedPoster = seedPoster,
                    loading = true,
                    error = null,
                ),
            )
        }
        collectionJob = viewModelScope.launch {
            when (val res = repository.getCollection(collectionId)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            collectionSheet = it.collectionSheet.copy(
                                collection = res.data,
                                loading = false,
                                error = null,
                            ),
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            collectionSheet = it.collectionSheet.copy(
                                loading = false,
                                error = res.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun closeCollection() {
        if (_uiState.value.collectionSheet.batchSubmitting) return
        collectionJob?.cancel()
        _uiState.update { it.copy(collectionSheet = SeerrCollectionSheetState()) }
    }

    fun requestCollectionMovie(movie: SeerrItem) {
        if (_uiState.value.collectionSheet.busyMovieId != null ||
            _uiState.value.collectionSheet.batchSubmitting
        ) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    collectionSheet = it.collectionSheet.copy(
                        busyMovieId = movie.id,
                        error = null,
                    ),
                )
            }
            when (val res = repository.createMovieRequest(movie.id)) {
                is ApiResult.Success -> {
                    syncMovieStatus(movie.id, SeerrStatus.REQUESTED)
                    loadRequests(_uiState.value.statusFilter, showSpinner = false)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            collectionSheet = it.collectionSheet.copy(error = res.message),
                        )
                    }
                }
            }
            _uiState.update {
                it.copy(collectionSheet = it.collectionSheet.copy(busyMovieId = null))
            }
        }
    }

    fun requestAllMissingInCollection() {
        val sheet = _uiState.value.collectionSheet
        val col = sheet.collection ?: return
        if (sheet.batchSubmitting) return
        val missing = SeerrHelpers.collectionMissingParts(col)
        if (missing.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    collectionSheet = it.collectionSheet.copy(
                        batchSubmitting = true,
                        error = null,
                    ),
                )
            }
            val failed = mutableListOf<String>()
            for (movie in missing) {
                when (val res = repository.createMovieRequest(movie.id)) {
                    is ApiResult.Success -> syncMovieStatus(movie.id, SeerrStatus.REQUESTED)
                    is ApiResult.Error -> failed.add("${movie.title}: ${res.message}")
                }
            }
            when (val refreshed = repository.getCollection(col.id)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(collectionSheet = it.collectionSheet.copy(collection = refreshed.data))
                    }
                    refreshed.data.parts.forEach { part ->
                        syncMovieStatus(part.id, part.status)
                    }
                }
                is ApiResult.Error -> {
                    // Keep optimistic updates
                }
            }
            loadRequests(_uiState.value.statusFilter, showSpinner = false)
            _uiState.update {
                it.copy(
                    collectionSheet = it.collectionSheet.copy(
                        batchSubmitting = false,
                        error = failed.takeIf { list -> list.isNotEmpty() }?.joinToString("\n"),
                    ),
                )
            }
        }
    }

    private fun executeSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            fetchSearchInternal(query)
        }
    }

    private suspend fun fetchSearchInternal(query: String) {
        _uiState.update { it.copy(searching = true, error = null) }
        when (val res = repository.search(query)) {
            is ApiResult.Success -> {
                _uiState.update {
                    it.copy(
                        searching = false,
                        searchResults = res.data,
                        error = null,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update {
                    it.copy(
                        searching = false,
                        error = res.message,
                    )
                }
            }
        }
    }

    private fun loadRequests(filter: SeerrRequestFilter, showSpinner: Boolean) {
        requestsJob?.cancel()
        requestsJob = viewModelScope.launch {
            if (showSpinner) {
                _uiState.update { it.copy(loadingFilter = true) }
            }
            try {
                fetchRequestsInternal(filter)
            } finally {
                _uiState.update { it.copy(loadingFilter = false) }
            }
        }
    }

    private suspend fun fetchRequestsInternal(filter: SeerrRequestFilter) {
        when (val res = repository.getRequests(filter)) {
            is ApiResult.Success -> {
                _uiState.update { state ->
                    state.copy(
                        requests = res.data.requests,
                        counts = if (filter == SeerrRequestFilter.ALL && res.data.counts != null) {
                            res.data.counts
                        } else {
                            state.counts
                        },
                        error = null,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }

    private fun syncMovieStatus(movieId: Int, status: SeerrStatus) {
        _uiState.update { state ->
            state.copy(
                searchResults = state.searchResults?.map { item ->
                    if (item.id == movieId && item.isMovie) item.copy(status = status) else item
                },
                collectionSheet = state.collectionSheet.copy(
                    collection = state.collectionSheet.collection?.let { col ->
                        col.copy(
                            parts = col.parts.map { part ->
                                if (part.id == movieId) part.copy(status = status) else part
                            },
                        )
                    },
                ),
            )
        }
    }
}
