package com.neekhar.pccontrol.feature.seerr

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.R
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
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
import com.neekhar.pccontrol.feature.tvremote.rememberSmartCastController
import com.neekhar.pccontrol.feature.watch.downloads.OfflineDownloadStatus
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchOfflineHelpers
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeerrDetailScreen(
    viewModel: SeerrDetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (mediaType: String, id: Int, seed: SeerrItem?) -> Unit,
    onOpenPerson: (id: Int, name: String, profilePath: String?) -> Unit,
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
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    val item = state.item
    val hasBackdrop = !item?.backdrop.isNullOrBlank()
    var pendingDeleteOffline by remember { mutableStateOf<OfflineWatchItem?>(null) }

    val offlineById = remember(offlineItems) {
        offlineItems.associateBy { it.jellyfinItemId }
    }
    val movieOfflineItem = remember(offlineItems, state.tmdbId, state.isTv) {
        if (state.isTv) {
            offlineItems.firstOrNull { it.tmdbId == state.tmdbId && it.isCompleted }
        } else {
            offlineItems.firstOrNull { it.tmdbId == state.tmdbId && it.mediaType == "movie" }
        }
    }

    val smartCast = rememberSmartCastController(
        tvRemoteViewModel = tvRemoteViewModel,
        onNoDevices = { msg -> viewModel.showPlayOnTvError(msg) },
    )

    val launchPhonePlayer: (String?, Int?, String?, Int?, Int?) -> Unit = { jfId, tid, mType, season, episode ->
        val local = jfId?.let { offlineById[it] }?.takeIf { it.isCompleted }
            ?: if (tid != null && tid > 0) {
                offlineItems.firstOrNull { it.tmdbId == tid && it.isCompleted }
            } else {
                null
            }
        if (local != null) {
            onPlayOnPhone(local.jellyfinItemId, null, local.mediaType, null, null, true)
        } else {
            onPlayOnPhone(jfId, tid, mType, season, episode, false)
        }
    }

    LifecycleResumeEffect(viewModel) {
        if (!state.loading && state.item != null) {
            viewModel.refreshQuietly()
        }
        onPauseOrDispose {}
    }

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

            // Primary Actions: Play on Phone (primary), Play on TV, Offline Download, Request, My List
            if (item != null) {
                item(key = "actions") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isAvailable = item.status == SeerrStatus.AVAILABLE || item.status == SeerrStatus.PARTIAL
                        val hasCompletedOffline = movieOfflineItem?.isCompleted == true
                        val canPlayOnPhone = isAvailable || hasCompletedOffline

                        if (!state.tailscaleNotice.isNullOrBlank()) {
                            PcErrorBanner(
                                title = "Tailscale required",
                                message = state.tailscaleNotice,
                                tone = PcTone.Warn,
                                actionLabel = "Dismiss",
                                onAction = viewModel::dismissTailscaleNotice,
                            )
                        }

                        if (canPlayOnPhone) {
                            val effectiveResumeTicks = maxOf(
                                item.resumePositionTicks,
                                movieOfflineItem?.resumePositionTicks ?: 0L,
                            )
                            val primaryPlayLabel = SeerrHelpers.formatPlayButtonLabel(
                                resumePositionTicks = effectiveResumeTicks,
                                isOffline = hasCompletedOffline,
                                episodeLabel = if (state.isTv) item.resumeEpisodeLabel else null,
                            )
                            PcButton(
                                label = primaryPlayLabel,
                                onClick = {
                                    val targetJfId = when {
                                        hasCompletedOffline && !state.isTv -> movieOfflineItem?.jellyfinItemId
                                        !item.resumeJellyfinItemId.isNullOrBlank() -> item.resumeJellyfinItemId
                                        hasCompletedOffline -> movieOfflineItem?.jellyfinItemId
                                        else -> null
                                    }
                                    val targetMediaType = if (state.isTv && !item.resumeJellyfinItemId.isNullOrBlank()) {
                                        "episode"
                                    } else {
                                        state.mediaType
                                    }
                                    launchPhonePlayer(
                                        targetJfId,
                                        item.id,
                                        targetMediaType,
                                        if (state.isTv) item.resumeSeasonNumber else null,
                                        if (state.isTv) item.resumeEpisodeNumber else null,
                                    )
                                },
                                variant = PcButtonVariant.Primary,
                                icon = Icons.Rounded.PlayArrow,
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (isAvailable) {
                            val connectedDevice = tvState?.connectedDeviceName
                            val playLabel = state.playOnTvStatus
                                ?: connectedDevice?.let { "Play on $it" }
                                ?: "Play on TV"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PcButton(
                                    label = playLabel,
                                    onClick = {
                                        smartCast.cast(forcePicker = false) { picked ->
                                            viewModel.playOnTv(
                                                sessionId = picked?.sessionId,
                                                deviceName = picked?.deviceName,
                                            )
                                        }
                                    },
                                    variant = PcButtonVariant.Subtle,
                                    icon = if (state.playOnTvStatus != null) {
                                        Icons.Rounded.Check
                                    } else {
                                        Icons.Rounded.Tv
                                    },
                                    busy = state.playingOnTv,
                                    enabled = !state.loading && !state.playingOnTv,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.surfaceRaised)
                                        .clickable(enabled = !state.loading && !state.playingOnTv) {
                                            smartCast.cast(forcePicker = true) { picked ->
                                                viewModel.playOnTv(
                                                    sessionId = picked?.sessionId,
                                                    deviceName = picked?.deviceName,
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tv,
                                        contentDescription = "Choose TV",
                                        tint = colors.textDim,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
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
                                variant = if (canPlayOnPhone) PcButtonVariant.Subtle else PcButtonVariant.Primary,
                                icon = Icons.Rounded.Add,
                                busy = state.submitting,
                                enabled = !state.loading && !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Secondary utility row: Offline Download (for available Movies) + My List toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!state.isTv && (isAvailable || movieOfflineItem != null)) {
                                val dl = movieOfflineItem
                                val dlBusy = state.enqueuingDownloadKey == "movie:${state.tmdbId}"
                                val (dlLabel, dlIcon, dlClick) = when (dl?.status) {
                                    OfflineDownloadStatus.COMPLETED -> Triple(
                                        "Downloaded",
                                        Icons.Rounded.OfflinePin,
                                        { pendingDeleteOffline = dl },
                                    )
                                    OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED -> Triple(
                                        "${dl.progressPercent}% · Pause",
                                        Icons.Rounded.Pause,
                                        { viewModel.pauseOfflineDownload(dl.jellyfinItemId) },
                                    )
                                    OfflineDownloadStatus.PAUSED, OfflineDownloadStatus.FAILED -> Triple(
                                        "Resume (${dl.progressPercent}%)",
                                        Icons.Rounded.Download,
                                        { viewModel.resumeOfflineDownload(dl.jellyfinItemId) },
                                    )
                                    null -> Triple(
                                        "Download",
                                        Icons.Rounded.Download,
                                        { viewModel.downloadMovie() },
                                    )
                                }
                                PcButton(
                                    label = dlLabel,
                                    onClick = dlClick,
                                    variant = PcButtonVariant.Subtle,
                                    icon = dlIcon,
                                    busy = dlBusy,
                                    enabled = !dlBusy,
                                    modifier = Modifier.weight(1f),
                                )
                            }

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
                                    val epOffline = ep.jellyfinItemId?.let { offlineById[it] }
                                    WatchEpisodeRow(
                                        episode = ep,
                                        offlineItem = epOffline,
                                        enqueuingDownload = ep.jellyfinItemId != null && state.enqueuingDownloadKey == ep.jellyfinItemId,
                                        resolveImageUrl = viewModel::resolveImageUrl,
                                        playingOnTv = state.playingEpisodeId == ep.jellyfinItemId,
                                        playEnabled = state.playingEpisodeId == null && !state.playingOnTv,
                                        onPlayOnPhone = {
                                            launchPhonePlayer(
                                                ep.jellyfinItemId,
                                                state.tmdbId,
                                                "episode",
                                                ep.seasonNumber,
                                                ep.episodeNumber,
                                            )
                                        },
                                        onDownloadAction = {
                                            when (epOffline?.status) {
                                                OfflineDownloadStatus.COMPLETED -> pendingDeleteOffline = epOffline
                                                OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED ->
                                                    viewModel.pauseOfflineDownload(epOffline.jellyfinItemId)
                                                OfflineDownloadStatus.PAUSED, OfflineDownloadStatus.FAILED ->
                                                    viewModel.resumeOfflineDownload(epOffline.jellyfinItemId)
                                                null -> viewModel.downloadEpisode(ep)
                                            }
                                        },
                                        onPlayOnTv = { forcePicker ->
                                            smartCast.cast(forcePicker = forcePicker) { picked ->
                                                viewModel.playEpisodeOnTv(
                                                    episode = ep,
                                                    sessionId = picked?.sessionId,
                                                    deviceName = picked?.deviceName,
                                                )
                                            }
                                        },
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

    pendingDeleteOffline?.let { del ->
        PcConfirmDialog(
            title = "Delete offline download?",
            message = "Remove \"${del.title}\"${del.subtitle?.let { " ($it)" }.orEmpty()} (${WatchOfflineHelpers.formatBytes(del.downloadedBytes)}) from this phone?",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                viewModel.deleteOfflineDownload(del.jellyfinItemId)
                pendingDeleteOffline = null
            },
            onDismiss = { pendingDeleteOffline = null },
        )
    }
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

private enum class RatingSourceKind {
    IMDB,
    RT_CRITICS_FRESH,
    RT_CRITICS_ROTTEN,
    RT_AUDIENCE_UPRIGHT,
    RT_AUDIENCE_SPILLED,
    TMDB,
}

private data class RatingCellData(
    val key: String,
    val kind: RatingSourceKind,
    val value: String,
    val sub: String?,
)

@Composable
private fun SeerrRatingsStrip(
    ratings: SeerrRatings,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val cells = buildList {
        ratings.imdb?.takeIf { it.score > 0.0 }?.let { imdb ->
            add(
                RatingCellData(
                    key = "imdb",
                    kind = RatingSourceKind.IMDB,
                    value = SeerrHelpers.formatVoteAverage(imdb.score) ?: return@let,
                    sub = SeerrHelpers.formatCompactCount(imdb.count),
                ),
            )
        }
        ratings.rt?.criticsScore?.let { score ->
            val ratingText = ratings.rt.criticsRating.orEmpty()
            val fresh = when {
                ratingText.contains("rotten", ignoreCase = true) -> false
                ratingText.contains("fresh", ignoreCase = true) -> true
                else -> score >= 60
            }
            add(
                RatingCellData(
                    key = "rt-critics",
                    kind = if (fresh) RatingSourceKind.RT_CRITICS_FRESH else RatingSourceKind.RT_CRITICS_ROTTEN,
                    value = "$score%",
                    sub = null,
                ),
            )
        }
        ratings.rt?.audienceScore?.let { score ->
            val ratingText = ratings.rt.audienceRating.orEmpty()
            val upright = when {
                ratingText.contains("spilled", ignoreCase = true) -> false
                ratingText.contains("upright", ignoreCase = true) -> true
                else -> score >= 60
            }
            add(
                RatingCellData(
                    key = "rt-audience",
                    kind = if (upright) RatingSourceKind.RT_AUDIENCE_UPRIGHT else RatingSourceKind.RT_AUDIENCE_SPILLED,
                    value = "$score%",
                    sub = null,
                ),
            )
        }
        ratings.tmdb?.takeIf { it.score > 0.0 }?.let { tmdb ->
            add(
                RatingCellData(
                    key = "tmdb",
                    kind = RatingSourceKind.TMDB,
                    value = SeerrHelpers.formatVoteAverage(tmdb.score) ?: return@let,
                    sub = SeerrHelpers.formatCompactCount(tmdb.count),
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
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                RatingSourceLogo(kind = cell.kind)
                Text(
                    text = cell.value,
                    color = colors.text,
                    style = TabularNumStyle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                )
                if (!cell.sub.isNullOrBlank()) {
                    Text(
                        text = cell.sub,
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 11.5.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingSourceLogo(kind: RatingSourceKind) {
    when (kind) {
        RatingSourceKind.IMDB -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_imdb),
                contentDescription = "IMDb",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(16.dp)
                    .width(32.dp),
            )
        }
        RatingSourceKind.TMDB -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_tmdb),
                contentDescription = "TMDB",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(15.dp)
                    .width(35.dp),
            )
        }
        RatingSourceKind.RT_CRITICS_FRESH -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_rt_fresh),
                contentDescription = "Rotten Tomatoes Fresh",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
        }
        RatingSourceKind.RT_CRITICS_ROTTEN -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_rt_rotten),
                contentDescription = "Rotten Tomatoes Rotten",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
        }
        RatingSourceKind.RT_AUDIENCE_UPRIGHT -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_rt_aud_fresh),
                contentDescription = "Rotten Tomatoes Audience Fresh",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
        }
        RatingSourceKind.RT_AUDIENCE_SPILLED -> {
            Image(
                painter = painterResource(id = R.drawable.ic_rating_rt_aud_rotten),
                contentDescription = "Rotten Tomatoes Audience Spilled",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchEpisodeRow(
    episode: WatchEpisode,
    offlineItem: OfflineWatchItem?,
    enqueuingDownload: Boolean,
    resolveImageUrl: (String?) -> String?,
    playingOnTv: Boolean,
    playEnabled: Boolean,
    onPlayOnPhone: () -> Unit,
    onDownloadAction: () -> Unit,
    onPlayOnTv: (forcePicker: Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val stillUrl = resolveImageUrl(episode.still)
    val runtimeStr = SeerrHelpers.formatRuntime(episode.runtimeMinutes)
    val isOfflineReady = offlineItem?.isCompleted == true
    val canPlay = (episode.available && !episode.jellyfinItemId.isNullOrBlank()) || isOfflineReady
    val epResumeTicks = if (episode.played) {
        0L
    } else {
        maxOf(episode.positionTicks, offlineItem?.resumePositionTicks ?: 0L)
    }
    val epResumeLabel = SeerrHelpers.formatResumeTimestamp(epResumeTicks)
    val metaLine = buildList {
        runtimeStr?.let { add(it) }
        when {
            epResumeLabel != null -> add("Resume $epResumeLabel")
            isOfflineReady -> add("Downloaded")
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
                if (canPlay) {
                    Modifier.clickable(onClick = onPlayOnPhone)
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
            // 16:9 episode still thumbnail with play badge, watched checkmark & resume progress bar
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
                if (canPlay) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play episode",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                    color = if (canPlay) colors.text else colors.textDim,
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
                        color = if (isOfflineReady || (episode.available && !episode.played)) colors.ok else colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Per-episode actions: Offline Download (with progress ring / downloaded checkmark) + Play on TV
            if (canPlay) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Download / Progress / Downloaded action button
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (isOfflineReady) colors.accentSoft else colors.surfaceRaised,
                            )
                            .clickable(enabled = !enqueuingDownload, onClick = onDownloadAction),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            enqueuingDownload -> {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            offlineItem?.status == OfflineDownloadStatus.DOWNLOADING -> {
                                CircularProgressIndicator(
                                    progress = { offlineItem.progressFraction.coerceIn(0.05f, 1f) },
                                    color = colors.accent,
                                    trackColor = colors.meterTrack,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = "Pause download",
                                    tint = colors.accent,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            offlineItem?.status == OfflineDownloadStatus.QUEUED -> {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            offlineItem?.status == OfflineDownloadStatus.COMPLETED -> {
                                Icon(
                                    imageVector = Icons.Rounded.OfflinePin,
                                    contentDescription = "Downloaded offline",
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            offlineItem?.status == OfflineDownloadStatus.PAUSED ||
                                offlineItem?.status == OfflineDownloadStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Resume download",
                                    tint = colors.warn,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Download episode",
                                    tint = colors.textDim,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }

                    // Play on TV action button (tap = smart cast, long-press = pick TV)
                    if (episode.available && !episode.jellyfinItemId.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised)
                                .combinedClickable(
                                    enabled = playEnabled,
                                    onClick = { onPlayOnTv(false) },
                                    onLongClick = { onPlayOnTv(true) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (playingOnTv) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Tv,
                                    contentDescription = "Play episode on TV",
                                    tint = colors.textDim,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
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
