package com.neekhar.pccontrol.feature.system

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.home.PcFormatters
import kotlin.math.max
import kotlin.math.roundToInt

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesTab(
    state: ServicesUiState,
    onRefresh: () -> Unit,
    onSelectFilter: (ServiceGroup) -> Unit,
    onOpenServiceDetail: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
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

    val filterOptions = remember(state.catCamEnabled) {
        buildList {
            add(PcSegmentOption(ServiceGroup.ALL, ServiceGroup.ALL.label))
            add(PcSegmentOption(ServiceGroup.MEDIA, ServiceGroup.MEDIA.label))
            if (state.catCamEnabled) {
                add(PcSegmentOption(ServiceGroup.CAMS, ServiceGroup.CAMS.label))
            }
            add(PcSegmentOption(ServiceGroup.AGENTS, ServiceGroup.AGENTS.label))
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PcDimens.ScreenPad,
                end = PcDimens.ScreenPad,
                top = 4.dp,
                bottom = PcDimens.Space8,
            ),
            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
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
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            // 1. Summary Card with native Canvas status ring
            item(key = "summary") {
                val problems = h.problems
                val runningCount = h.services.counts?.running ?: allUnits.count { it.active == "active" }
                val totalCount = allUnits.size.coerceAtLeast(runningCount)
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
                            trackColor = colors.surfaceRaised,
                            okColor = colors.ok,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (problems.isEmpty()) {
                                    "All $runningCount running"
                                } else {
                                    "${problems.size} problem${if (problems.size == 1) "" else "s"}"
                                },
                                color = colors.text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val counts = h.services.counts
                            if (counts != null) {
                                Text(
                                    text = buildString {
                                        append("${counts.running} active")
                                        if (counts.failed > 0) append(" · ${counts.failed} failed")
                                        if (counts.inactive > 0) append(" · ${counts.inactive} inactive")
                                    },
                                    color = colors.textDim,
                                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
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
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (!prob.detail.isNullOrBlank()) {
                                        Text(
                                            text = prob.detail,
                                            color = colors.textDim,
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.sp,
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

            // 2. Services Segmented Filter + List
            if (h.services.available && allUnits.isNotEmpty()) {
                item(key = "services_filter") {
                    PcSegmentedControl(
                        options = filterOptions,
                        selected = effectiveFilter,
                        onSelect = onSelectFilter,
                    )
                }
            }

            item(key = "services_list") {
                if (!h.services.available) {
                    UnavailableCard(label = "Services", error = h.services.error)
                } else {
                    PcListCard {
                        if (shownUnits.isEmpty()) {
                            Text(
                                text = "No services in this group.",
                                color = colors.textFaint,
                                fontSize = 13.sp,
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

            // 3. Downloads Snapshot
            item(key = "downloads_snapshot") {
                DownloadsHealthSection(
                    downloads = h.downloads,
                    radarr = h.radarr,
                    sonarr = h.sonarr,
                    onOpenDownloads = onOpenDownloads,
                )
            }

            // 4. Jellyfin Active Streams
            item(key = "jellyfin_streams") {
                JellyfinHealthSection(jellyfin = h.jellyfin)
            }

            // 5. Disks Health
            item(key = "disks_health") {
                DisksHealthSection(disks = h.disks)
            }
        }
    }
}

@Composable
private fun UnavailableCard(label: String, error: String?) {
    val colors = LocalPcColors.current
    PcCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$label unavailable",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = error?.takeIf { it.isNotBlank() } ?: "No answer from the laptop",
                    color = colors.textFaint,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun DownloadsHealthSection(
    downloads: HealthDownloads,
    radarr: ArrQueue,
    sonarr: ArrQueue,
    onOpenDownloads: () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(
            label = "Downloads",
            trailing = {
                Text(
                    text = "All ›",
                    color = colors.accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenDownloads)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            },
        )
        if (!downloads.available) {
            UnavailableCard(label = "Downloads", error = downloads.error)
        } else {
            PcCard(onClick = onOpenDownloads) {
                if (!downloads.qbt.isNullOrBlank() && downloads.qbt != "ok") {
                    Text(
                        text = if (downloads.qbt == "auth") "qBittorrent sign-in failed" else "qBittorrent is not answering",
                        color = colors.warn,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val total = downloads.total
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Active", color = colors.textFaint, fontSize = 11.5.sp)
                        Text(
                            text = "${total?.count ?: 0}",
                            color = colors.text,
                            style = TabularNumStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Down", color = colors.textFaint, fontSize = 11.5.sp)
                        Text(
                            text = PcFormatters.formatRate(total?.speed ?: 0.0),
                            color = colors.text,
                            style = TabularNumStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Up", color = colors.textFaint, fontSize = 11.5.sp)
                        Text(
                            text = PcFormatters.formatRate(total?.upspeed ?: 0.0),
                            color = colors.textDim,
                            style = TabularNumStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (downloads.items.isEmpty()) {
                    Text(text = "Nothing downloading.", color = colors.textFaint, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        downloads.items.take(4).forEach { item ->
                            val stalled = item.state == "stalled"
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = item.name,
                                        color = colors.text,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${(item.progress * 100.0).roundToInt()}%",
                                        color = if (stalled) colors.warn else colors.textDim,
                                        style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                                    )
                                }
                                VitalsProgressBar(
                                    percent = item.progress * 100.0,
                                    trackColor = colors.surfaceRaised,
                                    fillColor = if (stalled) colors.warn else colors.text,
                                    height = 4.dp,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = buildString {
                                            append(if (stalled) "stalled" else item.state)
                                            if (item.state == "downloading" && item.eta != null && item.eta > 0L) {
                                                append(" · ${PcFormatters.formatDuration(item.eta)} left")
                                            }
                                        },
                                        color = if (stalled) colors.warn else colors.textFaint,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = buildString {
                                            append(PcFormatters.formatRate(item.speed))
                                            if (item.upspeed > 0.0) append(" · ↑ ${PcFormatters.formatRate(item.upspeed)}")
                                        },
                                        color = colors.textFaint,
                                        style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                    )
                                }
                            }
                        }
                        val totalCount = total?.count ?: downloads.items.size
                        if (totalCount > 4) {
                            Text(
                                text = "+${totalCount - 4} more",
                                color = colors.accent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                Spacer(modifier = Modifier.height(6.dp))
                ArrQueueSummaryRow(label = "Radarr", queue = radarr, showDivider = true)
                ArrQueueSummaryRow(label = "Sonarr", queue = sonarr, showDivider = false)
            }
        }
    }
}

@Composable
private fun ArrQueueSummaryRow(label: String, queue: ArrQueue, showDivider: Boolean) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                color = colors.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (queue.available) {
                Text(
                    text = "${queue.queue} queued",
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
                )
                PcPill(
                    label = when {
                        queue.needsAction > 0 -> "${queue.needsAction} action"
                        queue.queue > 0 -> "waiting"
                        else -> "clear"
                    },
                    tone = when {
                        queue.needsAction > 0 -> PcTone.Bad
                        queue.queue > 0 -> PcTone.Ok
                        else -> PcTone.Dim
                    },
                )
            } else {
                PcPill(label = "unavailable", tone = PcTone.Warn)
            }
        }
        if (queue.needsAction > 0) {
            queue.items.take(3).forEach { item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.bad,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(14.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = colors.text,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.reason.isNotBlank()) {
                            Text(
                                text = item.reason,
                                color = colors.textFaint,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        }
    }
}

@Composable
private fun JellyfinHealthSection(jellyfin: HealthJellyfin) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(label = "Jellyfin")
        when {
            !jellyfin.available -> UnavailableCard(label = "Jellyfin", error = jellyfin.error)
            jellyfin.streams.isEmpty() -> PcCard {
                Text(text = "No one watching.", color = colors.textFaint, fontSize = 13.sp)
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
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = colors.textDim,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stream.user,
                                color = colors.text,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
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
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!stream.subtitle.isNullOrBlank()) {
                            Text(
                                text = stream.subtitle,
                                color = colors.textFaint,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (stream.progress != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            VitalsProgressBar(
                                percent = stream.progress * 100.0,
                                trackColor = colors.surfaceRaised,
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

@Composable
private fun DisksHealthSection(disks: HealthDisks) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(label = "Disks")
        if (!disks.available) {
            UnavailableCard(label = "Disks", error = disks.error)
        } else {
            PcCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    disks.disks.forEach { disk ->
                        val free = max(0L, disk.total - disk.used)
                        val freePct = if (disk.total > 0L) (free.toDouble() / disk.total.toDouble()) * 100.0 else 0.0
                        val barColor = when {
                            freePct < 10.0 -> colors.bad
                            freePct < 20.0 -> colors.warn
                            else -> colors.text
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${disk.kind} · ${disk.mount}",
                                    color = colors.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${freePct.roundToInt()}% free",
                                    color = barColor,
                                    style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                                )
                            }
                            VitalsProgressBar(
                                percent = disk.pct,
                                trackColor = colors.surfaceRaised,
                                fillColor = barColor,
                                height = 4.dp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${PcFormatters.formatBytes(disk.used, 0)} / ${PcFormatters.formatBytes(disk.total, 0)}",
                                    color = colors.textFaint,
                                    style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                )
                                Text(
                                    text = "${PcFormatters.formatBytes(free, 0)} free",
                                    color = colors.textFaint,
                                    style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
