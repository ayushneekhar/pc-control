package com.neekhar.pccontrol.feature.space

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcProgressBar
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcTone
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    viewModel: SpaceViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current

    DisposableEffect(viewModel) {
        viewModel.startStream()
        onDispose { viewModel.stopStream() }
    }

    val hdd = state.localData?.hdd
    val (statusText, statusTone) = remember(hdd, state.error) {
        when {
            !state.error.isNullOrBlank() -> "Offline" to PcTone.Bad
            hdd != null && !hdd.mounted -> "HDD not mounted" to PcTone.Warn
            hdd?.freeBytes != null && hdd.freeBytes < hdd.thresholdBytes -> "Low disk space" to PcTone.Warn
            else -> null to PcTone.Faint
        }
    }

    val tabOptions = remember {
        listOf(
            PcSegmentOption(id = SpaceTab.MOVIES, label = SpaceTab.MOVIES.label),
            PcSegmentOption(id = SpaceTab.TV_SHOWS, label = SpaceTab.TV_SHOWS.label),
            PcSegmentOption(id = SpaceTab.OFFLOADED, label = SpaceTab.OFFLOADED.label),
        )
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            PcScreenHeader(
                title = "Space",
                statusText = statusText,
                statusTone = statusTone,
                showStatusDot = statusText != null,
                onBack = onBack,
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 4.dp,
                        bottom = (if (state.hasSelection) 136.dp else 28.dp) + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { err ->
                        item(key = "err_local") {
                            PcErrorBanner(
                                title = err,
                                tone = PcTone.Bad,
                            )
                        }
                    }

                    state.driveError?.let { driveErr ->
                        item(key = "err_drive") {
                            PcErrorBanner(
                                title = "Drive status unavailable",
                                message = driveErr,
                                tone = PcTone.Warn,
                            )
                        }
                    }

                    state.notice?.let { notice ->
                        item(key = "notice") {
                            PcErrorBanner(
                                title = notice.title,
                                message = notice.body,
                                tone = when (notice.tone) {
                                    SpaceNoticeTone.OK -> PcTone.Ok
                                    SpaceNoticeTone.WARN -> PcTone.Warn
                                    SpaceNoticeTone.BAD -> PcTone.Bad
                                },
                                actionLabel = "Dismiss",
                                onAction = viewModel::dismissNotice,
                            )
                        }
                    }

                    state.freeFeedback?.let { fb ->
                        item(key = "free_feedback") {
                            FreeFeedbackCard(
                                feedback = fb,
                                onDismiss = viewModel::dismissFreeFeedback,
                            )
                        }
                    }

                    state.driveDeleteFeedback?.let { fb ->
                        item(key = "drive_delete_feedback") {
                            DriveDeleteFeedbackCard(
                                feedback = fb,
                                onDismiss = viewModel::dismissDriveDeleteFeedback,
                            )
                        }
                    }

                    item(key = "hdd_summary") {
                        if (hdd != null) {
                            HddSummaryCard(hdd = hdd)
                        } else {
                            HddSummarySkeleton()
                        }
                    }

                    state.driveData?.let { drive ->
                        item(key = "drive_summary") {
                            DriveStatusCard(drive = drive)
                        }
                    }

                    if (state.activeJobs.isNotEmpty()) {
                        item(key = "active_transfers") {
                            ActiveTransfersCard(
                                jobs = state.activeJobs,
                                cancellingJobId = state.cancellingJobId,
                                onCancel = viewModel::cancelJob,
                            )
                        }
                    }

                    state.upstreamErrorBanner?.let { msg ->
                        item(key = "upstream_err") {
                            PcErrorBanner(
                                title = msg,
                                tone = PcTone.Warn,
                            )
                        }
                    }

                    item(key = "tabs") {
                        PcSegmentedControl(
                            options = tabOptions,
                            selected = state.tab,
                            onSelect = viewModel::selectTab,
                        )
                    }

                    item(key = "search_and_filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpaceSearchField(
                                query = state.query,
                                placeholder = when (state.tab) {
                                    SpaceTab.MOVIES -> "Search local movies"
                                    SpaceTab.TV_SHOWS -> "Search TV seasons"
                                    SpaceTab.OFFLOADED -> "Search offloaded media"
                                },
                                onQueryChange = viewModel::updateQuery,
                            )
                            FilterAndSortBar(
                                tab = state.tab,
                                localStatusChip = state.localStatusChip,
                                driveKindFilter = state.driveKindFilter,
                                sort = state.sort,
                                onSelectLocalChip = viewModel::selectLocalStatusChip,
                                onSelectDriveFilter = viewModel::selectDriveKindFilter,
                                onSelectSort = viewModel::selectSort,
                            )
                        }
                    }

                    val visSel = state.visibleSelection
                    if (visSel.visibleCount > 0) {
                        item(key = "select_bar") {
                            SelectAllRow(
                                selection = visSel,
                                visibleTotalBytes = state.visibleSelectableTotalBytes,
                                onToggleSelectAll = viewModel::toggleSelectAllVisible,
                            )
                        }
                    }

                    if (state.tab == SpaceTab.OFFLOADED) {
                        val drive = state.driveData
                        when {
                            drive == null -> {
                                item(key = "drive_skel") { MediaListSkeleton() }
                            }
                            !drive.configured -> {
                                item(key = "drive_unconfigured") {
                                    PcCard {
                                        PcEmptyState(
                                            icon = Icons.Filled.CloudOff,
                                            title = "Drive not configured",
                                            body = "Add a 'gdrive' remote in rclone on your PC to offload and stream media.",
                                        )
                                    }
                                }
                            }
                            state.visibleDrive.isEmpty() -> {
                                item(key = "drive_empty") {
                                    PcCard {
                                        PcEmptyState(
                                            icon = Icons.Filled.CloudDone,
                                            title = if (state.query.isNotBlank()) {
                                                "No matching offloaded items"
                                            } else {
                                                when (state.driveKindFilter) {
                                                    SpaceFilter.MOVIE -> "No offloaded movies"
                                                    SpaceFilter.SEASON -> "No offloaded TV seasons"
                                                    SpaceFilter.ALL -> "Nothing on Drive yet"
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            else -> {
                                item(key = "drive_list") {
                                    val items = state.visibleDrive
                                    val selectedPaths = state.effectiveSelectedDrivePaths
                                    val activeMap = state.activeDriveJobs
                                    PcListCard {
                                        items.forEachIndexed { index, item ->
                                            DriveItemRow(
                                                item = item,
                                                selected = item.relPath in selectedPaths,
                                                activeJob = activeMap[item.relPath],
                                                showDivider = index < items.lastIndex,
                                                onToggle = { viewModel.toggleDriveItem(item.relPath) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        when {
                            state.localData == null -> {
                                item(key = "local_skel") { MediaListSkeleton() }
                            }
                            state.visibleLocal.isEmpty() -> {
                                item(key = "local_empty") {
                                    PcCard {
                                        PcEmptyState(
                                            icon = Icons.Filled.Storage,
                                            title = if (state.query.isNotBlank()) {
                                                "No matching media"
                                            } else if (state.tab == SpaceTab.MOVIES) {
                                                when (state.localStatusChip) {
                                                    LocalStatusChip.WATCHED -> "No watched movies"
                                                    LocalStatusChip.UNWATCHED -> "No unwatched movies"
                                                    LocalStatusChip.ALL -> "No local movies found"
                                                }
                                            } else {
                                                when (state.localStatusChip) {
                                                    LocalStatusChip.WATCHED -> "No watched TV seasons"
                                                    LocalStatusChip.UNWATCHED -> "No unwatched TV seasons"
                                                    LocalStatusChip.ALL -> "No local TV seasons found"
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            else -> {
                                item(key = "local_list") {
                                    val items = state.visibleLocal
                                    val selectedIds = state.effectiveSelectedLocalIds
                                    val activeMap = state.activeLocalJobs
                                    PcListCard {
                                        items.forEachIndexed { index, item ->
                                            CandidateRow(
                                                item = item,
                                                selected = item.id in selectedIds,
                                                activeJob = activeMap[item.id],
                                                showDivider = index < items.lastIndex,
                                                onToggle = { viewModel.toggleLocalItem(item.id) },
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

        // Sticky multi-select action bar
        AnimatedVisibility(
            visible = state.hasSelection,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionActionBar(
                isDriveMode = state.tab == SpaceTab.OFFLOADED,
                count = if (state.tab == SpaceTab.OFFLOADED) state.driveSelection.count else state.localSelection.count,
                totalBytes = if (state.tab == SpaceTab.OFFLOADED) state.driveSelection.totalBytes else state.localSelection.totalBytes,
                offloadDisabled = state.driveExplicitlyUnconfigured,
                onClear = viewModel::clearSelection,
                onPrimaryAction = {
                    viewModel.requestConfirm(
                        if (state.tab == SpaceTab.OFFLOADED) SpaceConfirmAction.RESTORE else SpaceConfirmAction.OFFLOAD,
                    )
                },
                onDeleteAction = {
                    viewModel.requestConfirm(
                        if (state.tab == SpaceTab.OFFLOADED) SpaceConfirmAction.DELETE_DRIVE else SpaceConfirmAction.DELETE_LOCAL,
                    )
                },
            )
        }
    }

    // Confirmation ModalBottomSheet
    val confirmAction = state.confirmAction
    if (confirmAction != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissConfirm,
            sheetState = sheetState,
            containerColor = colors.surface,
            contentColor = colors.text,
            dragHandle = null,
        ) {
            ConfirmActionSheetContent(
                action = confirmAction,
                localSelection = state.localSelection,
                driveSelection = state.driveSelection,
                isBusy = state.isBusyAction,
                onDismiss = viewModel::dismissConfirm,
                onConfirm = viewModel::confirmPendingAction,
            )
        }
    }
}

@Composable
private fun HddSummaryCard(hdd: SpaceHdd) {
    val colors = LocalPcColors.current
    if (!hdd.mounted) {
        PcErrorBanner(
            title = "HDD not mounted",
            message = hdd.mount.takeIf { it.isNotBlank() },
            tone = PcTone.Warn,
            icon = Icons.Filled.Storage,
        )
        return
    }

    val total = hdd.totalBytes ?: 0L
    val used = hdd.usedBytes ?: 0L
    val free = hdd.freeBytes ?: 0L
    val predicted = hdd.predictedFreeBytes ?: free
    val usedFraction = if (total > 0L) (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    val pendingFraction = if (total > 0L && hdd.pendingBytes > 0L) {
        (hdd.pendingBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f - usedFraction)
    } else {
        0f
    }

    val lowNow = free < hdd.thresholdBytes
    val lowAfter = predicted < hdd.thresholdBytes
    val usedBarColor = when {
        predicted < 0L -> colors.bad
        lowNow || lowAfter -> colors.warn
        else -> colors.accent
    }
    val freeTextColor = if (lowNow) colors.warn else colors.text
    val hasPending = hdd.pendingCount > 0 || hdd.pendingBytes > 0L

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${formatBytes(free)} free",
                    color = freeTextColor,
                    style = TabularNumStyle.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatBytes(used)} / ${formatBytes(total)}",
                    color = colors.textDim,
                    style = TabularNumStyle.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
            }

            // Segmented horizontal storage bar: used + pending downloads + free track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceRaised),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (usedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(usedFraction)
                                .background(usedBarColor),
                        )
                    }
                    if (pendingFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(pendingFraction)
                                .background(colors.warn),
                        )
                    }
                    val remaining = (1f - usedFraction - pendingFraction).coerceAtLeast(0f)
                    if (remaining > 0f) {
                        Spacer(modifier = Modifier.weight(remaining))
                    }
                }
            }

            if (hasPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dlSuffix = if (hdd.pendingCount == 1) "" else "s"
                    val unknownSuffix = if (hdd.pendingUnknown > 0) " (+${hdd.pendingUnknown} unknown)" else ""
                    Text(
                        text = "After ${hdd.pendingCount} active download$dlSuffix$unknownSuffix",
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (predicted >= 0L) {
                            "${formatBytes(predicted)} free"
                        } else {
                            "${formatBytes(abs(predicted))} short"
                        },
                        color = when {
                            predicted < 0L -> colors.bad
                            lowAfter -> colors.warn
                            else -> colors.accent
                        },
                        style = TabularNumStyle.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveStatusCard(drive: DriveStatusResponse) {
    val colors = LocalPcColors.current
    val quota = drive.quota
    val total = quota?.totalBytes ?: 0L
    val used = quota?.usedBytes ?: 0L
    val free = quota?.freeBytes ?: if (total > 0L) (total - used).coerceAtLeast(0L) else 0L
    val progress = if (total > 0L) (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = colors.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Google Drive",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    !drive.configured -> PcPill(label = "Not configured", tone = PcTone.Dim)
                    drive.mounted -> PcPill(label = "Playable in Jellyfin", tone = PcTone.Ok, showDot = true)
                    else -> PcPill(label = "Mount offline", tone = PcTone.Warn, showDot = true)
                }
            }

            if (!drive.configured) {
                Text(
                    text = "Configure the 'gdrive' remote in rclone and enable rclone-gdrive.service to offload media.",
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            } else if (total > 0L) {
                PcProgressBar(
                    progress = progress,
                    color = if (progress > 0.90f) colors.warn else colors.accent,
                    height = 5.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatBytes(used)} / ${formatBytes(total)}",
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                    )
                    Text(
                        text = "${formatBytes(free)} free · ${drive.items.size} item${if (drive.items.size == 1) "" else "s"}",
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = "${drive.items.size} offloaded item${if (drive.items.size == 1) "" else "s"}",
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
                )
            }
        }
    }
}

@Composable
private fun ActiveTransfersCard(
    jobs: List<TransferJob>,
    cancellingJobId: String?,
    onCancel: (String) -> Unit,
) {
    val colors = LocalPcColors.current
    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Active transfers",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                PcPill(
                    label = "${jobs.size} active",
                    tone = PcTone.Accent,
                    showDot = true,
                )
            }

            jobs.forEachIndexed { idx, job ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.hairline, thickness = 1.dp)
                }
                val isCopying = job.status == "copying"
                val isVerifying = job.status == "verifying"
                val isCleaning = job.status == "cleaning"
                val tone = when {
                    isVerifying -> PcTone.Warn
                    job.status == "queued" -> PcTone.Dim
                    else -> PcTone.Accent
                }
                val metaParts = buildList {
                    when {
                        isCopying -> {
                            add("${job.pct.toInt()}%")
                            if (job.speedBps > 0L) add(formatRate(job.speedBps))
                            val eta = job.etaS
                            if (eta != null && eta > 0L) add("${formatDuration(eta)} left")
                        }
                        isVerifying -> add("Checking MD5 hashes")
                        isCleaning -> add(if (job.isOffload) "Removing local copy" else "Cleaning remote copy")
                        else -> add(formatBytes(job.sizeBytes))
                    }
                }
                val progress = when {
                    job.status == "queued" -> 0f
                    isVerifying || isCleaning -> 1f
                    else -> (job.pct / 100.0).toFloat().coerceIn(0f, 1f)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(job.title)
                                    if (!job.subtitle.isNullOrBlank()) {
                                        append(" · ")
                                        append(job.subtitle)
                                    }
                                },
                                color = colors.text,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = metaParts.joinToString(" · "),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        PcPill(label = transferStatusLabel(job), tone = tone)
                    }

                    PcProgressBar(
                        progress = progress,
                        color = if (isVerifying) colors.warn else colors.accent,
                        height = 5.dp,
                    )

                    if (job.canCancel) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            PcButton(
                                label = "Cancel",
                                variant = PcButtonVariant.Subtle,
                                compact = true,
                                busy = cancellingJobId == job.id,
                                onClick = { onCancel(job.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceSearchField(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.textFaint,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TabularNumStyle.copy(
                    color = colors.text,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = colors.textDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterAndSortBar(
    tab: SpaceTab,
    localStatusChip: LocalStatusChip,
    driveKindFilter: SpaceFilter,
    sort: SpaceSort,
    onSelectLocalChip: (LocalStatusChip) -> Unit,
    onSelectDriveFilter: (SpaceFilter) -> Unit,
    onSelectSort: (SpaceSort) -> Unit,
) {
    val colors = LocalPcColors.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (tab == SpaceTab.OFFLOADED) {
                SpaceFilter.entries.forEach { filter ->
                    FilterChipButton(
                        label = filter.label,
                        selected = driveKindFilter == filter,
                        onClick = { onSelectDriveFilter(filter) },
                    )
                }
            } else {
                LocalStatusChip.entries.forEach { chip ->
                    FilterChipButton(
                        label = chip.label,
                        selected = localStatusChip == chip,
                        onClick = { onSelectLocalChip(chip) },
                    )
                }
            }
        }

        Box {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surface)
                    .clickable { sortMenuExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Sort,
                    contentDescription = "Sort",
                    tint = colors.textDim,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (tab == SpaceTab.OFFLOADED && sort == SpaceSort.WATCHED) "Recent" else sort.label,
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
                containerColor = colors.surfaceRaised,
            ) {
                SpaceSort.entries.forEach { option ->
                    val label = if (tab == SpaceTab.OFFLOADED && option == SpaceSort.WATCHED) {
                        "Recent"
                    } else {
                        option.label
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (sort == option) colors.accent else colors.text,
                                fontWeight = if (sort == option) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                            )
                        },
                        trailingIcon = if (sort == option) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            sortMenuExpanded = false
                            onSelectSort(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.textDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SelectAllRow(
    selection: VisibleSelectionState,
    visibleTotalBytes: Long,
    onToggleSelectAll: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleSelectAll)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when {
                    selection.allSelected -> Icons.Filled.CheckBox
                    selection.someSelected -> Icons.Filled.IndeterminateCheckBox
                    else -> Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = null,
                tint = if (selection.allSelected || selection.someSelected) colors.accent else colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (selection.allSelected) "Deselect all" else "Select all",
                color = if (selection.allSelected) colors.accent else colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            text = if (selection.selectedVisibleCount > 0) {
                "${selection.selectedVisibleCount} of ${selection.visibleCount} selected"
            } else {
                "${selection.visibleCount} item${if (selection.visibleCount == 1) "" else "s"} · ${formatBytes(visibleTotalBytes)}"
            },
            color = colors.textDim,
            style = TabularNumStyle.copy(fontSize = 12.5.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CandidateRow(
    item: SpaceCandidate,
    selected: Boolean,
    activeJob: TransferJob?,
    showDivider: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalPcColors.current
    val locked = activeJob != null
    val subParts = buildList {
        if (!item.subtitle.isNullOrBlank()) add(item.subtitle)
        else if (item.year != null) add(item.year.toString())
        if (item.isSeason && item.episodeCount != null && item.episodeCount > 0) {
            add("${item.episodeCount} ep")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (!locked && selected) colors.accentSoft else colors.surface)
                .clickable(enabled = !locked, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (!locked && selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (!locked && selected) colors.accent else colors.textFaint,
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (locked) 0.32f else 1f),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    color = if (locked) colors.textDim else colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (subParts.isNotEmpty()) {
                        Text(
                            text = subParts.joinToString(" · "),
                            color = colors.textDim,
                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (activeJob != null) {
                        InFlightBadge(job = activeJob)
                    } else if (!item.isWatched) {
                        PcPill(label = "Unwatched", tone = PcTone.Dim)
                    } else if (item.warnUnwatched && item.unwatchedUsers.isNotEmpty()) {
                        PcPill(
                            label = item.unwatchedUsers.joinToString(", "),
                            tone = PcTone.Warn,
                            icon = Icons.Filled.Warning,
                        )
                    }
                }
            }

            Text(
                text = formatBytes(item.sizeBytes),
                color = if (locked) colors.textDim else colors.text,
                style = TabularNumStyle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = colors.hairline,
                thickness = 1.dp,
                modifier = Modifier.padding(start = 50.dp),
            )
        }
    }
}

@Composable
private fun DriveItemRow(
    item: DriveItem,
    selected: Boolean,
    activeJob: TransferJob?,
    showDivider: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalPcColors.current
    val locked = activeJob != null
    val subParts = buildList {
        if (!item.subtitle.isNullOrBlank()) add(item.subtitle)
        else if (item.year != null) add(item.year.toString())
        if (item.isSeason && item.episodeCount != null && item.episodeCount > 0) {
            add("${item.episodeCount} ep")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (!locked && selected) colors.accentSoft else colors.surface)
                .clickable(enabled = !locked, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (!locked && selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (!locked && selected) colors.accent else colors.textFaint,
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (locked) 0.32f else 1f),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    color = if (locked) colors.textDim else colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (subParts.isNotEmpty() || activeJob != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (subParts.isNotEmpty()) {
                            Text(
                                text = subParts.joinToString(" · "),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (activeJob != null) {
                            InFlightBadge(job = activeJob)
                        }
                    }
                }
            }

            Text(
                text = formatBytes(item.sizeBytes),
                color = if (locked) colors.textDim else colors.text,
                style = TabularNumStyle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = colors.hairline,
                thickness = 1.dp,
                modifier = Modifier.padding(start = 50.dp),
            )
        }
    }
}

@Composable
private fun InFlightBadge(job: TransferJob) {
    val tone = if (job.status == "queued") PcTone.Dim else PcTone.Accent
    PcPill(
        label = inFlightBadgeLabel(job),
        tone = tone,
        showDot = true,
    )
}

@Composable
private fun SelectionActionBar(
    isDriveMode: Boolean,
    count: Int,
    totalBytes: Long,
    offloadDisabled: Boolean,
    onClear: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDeleteAction: () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = colors.hairline, thickness = 1.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$count selected · ${formatBytes(totalBytes)}",
                    color = colors.text,
                    style = TabularNumStyle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "Clear",
                    color = colors.textDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcButton(
                    label = if (isDriveMode) "Restore to HDD" else "Offload to Drive",
                    icon = if (isDriveMode) Icons.Filled.CloudDownload else Icons.Filled.CloudUpload,
                    variant = PcButtonVariant.Primary,
                    enabled = isDriveMode || !offloadDisabled,
                    onClick = onPrimaryAction,
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Delete",
                    icon = Icons.Filled.Delete,
                    variant = PcButtonVariant.Danger,
                    onClick = onDeleteAction,
                )
            }
        }
    }
}

@Composable
private fun ConfirmActionSheetContent(
    action: SpaceConfirmAction,
    localSelection: LocalSelectionSummary,
    driveSelection: DriveSelectionSummary,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalPcColors.current
    val isLocal = action == SpaceConfirmAction.DELETE_LOCAL || action == SpaceConfirmAction.OFFLOAD
    val count = if (isLocal) localSelection.count else driveSelection.count
    val totalBytes = if (isLocal) localSelection.totalBytes else driveSelection.totalBytes

    val title = when (action) {
        SpaceConfirmAction.OFFLOAD -> "Offload ${formatBytes(totalBytes)}?"
        SpaceConfirmAction.DELETE_LOCAL -> "Delete ${formatBytes(totalBytes)}?"
        SpaceConfirmAction.RESTORE -> "Restore ${formatBytes(totalBytes)}?"
        SpaceConfirmAction.DELETE_DRIVE -> "Delete ${formatBytes(totalBytes)} from Drive?"
    }
    val description = when (action) {
        SpaceConfirmAction.OFFLOAD ->
            "Uploads to Google Drive, verifies MD5 hashes, then removes the local HDD copy. Items stay playable in Jellyfin via the VFS mount."
        SpaceConfirmAction.DELETE_LOCAL ->
            "Permanently removes local files and cleans up torrent copies. Frees ${formatBytes(totalBytes)} on your HDD."
        SpaceConfirmAction.RESTORE ->
            "Copies back to the local HDD, verifies MD5 hashes, then removes the Google Drive copy."
        SpaceConfirmAction.DELETE_DRIVE ->
            "Permanently removes these items from Google Drive. They will no longer play in Jellyfin."
    }
    val confirmLabel = when (action) {
        SpaceConfirmAction.OFFLOAD -> "Offload"
        SpaceConfirmAction.DELETE_LOCAL -> "Delete"
        SpaceConfirmAction.RESTORE -> "Restore"
        SpaceConfirmAction.DELETE_DRIVE -> "Delete from Drive"
    }
    val confirmIcon = when (action) {
        SpaceConfirmAction.OFFLOAD -> Icons.Filled.CloudUpload
        SpaceConfirmAction.RESTORE -> Icons.Filled.CloudDownload
        SpaceConfirmAction.DELETE_LOCAL, SpaceConfirmAction.DELETE_DRIVE -> Icons.Filled.Delete
    }
    val confirmVariant = when (action) {
        SpaceConfirmAction.OFFLOAD, SpaceConfirmAction.RESTORE -> PcButtonVariant.Primary
        SpaceConfirmAction.DELETE_LOCAL, SpaceConfirmAction.DELETE_DRIVE -> PcButtonVariant.Danger
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = colors.text,
                style = TabularNumStyle.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            PcPill(
                label = "$count item${if (count == 1) "" else "s"}",
                tone = PcTone.Dim,
            )
        }

        Text(
            text = description,
            color = colors.textDim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )

        if (action == SpaceConfirmAction.DELETE_LOCAL && localSelection.unwatched.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.warnSoft)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.warn,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Not watched by others",
                        color = colors.warn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                localSelection.unwatched.forEach { item ->
                    val sub = if (!item.subtitle.isNullOrBlank()) " · ${item.subtitle}" else ""
                    Text(
                        text = "• ${item.title}$sub (${item.unwatchedUsers.joinToString(", ")})",
                        color = colors.text,
                        fontSize = 12.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLocal) {
                localSelection.selected.forEach { item ->
                    ConfirmItemRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        sizeBytes = item.sizeBytes,
                    )
                }
            } else {
                driveSelection.selected.forEach { item ->
                    ConfirmItemRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        sizeBytes = item.sizeBytes,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PcButton(
                label = "Cancel",
                variant = PcButtonVariant.Subtle,
                enabled = !isBusy,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            PcButton(
                label = confirmLabel,
                icon = confirmIcon,
                variant = confirmVariant,
                busy = isBusy,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConfirmItemRow(
    title: String,
    subtitle: String?,
    sizeBytes: Long,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (!subtitle.isNullOrBlank()) "$title · $subtitle" else title,
            color = colors.textDim,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatBytes(sizeBytes),
            color = colors.text,
            style = TabularNumStyle.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun FreeFeedbackCard(
    feedback: SpaceFreeResponse,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val failedCount = feedback.results.count { !it.ok }
    val hasWarnings = feedback.warnings.isNotEmpty() || feedback.results.any { it.warnings.isNotEmpty() }
    val title = when {
        failedCount == 0 -> "Freed ${formatBytes(feedback.freedBytes)}"
        failedCount == feedback.results.size -> "Could not free selected items"
        else -> "Freed ${formatBytes(feedback.freedBytes)} · $failedCount failed"
    }
    val accentColor = when {
        failedCount == feedback.results.size && feedback.results.isNotEmpty() -> colors.bad
        failedCount > 0 || hasWarnings -> colors.warn
        else -> colors.ok
    }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                color = accentColor,
                style = TabularNumStyle.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            feedback.results.forEach { res ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (res.ok) Icons.Filled.Check else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (res.ok) colors.ok else colors.bad,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = res.label.ifBlank { res.id },
                            color = colors.text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (res.ok) {
                            Text(
                                text = formatBytes(res.freedBytes),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.5.sp),
                            )
                        }
                    }
                    if (!res.error.isNullOrBlank()) {
                        Text(
                            text = res.error,
                            color = colors.bad,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                    }
                    res.warnings.forEach { w ->
                        Text(
                            text = w,
                            color = colors.warn,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                    }
                }
            }
            feedback.warnings.forEach { w ->
                Text(text = w, color = colors.warn, fontSize = 12.sp)
            }
            PcButton(
                label = "Dismiss",
                variant = PcButtonVariant.Subtle,
                compact = true,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun DriveDeleteFeedbackCard(
    feedback: DriveDeleteResponse,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val title = if (feedback.ok) {
        "Deleted ${feedback.results.size} item${if (feedback.results.size == 1) "" else "s"} from Drive"
    } else {
        "Some Drive items could not be deleted"
    }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                color = if (feedback.ok) colors.ok else colors.warn,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            feedback.results.filter { !it.ok }.forEach { res ->
                Text(
                    text = "${res.relPath}: ${res.error ?: "Failed"}",
                    color = colors.bad,
                    fontSize = 12.sp,
                )
            }
            PcButton(
                label = "Dismiss",
                variant = PcButtonVariant.Subtle,
                compact = true,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun HddSummarySkeleton() {
    val colors = LocalPcColors.current
    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceRaised),
                )
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceRaised),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceRaised),
            )
        }
    }
}

@Composable
private fun MediaListSkeleton() {
    val colors = LocalPcColors.current
    PcListCard {
        repeat(5) { idx ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.surfaceRaised),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f + (idx % 3) * 0.1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceRaised),
                    )
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceRaised),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceRaised),
                )
            }
            if (idx < 4) {
                HorizontalDivider(
                    color = colors.hairline,
                    thickness = 1.dp,
                    modifier = Modifier.padding(start = 50.dp),
                )
            }
        }
    }
}
