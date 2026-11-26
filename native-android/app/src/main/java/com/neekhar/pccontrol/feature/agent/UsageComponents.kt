package com.neekhar.pccontrol.feature.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcBrandIcons
import com.neekhar.pccontrol.core.ui.PcCard
import kotlin.math.roundToInt

data class UsageSegmentPalette(
    val fill: Color,
    val badgeBg: Color,
    val badgeFg: Color,
    val pctFg: Color,
)

@Composable
fun usageSegmentColors(
    state: AccountWindowRowState,
    remainingPct: Double?,
): UsageSegmentPalette {
    val colors = LocalPcColors.current
    if (state != AccountWindowRowState.OK || remainingPct == null) {
        return UsageSegmentPalette(
            fill = Color.Transparent,
            badgeBg = colors.surfaceRaised,
            badgeFg = colors.textFaint,
            pctFg = colors.textFaint,
        )
    }
    return when {
        remainingPct < 15.0 -> UsageSegmentPalette(
            fill = colors.bad.copy(alpha = 0.28f),
            badgeBg = colors.badSoft,
            badgeFg = colors.bad,
            pctFg = colors.bad,
        )
        remainingPct < 40.0 -> UsageSegmentPalette(
            fill = colors.warn.copy(alpha = 0.26f),
            badgeBg = colors.warnSoft,
            badgeFg = colors.warn,
            pctFg = colors.warn,
        )
        else -> UsageSegmentPalette(
            fill = colors.ok.copy(alpha = 0.24f),
            badgeBg = colors.okSoft,
            badgeFg = colors.ok,
            pctFg = colors.text,
        )
    }
}

fun providerIcon(key: UsageProviderKey): ImageVector = when (key) {
    UsageProviderKey.CLAUDE -> PcBrandIcons.Claude
    UsageProviderKey.GEMINI -> Icons.Filled.AutoAwesome
    UsageProviderKey.OPENCODE -> Icons.Filled.Code
}

@Composable
fun UsageProgressBar(
    state: AccountWindowRowState,
    remainingPct: Double?,
    expectedRemainingPct: Double?,
    height: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val palette = usageSegmentColors(state, remainingPct)
    val fillFraction = if (state == AccountWindowRowState.OK && remainingPct != null) {
        (remainingPct / 100.0).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val showHatch = state == AccountWindowRowState.OK && fillFraction < 1f
    val hatchColor = colors.text.copy(alpha = 0.08f)
    val trackBg = colors.surfaceRaised

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(trackBg),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fillWidth = w * fillFraction

            if (showHatch) {
                val startX = fillWidth
                var x = startX - h
                val step = 8.dp.toPx()
                val stroke = 1.5.dp.toPx()
                while (x < w + h) {
                    val x1 = x.coerceAtLeast(startX)
                    val y1 = if (x < startX) (startX - x) else 0f
                    val x2 = (x + h).coerceAtMost(w)
                    val y2 = if (x + h > w) (w - x) else h
                    if (x2 > x1) {
                        drawLine(
                            color = hatchColor,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = stroke,
                        )
                    }
                    x += step
                }
            }

            if (fillWidth > 0f) {
                drawRect(
                    color = palette.fill,
                    topLeft = Offset.Zero,
                    size = Size(fillWidth, h),
                )
            }

            if (expectedRemainingPct != null) {
                val tickX = w * (expectedRemainingPct / 100.0).toFloat().coerceIn(0.02f, 0.98f)
                val padY = 2.dp.toPx()
                drawLine(
                    color = colors.text.copy(alpha = 0.9f),
                    start = Offset(tickX, padY),
                    end = Offset(tickX, h - padY),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

@Composable
fun GroupedUsageSection(
    accounts: List<AgentUsageAccount>,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val groups = UsageSummary.groupAccountsByProvider(accounts)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    Icon(
                        imageVector = providerIcon(group.key),
                        contentDescription = null,
                        tint = colors.textDim,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = group.name,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (group.key == UsageProviderKey.OPENCODE) {
                    group.accounts.forEach { acc ->
                        OpenCodeUsageCard(acc = acc)
                    }
                } else {
                    WindowUsageCard(
                        accounts = group.accounts,
                        windowKey = UsageWindowKey.FIVE_HOUR,
                        onSelectAccount = onSelectAccount,
                    )
                    WindowUsageCard(
                        accounts = group.accounts,
                        windowKey = UsageWindowKey.WEEKLY,
                        onSelectAccount = onSelectAccount,
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowUsageCard(
    accounts: List<AgentUsageAccount>,
    windowKey: UsageWindowKey,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val summary = UsageSummary.summarizeWindowGroup(accounts, windowKey)
    val errorRows = if (windowKey == UsageWindowKey.FIVE_HOUR) {
        summary.rows.filter { it.state == AccountWindowRowState.ERROR && !it.account.error.isNullOrBlank() }
    } else {
        emptyList()
    }

    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary.title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.paceStatus != null) {
                        Text(
                            text = summary.paceStatus.label,
                            color = colors.textFaint,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = summary.aggregateRemainingPct?.let { "$it%" } ?: "—",
                        color = colors.text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TabularNumStyle,
                    )
                    if (summary.aggregateRemainingPct != null) {
                        Text(
                            text = "left",
                            color = colors.textDim,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                if (summary.replenishment != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = colors.textFaint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "+${summary.replenishment.gainPct}% in ${summary.replenishment.durationLabel}",
                            color = colors.textFaint,
                            fontSize = 12.sp,
                            style = TabularNumStyle,
                        )
                    }
                }
            }

            // Segmented horizontal bar across accounts in this pool
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.rows.forEach { row ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onSelectAccount(row.account, windowKey) },
                        contentAlignment = Alignment.Center,
                    ) {
                        UsageProgressBar(
                            state = row.state,
                            remainingPct = row.remainingPct,
                            expectedRemainingPct = row.expectedRemainingPct,
                            height = 28.dp,
                        )
                        Text(
                            text = row.index.toString(),
                            color = if (row.state == AccountWindowRowState.OK) colors.text else colors.textFaint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TabularNumStyle,
                        )
                    }
                }
            }

            // Per-account rows
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                summary.rows.forEach { row ->
                    val palette = usageSegmentColors(row.state, row.remainingPct)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectAccount(row.account, windowKey) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(palette.badgeBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = row.index.toString(),
                                color = palette.badgeFg,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TabularNumStyle,
                            )
                        }

                        Text(
                            text = row.account.label,
                            color = if (row.state == AccountWindowRowState.OK) colors.text else colors.textFaint,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            when (row.state) {
                                AccountWindowRowState.SIGNED_OUT -> {
                                    Text(text = "Not signed in", color = colors.textFaint, fontSize = 12.5.sp)
                                }
                                AccountWindowRowState.ERROR -> {
                                    Text(text = "Error", color = colors.bad, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                }
                                AccountWindowRowState.MISSING -> {
                                    Text(text = "—", color = colors.textFaint, fontSize = 13.5.sp)
                                }
                                AccountWindowRowState.OK -> {
                                    Text(
                                        text = "${(row.remainingPct ?: 0.0).roundToInt()}%",
                                        color = palette.pctFg,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TabularNumStyle,
                                    )
                                    if (!row.countdownLabel.isNullOrBlank()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Refresh,
                                                contentDescription = null,
                                                tint = colors.textFaint,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Text(
                                                text = row.countdownLabel,
                                                color = colors.textFaint,
                                                fontSize = 12.sp,
                                                style = TabularNumStyle,
                                            )
                                        }
                                    }
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = colors.textFaint,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            if (errorRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    errorRows.forEach { r ->
                        Text(
                            text = "${r.account.label}: ${r.account.error}",
                            color = colors.bad,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenCodeUsageCard(acc: AgentUsageAccount) {
    val colors = LocalPcColors.current
    val ds = acc.deepseek
    val zen = acc.zen

    PcCard {
        if (!acc.error.isNullOrBlank()) {
            Text(text = acc.error, color = colors.bad, fontSize = 12.5.sp, lineHeight = 18.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "DeepSeek balance", color = colors.textDim, fontSize = 13.5.sp)
                    when {
                        !ds?.error.isNullOrBlank() -> {
                            Text(text = ds!!.error!!, color = colors.bad, fontSize = 12.5.sp, maxLines = 1)
                        }
                        !ds?.formatted.isNullOrBlank() -> {
                            Text(
                                text = "${ds!!.formatted} left",
                                color = colors.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TabularNumStyle,
                            )
                        }
                        else -> Text(text = "—", color = colors.textFaint, fontSize = 13.sp)
                    }
                }

                if (zen?.status == "ok") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceRaised)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Zen (${zen.days ?: 7}d)",
                                color = colors.textDim,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${zen.messages ?: 0} msgs · ${zen.totalCost ?: "$0.00"}",
                                color = colors.text,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TabularNumStyle,
                            )
                        }
                        zen.models?.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = m.model.substringAfter('/'),
                                    color = colors.textFaint,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${m.messages} msgs",
                                    color = colors.textDim,
                                    fontSize = 12.sp,
                                    style = TabularNumStyle,
                                )
                            }
                        }
                    }
                } else if (!zen?.error.isNullOrBlank()) {
                    Text(text = "Zen: ${zen!!.error}", color = colors.textFaint, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun UsageSkeletonCard(segments: Int = 4) {
    val colors = LocalPcColors.current
    PcCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceRaised),
                )
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceRaised),
                )
            }
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceRaised),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(segments) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(colors.surfaceRaised),
                    )
                }
            }
        }
    }
}
