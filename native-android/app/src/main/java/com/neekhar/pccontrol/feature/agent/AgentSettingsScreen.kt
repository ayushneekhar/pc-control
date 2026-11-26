package com.neekhar.pccontrol.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcBrandIcons
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle

private val FIVE_HOUR_THRESHOLDS = listOf(10, 15, 20, 25, 30, 40, 50)
private val WEEKLY_THRESHOLDS = listOf(5, 10, 15, 20, 25, 30)

private data class PickerOption(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val disabled: Boolean = false,
)

private data class PickerRequest(
    val title: String,
    val selectedId: String,
    val options: List<PickerOption>,
    val onSelect: (String) -> Unit,
)

private fun formatEffort(v: String): String = when {
    v == "xhigh" -> "XHigh"
    v.isBlank() -> "—"
    else -> v.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    viewModel: AgentSettingsViewModel,
    onBack: () -> Unit = {},
    onOpenAccountDetail: (accountId: String, initialWindow: UsageWindowKey) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current

    var activePicker by remember { mutableStateOf<PickerRequest?>(null) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val statusText = when {
        state.isSwitching -> "Switching…"
        state.isUnavailable -> "Unavailable"
        state.agentState != null && !state.isSelectedActive -> "Stopped"
        else -> null
    }
    val statusColor = when {
        state.isSwitching -> colors.warn
        state.isUnavailable -> colors.bad
        state.agentState != null && !state.isSelectedActive -> colors.warn
        else -> null
    }

    val claude = state.settings?.claude
    val gemini = state.settings?.gemini
    val alerts = state.settings?.usageAlerts
    val claudeModelLabel = claude?.models?.find { it.id == claude.model }?.label ?: claude?.model?.ifBlank { "—" } ?: "—"
    val geminiModelLabel = gemini?.models?.find { it.id == gemini.model }?.label ?: gemini?.model?.ifBlank { "—" } ?: "—"
    val geminiInst = gemini?.instances?.find { it.id == gemini.instanceId }

    val harnessOptions = remember(state.agentState) {
        state.agentState?.options?.takeIf { it.isNotEmpty() } ?: listOf(
            AgentOption(id = "claude", label = "Claude"),
            AgentOption(id = "gemini", label = "Gemini"),
        )
    }
    val selectedHarnessIdx = harnessOptions.indexOfFirst { it.id == state.selectedEngine }.coerceAtLeast(0)

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refreshAll(forceUsage = true) },
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                PcScreenHeader(
                    title = "Agent settings",
                    statusText = statusText,
                    statusColor = statusColor,
                    onBack = onBack,
                )
            }

            // ---- Harness Switcher ----
            item(key = "sec_harness") {
                PcSectionLabel(label = "Harness")
            }
            item(key = "card_harness") {
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PcSegmentedControl(
                            options = harnessOptions.map { it.label },
                            selectedIndex = selectedHarnessIdx,
                            onSelect = { idx ->
                                val chosen = harnessOptions.getOrNull(idx) ?: return@PcSegmentedControl
                                viewModel.selectEngine(chosen.id)
                            },
                        )
                        if (state.isUnavailable) {
                            Text(
                                text = "Agent switch not installed",
                                color = colors.textFaint,
                                fontSize = 12.5.sp,
                            )
                        } else if (!state.agentError.isNullOrBlank()) {
                            PcErrorBanner(message = state.agentError!!)
                        }
                    }
                }
            }

            // ---- AI Usage ----
            item(key = "sec_usage") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PcSectionLabel(
                        label = "AI usage",
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.usage?.stale == true) {
                            Text(
                                text = "updating…",
                                color = colors.textFaint,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        if (state.usageLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent,
                            )
                        } else {
                            Text(
                                text = "Refresh",
                                color = colors.accent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewModel.loadUsage(force = true) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            if (!state.usageError.isNullOrBlank()) {
                item(key = "usage_err") {
                    PcErrorBanner(message = state.usageError!!)
                }
            }

            item(key = "usage_body") {
                val usage = state.usage
                when {
                    usage == null && state.usageLoading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            UsageSkeletonCard(segments = 1)
                            UsageSkeletonCard(segments = 4)
                        }
                    }
                    !usage?.accounts.isNullOrEmpty() -> {
                        GroupedUsageSection(
                            accounts = usage!!.accounts,
                            onSelectAccount = { acc, winKey -> onOpenAccountDetail(acc.id, winKey) },
                        )
                    }
                    state.usageError == null -> {
                        PcCard {
                            Text(text = "No accounts reported.", color = colors.textFaint, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (!state.settingsError.isNullOrBlank()) {
                item(key = "settings_err") {
                    PcErrorBanner(message = state.settingsError!!)
                }
            }

            // ---- Claude defaults ----
            item(key = "sec_claude") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Claude")
            }
            item(key = "card_claude") {
                val showClaudeRestart = state.agentState?.engine == "claude"
                PcListCard {
                    PcListRow(
                        icon = PcBrandIcons.Claude,
                        title = "Account",
                        trailing = claude?.account?.ifBlank { "—" } ?: "—",
                        showChevron = false,
                        showDivider = true,
                    )
                    PcListRow(
                        icon = Icons.Filled.Tune,
                        title = "Model",
                        trailing = claudeModelLabel,
                        showDivider = true,
                        onClick = if (claude != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Claude model",
                                    selectedId = claude.model,
                                    options = claude.models.map { PickerOption(it.id, it.label, it.id) },
                                    onSelect = { id ->
                                        viewModel.updateSettings(AgentSettingsPatch(claude = ClaudePatch(model = id)))
                                    },
                                )
                            }
                        } else null,
                    )
                    PcListRow(
                        icon = Icons.Filled.Speed,
                        title = "Effort",
                        trailing = claude?.let { formatEffort(it.effort) } ?: "—",
                        showDivider = showClaudeRestart,
                        onClick = if (claude != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Claude effort",
                                    selectedId = claude.effort,
                                    options = claude.efforts.map { PickerOption(it, formatEffort(it)) },
                                    onSelect = { id ->
                                        viewModel.updateSettings(AgentSettingsPatch(claude = ClaudePatch(effort = id)))
                                    },
                                )
                            }
                        } else null,
                    )
                    if (showClaudeRestart) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            PcButton(
                                label = "Restart agent now",
                                variant = PcButtonVariant.Subtle,
                                busy = state.restartingClaude,
                                onClick = { showRestartConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- Gemini defaults ----
            item(key = "sec_gemini") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Gemini")
            }
            item(key = "card_gemini") {
                PcListCard {
                    PcListRow(
                        icon = Icons.Filled.AutoAwesome,
                        title = "Account",
                        subtitle = geminiInst?.email ?: if (geminiInst?.signedIn == false) "Not signed in" else null,
                        trailing = geminiInst?.label ?: gemini?.instanceId?.ifBlank { "—" } ?: "—",
                        showDivider = true,
                        onClick = if (gemini != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Gemini account",
                                    selectedId = gemini.instanceId,
                                    options = gemini.instances.map { inst ->
                                        PickerOption(
                                            id = inst.id,
                                            label = inst.label,
                                            subtitle = inst.email ?: if (inst.signedIn) inst.id else "Not signed in",
                                            disabled = !inst.signedIn,
                                        )
                                    },
                                    onSelect = { id ->
                                        viewModel.updateSettings(AgentSettingsPatch(gemini = GeminiPatch(instanceId = id)))
                                    },
                                )
                            }
                        } else null,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Autorenew,
                            contentDescription = null,
                            tint = colors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Auto-rotate on usage limit",
                            color = colors.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = gemini?.autoRotate == true,
                            enabled = gemini != null && !state.saving,
                            onCheckedChange = { v ->
                                viewModel.updateSettings(AgentSettingsPatch(gemini = GeminiPatch(autoRotate = v)))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.bg,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = colors.textFaint,
                                uncheckedTrackColor = colors.surfaceRaised,
                            ),
                        )
                    }
                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                    PcListRow(
                        icon = Icons.Filled.Tune,
                        title = "Model",
                        trailing = geminiModelLabel,
                        showDivider = true,
                        onClick = if (gemini != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Gemini model",
                                    selectedId = gemini.model,
                                    options = gemini.models.map { PickerOption(it.id, it.label, it.id) },
                                    onSelect = { id ->
                                        viewModel.updateSettings(AgentSettingsPatch(gemini = GeminiPatch(model = id)))
                                    },
                                )
                            }
                        } else null,
                    )
                    PcListRow(
                        icon = Icons.Filled.Speed,
                        title = "Effort",
                        trailing = gemini?.let { formatEffort(it.thoughtLevel) } ?: "—",
                        showDivider = false,
                        onClick = if (gemini != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Gemini effort",
                                    selectedId = gemini.thoughtLevel,
                                    options = gemini.efforts.map { PickerOption(it, formatEffort(it)) },
                                    onSelect = { id ->
                                        viewModel.updateSettings(AgentSettingsPatch(gemini = GeminiPatch(thoughtLevel = id)))
                                    },
                                )
                            }
                        } else null,
                    )
                }
            }

            // ---- Usage Alerts ----
            item(key = "sec_alerts") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Usage alerts")
            }
            item(key = "card_alerts") {
                PcListCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            tint = colors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Usage alerts",
                            color = colors.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = alerts?.enabled ?: true,
                            enabled = state.settings != null && !state.saving,
                            onCheckedChange = { v ->
                                viewModel.updateSettings(AgentSettingsPatch(usageAlerts = UsageAlertsPatch(enabled = v)))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.bg,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = colors.textFaint,
                                uncheckedTrackColor = colors.surfaceRaised,
                            ),
                        )
                    }
                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                    PcListRow(
                        icon = Icons.Filled.HourglassBottom,
                        title = "5-hour threshold",
                        trailing = alerts?.let { "${it.fiveHourPct}% left" } ?: "—",
                        showDivider = true,
                        onClick = if (alerts != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "5-hour alert threshold",
                                    selectedId = alerts.fiveHourPct.toString(),
                                    options = FIVE_HOUR_THRESHOLDS.map { pct ->
                                        PickerOption(pct.toString(), "$pct% left", "Alert at ${100 - pct}% used")
                                    },
                                    onSelect = { id ->
                                        id.toIntOrNull()?.let { pct ->
                                            viewModel.updateSettings(AgentSettingsPatch(usageAlerts = UsageAlertsPatch(fiveHourPct = pct)))
                                        }
                                    },
                                )
                            }
                        } else null,
                    )
                    PcListRow(
                        icon = Icons.Filled.CalendarMonth,
                        title = "Weekly threshold",
                        trailing = alerts?.let { "${it.weeklyPct}% left" } ?: "—",
                        showDivider = false,
                        onClick = if (alerts != null && !state.saving) {
                            {
                                activePicker = PickerRequest(
                                    title = "Weekly alert threshold",
                                    selectedId = alerts.weeklyPct.toString(),
                                    options = WEEKLY_THRESHOLDS.map { pct ->
                                        PickerOption(pct.toString(), "$pct% left", "Alert at ${100 - pct}% used")
                                    },
                                    onSelect = { id ->
                                        id.toIntOrNull()?.let { pct ->
                                            viewModel.updateSettings(AgentSettingsPatch(usageAlerts = UsageAlertsPatch(weeklyPct = pct)))
                                        }
                                    },
                                )
                            }
                        } else null,
                    )
                }
            }

            // ---- Conversation reset ----
            item(key = "sec_convo") {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Conversation")
            }
            item(key = "card_convo") {
                PcCard {
                    PcButton(
                        label = "Start fresh",
                        variant = PcButtonVariant.Subtle,
                        busy = state.resetting,
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "bottom_pad") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    val picker = activePicker
    if (picker != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { activePicker = null },
            sheetState = sheetState,
            containerColor = colors.bg,
            contentColor = colors.text,
            dragHandle = { PcSheetDragHandle() },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = picker.title,
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                PcListCard {
                    picker.options.forEachIndexed { idx, opt ->
                        if (idx > 0) {
                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                        }
                        val isSelected = opt.id == picker.selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !opt.disabled) {
                                    picker.onSelect(opt.id)
                                    activePicker = null
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = opt.label,
                                    color = if (opt.disabled) colors.textFaint else colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (!opt.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = opt.subtitle,
                                        color = colors.textFaint,
                                        fontSize = 12.5.sp,
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (opt.disabled) {
                                PcPill(text = "Not signed in", color = colors.textFaint, backgroundColor = colors.surfaceRaised)
                            }
                        }
                    }
                }
                PcButton(
                    label = "Cancel",
                    variant = PcButtonVariant.Subtle,
                    onClick = { activePicker = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showRestartConfirm) {
        PcConfirmDialog(
            title = "Restart Claude agent?",
            message = "Applies your model and effort changes immediately.",
            confirmLabel = "Restart",
            dismissLabel = "Cancel",
            onConfirm = {
                showRestartConfirm = false
                viewModel.restartClaudeAgent()
            },
            onDismiss = { showRestartConfirm = false },
        )
    }

    if (showResetConfirm) {
        PcConfirmDialog(
            title = "Start a fresh conversation?",
            message = "Clears the current conversation context for the active agent.",
            confirmLabel = "Start fresh",
            dismissLabel = "Cancel",
            onConfirm = {
                showResetConfirm = false
                viewModel.resetConversation()
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    if (!state.noticeMessage.isNullOrBlank()) {
        PcConfirmDialog(
            title = "Agent",
            message = state.noticeMessage!!,
            confirmLabel = "OK",
            onConfirm = viewModel::dismissNotice,
            onDismiss = viewModel::dismissNotice,
        )
    }
}
