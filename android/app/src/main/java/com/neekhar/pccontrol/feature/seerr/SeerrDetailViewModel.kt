package com.neekhar.pccontrol.feature.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeerrDetailUiState(
    val mediaType: String = "movie",
    val tmdbId: Int = 0,
    val item: SeerrItem? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val overviewExpanded: Boolean = false,
    val submitting: Boolean = false,
    val playingOnTv: Boolean = false,
    val playOnTvStatus: String? = null,
    val playOnTvError: String? = null,
    val playOnTvShowRemoteAction: Boolean = false,
    val selectedSeasons: Set<Int> = emptySet(),
    val showSeasonSheet: Boolean = false,
    val collectionSheet: SeerrCollectionSheetState = SeerrCollectionSheetState(),
) {
    val isTv: Boolean get() = mediaType == "tv"

    val pickableSeasons: List<SeerrSeason>
        get() = item?.seasons.orEmpty().filter { SeerrHelpers.isRequestableSeason(it.status) }

    val allPickableSeasonsSelected: Boolean
        get() = pickableSeasons.isNotEmpty() && pickableSeasons.all { it.seasonNumber in selectedSeasons }

    val canRequestMovie: Boolean
        get() = item != null && item.isMovie && SeerrHelpers.isRequestableMovie(item.status)
}

class SeerrDetailViewModel(
    private val repository: SeerrRepository,
    mediaType: String,
    tmdbId: Int,
    seed: SeerrItem? = null,
) : ViewModel() {

    private val normalizedType: String = if (mediaType == "tv") "tv" else "movie"

    private val _uiState = MutableStateFlow(
        SeerrDetailUiState(
            mediaType = normalizedType,
            tmdbId = tmdbId,
            item = seed,
            loading = tmdbId > 0,
            error = if (tmdbId > 0) null else "Missing media ID",
        ),
    )
    val uiState: StateFlow<SeerrDetailUiState> = _uiState.asStateFlow()

    private val _openTvRemoteEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openTvRemoteEvents: SharedFlow<Unit> = _openTvRemoteEvents.asSharedFlow()

    private var loadJob: Job? = null
    private var collectionJob: Job? = null
    private var playOnTvClearJob: Job? = null

    init {
        if (tmdbId > 0) {
            load()
        }
    }

    fun load() {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            fetchDetailsInternal(id)
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun refresh() {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            fetchDetailsInternal(id)
            _uiState.update { it.copy(refreshing = false, loading = false) }
        }
    }

    fun toggleOverviewExpanded() {
        _uiState.update { it.copy(overviewExpanded = !it.overviewExpanded) }
    }

    fun openSeasonSheet() {
        _uiState.update { it.copy(showSeasonSheet = true) }
    }

    fun closeSeasonSheet() {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(showSeasonSheet = false) }
    }

    fun toggleSeason(seasonNumber: Int) {
        _uiState.update { state ->
            val next = state.selectedSeasons.toMutableSet()
            if (!next.add(seasonNumber)) {
                next.remove(seasonNumber)
            }
            state.copy(selectedSeasons = next)
        }
    }

    fun toggleAllSeasons() {
        _uiState.update { state ->
            val next = if (state.allPickableSeasonsSelected) {
                emptySet()
            } else {
                state.pickableSeasons.map { it.seasonNumber }.toSet()
            }
            state.copy(selectedSeasons = next)
        }
    }

    fun playOnTv() {
        val current = _uiState.value.item ?: return
        if (_uiState.value.playingOnTv) return
        playOnTvClearJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    playingOnTv = true,
                    playOnTvStatus = null,
                    playOnTvError = null,
                )
            }
            when (
                val res = repository.playOnTv(
                    mediaUrl = current.mediaUrl,
                    tmdbId = current.id,
                    mediaType = normalizedType,
                    title = current.title,
                )
            ) {
                is ApiResult.Success -> {
                    val device = res.data.device?.takeIf { it.isNotBlank() } ?: "TV"
                    val isView = res.data.action == "view"
                    val statusMessage = if (isView) {
                        "Opened on $device"
                    } else {
                        "Sent to $device"
                    }
                    _uiState.update {
                        it.copy(
                            playingOnTv = false,
                            playOnTvStatus = statusMessage,
                            playOnTvError = null,
                            playOnTvShowRemoteAction = isView,
                        )
                    }
                    if (!isView) {
                        _openTvRemoteEvents.tryEmit(Unit)
                    }
                    playOnTvClearJob = viewModelScope.launch {
                        delay(3_000L)
                        _uiState.update { it.copy(playOnTvStatus = null) }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            playingOnTv = false,
                            playOnTvStatus = null,
                            playOnTvError = res.message,
                            playOnTvShowRemoteAction = false,
                        )
                    }
                }
            }
        }
    }

    fun requestMovie() {
        val current = _uiState.value.item ?: return
        if (_uiState.value.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, error = null) }
            when (val res = repository.createMovieRequest(current.id)) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        state.copy(item = state.item?.copy(status = SeerrStatus.REQUESTED))
                    }
                    fetchDetailsInternal(current.id)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = res.message) }
                }
            }
            _uiState.update { it.copy(submitting = false) }
        }
    }

    fun requestSelectedSeasons() {
        val state = _uiState.value
        val current = state.item ?: return
        val picked = state.selectedSeasons.sorted()
        if (state.submitting || picked.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, error = null) }
            when (val res = repository.createTvRequest(current.id, picked)) {
                is ApiResult.Success -> {
                    val pickedSet = picked.toSet()
                    _uiState.update { s ->
                        val prev = s.item
                        s.copy(
                            item = prev?.copy(
                                status = if (prev.status == SeerrStatus.NONE || prev.status == SeerrStatus.DELETED) {
                                    SeerrStatus.REQUESTED
                                } else {
                                    prev.status
                                },
                                seasons = prev.seasons.map { season ->
                                    if (season.seasonNumber in pickedSet) {
                                        season.copy(status = SeerrStatus.REQUESTED)
                                    } else {
                                        season
                                    }
                                },
                            ),
                            selectedSeasons = emptySet(),
                            showSeasonSheet = false,
                        )
                    }
                    fetchDetailsInternal(current.id)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = res.message) }
                }
            }
            _uiState.update { it.copy(submitting = false) }
        }
    }

    fun openCollection(ref: SeerrCollectionRef) {
        collectionJob?.cancel()
        _uiState.update {
            it.copy(
                collectionSheet = SeerrCollectionSheetState(
                    visible = true,
                    collection = null,
                    seedTitle = ref.name,
                    seedPoster = ref.poster,
                    loading = true,
                    error = null,
                ),
            )
        }
        collectionJob = viewModelScope.launch {
            when (val res = repository.getCollection(ref.id)) {
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
        val sheet = _uiState.value.collectionSheet
        if (sheet.busyMovieId != null || sheet.batchSubmitting) return
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
                    markCollectionMovieRequested(movie.id)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(collectionSheet = it.collectionSheet.copy(error = res.message))
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
                    is ApiResult.Success -> markCollectionMovieRequested(movie.id)
                    is ApiResult.Error -> failed.add("${movie.title}: ${res.message}")
                }
            }
            when (val refreshed = repository.getCollection(col.id)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(collectionSheet = it.collectionSheet.copy(collection = refreshed.data))
                    }
                }
                is ApiResult.Error -> {
                    // Keep optimistic updates
                }
            }
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

    private fun markCollectionMovieRequested(movieId: Int) {
        _uiState.update { state ->
            val updatedItem = if (state.mediaType == "movie" && state.tmdbId == movieId) {
                state.item?.copy(status = SeerrStatus.REQUESTED)
            } else {
                state.item
            }
            val updatedCollection = state.collectionSheet.collection?.let { col ->
                col.copy(
                    parts = col.parts.map { part ->
                        if (part.id == movieId) part.copy(status = SeerrStatus.REQUESTED) else part
                    },
                )
            }
            state.copy(
                item = updatedItem,
                collectionSheet = state.collectionSheet.copy(collection = updatedCollection),
            )
        }
    }

    private suspend fun fetchDetailsInternal(id: Int) {
        when (val res = repository.getDetails(normalizedType, id)) {
            is ApiResult.Success -> {
                val detail = res.data
                val defaultSelected = if (detail.isTv) {
                    detail.seasons
                        .filter { SeerrHelpers.isRequestableSeason(it.status) }
                        .map { it.seasonNumber }
                        .toSet()
                } else {
                    emptySet()
                }
                _uiState.update {
                    it.copy(
                        item = detail,
                        error = null,
                        selectedSeasons = defaultSelected,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }
}
