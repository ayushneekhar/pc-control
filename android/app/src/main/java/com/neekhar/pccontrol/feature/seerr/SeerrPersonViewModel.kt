package com.neekhar.pccontrol.feature.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeerrPersonUiState(
    val personId: Int = 0,
    val seedName: String? = null,
    val seedProfilePath: String? = null,
    val seedDepartment: String? = null,
    val person: SeerrPerson? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val bioExpanded: Boolean = false,
    val mediaFilter: SeerrPersonMediaFilter = SeerrPersonMediaFilter.ALL,
    val sortMode: SeerrPersonSortMode = SeerrPersonSortMode.POPULAR,
) {
    val displayName: String
        get() = person?.name?.takeIf { it.isNotBlank() }
            ?: seedName?.takeIf { it.isNotBlank() }
            ?: if (loading) "Loading…" else "Person"

    val displayProfilePath: String?
        get() = person?.profilePath ?: seedProfilePath

    val displayDepartment: String?
        get() = person?.knownForDepartment ?: seedDepartment

    val credits: List<SeerrPersonCredit>
        get() = person?.credits.orEmpty()

    val movieCount: Int
        get() = credits.count { it.mediaType == "movie" }

    val tvCount: Int
        get() = credits.count { it.mediaType == "tv" }

    val knownFor: List<SeerrPersonCredit>
        get() = credits.filter { !it.poster.isNullOrBlank() }.take(10)

    val filteredCredits: List<SeerrPersonCredit>
        get() = SeerrHelpers.filterAndSortPersonCredits(credits, mediaFilter, sortMode)
}

class SeerrPersonViewModel(
    private val repository: SeerrRepository,
    personId: Int,
    seedName: String? = null,
    seedProfilePath: String? = null,
    seedDepartment: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SeerrPersonUiState(
            personId = personId,
            seedName = seedName,
            seedProfilePath = seedProfilePath,
            seedDepartment = seedDepartment,
            loading = personId > 0,
            error = if (personId > 0) null else "Missing person ID",
        ),
    )
    val uiState: StateFlow<SeerrPersonUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (personId > 0) {
            load()
        }
    }

    fun load() {
        val id = _uiState.value.personId
        if (id <= 0) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                fetchPersonInternal(id)
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun refresh() {
        val id = _uiState.value.personId
        if (id <= 0) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                fetchPersonInternal(id)
            } finally {
                _uiState.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    fun toggleBioExpanded() {
        _uiState.update { it.copy(bioExpanded = !it.bioExpanded) }
    }

    fun selectMediaFilter(filter: SeerrPersonMediaFilter) {
        _uiState.update { it.copy(mediaFilter = filter) }
    }

    fun toggleSortMode() {
        _uiState.update { state ->
            val next = if (state.sortMode == SeerrPersonSortMode.POPULAR) {
                SeerrPersonSortMode.NEWEST
            } else {
                SeerrPersonSortMode.POPULAR
            }
            state.copy(sortMode = next)
        }
    }

    private suspend fun fetchPersonInternal(id: Int) {
        when (val res = repository.getPerson(id)) {
            is ApiResult.Success -> {
                _uiState.update { it.copy(person = res.data, error = null) }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }
}
