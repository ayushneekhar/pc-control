package com.neekhar.pccontrol.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatItem
import com.neekhar.pccontrol.core.ui.PcStatStrip
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.cams.LinkKind
import com.neekhar.pccontrol.feature.cams.WebRtcCamLink
import com.neekhar.pccontrol.feature.cams.WebRtcLivePlayer
import com.neekhar.pccontrol.feature.cams.WebRtcRuntime
import com.neekhar.pccontrol.feature.system.ServiceHelpers
import com.neekhar.pccontrol.feature.tvremote.TvNowPlayingBar
import com.neekhar.pccontrol.feature.tvremote.TvRemoteFormatters
import com.neekhar.pccontrol.feature.tvremote.TvRemoteUiState
import com.neekhar.pccontrol.feature.tvremote.TvRemoteViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.webrtc.VideoTrack
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Process-scoped last-frame cache for the 3 Home camera tiles so tab switches never flash blank. */
private val homeCamLastPainters = ConcurrentHashMap<String, Painter>()

@Composable
fun HomeScreen(
    api: PcApiClient,
    cache: LocalCache,
    onOpenVitals: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenCams: (initialTab: String?) -> Unit,
    onOpenSeerr: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAgentView: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockKeyring: () -> Unit = onOpenSettings,
    tvRemoteViewModel: TvRemoteViewModel? = null,
    onOpenTvRemote: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(api, cache)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tvState: TvRemoteUiState? = if (tvRemoteViewModel != null) {
        tvRemoteViewModel.uiState.collectAsStateWithLifecycle().value
    } else {
        null
    }
    var isResumed by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel, tvRemoteViewModel) {
        isResumed = true
        viewModel.onResume()
        tvRemoteViewModel?.setMiniBarOwnerActive("home", true)
        onPauseOrDispose {
            isResumed = false
            viewModel.onPause()
            tvRemoteViewModel?.setMiniBarOwnerActive("home", false)
        }
    }

    HomeScreenContent(
        state = state,
        okHttpClient = api.okHttpClient,
        isResumed = isResumed,
        tvState = tvState,
        resolveTvImageUrl = { path -> tvRemoteViewModel?.resolveImageUrl(path) },
        onToggleTvPlayPause = { tvRemoteViewModel?.togglePlayPause() },
        onOpenTvRemote = onOpenTvRemote,
        onRefresh = {
            viewModel.refresh(isPullToRefresh = true)
            tvRemoteViewModel?.refreshNow(userInitiated = false)
        },
        onEnableCatCams = viewModel::enableCatCams,
        onOpenVitals = onOpenVitals,
        onOpenServices = onOpenServices,
        onOpenCams = onOpenCams,
        onOpenSeerr = onOpenSeerr,
        onOpenSpace = onOpenSpace,
        onOpenDownloads = onOpenDownloads,
        onOpenAgentView = onOpenAgentView,
        onOpenSettings = onOpenSettings,
        onUnlockKeyring = onUnlockKeyring,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onEnableCatCams: () -> Unit,
    onOpenVitals: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenCams: (initialTab: String?) -> Unit,
    onOpenSeerr: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAgentView: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockKeyring: () -> Unit,
    modifier: Modifier = Modifier,
    okHttpClient: OkHttpClient? = null,
    isResumed: Boolean = false,
    tvState: TvRemoteUiState? = null,
    resolveTvImageUrl: (String?) -> String? = { null },
    onToggleTvPlayPause: () -> Unit = {},
    onOpenTvRemote: () -> Unit = {},
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val isCamerasVisible by remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.isEmpty() || visible.any { it.key == "cameras" }
        }
    }
    val heroLiveEnabled = isResumed &&
        isCamerasVisible &&
        state.catCamEnabled &&
        !state.catCamBusy &&
        !state.isOffline &&
        state.baseUrl.isNotBlank() &&
        okHttpClient != null

    val homeTitle = remember(state.info?.host, state.baseUrl) {
        formatHomeTitle(state.info?.host, fallbackUrl = state.baseUrl)
    }
    val uptimeSubtitle = remember(state.vitals?.uptime) { formatHomeUptimeSubtitle(state.vitals?.uptime) }
    val attention = remember(state.keyring, state.health) {
        buildHomeAttentionSummary(
            keyring = state.keyring,
            vpn = state.health?.vpn,
            problems = state.health?.problems.orEmpty(),
        )
    }
    val transfers = state.transfersSummary

    val headerStatusText = if (state.isOffline) "Offline" else uptimeSubtitle
    val headerStatusTone = if (state.isOffline) PcTone.Bad else PcTone.Ok

    PcScaffold(
        title = homeTitle,
        statusText = headerStatusText,
        statusTone = headerStatusTone,
        showStatusDot = state.isOffline || uptimeSubtitle != null,
        listState = listState,
        applyNavigationBarPadding = false,
        modifier = modifier,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
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
                contentPadding = PaddingValues(top = topPad, bottom = PcDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // Offline banner
                if (state.isOffline) {
                    item(key = "offline_banner") {
                        PcErrorBanner(
                            title = "Can't reach the laptop",
                            message = "Check the server address in Settings and make sure Tailscale is connected.",
                            tone = PcTone.Bad,
                            icon = Icons.Rounded.WifiOff,
                            actionLabel = "Open Settings",
                            onAction = onOpenSettings,
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                bottom = PcDimens.Space4,
                            ),
                        )
                    }
                }

                // Vitals Stat Strip
                item(key = "vitals_strip") {
                    val v = state.vitals
                    Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                        if (v != null) {
                            val gib = 1024.0 * 1024.0 * 1024.0
                            val cpuTemp = ServiceHelpers.primaryCpuTemp(v)
                            PcStatStrip(
                                onClick = onOpenVitals,
                                items = listOf(
                                    PcStatItem(
                                        label = "CPU",
                                        value = "${v.cpu.roundToInt()}",
                                        unit = "%",
                                        valueColor = if (v.cpu >= 80.0) colors.warn else null,
                                    ),
                                    PcStatItem(
                                        label = "Memory",
                                        value = String.format(Locale.US, "%.1f", v.mem.used / gib),
                                        unit = "/ ${(v.mem.total / gib).roundToInt()} GB",
                                        valueColor = if (v.mem.pct >= 80.0) colors.warn else null,
                                    ),
                                    PcStatItem(
                                        label = "Temp",
                                        value = cpuTemp?.let { "${it.roundToInt()}" } ?: "–",
                                        unit = if (cpuTemp != null) "°C" else null,
                                        valueColor = when {
                                            cpuTemp == null -> null
                                            cpuTemp >= 85.0 -> colors.bad
                                            cpuTemp >= 70.0 -> colors.warn
                                            else -> null
                                        },
                                    ),
                                ),
                            )
                        } else if (!state.loaded) {
                            PcCard {
                                Text(
                                    text = "Loading vitals…",
                                    color = colors.textDim,
                                    style = type.body,
                                )
                            }
                        }
                    }
                }

                // Exception-only Needs Attention row (hidden when everything is healthy)
                if (!state.isOffline && attention != null) {
                    item(key = "needs_attention") {
                        NeedsAttentionRow(
                            summary = attention,
                            onClick = {
                                when (attention.primaryAction) {
                                    HomeAttentionAction.UNLOCK_KEYRING -> onUnlockKeyring()
                                    HomeAttentionAction.OPEN_SERVICES -> onOpenServices()
                                    HomeAttentionAction.OPEN_SETTINGS -> onOpenSettings()
                                }
                            },
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space3,
                            ),
                        )
                    }
                }

                // Compact "Playing on TV" bar when Jellyfin playback is active on projector
                if (!state.isOffline && tvState != null && tvState.isPlaying) {
                    item(key = "tv_now_playing_bar") {
                        TvNowPlayingBar(
                            state = tvState,
                            resolveImageUrl = resolveTvImageUrl,
                            onTogglePlayPause = onToggleTvPlayPause,
                            onOpenRemote = onOpenTvRemote,
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space3,
                            ),
                        )
                    }
                }

                // Cameras section: exact 24.dp above label, 8.dp from label to card, full 48.dp action touch targets
                item(key = "cameras") {
                    if (state.catCamEnabled) {
                        Column(
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text(
                                    text = "Cameras",
                                    style = type.sectionLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 2.dp, bottom = PcDimens.Space2),
                                )
                                Row(
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    HomeSectionActionButton(
                                        label = "Replay",
                                        onClick = { onOpenCams("replay") },
                                    )
                                    HomeSectionActionButton(
                                        label = "Music",
                                        onClick = { onOpenCams("music") },
                                    )
                                }
                            }
                            CamerasMosaicCard(
                                baseUrl = state.baseUrl,
                                tick = state.camTick,
                                okHttpClient = okHttpClient,
                                heroLiveEnabled = heroLiveEnabled,
                                detectState = state.detectState,
                                latestSighting = state.latestSighting,
                                onClick = { onOpenCams(null) },
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space6,
                            ),
                        ) {
                            CatCamsOffCard(
                                busy = state.catCamBusy,
                                error = state.catCamError,
                                onEnable = onEnableCatCams,
                                onOpenCams = { onOpenCams(null) },
                            )
                        }
                    }
                }

                // Active Transfers Card (shown when qBittorrent or Google Drive transfers are active)
                if (transfers.activeCount > 0) {
                    item(key = "active_transfers") {
                        ActiveTransfersCard(
                            summary = transfers,
                            onClick = {
                                if (transfers.isDriveOnly) onOpenSpace() else onOpenDownloads()
                            },
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space6,
                            ),
                        )
                    }
                }

                // Quick Actions / Open section
                item(key = "open_section") {
                    OpenRoutesSection(
                        state = state,
                        tvState = tvState,
                        transfers = transfers,
                        context = context,
                        onOpenSeerr = onOpenSeerr,
                        onOpenTvRemote = onOpenTvRemote,
                        onOpenSpace = onOpenSpace,
                        onOpenDownloads = onOpenDownloads,
                        onOpenAgentView = onOpenAgentView,
                        modifier = Modifier.padding(
                            start = PcDimens.ScreenPad,
                            end = PcDimens.ScreenPad,
                            top = PcDimens.Space6,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionActionButton(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp)
            .height(48.dp)
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .indication(interactionSource = interactionSource, indication = ripple())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = colors.accent,
                style = type.sectionLabel.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NeedsAttentionRow(
    summary: HomeAttentionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val isBad = summary.tone == HomeAttentionTone.BAD
    val fg = if (isBad) colors.bad else colors.warn
    val bg = if (isBad) colors.badSoft else colors.warnSoft

    PcCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = "Needs attention",
                    color = colors.text,
                    style = type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.summaryText,
                    color = fg,
                    style = type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CamerasMosaicCard(
    baseUrl: String,
    tick: Long,
    okHttpClient: OkHttpClient?,
    heroLiveEnabled: Boolean,
    detectState: DetectState?,
    latestSighting: CatSighting?,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val loadedCams = remember { mutableStateMapOf<String, Boolean>() }
    val liveCount = HOME_CAMS.count { loadedCams[it.id] == true }

    PcCard(
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.media),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CameraHeroLiveTile(
                cam = HOME_CAMS[0],
                baseUrl = baseUrl,
                tick = tick,
                okHttpClient = okHttpClient,
                liveEnabled = heroLiveEnabled,
                onLoadState = { loaded -> loadedCams[HOME_CAMS[0].id] = loaded },
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CameraSnapshotTile(
                    cam = HOME_CAMS[1],
                    baseUrl = baseUrl,
                    tick = tick,
                    onLoadState = { loaded -> loadedCams[HOME_CAMS[1].id] = loaded },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                CameraSnapshotTile(
                    cam = HOME_CAMS[2],
                    baseUrl = baseUrl,
                    tick = tick,
                    onLoadState = { loaded -> loadedCams[HOME_CAMS[2].id] = loaded },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PcStatusDot(
                    tone = if (liveCount > 0) PcTone.Ok else PcTone.Faint,
                    size = 7.dp,
                )
                Text(
                    text = if (liveCount > 0) "$liveCount live" else "${HOME_CAMS.size} cams",
                    color = colors.text,
                    style = type.body.tabular().copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = when {
                    latestSighting != null -> "Last cat seen ${PcFormatters.formatSeenAgo(latestSighting.ts)} · ${latestSighting.name}"
                    detectState != null -> "No cats yet"
                    else -> "Detector offline"
                },
                color = colors.textDim,
                style = type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CameraHeroLiveTile(
    cam: CamDefinition,
    baseUrl: String,
    tick: Long,
    okHttpClient: OkHttpClient?,
    liveEnabled: Boolean,
    onLoadState: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    val safeMode = remember(context) { WebRtcRuntime.isSafeMode(context) }
    val activeSrc = if (liveEnabled && !safeMode && okHttpClient != null && baseUrl.isNotBlank()) {
        cam.id
    } else {
        null
    }

    // Lifecycle- & visibility-aware muted WebRTC video stream (recvonly video, no audio).
    // Tears down PeerConnection immediately when Home pauses, tab switches, or scrolls off-screen.
    val idleTrack = remember { MutableStateFlow<VideoTrack?>(null) }
    val link = remember(activeSrc, baseUrl, okHttpClient) {
        if (activeSrc == null || okHttpClient == null) {
            null
        } else {
            WebRtcCamLink(
                context = context,
                okHttpClient = okHttpClient,
                baseUrl = baseUrl,
                src = activeSrc,
                kind = LinkKind.VIDEO,
            )
        }
    }

    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }

    val rawVideoTrack by (link?.videoTrack ?: idleTrack).collectAsState()
    val videoTrack = if (activeSrc != null) rawVideoTrack else null
    var firstFrameRendered by remember(cam.id, videoTrack) { mutableStateOf(false) }
    val videoLive = videoTrack != null && firstFrameRendered

    var lastPainter by remember(cam.id) { mutableStateOf(homeCamLastPainters[cam.id]) }
    var failed by remember(cam.id) { mutableStateOf(false) }

    LaunchedEffect(cam.id, lastPainter, videoLive) {
        if (videoLive || lastPainter != null) {
            onLoadState(true)
        }
    }

    // Keep refreshing JPEG fallback while waiting for first WebRTC frame (or in safe mode);
    // freeze JPEG polling once WebRTC video is actively rendering frames.
    var snapshotTick by remember(cam.id) { mutableStateOf(tick) }
    if (!videoLive) {
        snapshotTick = tick
    }

    val url = remember(baseUrl, cam.id, snapshotTick) {
        if (baseUrl.isBlank()) null else "$baseUrl/api/frame.jpeg?src=${cam.id}&t=$snapshotTick"
    }

    val request = remember(url, context) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(false)
                .build()
        }
    }

    val snapshotOverlayAlpha by animateFloatAsState(
        targetValue = if (videoLive) 0f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "home_hero_snapshot_fade",
    )

    Box(
        modifier = modifier
            .background(colors.media)
            .clip(RoundedCornerShape(0.dp)),
    ) {
        val cached = lastPainter
        if (cached != null) {
            Image(
                painter = cached,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!videoLive) {
            if (failed || request == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF161616)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideocamOff,
                        contentDescription = null,
                        tint = colors.onMediaDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                CameraTileShimmer(modifier = Modifier.fillMaxSize())
            }
        }

        if (videoTrack != null) {
            WebRtcLivePlayer(
                videoTrack = videoTrack,
                zoomable = true,
                onFirstFrameRendered = { firstFrameRendered = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (request != null && snapshotOverlayAlpha > 0.01f) {
            AsyncImage(
                model = request,
                contentDescription = cam.name,
                contentScale = ContentScale.Crop,
                placeholder = cached,
                error = cached,
                onSuccess = { result ->
                    homeCamLastPainters[cam.id] = result.painter
                    lastPainter = result.painter
                    failed = false
                    onLoadState(true)
                },
                onError = {
                    if (lastPainter == null && !videoLive) {
                        failed = true
                        onLoadState(false)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(snapshotOverlayAlpha),
            )
        }

        // Dark pill label always sits above both live frames and snapshot placeholders
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(CircleShape)
                .background(colors.mediaChip)
                .border(0.5.dp, colors.mediaChipBorder, CircleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (videoLive) {
                PcStatusDot(tone = PcTone.Ok, size = 5.dp)
            }
            Text(
                text = cam.name,
                color = colors.onMedia,
                style = type.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CameraSnapshotTile(
    cam: CamDefinition,
    baseUrl: String,
    tick: Long,
    onLoadState: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val context = LocalContext.current
    var lastPainter by remember(cam.id) { mutableStateOf(homeCamLastPainters[cam.id]) }
    var failed by remember(cam.id) { mutableStateOf(false) }

    LaunchedEffect(cam.id, lastPainter) {
        if (lastPainter != null) {
            onLoadState(true)
        }
    }

    val url = remember(baseUrl, cam.id, tick) {
        if (baseUrl.isBlank()) null else "$baseUrl/api/frame.jpeg?src=${cam.id}&t=$tick"
    }

    val request = remember(url, context) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(false)
                .build()
        }
    }

    Box(
        modifier = modifier
            .background(colors.media)
            .clip(RoundedCornerShape(0.dp)),
    ) {
        if (lastPainter == null) {
            if (failed || request == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF161616)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideocamOff,
                        contentDescription = null,
                        tint = colors.onMediaDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                CameraTileShimmer(modifier = Modifier.fillMaxSize())
            }
        }

        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = cam.name,
                contentScale = ContentScale.Crop,
                placeholder = lastPainter,
                error = lastPainter,
                onSuccess = { result ->
                    homeCamLastPainters[cam.id] = result.painter
                    lastPainter = result.painter
                    failed = false
                    onLoadState(true)
                },
                onError = {
                    if (lastPainter == null) {
                        failed = true
                        onLoadState(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Dark pill label always sits above both live frames and loading shimmer placeholders
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(CircleShape)
                .background(colors.mediaChip)
                .border(0.5.dp, colors.mediaChipBorder, CircleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text = cam.name,
                color = colors.onMedia,
                style = type.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CameraTileShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "home_cam_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "home_cam_shimmer_progress",
    )
    val startX = -260f + progress * 720f
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF161616),
            Color(0xFF252525),
            Color(0xFF161616),
        ),
        start = Offset(startX, 0f),
        end = Offset(startX + 260f, 160f),
    )
    Box(modifier = modifier.background(brush))
}

@Composable
private fun CatCamsOffCard(
    busy: Boolean,
    error: String?,
    onEnable: () -> Unit,
    onOpenCams: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    PcCard(onClick = onOpenCams) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.VideocamOff,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Cat cams off",
                    color = colors.text,
                    style = type.cardTitle,
                )
                Text(
                    text = error ?: "Streams & cat detection are paused",
                    color = if (error != null) colors.bad else colors.textFaint,
                    style = type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PcButton(
                label = "Enable",
                onClick = onEnable,
                variant = PcButtonVariant.Primary,
                busy = busy,
                compact = true,
            )
        }
    }
}

@Composable
private fun ActiveTransfersCard(
    summary: HomeTransfersSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val pillTone = when (summary.trailingTone) {
        HomeTransfersTone.ACCENT -> PcTone.Accent
        HomeTransfersTone.WARN -> PcTone.Warn
        HomeTransfersTone.DIM, null -> PcTone.Dim
    }
    val icon = when {
        summary.isDriveOnly && summary.uploadCount > 0 -> Icons.Rounded.CloudUpload
        summary.torrentCount > 0 && summary.uploadCount == 0 -> Icons.Rounded.Download
        else -> Icons.Rounded.SwapVert
    }
    val title = when {
        summary.isDriveOnly && summary.uploadCount > 0 && summary.restoreCount == 0 -> "Drive offload"
        summary.isDriveOnly && summary.restoreCount > 0 && summary.uploadCount == 0 -> "Drive restore"
        summary.torrentCount > 0 && summary.uploadCount == 0 && summary.restoreCount == 0 -> "Downloads"
        else -> "Active transfers"
    }
    val barColor = if (summary.trailingTone == HomeTransfersTone.ACCENT) colors.accent else colors.warn

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
    ) {
        PcSectionLabel(label = "Transfers")
        PcCard(onClick = onClick) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = barColor,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        color = colors.text,
                        style = type.cardTitle,
                    )
                    if (!summary.subtitle.isNullOrBlank()) {
                        Text(
                            text = summary.subtitle,
                            color = colors.textDim,
                            style = type.caption.tabular(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!summary.trailing.isNullOrBlank()) {
                    PcPill(label = summary.trailing, tone = pillTone)
                }
            }
            if (summary.progressFraction != null) {
                Spacer(modifier = Modifier.height(10.dp))
                PcProgressBar(
                    progress = summary.progressFraction,
                    color = barColor,
                    height = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun OpenRoutesSection(
    state: HomeUiState,
    tvState: TvRemoteUiState?,
    transfers: HomeTransfersSummary,
    context: Context,
    onOpenSeerr: () -> Unit,
    onOpenTvRemote: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAgentView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val remainingExternalRoutes = remember(state.routes) {
        state.routes
            .filter { r ->
                !r.name.contains("jellyfin", ignoreCase = true) &&
                    r.port != 8096 &&
                    r.path != "/cams/" &&
                    !r.name.equals("Cat cams", ignoreCase = true) &&
                    r.path != "/agentview" &&
                    !r.name.equals("Agent view", ignoreCase = true) &&
                    !r.name.equals("Seerr", ignoreCase = true) &&
                    !r.name.equals("Jellyseerr", ignoreCase = true) &&
                    !r.name.equals("Downloads", ignoreCase = true) &&
                    !r.name.equals("Space", ignoreCase = true) &&
                    !r.name.equals("Space Manager", ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
    }

    val transfersTrailingColor = when (transfers.trailingTone) {
        HomeTransfersTone.ACCENT -> colors.accent
        HomeTransfersTone.WARN -> colors.warn
        HomeTransfersTone.DIM, null -> colors.textDim
    }
    val hddFree = state.hddFreeBytes
    val hddWarn = hddFree != null && hddFree < 150L * 1024L * 1024L * 1024L

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
    ) {
        PcSectionLabel(label = "Open")
        PcListCard {
            // 1. Primary everyday media & storage apps (curated priority order)
            PcListRow(
                title = "Seerr",
                subtitle = "Search & request movies or TV",
                icon = Icons.Rounded.Movie,
                showChevron = true,
                showDivider = true,
                onClick = onOpenSeerr,
            )
            val tvNowPlaying = tvState?.session?.nowPlaying
            val tvSubtitle = when {
                tvState != null && tvState.isPlaying && tvNowPlaying != null -> {
                    val verb = if (tvState.isPaused) "Paused" else "Playing"
                    "$verb · ${TvRemoteFormatters.miniBarTitle(tvNowPlaying)}"
                }
                tvState?.connected == true -> "${tvState.deviceName} · Connected"
                else -> "${tvState?.deviceName ?: "Lumio Arc 7"} · Jellyfin remote"
            }
            PcListRow(
                title = "TV Remote",
                subtitle = tvSubtitle,
                icon = Icons.Rounded.Tv,
                trailingContent = if (tvState?.connected == true) {
                    {
                        PcStatusDot(
                            tone = when {
                                tvState.isPlaying && !tvState.isPaused -> PcTone.Ok
                                tvState.isPlaying && tvState.isPaused -> PcTone.Warn
                                else -> PcTone.Dim
                            },
                            size = 7.dp,
                        )
                    }
                } else {
                    null
                },
                showChevron = true,
                showDivider = true,
                onClick = onOpenTvRemote,
            )
            PcListRow(
                title = "Downloads",
                subtitle = transfers.subtitle ?: "qBittorrent & Drive transfers",
                icon = Icons.Rounded.Download,
                trailingContent = transfers.trailing?.let { badge ->
                    {
                        Text(
                            text = badge,
                            color = transfersTrailingColor,
                            style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
                        )
                    }
                },
                showChevron = true,
                showDivider = true,
                onClick = onOpenDownloads,
            )
            PcListRow(
                title = "Space",
                subtitle = "Media storage & Google Drive offload",
                icon = Icons.Rounded.Storage,
                trailingContent = hddFree?.let { freeBytes ->
                    {
                        Text(
                            text = "${PcFormatters.formatBytes(freeBytes, digits = 1)} free",
                            color = if (hddWarn) colors.warn else colors.textDim,
                            style = type.caption.tabular(),
                        )
                    }
                },
                showChevron = true,
                showDivider = true,
                onClick = onOpenSpace,
            )

            // 2. Agent tools (Agent settings lives cleanly in Settings & the Chat Model Picker)
            PcListRow(
                title = "Agent view",
                subtitle = "Watcher rules & live activity",
                icon = Icons.Rounded.SmartToy,
                showChevron = true,
                showDivider = remainingExternalRoutes.isNotEmpty(),
                onClick = onOpenAgentView,
            )

            // 3. Remaining dynamic server routes sorted alphabetically
            remainingExternalRoutes.forEachIndexed { idx, route ->
                PcListRow(
                    title = route.name,
                    subtitle = route.subtitle.takeIf { it.isNotBlank() },
                    icon = RouteIcons.forName(route.icon),
                    showChevron = true,
                    showDivider = idx < remainingExternalRoutes.lastIndex,
                    onClick = { launchExternalRoute(context, route) },
                )
            }
        }
    }
}

private fun launchExternalRoute(context: Context, route: RouteItem) {
    val pkg = route.app?.takeIf { it.isNotBlank() }
    if (pkg != null) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(launchIntent)
                return
            }
        }
    }
    val url = route.url.takeIf { it.isNotBlank() } ?: return
    runCatching {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(viewIntent)
    }
}
