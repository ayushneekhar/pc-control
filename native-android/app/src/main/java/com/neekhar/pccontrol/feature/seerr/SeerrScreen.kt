package com.neekhar.pccontrol.feature.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeerrScreen(
    viewModel: SeerrViewModel,
    onBack: () -> Unit,
    onOpenDetail: (mediaType: String, id: Int, seed: SeerrItem?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalPcColors.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        PcScreenHeader(
            title = "Seerr",
            statusText = state.counts?.get("all")?.let { "$it total requests" },
            onBack = onBack,
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 4.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.error.isNullOrBlank()) {
                    item(key = "error") {
                        PcErrorBanner(title = "Seerr", message = state.error)
                    }
                }

                // Native search bar with 3-char debounce + immediate IME search
                item(key = "search_bar") {
                    SeerrSearchBar(
                        query = state.query,
                        searching = state.searching,
                        onQueryChange = viewModel::onQueryChange,
                        onSearchSubmit = {
                            keyboard?.hide()
                            viewModel.submitSearch()
                        },
                        onClear = viewModel::clearSearch,
                    )
                }

                if (state.searchResults != null) {
                    val allResults = state.searchResults!!
                    val filtered = state.filteredSearchResults

                    if (allResults.isNotEmpty()) {
                        item(key = "search_filters") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SeerrSearchMediaFilter.entries.forEach { filter ->
                                    val count = when (filter) {
                                        SeerrSearchMediaFilter.ALL -> allResults.size
                                        SeerrSearchMediaFilter.MOVIE -> allResults.count { it.isMovie }
                                        SeerrSearchMediaFilter.TV -> allResults.count { it.isTv }
                                        SeerrSearchMediaFilter.COLLECTION -> allResults.count { it.isCollection }
                                    }
                                    if (filter == SeerrSearchMediaFilter.ALL || count > 0) {
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
                    }

                    item(key = "results_header") {
                        PcSectionLabel(label = "Results (${filtered.size})")
                    }

                    if (filtered.isEmpty()) {
                        item(key = "no_matches") {
                            PcCard(modifier = Modifier.fillMaxWidth()) {
                                PcEmptyState(
                                    icon = Icons.Filled.Search,
                                    title = "No matches",
                                    body = "Try a different title or media filter",
                                )
                            }
                        }
                    } else {
                        items(
                            items = filtered,
                            key = { "${it.mediaType}:${it.id}" },
                        ) { item ->
                            SeerrSearchResultRow(
                                item = item,
                                busy = state.busyMovieId == item.id && item.isMovie,
                                onClick = {
                                    if (item.isCollection) {
                                        viewModel.openCollection(item.id, item.title, item.poster)
                                    } else {
                                        onOpenDetail(item.mediaType, item.id, item)
                                    }
                                },
                                onQuickRequest = { viewModel.requestMovie(item.id) },
                            )
                        }
                    }
                } else {
                    // Horizontal poster carousel of recent requests when viewing All
                    val recentWithPosters = state.requests
                        ?.filter { !it.poster.isNullOrBlank() }
                        ?.take(12)
                        .orEmpty()
                    if (state.statusFilter == SeerrRequestFilter.ALL && recentWithPosters.isNotEmpty()) {
                        item(key = "recent_carousel_header") {
                            PcSectionLabel(label = "Recent activity")
                        }
                        item(key = "recent_carousel") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(
                                    items = recentWithPosters,
                                    key = { "carousel:${it.id}" },
                                ) { req ->
                                    SeerrRecentPosterCard(
                                        request = req,
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

                    // Status filter chips
                    item(key = "status_filters") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.visibleFilterTabs.forEach { tab ->
                                SeerrFilterChip(
                                    label = tab.label,
                                    count = state.counts?.get(tab.wire),
                                    selected = state.statusFilter == tab,
                                    onClick = { viewModel.selectStatusFilter(tab) },
                                )
                            }
                        }
                    }

                    // Requests section header
                    item(key = "requests_header") {
                        val sectionTitle = if (state.statusFilter == SeerrRequestFilter.ALL) {
                            "Requests"
                        } else {
                            "${state.statusFilter.label} requests"
                        }
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
                                            style = TabularNumStyle.copy(fontSize = 12.sp),
                                        )
                                    }
                                }
                            },
                        )
                    }

                    val requests = state.requests
                    when {
                        requests == null -> {
                            items(3, key = { "skel_$it" }) {
                                SeerrRowSkeleton()
                            }
                        }
                        requests.isEmpty() -> {
                            item(key = "empty_requests") {
                                PcCard(modifier = Modifier.fillMaxWidth()) {
                                    PcEmptyState(
                                        icon = Icons.Filled.Movie,
                                        title = if (state.statusFilter == SeerrRequestFilter.ALL) {
                                            "No requests yet"
                                        } else {
                                            "No ${state.statusFilter.label.lowercase()} requests"
                                        },
                                        body = "Search above to request movies, TV shows, or collections",
                                    )
                                }
                            }
                        }
                        else -> {
                            items(
                                items = requests,
                                key = { "req:${it.id}" },
                            ) { req ->
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
            imageVector = Icons.Filled.Search,
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
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                ),
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
                        imageVector = Icons.Filled.Cancel,
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
private fun SeerrSearchResultRow(
    item: SeerrItem,
    busy: Boolean,
    onClick: () -> Unit,
    onQuickRequest: () -> Unit,
) {
    val colors = LocalPcColors.current
    val canQuickRequestMovie = item.isMovie && SeerrHelpers.isRequestableMovie(item.status)

    PcCard(
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeerrPoster(
                url = item.poster,
                mediaType = item.mediaType,
                size = SeerrPosterSize.SM,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = item.title,
                    color = colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (item.isCollection) {
                        PcPill(label = "Collection", tone = PcTone.Accent)
                    } else {
                        PcPill(
                            label = if (item.isTv) "TV" else "Movie",
                            tone = PcTone.Faint,
                        )
                    }
                    item.year?.let { year ->
                        Text(
                            text = year.toString(),
                            color = colors.textFaint,
                            style = TabularNumStyle.copy(fontSize = 12.sp),
                        )
                    }
                    SeerrRatingInline(voteAverage = item.voteAverage)
                    if (!item.isCollection) {
                        SeerrStatusPill(status = item.status)
                    }
                }
            }
            if (canQuickRequestMovie) {
                PcButton(
                    label = "Request",
                    onClick = onQuickRequest,
                    variant = PcButtonVariant.Primary,
                    compact = true,
                    busy = busy,
                    enabled = !busy,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SeerrRecentPosterCard(
    request: SeerrRequestItem,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val badge = SeerrHelpers.statusBadge(request.status)
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .background(colors.surfaceRaised),
        ) {
            if (!request.poster.isNullOrBlank()) {
                AsyncImage(
                    model = request.poster,
                    contentDescription = request.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (request.mediaType == "tv") Icons.Filled.Tv else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier
                        .size(26.dp)
                        .align(Alignment.Center),
                )
            }
            if (badge != null) {
                val (fg, _) = seerrToneColors(badge.tone)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.mediaChip)
                        .border(0.5.dp, colors.mediaChipBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge.label,
                        color = fg,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = request.title,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = request.year?.toString()
                    ?: if (request.mediaType == "tv") "TV" else "Movie",
                color = colors.textFaint,
                style = TabularNumStyle.copy(fontSize = 11.sp),
                maxLines = 1,
            )
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
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
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
