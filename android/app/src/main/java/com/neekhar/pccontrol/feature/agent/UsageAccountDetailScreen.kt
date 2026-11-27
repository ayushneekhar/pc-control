package com.neekhar.pccontrol.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageAccountDetailScreen(
    viewModel: AgentSettingsViewModel,
    accountId: String,
    initialWindow: UsageWindowKey = UsageWindowKey.FIVE_HOUR,
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        if (state.usage == null) {
            viewModel.loadUsage(force = false)
        }
        while (true) {
            delay(5_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val summary = remember(state.usage, accountId, nowMs) {
        state.usage?.accounts?.let { accounts ->
            UsageSummary.getAccountDetailSummary(accounts, accountId, Instant.ofEpochMilli(nowMs))
        }
    }

    val windows = remember(summary, initialWindow) {
        if (summary == null) emptyList()
        else if (initialWindow == UsageWindowKey.WEEKLY) listOf(summary.weekly, summary.fiveHour)
        else listOf(summary.fiveHour, summary.weekly)
    }

    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    PcScaffold(
        title = "Account",
        statusText = if (summary?.account?.error != null) "Error" else null,
        statusColor = if (summary?.account?.error != null) colors.bad else null,
        onBack = onBack,
        listState = listState,
        applyNavigationBarPadding = false,
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        val pullState = rememberPullToRefreshState()
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing || state.usageLoading,
                onRefresh = { viewModel.loadUsage(force = true) },
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.isRefreshing || state.usageLoading,
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!state.usageError.isNullOrBlank()) {
                        item(key = "err") {
                            PcErrorBanner(message = state.usageError!!)
                        }
                    }

                    if (summary == null) {
                        item(key = "empty") {
                            PcCard {
                                PcEmptyState(
                                    icon = Icons.Rounded.PersonOff,
                                    title = "Account not found",
                                    body = "Pull down to refresh usage data from your PC.",
                                )
                            }
                        }
                    } else {
                        val acc = summary.account
                        val (statusLabel, statusFg, statusBg) = when {
                            !acc.signedIn -> Triple("Not signed in", colors.textFaint, colors.surfaceRaised)
                            !acc.error.isNullOrBlank() -> Triple("Error", colors.bad, colors.badSoft)
                            else -> Triple("Signed in", colors.textDim, colors.surfaceRaised)
                        }

                        item(key = "hero") {
                            PcCard {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        ProviderUsageIcon(
                                            key = summary.providerKey,
                                            tint = colors.textDim,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            text = acc.label,
                                            color = colors.text,
                                            style = type.collapsedTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (summary.poolSize > 1) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(colors.surfaceRaised)
                                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    text = "#${summary.poolIndex} of ${summary.poolSize}",
                                                    color = colors.textDim,
                                                    style = type.micro.tabular(),
                                                )
                                            }
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (!acc.email.isNullOrBlank()) {
                                            Text(text = acc.email, color = colors.text, style = type.body)
                                        }
                                        Text(text = summary.providerSubtitle, color = colors.textDim, style = type.caption)
                                    }

                                    PcPill(text = statusLabel, color = statusFg, backgroundColor = statusBg)
                                }
                            }
                        }

                        if (!acc.error.isNullOrBlank()) {
                            item(key = "acc_error") {
                                PcErrorBanner(message = acc.error)
                            }
                        }

                        items(count = windows.size, key = { "win_${windows[it].windowKey.wireValue}" }) { idx ->
                            WindowDetailCard(detail = windows[idx])
                        }

                        item(key = "sec_info") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PcSectionLabel(label = "Account info")
                                PcCard {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AccountKvRow("Status", statusLabel, valueColor = statusFg)
                                        AccountKvRow("Email", acc.email ?: "—")
                                        AccountKvRow("Instance ID", acc.id, mono = true)
                                        AccountKvRow("Last updated", UsageSummary.formatUpdatedAgo(state.usage?.ts, nowMs))
                                    }
                                }
                            }
                        }
                    }
                }
            }

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
}

@Composable
private fun WindowDetailCard(detail: AccountWindowDetail) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val palette = usageSegmentColors(detail.state, detail.remainingPct)
    val evenPaceText = remember(detail) { formatEvenPaceText(detail) }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = detail.headerTitle,
                    color = colors.text,
                    style = type.cardTitle,
                )
                if (detail.paceStatus != null) {
                    val (fg, bg) = when (detail.paceStatus) {
                        PaceStatus.AHEAD_OF_PACE -> colors.warn to colors.warnSoft
                        PaceStatus.UNDER_PACE, PaceStatus.ON_PACE -> colors.textDim to colors.surfaceRaised
                    }
                    PcPill(text = detail.paceStatus.label, color = fg, backgroundColor = bg)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                when {
                    detail.state == AccountWindowRowState.OK && detail.remainingPct != null -> {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${detail.remainingPct.roundToInt()}%",
                                color = palette.pctFg,
                                style = type.metricHero.copy(fontSize = 32.sp, lineHeight = 38.sp),
                            )
                            Text(
                                text = "left",
                                color = colors.textDim,
                                style = type.body,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                        Text(
                            text = "${(detail.usedPct ?: 0.0).roundToInt()}% used",
                            color = colors.textFaint,
                            style = type.caption.tabular(),
                        )
                    }
                    detail.state == AccountWindowRowState.SIGNED_OUT -> {
                        Text(text = "Not signed in", color = colors.textFaint, style = type.cardTitle)
                    }
                    detail.state == AccountWindowRowState.ERROR -> {
                        Text(text = "Unavailable", color = colors.bad, style = type.cardTitle)
                    }
                    else -> {
                        Text(text = "—", color = colors.textFaint, style = type.cardTitle)
                    }
                }
            }

            UsageProgressBar(
                state = detail.state,
                remainingPct = detail.remainingPct,
                expectedRemainingPct = detail.expectedRemainingPct,
                height = 10.dp,
            )

            if (detail.state == AccountWindowRowState.OK) {
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        !detail.resetDateTimeLabel.isNullOrBlank() -> {
                            val suffix = detail.countdownLabel?.let { " (in $it)" } ?: ""
                            DetailInfoLine(
                                icon = Icons.Rounded.AccessTime,
                                text = "Resets ${detail.resetDateTimeLabel}$suffix",
                                textColor = colors.text,
                            )
                        }
                        detail.isFullIdle -> {
                            DetailInfoLine(
                                icon = Icons.Rounded.CheckCircle,
                                text = "Quota full · no active cooldown",
                                textColor = colors.textDim,
                            )
                        }
                        else -> {
                            DetailInfoLine(
                                icon = Icons.Rounded.AccessTime,
                                text = "Reset time unavailable",
                                textColor = colors.textFaint,
                            )
                        }
                    }

                    if (evenPaceText != null) {
                        DetailInfoLine(icon = Icons.Rounded.Speed, text = evenPaceText)
                    }

                    if (detail.poolRestorePct != null) {
                        DetailInfoLine(
                            icon = Icons.Rounded.Refresh,
                            text = "Restores +${detail.poolRestorePct}% of the ${detail.activePoolSize}-account pool on reset",
                        )
                    }

                    if (detail.poolSharePct != null) {
                        DetailInfoLine(
                            icon = Icons.Rounded.DonutLarge,
                            text = "Contributes ${detail.poolSharePct}% of current pool",
                        )
                    }

                    val proj = detail.projection
                    if (proj != null) {
                        val (icon, tint) = when (proj) {
                            is AccountWindowProjection.Exhausted -> Icons.Rounded.ErrorOutline to colors.bad
                            is AccountWindowProjection.RunsOut -> Icons.Rounded.Warning to colors.warn
                            is AccountWindowProjection.Headroom -> Icons.AutoMirrored.Rounded.TrendingUp to colors.textDim
                        }
                        DetailInfoLine(
                            icon = icon,
                            text = proj.label,
                            textColor = tint,
                            iconColor = tint,
                        )
                    }
                }
            }
        }
    }
}

private fun formatEvenPaceText(detail: AccountWindowDetail): String? {
    val expected = detail.evenPaceExpectedLeftPct ?: return null
    val base = "Even pace: $expected% left by now"
    val delta = detail.paceDeltaPct
    if (delta == null || (detail.usedPct ?: 0.0) <= 0.0) return base
    return when {
        delta > 0 -> "$base (+$delta% buffer)"
        delta < 0 -> "$base ($delta% ahead of pace)"
        else -> "$base (right on pace)"
    }
}

@Composable
private fun DetailInfoLine(
    icon: ImageVector,
    text: String,
    textColor: Color = LocalPcColors.current.textDim,
    iconColor: Color = LocalPcColors.current.textFaint,
) {
    val type = LocalPcType.current
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp),
        )
        Text(
            text = text,
            color = textColor,
            style = type.caption.tabular(),
        )
    }
}

@Composable
private fun AccountKvRow(
    label: String,
    value: String,
    valueColor: Color = LocalPcColors.current.text,
    mono: Boolean = false,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = colors.textDim, style = type.caption)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor,
            style = if (mono) {
                MonoTextStyle.tabular().copy(fontWeight = FontWeight.Medium)
            } else {
                type.caption.tabular().copy(fontWeight = FontWeight.Medium)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
