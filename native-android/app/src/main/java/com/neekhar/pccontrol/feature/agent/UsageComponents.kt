package com.neekhar.pccontrol.feature.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.ProviderBrandIcon
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
            fill = colors.bad,
            badgeBg = colors.badSoft,
            badgeFg = colors.bad,
            pctFg = colors.bad,
        )
        remainingPct < 40.0 -> UsageSegmentPalette(
            fill = colors.warn,
            badgeBg = colors.warnSoft,
            badgeFg = colors.warn,
            pctFg = colors.warn,
        )
        else -> UsageSegmentPalette(
            fill = colors.accent,
            badgeBg = colors.surfaceRaised,
            badgeFg = colors.textDim,
            pctFg = colors.text,
        )
    }
}

@Composable
fun ProviderUsageIcon(
    key: UsageProviderKey,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (key) {
        UsageProviderKey.CLAUDE -> ProviderBrandIcon(
            isClaude = true,
            tint = tint,
            modifier = modifier,
        )
        UsageProviderKey.GEMINI -> ProviderBrandIcon(
            isClaude = false,
            tint = tint,
            modifier = modifier,
        )
        UsageProviderKey.OPENCODE -> Icon(
            imageVector = Icons.Filled.Code,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

/**
 * Sleek thin progress bar (4.dp default) with a vertical pace tick mark.
 */
@Composable
fun UsageProgressBar(
    state: AccountWindowRowState,
    remainingPct: Double?,
    expectedRemainingPct: Double?,
    height: Dp = 4.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val palette = usageSegmentColors(state, remainingPct)
    val fillFraction = if (state == AccountWindowRowState.OK && remainingPct != null) {
        (remainingPct / 100.0).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val trackBg = colors.surfaceRaised
    val tickColor = colors.text
    val canvasHeight = (height + 6.dp).coerceAtLeast(10.dp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(canvasHeight),
    ) {
        val w = size.width
        val totalH = size.height
        val barH = height.toPx().coerceAtMost(totalH)
        val barTop = (totalH - barH) / 2f
        val radius = CornerRadius(barH / 2f, barH / 2f)

        drawRoundRect(
            color = trackBg,
            topLeft = Offset(0f, barTop),
            size = Size(w, barH),
            cornerRadius = radius,
        )

        val fillWidth = (w * fillFraction).coerceAtLeast(0f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = palette.fill,
                topLeft = Offset(0f, barTop),
                size = Size(fillWidth.coerceAtLeast(barH.coerceAtMost(w)), barH),
                cornerRadius = radius,
            )
        }

        if (expectedRemainingPct != null) {
            val tickX = w * (expectedRemainingPct / 100.0).toFloat().coerceIn(0.02f, 0.98f)
            drawLine(
                color = tickColor,
                start = Offset(tickX, 0f),
                end = Offset(tickX, totalH),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
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

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    ProviderUsageIcon(
                        key = group.key,
                        tint = colors.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = group.name,
                        color = colors.textDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                when {
                    group.key == UsageProviderKey.OPENCODE -> {
                        group.accounts.forEach { acc ->
                            OpenCodeUsageCard(acc = acc)
                        }
                    }
                    group.accounts.size == 1 -> {
                        SingleAccountUsageCard(
                            account = group.accounts.first(),
                            onSelectAccount = onSelectAccount,
                        )
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MultiAccountWindowCard(
                                accounts = group.accounts,
                                windowKey = UsageWindowKey.FIVE_HOUR,
                                onSelectAccount = onSelectAccount,
                            )
                            MultiAccountWindowCard(
                                accounts = group.accounts,
                                windowKey = UsageWindowKey.WEEKLY,
                                onSelectAccount = onSelectAccount,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean single-account usage card (e.g. Claude):
 * Combines 5-hour and Weekly windows into one PcListCard, each with a single
 * "98% left · resets 1h 9m" header over a sleek 4.dp progress bar with a pace tick.
 */
@Composable
private fun SingleAccountUsageCard(
    account: AgentUsageAccount,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val fiveHourSummary = UsageSummary.summarizeWindowGroup(listOf(account), UsageWindowKey.FIVE_HOUR)
    val weeklySummary = UsageSummary.summarizeWindowGroup(listOf(account), UsageWindowKey.WEEKLY)
    val windows = listOf(fiveHourSummary, weeklySummary)

    PcListCard {
        windows.forEachIndexed { idx, summary ->
            if (idx > 0) {
                HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
            }
            val row = summary.rows.first()
            val palette = usageSegmentColors(row.state, row.remainingPct)
            val metaAnnotated = buildAnnotatedString {
                when (row.state) {
                    AccountWindowRowState.SIGNED_OUT -> {
                        withStyle(SpanStyle(color = colors.textFaint)) { append("Not signed in") }
                    }
                    AccountWindowRowState.ERROR -> {
                        withStyle(SpanStyle(color = colors.bad, fontWeight = FontWeight.Medium)) { append("Error") }
                    }
                    AccountWindowRowState.MISSING -> {
                        withStyle(SpanStyle(color = colors.textFaint)) { append("—") }
                    }
                    AccountWindowRowState.OK -> {
                        val pct = (row.remainingPct ?: 0.0).roundToInt()
                        withStyle(SpanStyle(color = palette.pctFg, fontWeight = FontWeight.SemiBold)) {
                            append("$pct% left")
                        }
                        if (!row.countdownLabel.isNullOrBlank()) {
                            withStyle(SpanStyle(color = colors.textDim)) {
                                append(" · resets ${row.countdownLabel}")
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectAccount(account, summary.windowKey) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = summary.title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.paceStatus == PaceStatus.AHEAD_OF_PACE) {
                        Text(
                            text = "· ${summary.paceStatus.label}",
                            color = colors.warn,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = metaAnnotated,
                        style = TabularNumStyle.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }

                UsageProgressBar(
                    state = row.state,
                    remainingPct = row.remainingPct,
                    expectedRemainingPct = row.expectedRemainingPct,
                    height = 4.dp,
                )
            }
        }

        if (!account.error.isNullOrBlank()) {
            HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
            Text(
                text = account.error,
                color = colors.bad,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MultiAccountWindowCard(
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
            // Compact summary header
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.Baseline,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = summary.title,
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = summary.aggregateRemainingPct?.let { "$it% left" } ?: "—",
                            color = colors.text,
                            style = TabularNumStyle.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                    if (summary.paceStatus != null) {
                        Text(
                            text = summary.paceStatus.label,
                            color = if (summary.paceStatus == PaceStatus.AHEAD_OF_PACE) colors.warn else colors.textFaint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (summary.replenishment != null) {
                    Text(
                        text = "+${summary.replenishment.gainPct}% in ${summary.replenishment.durationLabel}",
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 12.sp),
                    )
                }
            }

            // Sleek grouped progress bars across accounts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.rows.forEach { row ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSelectAccount(row.account, windowKey) }
                            .padding(vertical = 2.dp),
                    ) {
                        UsageProgressBar(
                            state = row.state,
                            remainingPct = row.remainingPct,
                            expectedRemainingPct = row.expectedRemainingPct,
                            height = 5.dp,
                        )
                    }
                }
            }

            // Per-account compact scannable rows
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                summary.rows.forEach { row ->
                    val palette = usageSegmentColors(row.state, row.remainingPct)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectAccount(row.account, windowKey) }
                            .padding(vertical = 5.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(palette.badgeBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = row.index.toString(),
                                color = palette.badgeFg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
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
                                    Text(text = "Not signed in", color = colors.textFaint, fontSize = 12.sp)
                                }
                                AccountWindowRowState.ERROR -> {
                                    Text(text = "Error", color = colors.bad, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                AccountWindowRowState.MISSING -> {
                                    Text(text = "—", color = colors.textFaint, fontSize = 13.sp)
                                }
                                AccountWindowRowState.OK -> {
                                    Text(
                                        text = "${(row.remainingPct ?: 0.0).roundToInt()}%",
                                        color = palette.pctFg,
                                        style = TabularNumStyle.copy(
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                    if (!row.countdownLabel.isNullOrBlank()) {
                                        Text(
                                            text = row.countdownLabel,
                                            color = colors.textFaint,
                                            style = TabularNumStyle.copy(fontSize = 12.sp),
                                        )
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
                    HorizontalDivider(color = colors.hairline, thickness = 0.5.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                fontWeight = FontWeight.Medium,
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        .width(108.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceRaised),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(segments) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceRaised),
                    )
                }
            }
        }
    }
}
