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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.tabular
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
            imageVector = Icons.Rounded.Code,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

/**
 * Readable usage progress bar (8.dp default) with a high-contrast rounded track,
 * clipped quota fill, and a pace tick inset cleanly inside the rounded bar's bounds.
 */
@Composable
fun UsageProgressBar(
    state: AccountWindowRowState,
    remainingPct: Double?,
    expectedRemainingPct: Double?,
    height: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val palette = usageSegmentColors(state, remainingPct)
    val fillFraction = if (state == AccountWindowRowState.OK && remainingPct != null) {
        (remainingPct / 100.0).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val trackBg = if (colors.isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)
    val tickColor = colors.text

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val w = size.width
        val barH = size.height
        val radiusPx = 4.dp.toPx().coerceAtMost(barH / 2f)
        val radius = CornerRadius(radiusPx, radiusPx)

        drawRoundRect(
            color = trackBg,
            topLeft = Offset.Zero,
            size = Size(w, barH),
            cornerRadius = radius,
        )

        val fillWidth = (w * fillFraction).coerceIn(0f, w)
        if (fillWidth > 0f) {
            val trackPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = w,
                        bottom = barH,
                        cornerRadius = radius,
                    ),
                )
            }
            clipPath(trackPath) {
                drawRoundRect(
                    color = palette.fill,
                    topLeft = Offset.Zero,
                    size = Size(fillWidth, barH),
                    cornerRadius = radius,
                )
            }
        }

        if (expectedRemainingPct != null && w > 0f) {
            val strokePx = 2.dp.toPx()
            val halfStroke = strokePx / 2f
            val insetPx = maxOf(4.dp.toPx(), radiusPx + halfStroke).coerceAtMost(w / 2f)
            val rawX = w * (expectedRemainingPct / 100.0).toFloat().coerceIn(0f, 1f)
            val tickX = rawX.coerceIn(insetPx, (w - insetPx).coerceAtLeast(insetPx))
            drawLine(
                color = tickColor,
                start = Offset(tickX, halfStroke),
                end = Offset(tickX, (barH - halfStroke).coerceAtLeast(halfStroke)),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun GroupedUsageSection(
    accounts: List<AgentUsageAccount>,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
    preferredProvider: UsageProviderKey? = null,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val baseGroups = UsageSummary.groupAccountsByProvider(accounts)
    val groups = if (preferredProvider != null) {
        baseGroups.sortedBy { if (it.key == preferredProvider) 0 else 1 }
    } else {
        baseGroups
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                ) {
                    ProviderUsageIcon(
                        key = group.key,
                        tint = colors.textDim,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = group.name,
                        color = colors.textDim,
                        style = type.sectionLabel,
                        modifier = Modifier.weight(1f),
                    )
                    if (group.accounts.size > 1) {
                        Text(
                            text = "${group.accounts.size} accounts",
                            color = colors.textFaint,
                            style = type.micro.tabular(),
                        )
                    }
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
                        UnifiedMultiAccountUsageCard(
                            accounts = group.accounts,
                            onSelectAccount = onSelectAccount,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unified single-account usage card (e.g. Claude):
 * Combines 5-hour and Weekly windows into one PcListCard using the shared quota row layout.
 */
@Composable
private fun SingleAccountUsageCard(
    account: AgentUsageAccount,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val fiveHourSummary = UsageSummary.summarizeWindowGroup(listOf(account), UsageWindowKey.FIVE_HOUR)
    val weeklySummary = UsageSummary.summarizeWindowGroup(listOf(account), UsageWindowKey.WEEKLY)
    val windows = listOf(fiveHourSummary, weeklySummary)

    PcListCard {
        windows.forEachIndexed { idx, summary ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = colors.hairline,
                    thickness = 0.5.dp,
                )
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = summary.title,
                        color = colors.text,
                        style = type.cardTitle,
                    )
                    if (summary.paceStatus == PaceStatus.AHEAD_OF_PACE) {
                        Text(
                            text = "· ${summary.paceStatus.label}",
                            color = colors.warn,
                            style = type.micro,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = metaAnnotated,
                        style = type.caption.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }

                UsageProgressBar(
                    state = row.state,
                    remainingPct = row.remainingPct,
                    expectedRemainingPct = row.expectedRemainingPct,
                    height = 8.dp,
                )
            }
        }

        if (!account.error.isNullOrBlank()) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = colors.hairline,
                thickness = 0.5.dp,
            )
            Text(
                text = account.error,
                color = colors.bad,
                style = type.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Unified multi-account usage card (e.g. Antigravity):
 * Mirrors [SingleAccountUsageCard] with the exact same 5-hour + Weekly quota rows in one
 * PcListCard, followed by a single deduplicated account strip so each account is listed once.
 */
@Composable
private fun UnifiedMultiAccountUsageCard(
    accounts: List<AgentUsageAccount>,
    onSelectAccount: (account: AgentUsageAccount, windowKey: UsageWindowKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val fiveHourSummary = UsageSummary.summarizeWindowGroup(accounts, UsageWindowKey.FIVE_HOUR)
    val weeklySummary = UsageSummary.summarizeWindowGroup(accounts, UsageWindowKey.WEEKLY)
    val windows = listOf(fiveHourSummary, weeklySummary)
    val errorAccounts = accounts.filter { !it.error.isNullOrBlank() }

    PcListCard {
        windows.forEachIndexed { idx, summary ->
            if (idx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = colors.hairline,
                    thickness = 0.5.dp,
                )
            }
            val metaAnnotated = buildAnnotatedString {
                val agg = summary.aggregateRemainingPct
                if (agg != null) {
                    val aggPalette = usageSegmentColors(AccountWindowRowState.OK, agg.toDouble())
                    withStyle(SpanStyle(color = aggPalette.pctFg, fontWeight = FontWeight.SemiBold)) {
                        append("$agg% left")
                    }
                    summary.replenishment?.let { rep ->
                        withStyle(SpanStyle(color = colors.textDim)) {
                            append(" · +${rep.gainPct}% in ${rep.durationLabel}")
                        }
                    }
                } else {
                    withStyle(SpanStyle(color = colors.textFaint)) { append("—") }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = summary.title,
                        color = colors.text,
                        style = type.cardTitle,
                    )
                    if (summary.paceStatus == PaceStatus.AHEAD_OF_PACE) {
                        Text(
                            text = "· ${summary.paceStatus.label}",
                            color = colors.warn,
                            style = type.micro,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = metaAnnotated,
                        style = type.caption.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Segmented 8.dp progress bars across accounts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    summary.rows.forEach { row ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onSelectAccount(row.account, summary.windowKey) }
                                .padding(vertical = 2.dp),
                        ) {
                            UsageProgressBar(
                                state = row.state,
                                remainingPct = row.remainingPct,
                                expectedRemainingPct = row.expectedRemainingPct,
                                height = 8.dp,
                            )
                        }
                    }
                }
            }
        }

        // Single deduplicated account list showing both 5h and 7d percentages per account
        fiveHourSummary.rows.forEachIndexed { idx, fiveRow ->
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = colors.hairline,
                thickness = 0.5.dp,
            )
            val weekRow = weeklySummary.rows.getOrNull(idx)
            val palette = usageSegmentColors(fiveRow.state, fiveRow.remainingPct)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectAccount(fiveRow.account, UsageWindowKey.FIVE_HOUR) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = fiveRow.index.toString(),
                        color = palette.badgeFg,
                        style = type.micro.tabular(),
                    )
                }

                Text(
                    text = fiveRow.account.label,
                    color = if (fiveRow.state == AccountWindowRowState.OK) colors.text else colors.textFaint,
                    style = type.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (fiveRow.state) {
                        AccountWindowRowState.SIGNED_OUT -> {
                            Text(text = "Not signed in", color = colors.textFaint, style = type.caption)
                        }
                        AccountWindowRowState.ERROR -> {
                            Text(
                                text = "Error",
                                color = colors.bad,
                                style = type.caption.copy(fontWeight = FontWeight.Medium),
                            )
                        }
                        AccountWindowRowState.MISSING -> {
                            Text(text = "—", color = colors.textFaint, style = type.caption)
                        }
                        AccountWindowRowState.OK -> {
                            val fivePct = "${(fiveRow.remainingPct ?: 0.0).roundToInt()}%"
                            val weekPct = if (weekRow?.state == AccountWindowRowState.OK && weekRow.remainingPct != null) {
                                "${weekRow.remainingPct.roundToInt()}%"
                            } else {
                                null
                            }
                            Text(
                                text = fivePct,
                                color = palette.pctFg,
                                style = type.caption.tabular().copy(fontWeight = FontWeight.SemiBold),
                            )
                            if (weekPct != null) {
                                Text(
                                    text = "· $weekPct 7d",
                                    color = colors.textFaint,
                                    style = type.caption.tabular(),
                                )
                            } else if (!fiveRow.countdownLabel.isNullOrBlank()) {
                                Text(
                                    text = fiveRow.countdownLabel,
                                    color = colors.textFaint,
                                    style = type.caption.tabular(),
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (errorAccounts.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = colors.hairline,
                thickness = 0.5.dp,
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                errorAccounts.forEach { acc ->
                    Text(
                        text = "${acc.label}: ${acc.error}",
                        color = colors.bad,
                        style = type.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenCodeUsageCard(acc: AgentUsageAccount) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val ds = acc.deepseek
    val zen = acc.zen

    PcListCard {
        if (!acc.error.isNullOrBlank()) {
            Text(
                text = acc.error,
                color = colors.bad,
                style = type.caption,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            // Unified quota row 1: DeepSeek balance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DeepSeek balance",
                    color = colors.text,
                    style = type.cardTitle,
                )
                when {
                    !ds?.error.isNullOrBlank() -> {
                        Text(
                            text = ds!!.error!!,
                            color = colors.bad,
                            style = type.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    !ds?.formatted.isNullOrBlank() -> {
                        Text(
                            text = "${ds!!.formatted} left",
                            color = colors.text,
                            style = type.body.tabular().copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    else -> Text(text = "—", color = colors.textFaint, style = type.caption)
                }
            }

            // Unified quota row 2: Zen summary
            if (zen?.status == "ok") {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = colors.hairline,
                    thickness = 0.5.dp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Zen (${zen.days ?: 7}d)",
                            color = colors.text,
                            style = type.cardTitle,
                        )
                        Text(
                            text = "${zen.messages ?: 0} msgs · ${zen.totalCost ?: "$0.00"}",
                            color = colors.textDim,
                            style = type.caption.tabular().copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    zen.models?.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = m.model.substringAfter('/'),
                                color = colors.textFaint,
                                style = type.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${m.messages} msgs",
                                color = colors.textDim,
                                style = type.caption.tabular(),
                            )
                        }
                    }
                }
            } else if (!zen?.error.isNullOrBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = colors.hairline,
                    thickness = 0.5.dp,
                )
                Text(
                    text = "Zen: ${zen!!.error}",
                    color = colors.textFaint,
                    style = type.caption,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun UsageSkeletonCard(segments: Int = 4) {
    val colors = LocalPcColors.current
    val trackBg = if (colors.isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)
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
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(trackBg),
                    )
                }
            }
        }
    }
}
