package com.neekhar.pccontrol.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcListCard
import com.neekhar.pccontrol.core.ui.PcListRow
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.home.PcFormatters
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
data class VitalsUiState(
    val vitals: VitalsResponse? = null,
    val history: List<VitalsHistPoint> = emptyList(),
    val error: String? = null,
    val isLive: Boolean = true,
    val showProcesses: Boolean = false,
    val processQuery: String = "",
    val sortKey: ProcessSortKey = ProcessSortKey.CPU,
    val sortDescending: Boolean = true,
) {
    val statusText: String?
        get() = vitals?.let {
            if (isLive) "Up ${PcFormatters.formatUptimeFor(it.uptime)} · live"
            else "Disconnected, retrying…"
        }

    val statusTone: PcTone
        get() = if (isLive) PcTone.Ok else PcTone.Bad
}

@Composable
fun VitalsTab(
    state: VitalsUiState,
    onToggleProcesses: (Boolean) -> Unit,
    onProcessQueryChange: (String) -> Unit,
    onSelectSort: (ProcessSortKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val v = state.vitals

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
        contentPadding = PaddingValues(
            start = PcDimens.ScreenPad,
            end = PcDimens.ScreenPad,
            top = 4.dp,
            bottom = PcDimens.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
    ) {
        if (state.error != null && v == null) {
            item(key = "error") {
                PcErrorBanner(
                    title = "Can't reach /vitals",
                    message = state.error,
                    tone = PcTone.Bad,
                )
            }
        }

        if (v == null) {
            if (state.error == null) {
                item(key = "loading") {
                    PcCard {
                        Text(
                            text = "Connecting to vitals…",
                            color = colors.textDim,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        // 1. Hero CPU Card (44sp tabular + native Canvas sparkline + per-core strip)
        item(key = "cpu_hero") {
            CpuHeroCard(vitals = v, history = state.history)
        }

        // 2. 2x2 Grid: Memory / Swap
        item(key = "mem_swap_row") {
            val gib = 1024.0 * 1024.0 * 1024.0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
            ) {
                MetricTile(
                    label = "Memory",
                    value = String.format(Locale.US, "%.1f / %.0f GB", v.mem.used / gib, v.mem.total / gib),
                    percent = v.mem.pct,
                    sub = "${v.mem.pct.roundToInt()}% used",
                    sparkValues = state.history.map { it.mem },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                MetricTile(
                    label = "Swap",
                    value = String.format(Locale.US, "%.1f / %.0f GB", v.swap.used / gib, v.swap.total / gib),
                    percent = v.swap.pct,
                    sub = "${v.swap.pct.roundToInt()}% used",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        // 3. 2x2 Grid: GPU / VRAM
        item(key = "gpu_vram_row") {
            val gpu = v.gpu
            val vramPct = if (gpu != null && gpu.memTotal > 0.0) (gpu.memUsed / gpu.memTotal) * 100.0 else null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
            ) {
                MetricTile(
                    label = "GPU",
                    value = gpu?.let { "${it.util.roundToInt()}%" } ?: "–",
                    percent = gpu?.util,
                    sub = gpu?.let { "${it.temp.roundToInt()}° · ${it.power.roundToInt()} W" } ?: "Not available",
                    sparkValues = if (gpu != null) state.history.map { it.gpu } else null,
                    barColorOverride = colors.textDim,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                MetricTile(
                    label = "VRAM",
                    value = gpu?.let {
                        String.format(Locale.US, "%.1f / %.0f GB", it.memUsed / 1024.0, it.memTotal / 1024.0)
                    } ?: "–",
                    percent = vramPct,
                    sub = vramPct?.let { "${it.roundToInt()}% used" } ?: "Not available",
                    barColorOverride = colors.textDim,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        // 4. Temperatures
        val temps = ServiceHelpers.sortedTemperatures(v.temps)
        if (temps.isNotEmpty()) {
            item(key = "temps_section") {
                Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PcSectionLabel(label = "Temperatures")
                    PcListCard {
                        temps.forEachIndexed { idx, (key, tempC) ->
                            val tempColor = when {
                                tempC >= 85.0 -> colors.bad
                                tempC >= 70.0 -> colors.warn
                                else -> colors.text
                            }
                            PcListRow(
                                title = ServiceHelpers.prettyTempLabel(key),
                                trailingContent = {
                                    Text(
                                        text = "${tempC.roundToInt()}°C",
                                        color = tempColor,
                                        style = TabularNumStyle.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                },
                                showChevron = false,
                                showDivider = idx < temps.lastIndex,
                            )
                        }
                    }
                }
            }
        }

        // 5. Power & I/O
        item(key = "power_io") {
            Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
                Spacer(modifier = Modifier.height(4.dp))
                PcSectionLabel(label = "Power & I/O")
                PcListCard {
                    PcListRow(
                        title = "Battery",
                        subtitle = v.bat?.let { if (it.plugged) "Plugged in" else "On battery" } ?: "No battery",
                        trailing = v.bat?.let { "${it.pct.roundToInt()}%" } ?: "–",
                        showChevron = false,
                        showDivider = true,
                    )
                    PcListRow(
                        title = "Disk I/O",
                        trailing = "↓ ${PcFormatters.formatRate(v.diskIo.r)} · ↑ ${PcFormatters.formatRate(v.diskIo.w)}",
                        showChevron = false,
                        showDivider = false,
                    )
                }
            }
        }

        // 6. Network
        item(key = "network") {
            NetworkVitalsSection(vitals = v, history = state.history)
        }

        // 7. Disks
        item(key = "disks") {
            DisksVitalsSection(disks = v.disks)
        }

        // 8. Top Processes
        item(key = "processes") {
            ProcessesVitalsSection(
                state = state,
                onToggleProcesses = onToggleProcesses,
                onProcessQueryChange = onProcessQueryChange,
                onSelectSort = onSelectSort,
            )
        }
    }
}

@Composable
private fun CpuHeroCard(
    vitals: VitalsResponse,
    history: List<VitalsHistPoint>,
) {
    val colors = LocalPcColors.current
    val cpuValues = remember(history) { history.map { it.cpu } }

    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "CPU",
                    color = colors.textDim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${vitals.cpu.roundToInt()}",
                        color = if (vitals.cpu > 85.0) colors.warn else colors.text,
                        style = TabularNumStyle.copy(
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-1.5).sp,
                        ),
                    )
                    Text(
                        text = "%",
                        color = colors.textDim,
                        style = TabularNumStyle.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${vitals.percpu.size} threads",
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
                )
                Text(
                    text = vitals.freq?.let { String.format(Locale.US, "%.2f GHz", it / 1000.0) } ?: "–",
                    color = colors.textFaint,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        VitalsSparkline(
            series = listOf(
                SparklineSeries(
                    values = cpuValues,
                    color = colors.text,
                    strokeWidthDp = 1.9f,
                ),
            ),
            baselineColor = colors.hairline,
            dotColor = colors.accent,
            maxValue = 100.0,
            height = 48.dp,
        )

        if (vitals.percpu.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            PerCoreCpuStrip(
                perCpu = vitals.percpu,
                trackColor = colors.surfaceRaised,
                normalColor = colors.textDim,
                warnColor = colors.warn,
                height = 8.dp,
            )
        }

        if (vitals.load.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Load ${vitals.load.joinToString(" ") { String.format(Locale.US, "%.2f", it) }}",
                color = colors.textFaint,
                style = TabularNumStyle.copy(fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    percent: Double?,
    sub: String,
    modifier: Modifier = Modifier,
    sparkValues: List<Double?>? = null,
    barColorOverride: Color? = null,
) {
    val colors = LocalPcColors.current
    val over = percent != null && percent > 85.0
    val barColor = if (over) colors.warn else (barColorOverride ?: colors.text)

    PcCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            text = label,
            color = colors.textDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            color = if (over) colors.warn else colors.text,
            style = TabularNumStyle.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (percent != null) {
            Spacer(modifier = Modifier.height(8.dp))
            VitalsProgressBar(
                percent = percent,
                trackColor = colors.surfaceRaised,
                fillColor = barColor,
                height = 4.dp,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = sub,
            color = if (over && sub.contains('%')) colors.warn else colors.textFaint,
            style = TabularNumStyle.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sparkValues.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            VitalsSparkline(
                series = listOf(
                    SparklineSeries(
                        values = sparkValues,
                        color = colors.textDim,
                        strokeWidthDp = 1.5f,
                    ),
                ),
                baselineColor = colors.hairline,
                dotColor = if (over) colors.warn else colors.text,
                maxValue = 100.0,
                height = 28.dp,
                maxPoints = 40,
            )
        }
    }
}

@Composable
private fun NetworkVitalsSection(
    vitals: VitalsResponse,
    history: List<VitalsHistPoint>,
) {
    val colors = LocalPcColors.current
    val nics = vitals.nics.entries.toList()
    val totalRx = nics.sumOf { it.value.rx }
    val totalTx = nics.sumOf { it.value.tx }
    val activeNics = nics.filter { it.value.rx + it.value.tx > 1024.0 }
    val rxSeries = remember(history) { history.map { it.rx } }
    val txSeries = remember(history) { history.map { it.tx } }

    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(label = "Network")
        PcCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "↓ ${PcFormatters.formatRate(totalRx)}",
                    color = colors.text,
                    style = TabularNumStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = "↑ ${PcFormatters.formatRate(totalTx)}",
                    color = colors.textDim,
                    style = TabularNumStyle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = if (activeNics.isNotEmpty()) activeNics.joinToString(", ") { it.key } else "idle",
                    color = colors.textFaint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            VitalsSparkline(
                series = listOf(
                    SparklineSeries(values = rxSeries, color = colors.text, strokeWidthDp = 1.7f),
                    SparklineSeries(values = txSeries, color = colors.textFaint, strokeWidthDp = 1.4f),
                ),
                baselineColor = colors.hairline,
                dotColor = colors.accent,
                height = 38.dp,
            )

            if (nics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nics.forEach { (name, stats) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = name,
                                color = colors.text,
                                style = MonoTextStyle.copy(fontSize = 12.5.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                tint = colors.textDim,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = PcFormatters.formatRate(stats.rx),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.sp),
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(78.dp),
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                tint = colors.textDim,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = PcFormatters.formatRate(stats.tx),
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.sp),
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(78.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisksVitalsSection(disks: List<DiskUsage>) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(label = "Disks")
        PcCard {
            if (disks.isEmpty()) {
                Text(
                    text = "No disks reported.",
                    color = colors.textFaint,
                    fontSize = 13.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    disks.forEach { disk ->
                        val shortName = if (disk.mount == "/") "/" else disk.mount.substringAfterLast('/')
                        val fillColor = when {
                            disk.pct >= 90.0 -> colors.bad
                            disk.pct > 85.0 -> colors.warn
                            else -> colors.text
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (shortName == disk.mount) disk.mount else "$shortName · ${disk.mount}",
                                    color = colors.text,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${PcFormatters.formatBytes(disk.used)} / ${PcFormatters.formatBytes(disk.total)}",
                                    color = colors.textDim,
                                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                )
                            }
                            VitalsProgressBar(
                                percent = disk.pct,
                                trackColor = colors.surfaceRaised,
                                fillColor = fillColor,
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
private fun ProcessesVitalsSection(
    state: VitalsUiState,
    onToggleProcesses: (Boolean) -> Unit,
    onProcessQueryChange: (String) -> Unit,
    onSelectSort: (ProcessSortKey) -> Unit,
) {
    val colors = LocalPcColors.current
    val allProcs = state.vitals?.procs

    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        Spacer(modifier = Modifier.height(4.dp))
        PcSectionLabel(
            label = "Top processes",
            trailing = {
                Switch(
                    checked = state.showProcesses,
                    onCheckedChange = onToggleProcesses,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = colors.textDim,
                        uncheckedTrackColor = colors.surfaceRaised,
                        uncheckedBorderColor = colors.hairline,
                    ),
                )
            },
        )

        if (state.showProcesses) {
            if (allProcs == null) {
                PcCard {
                    Text(
                        text = "Sampling processes…",
                        color = colors.textDim,
                        fontSize = 13.5.sp,
                    )
                }
            } else {
                val (shown, filteredCount) = remember(allProcs, state.processQuery, state.sortKey, state.sortDescending) {
                    ServiceHelpers.filterAndSortProcesses(
                        processes = allProcs,
                        query = state.processQuery,
                        sortKey = state.sortKey,
                        descending = state.sortDescending,
                    )
                }

                PcCard {
                    // Search box
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceRaised)
                                .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = colors.textFaint,
                                modifier = Modifier.size(16.dp),
                            )
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (state.processQuery.isEmpty()) {
                                    Text(
                                        text = "Filter name, command or PID",
                                        color = colors.textFaint,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = state.processQuery,
                                    onValueChange = onProcessQueryChange,
                                    singleLine = true,
                                    textStyle = TabularNumStyle.copy(color = colors.text, fontSize = 13.5.sp),
                                    cursorBrush = SolidColor(colors.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (state.processQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onProcessQueryChange("") },
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear filter",
                                        tint = colors.textDim,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = "$filteredCount/${allProcs.size}",
                            color = colors.textFaint,
                            style = TabularNumStyle.copy(fontSize = 12.sp),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Sort chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProcessSortKey.entries.forEach { key ->
                            val selected = key == state.sortKey
                            val arrow = if (!selected) "" else if (state.sortDescending) " ▾" else " ▴"
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) colors.accentSoft else Color.Transparent)
                                    .border(
                                        width = 0.5.dp,
                                        color = if (selected) colors.accent else colors.hairline,
                                        shape = CircleShape,
                                    )
                                    .clickable { onSelectSort(key) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "${key.label}$arrow",
                                    color = if (selected) colors.accent else colors.textDim,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (shown.isEmpty()) {
                        Text(
                            text = "No matching processes.",
                            color = colors.textFaint,
                            fontSize = 13.sp,
                        )
                    } else {
                        shown.forEachIndexed { idx, proc ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = colors.hairline,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = proc.name,
                                        color = colors.text,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f%%", proc.cpu),
                                        color = if (proc.cpu > 50.0) colors.bad else colors.text,
                                        style = TabularNumStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(56.dp),
                                    )
                                    Text(
                                        text = PcFormatters.formatBytes(proc.rss, digits = 0),
                                        color = colors.textDim,
                                        style = TabularNumStyle.copy(fontSize = 12.sp),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(64.dp),
                                    )
                                }
                                val meta = buildString {
                                    append("PID ${proc.pid} · ${proc.user.ifBlank { "–" }} · ${proc.thr?.toString() ?: "–"} thr")
                                    if (proc.gpu != null) {
                                        append(" · GPU ${proc.gpu.roundToInt()} MB")
                                    }
                                }
                                Text(
                                    text = meta,
                                    color = colors.textFaint,
                                    style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (proc.cmd.isNotBlank()) {
                                    Text(
                                        text = proc.cmd.take(120),
                                        color = colors.textFaint,
                                        style = MonoTextStyle.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (filteredCount > shown.size) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Showing ${shown.size} of $filteredCount",
                                color = colors.textFaint,
                                style = TabularNumStyle.copy(fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}
