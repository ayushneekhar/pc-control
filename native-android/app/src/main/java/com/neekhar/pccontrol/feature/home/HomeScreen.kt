package com.neekhar.pccontrol.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatItem
import com.neekhar.pccontrol.core.ui.PcStatStrip
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.system.ServiceHelpers
import java.util.Locale
import kotlin.math.roundToInt

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
    onOpenAgentSettings: () -> Unit,
    onOpenAgentView: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockKeyring: () -> Unit = onOpenSettings,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(api, cache)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    HomeScreenContent(
        state = state,
        onRefresh = { viewModel.refresh(isPullToRefresh = true) },
        onEnableCatCams = viewModel::enableCatCams,
        onOpenVitals = onOpenVitals,
        onOpenServices = onOpenServices,
        onOpenCams = onOpenCams,
        onOpenSeerr = onOpenSeerr,
        onOpenSpace = onOpenSpace,
        onOpenDownloads = onOpenDownloads,
        onOpenAgentSettings = onOpenAgentSettings,
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
    onOpenAgentSettings: () -> Unit,
    onOpenAgentView: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockKeyring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val pullState = rememberPullToRefreshState()

    val homeTitle = remember(state.info?.host) { formatHomeTitle(state.info?.host) }
    val uptimeSubtitle = remember(state.vitals?.uptime) { formatHomeUptimeSubtitle(state.vitals?.uptime) }
    val attention = remember(state.keyring, state.health) {
        buildHomeAttentionSummary(
            keyring = state.keyring,
            vpn = state.health?.vpn,
            problems = state.health?.problems.orEmpty(),
        )
    }
    val transfers = state.transfersSummary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
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
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = PcDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(PcDimens.Space6),
            ) {
                // Header: hostname with quiet uptime subtitle underneath
                item(key = "header") {
                    HomeHeader(
                        title = homeTitle,
                        uptimeSubtitle = uptimeSubtitle,
                        isOffline = state.isOffline,
                    )
                }

                // Offline banner
                if (state.isOffline) {
                    item(key = "offline_banner") {
                        PcErrorBanner(
                            title = "Can't reach the laptop",
                            message = "Check the server address in Settings and make sure Tailscale is connected.",
                            tone = PcTone.Bad,
                            icon = Icons.Filled.WifiOff,
                            actionLabel = "Open Settings",
                            onAction = onOpenSettings,
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
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
                                        valueColor = if (v.cpu > 85.0) colors.warn else null,
                                    ),
                                    PcStatItem(
                                        label = "Memory",
                                        value = String.format(Locale.US, "%.1f", v.mem.used / gib),
                                        unit = "/${(v.mem.total / gib).roundToInt()}G",
                                        valueColor = if (v.mem.pct > 85.0) colors.warn else null,
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
                                    fontSize = 13.5.sp,
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
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        )
                    }
                }

                // Cameras Card (Live mosaic when ON; compact "Cat cams off" card with one-tap enable when OFF)
                item(key = "cameras") {
                    Column(
                        modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        verticalArrangement = Arrangement.spacedBy(if (state.catCamEnabled) 2.dp else PcDimens.Space2),
                    ) {
                        if (state.catCamEnabled) {
                            PcSectionLabel(
                                label = "Cameras",
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(role = Role.Button) { onOpenCams("replay") }
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "Replay",
                                                color = colors.textDim,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(role = Role.Button) { onOpenCams("music") }
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "Music",
                                                color = colors.textDim,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                },
                            )
                            CamerasMosaicCard(
                                baseUrl = state.baseUrl,
                                tick = state.camTick,
                                detectState = state.detectState,
                                latestSighting = state.latestSighting,
                                onClick = { onOpenCams(null) },
                            )
                        } else {
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
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        )
                    }
                }

                // Quick Actions / Open section
                item(key = "open_section") {
                    OpenRoutesSection(
                        state = state,
                        transfers = transfers,
                        context = context,
                        onOpenSeerr = onOpenSeerr,
                        onOpenSpace = onOpenSpace,
                        onOpenDownloads = onOpenDownloads,
                        onOpenAgentSettings = onOpenAgentSettings,
                        onOpenAgentView = onOpenAgentView,
                        modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    title: String,
    uptimeSubtitle: String?,
    isOffline: Boolean,
) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PcDimens.ScreenPad,
                end = PcDimens.ScreenPad,
                top = PcDimens.Space4,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = colors.text,
            fontSize = 30.sp,
            lineHeight = 35.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            isOffline -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(tone = PcTone.Bad, size = 7.dp)
                    Text(
                        text = "Offline",
                        color = colors.bad,
                        style = TabularNumStyle.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            uptimeSubtitle != null -> {
                Text(
                    text = uptimeSubtitle,
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                    imageVector = Icons.Filled.Warning,
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.summaryText,
                    color = fg,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
    detectState: DetectState?,
    latestSighting: CatSighting?,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
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
            CameraSnapshotTile(
                cam = HOME_CAMS[0],
                baseUrl = baseUrl,
                tick = tick,
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
                    style = TabularNumStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
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
                fontSize = 12.5.sp,
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
    val context = LocalContext.current
    var lastPainter by remember(cam.id) { mutableStateOf<Painter?>(null) }
    var failed by remember(cam.id) { mutableStateOf(false) }

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
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = cam.name,
                contentScale = ContentScale.Crop,
                placeholder = lastPainter,
                error = lastPainter,
                onSuccess = { result ->
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

        if (lastPainter == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (failed || request == null) {
                    Icon(
                        imageVector = Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = colors.onMediaDim,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        color = colors.onMediaDim,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.mediaChip)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = cam.name,
                color = colors.onMedia,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CatCamsOffCard(
    busy: Boolean,
    error: String?,
    onEnable: () -> Unit,
    onOpenCams: () -> Unit,
) {
    val colors = LocalPcColors.current
    PcCard(onClick = onOpenCams) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VideocamOff,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = error ?: "Streams & cat detection are paused",
                    color = if (error != null) colors.bad else colors.textFaint,
                    fontSize = 12.5.sp,
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
    val pillTone = when (summary.trailingTone) {
        HomeTransfersTone.ACCENT -> PcTone.Accent
        HomeTransfersTone.WARN -> PcTone.Warn
        HomeTransfersTone.DIM, null -> PcTone.Dim
    }
    val icon = when {
        summary.isDriveOnly && summary.uploadCount > 0 -> Icons.Filled.CloudUpload
        summary.torrentCount > 0 && summary.uploadCount == 0 -> Icons.Filled.Download
        else -> Icons.Filled.SwapVert
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!summary.subtitle.isNullOrBlank()) {
                        Text(
                            text = summary.subtitle,
                            color = colors.textDim,
                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
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
    transfers: HomeTransfersSummary,
    context: Context,
    onOpenSeerr: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenAgentView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val jellyfinRoute = remember(state.routes) {
        state.routes.firstOrNull {
            it.name.contains("jellyfin", ignoreCase = true) || it.port == 8096
        }
    }
    val remainingExternalRoutes = remember(state.routes, jellyfinRoute) {
        state.routes
            .filter { r ->
                r.id != jellyfinRoute?.id &&
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
                title = "Jellyfin",
                subtitle = jellyfinRoute?.subtitle?.takeIf { it.isNotBlank() } ?: "Media server",
                icon = Icons.Filled.LiveTv,
                showChevron = true,
                showDivider = true,
                onClick = {
                    val target = jellyfinRoute ?: RouteItem(
                        id = "jellyfin",
                        name = "Jellyfin",
                        url = "${state.baseUrl.trimEnd('/')}:8096/",
                        app = "org.jellyfin.mobile",
                    )
                    launchExternalRoute(context, target)
                },
            )
            PcListRow(
                title = "Seerr",
                subtitle = "Search & request movies or TV",
                icon = Icons.Filled.Movie,
                showChevron = true,
                showDivider = true,
                onClick = onOpenSeerr,
            )
            PcListRow(
                title = "Downloads",
                subtitle = transfers.subtitle ?: "qBittorrent & Drive transfers",
                icon = Icons.Filled.Download,
                trailingContent = transfers.trailing?.let { badge ->
                    {
                        Text(
                            text = badge,
                            color = transfersTrailingColor,
                            style = TabularNumStyle.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
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
                icon = Icons.Filled.Storage,
                trailingContent = hddFree?.let { freeBytes ->
                    {
                        Text(
                            text = "${PcFormatters.formatBytes(freeBytes, digits = 0)} free",
                            color = if (hddWarn) colors.warn else colors.textDim,
                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        )
                    }
                },
                showChevron = true,
                showDivider = true,
                onClick = onOpenSpace,
            )

            // 2. Agent tools
            PcListRow(
                title = "Agent settings",
                subtitle = "Models, quotas & usage alerts",
                icon = Icons.Filled.Tune,
                showChevron = true,
                showDivider = true,
                onClick = onOpenAgentSettings,
            )
            PcListRow(
                title = "Agent view",
                subtitle = "Watcher rules & live activity",
                icon = Icons.Filled.SmartToy,
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
