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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcScaffold
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
    val type = LocalPcType.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    PcScaffold(
        modifier = modifier,
        title = "Seerr",
        statusText = if (!state.error.isNullOrBlank()) "Offline" else null,
        statusTone = PcTone.Bad,
        showStatusDot = !state.error.isNullOrBlank(),
        onBack = onBack,
        listState = listState,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
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
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.error.isNullOrBlank()) {
                    item(key = "error") {
                        Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcErrorBanner(title = "Seerr", message = state.error)
                        }
                    }
                }

                // Native search bar with 3-char debounce + immediate IME search
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
                            onClear = viewModel::clearSearch,
                        )
                    }
                }

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
                                "grid:" + row.joinToString(",") { "${it.mediaType}:${it.id}" }
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
                    // Edge-to-edge horizontal poster carousel of recent requests when viewing All
                    val recentWithPosters = state.requests
                        ?.filter { !it.poster.isNullOrBlank() }
                        ?.take(12)
                        .orEmpty()
                    if (state.statusFilter == SeerrRequestFilter.ALL && recentWithPosters.isNotEmpty()) {
                        item(key = "recent_carousel_header") {
                            Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                PcSectionLabel(label = "Recent activity")
                            }
                        }
                        item(key = "recent_carousel") {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
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

                    // Edge-to-edge status filter chips
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

                    // Requests section header
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
                                            body = "Search above to request movies, TV shows, or collections",
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
                        style = type.micro.copy(fontSize = 9.5.sp),
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
                        style = type.micro.copy(fontSize = 9.5.sp),
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
private fun SeerrRecentPosterCard(
    request: SeerrRequestItem,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val badge = SeerrHelpers.statusBadge(request.status)
    Column(
        modifier = Modifier
            .width(104.dp)
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
            if (!request.poster.isNullOrBlank()) {
                AsyncImage(
                    model = request.poster,
                    contentDescription = request.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (request.mediaType == "tv") Icons.Rounded.Tv else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier
                        .size(26.dp)
                        .align(Alignment.Center),
                )
            }
            if (badge != null) {
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
                        style = type.micro.copy(fontSize = 9.5.sp),
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
                text = request.title,
                color = colors.text,
                style = type.caption.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = request.year?.toString()
                    ?: if (request.mediaType == "tv") "TV" else "Movie",
                color = colors.textFaint,
                style = type.micro.tabular(),
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
