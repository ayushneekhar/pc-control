package com.neekhar.pccontrol.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.ui.PcBottomSheet
import com.neekhar.pccontrol.core.ui.PcSectionLabel
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

    PcBottomSheet(
        onDismissRequest = onDismiss,
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Header: title (+ subtle saving spinner) and Settings gear icon only (swipe/scrim dismisses)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Model & effort",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (applying) {
                        CircularProgressIndicator(
                            color = colors.textDim,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (onOpenAgentSettings != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable {
                                onDismiss()
                                onOpenAgentSettings()
                            },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised),
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
                        PickerRetryButton(onClick = onRetryLoad)
                    }
                }

                settings != null -> {
                    PickerBody(
                        engine = engine,
                        settings = settings,
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
    applyError: String?,
    onApply: (AgentSelectionResult) -> Unit,
) {
    val colors = LocalPcColors.current
    val haptic = LocalHapticFeedback.current

    val activeEngine = ChatEngines.normalize(engine)
    val isClaudeActive = activeEngine == ChatEngines.CLAUDE
    val models = if (isClaudeActive) settings.claude.models else settings.gemini.models
    val activeModelId = if (isClaudeActive) settings.claude.model else settings.gemini.model
    val efforts = if (isClaudeActive) settings.claude.efforts else settings.gemini.efforts
    val activeEffort = if (isClaudeActive) settings.claude.effort else settings.gemini.thoughtLevel

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        // Grouped Account card (labels only — no email addresses)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PcSectionLabel(label = "Account")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.bg)
                    .border(0.5.dp, colors.hairline, RoundedCornerShape(16.dp)),
            ) {
                AccountOptionRow(
                    isClaude = true,
                    label = "Claude",
                    selected = isClaudeActive,
                    disabled = false,
                    onClick = {
                        if (!isClaudeActive) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onApply(
                                AgentSelectionResult(
                                    engine = ChatEngines.CLAUDE,
                                    patch = ChatAgentSettingsPatch(),
                                ),
                            )
                        }
                    },
                )

                for (inst in settings.gemini.instances) {
                    val isSelected = !isClaudeActive && settings.gemini.instanceId == inst.id
                    val disabled = !inst.signedIn
                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                    AccountOptionRow(
                        isClaude = false,
                        label = inst.label,
                        selected = isSelected,
                        disabled = disabled,
                        onClick = {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val instDiff = inst.id.takeIf { it.isNotEmpty() && it != settings.gemini.instanceId }
                                val patch = if (instDiff != null) {
                                    ChatAgentSettingsPatch(
                                        gemini = GeminiSettingsPatch(instanceId = instDiff),
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
                        },
                    )
                }
            }
        }

        // Grouped Model card for the active provider
        if (models.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PcSectionLabel(label = "Model")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.bg)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(16.dp)),
                ) {
                    models.forEachIndexed { idx, model ->
                        val selected = model.id == activeModelId
                        if (idx > 0) {
                            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                        }
                        ModelOptionRow(
                            label = model.label,
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val patch = if (isClaudeActive) {
                                        ChatAgentSettingsPatch(
                                            claude = ClaudeSettingsPatch(model = model.id),
                                        )
                                    } else {
                                        ChatAgentSettingsPatch(
                                            gemini = GeminiSettingsPatch(model = model.id),
                                        )
                                    }
                                    onApply(
                                        AgentSelectionResult(
                                            engine = activeEngine,
                                            patch = patch,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // Effort segmented selector (selected segment uses surfaceRaised, instant apply)
        if (efforts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PcSectionLabel(label = "Effort")
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
                                .height(38.dp)
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

        if (!applyError.isNullOrBlank()) {
            PickerErrorBanner(title = applyError, detail = null)
        }
    }
}

@Composable
private fun AccountOptionRow(
    isClaude: Boolean,
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
            .alpha(if (disabled) 0.5f else 1f)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        ProviderBrandIcon(
            isClaude = isClaude,
            tint = if (selected) colors.accent else colors.textDim,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = if (selected) colors.text else colors.textDim,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (disabled) {
            Text(
                text = "Signed out",
                color = colors.textFaint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
            )
        } else {
            RadioCheckmark(selected = selected)
        }
    }
}

@Composable
private fun ModelOptionRow(
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
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.text else colors.textDim,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RadioCheckmark(selected = selected)
    }
}

@Composable
private fun RadioCheckmark(selected: Boolean) {
    val colors = LocalPcColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
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
                modifier = Modifier.size(13.dp),
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
