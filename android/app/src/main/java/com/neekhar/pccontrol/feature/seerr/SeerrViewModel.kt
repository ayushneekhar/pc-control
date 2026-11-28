package com.neekhar.pccontrol.feature.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.network.ApiTransportManager
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadEnqueueResult
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val activeTab: WatchTab = WatchTab.ALL,
    val searchExpanded: Boolean = false,
    val query: String = "",
    val searchResults: List<SeerrItem>? = null,
    val searchMediaFilter: SeerrSearchMediaFilter = SeerrSearchMediaFilter.ALL,
    val searching: Boolean = false,
    // Watch Home
    val watchHome: WatchHomeResponse? = null,
    val loadingHome: Boolean = false,
    val watchlistIds: Set<String> = emptySet(),
    val busyWatchlistKey: String? = null,
    val playingOnTvKey: String? = null,
    val playOnTvNotice: String? = null,
    val tailscaleNotice: String? = null,
    // Browse / Discover (In Library, Movies, TV, My List, Genre/Network see-all)
    val discoverItems: List<WatchItem>? = null,
    val discoverGenres: List<WatchGenre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedNetworkId: Int? = null,
    val selectedNetworkLabel: String? = null,
    val libraryFilter: WatchLibraryFilter = WatchLibraryFilter.ALL,
    val librarySort: WatchLibrarySort = WatchLibrarySort.RECENT,
    val discoverPage: Int = 1,
    val discoverTotalPages: Int = 1,
    val loadingDiscover: Boolean = false,
    val loadingMoreDiscover: Boolean = false,
    // Requests tab
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

    val pendingRequestsBadge: Int
        get() = SeerrHelpers.pendingRequestsBadgeCount(counts)

    val isSearchMode: Boolean
        get() = searchExpanded || query.isNotBlank() || searchResults != null

    val hasMoreDiscover: Boolean
        get() = discoverPage < discoverTotalPages
}

@OptIn(FlowPreview::class)
class SeerrViewModel(
    private val repository: SeerrRepository,
    autoLoad: Boolean = true,
    private val downloadManager: WatchDownloadManager? = null,
    private val transportManager: ApiTransportManager? = null,
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MIN_AUTO_SEARCH_LEN = 3
        const val TAILSCALE_GUARD_MESSAGE = "Connect to Tailscale to stream or download video"
    }

    private val cachedHome = repository.getCachedWatchHome()
    private val emptyOfflineFlow = MutableStateFlow<List<OfflineWatchItem>>(emptyList())
    val offlineDownloads: StateFlow<List<OfflineWatchItem>>
        get() = downloadManager?.items ?: emptyOfflineFlow

    fun isTailscaleActive(): Boolean =
        transportManager?.resolveTransport()?.let { it == ApiTransport.TAILSCALE } ?: true

    fun offlineStorageBytes(): Long = downloadManager?.totalStorageBytes() ?: 0L

    fun findCompletedOffline(jellyfinItemId: String?, tmdbId: Int = 0, mediaType: String = "movie"): OfflineWatchItem? {
        val mgr = downloadManager ?: return null
        val byId = jellyfinItemId?.takeIf { it.isNotBlank() }?.let { mgr.getItem(it) }
        if (byId != null && byId.isCompleted) return byId
        if (tmdbId > 0) {
            val byTmdb = mgr.findByTmdb(tmdbId, mediaType)
            if (byTmdb != null && byTmdb.isCompleted) return byTmdb
        }
        return null
    }

    fun showTailscaleRequiredNotice() {
        noticeClearJob?.cancel()
        _uiState.update { it.copy(tailscaleNotice = TAILSCALE_GUARD_MESSAGE) }
        noticeClearJob = viewModelScope.launch {
            delay(4_500L)
            _uiState.update { it.copy(tailscaleNotice = null) }
        }
    }

    fun dismissTailscaleNotice() {
        _uiState.update { it.copy(tailscaleNotice = null) }
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

    fun cancelOfflineDownload(jellyfinItemId: String) {
        downloadManager?.cancelDownload(jellyfinItemId)
    }

    fun deleteOfflineDownload(jellyfinItemId: String) {
        downloadManager?.deleteDownload(jellyfinItemId)
    }

    fun deleteAllOfflineDownloads() {
        downloadManager?.deleteAllDownloads()
    }

    private val _uiState = MutableStateFlow(
        SeerrUiState(
            watchHome = cachedHome,
            loadingHome = cachedHome == null,
            watchlistIds = cachedHome?.watchlistIds?.toSet().orEmpty(),
            requests = repository.getCachedRequests(),
            counts = repository.getCachedCounts(),
        ),
    )
    val uiState: StateFlow<SeerrUiState> = _uiState.asStateFlow()

    private val _openTvRemoteEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openTvRemoteEvents: SharedFlow<Unit> = _openTvRemoteEvents.asSharedFlow()

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var discoverJob: Job? = null
    private var requestsJob: Job? = null
    private var collectionJob: Job? = null
    private var noticeClearJob: Job? = null

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
            loadWatchHome(forceRefresh = false)
            loadRequests(SeerrRequestFilter.ALL, showSpinner = false)
        }
    }

    fun resolveImageUrl(pathOrUrl: String?): String? = repository.resolveImageUrl(pathOrUrl)

    fun onScreenResume() {
        // Sync watchlist IDs from cache immediately in case Detail screen toggled My List
        val cachedIds = repository.getCachedWatchlistIds()
        if (cachedIds.isNotEmpty() || _uiState.value.watchlistIds.isNotEmpty()) {
            _uiState.update { state ->
                val merged = if (cachedIds.isNotEmpty()) cachedIds else state.watchlistIds
                state.copy(
                    watchlistIds = merged,
                    watchHome = state.watchHome?.let { h ->
                        h.copy(
                            watchlistIds = merged.toList(),
                            hero = h.hero?.let { hero ->
                                hero.copy(inWatchlist = SeerrHelpers.watchlistKey(hero.mediaType, hero.id) in merged)
                            },
                        )
                    },
                )
            }
        }
        if (homeJob?.isActive != true) {
            loadWatchHome(forceRefresh = false)
        }
        if (requestsJob?.isActive != true) {
            loadRequests(_uiState.value.statusFilter, showSpinner = false)
        }
        if (_uiState.value.activeTab == WatchTab.MY_LIST && discoverJob?.isActive != true) {
            loadDiscover(page = 1, append = false)
        }
        if (downloadManager != null) {
            viewModelScope.launch {
                runCatching { downloadManager.syncDirtyResumePositions() }
            }
        }
    }

    fun selectTab(tab: WatchTab) {
        if (tab == _uiState.value.activeTab &&
            _uiState.value.selectedNetworkId == null
        ) return
        _uiState.update {
            it.copy(
                activeTab = tab,
                selectedGenreId = null,
                selectedNetworkId = null,
                selectedNetworkLabel = null,
                discoverItems = if (tab == WatchTab.ALL || tab == WatchTab.REQUESTS || tab == WatchTab.DOWNLOADS) it.discoverItems else null,
                discoverGenres = emptyList(),
                discoverPage = 1,
                discoverTotalPages = 1,
                error = null,
            )
        }
        when (tab) {
            WatchTab.ALL -> {
                if (_uiState.value.watchHome == null) {
                    loadWatchHome(forceRefresh = false)
                }
            }
            WatchTab.DOWNLOADS -> {
                // Offline downloads are observed reactively via offlineDownloads StateFlow
            }
            WatchTab.REQUESTS -> {
                loadRequests(_uiState.value.statusFilter, showSpinner = _uiState.value.requests == null)
            }
            WatchTab.LIBRARY, WatchTab.MOVIES, WatchTab.TV, WatchTab.MY_LIST -> {
                loadDiscover(page = 1, append = false)
            }
        }
    }

    fun openRailSeeAll(rail: WatchRail) {
        val targetTab = when {
            rail.category == "movies" || rail.mediaType == "movie" -> WatchTab.MOVIES
            rail.category == "tv" || rail.category == "network" || rail.mediaType == "tv" -> WatchTab.TV
            else -> WatchTab.MOVIES
        }
        _uiState.update {
            it.copy(
                activeTab = targetTab,
                selectedGenreId = rail.genreId,
                selectedNetworkId = rail.networkId,
                selectedNetworkLabel = if (rail.networkId != null) rail.title else null,
                discoverItems = null,
                discoverPage = 1,
                discoverTotalPages = 1,
                error = null,
            )
        }
        loadDiscover(page = 1, append = false)
    }

    fun selectGenre(genreId: Int?) {
        if (genreId == _uiState.value.selectedGenreId && _uiState.value.selectedNetworkId == null) return
        _uiState.update {
            it.copy(
                selectedGenreId = genreId,
                selectedNetworkId = null,
                selectedNetworkLabel = null,
                discoverItems = null,
                discoverPage = 1,
                discoverTotalPages = 1,
            )
        }
        loadDiscover(page = 1, append = false)
    }

    fun clearNetworkFilter() {
        if (_uiState.value.selectedNetworkId == null) return
        _uiState.update {
            it.copy(
                selectedNetworkId = null,
                selectedNetworkLabel = null,
                discoverItems = null,
                discoverPage = 1,
                discoverTotalPages = 1,
            )
        }
        loadDiscover(page = 1, append = false)
    }

    fun selectLibraryFilter(filter: WatchLibraryFilter) {
        if (filter == _uiState.value.libraryFilter) return
        _uiState.update {
            it.copy(
                libraryFilter = filter,
                discoverItems = null,
                discoverPage = 1,
                discoverTotalPages = 1,
            )
        }
        loadDiscover(page = 1, append = false)
    }

    fun selectLibrarySort(sort: WatchLibrarySort) {
        if (sort == _uiState.value.librarySort) return
        _uiState.update {
            it.copy(
                librarySort = sort,
                discoverItems = null,
                discoverPage = 1,
                discoverTotalPages = 1,
            )
        }
        loadDiscover(page = 1, append = false)
    }

    fun loadMoreDiscover() {
        val st = _uiState.value
        if (st.loadingDiscover || st.loadingMoreDiscover || !st.hasMoreDiscover) return
        loadDiscover(page = st.discoverPage + 1, append = true)
    }

    fun toggleSearchExpanded() {
        val currentlyExpanded = _uiState.value.searchExpanded
        if (currentlyExpanded) {
            clearSearch()
        } else {
            _uiState.update { it.copy(searchExpanded = true) }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, searchExpanded = true) }
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
                searchExpanded = false,
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
                val st = _uiState.value
                val q = st.query.trim()
                if (q.isNotEmpty()) {
                    fetchSearchInternal(q)
                } else {
                    when (st.activeTab) {
                        WatchTab.ALL -> {
                            fetchWatchHomeInternal(forceRefresh = true)
                            fetchRequestsInternal(SeerrRequestFilter.ALL)
                        }
                        WatchTab.DOWNLOADS -> {
                            runCatching { downloadManager?.syncDirtyResumePositions() }
                        }
                        WatchTab.REQUESTS -> {
                            fetchRequestsInternal(st.statusFilter)
                        }
                        WatchTab.LIBRARY, WatchTab.MOVIES, WatchTab.TV, WatchTab.MY_LIST -> {
                            fetchDiscoverInternal(page = 1, append = false)
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun reloadRequests() {
        loadRequests(_uiState.value.statusFilter, showSpinner = false)
    }

    fun toggleWatchlist(item: WatchItem) {
        if (item.id <= 0) return
        val key = SeerrHelpers.watchlistKey(item.mediaType, item.id)
        if (_uiState.value.busyWatchlistKey == key) return
        val wasIn = key in _uiState.value.watchlistIds

        // Optimistic update
        _uiState.update { state ->
            val nextIds = if (wasIn) state.watchlistIds - key else state.watchlistIds + key
            state.copy(
                busyWatchlistKey = key,
                watchlistIds = nextIds,
                watchHome = state.watchHome?.let { h ->
                    h.copy(
                        watchlistIds = nextIds.toList(),
                        hero = h.hero?.let { hero ->
                            if (SeerrHelpers.watchlistKey(hero.mediaType, hero.id) == key) {
                                hero.copy(inWatchlist = !wasIn)
                            } else {
                                hero
                            }
                        },
                    )
                },
                discoverItems = if (state.activeTab == WatchTab.MY_LIST && wasIn) {
                    state.discoverItems?.filterNot {
                        SeerrHelpers.watchlistKey(it.mediaType, it.id) == key
                    }
                } else {
                    state.discoverItems
                },
            )
        }

        viewModelScope.launch {
            val res = repository.toggleWatchlist(
                WatchWatchlistRequestBody(
                    id = item.id,
                    mediaType = item.mediaType,
                    action = if (wasIn) "remove" else "add",
                    title = item.title,
                    year = item.year,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    overview = item.overview,
                    voteAverage = item.voteAverage,
                ),
            )
            when (res) {
                is ApiResult.Success -> {
                    val serverIds = res.data.ids.toSet()
                    _uiState.update { state ->
                        state.copy(
                            busyWatchlistKey = null,
                            watchlistIds = serverIds,
                            watchHome = state.watchHome?.let { h ->
                                h.copy(
                                    watchlistIds = res.data.ids,
                                    hero = h.hero?.let { hero ->
                                        hero.copy(
                                            inWatchlist = SeerrHelpers.watchlistKey(hero.mediaType, hero.id) in serverIds,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
                is ApiResult.Error -> {
                    // Revert optimistic change
                    _uiState.update { state ->
                        val reverted = if (wasIn) state.watchlistIds + key else state.watchlistIds - key
                        state.copy(
                            busyWatchlistKey = null,
                            watchlistIds = reverted,
                            error = res.message,
                        )
                    }
                }
            }
        }
    }

    fun playHeroOnTv(item: WatchItem) {
        val key = "hero:${item.mediaType}:${item.id}"
        if (_uiState.value.playingOnTvKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(playingOnTvKey = key, error = null) }
            val res = repository.playOnTv(
                tmdbId = item.id.takeIf { it > 0 },
                mediaType = item.mediaType,
                title = item.title,
                jellyfinItemId = item.jellyfinItemId,
            )
            handlePlayOnTvResult(res)
        }
    }

    fun playContinueOnTv(item: WatchContinueItem) {
        val key = "cw:${item.jellyfinItemId}"
        if (_uiState.value.playingOnTvKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(playingOnTvKey = key, error = null) }
            val res = repository.playOnTv(
                tmdbId = item.id.takeIf { it > 0 },
                mediaType = if (item.isTv) "episode" else "movie",
                title = item.episodeTitle ?: item.title,
                jellyfinItemId = item.jellyfinItemId,
                startPositionTicks = item.positionTicks.takeIf { it > 0L && !item.isNextUp },
            )
            handlePlayOnTvResult(res)
        }
    }

    private fun handlePlayOnTvResult(res: ApiResult<PlayOnTvResponse>) {
        when (res) {
            is ApiResult.Success -> {
                val device = res.data.device?.takeIf { it.isNotBlank() } ?: "TV"
                val isView = res.data.action == "view"
                val msg = if (isView) "Opened on $device" else "Playing on $device"
                _uiState.update { it.copy(playingOnTvKey = null, playOnTvNotice = msg) }
                if (!isView) {
                    _openTvRemoteEvents.tryEmit(Unit)
                }
                noticeClearJob?.cancel()
                noticeClearJob = viewModelScope.launch {
                    delay(3_000L)
                    _uiState.update { it.copy(playOnTvNotice = null) }
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(playingOnTvKey = null, error = res.message) }
            }
        }
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

    // ------------------------------------------------------------------ Internal fetchers

    private fun loadWatchHome(forceRefresh: Boolean) {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            if (_uiState.value.watchHome == null) {
                _uiState.update { it.copy(loadingHome = true) }
            }
            try {
                fetchWatchHomeInternal(forceRefresh = forceRefresh)
            } finally {
                _uiState.update { it.copy(loadingHome = false) }
            }
        }
    }

    private suspend fun fetchWatchHomeInternal(forceRefresh: Boolean) {
        when (val res = repository.getWatchHome(forceRefresh = forceRefresh)) {
            is ApiResult.Success -> {
                val home = res.data
                _uiState.update {
                    it.copy(
                        watchHome = home,
                        watchlistIds = home.watchlistIds.toSet(),
                        error = null,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
            }
        }
    }

    private fun loadDiscover(page: Int, append: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            if (append) {
                _uiState.update { it.copy(loadingMoreDiscover = true) }
            } else {
                _uiState.update { it.copy(loadingDiscover = true, error = null) }
            }
            try {
                fetchDiscoverInternal(page = page, append = append)
            } finally {
                _uiState.update { it.copy(loadingDiscover = false, loadingMoreDiscover = false) }
            }
        }
    }

    private suspend fun fetchDiscoverInternal(page: Int, append: Boolean) {
        val st = _uiState.value
        val category: String
        val mediaType: String?
        val sort: String?

        when (st.activeTab) {
            WatchTab.LIBRARY -> {
                category = "library"
                mediaType = st.libraryFilter.mediaType
                sort = st.librarySort.wire
            }
            WatchTab.MY_LIST -> {
                category = "watchlist"
                mediaType = null
                sort = null
            }
            WatchTab.MOVIES -> {
                category = if (st.selectedGenreId != null) "genre" else "movies"
                mediaType = "movie"
                sort = null
            }
            WatchTab.TV -> {
                category = when {
                    st.selectedNetworkId != null -> "network"
                    st.selectedGenreId != null -> "genre"
                    else -> "tv"
                }
                mediaType = "tv"
                sort = null
            }
            else -> return
        }

        val res = repository.getWatchDiscover(
            category = category,
            mediaType = mediaType,
            genreId = st.selectedGenreId,
            networkId = st.selectedNetworkId,
            page = page,
            sort = sort,
        )
        when (res) {
            is ApiResult.Success -> {
                val resp = res.data
                _uiState.update { cur ->
                    val mergedItems = if (append && !cur.discoverItems.isNullOrEmpty()) {
                        val existingKeys = cur.discoverItems.map { "${it.mediaType}:${it.id}:${it.jellyfinItemId}" }.toSet()
                        cur.discoverItems + resp.items.filter { "${it.mediaType}:${it.id}:${it.jellyfinItemId}" !in existingKeys }
                    } else {
                        resp.items
                    }
                    cur.copy(
                        discoverItems = mergedItems,
                        discoverGenres = if (resp.genres.isNotEmpty()) resp.genres else cur.discoverGenres,
                        discoverPage = resp.page,
                        discoverTotalPages = resp.totalPages,
                        error = null,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(error = res.message) }
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
                if (_uiState.value.activeTab == WatchTab.REQUESTS) {
                    _uiState.update { it.copy(error = res.message) }
                }
            }
        }
    }

    private fun syncMovieStatus(movieId: Int, status: SeerrStatus) {
        _uiState.update { state ->
            state.copy(
                searchResults = state.searchResults?.map { item ->
                    if (item.id == movieId && item.isMovie) item.copy(status = status) else item
                },
                watchHome = state.watchHome?.let { h ->
                    h.copy(
                        hero = h.hero?.let { hero ->
                            if (hero.id == movieId && hero.isMovie) hero.copy(status = status) else hero
                        },
                        rails = h.rails.map { rail ->
                            rail.copy(
                                items = rail.items.map { it ->
                                    if (it.id == movieId && it.isMovie) it.copy(status = status) else it
                                },
                            )
                        },
                    )
                },
                discoverItems = state.discoverItems?.map { item ->
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
