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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
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
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcStatusDot

private fun actionIcon(action: String): ImageVector = when (action) {
    "replace_page" -> Icons.Filled.Description
    "replace_text" -> Icons.Filled.FindReplace
    "hide" -> Icons.Filled.VisibilityOff
    "block" -> Icons.Filled.Block
    else -> Icons.Filled.FilterList
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentViewScreen(
    viewModel: AgentViewViewModel,
    onBack: () -> Unit = {},
    onOpenRule: (url: String?, ruleId: String?) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    var pendingDelete by remember { mutableStateOf<AgentRule?>(null) }

    val master = state.master
    val displayEnabled = if (state.switching) state.switchingTarget == true else master?.enabled == true
    val isOff = master != null && !master.enabled && !(state.switching && state.switchingTarget == true)
    val isActive = master?.enabled == true && master.webReachable && !(state.switching && state.switchingTarget == false)
    val isStartingOrOffline = master?.enabled == true && !master.webReachable && !state.switching

    val (dotColor, statusText, cardStatusText) = when {
        state.switching && state.switchingTarget == true ->
            Triple(colors.warn, "Starting stack…", "Starting proxy and web UI…")
        state.switching && state.switchingTarget == false ->
            Triple(colors.warn, "Stopping stack…", "Stopping proxy and web UI…")
        master == null -> Triple(
            if (state.error != null) colors.bad else colors.textFaint,
            if (state.error != null) "Status unavailable" else "Checking status…",
            if (state.error != null) "Status unavailable" else "Checking status…",
        )
        !master.enabled -> Triple(colors.textFaint, "Off · proxy and web UI stopped", "Off")
        !master.webReachable -> Triple(colors.warn, "On · waiting for web UI", "Starting · web UI offline")
        else -> {
            val count = state.viewState?.rules?.size
            val hdr = if (count != null) "Active · $count ${if (count == 1) "rule" else "rules"}" else "Active"
            Triple(colors.ok, hdr, "On · proxy and web UI running")
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
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
                val headerStatus = when {
                    state.error != null -> "Offline"
                    master != null && master.enabled && !master.webReachable -> "Web UI offline"
                    else -> null
                }
                PcScreenHeader(
                    title = "Agent view",
                    statusText = headerStatus,
                    statusColor = if (state.error != null) colors.bad else colors.warn,
                    onBack = onBack,
                )
            }

            if (!state.error.isNullOrBlank()) {
                item(key = "err") {
                    PcErrorBanner(message = state.error!!)
                }
            }

            // Master capture toggle card
            item(key = "master") {
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SmartToy,
                                contentDescription = null,
                                tint = if (isActive) colors.accent else colors.textDim,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Agent view",
                                    color = colors.text,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PcStatusDot(color = dotColor)
                                    Text(text = cardStatusText, color = colors.textDim, fontSize = 13.sp)
                                }
                            }
                            if (state.switching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.textDim,
                                )
                            }
                            Switch(
                                checked = displayEnabled,
                                onCheckedChange = viewModel::toggleMaster,
                                enabled = master != null && !state.switching,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.bg,
                                    checkedTrackColor = colors.accent,
                                    uncheckedThumbColor = colors.textFaint,
                                    uncheckedTrackColor = colors.surfaceRaised,
                                ),
                            )
                        }

                        if (isOff) {
                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                            Text(
                                text = "Capture proxy, MITM filter, and web UI are stopped. Turn on to inspect captured URLs and manage rewrite rules.",
                                color = colors.textDim,
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                }
            }

            if (isStartingOrOffline && state.error == null) {
                item(key = "offline_banner") {
                    PcCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Web UI isn't responding yet",
                                color = colors.warn,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Agent view is turned on, but the local web service on port 1991 has not answered yet.",
                                color = colors.textDim,
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                            )
                            PcButton(
                                label = "Retry",
                                variant = PcButtonVariant.Subtle,
                                onClick = viewModel::load,
                            )
                        }
                    }
                }
            }

            if (isActive) {
                item(key = "tabs") {
                    PcSegmentedControl(
                        options = AgentViewTab.entries.map { it.label },
                        selectedIndex = AgentViewTab.entries.indexOf(state.tab),
                        onSelect = { idx -> viewModel.selectTab(AgentViewTab.entries[idx]) },
                    )
                }

                if (state.tab == AgentViewTab.URLS) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::onSearchQueryChange,
                            singleLine = true,
                            placeholder = { Text("Search URLs", color = colors.textFaint) },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textFaint)
                            },
                            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = colors.textFaint)
                                    }
                                }
                            } else null,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.text,
                                unfocusedTextColor = colors.text,
                                focusedContainerColor = colors.surface,
                                unfocusedContainerColor = colors.surface,
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.hairline,
                                cursorColor = colors.accent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    val urls = state.urls
                    when {
                        urls == null -> item(key = "urls_loading") {
                            PcCard { Text("Loading…", color = colors.textDim, fontSize = 13.5.sp) }
                        }
                        urls.isEmpty() -> item(key = "urls_empty") {
                            PcCard {
                                PcEmptyState(
                                    icon = Icons.Filled.LinkOff,
                                    title = if (state.searchQuery.isNotEmpty()) "No matches" else "No URLs yet",
                                )
                            }
                        }
                        else -> item(key = "urls_list") {
                            PcListCard {
                                urls.forEachIndexed { idx, u ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                                    }
                                    val split = AgentViewRules.splitUrl(u.url)
                                    val matchedRule = state.viewState?.rules?.find { it.id == u.ruleId }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenRule(u.url, u.ruleId) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Link,
                                            contentDescription = null,
                                            tint = colors.textDim,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = split.host.ifBlank { u.host },
                                                color = colors.text,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = split.path,
                                                color = colors.textFaint,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${AgentViewRules.relativeTime(u.lastSeen)} · ${u.count}×",
                                                color = colors.textFaint,
                                                fontSize = 12.sp,
                                                style = TabularNumStyle,
                                            )
                                        }
                                        if (matchedRule != null) {
                                            val pillLabel = matchedRule.name.ifBlank { "Rule" }.take(18)
                                            PcPill(
                                                text = pillLabel,
                                                color = colors.textDim,
                                                backgroundColor = colors.surfaceRaised,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val rules = state.viewState?.rules
                    when {
                        rules == null -> item(key = "rules_loading") {
                            PcCard { Text("Loading…", color = colors.textDim, fontSize = 13.5.sp) }
                        }
                        rules.isEmpty() -> item(key = "rules_empty") {
                            PcCard {
                                PcEmptyState(
                                    icon = Icons.Filled.FilterList,
                                    title = "No rules yet",
                                    body = "Select a captured URL to create a rewrite or block rule.",
                                )
                            }
                        }
                        else -> item(key = "rules_list") {
                            PcListCard {
                                rules.forEachIndexed { idx, rule ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                                    }
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 12.dp, top = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onOpenRule(null, rule.id) }
                                                    .padding(vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Icon(
                                                    imageVector = actionIcon(rule.action),
                                                    contentDescription = null,
                                                    tint = colors.textDim,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = rule.name.ifBlank { rule.match },
                                                        color = colors.text,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = rule.match,
                                                        color = colors.textFaint,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = AgentViewRules.actionLabel(rule.action),
                                                        color = colors.textDim,
                                                        fontSize = 12.sp,
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = rule.enabled,
                                                onCheckedChange = { v -> viewModel.toggleRuleEnabled(rule, v) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = colors.bg,
                                                    checkedTrackColor = colors.accent,
                                                    uncheckedThumbColor = colors.textFaint,
                                                    uncheckedTrackColor = colors.surfaceRaised,
                                                ),
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.moveRule(idx, -1) },
                                                enabled = idx > 0 && !state.busy,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.ArrowUpward,
                                                    contentDescription = "Move up",
                                                    tint = if (idx > 0) colors.textDim else colors.textFaint.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.moveRule(idx, 1) },
                                                enabled = idx < rules.lastIndex && !state.busy,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.ArrowDownward,
                                                    contentDescription = "Move down",
                                                    tint = if (idx < rules.lastIndex) colors.textDim else colors.textFaint.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                            IconButton(onClick = { pendingDelete = rule }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Delete rule",
                                                    tint = colors.bad,
                                                    modifier = Modifier.size(19.dp),
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

            item(key = "bottom_pad") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        PcConfirmDialog(
            title = "Delete rule?",
            message = toDelete.name.ifBlank { toDelete.match },
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteRule(toDelete.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
