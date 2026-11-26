package com.neekhar.pccontrol.feature.root

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    viewModel: RootViewModel,
    initialRequestId: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(initialRequestId) {
        if (!initialRequestId.isNullOrBlank()) {
            viewModel.openDetail(initialRequestId)
        }
    }

    val statusDotColor = when (state.rootdState) {
        RootdState.DOWN -> colors.bad
        RootdState.NOT_INSTALLED -> colors.warn
        else -> null
    }
    val statusLabel = when (state.rootdState) {
        RootdState.NOT_INSTALLED -> "rootd not installed"
        RootdState.DOWN -> "rootd not running"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
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
                verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                item(key = "header") {
                    PcScreenHeader(
                        title = "Root",
                        statusText = statusLabel,
                        statusColor = statusDotColor,
                    )
                }

                if (state.rootdState == RootdState.NOT_INSTALLED) {
                    item(key = "banner_not_installed") {
                        PcCard(
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                bottom = PcDimens.Space4,
                            ),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "rootd isn't installed yet",
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Root approvals need the root daemon on the laptop. Run this once in a terminal:",
                                    color = colors.textDim,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                )
                                Text(
                                    text = "sudo ~/code/pc-control/server/install-rootd.sh",
                                    color = colors.accent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                    }
                }

                if (state.rootdState == RootdState.DOWN) {
                    item(key = "banner_down") {
                        PcErrorBanner(
                            message = "rootd is not running · Check: sudo systemctl status rootd",
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                bottom = PcDimens.Space4,
                            ),
                        )
                    }
                }

                if (state.rootdState == RootdState.OK && !state.enrolled) {
                    item(key = "banner_enroll") {
                        PcCard(
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                bottom = PcDimens.Space4,
                            ),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Fingerprint,
                                        contentDescription = null,
                                        tint = colors.warn,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "This phone can't approve yet",
                                        color = colors.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    text = "Set up root approvals in Settings to enroll this phone's hardware key.",
                                    color = colors.textDim,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                )
                                PcButton(
                                    label = "Open Settings",
                                    variant = PcButtonVariant.Subtle,
                                    onClick = onOpenSettings,
                                )
                            }
                        }
                    }
                }

                item(key = "sec_waiting") {
                    PcSectionLabel(
                        label = "Waiting for you",
                        modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                    )
                }

                if (state.pending.isEmpty()) {
                    item(key = "empty_waiting") {
                        PcCard(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            PcEmptyState(
                                icon = Icons.Filled.VerifiedUser,
                                title = "Nothing waiting",
                                body = "New root approval requests will appear here.",
                            )
                        }
                    }
                } else {
                    items(
                        count = state.pending.size,
                        key = { "pending_${state.pending[it].id}" },
                    ) { idx ->
                        val req = state.pending[idx]
                        PendingRequestCard(
                            req = req,
                            busyKey = state.busyKey,
                            onDecide = { d -> viewModel.decide(req, d) },
                            onOpenDetail = { viewModel.openDetail(req.id) },
                            modifier = Modifier.padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = if (idx > 0) 4.dp else 0.dp,
                            ),
                        )
                    }
                }

                item(key = "sec_history") {
                    PcSectionLabel(
                        label = "History",
                        modifier = Modifier.padding(
                            start = PcDimens.ScreenPad,
                            end = PcDimens.ScreenPad,
                            top = PcDimens.Space4,
                        ),
                    )
                }

                if (state.historyError != null) {
                    item(key = "history_err") {
                        PcErrorBanner(
                            message = state.historyError!!,
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
                        )
                    }
                }

                if (state.history.isEmpty() && state.historyError == null) {
                    item(key = "history_empty") {
                        Text(
                            text = "No commands yet.",
                            color = colors.textFaint,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad + 4.dp),
                        )
                    }
                } else if (state.history.isNotEmpty()) {
                    item(key = "history_list") {
                        PcListCard(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                            state.history.forEachIndexed { idx, item ->
                                if (idx > 0) {
                                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                                }
                                HistoryRow(
                                    req = item,
                                    onClick = { viewModel.openDetail(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.selectedId != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeDetail,
            sheetState = sheetState,
            containerColor = colors.bg,
            contentColor = colors.text,
            dragHandle = { PcSheetDragHandle() },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            RootDetailSheetContent(
                req = state.detailRequest,
                loading = state.detailLoading,
                error = state.detailError,
                busyKey = state.busyKey,
                onDecide = { r, d -> viewModel.decide(r, d) },
                onClose = viewModel::closeDetail,
            )
        }
    }

    val alert = state.alert
    if (alert != null) {
        PcConfirmDialog(
            title = alert.title,
            message = alert.message,
            confirmLabel = if (alert.requiresReenroll) "Open Settings" else "OK",
            dismissLabel = if (alert.requiresReenroll) "Close" else null,
            onConfirm = {
                viewModel.dismissAlert()
                if (alert.requiresReenroll) onOpenSettings()
            },
            onDismiss = viewModel::dismissAlert,
        )
    }
}

@Composable
private fun PendingRequestCard(
    req: RootRequest,
    busyKey: String?,
    onDecide: (Decision) -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val isPending = req.status == "pending"
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(req.id, isPending) {
        while (isPending) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val secsLeft = max(0, ((req.expires * 1000.0 - nowMs) / 1000.0).roundToInt())
    val cmd = remember(req.argv) { RootPayload.commandLine(req.argv) }
    val longCmd = remember(req.argv) { RootPayload.isLongCommand(req.argv) }
    var showArgs by remember(req.id, longCmd) { mutableStateOf(longCmd) }
    var reviewedFull by remember(req.id) { mutableStateOf(false) }

    PcCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (isPending) colors.warn else colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = req.reason.ifBlank { "Root command" },
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isPending) {
                    val timerText = "${secsLeft / 60}:${(secsLeft % 60).toString().padStart(2, '0')}"
                    PcPill(
                        text = timerText,
                        color = if (secsLeft < 30) colors.bad else colors.warn,
                        backgroundColor = if (secsLeft < 30) colors.badSoft else colors.warnSoft,
                    )
                } else {
                    RootStatusPill(req)
                }
            }

            if (longCmd) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.warnSoft)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Long command (${cmd.length} chars) · Review every argument below before approving.",
                        color = colors.warn,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceRaised)
                    .clickable(enabled = !longCmd) { showArgs = !showArgs }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "# $cmd",
                    color = colors.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
                if (!longCmd) {
                    Text(
                        text = "${if (showArgs) "Hide" else "Show"} ${req.argv.size} argument${if (req.argv.size == 1) "" else "s"} exactly",
                        color = colors.accent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (showArgs || longCmd) {
                ArgvList(argv = req.argv)
            }

            if (isPending && longCmd) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .border(
                            width = 1.dp,
                            color = if (reviewedFull) colors.ok else colors.warn,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { reviewedFull = !reviewedFull }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (reviewedFull) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (reviewedFull) colors.ok else colors.warn,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "I reviewed all ${req.argv.size} arguments (${cmd.length} chars)",
                        color = colors.text,
                        fontSize = 13.5.sp,
                    )
                }
            }

            Text(
                text = "${formatClock(req.created)} · uid ${req.uid} · timeout ${formatDuration(req.timeout)} · id ${req.id.take(8)}",
                color = colors.textFaint,
                fontSize = 12.sp,
                style = TabularNumStyle,
            )

            if (isPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RootActionButton(
                        label = "Deny",
                        variant = PcButtonVariant.Subtle,
                        enabled = busyKey == null,
                        busy = busyKey == "${req.id}:deny",
                        onClick = { onDecide(Decision.DENY) },
                        modifier = Modifier.weight(1f),
                    )
                    RootActionButton(
                        label = "Approve",
                        variant = PcButtonVariant.Primary,
                        enabled = busyKey == null && secsLeft > 0 && (!longCmd || reviewedFull),
                        busy = busyKey == "${req.id}:approve",
                        onClick = { onDecide(Decision.APPROVE) },
                        modifier = Modifier.weight(1.4f),
                    )
                }
            } else {
                PcButton(
                    label = "View output",
                    variant = PcButtonVariant.Subtle,
                    onClick = onOpenDetail,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Root approval action button whose disabled state uses clean `surfaceRaised` + `textFaint`
 * instead of alpha-fading the primary accent fill.
 */
@Composable
private fun RootActionButton(
    label: String,
    variant: PcButtonVariant,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val interactive = enabled && !busy
    val (bg, fg) = when {
        !enabled && !busy -> colors.surfaceRaised to colors.textFaint
        variant == PcButtonVariant.Primary -> colors.accent to colors.onAccent
        variant == PcButtonVariant.Danger -> colors.badSoft to colors.bad
        else -> colors.surfaceRaised to colors.text
    }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(PcDimens.RadiusSm))
            .background(bg)
            .clickable(
                enabled = interactive,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = PcDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
        }
        Text(
            text = label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArgvList(argv: List<String>) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        argv.forEachIndexed { idx, arg ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = idx.toString(),
                    color = colors.textFaint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    text = if (arg.isEmpty()) "(empty)" else RootPayload.showArg(arg),
                    color = if (arg.isEmpty()) colors.textFaint else colors.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    req: RootRequest,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = RootPayload.commandLine(req.argv).take(120),
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append(formatClock(req.finished ?: req.created))
                if (req.reason.isNotBlank()) {
                    append(" · ")
                    append(req.reason)
                }
            }
            Text(
                text = meta,
                color = colors.textFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TabularNumStyle,
            )
        }
        RootStatusPill(req)
    }
}

@Composable
private fun RootStatusPill(req: RootRequest) {
    val colors = LocalPcColors.current
    val (label, fg, bg) = when {
        req.status == "done" && req.exit != 0 -> Triple(
            if (req.timedOut == true) "Timed out" else "Exit ${req.exit ?: "?"}",
            colors.bad,
            colors.badSoft,
        )
        req.status == "done" -> Triple("Exit 0", colors.ok, colors.okSoft)
        req.status == "pending" -> Triple("Waiting", colors.warn, colors.warnSoft)
        req.status == "running" -> Triple("Running", colors.accent, colors.accentSoft)
        req.status == "denied" -> Triple("Denied", colors.bad, colors.badSoft)
        req.status == "expired" -> Triple("Expired", colors.textFaint, colors.surfaceRaised)
        req.status == "cancelled" -> Triple("Cancelled", colors.textFaint, colors.surfaceRaised)
        else -> Triple("Lost", colors.bad, colors.badSoft)
    }
    PcPill(text = label, color = fg, backgroundColor = bg)
}

@Composable
private fun RootDetailSheetContent(
    req: RootRequest?,
    loading: Boolean,
    error: String?,
    busyKey: String?,
    onDecide: (RootRequest, Decision) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPcColors.current
    var reviewedFull by remember(req?.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading && req == null) {
            Text(text = "Loading…", color = colors.textDim, fontSize = 14.sp)
        }
        if (error != null && req == null) {
            PcErrorBanner(message = error)
        }
        if (req != null) {
            val longCmd = remember(req.argv) { RootPayload.isLongCommand(req.argv) }
            val cmdLen = remember(req.argv) { RootPayload.commandLine(req.argv).length }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = req.reason.ifBlank { "Root command" },
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RootStatusPill(req)
            }

            ArgvList(argv = req.argv)

            if (req.status == "pending" && longCmd) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .border(
                            width = 1.dp,
                            color = if (reviewedFull) colors.ok else colors.warn,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { reviewedFull = !reviewedFull }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (reviewedFull) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (reviewedFull) colors.ok else colors.warn,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "I reviewed all ${req.argv.size} arguments ($cmdLen chars)",
                        color = colors.text,
                        fontSize = 13.5.sp,
                    )
                }
            }

            if (req.status == "pending") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RootActionButton(
                        label = "Deny",
                        variant = PcButtonVariant.Subtle,
                        enabled = busyKey == null,
                        busy = busyKey == "${req.id}:deny",
                        onClick = { onDecide(req, Decision.DENY) },
                        modifier = Modifier.weight(1f),
                    )
                    RootActionButton(
                        label = "Approve",
                        variant = PcButtonVariant.Primary,
                        enabled = busyKey == null && (!longCmd || reviewedFull),
                        busy = busyKey == "${req.id}:approve",
                        onClick = { onDecide(req, Decision.APPROVE) },
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }

            PcCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailKvRow("Requested", formatClock(req.created))
                    if (req.decided != null) {
                        val label = if (req.status == "denied") "Denied" else "Approved"
                        val dev = req.device?.let { " · $it" } ?: ""
                        DetailKvRow(label, "${formatClock(req.decided)}$dev")
                    }
                    if (req.finished != null && req.started != null) {
                        DetailKvRow("Ran for", formatDuration((req.finished - req.started).roundToInt().coerceAtLeast(1)))
                    }
                    if (req.exit != null) {
                        DetailKvRow("Exit code", req.exit.toString(), mono = true)
                    }
                    if (!req.snapSha256.isNullOrBlank()) {
                        DetailKvRow("Snapshot SHA-256", req.snapSha256, mono = true)
                    }
                    DetailKvRow("Request ID", req.id, mono = true)
                }
            }

            if (!req.stdout.isNullOrEmpty()) {
                OutputBlock(title = "Stdout", text = req.stdout, isError = false)
            }
            if (!req.stderr.isNullOrEmpty()) {
                OutputBlock(title = "Stderr", text = req.stderr, isError = true)
            }
            if (req.status == "done" && req.stdout.isNullOrEmpty() && req.stderr.isNullOrEmpty()) {
                Text(
                    text = if (req.outputExpired == true) "Output expired (kept for 24 h)." else "No output.",
                    color = colors.textFaint,
                    fontSize = 13.sp,
                )
            }
        }

        PcButton(
            label = "Close",
            variant = PcButtonVariant.Subtle,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DetailKvRow(label: String, value: String, mono: Boolean = false) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = colors.textDim, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TabularNumStyle,
        )
    }
}

@Composable
private fun OutputBlock(title: String, text: String, isError: Boolean) {
    val colors = LocalPcColors.current
    val trimmed = remember(text) {
        if (text.length > 60_000) text.take(30_000) + "\n…\n" + text.takeLast(30_000) else text
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = if (isError) colors.bad else colors.textDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceRaised)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = trimmed,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

private fun formatClock(epochSeconds: Double): String {
    val ms = (epochSeconds * 1000.0).toLong()
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(ms))
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 60) return "${seconds}s"
    val mins = seconds / 60
    val rem = seconds % 60
    return if (rem == 0) "${mins}m" else "${mins}m ${rem}s"
}
