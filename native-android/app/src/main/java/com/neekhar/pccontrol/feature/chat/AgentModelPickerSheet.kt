package com.neekhar.pccontrol.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcSheetDragHandle
import com.neekhar.pccontrol.core.ui.ProviderBrandIcon

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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text,
        dragHandle = { PcSheetDragHandle() },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "Model & effort",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onOpenAgentSettings != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceRaised)
                            .clickable(onClick = onDismiss),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = colors.textDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

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
                        PickerPrimaryButton(
                            label = "Retry",
                            enabled = true,
                            busy = false,
                            subtle = true,
                            onClick = onRetryLoad,
                        )
                    }
                }

                settings != null -> {
                    PickerBody(
                        engine = engine,
                        settings = settings,
                        applying = applying,
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
    applying: Boolean,
    applyError: String?,
    onApply: (AgentSelectionResult) -> Unit,
) {
    val colors = LocalPcColors.current
    val onAccent = if (colors.accent.luminance() > 0.4f) Color.Black else Color.White

    var draftEngine by remember(engine, settings) { mutableStateOf(ChatEngines.normalize(engine)) }
    var draftClaudeModel by remember(settings) { mutableStateOf(settings.claude.model) }
    var draftClaudeEffort by remember(settings) { mutableStateOf(settings.claude.effort) }
    var draftGeminiInstance by remember(settings) { mutableStateOf(settings.gemini.instanceId) }
    var draftGeminiModel by remember(settings) { mutableStateOf(settings.gemini.model) }
    var draftGeminiEffort by remember(settings) { mutableStateOf(settings.gemini.thoughtLevel) }

    val expanded = remember(engine, settings) {
        mutableStateMapOf<String, Boolean>().apply {
            val activeKey = if (ChatEngines.normalize(engine) == ChatEngines.CLAUDE) {
                "claude"
            } else {
                "gemini:${settings.gemini.instanceId}"
            }
            put(activeKey, true)
        }
    }

    val efforts = if (draftEngine == ChatEngines.CLAUDE) settings.claude.efforts else settings.gemini.efforts
    val activeEffort = if (draftEngine == ChatEngines.CLAUDE) draftClaudeEffort else draftGeminiEffort

    val hasChanges = draftEngine != ChatEngines.normalize(engine) ||
        (draftEngine == ChatEngines.CLAUDE &&
            (draftClaudeModel != settings.claude.model || draftClaudeEffort != settings.claude.effort)) ||
        (draftEngine == ChatEngines.GEMINI &&
            (draftGeminiInstance != settings.gemini.instanceId ||
                draftGeminiModel != settings.gemini.model ||
                draftGeminiEffort != settings.gemini.thoughtLevel))

    Column(modifier = Modifier.fillMaxWidth()) {
        // Scrollable provider cards
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            // Claude provider card
            val claudeOpen = expanded["claude"] == true
            val claudeActive = draftEngine == ChatEngines.CLAUDE
            ProviderCard(
                isClaude = true,
                title = "Claude",
                subtitle = settings.claude.account,
                badgeText = if (claudeActive) "Active" else null,
                badgeAccent = true,
                active = claudeActive,
                disabled = false,
                expanded = claudeOpen,
                onToggle = { expanded["claude"] = !claudeOpen },
            ) {
                settings.claude.models.forEachIndexed { idx, model ->
                    val selected = draftEngine == ChatEngines.CLAUDE && draftClaudeModel == model.id
                    if (idx > 0) {
                        HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                    }
                    ModelOptionRow(
                        label = model.label,
                        selected = selected,
                        disabled = false,
                        onClick = {
                            draftEngine = ChatEngines.CLAUDE
                            draftClaudeModel = model.id
                        },
                    )
                }
            }

            // Dynamic Antigravity / Gemini instance cards
            for (inst in settings.gemini.instances) {
                val key = "gemini:${inst.id}"
                val isOpen = expanded[key] == true
                val isSelectedProvider = draftEngine == ChatEngines.GEMINI && draftGeminiInstance == inst.id
                val disabled = !inst.signedIn
                ProviderCard(
                    isClaude = false,
                    title = inst.label,
                    subtitle = inst.email ?: if (disabled) "Not signed in" else null,
                    badgeText = when {
                        disabled -> "Signed out"
                        isSelectedProvider -> "Active"
                        else -> null
                    },
                    badgeAccent = !disabled && isSelectedProvider,
                    active = isSelectedProvider,
                    disabled = disabled,
                    expanded = isOpen,
                    onToggle = { expanded[key] = !isOpen },
                ) {
                    settings.gemini.models.forEachIndexed { idx, model ->
                        val selected = draftEngine == ChatEngines.GEMINI &&
                            draftGeminiInstance == inst.id &&
                            draftGeminiModel == model.id
                        if (idx > 0) {
                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                        }
                        ModelOptionRow(
                            label = model.label,
                            selected = selected,
                            disabled = disabled,
                            onClick = {
                                draftEngine = ChatEngines.GEMINI
                                draftGeminiInstance = inst.id
                                draftGeminiModel = model.id
                            },
                        )
                    }
                }
            }
        }

        // Effort segmented selector
        if (efforts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    text = "Effort",
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
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
                                .background(if (selected) colors.accent else Color.Transparent)
                                .clickable {
                                    if (draftEngine == ChatEngines.CLAUDE) {
                                        draftClaudeEffort = eff
                                    } else {
                                        draftGeminiEffort = eff
                                    }
                                }
                                .padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = ChatLogic.shortEffortLabel(eff),
                                color = if (selected) onAccent else colors.textDim,
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

        if (!applyError.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                PickerErrorBanner(title = applyError, detail = null)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
            PickerPrimaryButton(
                label = "Apply",
                enabled = hasChanges && !applying,
                busy = applying,
                subtle = false,
                onClick = {
                    val patch = if (draftEngine == ChatEngines.CLAUDE) {
                        val modelDiff = draftClaudeModel.takeIf { it.isNotEmpty() && it != settings.claude.model }
                        val effortDiff = draftClaudeEffort.takeIf { it.isNotEmpty() && it != settings.claude.effort }
                        if (modelDiff != null || effortDiff != null) {
                            ChatAgentSettingsPatch(claude = ClaudeSettingsPatch(model = modelDiff, effort = effortDiff))
                        } else {
                            ChatAgentSettingsPatch()
                        }
                    } else {
                        val instDiff = draftGeminiInstance.takeIf { it.isNotEmpty() && it != settings.gemini.instanceId }
                        val modelDiff = draftGeminiModel.takeIf { it.isNotEmpty() && it != settings.gemini.model }
                        val effortDiff = draftGeminiEffort.takeIf { it.isNotEmpty() && it != settings.gemini.thoughtLevel }
                        if (instDiff != null || modelDiff != null || effortDiff != null) {
                            ChatAgentSettingsPatch(
                                gemini = GeminiSettingsPatch(
                                    instanceId = instDiff,
                                    model = modelDiff,
                                    thoughtLevel = effortDiff,
                                )
                            )
                        } else {
                            ChatAgentSettingsPatch()
                        }
                    }
                    onApply(AgentSelectionResult(engine = draftEngine, patch = patch))
                },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    isClaude: Boolean,
    title: String,
    subtitle: String?,
    badgeText: String?,
    badgeAccent: Boolean,
    active: Boolean,
    disabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (disabled) 0.6f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            ProviderBrandIcon(
                isClaude = isClaude,
                tint = if (active) colors.accent else colors.textDim,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.textFaint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!badgeText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (badgeAccent) colors.accentSoft else colors.surfaceRaised)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badgeText,
                        color = if (badgeAccent) colors.accent else colors.textDim,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                content()
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    label: String,
    selected: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .alpha(if (disabled) 0.4f else 1f)
                .border(
                    width = 1.5.dp,
                    color = if (selected) colors.accent else colors.hairline,
                    shape = CircleShape,
                ),
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
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
private fun PickerPrimaryButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    subtle: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val onAccent = if (colors.accent.luminance() > 0.4f) Color.Black else Color.White
    val bg = if (subtle) colors.surfaceRaised else colors.accent
    val fg = if (subtle) colors.text else onAccent

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled && !busy, onClick = onClick),
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = fg,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = label,
                color = fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
