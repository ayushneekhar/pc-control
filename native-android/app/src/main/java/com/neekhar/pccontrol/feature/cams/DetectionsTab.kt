package com.neekhar.pccontrol.feature.cams

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcBrandIcons
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcPill
import com.neekhar.pccontrol.core.ui.PcTone
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val REPLAY_WINDOW_MS = (ReplayMath.DAY * 1000.0).toLong()

/**
 * Detections tab: virtualized 2-column grid of YOLO11s `catdetect` sightings with camera filter
 * chips, confidence pills, pull-to-refresh, and a full-screen pinch-to-zoom viewer with
 * Jump to Replay. Ported from `src/screens/cams/DetectionsView.tsx`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionsTab(
    visible: Boolean,
    repository: CamsRepository,
    bottomInset: Dp,
    onJumpToReplay: (cam: String, tsMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val scope = rememberCoroutineScope()
    val baseUrl = repository.baseUrl().trimEnd('/')

    var camFilter by remember { mutableStateOf("all") }
    var snaps by remember { mutableStateOf<List<DetectionSnap>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(mapOf("all" to 0, "cat" to 0, "cat2" to 0, "cat3" to 0)) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedIdx by remember { mutableStateOf<Int?>(null) }
    var loadedAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewerNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var reqSeq by remember { mutableIntStateOf(0) }

    fun loadSnaps(cam: String, isRefresh: Boolean) {
        val seq = ++reqSeq
        if (isRefresh) refreshing = true else loading = true
        error = null
        scope.launch {
            when (val res = repository.getDetectionSnaps(cam = cam.takeIf { it != "all" }, limit = 300)) {
                is ApiResult.Success -> {
                    if (seq != reqSeq) return@launch
                    snaps = res.data.snaps
                    counts = res.data.counts.ifEmpty { mapOf("all" to res.data.snaps.size) }
                    loadedAtMs = System.currentTimeMillis()
                    selectedIdx = selectedIdx?.let { cur ->
                        if (res.data.snaps.isEmpty()) null else cur.coerceAtMost(res.data.snaps.lastIndex)
                    }
                }

                is ApiResult.Error -> {
                    if (seq != reqSeq) return@launch
                    error = res.message.ifBlank { "Couldn't load detections" }
                }
            }
            if (seq == reqSeq) {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(visible, camFilter) {
        if (visible) {
            loadSnaps(camFilter, isRefresh = false)
        }
    }

    val filterChips = remember(counts) {
        val known = setOf("all") + CamsConfig.CAMS.map { it.id }
        val extras = counts.keys
            .filter { it !in known && (counts[it] ?: 0) > 0 }
            .sorted()
            .map { Triple(it, CamsConfig.camName(it), counts[it] ?: 0) }
        buildList {
            add(Triple("all", "All", counts["all"] ?: 0))
            CamsConfig.CAMS.forEach { cam ->
                add(Triple(cam.id, cam.displayName, counts[cam.id] ?: 0))
            }
            addAll(extras)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Horizontal camera filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PcDimens.ScreenPad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            filterChips.forEach { (id, label, count) ->
                val active = camFilter == id
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(if (active) colors.accentSoft else colors.surface)
                        .clickable {
                            selectedIdx = null
                            error = null
                            camFilter = id
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = label,
                        color = if (active) colors.accent else colors.textDim,
                        style = type.caption.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Text(
                        text = count.toString(),
                        color = if (active) colors.accent else colors.textFaint,
                        style = type.micro.tabular(),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                error != null && snaps.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PcDimens.ScreenPad, vertical = PcDimens.Space4),
                        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
                    ) {
                        PcErrorBanner(
                            title = "Couldn't load detections",
                            message = error,
                            tone = PcTone.Bad,
                        )
                        PcButton(
                            label = "Retry",
                            variant = PcButtonVariant.Subtle,
                            icon = Icons.Rounded.Refresh,
                            onClick = { loadSnaps(camFilter, isRefresh = false) },
                        )
                    }
                }

                loading && snaps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PcDimens.Space2),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                                color = colors.accent,
                            )
                            Text(
                                text = "Loading detections…",
                                color = colors.textDim,
                                style = type.body,
                            )
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { loadSnaps(camFilter, isRefresh = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (snaps.isEmpty()) {
                            PcEmptyState(
                                icon = PcBrandIcons.Cat,
                                title = if (camFilter == "all") {
                                    "No saved detections yet"
                                } else {
                                    "No detections on ${CamsConfig.camName(camFilter)}"
                                },
                                body = "YOLO11s cat sightings appear here automatically while Cat alerts are enabled.",
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(
                                    start = PcDimens.ScreenPad,
                                    end = PcDimens.ScreenPad,
                                    top = PcDimens.Space3,
                                    bottom = bottomInset + PcDimens.Space4,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                if (error != null) {
                                    item(span = { GridItemSpan(2) }) {
                                        PcErrorBanner(
                                            title = "Refresh failed",
                                            message = error,
                                            tone = PcTone.Warn,
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = snaps,
                                    key = { _, snap -> snap.filename },
                                ) { index, snap ->
                                    DetectionGridTile(
                                        snap = snap,
                                        imageUrl = "$baseUrl${snap.url}",
                                        nowMs = loadedAtMs,
                                        onClick = {
                                            viewerNowMs = System.currentTimeMillis()
                                            selectedIdx = index
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Seamless vertical fade under camera filter chips
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(colors.bg, Color.Transparent),
                        ),
                    ),
            )
        }
    }

    val activeIdx = selectedIdx
    val selectedSnap = if (activeIdx != null && activeIdx in snaps.indices) snaps[activeIdx] else null
    if (selectedSnap != null && activeIdx != null) {
        DetectionFullScreenViewer(
            snap = selectedSnap,
            imageUrl = "$baseUrl${selectedSnap.url}",
            index = activeIdx,
            total = snaps.size,
            canJumpToReplay = RecCam.fromAny(selectedSnap.cam) != null &&
                viewerNowMs - selectedSnap.tsMs <= REPLAY_WINDOW_MS &&
                selectedSnap.tsMs <= viewerNowMs + 60_000L,
            onDismiss = { selectedIdx = null },
            onPrev = { if (activeIdx > 0) selectedIdx = activeIdx - 1 },
            onNext = { if (activeIdx < snaps.lastIndex) selectedIdx = activeIdx + 1 },
            onJumpToReplay = {
                selectedIdx = null
                onJumpToReplay(selectedSnap.cam, selectedSnap.tsMs)
            },
        )
    }
}

@Composable
private fun DetectionGridTile(
    snap: DetectionSnap,
    imageUrl: String,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val timeText = remember(snap.tsMs, nowMs) { formatThumbTime(snap.tsMs, nowMs) }
    val confPct = snap.confidence?.let { (it * 100.0).roundToInt().coerceIn(0, 100) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(PcDimens.RadiusSm))
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "${CamsConfig.camName(snap.cam)} detection at $timeText",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (confPct != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(colors.mediaChip)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$confPct%",
                    color = colors.accent,
                    style = TabularNumStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(CircleShape)
                    .background(colors.mediaChip)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = CamsConfig.camName(snap.cam),
                    color = colors.onMedia,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.mediaChip)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = timeText,
                    color = colors.onMedia,
                    style = TabularNumStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DetectionFullScreenViewer(
    snap: DetectionSnap,
    imageUrl: String,
    index: Int,
    total: Int,
    canJumpToReplay: Boolean,
    onDismiss: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToReplay: () -> Unit,
) {
    val colors = LocalPcColors.current
    val confPct = snap.confidence?.let { (it * 100.0).roundToInt().coerceIn(0, 100) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.media),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.media)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close viewer",
                        tint = colors.onMedia,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = CamsConfig.camName(snap.cam),
                            color = colors.onMedia,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (confPct != null) {
                            PcPill(
                                label = "${snap.label ?: "cat"} $confPct%",
                                tone = PcTone.Accent,
                            )
                        }
                    }
                    Text(
                        text = formatExactTime(snap.tsMs),
                        color = colors.onMediaDim,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${index + 1} / $total",
                    color = colors.onMediaDim,
                    style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

            // Pinch-to-zoom full image
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                PinchZoomBox(
                    resetKey = snap.filename,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "${CamsConfig.camName(snap.cam)} detection",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Footer controls
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .navigationBarsPadding()
                    .padding(horizontal = PcDimens.ScreenPad, vertical = PcDimens.Space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                PcButton(
                    label = "Prev",
                    icon = Icons.Rounded.ChevronLeft,
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    enabled = index > 0,
                    onClick = onPrev,
                )
                if (canJumpToReplay) {
                    PcButton(
                        label = "Jump to Replay",
                        icon = Icons.Rounded.History,
                        variant = PcButtonVariant.Primary,
                        compact = true,
                        onClick = onJumpToReplay,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                PcButton(
                    label = "Next",
                    icon = Icons.Rounded.ChevronRight,
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    enabled = index < total - 1,
                    onClick = onNext,
                )
            }
        }
    }
}

private fun formatThumbTime(tsMs: Long, nowMs: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = tsMs }
    val n = Calendar.getInstance().apply { timeInMillis = nowMs }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tsMs))
    return if (d.get(Calendar.YEAR) == n.get(Calendar.YEAR) &&
        d.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
    ) {
        hm
    } else {
        val dm = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(tsMs))
        "$dm · $hm"
    }
}

private fun formatExactTime(tsMs: Long): String {
    val fmt = SimpleDateFormat("EEE, d MMM yyyy · HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(tsMs))
}
