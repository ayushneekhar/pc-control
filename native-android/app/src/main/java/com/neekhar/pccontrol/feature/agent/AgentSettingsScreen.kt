package com.neekhar.pccontrol.feature.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcSwitch
import com.neekhar.pccontrol.core.ui.ProviderBrandIcon

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
    val type = LocalPcType.current

    var activePicker by remember { mutableStateOf<PickerRequest?>(null) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showOtherProvider by remember(state.selectedEngine) { mutableStateOf(false) }

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
    val isGeminiSelected = state.selectedEngine == "gemini"
    val preferredProvider = if (isGeminiSelected) UsageProviderKey.GEMINI else UsageProviderKey.CLAUDE

    val harnessOptions = remember(state.agentState) {
        state.agentState?.options?.takeIf { it.isNotEmpty() } ?: listOf(
            AgentOption(id = "claude", label = "Claude"),
            AgentOption(id = "gemini", label = "Gemini"),
        )
    }
    val selectedHarnessIdx = harnessOptions.indexOfFirst { it.id == state.selectedEngine }.coerceAtLeast(0)

    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    PcScaffold(
        title = "Agent settings",
        statusText = statusText,
        statusColor = statusColor,
        onBack = onBack,
        listState = listState,
        applyNavigationBarPadding = false,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refreshAll(forceUsage = true) },
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
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topPad, bottom = 32.dp + navBottom),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // ---- Harness Switcher ----
                    item(key = "harness_switcher") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    style = type.caption,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            } else if (!state.agentError.isNullOrBlank()) {
                                PcErrorBanner(message = state.agentError!!)
                            }
                        }
                    }

                    // ---- AI Usage (selected provider ordered first, unified quota rows) ----
                    item(key = "usage_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PcSectionLabel(
                                label = "AI usage",
                                trailing = if (state.usage?.stale == true) {
                                    {
                                        Text(
                                            text = "updating…",
                                            color = colors.textFaint,
                                            style = type.micro,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )

                            if (!state.usageError.isNullOrBlank()) {
                                PcErrorBanner(message = state.usageError!!)
                            }

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
                                        preferredProvider = preferredProvider,
                                    )
                                }
                                state.usageError == null -> {
                                    PcCard {
                                        Text(text = "No accounts reported.", color = colors.textFaint, style = type.caption)
                                    }
                                }
                            }
                        }
                    }

                    if (!state.settingsError.isNullOrBlank()) {
                        item(key = "settings_err") {
                            PcErrorBanner(message = state.settingsError!!)
                        }
                    }

                    // ---- Active provider settings in focus ----
                    item(key = "section_active_provider") {
                        if (isGeminiSelected) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PcSectionLabel(label = "Gemini")
                                GeminiSettingsCard(
                                    gemini = gemini,
                                    geminiModelLabel = geminiModelLabel,
                                    geminiInst = geminiInst,
                                    saving = state.saving,
                                    onOpenPicker = { activePicker = it },
                                    onUpdateSettings = viewModel::updateSettings,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PcSectionLabel(label = "Claude")
                                ClaudeSettingsCard(
                                    claude = claude,
                                    claudeModelLabel = claudeModelLabel,
                                    showRestart = state.agentState?.engine == "claude",
                                    saving = state.saving,
                                    restartingClaude = state.restartingClaude,
                                    onOpenPicker = { activePicker = it },
                                    onUpdateSettings = viewModel::updateSettings,
                                    onRequestRestart = { showRestartConfirm = true },
                                )
                            }
                        }
                    }

                    // ---- Inactive provider settings collapsed cleanly to keep layout tight ----
                    item(key = "section_other_provider") {
                        val otherIsClaude = isGeminiSelected
                        val otherTitle = if (otherIsClaude) "Claude defaults" else "Gemini defaults"
                        val otherSubtitle = if (otherIsClaude) {
                            val effort = claude?.let { formatEffort(it.effort) } ?: "—"
                            "$claudeModelLabel · $effort"
                        } else {
                            val acc = geminiInst?.label ?: gemini?.instanceId?.ifBlank { "—" } ?: "—"
                            "$geminiModelLabel · $acc"
                        }

                        PcListCard(modifier = Modifier.animateContentSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showOtherProvider = !showOtherProvider }
                                    .defaultMinSize(minHeight = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ProviderBrandIcon(
                                    isClaude = otherIsClaude,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        text = otherTitle,
                                        color = colors.text,
                                        style = type.cardTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = otherSubtitle,
                                        color = colors.textFaint,
                                        style = type.caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    imageVector = if (showOtherProvider) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (showOtherProvider) "Collapse" else "Expand",
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            if (showOtherProvider) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = colors.hairline,
                                    thickness = 0.5.dp,
                                )
                                if (otherIsClaude) {
                                    ClaudeSettingsRows(
                                        claude = claude,
                                        claudeModelLabel = claudeModelLabel,
                                        showRestart = state.agentState?.engine == "claude",
                                        saving = state.saving,
                                        restartingClaude = state.restartingClaude,
                                        onOpenPicker = { activePicker = it },
                                        onUpdateSettings = viewModel::updateSettings,
                                        onRequestRestart = { showRestartConfirm = true },
                                    )
                                } else {
                                    GeminiSettingsRows(
                                        gemini = gemini,
                                        geminiModelLabel = geminiModelLabel,
                                        geminiInst = geminiInst,
                                        saving = state.saving,
                                        onOpenPicker = { activePicker = it },
                                        onUpdateSettings = viewModel::updateSettings,
                                    )
                                }
                            }
                        }
                    }

                    // ---- Usage Alerts ----
                    item(key = "section_alerts") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PcSectionLabel(label = "Usage alerts")
                            PcListCard {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 56.dp)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.NotificationsActive,
                                        contentDescription = null,
                                        tint = colors.textDim,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "Usage alerts",
                                        color = colors.text,
                                        style = type.cardTitle,
                                        modifier = Modifier.weight(1f),
                                    )
                                    PcSwitch(
                                        checked = alerts?.enabled ?: true,
                                        enabled = state.settings != null && !state.saving,
                                        onCheckedChange = { v ->
                                            viewModel.updateSettings(AgentSettingsPatch(usageAlerts = UsageAlertsPatch(enabled = v)))
                                        },
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = colors.hairline,
                                    thickness = 0.5.dp,
                                )
                                PcListRow(
                                    icon = Icons.Rounded.HourglassBottom,
                                    title = "5-hour threshold",
                                    trailing = alerts?.let { "${it.fiveHourPct}% left" } ?: "—",
                                    showChevron = true,
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
                                    icon = Icons.Rounded.CalendarMonth,
                                    title = "Weekly threshold",
                                    trailing = alerts?.let { "${it.weeklyPct}% left" } ?: "—",
                                    showChevron = true,
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
                    }

                    // ---- Conversation reset ----
                    item(key = "section_convo") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PcSectionLabel(label = "Conversation")
                            PcButton(
                                label = "Start fresh",
                                variant = PcButtonVariant.Subtle,
                                busy = state.resetting,
                                onClick = { showResetConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // Subtle bottom gesture-bar fade scrim so content draws cleanly under the nav bar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navBottom + 20.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.bg),
                        ),
                    ),
            )
        }
    }

    val picker = activePicker
    if (picker != null) {
        PcBottomSheet(
            onDismissRequest = { activePicker = null },
            title = picker.title,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceRaised),
                ) {
                    picker.options.forEachIndexed { idx, opt ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = colors.hairline,
                                thickness = 0.5.dp,
                            )
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
                                    style = type.cardTitle,
                                )
                                if (!opt.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = opt.subtitle,
                                        color = colors.textFaint,
                                        style = type.caption,
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (opt.disabled) {
                                PcPill(text = "Not signed in", color = colors.textFaint, backgroundColor = colors.surface)
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

@Composable
private fun ClaudeSettingsCard(
    claude: ClaudeSettings?,
    claudeModelLabel: String,
    showRestart: Boolean,
    saving: Boolean,
    restartingClaude: Boolean,
    onOpenPicker: (PickerRequest) -> Unit,
    onUpdateSettings: (AgentSettingsPatch) -> Unit,
    onRequestRestart: () -> Unit,
) {
    PcListCard {
        ClaudeSettingsRows(
            claude = claude,
            claudeModelLabel = claudeModelLabel,
            showRestart = showRestart,
            saving = saving,
            restartingClaude = restartingClaude,
            onOpenPicker = onOpenPicker,
            onUpdateSettings = onUpdateSettings,
            onRequestRestart = onRequestRestart,
        )
    }
}

@Composable
private fun ClaudeSettingsRows(
    claude: ClaudeSettings?,
    claudeModelLabel: String,
    showRestart: Boolean,
    saving: Boolean,
    restartingClaude: Boolean,
    onOpenPicker: (PickerRequest) -> Unit,
    onUpdateSettings: (AgentSettingsPatch) -> Unit,
    onRequestRestart: () -> Unit,
) {
    PcListRow(
        leading = {
            ProviderBrandIcon(
                isClaude = true,
                modifier = Modifier.size(20.dp),
            )
        },
        title = "Account",
        trailing = claude?.account?.ifBlank { "—" } ?: "—",
        showChevron = false,
        showDivider = true,
    )
    PcListRow(
        icon = Icons.Rounded.Tune,
        title = "Model",
        trailing = claudeModelLabel,
        showChevron = true,
        showDivider = true,
        onClick = if (claude != null && !saving) {
            {
                onOpenPicker(
                    PickerRequest(
                        title = "Claude model",
                        selectedId = claude.model,
                        options = claude.models.map { PickerOption(it.id, it.label, it.id) },
                        onSelect = { id ->
                            onUpdateSettings(AgentSettingsPatch(claude = ClaudePatch(model = id)))
                        },
                    ),
                )
            }
        } else null,
    )
    PcListRow(
        icon = Icons.Rounded.Speed,
        title = "Effort",
        trailing = claude?.let { formatEffort(it.effort) } ?: "—",
        showChevron = true,
        showDivider = showRestart,
        onClick = if (claude != null && !saving) {
            {
                onOpenPicker(
                    PickerRequest(
                        title = "Claude effort",
                        selectedId = claude.effort,
                        options = claude.efforts.map { PickerOption(it, formatEffort(it)) },
                        onSelect = { id ->
                            onUpdateSettings(AgentSettingsPatch(claude = ClaudePatch(effort = id)))
                        },
                    ),
                )
            }
        } else null,
    )
    if (showRestart) {
        Column(modifier = Modifier.padding(14.dp)) {
            PcButton(
                label = "Restart agent now",
                variant = PcButtonVariant.Subtle,
                busy = restartingClaude,
                onClick = onRequestRestart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GeminiSettingsCard(
    gemini: GeminiSettings?,
    geminiModelLabel: String,
    geminiInst: GeminiInstanceOption?,
    saving: Boolean,
    onOpenPicker: (PickerRequest) -> Unit,
    onUpdateSettings: (AgentSettingsPatch) -> Unit,
) {
    PcListCard {
        GeminiSettingsRows(
            gemini = gemini,
            geminiModelLabel = geminiModelLabel,
            geminiInst = geminiInst,
            saving = saving,
            onOpenPicker = onOpenPicker,
            onUpdateSettings = onUpdateSettings,
        )
    }
}

@Composable
private fun GeminiSettingsRows(
    gemini: GeminiSettings?,
    geminiModelLabel: String,
    geminiInst: GeminiInstanceOption?,
    saving: Boolean,
    onOpenPicker: (PickerRequest) -> Unit,
    onUpdateSettings: (AgentSettingsPatch) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    PcListRow(
        leading = {
            ProviderBrandIcon(
                isClaude = false,
                modifier = Modifier.size(20.dp),
            )
        },
        title = "Account",
        subtitle = geminiInst?.email ?: if (geminiInst?.signedIn == false) "Not signed in" else null,
        trailing = geminiInst?.label ?: gemini?.instanceId?.ifBlank { "—" } ?: "—",
        showChevron = true,
        showDivider = true,
        onClick = if (gemini != null && !saving) {
            {
                onOpenPicker(
                    PickerRequest(
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
                            onUpdateSettings(AgentSettingsPatch(gemini = GeminiPatch(instanceId = id)))
                        },
                    ),
                )
            }
        } else null,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Autorenew,
            contentDescription = null,
            tint = colors.textDim,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Auto-rotate on usage limit",
            color = colors.text,
            style = type.cardTitle,
            modifier = Modifier.weight(1f),
        )
        PcSwitch(
            checked = gemini?.autoRotate == true,
            enabled = gemini != null && !saving,
            onCheckedChange = { v ->
                onUpdateSettings(AgentSettingsPatch(gemini = GeminiPatch(autoRotate = v)))
            },
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = colors.hairline,
        thickness = 0.5.dp,
    )
    PcListRow(
        icon = Icons.Rounded.Tune,
        title = "Model",
        trailing = geminiModelLabel,
        showChevron = true,
        showDivider = true,
        onClick = if (gemini != null && !saving) {
            {
                onOpenPicker(
                    PickerRequest(
                        title = "Gemini model",
                        selectedId = gemini.model,
                        options = gemini.models.map { PickerOption(it.id, it.label, it.id) },
                        onSelect = { id ->
                            onUpdateSettings(AgentSettingsPatch(gemini = GeminiPatch(model = id)))
                        },
                    ),
                )
            }
        } else null,
    )
    PcListRow(
        icon = Icons.Rounded.Speed,
        title = "Effort",
        trailing = gemini?.let { formatEffort(it.thoughtLevel) } ?: "—",
        showChevron = true,
        showDivider = false,
        onClick = if (gemini != null && !saving) {
            {
                onOpenPicker(
                    PickerRequest(
                        title = "Gemini effort",
                        selectedId = gemini.thoughtLevel,
                        options = gemini.efforts.map { PickerOption(it, formatEffort(it)) },
                        onSelect = { id ->
                            onUpdateSettings(AgentSettingsPatch(gemini = GeminiPatch(thoughtLevel = id)))
                        },
                    ),
                )
            }
        } else null,
    )
}
