package com.neekhar.pccontrol.feature.seerr

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatusBarScrim
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.tvremote.TvRemoteUiState
import com.neekhar.pccontrol.feature.tvremote.TvRemoteViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeerrDetailScreen(
    viewModel: SeerrDetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (mediaType: String, id: Int, seed: SeerrItem?) -> Unit,
    onOpenPerson: (id: Int, name: String, profilePath: String?) -> Unit,
    tvRemoteViewModel: TvRemoteViewModel? = null,
    onOpenTvRemote: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tvState: TvRemoteUiState? = if (tvRemoteViewModel != null) {
        tvRemoteViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        null
    }
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    val item = state.item
    val hasBackdrop = !item?.backdrop.isNullOrBlank()

    if (tvRemoteViewModel != null) {
        LifecycleResumeEffect(tvRemoteViewModel) {
            tvRemoteViewModel.setMiniBarOwnerActive("seerr_detail", true)
            onPauseOrDispose {
                tvRemoteViewModel.setMiniBarOwnerActive("seerr_detail", false)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openTvRemoteEvents.collect {
            tvRemoteViewModel?.notifyPlaybackStarted()
            onOpenTvRemote()
        }
    }

    val openExternalUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    val runtimeStr = remember(item?.runtime) { SeerrHelpers.formatRuntime(item?.runtime) }
    val metaLine = remember(item, runtimeStr, state.isTv) {
        buildList {
            item?.year?.let { add(it.toString()) }
            runtimeStr?.let { add(if (state.isTv) "$it / ep" else it) }
            item?.certification?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (state.isTv && (item?.numberOfSeasons ?: 0) > 0) {
                val n = item!!.numberOfSeasons!!
                add("$n ${if (n == 1) "season" else "seasons"}")
            }
        }.joinToString(" · ")
    }

    val kvRows = remember(item, state.isTv, runtimeStr) {
        buildDetailKvRows(item, state.isTv, runtimeStr)
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val backdropFadePx = remember(density) { with(density) { 140.dp.toPx() } }
    val statusBarScrimTarget by remember(hasBackdrop, listState, backdropFadePx) {
        derivedStateOf {
            if (!hasBackdrop) {
                1f
            } else if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset.toFloat() / backdropFadePx).coerceIn(0f, 1f)
            }
        }
    }
    val statusBarScrimAlpha by animateFloatAsState(
        targetValue = statusBarScrimTarget,
        label = "seerrDetailStatusScrim",
    )

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.refresh() },
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp + navBottom),
        ) {
            // Top backdrop or plain top bar
            item(key = "header") {
                if (hasBackdrop) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp)
                            .background(colors.surfaceRaised),
                    ) {
                        AsyncImage(
                            model = viewModel.resolveImageUrl(item!!.backdrop),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(start = 18.dp, top = 12.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.mediaChip)
                                .border(0.5.dp, colors.mediaChipBorder, CircleShape)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.onMedia,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.text,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            text = if (state.isTv) "TV show" else "Movie",
                            color = colors.textDim,
                            style = type.body.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }

            if (!state.error.isNullOrBlank()) {
                item(key = "error") {
                    Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        PcErrorBanner(title = "Watch", message = state.error)
                    }
                }
            }

            // Hero Poster + Title + Status
            item(key = "hero") {
                val overlapOffset = if (hasBackdrop) (-28).dp else 0.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = overlapOffset)
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (hasBackdrop) {
                                    Modifier.border(3.dp, colors.bg, RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        SeerrPoster(
                            url = viewModel.resolveImageUrl(item?.poster),
                            mediaType = state.mediaType,
                            size = SeerrPosterSize.LG,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = if (hasBackdrop) 34.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item?.title?.takeIf { it.isNotBlank() }
                                ?: if (state.loading) "Loading…" else "Untitled",
                            color = colors.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 27.sp,
                            letterSpacing = (-0.3).sp,
                        )
                        val showOriginal = !item?.originalTitle.isNullOrBlank() &&
                            !item!!.originalTitle.equals(item.title, ignoreCase = true)
                        if (showOriginal) {
                            Text(
                                text = item!!.originalTitle!!,
                                color = colors.textFaint,
                                fontSize = 12.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (metaLine.isNotEmpty()) {
                            Text(
                                text = metaLine,
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 13.sp),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PcPill(
                                label = if (state.isTv) "TV" else "Movie",
                                tone = PcTone.Faint,
                            )
                            if (item != null) {
                                SeerrStatusPill(status = item.status, showDot = true)
                            }
                        }
                    }
                }
            }

            // Tagline
            if (!item?.tagline.isNullOrBlank()) {
                item(key = "tagline") {
                    Text(
                        text = "“${item!!.tagline}”",
                        color = colors.textDim,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                }
            }

            // Ratings Strip
            if (item?.ratings != null) {
                item(key = "ratings") {
                    SeerrRatingsStrip(
                        ratings = item.ratings,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }

            // Primary Actions (Playback / Request / My List — structured so Phase 2 Play on Phone & Offline Download slot right in)
            if (item != null) {
                item(key = "actions") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isPlayableOnTv = item.status == SeerrStatus.AVAILABLE || item.status == SeerrStatus.PARTIAL

                        if (isPlayableOnTv) {
                            val connectedDevice = tvState?.connectedDeviceName
                            val playLabel = state.playOnTvStatus
                                ?: connectedDevice?.let { "Play on $it" }
                                ?: "Play on TV"
                            PcButton(
                                label = playLabel,
                                onClick = viewModel::playOnTv,
                                variant = PcButtonVariant.Primary,
                                icon = if (state.playOnTvStatus != null) {
                                    Icons.Rounded.Check
                                } else {
                                    Icons.Rounded.Tv
                                },
                                busy = state.playingOnTv,
                                enabled = !state.loading && !state.playingOnTv,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (state.playOnTvShowRemoteAction) {
                                PcButton(
                                    label = "Open TV Remote",
                                    onClick = onOpenTvRemote,
                                    variant = PcButtonVariant.Subtle,
                                    icon = Icons.Rounded.Tv,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        if (!state.isTv && state.canRequestMovie) {
                            PcButton(
                                label = if (item.status == SeerrStatus.DELETED) {
                                    "Request again"
                                } else {
                                    "Request movie"
                                },
                                onClick = viewModel::requestMovie,
                                variant = PcButtonVariant.Primary,
                                icon = Icons.Rounded.Add,
                                busy = state.submitting,
                                enabled = !state.loading && !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (state.isTv && state.pickableSeasons.isNotEmpty()) {
                            PcButton(
                                label = if (state.selectedSeasons.isNotEmpty()) {
                                    "Request ${state.selectedSeasons.size} ${if (state.selectedSeasons.size == 1) "season" else "seasons"}"
                                } else {
                                    "Choose seasons to request"
                                },
                                onClick = viewModel::openSeasonSheet,
                                variant = if (isPlayableOnTv) PcButtonVariant.Subtle else PcButtonVariant.Primary,
                                icon = Icons.Rounded.Add,
                                busy = state.submitting,
                                enabled = !state.loading && !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Secondary utility row: My List toggle (Phase 2 Play on Phone / Offline Download slot beside this)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PcButton(
                                label = if (state.inWatchlist) "In My List" else "My List",
                                onClick = viewModel::toggleWatchlist,
                                variant = PcButtonVariant.Subtle,
                                icon = if (state.inWatchlist) Icons.Rounded.Check else Icons.Rounded.Add,
                                busy = state.togglingWatchlist,
                                enabled = !state.togglingWatchlist,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (!state.playOnTvError.isNullOrBlank()) {
                            PcErrorBanner(
                                title = "Play on TV",
                                message = state.playOnTvError,
                                tone = PcTone.Warn,
                            )
                        }
                    }
                }
            }

            // Live Download Progress (when Radarr/Sonarr is actively downloading this title)
            val activeDownload = state.activeDownload
            if (activeDownload != null) {
                item(key = "active_download") {
                    Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        WatchActiveDownloadCard(download = activeDownload)
                    }
                }
            }

            // Existing requests note
            if (item != null && item.requests.isNotEmpty()) {
                item(key = "existing_requests") {
                    Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        PcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.requests.take(3).forEach { req ->
                                    val line = buildString {
                                        append("Requested")
                                        if (req.requestedBy.isNotBlank()) append(" by ${req.requestedBy}")
                                        if (req.seasons.isNotEmpty()) {
                                            append(" · ")
                                            append(req.seasons.joinToString(", ") { "S$it" })
                                        }
                                        val age = SeerrHelpers.formatIsoAge(req.createdAt)
                                        if (age.isNotBlank()) append(" · $age")
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Schedule,
                                            contentDescription = null,
                                            tint = colors.textFaint,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = line,
                                            color = colors.textDim,
                                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Genres
            if (item != null && item.genres.isNotEmpty()) {
                item(key = "genres") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.genres.forEach { genre ->
                            PcPill(label = genre, tone = PcTone.Dim)
                        }
                    }
                }
            }

            // Loading indicator before rich detail arrives
            if (state.loading && item?.cast.isNullOrEmpty() && item?.ratings == null) {
                item(key = "loading_detail") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Overview
            val overviewText = item?.overview?.trim().orEmpty()
            if (overviewText.isNotEmpty()) {
                item(key = "overview") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionLabel(label = "Overview")
                        PcCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = overviewText,
                                    color = colors.textDim,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    maxLines = if (state.overviewExpanded) Int.MAX_VALUE else 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (overviewText.length > 260) {
                                    Text(
                                        text = if (state.overviewExpanded) "Show less" else "Read more",
                                        color = colors.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(onClick = viewModel::toggleOverviewExpanded)
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TV Episode Browser (Season selector pills + episode stills + progress + per-episode Play on TV)
            if (state.isTv && state.browsableSeasons.isNotEmpty()) {
                val epData = state.currentSeasonEpisodes
                item(key = "episodes_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                            PcSectionLabel(
                                label = "Episodes",
                                trailing = epData?.let { data ->
                                    {
                                        val countLabel = if (data.availableCount > 0) {
                                            "${data.availableCount} of ${data.totalCount} available"
                                        } else {
                                            "${data.totalCount} ${if (data.totalCount == 1) "episode" else "episodes"}"
                                        }
                                        Text(
                                            text = countLabel,
                                            color = colors.textFaint,
                                            style = TabularNumStyle.copy(fontSize = 12.sp),
                                        )
                                    }
                                },
                            )
                        }
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.browsableSeasons, key = { "ep_season:${it.seasonNumber}" }) { season ->
                                val label = if (season.seasonNumber == 0) {
                                    "Specials"
                                } else {
                                    "Season ${season.seasonNumber}"
                                }
                                SeerrFilterChip(
                                    label = label,
                                    count = season.episodeCount.takeIf { it > 0 },
                                    selected = state.selectedEpisodeSeason == season.seasonNumber,
                                    onClick = { viewModel.selectEpisodeSeason(season.seasonNumber) },
                                )
                            }
                        }
                    }
                }

                when {
                    state.loadingEpisodes && epData == null -> {
                        item(key = "episodes_loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    !state.episodesError.isNullOrBlank() && epData == null -> {
                        item(key = "episodes_error") {
                            Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                PcErrorBanner(
                                    title = "Episodes",
                                    message = state.episodesError,
                                    tone = PcTone.Warn,
                                    actionLabel = "Retry",
                                    onAction = viewModel::retrySeasonEpisodes,
                                )
                            }
                        }
                    }
                    epData != null && epData.episodes.isNotEmpty() -> {
                        item(key = "episodes_list_${epData.seasonNumber}") {
                            PcListCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                            ) {
                                epData.episodes.forEachIndexed { idx, ep ->
                                    WatchEpisodeRow(
                                        episode = ep,
                                        resolveImageUrl = viewModel::resolveImageUrl,
                                        playingOnTv = state.playingEpisodeId == ep.jellyfinItemId,
                                        playEnabled = state.playingEpisodeId == null && !state.playingOnTv,
                                        onPlayOnTv = { viewModel.playEpisodeOnTv(ep) },
                                    )
                                    if (idx < epData.episodes.lastIndex) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = colors.hairline,
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TV Seasons section (inline selectable + modal sheet trigger)
            if (state.isTv && item != null && item.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionLabel(
                            label = "Seasons (${item.seasons.size})",
                            trailing = if (state.pickableSeasons.size > 1) {
                                {
                                    Text(
                                        text = if (state.allPickableSeasonsSelected) "Clear" else "Select all",
                                        color = colors.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(onClick = viewModel::toggleAllSeasons)
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )

                        PcListCard(modifier = Modifier.fillMaxWidth()) {
                            item.seasons.forEachIndexed { idx, season ->
                                val locked = !SeerrHelpers.isRequestableSeason(season.status)
                                val checked = season.seasonNumber in state.selectedSeasons
                                val sub = buildList {
                                    if (season.episodeCount > 0) add("${season.episodeCount} eps")
                                    season.airDate?.take(4)?.takeIf { it.all(Char::isDigit) }?.let { add(it) }
                                }.joinToString(" · ").takeIf { it.isNotEmpty() }

                                PcListRow(
                                    title = season.name.ifBlank { "Season ${season.seasonNumber}" },
                                    subtitle = sub,
                                    showChevron = false,
                                    showDivider = idx < item.seasons.lastIndex,
                                    onClick = if (locked) null else {
                                        { viewModel.toggleSeason(season.seasonNumber) }
                                    },
                                    trailingContent = {
                                        if (locked) {
                                            SeerrStatusPill(status = season.status)
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                SeerrStatusPill(status = season.status)
                                                Icon(
                                                    imageVector = if (checked) {
                                                        Icons.Rounded.CheckBox
                                                    } else {
                                                        Icons.Rounded.CheckBoxOutlineBlank
                                                    },
                                                    contentDescription = null,
                                                    tint = if (checked) colors.accent else colors.textFaint,
                                                    modifier = Modifier.size(22.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Movie Collection section
            if (item?.collection != null) {
                val colRef = item.collection
                item(key = "collection") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionLabel(label = "Collection")
                        PcCard(
                            onClick = { viewModel.openCollection(colRef) },
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SeerrPoster(
                                    url = colRef.poster,
                                    mediaType = "collection",
                                    size = SeerrPosterSize.SM,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = colRef.name,
                                        color = colors.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "View collection & request missing",
                                        color = colors.textFaint,
                                        fontSize = 12.5.sp,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Cast horizontal row
            if (item != null && item.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                            PcSectionLabel(label = "Cast")
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(item.cast, key = { "cast:${it.id}" }) { member ->
                                SeerrCastCard(
                                    member = member,
                                    onClick = { onOpenPerson(member.id, member.name, member.profilePath) },
                                )
                            }
                        }
                    }
                }
            }

            // Key Crew list
            if (item != null && item.crew.isNotEmpty()) {
                item(key = "crew") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionLabel(label = "Crew")
                        PcListCard(modifier = Modifier.fillMaxWidth()) {
                            item.crew.forEachIndexed { idx, crew ->
                                PcListRow(
                                    title = crew.name,
                                    subtitle = crew.job,
                                    showChevron = true,
                                    showDivider = idx < item.crew.lastIndex,
                                    leading = {
                                        SeerrPersonAvatar(url = crew.profilePath, size = 38.dp)
                                    },
                                    onClick = { onOpenPerson(crew.id, crew.name, crew.profilePath) },
                                )
                            }
                        }
                    }
                }
            }

            // Details KV card
            if (kvRows.isNotEmpty()) {
                item(key = "details_kv") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionLabel(label = "Details")
                        PcCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                kvRows.forEachIndexed { idx, row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = row.label,
                                            color = colors.textFaint,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        if (row.url != null) {
                                            Text(
                                                text = row.value,
                                                color = colors.accent,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { openExternalUrl(row.url) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            )
                                        } else {
                                            Text(
                                                text = row.value,
                                                color = colors.text,
                                                style = TabularNumStyle.copy(
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                ),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (idx < kvRows.lastIndex) {
                                        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recommendations horizontal row
            if (item != null && item.recommendations.isNotEmpty()) {
                item(key = "recommendations") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                            PcSectionLabel(label = "Recommended")
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = item.recommendations,
                                key = { "rec:${it.mediaType}:${it.id}" },
                            ) { rec ->
                                SeerrRecommendationCard(
                                    item = rec,
                                    onClick = {
                                        if (!rec.isCollection) {
                                            onOpenDetail(rec.mediaType, rec.id, rec)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subtle status-bar vignette over backdrop at rest, fading smoothly into solid bg scrim on scroll
        if (hasBackdrop && statusBarScrimAlpha < 0.99f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = (1f - statusBarScrimAlpha).coerceIn(0f, 1f) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.55f),
                                1.0f to Color.Black.copy(alpha = 0.28f),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.28f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                )
            }
        }

        if (statusBarScrimAlpha > 0.01f) {
            PcStatusBarScrim(
                color = colors.bg,
                fadeHeight = 22.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = statusBarScrimAlpha },
            )
        }
    }

    // TV Season Picker ModalBottomSheet
    if (state.showSeasonSheet && item != null && state.isTv) {
        SeerrSeasonPickerSheet(
            title = item.title,
            seasons = item.seasons,
            selectedSeasons = state.selectedSeasons,
            allSelected = state.allPickableSeasonsSelected,
            pickableCount = state.pickableSeasons.size,
            submitting = state.submitting,
            onToggleSeason = viewModel::toggleSeason,
            onToggleAll = viewModel::toggleAllSeasons,
            onSubmit = viewModel::requestSelectedSeasons,
            onDismiss = viewModel::closeSeasonSheet,
        )
    }

    // Collection ModalBottomSheet
    SeerrCollectionSheet(
        state = state.collectionSheet,
        onRequestMovie = viewModel::requestCollectionMovie,
        onRequestAllMissing = viewModel::requestAllMissingInCollection,
        onSelectMovie = { movie ->
            viewModel.closeCollection()
            if (movie.id != state.tmdbId || state.isTv) {
                onOpenDetail("movie", movie.id, movie)
            }
        },
        onDismiss = viewModel::closeCollection,
    )
}

private data class DetailKvRow(
    val label: String,
    val value: String,
    val url: String? = null,
)

private fun buildDetailKvRows(
    item: SeerrItem?,
    isTv: Boolean,
    runtimeStr: String?,
): List<DetailKvRow> {
    if (item == null) return emptyList()
    val rows = mutableListOf<DetailKvRow>()
    item.mediaStatus?.takeIf { it.isNotBlank() }?.let {
        rows.add(DetailKvRow("Status", it))
    }
    SeerrHelpers.formatDate(item.releaseDate)?.let {
        rows.add(DetailKvRow(if (isTv) "First aired" else "Release date", it))
    }
    if (isTv) {
        SeerrHelpers.formatDate(item.lastAirDate)?.let {
            rows.add(DetailKvRow("Last aired", it))
        }
    }
    runtimeStr?.let {
        rows.add(DetailKvRow(if (isTv) "Episode runtime" else "Runtime", it))
    }
    if (isTv && (item.numberOfSeasons != null || item.numberOfEpisodes != null)) {
        val parts = buildList {
            item.numberOfSeasons?.let { add("$it ${if (it == 1) "season" else "seasons"}") }
            item.numberOfEpisodes?.let { add("$it ${if (it == 1) "episode" else "episodes"}") }
        }
        if (parts.isNotEmpty()) {
            rows.add(DetailKvRow("Length", parts.joinToString(" · ")))
        }
    }
    if (isTv && item.networks.isNotEmpty()) {
        rows.add(DetailKvRow("Network", item.networks.joinToString(", ")))
    } else if (item.studios.isNotEmpty()) {
        rows.add(DetailKvRow("Studio", item.studios.joinToString(", ")))
    }
    if (item.spokenLanguages.isNotEmpty()) {
        rows.add(DetailKvRow("Languages", item.spokenLanguages.joinToString(", ")))
    }
    if (item.countries.isNotEmpty()) {
        rows.add(DetailKvRow("Country", item.countries.joinToString(", ")))
    }
    SeerrHelpers.formatMoney(item.budget)?.let {
        rows.add(DetailKvRow("Budget", it))
    }
    SeerrHelpers.formatMoney(item.revenue)?.let {
        rows.add(DetailKvRow("Revenue", it))
    }
    item.imdbId?.takeIf { it.isNotBlank() }?.let { imdb ->
        rows.add(DetailKvRow("IMDb", imdb, url = "https://www.imdb.com/title/$imdb/"))
    }
    return rows
}

private data class RatingCellData(
    val key: String,
    val label: String,
    val value: String,
    val sub: String?,
    val fg: Color,
    val bg: Color,
    val icon: ImageVector,
)

@Composable
private fun SeerrRatingsStrip(
    ratings: SeerrRatings,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val cells = buildList {
        ratings.tmdb?.takeIf { it.score > 0.0 }?.let { tmdb ->
            add(
                RatingCellData(
                    key = "tmdb",
                    label = "TMDB",
                    value = SeerrHelpers.formatVoteAverage(tmdb.score) ?: return@let,
                    sub = SeerrHelpers.formatCompactCount(tmdb.count) ?: "/ 10",
                    fg = colors.text,
                    bg = colors.surfaceRaised,
                    icon = Icons.Rounded.Star,
                ),
            )
        }
        ratings.imdb?.takeIf { it.score > 0.0 }?.let { imdb ->
            add(
                RatingCellData(
                    key = "imdb",
                    label = "IMDb",
                    value = SeerrHelpers.formatVoteAverage(imdb.score) ?: return@let,
                    sub = SeerrHelpers.formatCompactCount(imdb.count) ?: "/ 10",
                    fg = colors.warn,
                    bg = colors.warnSoft,
                    icon = Icons.Rounded.Star,
                ),
            )
        }
        ratings.rt?.criticsScore?.let { score ->
            val fresh = score >= 60
            add(
                RatingCellData(
                    key = "rt-critics",
                    label = "RT Critics",
                    value = "$score%",
                    sub = ratings.rt.criticsRating ?: if (fresh) "Fresh" else "Rotten",
                    fg = if (fresh) colors.ok else colors.bad,
                    bg = if (fresh) colors.okSoft else colors.badSoft,
                    icon = if (fresh) Icons.Rounded.Verified else Icons.Rounded.Warning,
                ),
            )
        }
        ratings.rt?.audienceScore?.let { score ->
            val good = score >= 60
            add(
                RatingCellData(
                    key = "rt-audience",
                    label = "RT Audience",
                    value = "$score%",
                    sub = ratings.rt.audienceRating,
                    fg = if (good) colors.ok else colors.bad,
                    bg = if (good) colors.okSoft else colors.badSoft,
                    icon = Icons.Rounded.Group,
                ),
            )
        }
    }

    if (cells.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cells, key = { it.key }) { cell ->
            Column(
                modifier = Modifier
                    .widthIn(min = 96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(cell.bg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = cell.icon,
                        contentDescription = null,
                        tint = cell.fg,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = cell.value,
                        color = cell.fg,
                        style = TabularNumStyle.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = cell.label,
                    color = colors.textDim,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (!cell.sub.isNullOrBlank()) {
                    Text(
                        text = cell.sub,
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeerrCastCard(
    member: SeerrCastMember,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = 108.dp, height = 132.dp)
                .background(colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(32.dp),
            )
            if (!member.profilePath.isNullOrBlank()) {
                AsyncImage(
                    model = member.profilePath,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = member.name,
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = member.character.trim().ifEmpty { " " },
                color = colors.textFaint,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SeerrRecommendationCard(
    item: SeerrItem,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val badge = SeerrHelpers.statusBadge(item.status)
    Column(
        modifier = Modifier
            .width(114.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = 114.dp, height = 168.dp)
                .background(colors.surfaceRaised),
        ) {
            Icon(
                imageVector = if (item.isTv) Icons.Rounded.Tv else Icons.Rounded.Movie,
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
            if (badge != null) {
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
                        color = colors.onMedia,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                color = colors.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.year?.let { year ->
                    Text(
                        text = year.toString(),
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 11.5.sp),
                    )
                }
                SeerrRatingInline(voteAverage = item.voteAverage)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeerrSeasonPickerSheet(
    title: String,
    seasons: List<SeerrSeason>,
    selectedSeasons: Set<Int>,
    allSelected: Boolean,
    pickableCount: Int,
    submitting: Boolean,
    onToggleSeason: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current

    PcBottomSheet(
        onDismissRequest = { if (!submitting) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select seasons",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = title,
                        color = colors.textDim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (pickableCount > 1) {
                    Text(
                        text = if (allSelected) "Clear" else "Select all",
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleAll)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface),
            ) {
                itemsIndexed(seasons, key = { _, s -> s.seasonNumber }) { idx, season ->
                    val locked = !SeerrHelpers.isRequestableSeason(season.status)
                    val checked = season.seasonNumber in selectedSeasons
                    val sub = buildList {
                        if (season.episodeCount > 0) add("${season.episodeCount} eps")
                        season.airDate?.take(4)?.takeIf { it.all(Char::isDigit) }?.let { add(it) }
                    }.joinToString(" · ").takeIf { it.isNotEmpty() }

                    PcListRow(
                        title = season.name.ifBlank { "Season ${season.seasonNumber}" },
                        subtitle = sub,
                        showChevron = false,
                        showDivider = idx < seasons.lastIndex,
                        onClick = if (locked) null else {
                            { onToggleSeason(season.seasonNumber) }
                        },
                        trailingContent = {
                            if (locked) {
                                SeerrStatusPill(status = season.status)
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SeerrStatusPill(status = season.status)
                                    Icon(
                                        imageVector = if (checked) {
                                            Icons.Rounded.CheckBox
                                        } else {
                                            Icons.Rounded.CheckBoxOutlineBlank
                                        },
                                        contentDescription = null,
                                        tint = if (checked) colors.accent else colors.textFaint,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    variant = PcButtonVariant.Subtle,
                    enabled = !submitting,
                    modifier = Modifier.weight(0.45f),
                )
                PcButton(
                    label = if (selectedSeasons.isNotEmpty()) {
                        "Request ${selectedSeasons.size} ${if (selectedSeasons.size == 1) "season" else "seasons"}"
                    } else {
                        "Select seasons"
                    },
                    onClick = onSubmit,
                    variant = PcButtonVariant.Primary,
                    icon = Icons.Rounded.Add,
                    busy = submitting,
                    enabled = !submitting && selectedSeasons.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WatchActiveDownloadCard(
    download: WatchDownloadItem,
) {
    val colors = LocalPcColors.current
    val pct = download.percent.roundToInt().coerceIn(0, 100)
    val barColor = if (download.status == "warning") colors.warn else colors.accent
    val statusLabel = when (download.status.lowercase()) {
        "importing" -> "Importing"
        "queued" -> "Queued"
        "paused" -> "Paused"
        "warning" -> "Needs attention"
        else -> "Downloading"
    }
    val headerTitle = buildString {
        append(statusLabel)
        if (!download.episodeLabel.isNullOrBlank()) {
            append(" · ")
            append(download.episodeLabel)
        }
    }
    val subParts = buildList {
        add("$pct%")
        if (!download.eta.isNullOrBlank()) add("${download.eta} left")
        if (download.sizeLeft > 0L) add("${SeerrHelpers.formatDownloadBytes(download.sizeLeft)} remaining")
    }.joinToString(" · ")

    PcCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = barColor,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = headerTitle,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subParts,
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PcProgressBar(
                progress = (download.percent / 100.0).toFloat().coerceIn(0f, 1f),
                color = barColor,
                height = 4.dp,
            )
        }
    }
}

@Composable
private fun WatchEpisodeRow(
    episode: WatchEpisode,
    resolveImageUrl: (String?) -> String?,
    playingOnTv: Boolean,
    playEnabled: Boolean,
    onPlayOnTv: () -> Unit,
) {
    val colors = LocalPcColors.current
    val stillUrl = resolveImageUrl(episode.still)
    val runtimeStr = SeerrHelpers.formatRuntime(episode.runtimeMinutes)
    val metaLine = buildList {
        runtimeStr?.let { add(it) }
        when {
            episode.played -> add("Watched")
            episode.progressPct > 0.0 -> add("${episode.progressPct.roundToInt()}% watched")
            episode.available -> add("Available")
            else -> SeerrHelpers.formatDate(episode.airDate)?.let { add(it) }
        }
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (episode.available && !episode.jellyfinItemId.isNullOrBlank()) {
                    Modifier.clickable(enabled = playEnabled, onClick = onPlayOnTv)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 16:9 episode still thumbnail with watched checkmark & resume progress bar
            Box(
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceRaised),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = colors.textFaint,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center),
                )
                if (!stillUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = stillUrl,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (episode.played) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(colors.mediaChip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Watched",
                            tint = colors.ok,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else if (episode.available) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(colors.mediaChip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.ok),
                        )
                    }
                }
                if (episode.progressPct > 0.0 && !episode.played) {
                    val frac = (episode.progressPct / 100.0).toFloat().coerceIn(0.04f, 1f)
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
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "E${episode.episodeNumber} · ${episode.name.ifBlank { "Episode ${episode.episodeNumber}" }}",
                    color = if (episode.available) colors.text else colors.textDim,
                    style = TabularNumStyle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        color = if (episode.available && !episode.played) colors.ok else colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Per-episode playback actions slot (Phase 2 Play on Phone + Offline Download slot right alongside this)
            if (episode.available && !episode.jellyfinItemId.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                        .clickable(enabled = playEnabled, onClick = onPlayOnTv),
                    contentAlignment = Alignment.Center,
                ) {
                    if (playingOnTv) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = "Play episode on TV",
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (episode.overview.isNotBlank()) {
            Text(
                text = episode.overview,
                color = colors.textDim,
                fontSize = 12.5.sp,
                lineHeight = 17.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
