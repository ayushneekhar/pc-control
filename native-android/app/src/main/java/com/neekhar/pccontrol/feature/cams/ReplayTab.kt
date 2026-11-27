package com.neekhar.pccontrol.feature.cams

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcBrandIcons
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcTone
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Replay tab: 24 h HLS playback across all three cat cameras (`cat`, `cat2`, `cat3hd`)
 * powered by Media3 `ExoPlayer` + `HlsMediaSource`, with 24 h overview strip, zoomable timeline
 * ribbon, playback speed picker (`1×` / `2×` / `4×`), ±10s/±1m skip, frame snapshot, clip
 * download/share, and cat sighting visit jump chips.
 */
@Composable
fun ReplayTab(
    visible: Boolean,
    hevcOk: Boolean,
    dataSource: ReplayDataSource,
    okHttpClient: OkHttpClient,
    target: SightingTarget?,
    fullscreen: Boolean = false,
    isInPip: Boolean = false,
    bottomInset: androidx.compose.ui.unit.Dp = 28.dp,
    onPlayingChange: (Boolean) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onTargetConsumed: () -> Unit,
    onToast: (message: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember(dataSource, hevcOk) {
        ReplayEngine(
            scope = scope,
            dataSource = dataSource,
            hevcOk = hevcOk,
        )
    }
    val state by engine.state.collectAsState()

    LaunchedEffect(state.playing, visible) {
        onPlayingChange(visible && state.playing)
    }
    DisposableEffect(Unit) {
        onDispose { onPlayingChange(false) }
    }

    var snapBusy by remember { mutableStateOf(false) }
    var clipBusy by remember { mutableStateOf(false) }

    DisposableEffect(visible, engine) {
        if (visible) engine.start()
        onDispose { engine.stop() }
    }

    LaunchedEffect(visible, target) {
        if (visible && target != null) {
            if (engine.seekSighting(target.cam.id, target.tsMs)) {
                onTargetConsumed()
            }
        }
    }

    val big = state.big
    val others = remember(big) { RecCam.ALL.filter { it != big } }
    val wallTime = state.wallTime
    val now = state.now
    val lastSighting = state.sightings.lastOrNull()

    fun saveReplaySnapshot() {
        if (snapBusy) return
        snapBusy = true
        scope.launch {
            runCatching {
                engine.snapshot { t -> "${big.displayName} ${fmtHms(t)}" }
            }.onSuccess { uri ->
                onToast("Saved to Pictures/PC Control", "Share") {
                    CamsMediaTools.shareImage(context, uri)
                }
            }.onFailure { err ->
                onToast("Snapshot failed: ${err.message ?: "error"}", null, null)
            }
            snapBusy = false
        }
    }

    fun downloadReplayClip() {
        if (clipBusy || wallTime <= 0.0) return
        clipBusy = true
        scope.launch {
            val startEpoch = (wallTime - 15.0).toLong().coerceAtLeast(0L)
            runCatching {
                CamsMediaTools.saveReplayClip(
                    context = context,
                    client = okHttpClient,
                    baseUrl = dataSource.baseUrl(),
                    cam = big,
                    startEpochSec = startEpoch,
                    durationSec = 30,
                )
            }.onSuccess { uri ->
                onToast("Saved 30 s clip to Movies/PC Control", "Share") {
                    CamsMediaTools.shareVideo(context, uri)
                }
            }.onFailure { err ->
                onToast(err.message ?: "Clip download unavailable", null, null)
            }
            clipBusy = false
        }
    }

    if (fullscreen || isInPip) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.media),
        ) {
            ReplayVideoTile(
                cam = big,
                tile = state.tiles[big] ?: ReplayTileState(label = big.displayName),
                source = state.sources[big],
                playing = state.playPerCam[big] == true,
                globalPlaying = state.playing,
                rate = state.rate,
                isBig = true,
                fullscreen = true,
                hideChrome = isInPip,
                clockOverlay = if (!isInPip && wallTime > 0.0) fmtHms(wallTime) else null,
                snapBusy = snapBusy,
                onHandle = { engine.setHandle(big, it) },
                onProgress = { engine.onProgress(big, it) },
                onError = { engine.onPlayerError(big) },
                onTap = { if (!isInPip) engine.togglePlay() },
                onSnapshot = ::saveReplaySnapshot,
                onToggleFullscreen = onToggleFullscreen,
                modifier = if (isInPip) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                },
            )

            // Compact bottom transport overlay in landscape fullscreen (hidden in PiP)
            if (!isInPip) {
                Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .clip(CircleShape)
                    .background(colors.mediaChip)
                    .border(0.5.dp, colors.mediaChipBorder, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { engine.skip(-10.0) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "−10s",
                        color = colors.onMedia,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable { engine.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = colors.onAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { engine.skip(10.0) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+10s",
                        color = colors.onMedia,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (state.rate > 1f) colors.accentSoft else Color.Transparent)
                        .clickable { engine.cycleRate() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = fmtRate(state.rate),
                        color = if (state.rate > 1f) colors.accent else colors.onMedia,
                        style = TabularNumStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = PcDimens.Space3,
                bottom = bottomInset,
            ),
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
    ) {
        if (state.error.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                PcErrorBanner(
                    title = "Recorder unavailable",
                    message = state.error,
                    tone = PcTone.Bad,
                )
            }
        }

        // Camera selector pills (edge-to-edge)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            items(RecCam.ALL, key = { it.id }) { cam ->
                val selected = cam == big
                val hasFootage = (state.indices[cam]?.spans?.isNotEmpty() == true)
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) colors.accentSoft else colors.surface)
                        .clickable { engine.setBig(cam) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (hasFootage) colors.accent else colors.textFaint),
                    )
                    Text(
                        text = cam.displayName,
                        color = if (selected) colors.accent else colors.textDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Big primary player tile
        ReplayVideoTile(
            cam = big,
            tile = state.tiles[big] ?: ReplayTileState(label = big.displayName),
            source = state.sources[big],
            playing = state.playPerCam[big] == true,
            globalPlaying = state.playing,
            rate = state.rate,
            isBig = true,
            fullscreen = false,
            snapBusy = snapBusy,
            onHandle = { engine.setHandle(big, it) },
            onProgress = { engine.onProgress(big, it) },
            onError = { engine.onPlayerError(big) },
            onTap = { engine.togglePlay() },
            onSnapshot = ::saveReplaySnapshot,
            onToggleFullscreen = onToggleFullscreen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad)
                .aspectRatio(16f / 9f),
        )

        // Two synchronized secondary camera tiles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            others.forEach { cam ->
                ReplayVideoTile(
                    cam = cam,
                    tile = state.tiles[cam] ?: ReplayTileState(label = cam.displayName),
                    source = state.sources[cam],
                    playing = state.playPerCam[cam] == true,
                    globalPlaying = state.playing,
                    rate = state.rate,
                    isBig = false,
                    fullscreen = false,
                    snapBusy = false,
                    onHandle = { engine.setHandle(cam, it) },
                    onProgress = { engine.onProgress(cam, it) },
                    onError = { engine.onPlayerError(cam) },
                    onTap = { engine.setBig(cam) },
                    onSnapshot = {},
                    onToggleFullscreen = {},
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(16f / 9f),
                )
            }
        }

        // Clock + Zoom + Now row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (wallTime > 0.0) fmtHms(wallTime) else "--:--:--",
                    color = colors.text,
                    style = MonoTextStyle.copy(
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.5).sp,
                        fontFeatureSettings = "tnum",
                    ),
                )
                val subtitle = if (wallTime > 0.0) {
                    buildString {
                        append(dayName(wallTime))
                        append(" · ")
                        append(if (now - wallTime < 90.0) "moments ago" else formatAgoSeconds(wallTime, now))
                        if (state.rate > 1f) append(" · ${fmtRate(state.rate)} speed")
                        if (!state.decoder.isNullOrBlank()) append(" · ${state.decoder}")
                    }
                } else {
                    "Loading recordings…"
                }
                Text(
                    text = subtitle,
                    color = colors.textDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SmallTimelineButton(
                label = ReplayMath.fmtSpan(state.span),
                onClick = { engine.cycleZoom() },
            )
            SmallTimelineButton(
                label = "Now",
                icon = Icons.Rounded.SkipNext,
                onClick = { engine.goLive() },
            )
        }

        // 24 h overview strip + zoomable ribbon timeline
        PcCard(
            modifier = Modifier.padding(horizontal = PcDimens.ScreenPad),
            contentPadding = PaddingValues(0.dp),
        ) {
            OverviewTimelineStrip(
                indices = state.indices,
                visits = state.visits,
                wallTime = wallTime,
                span = state.span,
                now = now,
                onWidthChanged = { engine.setOverviewWidth(it) },
                onScrub = { engine.overviewMove(it) },
                onScrubEnd = { engine.overviewEnd() },
            )
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            ZoomableTimelineRibbon(
                indices = state.indices,
                sightings = state.sightings,
                wallTime = wallTime,
                span = state.span,
                now = now,
                big = big,
                onWidthChanged = { engine.setRibbonWidth(it) },
                onDragStart = { engine.ribbonDragStart() },
                onDragDelta = { engine.ribbonDragDelta(it) },
                onDragEnd = { engine.ribbonDragEnd(it) },
                onPinchZoom = { engine.ribbonPinchZoom(it) },
                onTap = { engine.ribbonTap(it) },
            )
        }

        // Transport controls: -1m, -10s, Play/Pause, +10s, +1m
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TransportChip(label = "−1m", onClick = { engine.skip(-60.0) })
            TransportChip(label = "−10s", onClick = { engine.skip(-10.0) })
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable { engine.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = colors.onAccent,
                    modifier = Modifier.size(28.dp),
                )
            }
            TransportChip(label = "+10s", onClick = { engine.skip(10.0) })
            TransportChip(label = "+1m", onClick = { engine.skip(60.0) })
        }

        // Playback speed selector (1x / 2x / 4x / 8x / 16x) + Clip Share button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(PcDimens.RadiusSm))
                    .background(colors.surface)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ReplayMath.SPEEDS.forEach { spd ->
                    val on = state.rate == spd
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) colors.surfaceRaised else Color.Transparent)
                            .clickable { engine.setRate(spd) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = fmtRate(spd),
                            color = if (on) colors.accent else colors.textDim,
                            style = TabularNumStyle.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(PcDimens.RadiusSm))
                    .background(colors.surfaceRaised)
                    .alpha(if (wallTime > 0.0 && !clipBusy) 1f else 0.5f)
                    .clickable(enabled = wallTime > 0.0 && !clipBusy) { downloadReplayClip() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (clipBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.text,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Save 30s clip",
                        tint = colors.text,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    text = "Clip 30s",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Cat sightings navigation bar
        val hasPrevVisit = state.visits.any { it.start < wallTime - 6.0 }
        val hasNextVisit = state.visits.any { it.start > wallTime + 5.0 }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
        ) {
            SmallTimelineButton(
                icon = Icons.Rounded.ChevronLeft,
                enabled = hasPrevVisit,
                onClick = { engine.prevVisit() },
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(if (lastSighting != null) colors.warnSoft else colors.surface)
                    .clickable(enabled = lastSighting != null) { engine.lastSighting() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = PcBrandIcons.Cat,
                    contentDescription = null,
                    tint = if (lastSighting != null) colors.warn else colors.textDim,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        lastSighting != null -> "Last seen: ${lastSighting.cam.displayName}, ${fmtHm(lastSighting.ts)}"
                        state.detectorEnabled == false -> "Cat alerts are off · no sightings logged"
                        else -> "No cat sightings in the last 24 h"
                    },
                    color = if (lastSighting != null) colors.warn else colors.textDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SmallTimelineButton(
                icon = Icons.Rounded.ChevronRight,
                enabled = hasNextVisit,
                onClick = { engine.nextVisit() },
            )
        }

        // Recent cat visits horizontal chips (edge-to-edge)
        if (state.visits.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = PcDimens.ScreenPad),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                items(
                    items = state.visits.asReversed().take(40),
                    key = { "${it.cam.id}:${it.start}" },
                ) { visit ->
                    val current = wallTime >= visit.start - 5.0 && wallTime <= visit.end + 10.0
                    val durMin = ((visit.end - visit.start) / 60.0).roundToInt()
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(PcDimens.RadiusSm))
                            .background(if (current) colors.warnSoft else colors.surface)
                            .border(
                                width = 1.dp,
                                color = if (current) colors.warn else colors.hairline,
                                shape = RoundedCornerShape(PcDimens.RadiusSm),
                            )
                            .clickable { engine.jumpTo(visit.cam, visit.start) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = fmtHm(visit.start),
                            color = colors.text,
                            style = TabularNumStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = buildString {
                                append(visit.cam.shortName)
                                if (visit.end - visit.start >= 60.0) append(" · $durMin min")
                            },
                            color = colors.textDim,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayVideoTile(
    cam: RecCam,
    tile: ReplayTileState,
    source: ReplaySource?,
    playing: Boolean,
    globalPlaying: Boolean,
    rate: Float,
    isBig: Boolean,
    fullscreen: Boolean,
    snapBusy: Boolean,
    onHandle: (ReplayPlayerHandle?) -> Unit,
    onProgress: (ReplayProgress) -> Unit,
    onError: (String) -> Unit,
    onTap: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleFullscreen: () -> Unit,
    hideChrome: Boolean = false,
    clockOverlay: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val corner = when {
        fullscreen -> 0.dp
        isBig -> PcDimens.RadiusLg
        else -> PcDimens.RadiusSm
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(colors.media),
    ) {
        if (source != null) {
            PinchZoomBox(
                enabled = isBig,
                resetKey = "${cam.id}:${isBig}:${fullscreen}",
                onTap = onTap,
                modifier = Modifier.fillMaxSize(),
            ) {
                HlsReplayPlayer(
                    source = source,
                    playing = playing,
                    rate = rate,
                    onHandle = onHandle,
                    onProgress = onProgress,
                    onPlayerError = onError,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onTap),
            )
        }

        if (tile.msg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.mediaChip)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tile.msg,
                    color = colors.onMediaDim,
                    fontSize = if (isBig) 14.sp else 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Camera label chip (+ clock overlay in fullscreen)
        if (!hideChrome) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (isBig) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.mediaChip)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = tile.label,
                    color = colors.onMedia,
                    fontSize = if (isBig) 13.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!clockOverlay.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.mediaChip)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = clockOverlay,
                        color = colors.onMediaDim,
                        style = MonoTextStyle.copy(
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        }

        if (tile.spinning && !hideChrome) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = if (isBig) 94.dp else 10.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
                color = colors.onMedia,
            )
        }

        if (isBig && !hideChrome && !globalPlaying && tile.msg == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(colors.mediaChip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Paused",
                    tint = colors.onMedia,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        if (isBig && !hideChrome) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.mediaChip)
                        .clickable(enabled = !snapBusy, onClick = onSnapshot),
                    contentAlignment = Alignment.Center,
                ) {
                    if (snapBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.onMedia,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = "Save frame",
                            tint = colors.onMedia,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.mediaChip)
                        .clickable(onClick = onToggleFullscreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                        tint = colors.onMedia,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewTimelineStrip(
    indices: Map<RecCam, RecIndex>,
    visits: List<Visit>,
    wallTime: Double,
    span: Double,
    now: Double,
    onWidthChanged: (Float) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val colors = LocalPcColors.current
    val tzOffset = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000.0 }
    var widthPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged {
                widthPx = it.width.toFloat()
                onWidthChanged(widthPx)
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onScrub(offset.x)
                    onScrubEnd()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> onScrub(offset.x) },
                    onDrag = { change, _ ->
                        change.consume()
                        onScrub(change.position.x)
                    },
                    onDragEnd = onScrubEnd,
                    onDragCancel = onScrubEnd,
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            if (w <= 0f || now <= 0.0) return@Canvas
            val pad = 8.dp.toPx()
            val innerW = max(1f, w - 2f * pad)
            val lo = now - ReplayMath.DAY
            fun xOf(t: Double): Float = pad + (((t - lo) / ReplayMath.DAY).toFloat() * innerW)

            // 6-hour grid lines
            val firstMark = ceil((lo + tzOffset) / 21_600.0) * 21_600.0 - tzOffset
            var mark = firstMark
            while (mark <= now) {
                val mx = xOf(mark)
                drawLine(
                    color = colors.hairline,
                    start = Offset(mx, 3.dp.toPx()),
                    end = Offset(mx, 27.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                mark += 21_600.0
            }

            // 3 camera lanes
            val laneH = 5.dp.toPx()
            val laneGap = 7.dp.toPx()
            val topPad = 4.dp.toPx()
            RecCam.ALL.forEachIndexed { idx, cam ->
                val y = topPad + idx * laneGap
                drawRect(
                    color = colors.surfaceRaised,
                    topLeft = Offset(pad, y),
                    size = Size(innerW, laneH),
                )
                indices[cam]?.spans.orEmpty().forEach { sp ->
                    val x0 = xOf(sp.wallStart).coerceIn(pad, w - pad)
                    val x1 = xOf(sp.wallEnd).coerceIn(pad, w - pad)
                    drawRect(
                        color = colors.textDim.copy(alpha = 0.55f),
                        topLeft = Offset(x0, y),
                        size = Size(max(1.dp.toPx(), x1 - x0), laneH),
                    )
                }
            }

            // Amber cat visit markers
            visits.forEach { v ->
                val laneIdx = RecCam.ALL.indexOf(v.cam)
                if (laneIdx >= 0) {
                    val y = topPad + laneIdx * laneGap
                    val x0 = (xOf(v.start) - 1.dp.toPx()).coerceIn(pad, w - pad)
                    val x1 = (xOf(v.end) + 1.dp.toPx()).coerceIn(pad, w - pad)
                    drawRect(
                        color = colors.warn,
                        topLeft = Offset(x0, y),
                        size = Size(max(2.5.dp.toPx(), x1 - x0), laneH),
                    )
                }
            }

            // Viewport bracket + playhead
            if (wallTime > 0.0) {
                val left = max(pad - 2.dp.toPx(), xOf(wallTime - span / 2.0))
                val right = min(w - pad + 2.dp.toPx(), xOf(wallTime + span / 2.0))
                drawRoundRect(
                    color = colors.text,
                    topLeft = Offset(left, 1.5.dp.toPx()),
                    size = Size(max(4.dp.toPx(), right - left), 25.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                val cx = xOf(wallTime)
                drawLine(
                    color = colors.accent,
                    start = Offset(cx, 0f),
                    end = Offset(cx, 28.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // Bottom 6h labels
        if (widthPx > 0f && now > 0.0) {
            val lo = now - ReplayMath.DAY
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (i in 0..4) {
                    val ts = lo + (ReplayMath.DAY * i / 4.0)
                    Text(
                        text = fmtHm(ts),
                        color = colors.textFaint,
                        style = TabularNumStyle.copy(fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableTimelineRibbon(
    indices: Map<RecCam, RecIndex>,
    sightings: List<Sighting>,
    wallTime: Double,
    span: Double,
    now: Double,
    big: RecCam,
    onWidthChanged: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onTap: (Float) -> Unit,
) {
    val colors = LocalPcColors.current
    val tzOffset = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000.0 }
    var widthPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .onSizeChanged {
                widthPx = it.width.toFloat()
                onWidthChanged(widthPx)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    var totalDx = 0f
                    var didDrag = false
                    var didPinch = false
                    onDragStart()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                didPinch = true
                                onPinchZoom(zoom)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (!didPinch) {
                            val change = pressed.first()
                            val dx = change.position.x - change.previousPosition.x
                            totalDx += dx
                            if (!didDrag && abs(totalDx) > 6f) {
                                didDrag = true
                            }
                            if (didDrag) {
                                change.consume()
                                onDragDelta(totalDx)
                            }
                        }
                    }

                    when {
                        didPinch -> Unit
                        didDrag -> onDragEnd(totalDx)
                        else -> onTap(startX)
                    }
                }
            },
    ) {
        val spp = if (widthPx > 0f) span / widthPx.toDouble() else 1.0
        val t0 = wallTime - (widthPx / 2.0) * spp
        val t1 = t0 + widthPx * spp
        val tickInfo = remember(t0, t1, spp, tzOffset, wallTime) {
            if (widthPx > 0f && wallTime > 0.0) {
                ReplayMath.ticks(t0, t1, spp, tzOffset, minPx = 76.0)
            } else {
                TimelineTicks(600.0, emptyList())
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || wallTime <= 0.0) return@Canvas
            val curSpp = span / w.toDouble()
            val startT = wallTime - (w / 2.0) * curSpp
            fun xOf(t: Double): Float = ((t - startT) / curSpp).toFloat()

            // Out-of-window shaded regions
            val oldestX = xOf(now - ReplayMath.DAY)
            if (oldestX > 0f) {
                drawRect(
                    color = colors.media.copy(alpha = 0.25f),
                    topLeft = Offset.Zero,
                    size = Size(oldestX.coerceAtMost(w), h),
                )
            }
            val newestX = xOf(now)
            if (newestX < w) {
                val lx = newestX.coerceAtLeast(0f)
                drawRect(
                    color = colors.media.copy(alpha = 0.25f),
                    topLeft = Offset(lx, 0f),
                    size = Size(w - lx, h),
                )
            }

            // Grid ticks
            tickInfo.positions.forEach { tick ->
                val tx = xOf(tick)
                drawLine(
                    color = colors.hairline,
                    start = Offset(tx, 22.dp.toPx()),
                    end = Offset(tx, h),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Three camera lanes
            val laneH = 16.dp.toPx()
            val laneStep = 24.dp.toPx()
            val laneTop = 28.dp.toPx()
            val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            RecCam.ALL.forEachIndexed { idx, cam ->
                val y = laneTop + idx * laneStep
                drawRoundRect(
                    color = colors.surfaceRaised,
                    topLeft = Offset(0f, y),
                    size = Size(w, laneH),
                    cornerRadius = radius,
                )
                val alpha = if (cam == big) 0.92f else 0.32f
                indices[cam]?.spans.orEmpty().forEach { sp ->
                    val x0 = xOf(sp.wallStart)
                    val x1 = xOf(sp.wallEnd)
                    if (x1 >= 0f && x0 <= w) {
                        val clampedL = x0.coerceAtLeast(-4f)
                        val clampedR = x1.coerceAtMost(w + 4f)
                        drawRoundRect(
                            color = colors.textDim.copy(alpha = alpha),
                            topLeft = Offset(clampedL, y),
                            size = Size(max(1.dp.toPx(), clampedR - clampedL), laneH),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                }
                sightings.forEach { s ->
                    if (s.cam == cam) {
                        val sx = xOf(s.ts)
                        if (sx > -12f && sx < w + 12f) {
                            val sw = max(4.dp.toPx(), (20.0 / curSpp).toFloat())
                            drawRoundRect(
                                color = colors.warn,
                                topLeft = Offset(sx - 2.dp.toPx(), y + 3.dp.toPx()),
                                size = Size(sw, laneH - 6.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            )
                        }
                    }
                }
            }

            // Centered playhead line + triangle cap
            val midX = w / 2f
            val headTop = 20.dp.toPx()
            drawLine(
                color = colors.accent,
                start = Offset(midX, headTop),
                end = Offset(midX, h),
                strokeWidth = 2.dp.toPx(),
            )
            val tri = Path().apply {
                moveTo(midX - 6.dp.toPx(), headTop)
                lineTo(midX + 6.dp.toPx(), headTop)
                lineTo(midX, headTop + 7.dp.toPx())
                close()
            }
            drawPath(path = tri, color = colors.accent)
        }

        // Top time labels
        if (widthPx > 0f && wallTime > 0.0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val labels = listOf(
                    t0 + span * 0.12,
                    wallTime,
                    t1 - span * 0.12,
                )
                labels.forEachIndexed { idx, ts ->
                    Text(
                        text = if (tickInfo.step < 60.0) fmtHms(ts) else fmtHm(ts),
                        color = if (idx == 1) colors.text else colors.textDim,
                        style = TabularNumStyle.copy(
                            fontSize = 11.sp,
                            fontWeight = if (idx == 1) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        // Lane short labels on left edge
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 29.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecCam.ALL.forEach { cam ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = cam.shortName,
                        color = if (cam == big) colors.text else colors.textDim,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallTimelineButton(
    onClick: () -> Unit,
    label: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(PcDimens.RadiusSm))
            .background(colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusSm))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = colors.text,
                modifier = Modifier.size(18.dp),
            )
        }
        if (label != null) {
            Text(
                text = label,
                color = colors.text,
                style = TabularNumStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun TransportChip(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.text,
            style = TabularNumStyle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

private fun fmtHm(epochSec: Double): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((epochSec * 1000.0).toLong()))

private fun fmtHms(epochSec: Double): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date((epochSec * 1000.0).toLong()))

private fun fmtRate(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}×" else "${rate}×"

private fun dayName(epochSec: Double): String {
    val cal = Calendar.getInstance().apply { timeInMillis = (epochSec * 1000.0).toLong() }
    val today = Calendar.getInstance()
    if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Today"
    }
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Yesterday"
    }
    return SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time)
}

private fun formatAgoSeconds(ts: Double, now: Double): String {
    val mins = floor((now - ts) / 60.0).toInt()
    if (mins < 1) return "just now"
    if (mins < 60) return "$mins min ago"
    val hrs = mins / 60
    return if (hrs < 24) "$hrs h ago" else "${hrs / 24} d ago"
}
