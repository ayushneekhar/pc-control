package com.neekhar.pccontrol.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.ProviderBrandIcon

private data class ProviderAccordionGroup(
    val key: String,
    val isClaude: Boolean,
    val label: String,
    val instanceId: String?,
    val signedIn: Boolean,
    val isActiveProvider: Boolean,
    val activeModelId: String?,
    val models: List<AgentModelOption>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentModelPickerSheet(
    engine: String,
    settings: ChatAgentSettings?,
    loading: Boolean,
    loadError: String?,
    applying: Boolean,
    applyError: String?,
    onRetryLoad: () -> Unit,
    onApply: (AgentSelectionResult) -> Unit,
    onOpenAgentSettings: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    var searchQuery by remember { mutableStateOf("") }

    PcBottomSheet(
        onDismissRequest = onDismiss,
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Top bar: T3 Code-style "Find a model" search input + Settings gear icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bg)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Find a model",
                                color = colors.textFaint,
                                style = type.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            enabled = settings != null,
                            textStyle = type.body.copy(color = colors.text),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (applying) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else if (searchQuery.isNotEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = colors.textFaint,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                if (onOpenAgentSettings != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceRaised)
                            .clickable {
                                onDismiss()
                                onOpenAgentSettings()
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Agent settings",
                            tint = colors.textDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                settings == null && loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Loading providers…",
                            color = colors.textDim,
                            fontSize = 13.5.sp,
                        )
                    }
                }

                settings == null && loadError != null -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        PickerErrorBanner(title = "Could not load models", detail = loadError)
                        PickerRetryButton(onClick = onRetryLoad)
                    }
                }

                settings != null -> {
                    PickerBody(
                        engine = engine,
                        settings = settings,
                        searchQuery = searchQuery,
                        applyError = applyError,
                        onApply = onApply,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerBody(
    engine: String,
    settings: ChatAgentSettings,
    searchQuery: String,
    applyError: String?,
    onApply: (AgentSelectionResult) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val haptic = LocalHapticFeedback.current

    val activeEngine = ChatEngines.normalize(engine)
    val isClaudeActive = activeEngine == ChatEngines.CLAUDE
    val efforts = if (isClaudeActive) settings.claude.efforts else settings.gemini.efforts
    val activeEffort = if (isClaudeActive) settings.claude.effort else settings.gemini.thoughtLevel

    // Build provider groups (labels only — never show account emails)
    val providers = remember(engine, settings) {
        buildList {
            add(
                ProviderAccordionGroup(
                    key = "claude",
                    isClaude = true,
                    label = "Claude",
                    instanceId = null,
                    signedIn = true,
                    isActiveProvider = isClaudeActive,
                    activeModelId = if (isClaudeActive) settings.claude.model else null,
                    models = settings.claude.models,
                ),
            )
            if (settings.gemini.instances.isNotEmpty()) {
                for (inst in settings.gemini.instances) {
                    val isInstActive = !isClaudeActive && settings.gemini.instanceId == inst.id
                    add(
                        ProviderAccordionGroup(
                            key = "gemini:${inst.id}",
                            isClaude = false,
                            label = inst.label,
                            instanceId = inst.id,
                            signedIn = inst.signedIn,
                            isActiveProvider = isInstActive,
                            activeModelId = if (isInstActive) settings.gemini.model else null,
                            models = settings.gemini.models,
                        ),
                    )
                }
            } else {
                add(
                    ProviderAccordionGroup(
                        key = "gemini:${settings.gemini.instanceId}",
                        isClaude = false,
                        label = "Antigravity",
                        instanceId = settings.gemini.instanceId,
                        signedIn = true,
                        isActiveProvider = !isClaudeActive,
                        activeModelId = if (!isClaudeActive) settings.gemini.model else null,
                        models = settings.gemini.models,
                    ),
                )
            }
        }
    }

    val initialExpandedKey = remember(providers) {
        providers.firstOrNull { it.isActiveProvider }?.key ?: providers.firstOrNull()?.key
    }
    var expandedKeys by remember {
        mutableStateOf(if (initialExpandedKey != null) setOf(initialExpandedKey) else emptySet())
    }

    val trimmedQuery = searchQuery.trim()
    val isSearching = trimmedQuery.isNotEmpty()

    val filteredProviders = remember(providers, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            providers.map { it to it.models }
        } else {
            providers.mapNotNull { group ->
                if (!group.signedIn) return@mapNotNull null
                val providerMatches = group.label.contains(trimmedQuery, ignoreCase = true)
                val matchedModels = if (providerMatches) {
                    group.models
                } else {
                    group.models.filter { m ->
                        m.label.contains(trimmedQuery, ignoreCase = true) ||
                            m.id.contains(trimmedQuery, ignoreCase = true)
                    }
                }
                if (matchedModels.isEmpty()) null else group to matchedModels
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Scrollable collapsible provider accordion list
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            if (filteredProviders.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                ) {
                    Text(
                        text = "No matching models",
                        color = colors.textFaint,
                        style = type.body,
                    )
                }
            } else {
                for ((group, visibleModels) in filteredProviders) {
                    val isExpanded = if (isSearching) {
                        true
                    } else {
                        group.key in expandedKeys
                    }

                    ProviderAccordionSection(
                        group = group,
                        models = visibleModels,
                        expanded = isExpanded,
                        onToggleExpand = {
                            if (!isSearching && group.signedIn) {
                                expandedKeys = if (group.key in expandedKeys) {
                                    expandedKeys - group.key
                                } else {
                                    expandedKeys + group.key
                                }
                            }
                        },
                        onSelectModel = { model ->
                            val alreadyActive = group.isActiveProvider && model.id == group.activeModelId
                            if (!alreadyActive) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (group.isClaude) {
                                    val modelDiff = model.id.takeIf { it != settings.claude.model }
                                    val patch = if (modelDiff != null) {
                                        ChatAgentSettingsPatch(
                                            claude = ClaudeSettingsPatch(model = modelDiff),
                                        )
                                    } else {
                                        ChatAgentSettingsPatch()
                                    }
                                    onApply(
                                        AgentSelectionResult(
                                            engine = ChatEngines.CLAUDE,
                                            patch = patch,
                                        ),
                                    )
                                } else {
                                    val instDiff = group.instanceId?.takeIf {
                                        it.isNotEmpty() && it != settings.gemini.instanceId
                                    }
                                    val modelDiff = model.id.takeIf { it != settings.gemini.model }
                                    val patch = if (instDiff != null || modelDiff != null) {
                                        ChatAgentSettingsPatch(
                                            gemini = GeminiSettingsPatch(
                                                instanceId = instDiff,
                                                model = modelDiff,
                                            ),
                                        )
                                    } else {
                                        ChatAgentSettingsPatch()
                                    }
                                    onApply(
                                        AgentSelectionResult(
                                            engine = ChatEngines.GEMINI,
                                            patch = patch,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            if (!applyError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                PickerErrorBanner(title = applyError, detail = null)
            }

            Spacer(Modifier.height(6.dp))
        }

        // Effort selector cleanly pinned at the bottom with instant apply (no Apply button)
        if (efforts.isNotEmpty()) {
            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp),
            ) {
                Text(
                    text = "Effort",
                    color = colors.textDim,
                    style = type.sectionLabel,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bg)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                ) {
                    for (eff in efforts) {
                        val selected = eff == activeEffort
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) colors.surfaceRaised else Color.Transparent)
                                .clickable {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val patch = if (isClaudeActive) {
                                            ChatAgentSettingsPatch(
                                                claude = ClaudeSettingsPatch(effort = eff),
                                            )
                                        } else {
                                            ChatAgentSettingsPatch(
                                                gemini = GeminiSettingsPatch(thoughtLevel = eff),
                                            )
                                        }
                                        onApply(
                                            AgentSelectionResult(
                                                engine = activeEngine,
                                                patch = patch,
                                            ),
                                        )
                                    }
                                }
                                .padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = ChatLogic.shortEffortLabel(eff),
                                color = if (selected) colors.text else colors.textDim,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderAccordionSection(
    group: ProviderAccordionGroup,
    models: List<AgentModelOption>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectModel: (AgentModelOption) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Collapsible provider header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (group.signedIn) 1f else 0.45f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = group.signedIn, onClick = onToggleExpand)
                .padding(horizontal = 8.dp, vertical = 11.dp),
        ) {
            ProviderBrandIcon(
                isClaude = group.isClaude,
                modifier = Modifier.size(18.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = group.label,
                    color = if (group.isActiveProvider) colors.text else colors.textDim,
                    fontSize = 14.5.sp,
                    fontWeight = if (group.isActiveProvider) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.isActiveProvider) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                }
            }

            if (!group.signedIn) {
                Text(
                    text = "Signed out",
                    color = colors.textFaint,
                    style = type.caption,
                )
            } else {
                Text(
                    text = "${models.size}",
                    color = colors.textFaint,
                    style = type.caption.tabular(),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse ${group.label}" else "Expand ${group.label}",
                    tint = colors.textFaint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Expanded grouped card of radio-button model rows
        AnimatedVisibility(
            visible = expanded && group.signedIn && models.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.bg)
                    .border(0.5.dp, colors.hairline, RoundedCornerShape(14.dp)),
            ) {
                models.forEachIndexed { idx, model ->
                    val selected = group.isActiveProvider && model.id == group.activeModelId
                    if (idx > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 42.dp),
                            color = colors.hairline,
                            thickness = 0.5.dp,
                        )
                    }
                    ModelRadioRow(
                        label = model.label,
                        selected = selected,
                        onClick = { onSelectModel(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        RadioCheckmark(selected = selected)
        Text(
            text = label,
            color = if (selected) colors.text else colors.textDim,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RadioCheckmark(selected: Boolean) {
    val colors = LocalPcColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(colors.accent)
                } else {
                    Modifier.border(1.5.dp, colors.hairline, CircleShape)
                },
            ),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = colors.onAccent,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun PickerErrorBanner(title: String, detail: String?) {
    val colors = LocalPcColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.badSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = colors.bad,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.bad,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun PickerRetryButton(onClick: () -> Unit) {
    val colors = LocalPcColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceRaised)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "Retry",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
