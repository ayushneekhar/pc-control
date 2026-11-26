package com.neekhar.pccontrol.feature.space

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcBottomSheet
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

private sealed interface FocusedSpaceItem {
    data class Local(val item: SpaceCandidate) : FocusedSpaceItem
    data class Drive(val item: DriveItem) : FocusedSpaceItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    viewModel: SpaceViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    val view = LocalView.current

    var searchOpen by remember { mutableStateOf(false) }
    var focusedItem by remember { mutableStateOf<FocusedSpaceItem?>(null) }

    DisposableEffect(viewModel) {
        viewModel.startStream()
        onDispose { viewModel.stopStream() }
    }

    BackHandler(enabled = state.hasSelection) {
        viewModel.clearSelection()
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
    val showSearchField = searchOpen || state.query.isNotEmpty()
    val selectionMode = state.hasSelection

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
                actions = {
                    IconButton(
                        onClick = {
                            if (showSearchField) {
                                viewModel.updateQuery("")
                                searchOpen = false
                            } else {
                                searchOpen = true
                            }
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = if (showSearchField) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (showSearchField) "Close search" else "Search media",
                            tint = if (showSearchField) colors.text else colors.textDim,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                },
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
                        top = 2.dp,
                        bottom = (if (selectionMode) 144.dp else 28.dp) + navBottom,
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

                    // ONE unified Disk + Google Drive storage summary card
                    item(key = "storage_summary") {
                        if (hdd != null) {
                            UnifiedStorageCard(
                                hdd = hdd,
                                drive = state.driveData,
                            )
                        } else {
                            StorageSummarySkeleton()
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

                    // Collapsible search field (hidden by default so media starts higher)
                    item(key = "search_bar") {
                        AnimatedVisibility(
                            visible = showSearchField,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            SpaceSearchField(
                                query = state.query,
                                placeholder = when (state.tab) {
                                    SpaceTab.MOVIES -> "Search local movies"
                                    SpaceTab.TV_SHOWS -> "Search TV seasons"
                                    SpaceTab.OFFLOADED -> "Search offloaded media"
                                },
                                onQueryChange = viewModel::updateQuery,
                            )
                        }
                    }

                    // Compact tabs + filter/sort controls (2 tight rows instead of 4 stacked rows)
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PcSegmentedControl(
                                options = tabOptions,
                                selected = state.tab,
                                onSelect = viewModel::selectTab,
                            )
                            FilterAndSortBar(
                                tab = state.tab,
                                localStatusChip = state.localStatusChip,
                                driveKindFilter = state.driveKindFilter,
                                sort = state.sort,
                                visibleCount = if (state.tab == SpaceTab.OFFLOADED) {
                                    state.visibleDrive.size
                                } else {
                                    state.visibleLocal.size
                                },
                                onSelectLocalChip = viewModel::selectLocalStatusChip,
                                onSelectDriveFilter = viewModel::selectDriveKindFilter,
                                onSelectSort = viewModel::selectSort,
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
                                                selectionMode = selectionMode,
                                                selected = item.relPath in selectedPaths,
                                                activeJob = activeMap[item.relPath],
                                                showDivider = index < items.lastIndex,
                                                onClick = {
                                                    if (selectionMode) {
                                                        viewModel.toggleDriveItem(item.relPath)
                                                    } else {
                                                        focusedItem = FocusedSpaceItem.Drive(item)
                                                    }
                                                },
                                                onLongClick = {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    viewModel.toggleDriveItem(item.relPath)
                                                },
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
                                                selectionMode = selectionMode,
                                                selected = item.id in selectedIds,
                                                activeJob = activeMap[item.id],
                                                showDivider = index < items.lastIndex,
                                                onClick = {
                                                    if (selectionMode) {
                                                        viewModel.toggleLocalItem(item.id)
                                                    } else {
                                                        focusedItem = FocusedSpaceItem.Local(item)
                                                    }
                                                },
                                                onLongClick = {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    viewModel.toggleLocalItem(item.id)
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

        // Sleek floating contextual action bar shown ONLY when items are selected
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionActionBar(
                isDriveMode = state.tab == SpaceTab.OFFLOADED,
                count = if (state.tab == SpaceTab.OFFLOADED) state.driveSelection.count else state.localSelection.count,
                totalBytes = if (state.tab == SpaceTab.OFFLOADED) state.driveSelection.totalBytes else state.localSelection.totalBytes,
                allVisibleSelected = state.visibleSelection.allSelected,
                offloadDisabled = state.driveExplicitlyUnconfigured,
                onClear = viewModel::clearSelection,
                onToggleSelectAll = viewModel::toggleSelectAllVisible,
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

    // Single-item quick action sheet (on short tap outside selection mode)
    val activeFocus = focusedItem
    if (activeFocus != null) {
        PcBottomSheet(
            onDismissRequest = { focusedItem = null },
            contentPadding = PaddingValues(0.dp),
        ) {
            SingleItemActionSheetContent(
                focused = activeFocus,
                offloadDisabled = state.driveExplicitlyUnconfigured,
                onSelectMultiple = {
                    focusedItem = null
                    when (activeFocus) {
                        is FocusedSpaceItem.Local -> viewModel.toggleLocalItem(activeFocus.item.id)
                        is FocusedSpaceItem.Drive -> viewModel.toggleDriveItem(activeFocus.item.relPath)
                    }
                },
                onPrimaryAction = {
                    focusedItem = null
                    when (activeFocus) {
                        is FocusedSpaceItem.Local ->
                            viewModel.selectOnlyLocalAndConfirm(activeFocus.item.id, SpaceConfirmAction.OFFLOAD)
                        is FocusedSpaceItem.Drive ->
                            viewModel.selectOnlyDriveAndConfirm(activeFocus.item.relPath, SpaceConfirmAction.RESTORE)
                    }
                },
                onDeleteAction = {
                    focusedItem = null
                    when (activeFocus) {
                        is FocusedSpaceItem.Local ->
                            viewModel.selectOnlyLocalAndConfirm(activeFocus.item.id, SpaceConfirmAction.DELETE_LOCAL)
                        is FocusedSpaceItem.Drive ->
                            viewModel.selectOnlyDriveAndConfirm(activeFocus.item.relPath, SpaceConfirmAction.DELETE_DRIVE)
                    }
                },
            )
        }
    }

    // Confirmation bottom sheet
    val confirmAction = state.confirmAction
    if (confirmAction != null) {
        PcBottomSheet(
            onDismissRequest = viewModel::dismissConfirm,
            contentPadding = PaddingValues(0.dp),
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

/**
 * Single unified storage card combining HDD free/used bar and a compact Google Drive summary line.
 */
@Composable
private fun UnifiedStorageCard(
    hdd: SpaceHdd,
    drive: DriveStatusResponse?,
) {
    val colors = LocalPcColors.current
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

    val lowNow = hdd.mounted && free < hdd.thresholdBytes
    val lowAfter = hdd.mounted && predicted < hdd.thresholdBytes
    val usedBarColor = when {
        predicted < 0L -> colors.bad
        lowNow || lowAfter -> colors.warn
        else -> colors.text
    }
    val hasPending = hdd.mounted && (hdd.pendingCount > 0 || hdd.pendingBytes > 0L)

    PcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!hdd.mounted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = colors.warn,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "HDD not mounted",
                            color = colors.warn,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (hdd.mount.isNotBlank()) {
                            Text(
                                text = hdd.mount,
                                color = colors.textDim,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "${formatBytes(free)} free",
                        color = if (lowNow) colors.warn else colors.text,
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
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                    )
                }

                // Segmented horizontal HDD bar: used + pending downloads + free track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
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
                        val unknownSuffix = if (hdd.pendingUnknown > 0) " (+${hdd.pendingUnknown})" else ""
                        Text(
                            text = "After ${hdd.pendingCount} download$dlSuffix$unknownSuffix",
                            color = colors.textDim,
                            style = TabularNumStyle.copy(fontSize = 12.sp),
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
                                else -> colors.textDim
                            },
                            style = TabularNumStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }

            // Compact Google Drive offloaded summary line inside the same card
            if (drive != null) {
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                val quota = drive.quota
                val dTotal = quota?.totalBytes ?: 0L
                val dUsed = quota?.usedBytes ?: 0L
                val dFree = quota?.freeBytes ?: if (dTotal > 0L) (dTotal - dUsed).coerceAtLeast(0L) else 0L
                val countLabel = "${drive.items.size} offloaded"
                val driveLeftText = when {
                    !drive.configured -> "Drive · Not configured"
                    !drive.mounted -> "Drive · Mount offline · $countLabel"
                    else -> "Drive · $countLabel"
                }
                val driveRightText = if (drive.configured && dTotal > 0L) {
                    "${formatBytes(dFree)} free"
                } else {
                    null
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (drive.configured && drive.mounted) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (drive.configured && !drive.mounted) colors.warn else colors.textFaint,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = driveLeftText,
                        color = if (drive.configured && !drive.mounted) colors.warn else colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (driveRightText != null) {
                        Text(
                            text = driveRightText,
                            color = colors.textFaint,
                            style = TabularNumStyle.copy(fontSize = 12.5.sp),
                            maxLines = 1,
                        )
                    }
                }
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
                        tint = colors.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Active transfers",
                        color = colors.text,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                PcPill(
                    label = "${jobs.size} active",
                    tone = PcTone.Dim,
                    showDot = true,
                )
            }

            jobs.forEachIndexed { idx, job ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
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
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = metaParts.joinToString(" · "),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.sp),
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
                        height = 4.dp,
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
            .height(40.dp)
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
            modifier = Modifier.size(17.dp),
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
    visibleCount: Int,
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visibleCount > 0) {
                Text(
                    text = "$visibleCount",
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
                )
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surface)
                        .clickable { sortMenuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sort,
                        contentDescription = "Sort",
                        tint = colors.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (tab == SpaceTab.OFFLOADED && sort == SpaceSort.WATCHED) "Recent" else sort.label,
                        color = colors.textDim,
                        fontSize = 12.5.sp,
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
                                    color = if (sort == option) colors.text else colors.textDim,
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
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.text else colors.textFaint,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun SpacePosterThumbnail(
    posterUrl: String?,
    isSeason: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    locked: Boolean,
    width: Dp = 36.dp,
    height: Dp = 52.dp,
) {
    val colors = LocalPcColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(colors.surfaceRaised)
            .alpha(if (locked) 0.45f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isSeason) Icons.Filled.Tv else Icons.Filled.Movie,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(18.dp),
        )
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (!locked && selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.overlay),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = colors.onAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else if (!locked && selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(colors.overlay)
                    .border(1.dp, colors.onMediaDim, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateRow(
    item: SpaceCandidate,
    selectionMode: Boolean,
    selected: Boolean,
    activeJob: TransferJob?,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
                .background(if (!locked && selected) colors.surfaceRaised else colors.surface)
                .combinedClickable(
                    enabled = !locked,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpacePosterThumbnail(
                posterUrl = item.resolvedPosterUrl,
                isSeason = item.isSeason,
                selected = selected,
                selectionMode = selectionMode,
                locked = locked,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
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
                    } else if (item.isWatched) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Watched",
                            tint = colors.textFaint,
                            modifier = Modifier.size(13.dp),
                        )
                        if (item.warnUnwatched && item.unwatchedUsers.isNotEmpty()) {
                            Text(
                                text = "${item.unwatchedUsers.joinToString(", ")} unwatched",
                                color = colors.warn,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Text(
                text = formatBytes(item.sizeBytes),
                color = if (locked) colors.textDim else colors.text,
                style = TabularNumStyle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = colors.hairline,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 64.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveItemRow(
    item: DriveItem,
    selectionMode: Boolean,
    selected: Boolean,
    activeJob: TransferJob?,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
                .background(if (!locked && selected) colors.surfaceRaised else colors.surface)
                .combinedClickable(
                    enabled = !locked,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpacePosterThumbnail(
                posterUrl = item.resolvedPosterUrl,
                isSeason = item.isSeason,
                selected = selected,
                selectionMode = selectionMode,
                locked = locked,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
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
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = colors.hairline,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 64.dp),
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

/**
 * Floating contextual multi-select action bar shown only while items are selected.
 */
@Composable
private fun SelectionActionBar(
    isDriveMode: Boolean,
    count: Int,
    totalBytes: Long,
    allVisibleSelected: Boolean,
    offloadDisabled: Boolean,
    onClear: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDeleteAction: () -> Unit,
) {
    val colors = LocalPcColors.current
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(0.5.dp, colors.hairline, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear selection",
                    tint = colors.textDim,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear),
                )
                Text(
                    text = "$count selected · ${formatBytes(totalBytes)}",
                    color = colors.text,
                    style = TabularNumStyle.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (allVisibleSelected) "Deselect all" else "Select all",
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleSelectAll)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcButton(
                    label = if (isDriveMode) "Restore" else "Offload",
                    icon = if (isDriveMode) Icons.Filled.CloudDownload else Icons.Filled.CloudUpload,
                    variant = PcButtonVariant.Primary,
                    compact = true,
                    enabled = isDriveMode || !offloadDisabled,
                    onClick = onPrimaryAction,
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Delete",
                    icon = Icons.Filled.Delete,
                    variant = PcButtonVariant.Danger,
                    compact = true,
                    onClick = onDeleteAction,
                    modifier = Modifier.weight(0.85f),
                )
            }
        }
    }
}

@Composable
private fun SingleItemActionSheetContent(
    focused: FocusedSpaceItem,
    offloadDisabled: Boolean,
    onSelectMultiple: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDeleteAction: () -> Unit,
) {
    val colors = LocalPcColors.current
    val isDrive = focused is FocusedSpaceItem.Drive
    val title = when (focused) {
        is FocusedSpaceItem.Local -> focused.item.title
        is FocusedSpaceItem.Drive -> focused.item.title
    }
    val subtitle = when (focused) {
        is FocusedSpaceItem.Local -> {
            val local = focused.item
            buildList {
                val sub = local.subtitle
                if (!sub.isNullOrBlank()) add(sub)
                else if (local.year != null) add(local.year.toString())
                if (local.isSeason && local.episodeCount != null && local.episodeCount > 0) {
                    add("${local.episodeCount} ep")
                }
                add(formatBytes(local.sizeBytes))
            }.joinToString(" · ")
        }
        is FocusedSpaceItem.Drive -> {
            val driveItem = focused.item
            buildList {
                val sub = driveItem.subtitle
                if (!sub.isNullOrBlank()) add(sub)
                else if (driveItem.year != null) add(driveItem.year.toString())
                if (driveItem.isSeason && driveItem.episodeCount != null && driveItem.episodeCount > 0) {
                    add("${driveItem.episodeCount} ep")
                }
                add(formatBytes(driveItem.sizeBytes))
            }.joinToString(" · ")
        }
    }
    val posterUrl = when (focused) {
        is FocusedSpaceItem.Local -> focused.item.resolvedPosterUrl
        is FocusedSpaceItem.Drive -> focused.item.resolvedPosterUrl
    }
    val isSeason = when (focused) {
        is FocusedSpaceItem.Local -> focused.item.isSeason
        is FocusedSpaceItem.Drive -> focused.item.isSeason
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpacePosterThumbnail(
                posterUrl = posterUrl,
                isSeason = isSeason,
                selected = false,
                selectionMode = false,
                locked = false,
                width = 42.dp,
                height = 60.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 13.sp),
                )
                if (focused is FocusedSpaceItem.Local) {
                    val watchedInfo = when {
                        focused.item.watchedUsers.isNotEmpty() ->
                            "Watched by ${focused.item.watchedUsers.joinToString(", ")}"
                        focused.item.isWatched -> "Watched"
                        else -> "Not watched yet"
                    }
                    Text(
                        text = watchedInfo,
                        color = colors.textFaint,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PcButton(
                label = if (isDrive) "Restore" else "Offload",
                icon = if (isDrive) Icons.Filled.CloudDownload else Icons.Filled.CloudUpload,
                variant = PcButtonVariant.Primary,
                enabled = isDrive || !offloadDisabled,
                onClick = onPrimaryAction,
                modifier = Modifier.weight(1f),
            )
            PcButton(
                label = "Delete",
                icon = Icons.Filled.Delete,
                variant = PcButtonVariant.Danger,
                onClick = onDeleteAction,
                modifier = Modifier.weight(0.9f),
            )
        }

        PcButton(
            label = "Select multiple",
            icon = Icons.Filled.CheckCircle,
            variant = PcButtonVariant.Subtle,
            onClick = onSelectMultiple,
            modifier = Modifier.fillMaxWidth(),
        )
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
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
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
private fun StorageSummarySkeleton() {
    val colors = LocalPcColors.current
    PcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
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
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceRaised),
            )
            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
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
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 52.dp)
                        .clip(RoundedCornerShape(6.dp))
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
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 64.dp),
                )
            }
        }
    }
}
