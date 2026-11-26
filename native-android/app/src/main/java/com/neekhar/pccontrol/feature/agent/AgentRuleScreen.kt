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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcConfirmDialog
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcScreenHeader
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentedControl

private val ACTION_CHOICES = listOf(
    Triple("replace_page", Icons.Filled.Description, "Show my page"),
    Triple("replace_text", Icons.Filled.FindReplace, "Replace text"),
    Triple("hide", Icons.Filled.VisibilityOff, "Hide elements"),
    Triple("block", Icons.Filled.Block, "Block"),
)

@Composable
fun AgentRuleScreen(
    viewModel: AgentViewViewModel,
    url: String? = null,
    ruleId: String? = null,
    onBack: () -> Unit = {},
) {
    val state by viewModel.ruleState.collectAsState()
    val colors = LocalPcColors.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(url, ruleId) {
        viewModel.loadRuleEditor(url, ruleId)
    }

    LaunchedEffect(state.savedAndDone) {
        if (state.savedAndDone) onBack()
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedContainerColor = colors.surfaceRaised,
        unfocusedContainerColor = colors.surfaceRaised,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.hairline,
        cursorColor = colors.accent,
    )

    val split = remember(state.ruleUrl) { AgentViewRules.splitUrl(state.ruleUrl) }
    val title = if (state.rule != null) "Edit rule" else "New rule"
    val subtitle = if (state.ruleUrl.isNotBlank()) "${split.host}${split.path}" else "Agent view"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            PcScreenHeader(
                title = title,
                statusText = subtitle,
                statusColor = colors.accent,
                onBack = onBack,
            )
        }

        if (state.ruleUrl.isNotBlank()) {
            item(key = "url_card") {
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "URL", color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = state.ruleUrl,
                            color = colors.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        if (!state.error.isNullOrBlank()) {
            item(key = "err") {
                PcErrorBanner(message = state.error!!)
            }
        }

        if (state.loading) {
            item(key = "loading") {
                PcCard { Text("Loading…", color = colors.textDim, fontSize = 13.5.sp) }
            }
        }

        // Step 1: Choose action when creating a rule from a captured URL
        if (!state.loading && state.rule == null && state.ruleUrl.isNotBlank()) {
            item(key = "sec_action") {
                PcSectionLabel(label = "Choose action")
            }
            item(key = "action_list") {
                PcListCard {
                    ACTION_CHOICES.forEachIndexed { idx, (actionId, icon, label) ->
                        if (idx > 0) {
                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                        }
                        PcListRow(
                            icon = icon,
                            title = label,
                            trailing = if (state.busyAction == actionId) "Creating…" else null,
                            onClick = if (state.busyAction == null) {
                                { viewModel.chooseActionForNewRule(actionId) }
                            } else null,
                        )
                    }
                }
            }
        }

        // Step 2: Edit rule fields
        val rule = state.rule
        val draft = state.draft
        if (!state.loading && rule != null && draft != null) {
            val shownMatch = when {
                draft.matchMode == MatchMode.EXACT && state.ruleUrl.isNotBlank() ->
                    AgentViewRules.buildExactMatch(state.ruleUrl).match
                draft.matchMode == MatchMode.SITE && state.ruleUrl.isNotBlank() ->
                    AgentViewRules.buildSiteMatch(state.ruleUrl).match
                else -> draft.customMatch
            }
            val shownMatchType = when (draft.matchMode) {
                MatchMode.EXACT -> "regex"
                MatchMode.SITE -> "glob"
                MatchMode.CUSTOM -> draft.customMatchType
            }

            item(key = "sec_rule") {
                PcSectionLabel(label = "Rule")
            }
            item(key = "card_rule") {
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = AgentViewRules.actionLabel(draft.action),
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (state.ruleUrl.isNotBlank()) split.host else rule.match,
                                    color = colors.textDim,
                                    fontSize = 12.5.sp,
                                )
                            }
                            Switch(
                                checked = draft.enabled,
                                onCheckedChange = { v -> viewModel.updateDraft { it.copy(enabled = v) } },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.bg,
                                    checkedTrackColor = colors.accent,
                                    uncheckedThumbColor = colors.textFaint,
                                    uncheckedTrackColor = colors.surfaceRaised,
                                ),
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Name", color = colors.textDim, fontSize = 12.5.sp)
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { v -> viewModel.updateDraft { it.copy(name = v) } },
                                singleLine = true,
                                placeholder = { Text("Rule name", color = colors.textFaint) },
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item(key = "sec_match") {
                PcSectionLabel(label = "Match")
            }
            item(key = "card_match") {
                PcCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PcSegmentedControl(
                            options = MatchMode.entries.map { it.label },
                            selectedIndex = MatchMode.entries.indexOf(draft.matchMode),
                            onSelect = { idx ->
                                val mode = MatchMode.entries[idx]
                                viewModel.updateDraft {
                                    it.copy(
                                        matchMode = mode,
                                        customMatchType = when (mode) {
                                            MatchMode.EXACT -> "regex"
                                            MatchMode.SITE -> "glob"
                                            MatchMode.CUSTOM -> it.customMatchType
                                        },
                                    )
                                }
                            },
                        )

                        OutlinedTextField(
                            value = shownMatch,
                            onValueChange = { v ->
                                viewModel.updateDraft {
                                    it.copy(
                                        matchMode = MatchMode.CUSTOM,
                                        customMatch = v,
                                        customMatchType = shownMatchType,
                                    )
                                }
                            },
                            singleLine = true,
                            placeholder = { Text("Match pattern", color = colors.textFaint) },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (draft.matchMode == MatchMode.CUSTOM) {
                            PcSegmentedControl(
                                options = listOf("Glob", "Regex"),
                                selectedIndex = if (draft.customMatchType == "regex") 1 else 0,
                                onSelect = { idx ->
                                    viewModel.updateDraft { it.copy(customMatchType = if (idx == 1) "regex" else "glob") }
                                },
                            )
                        }
                    }
                }
            }

            // Action-specific editor
            when (draft.action) {
                "replace_page" -> {
                    item(key = "sec_html") { PcSectionLabel(label = "HTML") }
                    item(key = "card_html") {
                        PcCard {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.originalMeta?.truncated == true) {
                                    Text(
                                        text = "Original HTML was truncated at 2 MiB.",
                                        color = colors.warn,
                                        fontSize = 12.5.sp,
                                    )
                                } else if (state.originalMeta?.available == false) {
                                    Text(
                                        text = "No original HTML captured yet. Paste HTML or visit the URL through the proxy first.",
                                        color = colors.warn,
                                        fontSize = 12.5.sp,
                                    )
                                }
                                OutlinedTextField(
                                    value = draft.html,
                                    onValueChange = { v -> viewModel.updateDraft { it.copy(html = v) } },
                                    placeholder = { Text("<!doctype html>…", color = colors.textFaint) },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = fieldColors,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp),
                                )
                                PcButton(
                                    label = "Reset to original",
                                    variant = PcButtonVariant.Subtle,
                                    busy = state.busyAction == "original",
                                    onClick = viewModel::resetHtmlToOriginal,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "replace_text" -> {
                    item(key = "sec_replacements") { PcSectionLabel(label = "Replacements") }
                    item(key = "card_replacements") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (draft.replacements.isEmpty()) {
                                Text("No pairs yet.", color = colors.textDim, fontSize = 13.sp)
                            } else {
                                PcListCard {
                                    draft.replacements.forEachIndexed { idx, pair ->
                                        if (idx > 0) {
                                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                                        }
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            OutlinedTextField(
                                                value = pair.find,
                                                onValueChange = { v ->
                                                    viewModel.updateDraft { d ->
                                                        d.copy(
                                                            replacements = d.replacements.mapIndexed { j, p ->
                                                                if (j == idx) p.copy(find = v) else p
                                                            },
                                                        )
                                                    }
                                                },
                                                singleLine = true,
                                                placeholder = { Text("Find text or regex", color = colors.textFaint) },
                                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = fieldColors,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            OutlinedTextField(
                                                value = pair.replace,
                                                onValueChange = { v ->
                                                    viewModel.updateDraft { d ->
                                                        d.copy(
                                                            replacements = d.replacements.mapIndexed { j, p ->
                                                                if (j == idx) p.copy(replace = v) else p
                                                            },
                                                        )
                                                    }
                                                },
                                                singleLine = true,
                                                placeholder = { Text("Replace with", color = colors.textFaint) },
                                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = fieldColors,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = "Regular expression",
                                                    color = colors.textDim,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Switch(
                                                    checked = pair.regex,
                                                    onCheckedChange = { v ->
                                                        viewModel.updateDraft { d ->
                                                            d.copy(
                                                                replacements = d.replacements.mapIndexed { j, p ->
                                                                    if (j == idx) p.copy(regex = v) else p
                                                                },
                                                            )
                                                        }
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = colors.bg,
                                                        checkedTrackColor = colors.accent,
                                                        uncheckedThumbColor = colors.textFaint,
                                                        uncheckedTrackColor = colors.surfaceRaised,
                                                    ),
                                                )
                                                IconButton(
                                                    onClick = {
                                                        viewModel.updateDraft { d ->
                                                            d.copy(replacements = d.replacements.filterIndexed { j, _ -> j != idx })
                                                        }
                                                    },
                                                ) {
                                                    Icon(Icons.Filled.Delete, contentDescription = "Remove pair", tint = colors.bad)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            PcButton(
                                label = "Add pair",
                                variant = PcButtonVariant.Subtle,
                                onClick = {
                                    viewModel.updateDraft { d ->
                                        d.copy(replacements = d.replacements + Replacement())
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                "hide" -> {
                    item(key = "sec_selectors") { PcSectionLabel(label = "CSS selectors") }
                    item(key = "card_selectors") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (draft.selectors.isEmpty()) {
                                Text("No selectors yet.", color = colors.textDim, fontSize = 13.sp)
                            } else {
                                PcListCard {
                                    draft.selectors.forEachIndexed { idx, sel ->
                                        if (idx > 0) {
                                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            OutlinedTextField(
                                                value = sel,
                                                onValueChange = { v ->
                                                    viewModel.updateDraft { d ->
                                                        d.copy(
                                                            selectors = d.selectors.mapIndexed { j, s -> if (j == idx) v else s },
                                                        )
                                                    }
                                                },
                                                singleLine = true,
                                                placeholder = { Text(".ad-banner", color = colors.textFaint) },
                                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = fieldColors,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = {
                                                    viewModel.updateDraft { d ->
                                                        d.copy(selectors = d.selectors.filterIndexed { j, _ -> j != idx })
                                                    }
                                                },
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Remove selector", tint = colors.bad)
                                            }
                                        }
                                    }
                                }
                            }
                            PcButton(
                                label = "Add selector",
                                variant = PcButtonVariant.Subtle,
                                onClick = {
                                    viewModel.updateDraft { d -> d.copy(selectors = d.selectors + "") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                "block" -> {
                    item(key = "sec_block") { PcSectionLabel(label = "Block mode") }
                    item(key = "card_block") {
                        PcCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("404" to "404 Not Found", "reset" to "Connection reset").forEach { (id, label) ->
                                    val selected = draft.blockMode == id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) colors.surfaceRaised else colors.surface)
                                            .clickable { viewModel.updateDraft { it.copy(blockMode = id) } }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (selected) colors.accent else colors.textFaint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = label,
                                            color = colors.text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Delete & Save actions
            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PcButton(
                        label = "Delete",
                        variant = PcButtonVariant.Danger,
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.weight(1f),
                    )
                    PcButton(
                        label = "Save",
                        variant = PcButtonVariant.Primary,
                        busy = state.busyAction == "save",
                        onClick = viewModel::saveRule,
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }

        item(key = "bottom_pad") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDeleteConfirm && state.rule != null) {
        val r = state.rule!!
        PcConfirmDialog(
            title = "Delete rule?",
            message = r.name.ifBlank { r.match },
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            confirmVariant = PcButtonVariant.Danger,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteRule(r.id, onDeleted = onBack)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
