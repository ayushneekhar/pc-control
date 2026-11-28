package com.neekhar.pccontrol.feature.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.network.ApiTransportManager
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadEnqueueResult
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadManager
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerRepository
import com.neekhar.pccontrol.feature.watch.player.WatchRemoteSubtitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Watchlist (My List) & Watched state
    val inWatchlist: Boolean = false,
    val togglingWatchlist: Boolean = false,
    val togglingWatched: Boolean = false,
    val busyEpisodeWatchedId: String? = null,
    // Active Radarr/Sonarr download progress
    val activeDownload: WatchDownloadItem? = null,
    // TV Episode Browser
    val selectedEpisodeSeason: Int? = null,
    val episodesBySeason: Map<Int, WatchEpisodesResponse> = emptyMap(),
    val loadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    val playingEpisodeId: String? = null,
    // Play on TV
    val playingOnTv: Boolean = false,
    val playOnTvStatus: String? = null,
    val playOnTvError: String? = null,
    val playOnTvShowRemoteAction: Boolean = false,
    // Offline downloads & Tailscale-only notice
    val enqueuingDownloadKey: String? = null,
    val tailscaleNotice: String? = null,
    // TV Request season picker
    val selectedSeasons: Set<Int> = emptySet(),
    val showSeasonSheet: Boolean = false,
    val collectionSheet: SeerrCollectionSheetState = SeerrCollectionSheetState(),
    val subtitleSheet: WatchSubtitleSheetState = WatchSubtitleSheetState(),
) {
    val isTv: Boolean get() = mediaType == "tv"

    val pickableSeasons: List<SeerrSeason>
        get() = item?.seasons.orEmpty().filter { SeerrHelpers.isRequestableSeason(it.status) }

    val allPickableSeasonsSelected: Boolean
        get() = pickableSeasons.isNotEmpty() && pickableSeasons.all { it.seasonNumber in selectedSeasons }

    val canRequestMovie: Boolean
        get() = item != null && item.isMovie && SeerrHelpers.isRequestableMovie(item.status)

    val currentSeasonEpisodes: WatchEpisodesResponse?
        get() = selectedEpisodeSeason?.let { episodesBySeason[it] }

    val browsableSeasons: List<SeerrSeason>
        get() {
            val all = item?.seasons.orEmpty()
            val regular = all.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
            val specials = all.filter { it.seasonNumber == 0 }
            return regular + specials
        }
}

class SeerrDetailViewModel(
    private val repository: SeerrRepository,
    mediaType: String,
    tmdbId: Int,
    seed: SeerrItem? = null,
    private val downloadManager: WatchDownloadManager? = null,
    private val transportManager: ApiTransportManager? = null,
    private val watchPlayerRepository: WatchPlayerRepository? = null,
) : ViewModel() {

    private val normalizedType: String = if (mediaType == "tv") "tv" else "movie"
    private val watchlistKey: String = SeerrHelpers.watchlistKey(normalizedType, tmdbId)

    private val emptyOfflineFlow = MutableStateFlow<List<OfflineWatchItem>>(emptyList())
    val offlineDownloads: StateFlow<List<OfflineWatchItem>>
        get() = downloadManager?.items ?: emptyOfflineFlow

    private val _uiState = MutableStateFlow(
        SeerrDetailUiState(
            mediaType = normalizedType,
            tmdbId = tmdbId,
            item = seed,
            loading = tmdbId > 0,
            inWatchlist = watchlistKey in repository.getCachedWatchlistIds(),
            error = if (tmdbId > 0) null else "Missing media ID",
        ),
    )
    val uiState: StateFlow<SeerrDetailUiState> = _uiState.asStateFlow()

    private val _openTvRemoteEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openTvRemoteEvents: SharedFlow<Unit> = _openTvRemoteEvents.asSharedFlow()

    private var loadJob: Job? = null
    private var episodesJob: Job? = null
    private var collectionJob: Job? = null
    private var playOnTvClearJob: Job? = null
    private var tailscaleNoticeJob: Job? = null
    private var prefetchJob: Job? = null
    private var subtitleSearchJob: Job? = null

    fun isTailscaleActive(): Boolean =
        transportManager?.resolveTransport()?.let { it == ApiTransport.TAILSCALE } ?: true

    fun showTailscaleRequiredNotice() {
        tailscaleNoticeJob?.cancel()
        _uiState.update { it.copy(tailscaleNotice = SeerrViewModel.TAILSCALE_GUARD_MESSAGE) }
        tailscaleNoticeJob = viewModelScope.launch {
            delay(4_500L)
            _uiState.update { it.copy(tailscaleNotice = null) }
        }
    }

    fun dismissTailscaleNotice() {
        _uiState.update { it.copy(tailscaleNotice = null) }
    }

    fun downloadMovie() {
        val mgr = downloadManager ?: return
        val id = _uiState.value.tmdbId
        if (id <= 0 || _uiState.value.enqueuingDownloadKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(enqueuingDownloadKey = "movie:$id", error = null) }
            try {
                when (val res = mgr.enqueueDownload(tmdbId = id, mediaType = "movie")) {
                    is WatchDownloadEnqueueResult.TailscaleRequired -> showTailscaleRequiredNotice()
                    is WatchDownloadEnqueueResult.Error -> _uiState.update { it.copy(error = res.message) }
                    else -> {}
                }
            } finally {
                _uiState.update { it.copy(enqueuingDownloadKey = null) }
            }
        }
    }

    fun downloadEpisode(episode: WatchEpisode) {
        val mgr = downloadManager ?: return
        val jfId = episode.jellyfinItemId?.takeIf { it.isNotBlank() } ?: return
        if (_uiState.value.enqueuingDownloadKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(enqueuingDownloadKey = jfId, error = null) }
            try {
                when (
                    val res = mgr.enqueueDownload(
                        jellyfinItemId = jfId,
                        tmdbId = _uiState.value.tmdbId,
                        mediaType = "episode",
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                    )
                ) {
                    is WatchDownloadEnqueueResult.TailscaleRequired -> showTailscaleRequiredNotice()
                    is WatchDownloadEnqueueResult.Error -> _uiState.update { it.copy(error = res.message) }
                    else -> {}
                }
            } finally {
                _uiState.update { it.copy(enqueuingDownloadKey = null) }
            }
        }
    }

    fun pauseOfflineDownload(jellyfinItemId: String) {
        downloadManager?.pauseDownload(jellyfinItemId)
    }

    fun resumeOfflineDownload(jellyfinItemId: String) {
        val mgr = downloadManager ?: return
        when (val res = mgr.resumeDownload(jellyfinItemId)) {
            is WatchDownloadEnqueueResult.TailscaleRequired -> showTailscaleRequiredNotice()
            is WatchDownloadEnqueueResult.Error -> _uiState.update { it.copy(error = res.message) }
            else -> {}
        }
    }

    fun deleteOfflineDownload(jellyfinItemId: String) {
        downloadManager?.deleteDownload(jellyfinItemId)
    }

    init {
        if (tmdbId > 0) {
            load()
        }
    }

    fun resolveImageUrl(pathOrUrl: String?): String? = repository.resolveImageUrl(pathOrUrl)

    fun load() {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                fetchAllInternal(id, forceEpisodes = false)
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun refresh() {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                fetchAllInternal(id, forceEpisodes = true)
            } finally {
                _uiState.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    fun toggleOverviewExpanded() {
        _uiState.update { it.copy(overviewExpanded = !it.overviewExpanded) }
    }

    fun toggleWatchlist() {
        val id = _uiState.value.tmdbId
        if (id <= 0 || _uiState.value.togglingWatchlist) return
        val current = _uiState.value.item
        val wasIn = _uiState.value.inWatchlist

        _uiState.update { it.copy(inWatchlist = !wasIn, togglingWatchlist = true) }
        viewModelScope.launch {
            val res = repository.toggleWatchlist(
                WatchWatchlistRequestBody(
                    id = id,
                    mediaType = normalizedType,
                    action = if (wasIn) "remove" else "add",
                    title = current?.title,
                    year = current?.year,
                    poster = current?.poster,
                    backdrop = current?.backdrop,
                    overview = current?.overview,
                    voteAverage = current?.voteAverage,
                ),
            )
            when (res) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            inWatchlist = res.data.inWatchlist,
                            togglingWatchlist = false,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            inWatchlist = wasIn,
                            togglingWatchlist = false,
                            error = res.message,
                        )
                    }
                }
            }
        }
    }

    fun toggleWatched() {
        val current = _uiState.value.item ?: return
        val jfId = current.jellyfinItemId?.takeIf { it.isNotBlank() }
            ?: current.resumeJellyfinItemId?.takeIf { it.isNotBlank() && !current.isTv }
            ?: return
        if (_uiState.value.togglingWatched) return
        val wasPlayed = current.played
        val targetPlayed = !wasPlayed

        _uiState.update { st ->
            st.copy(
                togglingWatched = true,
                item = st.item?.copy(
                    played = targetPlayed,
                    resumePositionTicks = if (targetPlayed) 0L else st.item.resumePositionTicks,
                ),
            )
        }
        viewModelScope.launch {
            when (val res = repository.setWatched(jfId, targetPlayed)) {
                is ApiResult.Success -> {
                    watchPlayerRepository?.clearPlaybackCache()
                    _uiState.update { st ->
                        st.copy(
                            togglingWatched = false,
                            item = st.item?.copy(
                                played = res.data.played,
                                resumePositionTicks = if (res.data.played) 0L else st.item.resumePositionTicks,
                            ),
                        )
                    }
                    if (normalizedType == "tv") {
                        val season = _uiState.value.selectedEpisodeSeason
                        if (season != null) {
                            loadSeasonEpisodes(season)
                        }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { st ->
                        st.copy(
                            togglingWatched = false,
                            item = st.item?.copy(played = wasPlayed),
                            error = res.message,
                        )
                    }
                }
            }
        }
    }

    fun toggleEpisodeWatched(episode: WatchEpisode) {
        val jfId = episode.jellyfinItemId?.takeIf { it.isNotBlank() } ?: return
        if (_uiState.value.busyEpisodeWatchedId != null) return
        val wasPlayed = episode.played
        val targetPlayed = !wasPlayed
        val season = episode.seasonNumber

        fun applyEpisodePlayed(playedVal: Boolean) {
            _uiState.update { st ->
                val curResp = st.episodesBySeason[season] ?: return@update st
                val updatedEps = curResp.episodes.map { ep ->
                    if (ep.jellyfinItemId == jfId) {
                        ep.copy(
                            played = playedVal,
                            progressPct = if (playedVal) 0.0 else ep.progressPct,
                            positionTicks = if (playedVal) 0L else ep.positionTicks,
                        )
                    } else {
                        ep
                    }
                }
                st.copy(
                    episodesBySeason = st.episodesBySeason + (season to curResp.copy(episodes = updatedEps)),
                )
            }
        }

        _uiState.update { it.copy(busyEpisodeWatchedId = jfId) }
        applyEpisodePlayed(targetPlayed)

        viewModelScope.launch {
            when (val res = repository.setWatched(jfId, targetPlayed)) {
                is ApiResult.Success -> {
                    watchPlayerRepository?.clearPlaybackCache()
                    applyEpisodePlayed(res.data.played)
                    _uiState.update { it.copy(busyEpisodeWatchedId = null) }
                }
                is ApiResult.Error -> {
                    applyEpisodePlayed(wasPlayed)
                    _uiState.update { it.copy(busyEpisodeWatchedId = null, error = res.message) }
                }
            }
        }
    }

    fun openSubtitleSearch(jellyfinItemId: String? = null, language: String = _uiState.value.subtitleSheet.language) {
        val current = _uiState.value.item
        val targetId = jellyfinItemId?.takeIf { it.isNotBlank() }
            ?: current?.jellyfinItemId?.takeIf { it.isNotBlank() }
            ?: current?.resumeJellyfinItemId?.takeIf { it.isNotBlank() }
            ?: return
        val lang = language.trim().lowercase().ifBlank { "eng" }
        subtitleSearchJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleSheet = it.subtitleSheet.copy(
                    visible = true,
                    jellyfinItemId = targetId,
                    language = lang,
                    loading = true,
                    error = null,
                    notice = null,
                ),
            )
        }
        subtitleSearchJob = viewModelScope.launch {
            when (val res = repository.searchRemoteSubtitles(targetId, lang)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            subtitleSheet = it.subtitleSheet.copy(
                                loading = false,
                                error = null,
                                results = res.data.results,
                            ),
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            subtitleSheet = it.subtitleSheet.copy(
                                loading = false,
                                error = res.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun closeSubtitleSearch() {
        if (_uiState.value.subtitleSheet.downloadingSubtitleId != null) return
        subtitleSearchJob?.cancel()
        _uiState.update { it.copy(subtitleSheet = WatchSubtitleSheetState()) }
    }

    fun downloadRemoteSubtitle(sub: WatchRemoteSubtitle) {
        val sheet = _uiState.value.subtitleSheet
        if (sheet.jellyfinItemId.isBlank() || sub.id.isBlank() || sheet.downloadingSubtitleId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    subtitleSheet = it.subtitleSheet.copy(
                        downloadingSubtitleId = sub.id,
                        error = null,
                        notice = null,
                    ),
                )
            }
            when (val res = repository.downloadRemoteSubtitle(sheet.jellyfinItemId, sub.id)) {
                is ApiResult.Success -> {
                    watchPlayerRepository?.clearPlaybackCache()
                    _uiState.update {
                        it.copy(
                            subtitleSheet = it.subtitleSheet.copy(
                                downloadingSubtitleId = null,
                                notice = "Subtitle downloaded (${res.data.subtitleTracks.size} tracks available)",
                            ),
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            subtitleSheet = it.subtitleSheet.copy(
                                downloadingSubtitleId = null,
                                error = res.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun selectEpisodeSeason(seasonNumber: Int) {
        if (seasonNumber == _uiState.value.selectedEpisodeSeason &&
            _uiState.value.episodesBySeason.containsKey(seasonNumber)
        ) return
        _uiState.update {
            it.copy(
                selectedEpisodeSeason = seasonNumber,
                episodesError = null,
            )
        }
        if (!_uiState.value.episodesBySeason.containsKey(seasonNumber)) {
            loadSeasonEpisodes(seasonNumber)
        }
    }

    fun retrySeasonEpisodes() {
        val season = _uiState.value.selectedEpisodeSeason ?: return
        loadSeasonEpisodes(season)
    }

    private fun loadSeasonEpisodes(seasonNumber: Int) {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingEpisodes = true, episodesError = null) }
            when (val res = repository.getWatchEpisodes(id, seasonNumber)) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            episodesBySeason = state.episodesBySeason + (seasonNumber to res.data),
                            loadingEpisodes = false,
                            episodesError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            loadingEpisodes = false,
                            episodesError = res.message,
                        )
                    }
                }
            }
        }
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

    fun refreshQuietly() {
        val id = _uiState.value.tmdbId
        if (id <= 0) return
        viewModelScope.launch {
            fetchAllInternal(id, forceEpisodes = true)
        }
    }

    fun showPlayOnTvError(message: String) {
        playOnTvClearJob?.cancel()
        _uiState.update {
            it.copy(
                playingOnTv = false,
                playingEpisodeId = null,
                playOnTvStatus = null,
                playOnTvError = message,
                playOnTvShowRemoteAction = false,
            )
        }
    }

    fun playOnTv(sessionId: String? = null, deviceName: String? = null) {
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
            val res = repository.playOnTv(
                mediaUrl = current.mediaUrl,
                tmdbId = current.id,
                mediaType = normalizedType,
                title = current.title,
                startPositionTicks = if (!current.isTv && current.resumePositionTicks > 0L) {
                    current.resumePositionTicks
                } else {
                    null
                },
                sessionId = sessionId,
                deviceName = deviceName,
            )
            handlePlayOnTvResponse(res)
        }
    }

    fun playEpisodeOnTv(
        episode: WatchEpisode,
        sessionId: String? = null,
        deviceName: String? = null,
    ) {
        val jfId = episode.jellyfinItemId?.takeIf { it.isNotBlank() } ?: return
        if (_uiState.value.playingEpisodeId != null || _uiState.value.playingOnTv) return
        playOnTvClearJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    playingEpisodeId = jfId,
                    playOnTvStatus = null,
                    playOnTvError = null,
                )
            }
            val res = repository.playOnTv(
                tmdbId = _uiState.value.tmdbId,
                mediaType = "episode",
                title = episode.name,
                jellyfinItemId = jfId,
                startPositionTicks = episode.positionTicks.takeIf { it > 0L && !episode.played },
                sessionId = sessionId,
                deviceName = deviceName,
            )
            _uiState.update { it.copy(playingEpisodeId = null) }
            handlePlayOnTvResponse(res)
        }
    }

    private fun handlePlayOnTvResponse(res: ApiResult<PlayOnTvResponse>) {
        when (res) {
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

    private suspend fun fetchAllInternal(id: Int, forceEpisodes: Boolean) {
        coroutineScope {
            val detailDef = async { fetchDetailsInternal(id) }
            val dlDef = async { repository.getWatchDownloads(tmdbId = id, mediaType = normalizedType) }
            val wlDef = async { repository.getWatchDiscover(category = "watchlist") }

            detailDef.await()

            val dlRes = dlDef.await()
            if (dlRes is ApiResult.Success) {
                _uiState.update { it.copy(activeDownload = dlRes.data.items.firstOrNull()) }
            }

            val wlRes = wlDef.await()
            if (wlRes is ApiResult.Success) {
                val found = wlRes.data.items.any {
                    it.id == id && it.mediaType == normalizedType
                }
                _uiState.update { it.copy(inWatchlist = found) }
            }
        }

        // Load Episode Browser automatically for TV shows
        val detail = _uiState.value.item
        if (normalizedType == "tv" && detail != null && detail.seasons.isNotEmpty()) {
            val targetSeason = _uiState.value.selectedEpisodeSeason
                ?: SeerrHelpers.defaultEpisodeSeason(detail.seasons)
            if (targetSeason != null) {
                _uiState.update { it.copy(selectedEpisodeSeason = targetSeason) }
                if (forceEpisodes || !_uiState.value.episodesBySeason.containsKey(targetSeason)) {
                    loadSeasonEpisodes(targetSeason)
                }
            }
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
                maybePrefetchPlaybackInfo(detail)
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }

    private fun maybePrefetchPlaybackInfo(detail: SeerrItem) {
        val repo = watchPlayerRepository ?: return
        val targetJfId = detail.resumeJellyfinItemId?.takeIf { it.isNotBlank() }
        val isAvailable = detail.status == SeerrStatus.AVAILABLE ||
            detail.status == SeerrStatus.PARTIAL ||
            targetJfId != null
        if (!isAvailable) return

        // Skip prefetch when an offline completed file will be played locally
        val hasCompletedOffline = when {
            targetJfId != null && downloadManager?.getItem(targetJfId)?.isCompleted == true -> true
            !detail.isTv && downloadManager?.findByTmdb(detail.id, "movie")?.isCompleted == true -> true
            detail.isTv && targetJfId == null && offlineDownloads.value.any { it.tmdbId == detail.id && it.isCompleted } -> true
            else -> false
        }
        if (hasCompletedOffline) return

        val targetMediaType = if (detail.isTv && targetJfId != null) "episode" else normalizedType
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            repo.prefetchPlaybackInfo(
                jellyfinItemId = targetJfId,
                tmdbId = detail.id,
                mediaType = targetMediaType,
                season = if (detail.isTv) detail.resumeSeasonNumber else null,
                episode = if (detail.isTv) detail.resumeEpisodeNumber else null,
            )
        }
    }
}
