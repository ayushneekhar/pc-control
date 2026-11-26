package com.neekhar.pccontrol.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
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
import com.neekhar.pccontrol.core.ui.PcScreenHeader
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

    val statusTone = when {
        state.isOffline -> PcTone.Bad
        state.problemCount > 0 -> PcTone.Warn
        state.info != null -> PcTone.Ok
        else -> PcTone.Faint
    }

    val statusText = when {
        state.isOffline -> "Can't reach the laptop"
        state.info != null -> {
            val host = PcFormatters.formatShortHost(state.info.host)
            val up = state.vitals?.let { " · up ${PcFormatters.formatUptimeShort(it.uptime)}" }.orEmpty()
            "$host$up"
        }
        else -> "Connecting to the laptop…"
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
                verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
            ) {
                // Header
                item(key = "header") {
                    PcScreenHeader(
                        title = "Home",
                        statusText = statusText,
                        statusTone = statusTone,
                        showStatusDot = true,
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

                // Keyring locked banner
                if (state.keyring?.locked == true) {
                    item(key = "keyring_banner") {
                        val rdpSuffix = state.keyring.rdp.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                        PcErrorBanner(
                            title = "Keyring locked · RDP unavailable",
                            message = "Unlock the laptop's login keyring to restart remote desktop$rdpSuffix.",
                            tone = PcTone.Warn,
                            icon = Icons.Filled.Lock,
                            actionLabel = "Unlock",
                            onAction = onUnlockKeyring,
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

                // Inline Quick Status Pills (VPN, Keyring, Service health issues)
                val vpn = state.health?.vpn
                val hasStatusPills = vpn != null || state.keyring != null || state.problemCount > 0
                if (hasStatusPills) {
                    item(key = "status_pills") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = PcDimens.ScreenPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.problemCount > 0) {
                                val hasBad = state.health?.problems?.any { it.severity == "bad" } == true
                                PcPill(
                                    label = "${state.problemCount} issue${if (state.problemCount == 1) "" else "s"}",
                                    tone = if (hasBad) PcTone.Bad else PcTone.Warn,
                                    showDot = true,
                                    modifier = Modifier.clickable(onClick = onOpenServices),
                                )
                            }
                            if (vpn != null) {
                                val vpnTone = when {
                                    vpn.enabled && vpn.tunnelUp -> PcTone.Ok
                                    vpn.enabled && !vpn.tunnelUp -> PcTone.Warn
                                    else -> PcTone.Dim
                                }
                                val vpnLabel = when {
                                    vpn.enabled && vpn.tunnelUp -> "Proton VPN · On"
                                    vpn.enabled && !vpn.tunnelUp -> "Proton VPN · Reconnecting"
                                    else -> "Proton VPN · Off"
                                }
                                PcPill(
                                    label = vpnLabel,
                                    tone = vpnTone,
                                    icon = Icons.Filled.Shield,
                                )
                            }
                            if (state.keyring != null) {
                                val locked = state.keyring.locked
                                PcPill(
                                    label = if (locked) "Keyring · Locked" else "Keyring · Unlocked",
                                    tone = if (locked) PcTone.Warn else PcTone.Ok,
                                    icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    modifier = if (locked) Modifier.clickable(onClick = onUnlockKeyring) else Modifier,
                                )
                            }
                        }
                    }
                }

                // Cameras Card (Live mosaic when ON; compact "Cat cams off" card with one-tap enable when OFF)
                item(key = "cameras") {
                    Column(
                        modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
                    ) {
                        if (state.catCamEnabled) {
                            PcSectionLabel(
                                label = "Cameras",
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "Replay",
                                            color = colors.textFaint,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable { onOpenCams("replay") },
                                        )
                                        Text(text = "·", color = colors.textFaint, fontSize = 12.5.sp)
                                        Text(
                                            text = "Music",
                                            color = colors.textFaint,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable { onOpenCams("music") },
                                        )
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
                .height(184.dp)
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
    val externalRoutes = remember(state.routes) {
        state.routes.filter { r ->
            r.path != "/cams/" &&
                !r.name.equals("Cat cams", ignoreCase = true) &&
                r.path != "/agentview"
        }
    }
    val hasJellyfinRoute = remember(externalRoutes) {
        externalRoutes.any { it.name.contains("jellyfin", ignoreCase = true) || it.port == 8096 }
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
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(label = "Open")
        PcListCard {
            PcListRow(
                title = "Seerr",
                subtitle = "Search & request movies or TV",
                icon = Icons.Filled.Movie,
                showChevron = true,
                showDivider = true,
                onClick = onOpenSeerr,
            )
            PcListRow(
                title = "Space Manager",
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

            // Dynamic server routes (including Jellyfin when reported by /pc/api/routes)
            externalRoutes.forEach { route ->
                PcListRow(
                    title = route.name,
                    subtitle = route.subtitle.takeIf { it.isNotBlank() },
                    icon = RouteIcons.forName(route.icon),
                    showChevron = true,
                    showDivider = true,
                    onClick = { launchExternalRoute(context, route) },
                )
            }

            if (!hasJellyfinRoute) {
                PcListRow(
                    title = "Jellyfin",
                    subtitle = "Media server",
                    icon = Icons.Filled.LiveTv,
                    showChevron = true,
                    showDivider = true,
                    onClick = {
                        val fallbackUrl = "${state.baseUrl.trimEnd('/')}:8096/"
                        launchExternalRoute(
                            context,
                            RouteItem(
                                id = "jellyfin",
                                name = "Jellyfin",
                                url = fallbackUrl,
                                app = "org.jellyfin.mobile",
                            ),
                        )
                    },
                )
            }

            PcListRow(
                title = "Agent Settings",
                subtitle = "Models, quotas & usage alerts",
                icon = Icons.Filled.Tune,
                showChevron = true,
                showDivider = true,
                onClick = onOpenAgentSettings,
            )
            PcListRow(
                title = "Agent View",
                subtitle = "Watcher rules & live activity",
                icon = Icons.Filled.SmartToy,
                showChevron = true,
                showDivider = false,
                onClick = onOpenAgentView,
            )
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
