package com.neekhar.pccontrol.feature.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcKeyValueRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.home.PcFormatters

@Composable
fun ServiceDetailScreen(
    unit: String,
    api: PcApiClient,
    cache: LocalCache,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServiceDetailViewModel = viewModel(
        key = "service_detail_$unit",
        factory = ServiceDetailViewModel.factory(unit, api, cache),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.loadAll()
        onPauseOrDispose { }
    }

    ServiceDetailScreenContent(
        state = state,
        onBack = onBack,
        onAction = viewModel::runAction,
        onRefreshLogs = viewModel::refreshLogs,
        onLogFilterChange = viewModel::setLogFilter,
        modifier = modifier,
    )
}

@Composable
fun ServiceDetailScreenContent(
    state: ServiceDetailUiState,
    onBack: () -> Unit,
    onAction: (ServiceAction) -> Unit,
    onRefreshLogs: () -> Unit,
    onLogFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    var pendingConfirmAction by remember { mutableStateOf<ServiceAction?>(null) }
    var copiedFeedback by remember { mutableStateOf(false) }

    val svc = state.service
    val statusTone = when {
        svc?.active == "failed" -> PcTone.Bad
        svc?.active == "active" -> PcTone.Ok
        svc?.active in setOf("activating", "deactivating", "reloading") -> PcTone.Warn
        else -> PcTone.Faint
    }

    val statusText = buildString {
        append(svc?.active?.ifBlank { "unknown" } ?: "unknown")
        if (!svc?.enabled.isNullOrBlank()) {
            append(" · ${svc?.enabled}")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        PcScreenHeader(
            title = if (state.unit.isBlank()) "Service" else state.displayName,
            statusText = if (state.unit.isBlank()) null else statusText,
            statusTone = statusTone,
            showStatusDot = state.unit.isNotBlank(),
            onBack = onBack,
        )

        if (state.unit.isBlank()) {
            PcErrorBanner(
                title = "No service selected",
                tone = PcTone.Bad,
                modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = PcDimens.ScreenPad,
                    end = PcDimens.ScreenPad,
                    bottom = PcDimens.Space8,
                ),
                verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
            ) {
                if (state.actionError != null) {
                    item(key = "action_error") {
                        PcErrorBanner(
                            title = "Action failed",
                            message = state.actionError,
                            tone = PcTone.Bad,
                        )
                    }
                }

                if (state.logsError != null) {
                    item(key = "logs_error") {
                        PcErrorBanner(
                            title = "Can't load logs",
                            message = state.logsError,
                            tone = PcTone.Warn,
                        )
                    }
                }

                // Metadata card
                item(key = "metadata") {
                    PcCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = svc?.active?.ifBlank { "unknown" } ?: "unknown",
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val subDesc = svc?.sub?.takeIf { it.isNotBlank() }
                                    ?: svc?.description?.takeIf { it.isNotBlank() }
                                if (subDesc != null) {
                                    Text(
                                        text = subDesc,
                                        color = colors.textDim,
                                        fontSize = 12.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            PcPill(
                                label = svc?.enabled?.ifBlank { "static" } ?: "static",
                                tone = if (svc?.enabled == "enabled") PcTone.Ok else PcTone.Dim,
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val runningFor = svc?.since?.takeIf { it.isNotBlank() }?.let { PcFormatters.formatServiceSince(it) }
                        if (!runningFor.isNullOrBlank()) {
                            PcKeyValueRow(key = "Running for", value = runningFor)
                        }
                        if (svc != null && svc.pid > 0) {
                            PcKeyValueRow(key = "PID", value = svc.pid.toString(), mono = true)
                        }
                        if (svc?.memory != null && svc.memory > 0L) {
                            PcKeyValueRow(key = "Memory", value = PcFormatters.formatBytes(svc.memory, digits = 0))
                        }
                        if (svc?.restarts != null && svc.restarts > 0) {
                            PcKeyValueRow(key = "Restarts", value = svc.restarts.toString())
                        }
                        if (!svc?.type.isNullOrBlank()) {
                            PcKeyValueRow(key = "Type", value = svc?.type.orEmpty(), mono = true)
                        }
                        if (!svc?.result.isNullOrBlank() && svc?.result != "success") {
                            PcKeyValueRow(key = "Last result", value = svc?.result.orEmpty())
                        }
                    }
                }

                // Action Buttons (Start / Stop / Restart)
                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!state.isActive) {
                            PcButton(
                                label = "Start",
                                onClick = { onAction(ServiceAction.START) },
                                variant = PcButtonVariant.Primary,
                                icon = Icons.Filled.PlayArrow,
                                busy = state.busyAction == ServiceAction.START,
                                enabled = state.busyAction == null,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (!state.isProtected) {
                            PcButton(
                                label = "Stop",
                                onClick = { pendingConfirmAction = ServiceAction.STOP },
                                variant = PcButtonVariant.Danger,
                                icon = Icons.Filled.Stop,
                                busy = state.busyAction == ServiceAction.STOP,
                                enabled = state.busyAction == null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PcButton(
                            label = "Restart",
                            onClick = { pendingConfirmAction = ServiceAction.RESTART },
                            variant = if (state.isActive && state.isProtected) PcButtonVariant.Primary else PcButtonVariant.Subtle,
                            icon = Icons.Filled.RestartAlt,
                            busy = state.busyAction == ServiceAction.RESTART,
                            enabled = state.busyAction == null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Journalctl Logs Viewer
                item(key = "logs") {
                    val filtered = state.filteredLogs
                    val totalLines = state.logs?.size ?: 0

                    PcCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Recent logs (journalctl)",
                                color = colors.textDim,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (state.logs != null) {
                                    Text(
                                        text = if (state.logFilter.isNotBlank()) {
                                            "${filtered.size}/$totalLines"
                                        } else {
                                            "$totalLines lines"
                                        },
                                        color = colors.textFaint,
                                        style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val text = filtered.joinToString("\n")
                                        if (text.isNotEmpty()) {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText(state.displayName, text))
                                            copiedFeedback = true
                                        }
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy logs",
                                        tint = if (copiedFeedback) colors.accent else colors.textDim,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Filter input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceRaised)
                                .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = colors.textFaint,
                                modifier = Modifier.size(15.dp),
                            )
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (state.logFilter.isEmpty()) {
                                    Text(
                                        text = "Filter log lines…",
                                        color = colors.textFaint,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = state.logFilter,
                                    onValueChange = onLogFilterChange,
                                    singleLine = true,
                                    textStyle = MonoTextStyle.copy(color = colors.text, fontSize = 12.5.sp),
                                    cursorBrush = SolidColor(colors.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (state.logFilter.isNotEmpty()) {
                                IconButton(
                                    onClick = { onLogFilterChange("") },
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear filter",
                                        tint = colors.textDim,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Scrollable monospace log console
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 380.dp)
                                .clip(RoundedCornerShape(PcDimens.RadiusSm))
                                .background(colors.surfaceRaised)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            val consoleText = when {
                                state.logs == null && state.logsLoading -> "Loading logs…"
                                state.logs == null -> "(no logs loaded)"
                                filtered.isEmpty() && state.logFilter.isNotBlank() -> "(no matching log lines)"
                                filtered.isEmpty() -> "(no log lines)"
                                else -> filtered.joinToString("\n")
                            }
                            SelectionContainer {
                                Text(
                                    text = consoleText,
                                    color = if (state.logs.isNullOrEmpty() || filtered.isEmpty()) colors.textFaint else colors.text,
                                    style = MonoTextStyle.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        PcButton(
                            label = "Refresh logs",
                            onClick = {
                                copiedFeedback = false
                                onRefreshLogs()
                            },
                            variant = PcButtonVariant.Subtle,
                            icon = Icons.Filled.Refresh,
                            busy = state.logsLoading,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    pendingConfirmAction?.let { action ->
        val isStop = action == ServiceAction.STOP
        PcConfirmDialog(
            title = "${action.label} ${state.displayName}?",
            message = svc?.description?.takeIf { it.isNotBlank() }
                ?: if (isStop) "This will stop the systemd unit on the laptop." else "This will restart the systemd unit on the laptop.",
            confirmLabel = action.label,
            confirmVariant = if (isStop) PcButtonVariant.Danger else PcButtonVariant.Primary,
            onConfirm = {
                pendingConfirmAction = null
                onAction(action)
            },
            onDismiss = { pendingConfirmAction = null },
        )
    }
}
