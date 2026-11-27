package com.neekhar.pccontrol.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.home.PcFormatters

@Immutable
data class ServicesUiState(
    val health: HealthResponse? = null,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val filter: ServiceGroup = ServiceGroup.ALL,
    val catCamEnabled: Boolean = true,
) {
    val summaryTone: PcTone
        get() {
            val probs = health?.problems.orEmpty()
            return when {
                probs.any { it.severity == "bad" } -> PcTone.Bad
                probs.isNotEmpty() -> PcTone.Warn
                else -> PcTone.Ok
            }
        }

    val statusText: String?
        get() = health?.takeIf { it.ts > 0L }?.let { "Checked ${PcFormatters.formatClock(it.ts)}" }
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesTab(
    state: ServicesUiState,
    onRefresh: () -> Unit,
    onSelectFilter: (ServiceGroup) -> Unit,
    onOpenServiceDetail: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    topPadding: Dp = 4.dp,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val h = state.health
    val pullState = rememberPullToRefreshState()

    val effectiveFilter = if (!state.catCamEnabled && state.filter == ServiceGroup.CAMS) {
        ServiceGroup.ALL
    } else {
        state.filter
    }

    val allUnits = h?.services?.units.orEmpty()
    val shownUnits = remember(allUnits, effectiveFilter, state.catCamEnabled) {
        ServiceHelpers.sortAndFilter(allUnits, effectiveFilter, state.catCamEnabled)
    }

    val filterGroups = remember(state.catCamEnabled) {
        buildList {
            add(ServiceGroup.ALL)
            add(ServiceGroup.MEDIA)
            if (state.catCamEnabled) {
                add(ServiceGroup.CAMS)
            }
            add(ServiceGroup.AGENTS)
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.isRefreshing,
                containerColor = colors.surfaceRaised,
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (topPadding > 4.dp) topPadding else 0.dp),
            )
        },
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PcDimens.ScreenPad,
                end = PcDimens.ScreenPad,
                top = topPadding,
                bottom = PcDimens.Space8,
            ),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space6),
        ) {
            if (state.error != null && h == null) {
                item(key = "error") {
                    PcErrorBanner(
                        title = "Can't reach the laptop",
                        message = state.error,
                        tone = PcTone.Bad,
                    )
                }
            }

            if (h == null) {
                if (state.error == null) {
                    item(key = "loading") {
                        PcCard {
                            Text(
                                text = "Checking services…",
                                color = colors.textDim,
                                style = type.body,
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            // 1. Summary Card with native Canvas status ring
            item(key = "summary") {
                val problems = h.problems
                val effectiveCounts = h.services.counts ?: allUnits.takeIf { it.isNotEmpty() }?.let { units ->
                    ServiceCounts(
                        running = units.count { it.active == "active" },
                        failed = units.count { it.active == "failed" },
                        inactive = units.count { it.active == "inactive" },
                        other = units.count { it.active !in setOf("active", "failed", "inactive") },
                    )
                }
                val runningCount = effectiveCounts?.running ?: allUnits.count { it.active == "active" }
                val totalCount = allUnits.size.coerceAtLeast(runningCount)
                val summaryCopy = remember(runningCount, effectiveCounts, problems.size) {
                    ServiceHelpers.servicesSummaryCopy(
                        runningCount = runningCount,
                        counts = effectiveCounts,
                        problemCount = problems.size,
                    )
                }
                val ringColor = when (state.summaryTone) {
                    PcTone.Bad -> colors.bad
                    PcTone.Warn -> colors.warn
                    else -> colors.ok
                }

                PcCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ServicesStatusRing(
                            runningCount = runningCount,
                            totalCount = totalCount,
                            problemCount = problems.size,
                            ringColor = ringColor,
                            trackColor = vitalsProgressTrackColor(),
                            okColor = colors.ok,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = summaryCopy.headline,
                                color = colors.text,
                                style = type.cardTitle.tabular().copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!summaryCopy.detail.isNullOrBlank()) {
                                Text(
                                    text = summaryCopy.detail,
                                    color = colors.textDim,
                                    style = type.caption.tabular(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (problems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            problems.forEach { prob ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .let { m ->
                                            val targetUnit = prob.unit
                                            if (!targetUnit.isNullOrBlank()) {
                                                m.clickable { onOpenServiceDetail(targetUnit) }
                                            } else {
                                                m
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = prob.title,
                                        color = if (prob.severity == "bad") colors.bad else colors.warn,
                                        style = type.body.copy(fontWeight = FontWeight.Medium),
                                    )
                                    if (!prob.detail.isNullOrBlank()) {
                                        Text(
                                            text = prob.detail,
                                            color = colors.textDim,
                                            style = type.caption,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Services Filter Chips + List
            item(key = "services_section") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (h.services.available && allUnits.isNotEmpty()) {
                        ServiceFilterChipRow(
                            groups = filterGroups,
                            selected = effectiveFilter,
                            onSelect = onSelectFilter,
                        )
                    }

                    if (!h.services.available) {
                        UnavailableCard(label = "Services", error = h.services.error)
                    } else {
                        PcListCard {
                            if (shownUnits.isEmpty()) {
                                Text(
                                    text = "No services in this group.",
                                    color = colors.textFaint,
                                    style = type.caption,
                                    modifier = Modifier.padding(PcDimens.Space4),
                                )
                            } else {
                                shownUnits.forEachIndexed { idx, svc ->
                                    val tone = when (ServiceHelpers.tone(svc)) {
                                        ServiceTone.OK -> PcTone.Ok
                                        ServiceTone.BAD -> PcTone.Bad
                                        ServiceTone.WARN -> PcTone.Warn
                                        ServiceTone.DIM -> PcTone.Faint
                                    }
                                    val trailing = if (svc.active == "active") {
                                        PcFormatters.formatServiceSince(svc.since)
                                    } else {
                                        ServiceHelpers.statusLabel(svc)
                                    }
                                    PcListRow(
                                        title = ServiceHelpers.friendlyName(svc.unit),
                                        subtitle = svc.description.takeIf { it.isNotBlank() },
                                        leading = { PcStatusDot(tone = tone) },
                                        trailing = trailing.takeIf { it.isNotBlank() },
                                        showChevron = true,
                                        showDivider = idx < shownUnits.lastIndex,
                                        onClick = { onOpenServiceDetail(svc.unit) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Jellyfin Active Streams
            item(key = "jellyfin_streams") {
                JellyfinHealthSection(jellyfin = h.jellyfin)
            }
        }
    }
}

@Composable
private fun ServiceFilterChipRow(
    groups: List<ServiceGroup>,
    selected: ServiceGroup,
    onSelect: (ServiceGroup) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group ->
            val isSelected = group == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accentSoft else colors.surface)
                    .border(
                        width = if (isSelected) 1.dp else 0.5.dp,
                        color = if (isSelected) colors.accent else colors.hairline,
                        shape = CircleShape,
                    )
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onSelect(group) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = group.label,
                    color = if (isSelected) colors.accent else colors.textDim,
                    style = type.caption.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UnavailableCard(label: String, error: String?) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    PcCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$label unavailable",
                    color = colors.text,
                    style = type.cardTitle,
                )
                Text(
                    text = error?.takeIf { it.isNotBlank() } ?: "No answer from the laptop",
                    color = colors.textFaint,
                    style = type.caption,
                )
            }
        }
    }
}

@Composable
private fun JellyfinHealthSection(jellyfin: HealthJellyfin) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val trackColor = vitalsProgressTrackColor()
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        PcSectionLabel(label = "Jellyfin")
        when {
            !jellyfin.available -> UnavailableCard(label = "Jellyfin", error = jellyfin.error)
            jellyfin.streams.isEmpty() -> PcCard {
                Text(
                    text = "No one watching.",
                    color = colors.textFaint,
                    style = type.caption,
                )
            }
            else -> PcCard {
                jellyfin.streams.forEachIndexed { idx, stream ->
                    if (idx > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = colors.hairline,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = colors.textDim,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stream.user,
                                color = colors.text,
                                style = type.body.copy(fontWeight = FontWeight.Medium),
                            )
                            PcPill(
                                label = when (stream.method) {
                                    "transcode" -> "Transcode"
                                    "direct_stream" -> "Direct stream"
                                    else -> "Direct play"
                                },
                                tone = if (stream.method == "transcode") PcTone.Warn else PcTone.Ok,
                            )
                            if (stream.paused) {
                                PcPill(label = "Paused", tone = PcTone.Dim)
                            }
                        }
                        Text(
                            text = stream.title,
                            color = colors.text,
                            style = type.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!stream.subtitle.isNullOrBlank()) {
                            Text(
                                text = stream.subtitle,
                                color = colors.textFaint,
                                style = type.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (stream.progress != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            VitalsProgressBar(
                                percent = stream.progress * 100.0,
                                trackColor = trackColor,
                                fillColor = colors.textFaint,
                                height = 4.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}
