package com.neekhar.pccontrol.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.home.ACTIVE_TRANSFER_STATUSES
import com.neekhar.pccontrol.feature.home.PcFormatters
import com.neekhar.pccontrol.feature.home.TransferJob
import com.neekhar.pccontrol.feature.home.transferStatusLabel
import com.neekhar.pccontrol.feature.system.ArrQueue
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    api: PcApiClient,
    cache: LocalCache,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = viewModel(factory = DownloadsViewModel.factory(api, cache)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    DownloadsScreenContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onToggleAltSpeed = viewModel::toggleAltSpeed,
        onTogglePause = viewModel::togglePause,
        onDeleteTorrent = viewModel::deleteTorrent,
        onCancelDriveJob = viewModel::cancelDriveJob,
        onRetryArr = viewModel::retryArrImport,
        onRefreshJellyfin = viewModel::refreshJellyfinLibrary,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreenContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAltSpeed: (Boolean) -> Unit,
    onTogglePause: (TorrentItem) -> Unit,
    onDeleteTorrent: (hash: String, deleteFiles: Boolean) -> Unit,
    onCancelDriveJob: (String) -> Unit,
    onRetryArr: (String) -> Unit,
    onRefreshJellyfin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val pullState = rememberPullToRefreshState()
    var pendingDeleteTorrent by remember { mutableStateOf<TorrentItem?>(null) }

    val total = state.data?.total
    val visibleDriveJobs = state.visibleDriveJobs
    val activeDriveCount = state.activeDriveJobs.size

    val headerStatus = remember(total, activeDriveCount) {
        buildList {
            if (total != null && total.active > 0) {
                add("${total.active} torrent${if (total.active == 1) "" else "s"}")
            }
            if (activeDriveCount > 0) {
                add("$activeDriveCount Drive")
            }
            if (total != null && (total.speed > 0.0 || total.upspeed > 0.0)) {
                add("↓ ${PcFormatters.formatRate(total.speed)}")
            }
        }.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        PcScreenHeader(
            title = "Downloads",
            statusText = headerStatus,
            statusTone = if (total != null && total.active > 0 || activeDriveCount > 0) PcTone.Accent else PcTone.Dim,
            showStatusDot = headerStatus != null,
            onBack = onBack,
        )

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
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PcDimens.ScreenPad,
                    end = PcDimens.ScreenPad,
                    bottom = PcDimens.Space8,
                ),
                verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
            ) {
                if (state.error != null) {
                    item(key = "error") {
                        PcErrorBanner(
                            title = "Downloads",
                            message = state.error,
                            tone = PcTone.Bad,
                        )
                    }
                }

                // Controls card: Alt speed limit + Jellyfin library refresh
                item(key = "controls") {
                    PcCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                tint = if (state.altSpeedEnabled) colors.warn else colors.textDim,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Alt speed limit",
                                color = colors.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.altSpeedEnabled,
                                onCheckedChange = onToggleAltSpeed,
                                enabled = state.data != null && !state.altSpeedBusy,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.onAccent,
                                    checkedTrackColor = colors.warn,
                                    checkedBorderColor = Color.Transparent,
                                    uncheckedThumbColor = colors.textDim,
                                    uncheckedTrackColor = colors.surfaceRaised,
                                    uncheckedBorderColor = colors.hairline,
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        PcButton(
                            label = if (state.jellyfinRefreshed) "Jellyfin refreshed" else "Refresh Jellyfin library",
                            onClick = onRefreshJellyfin,
                            variant = PcButtonVariant.Subtle,
                            icon = if (state.jellyfinRefreshed) Icons.Filled.Check else Icons.Filled.Refresh,
                            busy = state.jellyfinBusy,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Needs Attention (Radarr / Sonarr stuck imports)
                val data = state.data
                if (state.hasStuckArr && data != null) {
                    item(key = "stuck_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        PcSectionLabel(label = "Needs attention")
                    }
                    if (data.radarr.available && data.radarr.items.isNotEmpty()) {
                        item(key = "stuck_radarr") {
                            StuckArrCard(
                                label = "Radarr",
                                url = "${state.baseUrl.trimEnd('/')}:7878/activity/queue",
                                queue = data.radarr,
                                retrying = state.retryingArrApp == "radarr",
                                onRetry = { onRetryArr("radarr") },
                                onOpenExternal = { openBrowser(context, it) },
                            )
                        }
                    }
                    if (data.sonarr.available && data.sonarr.items.isNotEmpty()) {
                        item(key = "stuck_sonarr") {
                            StuckArrCard(
                                label = "Sonarr",
                                url = "${state.baseUrl.trimEnd('/')}:8989/activity/queue",
                                queue = data.sonarr,
                                retrying = state.retryingArrApp == "sonarr",
                                onRetry = { onRetryArr("sonarr") },
                                onOpenExternal = { openBrowser(context, it) },
                            )
                        }
                    }
                }

                // Active / Recent Space / Google Drive Transfer Jobs
                if (visibleDriveJobs.isNotEmpty()) {
                    item(key = "drive_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        PcSectionLabel(
                            label = if (activeDriveCount > 0) {
                                "Drive transfers ($activeDriveCount active)"
                            } else {
                                "Drive transfers (${visibleDriveJobs.size})"
                            },
                            trailing = if (state.activeDriveSpeedBps > 0.0) {
                                {
                                    Text(
                                        text = PcFormatters.formatRate(state.activeDriveSpeedBps),
                                        color = colors.textFaint,
                                        style = TabularNumStyle.copy(fontSize = 12.sp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    items(items = visibleDriveJobs, key = { "drive_${it.id}" }) { job ->
                        DriveTransferCard(
                            job = job,
                            cancelling = state.cancellingJobId == job.id,
                            onCancel = { onCancelDriveJob(job.id) },
                        )
                    }
                }

                // Torrents Section Header
                item(key = "torrents_header") {
                    Spacer(modifier = Modifier.height(4.dp))
                    PcSectionLabel(
                        label = total?.let { "Torrents (${it.count})" } ?: "Torrents",
                        trailing = total?.let {
                            {
                                Text(
                                    text = "↓ ${PcFormatters.formatRate(it.speed)} · ↑ ${PcFormatters.formatRate(it.upspeed)}",
                                    color = colors.textFaint,
                                    style = TabularNumStyle.copy(fontSize = 12.sp),
                                )
                            }
                        },
                    )
                }

                when {
                    data == null -> {
                        items(count = 3, key = { "skel_$it" }) {
                            TorrentSkeletonCard()
                        }
                    }
                    data.qbt != "ok" -> {
                        item(key = "qbt_err") {
                            PcErrorBanner(
                                title = if (data.qbt == "auth") "qBittorrent sign-in failed" else "qBittorrent unavailable",
                                tone = PcTone.Warn,
                            )
                        }
                    }
                    state.torrents.isEmpty() -> {
                        item(key = "torrents_empty") {
                            PcCard {
                                PcEmptyState(
                                    icon = Icons.Filled.Download,
                                    title = "No torrents",
                                    body = "Active and recent qBittorrent transfers appear here.",
                                )
                            }
                        }
                    }
                    else -> {
                        items(items = state.torrents, key = { "torrent_${it.hash}" }) { item ->
                            TorrentCard(
                                item = item,
                                busy = state.busyTorrentHash == item.hash,
                                onTogglePause = { onTogglePause(item) },
                                onDeleteClick = { pendingDeleteTorrent = item },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteTorrent?.let { target ->
        DeleteTorrentDialog(
            torrentName = target.name,
            onKeepFiles = {
                pendingDeleteTorrent = null
                onDeleteTorrent(target.hash, false)
            },
            onDeleteFiles = {
                pendingDeleteTorrent = null
                onDeleteTorrent(target.hash, true)
            },
            onDismiss = { pendingDeleteTorrent = null },
        )
    }
}

private fun torrentStateTone(state: String): PcTone = when (state) {
    "downloading" -> PcTone.Accent
    "seeding" -> PcTone.Ok
    "stalled", "metadata", "checking", "moving" -> PcTone.Warn
    "error" -> PcTone.Bad
    else -> PcTone.Dim
}

private fun driveJobTone(job: TransferJob): PcTone = when (job.status) {
    "copying", "cleaning" -> PcTone.Accent
    "verifying" -> PcTone.Warn
    "done" -> PcTone.Ok
    "failed" -> PcTone.Bad
    else -> PcTone.Dim
}

@Composable
private fun TorrentCard(
    item: TorrentItem,
    busy: Boolean,
    onTogglePause: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val pct = (item.progress * 100.0).roundToInt().coerceIn(0, 100)
    val barColor = when {
        item.state == "error" -> colors.bad
        item.state == "stalled" -> colors.warn
        item.state == "seeding" -> colors.ok
        item.paused -> colors.textFaint
        else -> colors.accent
    }

    val leftMeta = buildString {
        append("$pct%")
        if (item.size > 0L) {
            append(" · ${PcFormatters.formatBytes(item.done, 1)} / ${PcFormatters.formatBytes(item.size, 1)}")
        }
        if (item.eta != null && item.eta > 0L) {
            append(" · ${PcFormatters.formatDuration(item.eta)}")
        }
    }
    val rightSpeed = buildString {
        if (item.speed > 0.0) append("↓ ${PcFormatters.formatRate(item.speed)}")
        if (item.speed > 0.0 && item.upspeed > 0.0) append(" · ")
        if (item.upspeed > 0.0) append("↑ ${PcFormatters.formatRate(item.upspeed)}")
        if (item.speed <= 0.0 && item.upspeed <= 0.0 && item.ratio > 0.0) {
            append(String.format(Locale.US, "%.2fx", item.ratio))
        }
    }

    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.name,
                color = colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PcPill(label = item.state.ifBlank { "unknown" }, tone = torrentStateTone(item.state))
        }

        Spacer(modifier = Modifier.height(10.dp))

        PcProgressBar(
            progress = item.progress.toFloat().coerceIn(0f, 1f),
            color = barColor,
            height = 4.dp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = leftMeta,
                color = colors.textDim,
                style = TabularNumStyle.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rightSpeed.isNotEmpty()) {
                Text(
                    text = rightSpeed,
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.category.isNotBlank()) {
                PcPill(label = item.category, tone = PcTone.Faint)
            }
            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceRaised)
                    .alpha(if (busy) 0.5f else 1f)
                    .clickable(enabled = !busy, onClick = onTogglePause)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = colors.text,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (item.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = if (item.paused) "Resume" else "Pause",
                    color = colors.text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.badSoft)
                    .alpha(if (busy) 0.5f else 1f)
                    .clickable(enabled = !busy, onClick = onDeleteClick)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${item.name}",
                    tint = colors.bad,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DriveTransferCard(
    job: TransferJob,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val colors = LocalPcColors.current
    val isActive = job.status in ACTIVE_TRANSFER_STATUSES
    val isCopying = job.status == "copying"
    val isVerifying = job.status == "verifying"
    val canCancel = isActive && job.status != "cleaning"
    val totalBytes = if (job.bytesTotal > 0L) job.bytesTotal else job.sizeBytes

    val leftText = buildList {
        when {
            isCopying -> {
                add("${job.pct.roundToInt()}%")
                if (totalBytes > 0L) {
                    add("${PcFormatters.formatBytes(job.bytesDone, 1)} / ${PcFormatters.formatBytes(totalBytes, 1)}")
                }
                if (job.etaS != null && job.etaS > 0L) {
                    add("${PcFormatters.formatDuration(job.etaS)} left")
                }
            }
            isVerifying -> add("Checking MD5 hashes")
            job.status == "cleaning" -> add(if (job.op == "offload") "Removing local copy" else "Cleaning remote copy")
            totalBytes > 0L -> add(PcFormatters.formatBytes(totalBytes, 1))
        }
    }.joinToString(" · ")

    val speedText = if (isCopying && job.speedBps > 0.0) {
        "${if (job.op == "restore") "↓" else "↑"} ${PcFormatters.formatRate(job.speedBps)}"
    } else {
        ""
    }

    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (job.op == "restore") Icons.Filled.CloudDownload else Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = when {
                    isCopying -> colors.accent
                    job.status == "failed" -> colors.bad
                    else -> colors.textDim
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = buildString {
                    append(job.title)
                    if (!job.subtitle.isNullOrBlank()) append(" · ${job.subtitle}")
                },
                color = colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PcPill(label = transferStatusLabel(job), tone = driveJobTone(job))
        }

        if (isActive) {
            Spacer(modifier = Modifier.height(10.dp))
            val progress = when {
                job.status == "queued" -> 0f
                isVerifying || job.status == "cleaning" -> 1f
                else -> (job.pct / 100.0).toFloat().coerceIn(0f, 1f)
            }
            PcProgressBar(
                progress = progress,
                color = if (isVerifying) colors.warn else colors.accent,
                height = 4.dp,
            )
        }

        if (leftText.isNotEmpty() || speedText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = leftText,
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (speedText.isNotEmpty()) {
                    Text(
                        text = speedText,
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            }
        }

        if (job.status == "failed" && !job.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = job.error,
                color = colors.bad,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )
        }

        if (canCancel) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PcButton(
                    label = "Cancel",
                    onClick = onCancel,
                    variant = PcButtonVariant.Subtle,
                    busy = cancelling,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun StuckArrCard(
    label: String,
    url: String,
    queue: ArrQueue,
    retrying: Boolean,
    onRetry: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val colors = LocalPcColors.current
    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = colors.bad,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            PcPill(
                label = "${if (queue.needsAction > 0) queue.needsAction else queue.items.size} stuck",
                tone = PcTone.Bad,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            queue.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        color = colors.text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.reason.isNotBlank()) {
                        Text(
                            text = item.reason,
                            color = colors.textFaint,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PcButton(
                label = "Retry import",
                onClick = onRetry,
                variant = PcButtonVariant.Subtle,
                icon = Icons.Filled.Refresh,
                busy = retrying,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            PcButton(
                label = "Open $label",
                onClick = { onOpenExternal(url) },
                variant = PcButtonVariant.Subtle,
                icon = Icons.Filled.OpenInNew,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TorrentSkeletonCard() {
    val colors = LocalPcColors.current
    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(15.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceRaised),
            )
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surfaceRaised),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceRaised),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceRaised),
            )
            Box(
                modifier = Modifier
                    .size(width = 70.dp, height = 12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceRaised),
            )
        }
    }
}

@Composable
private fun DeleteTorrentDialog(
    torrentName: String,
    onKeepFiles: () -> Unit,
    onDeleteFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PcDimens.RadiusMd))
                .background(colors.surface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusMd))
                .padding(PcDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            Text(
                text = "Delete torrent?",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Remove \"$torrentName\" from qBittorrent. Should downloaded files also be deleted from disk?",
                color = colors.textDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcButton(
                    label = "Delete files too",
                    onClick = onDeleteFiles,
                    variant = PcButtonVariant.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
                PcButton(
                    label = "Remove only (keep files)",
                    onClick = onKeepFiles,
                    variant = PcButtonVariant.Subtle,
                    modifier = Modifier.fillMaxWidth(),
                )
                PcButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    variant = PcButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
