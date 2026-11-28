package com.neekhar.pccontrol.feature.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcIconButton
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.tvremote.TvNowPlayingBar
import com.neekhar.pccontrol.feature.tvremote.TvRemoteUiState
import com.neekhar.pccontrol.feature.tvremote.TvRemoteViewModel
import com.neekhar.pccontrol.feature.watch.downloads.OfflineDownloadStatus
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchOfflineHelpers
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeerrScreen(
    viewModel: SeerrViewModel,
    onBack: () -> Unit,
    onOpenDetail: (mediaType: String, id: Int, seed: SeerrItem?) -> Unit,
    onPlayOnPhone: (jellyfinItemId: String?, tmdbId: Int?, mediaType: String?, season: Int?, episode: Int?, preferOffline: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    tvRemoteViewModel: TvRemoteViewModel? = null,
    onOpenTvRemote: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val offlineItems by viewModel.offlineDownloads.collectAsStateWithLifecycle()
    val tvState: TvRemoteUiState? = if (tvRemoteViewModel != null) {
        tvRemoteViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        null
    }
    val tvConnected = tvState?.connected == true
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var pendingDeleteItem by remember { mutableStateOf<OfflineWatchItem?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val tryPlayOnlineOrOffline: (String?, Int?, String?, Int?, Int?) -> Unit = { jfId, tmdbId, mType, season, episode ->
        val local = viewModel.findCompletedOffline(jfId, tmdbId ?: 0, mType ?: "movie")
        if (local != null) {
            onPlayOnPhone(local.jellyfinItemId, null, local.mediaType, null, null, true)
        } else if (!viewModel.isTailscaleActive()) {
            viewModel.showTailscaleRequiredNotice()
        } else {
            onPlayOnPhone(jfId, tmdbId, mType, season, episode, false)
        }
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResume()
        onPauseOrDispose {}
    }

    if (tvRemoteViewModel != null) {
        LifecycleResumeEffect(tvRemoteViewModel) {
            tvRemoteViewModel.setMiniBarOwnerActive("seerr", true)
            onPauseOrDispose {
                tvRemoteViewModel.setMiniBarOwnerActive("seerr", false)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openTvRemoteEvents.collect {
            tvRemoteViewModel?.notifyPlaybackStarted()
            onOpenTvRemote()
        }
    }

    val openWatchItem: (WatchItem) -> Unit = { item ->
        if (item.id > 0) {
            onOpenDetail(item.mediaType, item.id, item.toSeedItem(viewModel::resolveImageUrl))
        }
    }

    val statusText = when {
        !state.playOnTvNotice.isNullOrBlank() -> state.playOnTvNotice
        !state.error.isNullOrBlank() -> "Offline"
        else -> null
    }
    val statusTone = if (!state.playOnTvNotice.isNullOrBlank()) PcTone.Ok else PcTone.Bad

    PcScaffold(
        modifier = modifier,
        title = "Watch",
        statusText = statusText,
        statusTone = statusTone,
        showStatusDot = statusText != null,
        onBack = onBack,
        actions = {
            PcIconButton(
                icon = if (state.isSearchMode) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (state.isSearchMode) "Close search" else "Search",
                onClick = {
                    if (state.isSearchMode) keyboard?.hide()
                    viewModel.toggleSearchExpanded()
                },
                tint = if (state.isSearchMode) colors.accent else colors.text,
                containerColor = if (state.isSearchMode) colors.accentSoft else Color.Transparent,
            )
            if (tvConnected) {
                PcIconButton(
                    icon = Icons.Rounded.Tv,
                    contentDescription = "Open TV Remote",
                    onClick = onOpenTvRemote,
                    tint = if (tvState?.isPlaying == true) colors.accent else colors.textDim,
                    containerColor = if (tvState?.isPlaying == true) colors.accentSoft else colors.surface,
                )
            }
        },
        listState = listState,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = {
                viewModel.refresh()
                tvRemoteViewModel?.refreshNow(userInitiated = false)
            },
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    containerColor = colors.surfaceRaised,
                    color = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPad),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = topPad,
                    bottom = 36.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.tailscaleNotice.isNullOrBlank()) {
                    item(key = "tailscale_notice") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcErrorBanner(
                                title = "Tailscale required",
                                message = state.tailscaleNotice,
                                tone = PcTone.Warn,
                                actionLabel = "Dismiss",
                                onAction = viewModel::dismissTailscaleNotice,
                            )
                        }
                    }
                }

                if (!state.error.isNullOrBlank()) {
                    item(key = "error") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcErrorBanner(title = "Watch", message = state.error)
                        }
                    }
                }

                if (tvRemoteViewModel != null && tvState != null && tvState.isPlaying) {
                    item(key = "tv_now_playing_bar") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            TvNowPlayingBar(
                                state = tvState,
                                resolveImageUrl = tvRemoteViewModel::resolveImageUrl,
                                onTogglePlayPause = tvRemoteViewModel::togglePlayPause,
                                onOpenRemote = onOpenTvRemote,
                            )
                        }
                    }
                }

                // Expandable native search bar (3-char debounce + immediate IME search)
                if (state.isSearchMode) {
                    item(key = "search_bar") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            SeerrSearchBar(
                                query = state.query,
                                searching = state.searching,
                                onQueryChange = viewModel::onQueryChange,
                                onSearchSubmit = {
                                    keyboard?.hide()
                                    viewModel.submitSearch()
                                },
                                onClear = {
                                    keyboard?.hide()
                                    viewModel.clearSearch()
                                },
                            )
                        }
                    }
                }

                // Active search results view
                if (state.searchResults != null) {
                    val allResults = state.searchResults!!
                    val filtered = state.filteredSearchResults

                    if (allResults.isNotEmpty()) {
                        val visibleSearchFilters = SeerrSearchMediaFilter.entries.mapNotNull { filter ->
                            val count = when (filter) {
                                SeerrSearchMediaFilter.ALL -> allResults.size
                                SeerrSearchMediaFilter.MOVIE -> allResults.count { it.isMovie }
                                SeerrSearchMediaFilter.TV -> allResults.count { it.isTv }
                                SeerrSearchMediaFilter.COLLECTION -> allResults.count { it.isCollection }
                            }
                            if (filter == SeerrSearchMediaFilter.ALL || count > 0) filter to count else null
                        }
                        item(key = "search_filters") {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(visibleSearchFilters, key = { it.first.name }) { (filter, count) ->
                                    SeerrFilterChip(
                                        label = filter.label,
                                        count = count,
                                        selected = state.searchMediaFilter == filter,
                                        onClick = { viewModel.selectSearchMediaFilter(filter) },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "results_header") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcSectionLabel(label = "Results")
                        }
                    }

                    if (filtered.isEmpty()) {
                        item(key = "no_matches") {
                            Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                PcCard(modifier = Modifier.fillMaxWidth()) {
                                    PcEmptyState(
                                        icon = Icons.Rounded.Search,
                                        title = "No matches",
                                        body = "Try a different title or media filter",
                                    )
                                }
                            }
                        }
                    } else {
                        val gridRows = filtered.chunked(3)
                        items(
                            items = gridRows,
                            key = { row ->
                                "search_grid:" + row.joinToString(",") { "${it.mediaType}:${it.id}" }
                            },
                        ) { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PcDimens.ScreenPad),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (item in rowItems) {
                                    SeerrPosterGridCard(
                                        item = item,
                                        onClick = {
                                            if (item.isCollection) {
                                                viewModel.openCollection(item.id, item.title, item.poster)
                                            } else {
                                                onOpenDetail(item.mediaType, item.id, item)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    // Top filter pill strip: All · In Library · Downloads · Movies · TV · My List · Requests
                    item(key = "watch_tabs") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(WatchTab.entries, key = { it.id }) { tab ->
                                val badgeCount = when (tab) {
                                    WatchTab.DOWNLOADS -> offlineItems.size.takeIf { it > 0 }
                                    WatchTab.REQUESTS -> state.pendingRequestsBadge.takeIf { it > 0 }
                                    WatchTab.MY_LIST -> state.watchlistIds.size.takeIf { it > 0 }
                                    WatchTab.LIBRARY -> state.watchHome?.libraryCount?.takeIf { it > 0 }
                                    else -> null
                                }
                                SeerrFilterChip(
                                    label = tab.label,
                                    count = badgeCount,
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                )
                            }
                        }
                    }

                    when (state.activeTab) {
                        WatchTab.ALL -> {
                            val home = state.watchHome
                            if (home == null && state.loadingHome) {
                                item(key = "home_hero_skeleton") {
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        WatchHeroSkeleton()
                                    }
                                }
                                items(2, key = { "home_rail_skel_$it" }) {
                                    WatchRailSkeleton()
                                }
                            } else if (home != null) {
                                // 1. Hero Billboard Card
                                val hero = home.hero
                                if (hero != null) {
                                    item(key = "hero_billboard") {
                                        val heroKey = SeerrHelpers.watchlistKey(hero.mediaType, hero.id)
                                        val inWatchlist = heroKey in state.watchlistIds
                                        val heroOffline = viewModel.findCompletedOffline(hero.jellyfinItemId, hero.id, hero.mediaType)
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            WatchHeroBillboard(
                                                item = hero,
                                                resolveImageUrl = viewModel::resolveImageUrl,
                                                tvConnected = tvConnected,
                                                isDownloadedOffline = heroOffline != null,
                                                inWatchlist = inWatchlist,
                                                busyWatchlist = state.busyWatchlistKey == heroKey,
                                                busyPlayOnTv = state.playingOnTvKey == "hero:${hero.mediaType}:${hero.id}",
                                                busyRequest = state.busyMovieId == hero.id,
                                                onPlayOnPhone = {
                                                    tryPlayOnlineOrOffline(
                                                        hero.jellyfinItemId,
                                                        hero.id.takeIf { it > 0 },
                                                        hero.mediaType,
                                                        null,
                                                        null,
                                                    )
                                                },
                                                onPlayOnTv = { viewModel.playHeroOnTv(hero) },
                                                onRequest = {
                                                    if (hero.isMovie && SeerrHelpers.isRequestableMovie(hero.status)) {
                                                        viewModel.requestMovie(hero.id)
                                                    } else {
                                                        openWatchItem(hero)
                                                    }
                                                },
                                                onToggleWatchlist = { viewModel.toggleWatchlist(hero) },
                                                onOpenDetail = { openWatchItem(hero) },
                                            )
                                        }
                                    }
                                }

                                // 2. Continue Watching horizontal rail
                                if (home.continueWatching.isNotEmpty()) {
                                    item(key = "cw_header") {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            PcSectionLabel(label = "Continue watching")
                                        }
                                    }
                                    item(key = "cw_rail") {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            items(
                                                items = home.continueWatching,
                                                key = { "cw:${it.jellyfinItemId}" },
                                            ) { cw ->
                                                WatchContinueCard(
                                                    item = cw,
                                                    resolveImageUrl = viewModel::resolveImageUrl,
                                                    tvConnected = tvConnected,
                                                    busyPlayOnTv = state.playingOnTvKey == "cw:${cw.jellyfinItemId}",
                                                    onPlayOnPhone = {
                                                        tryPlayOnlineOrOffline(
                                                            cw.jellyfinItemId,
                                                            cw.id.takeIf { it > 0 },
                                                            if (cw.isTv) "episode" else "movie",
                                                            cw.seasonNumber,
                                                            cw.episodeNumber,
                                                        )
                                                    },
                                                    onOpenDetail = {
                                                        if (cw.id > 0) {
                                                            onOpenDetail(
                                                                cw.mediaType,
                                                                cw.id,
                                                                cw.toSeedItem(viewModel::resolveImageUrl),
                                                            )
                                                        } else {
                                                            tryPlayOnlineOrOffline(
                                                                cw.jellyfinItemId,
                                                                null,
                                                                if (cw.isTv) "episode" else "movie",
                                                                cw.seasonNumber,
                                                                cw.episodeNumber,
                                                            )
                                                        }
                                                    },
                                                    onPlayOnTv = { viewModel.playContinueOnTv(cw) },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 3. Recently Added horizontal poster rail
                                if (home.recentlyAdded.isNotEmpty()) {
                                    item(key = "recent_header") {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            PcSectionLabel(
                                                label = "Recently added",
                                                trailing = {
                                                    WatchRailSeeAllButton(
                                                        label = "See all",
                                                        onClick = { viewModel.selectTab(WatchTab.LIBRARY) },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    item(key = "recent_rail") {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            items(
                                                items = home.recentlyAdded,
                                                key = { "latest:${it.jellyfinItemId ?: it.id}" },
                                            ) { item ->
                                                WatchPosterRailCard(
                                                    item = item,
                                                    resolveImageUrl = viewModel::resolveImageUrl,
                                                    onClick = { openWatchItem(item) },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 4. Downloading Now compact rail (only shown when active downloads exist)
                                if (home.downloads.isNotEmpty()) {
                                    item(key = "dl_header") {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            PcSectionLabel(label = "Downloading now")
                                        }
                                    }
                                    item(key = "dl_rail") {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            items(
                                                items = home.downloads,
                                                key = { "dl:${it.mediaType}:${it.tmdbId}" },
                                            ) { dl ->
                                                WatchDownloadRailCard(
                                                    item = dl,
                                                    onClick = {
                                                        if (dl.tmdbId > 0) {
                                                            onOpenDetail(dl.mediaType, dl.tmdbId, dl.toSeedItem())
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 5. Discovery Carousels (Trending, Popular Movies, Popular TV, Upcoming, Genres, Networks)
                                home.rails.forEach { rail ->
                                    if (rail.items.isNotEmpty()) {
                                        item(key = "rail_header:${rail.id}") {
                                            Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                                PcSectionLabel(
                                                    label = rail.title,
                                                    trailing = if (rail.id != "trending" && rail.id != "upcoming_movies") {
                                                        {
                                                            WatchRailSeeAllButton(
                                                                label = "See all",
                                                                onClick = { viewModel.openRailSeeAll(rail) },
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            }
                                        }
                                        item(key = "rail_row:${rail.id}") {
                                            LazyRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                items(
                                                    items = rail.items,
                                                    key = { "${rail.id}:${it.mediaType}:${it.id}" },
                                                ) { item ->
                                                    WatchPosterRailCard(
                                                        item = item,
                                                        resolveImageUrl = viewModel::resolveImageUrl,
                                                        onClick = { openWatchItem(item) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                item(key = "home_empty") {
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        PcCard(modifier = Modifier.fillMaxWidth()) {
                                            PcEmptyState(
                                                icon = Icons.Rounded.Movie,
                                                title = "Nothing to show yet",
                                                body = "Pull to refresh or use Search above to find movies and TV shows",
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        WatchTab.DOWNLOADS -> {
                            val activeList = offlineItems.filter { it.isInProgressOrPaused }
                            val completedList = offlineItems.filter { it.isCompleted }

                            if (offlineItems.isNotEmpty()) {
                                item(key = "offline_storage_header") {
                                    val totalBytes = viewModel.offlineStorageBytes()
                                    val summaryText = WatchOfflineHelpers.formatStorageSummary(
                                        totalBytes = totalBytes,
                                        completedCount = completedList.size,
                                    )
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        PcCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.OfflinePin,
                                                    contentDescription = null,
                                                    tint = colors.accent,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = "Offline storage",
                                                        color = colors.text,
                                                        style = type.cardTitle,
                                                    )
                                                    Text(
                                                        text = summaryText,
                                                        color = colors.textDim,
                                                        style = type.caption.tabular(),
                                                    )
                                                }
                                                PcButton(
                                                    label = "Delete all",
                                                    onClick = { confirmDeleteAll = true },
                                                    variant = PcButtonVariant.Danger,
                                                    compact = true,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (activeList.isNotEmpty()) {
                                item(key = "offline_active_header") {
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        PcSectionLabel(label = "Downloading")
                                    }
                                }
                                items(activeList, key = { "off_act:${it.jellyfinItemId}" }) { dl ->
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        WatchOfflineActiveCard(
                                            item = dl,
                                            onPause = { viewModel.pauseOfflineDownload(dl.jellyfinItemId) },
                                            onResume = { viewModel.resumeOfflineDownload(dl.jellyfinItemId) },
                                            onCancel = { viewModel.cancelOfflineDownload(dl.jellyfinItemId) },
                                        )
                                    }
                                }
                            }

                            if (completedList.isNotEmpty()) {
                                item(key = "offline_done_header") {
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        PcSectionLabel(label = "Downloaded")
                                    }
                                }
                                items(completedList, key = { "off_done:${it.jellyfinItemId}" }) { dl ->
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        WatchOfflineCompletedCard(
                                            item = dl,
                                            onPlay = {
                                                onPlayOnPhone(
                                                    dl.jellyfinItemId,
                                                    dl.tmdbId.takeIf { it > 0 },
                                                    dl.mediaType,
                                                    dl.seasonNumber,
                                                    dl.episodeNumber,
                                                    true,
                                                )
                                            },
                                            onDelete = { pendingDeleteItem = dl },
                                        )
                                    }
                                }
                            }

                            if (offlineItems.isEmpty()) {
                                item(key = "offline_empty") {
                                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                        PcCard(modifier = Modifier.fillMaxWidth()) {
                                            PcEmptyState(
                                                icon = Icons.Rounded.Download,
                                                title = "No offline downloads",
                                                body = "Download movies or episodes over Tailscale to watch anywhere, even without internet",
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        WatchTab.LIBRARY, WatchTab.MOVIES, WatchTab.TV, WatchTab.MY_LIST -> {
                            // Sub-filters for In Library (media type + sort)
                            if (state.activeTab == WatchTab.LIBRARY) {
                                item(key = "library_filters") {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(WatchLibraryFilter.entries, key = { "lf:${it.name}" }) { f ->
                                            SeerrFilterChip(
                                                label = f.label,
                                                selected = state.libraryFilter == f,
                                                onClick = { viewModel.selectLibraryFilter(f) },
                                            )
                                        }
                                        items(WatchLibrarySort.entries, key = { "ls:${it.name}" }) { s ->
                                            SeerrFilterChip(
                                                label = s.label,
                                                selected = state.librarySort == s,
                                                onClick = { viewModel.selectLibrarySort(s) },
                                            )
                                        }
                                    }
                                }
                            }

                            // Active Network chip or Genre chips for Movies / TV
                            if (state.activeTab == WatchTab.MOVIES || state.activeTab == WatchTab.TV) {
                                if (!state.selectedNetworkLabel.isNullOrBlank()) {
                                    item(key = "network_filter_chip") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = PcDimens.ScreenPad),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            SeerrFilterChip(
                                                label = "${state.selectedNetworkLabel}  ×",
                                                selected = true,
                                                onClick = viewModel::clearNetworkFilter,
                                            )
                                        }
                                    }
                                } else if (state.discoverGenres.isNotEmpty()) {
                                    item(key = "genre_chips") {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            item(key = "genre_all") {
                                                SeerrFilterChip(
                                                    label = "All genres",
                                                    selected = state.selectedGenreId == null,
                                                    onClick = { viewModel.selectGenre(null) },
                                                )
                                            }
                                            items(state.discoverGenres, key = { "g:${it.id}" }) { genre ->
                                                SeerrFilterChip(
                                                    label = genre.name,
                                                    selected = state.selectedGenreId == genre.id,
                                                    onClick = { viewModel.selectGenre(genre.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            val items = state.discoverItems
                            when {
                                state.loadingDiscover && items == null -> {
                                    items(3, key = { "disc_skel_$it" }) {
                                        WatchGridRowSkeleton()
                                    }
                                }
                                items.isNullOrEmpty() -> {
                                    item(key = "discover_empty") {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                                val emptyTitle = when (state.activeTab) {
                                                    WatchTab.MY_LIST -> "Your list is empty"
                                                    WatchTab.LIBRARY -> "Library is empty"
                                                    else -> "Nothing found"
                                                }
                                                val emptyBody = when (state.activeTab) {
                                                    WatchTab.MY_LIST -> "Tap + My List on any movie or TV show to save it here"
                                                    WatchTab.LIBRARY -> "Available titles in Jellyfin will appear here"
                                                    else -> "Try choosing a different genre or pull to refresh"
                                                }
                                                PcEmptyState(
                                                    icon = Icons.Rounded.Movie,
                                                    title = emptyTitle,
                                                    body = emptyBody,
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    val gridRows = items.chunked(3)
                                    items(
                                        items = gridRows,
                                        key = { row ->
                                            "disc_grid:" + row.joinToString(",") { "${it.mediaType}:${it.id}:${it.jellyfinItemId.orEmpty()}" }
                                        },
                                    ) { rowItems ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = PcDimens.ScreenPad),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            for (item in rowItems) {
                                                WatchPosterGridCard(
                                                    item = item,
                                                    resolveImageUrl = viewModel::resolveImageUrl,
                                                    onClick = { openWatchItem(item) },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            repeat(3 - rowItems.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }

                                    if (state.hasMoreDiscover) {
                                        item(key = "load_more_discover") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = PcDimens.ScreenPad, vertical = 4.dp),
                                            ) {
                                                PcButton(
                                                    label = "Load more",
                                                    onClick = viewModel::loadMoreDiscover,
                                                    variant = PcButtonVariant.Subtle,
                                                    busy = state.loadingMoreDiscover,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        WatchTab.REQUESTS -> {
                            // Preserved full Requests experience: status filter chips + request rows
                            item(key = "status_filters") {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(state.visibleFilterTabs, key = { it.wire }) { tab ->
                                        SeerrFilterChip(
                                            label = tab.label,
                                            count = state.counts?.get(tab.wire),
                                            selected = state.statusFilter == tab,
                                            onClick = { viewModel.selectStatusFilter(tab) },
                                        )
                                    }
                                }
                            }

                            item(key = "requests_header") {
                                val sectionTitle = if (state.statusFilter == SeerrRequestFilter.ALL) {
                                    "Requests"
                                } else {
                                    "${state.statusFilter.label} requests"
                                }
                                Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                    PcSectionLabel(
                                        label = sectionTitle,
                                        trailing = {
                                            when {
                                                state.loadingFilter -> {
                                                    CircularProgressIndicator(
                                                        color = colors.accent,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                                state.requests != null -> {
                                                    Text(
                                                        text = state.requests!!.size.toString(),
                                                        color = colors.textFaint,
                                                        style = type.caption.tabular(),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            val requests = state.requests
                            when {
                                requests == null -> {
                                    items(3, key = { "skel_$it" }) {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            SeerrRowSkeleton()
                                        }
                                    }
                                }
                                requests.isEmpty() -> {
                                    item(key = "empty_requests") {
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                                PcEmptyState(
                                                    icon = Icons.Rounded.Movie,
                                                    title = if (state.statusFilter == SeerrRequestFilter.ALL) {
                                                        "No requests yet"
                                                    } else {
                                                        "No ${state.statusFilter.label.lowercase()} requests"
                                                    },
                                                    body = "Tap Search above to request movies, TV shows, or collections",
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    items(
                                        items = requests,
                                        key = { "req:${it.id}" },
                                    ) { req ->
                                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                            SeerrRequestRow(
                                                request = req,
                                                dimmed = state.loadingFilter,
                                                onClick = {
                                                    onOpenDetail(
                                                        req.mediaType,
                                                        req.tmdbId,
                                                        req.toSeedItem(),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    SeerrCollectionSheet(
        state = state.collectionSheet,
        onRequestMovie = viewModel::requestCollectionMovie,
        onRequestAllMissing = viewModel::requestAllMissingInCollection,
        onSelectMovie = { movie ->
            viewModel.closeCollection()
            onOpenDetail("movie", movie.id, movie)
        },
        onDismiss = viewModel::closeCollection,
    )

    pendingDeleteItem?.let { del ->
        PcConfirmDialog(
            title = "Delete offline download?",
            message = "Remove \"${del.title}\" (${WatchOfflineHelpers.formatBytes(del.downloadedBytes)}) from this phone?",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                viewModel.deleteOfflineDownload(del.jellyfinItemId)
                pendingDeleteItem = null
            },
            onDismiss = { pendingDeleteItem = null },
        )
    }

    if (confirmDeleteAll) {
        PcConfirmDialog(
            title = "Delete all offline downloads?",
            message = "Remove all downloaded movies and episodes from this phone?",
            confirmLabel = "Delete all",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                viewModel.deleteAllOfflineDownloads()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun WatchHeroBillboard(
    item: WatchItem,
    resolveImageUrl: (String?) -> String?,
    tvConnected: Boolean,
    isDownloadedOffline: Boolean,
    inWatchlist: Boolean,
    busyWatchlist: Boolean,
    busyPlayOnTv: Boolean,
    busyRequest: Boolean,
    onPlayOnPhone: () -> Unit,
    onPlayOnTv: () -> Unit,
    onRequest: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val artUrl = resolveImageUrl(item.backdrop ?: item.poster)
    val badge = SeerrHelpers.statusBadge(item.status)
    val ratingStr = SeerrHelpers.formatVoteAverage(item.voteAverage)
    val isAvailable = item.status == SeerrStatus.AVAILABLE
    val canRequest = !isAvailable && (
        (item.isMovie && SeerrHelpers.isRequestableMovie(item.status)) || item.isTv
        )

    val metaLine = buildList {
        item.year?.let { add(it.toString()) }
        ratingStr?.let { add("★ $it") }
        add(if (item.isTv) "TV Show" else "Movie")
    }.joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .clip(RoundedCornerShape(PcDimens.RadiusLg))
            .background(colors.surface)
            .clickable(onClick = onOpenDetail),
    ) {
        if (!artUrl.isNullOrBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (item.isTv) Icons.Rounded.Tv else Icons.Rounded.Movie,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center),
            )
        }

        // Subtle dark scrim over photography for crisp text legibility (no decorative glow)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.10f),
                        0.38f to Color.Black.copy(alpha = 0.25f),
                        0.70f to Color.Black.copy(alpha = 0.78f),
                        1.0f to Color.Black.copy(alpha = 0.95f),
                    ),
                ),
        )

        // Top-left availability pill
        if (badge != null) {
            val (dotColor, _) = seerrToneColors(badge.tone)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.mediaChip)
                    .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = if (isAvailable) "In Library" else badge.label,
                    color = colors.onMedia,
                    style = type.micro,
                )
            }
        }

        // Bottom content overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    color = colors.onMedia,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.3).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        color = colors.onMediaDim,
                        style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                if (item.overview.isNotBlank()) {
                    Text(
                        text = item.overview,
                        color = colors.onMediaDim,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val showPlayPrimary = isAvailable || isDownloadedOffline
                val showRequestPrimary = !showPlayPrimary && canRequest

                when {
                    showPlayPrimary -> {
                        PcButton(
                            label = if (isDownloadedOffline) "Play offline" else "Play",
                            onClick = onPlayOnPhone,
                            variant = PcButtonVariant.Primary,
                            icon = Icons.Rounded.PlayArrow,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    showRequestPrimary -> {
                        PcButton(
                            label = "Request",
                            onClick = onRequest,
                            variant = PcButtonVariant.Primary,
                            icon = Icons.Rounded.Add,
                            busy = busyRequest,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    else -> {
                        PcButton(
                            label = "Details",
                            onClick = onOpenDetail,
                            variant = PcButtonVariant.Primary,
                            icon = Icons.Rounded.Info,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Secondary Play on TV icon button when available and TV connected
                if (isAvailable && tvConnected) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(PcDimens.RadiusSm))
                            .background(colors.mediaChip)
                            .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(PcDimens.RadiusSm))
                            .clickable(enabled = !busyPlayOnTv, onClick = onPlayOnTv),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busyPlayOnTv) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = "Play on TV",
                                tint = colors.onMedia,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // My List toggle
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(PcDimens.RadiusSm))
                        .background(if (inWatchlist) colors.accentSoft else colors.mediaChip)
                        .border(
                            width = 0.5.dp,
                            color = if (inWatchlist) colors.accent.copy(alpha = 0.5f) else colors.mediaChipBorder,
                            shape = RoundedCornerShape(PcDimens.RadiusSm),
                        )
                        .clickable(enabled = !busyWatchlist, onClick = onToggleWatchlist)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (busyWatchlist) {
                        CircularProgressIndicator(
                            color = colors.onMedia,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = if (inWatchlist) "In My List" else "Add to My List",
                            tint = if (inWatchlist) colors.accent else colors.onMedia,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = if (inWatchlist) "My List" else "My List",
                        color = if (inWatchlist) colors.accent else colors.onMedia,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                // Info icon button when primary button is Play or Request
                if (showPlayPrimary || showRequestPrimary) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(PcDimens.RadiusSm))
                            .background(colors.mediaChip)
                            .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(PcDimens.RadiusSm))
                            .clickable(onClick = onOpenDetail),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "Info",
                            tint = colors.onMedia,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchContinueCard(
    item: WatchContinueItem,
    resolveImageUrl: (String?) -> String?,
    tvConnected: Boolean,
    busyPlayOnTv: Boolean,
    onPlayOnPhone: () -> Unit,
    onOpenDetail: () -> Unit,
    onPlayOnTv: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val artUrl = resolveImageUrl(item.backdrop ?: item.poster)
    val remaining = SeerrHelpers.formatRemainingMinutes(item.remainingMinutes)
    val subLine = buildList {
        if (!item.episodeLabel.isNullOrBlank()) add(item.episodeLabel)
        if (item.isNextUp && !item.episodeTitle.isNullOrBlank()) {
            add(item.episodeTitle)
        } else if (remaining != null) {
            add(remaining)
        } else if (!item.episodeTitle.isNullOrBlank()) {
            add(item.episodeTitle)
        }
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .width(214.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.surfaceRaised)
                .clickable(onClick = onPlayOnPhone),
        ) {
            if (!artUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (item.isTv) Icons.Rounded.Tv else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center),
                )
            }

            // Subtle dark scrim at bottom for progress bar contrast
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )

            // Center Play button (plays directly on phone)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play on phone",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Episode / Next Up pill
            val cornerBadge = when {
                item.isNextUp -> item.episodeLabel?.let { "Next · $it" } ?: "Next up"
                !item.episodeLabel.isNullOrBlank() -> item.episodeLabel
                else -> null
            }
            if (!cornerBadge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = cornerBadge,
                        color = colors.onMedia,
                        style = type.micro.tabular(),
                        maxLines = 1,
                    )
                }
            }

            // Secondary Play on TV button when projector/TV is connected
            if (tvConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, CircleShape)
                        .clickable(enabled = !busyPlayOnTv, onClick = onPlayOnTv),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busyPlayOnTv) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = "Play on TV",
                            tint = colors.onMedia,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            // Mint progress bar pinned to bottom of thumbnail
            if (item.progressPct > 0.0) {
                val frac = (item.progressPct / 100.0).toFloat().coerceIn(0.03f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(colors.accent),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                color = colors.text,
                style = type.cardTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subLine.isNotEmpty()) {
                Text(
                    text = subLine,
                    color = colors.textDim,
                    style = type.micro.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WatchOfflineActiveCard(
    item: OfflineWatchItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val isDownloading = item.status == OfflineDownloadStatus.DOWNLOADING
    val isQueued = item.status == OfflineDownloadStatus.QUEUED
    val isFailed = item.status == OfflineDownloadStatus.FAILED
    val barColor = if (isFailed) colors.bad else colors.accent
    val statusLine = buildList {
        when (item.status) {
            OfflineDownloadStatus.DOWNLOADING -> add("Downloading")
            OfflineDownloadStatus.QUEUED -> add("Queued")
            OfflineDownloadStatus.PAUSED -> add("Paused")
            OfflineDownloadStatus.FAILED -> add(item.errorMessage ?: "Failed")
            OfflineDownloadStatus.COMPLETED -> add("Downloaded")
        }
        if (item.totalBytes > 0L) {
            add("${item.progressPercent}%")
            add("${WatchOfflineHelpers.formatBytes(item.downloadedBytes)} / ${WatchOfflineHelpers.formatBytes(item.totalBytes)}")
        } else if (item.downloadedBytes > 0L) {
            add(WatchOfflineHelpers.formatBytes(item.downloadedBytes))
        }
        val spd = WatchOfflineHelpers.formatSpeed(item.speedBytesPerSec)
        if (isDownloading && spd.isNotEmpty()) add(spd)
    }.joinToString(" · ")

    PcCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.title,
                        color = colors.text,
                        style = type.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            color = colors.textDim,
                            style = type.caption.tabular(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = statusLine,
                        color = if (isFailed) colors.bad else colors.textFaint,
                        style = type.micro.tabular(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            PcProgressBar(
                progress = item.progressFraction,
                color = barColor,
                height = 4.dp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDownloading || isQueued) {
                    PcButton(
                        label = "Pause",
                        onClick = onPause,
                        variant = PcButtonVariant.Subtle,
                        icon = Icons.Rounded.Pause,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PcButton(
                        label = "Resume",
                        onClick = onResume,
                        variant = PcButtonVariant.Primary,
                        icon = Icons.Rounded.PlayArrow,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                PcButton(
                    label = "Cancel",
                    onClick = onCancel,
                    variant = PcButtonVariant.Danger,
                    icon = Icons.Rounded.Close,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun WatchOfflineCompletedCard(
    item: OfflineWatchItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val localArt = item.localStillPath?.takeIf { File(it).isFile }
        ?: item.localPosterPath?.takeIf { File(it).isFile }
    val sizeText = WatchOfflineHelpers.formatBytes(item.downloadedBytes.coerceAtLeast(item.totalBytes))
    val metaLine = buildList {
        if (!item.subtitle.isNullOrBlank()) add(item.subtitle)
        add(sizeText)
        if (item.subtitles.isNotEmpty()) {
            add("${item.subtitles.size} sub${if (item.subtitles.size == 1) "" else "s"}")
        }
    }.joinToString(" · ")

    PcCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        onClick = onPlay,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (localArt != null) {
                    AsyncImage(
                        model = File(localArt),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (item.mediaType == "episode") Icons.Rounded.Tv else Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play offline",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (item.resumeProgressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.resumeProgressFraction)
                                .fillMaxHeight()
                                .background(colors.accent),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    color = colors.text,
                    style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    color = colors.textDim,
                    style = type.micro.tabular(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete offline download",
                    tint = colors.textDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun WatchDownloadRailCard(
    item: WatchDownloadItem,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val pct = item.percent.roundToInt().coerceIn(0, 100)
    val barColor = if (item.status == "warning") colors.warn else colors.accent
    val subLine = buildList {
        if (!item.episodeLabel.isNullOrBlank()) add(item.episodeLabel)
        add("$pct%")
        if (!item.eta.isNullOrBlank()) add(item.eta)
    }.joinToString(" · ")

    PcCard(
        modifier = Modifier.width(236.dp),
        contentPadding = PaddingValues(10.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SeerrPoster(
                url = item.poster,
                mediaType = item.mediaType,
                size = SeerrPosterSize.SM,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    color = colors.text,
                    style = type.cardTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = subLine,
                        color = colors.textDim,
                        style = type.micro.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PcProgressBar(
                    progress = (item.percent / 100.0).toFloat().coerceIn(0f, 1f),
                    color = barColor,
                    height = 3.dp,
                )
            }
        }
    }
}

@Composable
private fun WatchPosterRailCard(
    item: WatchItem,
    resolveImageUrl: (String?) -> String?,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val posterUrl = resolveImageUrl(item.poster)
    val subLine = buildList {
        item.year?.let { add(it.toString()) }
        SeerrHelpers.formatVoteAverage(item.voteAverage)?.let { add("★ $it") }
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        WatchPosterBox(
            posterUrl = posterUrl,
            title = item.title,
            mediaType = item.mediaType,
            status = item.status,
        )
        Column(
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                color = colors.text,
                style = type.caption.copy(fontWeight = FontWeight.Medium, lineHeight = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subLine.isNotEmpty()) {
                Text(
                    text = subLine,
                    color = colors.textFaint,
                    style = type.micro.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WatchPosterGridCard(
    item: WatchItem,
    resolveImageUrl: (String?) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val posterUrl = resolveImageUrl(item.poster)
    val subLine = buildList {
        item.year?.let { add(it.toString()) }
        if (item.isTv) add("TV")
    }.joinToString(" · ")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        WatchPosterBox(
            posterUrl = posterUrl,
            title = item.title,
            mediaType = item.mediaType,
            status = item.status,
        )
        Column(
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                color = colors.text,
                style = type.caption.copy(fontWeight = FontWeight.Medium, lineHeight = 16.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subLine.isNotEmpty()) {
                Text(
                    text = subLine,
                    color = colors.textFaint,
                    style = type.micro.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WatchPosterBox(
    posterUrl: String?,
    title: String,
    mediaType: String,
    status: SeerrStatus,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val badge = SeerrHelpers.statusBadge(status)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceRaised),
    ) {
        Icon(
            imageVector = if (mediaType == "tv") Icons.Rounded.Tv else Icons.Rounded.Movie,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier
                .size(26.dp)
                .align(Alignment.Center),
        )
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (badge != null) {
            val (fg, _) = seerrToneColors(badge.tone)
            val showCompactDot = status == SeerrStatus.AVAILABLE ||
                status == SeerrStatus.GRABBING ||
                status == SeerrStatus.REQUESTED ||
                status == SeerrStatus.PENDING ||
                status == SeerrStatus.PARTIAL
            if (showCompactDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(fg),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(fg),
                    )
                    Text(
                        text = badge.label,
                        color = colors.onMedia,
                        style = type.micro.copy(fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchRailSeeAllButton(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Text(
        text = label,
        color = colors.accent,
        style = type.sectionLabel.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun WatchHeroSkeleton() {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .clip(RoundedCornerShape(PcDimens.RadiusLg))
            .background(colors.surface),
    )
}

@Composable
private fun WatchRailSkeleton() {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .padding(horizontal = PcDimens.ScreenPad)
                .width(120.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceRaised),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface),
                )
            }
        }
    }
}

@Composable
private fun WatchGridRowSkeleton() {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PcDimens.ScreenPad),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface),
            )
        }
    }
}

@Composable
private fun SeerrSearchBar(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(20.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Search movies, TV, or collections",
                    color = colors.textFaint,
                    style = type.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                textStyle = type.body.copy(color = colors.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            searching -> {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
            query.isNotEmpty() -> {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cancel,
                        contentDescription = "Clear search",
                        tint = colors.textFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeerrPosterGridCard(
    item: SeerrItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val badge = if (item.isCollection) null else SeerrHelpers.statusBadge(item.status)
    val fallbackIcon = when {
        item.isCollection -> Icons.Rounded.CollectionsBookmark
        item.isTv -> Icons.Rounded.Tv
        else -> Icons.Rounded.Movie
    }
    val subLine = buildList {
        item.year?.let { add(it.toString()) }
        if (item.isTv) add("TV")
    }.joinToString(" · ")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceRaised),
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier
                    .size(26.dp)
                    .align(Alignment.Center),
            )
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.isCollection) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surfaceRaised.copy(alpha = 0.92f))
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Collection",
                        color = colors.textDim,
                        style = type.micro.copy(fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            } else if (badge != null) {
                val (fg, _) = seerrToneColors(badge.tone)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(fg),
                    )
                    Text(
                        text = badge.label,
                        color = colors.onMedia,
                        style = type.micro.copy(fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                color = colors.text,
                style = type.caption.copy(fontWeight = FontWeight.Medium, lineHeight = 16.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subLine.isNotEmpty()) {
                Text(
                    text = subLine,
                    color = colors.textFaint,
                    style = type.micro.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SeerrRequestRow(
    request: SeerrRequestItem,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val seasonTag = SeerrHelpers.formatSeasonTag(request.mediaType, request.seasons)
    val age = SeerrHelpers.formatIsoAge(request.createdAt)
    val metaLine = buildList {
        add(seasonTag)
        request.year?.let { add(it.toString()) }
        if (request.requestedBy.isNotBlank()) add(request.requestedBy)
        if (age.isNotBlank()) add(age)
    }.joinToString(" · ")

    PcCard(
        modifier = Modifier.alpha(if (dimmed) 0.65f else 1f),
        contentPadding = PaddingValues(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeerrPoster(
                url = request.poster,
                mediaType = request.mediaType,
                size = SeerrPosterSize.SM,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = request.title,
                    color = colors.text,
                    style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    color = colors.textFaint,
                    style = type.caption.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SeerrStatusPill(status = request.status)
        }
    }
}

private fun SeerrRequestItem.toSeedItem(): SeerrItem = SeerrItem(
    id = tmdbId,
    mediaType = mediaType,
    title = title,
    year = year,
    poster = poster,
    overview = "",
    status = status,
)
