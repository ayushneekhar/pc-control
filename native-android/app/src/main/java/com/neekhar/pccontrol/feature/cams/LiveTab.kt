package com.neekhar.pccontrol.feature.cams

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcSectionLabel
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import org.webrtc.VideoTrack

data class LiveCamFeed(
    val camId: CamId,
    val state: LinkState,
    val videoTrack: VideoTrack?,
)

/**
 * Live tab: pure-Kotlin WebRTC camera wall (`cat`, `cat2`, `cat3`/`cat3hd`), hero + thumbnail strip
 * default layout with optional stacked grid, pinch-to-zoom video cards with instant JPEG frame
 * placeholders, fullscreen landscape, and native `cat3` PTZ control pad + saved presets.
 */
@Composable
fun LiveTab(
    feeds: Map<CamId, LiveCamFeed>,
    snapshotBaseUrl: String,
    heroCam: CamId,
    stackedLayout: Boolean,
    cat3Hd: Boolean,
    hardwareCaps: CamsHardwareCapabilities,
    camMicOn: Boolean,
    ptzOpen: Boolean,
    ptzPresets: List<PtzPreset>,
    activePresetId: String?,
    onSelectHero: (CamId) -> Unit,
    onToggleCat3Hd: () -> Unit,
    onToggleCamMic: () -> Unit,
    onTogglePtzOpen: () -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
    onRunPtzPreset: (PtzPreset) -> Unit,
    onEnterFullscreen: (CamId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
    ) {
        if (!stackedLayout) {
            // Default Hero + Thumbnail strip layout: 1 large 16:9 hero feed + compact horizontal strip below
            val mainFeed = feeds[heroCam] ?: LiveCamFeed(heroCam, LinkState(), null)
            LiveCamCard(
                feed = mainFeed,
                snapshotBaseUrl = snapshotBaseUrl,
                focused = true,
                fullscreen = false,
                compact = false,
                camMicOn = camMicOn,
                cat3Hd = cat3Hd,
                ptzOpen = ptzOpen,
                onTapCard = {},
                onFullscreen = { onEnterFullscreen(heroCam) },
                onToggleCamMic = onToggleCamMic,
                onToggleCat3Hd = onToggleCat3Hd,
                onTogglePtz = onTogglePtzOpen,
                onPtzStart = onPtzStart,
                onPtzStop = onPtzStop,
            )

            val others = CamsConfig.CAMS.filter { it != heroCam }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                others.forEach { cam ->
                    val feed = feeds[cam] ?: LiveCamFeed(cam, LinkState(), null)
                    LiveCamCard(
                        feed = feed,
                        snapshotBaseUrl = snapshotBaseUrl,
                        focused = false,
                        fullscreen = false,
                        compact = true,
                        camMicOn = false,
                        cat3Hd = cat3Hd,
                        ptzOpen = false,
                        onTapCard = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSelectHero(cam)
                        },
                        onFullscreen = {},
                        onToggleCamMic = {},
                        onToggleCat3Hd = {},
                        onTogglePtz = {},
                        onPtzStart = {},
                        onPtzStop = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            StreamTelemetryLine(
                state = mainFeed.state,
                hdDecoder = if (heroCam == CamId.CAT3 && cat3Hd) hardwareCaps.h265?.name else null,
            )

            if (heroCam == CamId.CAT3) {
                PtzPresetsSection(
                    presets = ptzPresets,
                    activePresetId = activePresetId,
                    onRunPreset = onRunPtzPreset,
                    onPtzStart = onPtzStart,
                    onPtzStop = onPtzStop,
                )
            }
        } else {
            // Optional stacked full-width grid view
            CamsConfig.CAMS.forEach { cam ->
                val feed = feeds[cam] ?: LiveCamFeed(cam, LinkState(), null)
                LiveCamCard(
                    feed = feed,
                    snapshotBaseUrl = snapshotBaseUrl,
                    focused = false,
                    fullscreen = false,
                    compact = false,
                    camMicOn = camMicOn,
                    cat3Hd = cat3Hd,
                    ptzOpen = ptzOpen,
                    onTapCard = { onSelectHero(cam) },
                    onFullscreen = { onEnterFullscreen(cam) },
                    onToggleCamMic = onToggleCamMic,
                    onToggleCat3Hd = onToggleCat3Hd,
                    onTogglePtz = onTogglePtzOpen,
                    onPtzStart = onPtzStart,
                    onPtzStop = onPtzStop,
                )
            }

            if (ptzOpen) {
                PtzPresetsSection(
                    presets = ptzPresets,
                    activePresetId = activePresetId,
                    onRunPreset = onRunPtzPreset,
                    onPtzStart = onPtzStart,
                    onPtzStop = onPtzStop,
                )
            }
        }
    }
}

/** Fullscreen landscape live camera view. */
@Composable
fun LiveFullscreenOverlay(
    feed: LiveCamFeed,
    snapshotBaseUrl: String,
    cat3Hd: Boolean,
    camMicOn: Boolean,
    ptzOpen: Boolean,
    onExitFullscreen: () -> Unit,
    onToggleCamMic: () -> Unit,
    onToggleCat3Hd: () -> Unit,
    onTogglePtz: () -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.media),
    ) {
        LiveCamCard(
            feed = feed,
            snapshotBaseUrl = snapshotBaseUrl,
            focused = true,
            fullscreen = true,
            compact = false,
            camMicOn = camMicOn,
            cat3Hd = cat3Hd,
            ptzOpen = ptzOpen,
            onTapCard = {},
            onFullscreen = onExitFullscreen,
            onToggleCamMic = onToggleCamMic,
            onToggleCat3Hd = onToggleCat3Hd,
            onTogglePtz = onTogglePtz,
            onPtzStart = onPtzStart,
            onPtzStop = onPtzStop,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun LiveCamCard(
    feed: LiveCamFeed,
    snapshotBaseUrl: String,
    focused: Boolean,
    fullscreen: Boolean,
    compact: Boolean,
    camMicOn: Boolean,
    cat3Hd: Boolean,
    ptzOpen: Boolean,
    onTapCard: () -> Unit,
    onFullscreen: () -> Unit,
    onToggleCamMic: () -> Unit,
    onToggleCat3Hd: () -> Unit,
    onTogglePtz: () -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val state = feed.state
    var firstFrameRendered by remember(feed.camId, feed.videoTrack) { mutableStateOf(false) }
    var snapshotReady by remember(feed.camId, snapshotBaseUrl) { mutableStateOf(false) }
    val videoLive = feed.videoTrack != null && (firstFrameRendered || state.fps > 0)
    val showStatusCenter = !videoLive && (!snapshotReady || state.phase == LinkPhase.ERROR || state.phase == LinkPhase.RETRYING || state.phase == LinkPhase.STALLED)
    val corner = when {
        fullscreen -> 0.dp
        compact -> PcDimens.RadiusSm
        else -> PcDimens.RadiusLg
    }

    val snapshotRequest = remember(snapshotBaseUrl, feed.camId) {
        val url = "${snapshotBaseUrl.trimEnd('/')}/api/frame.jpeg?src=${feed.camId.id}"
        ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    val cardModifier = if (fullscreen) {
        modifier
            .fillMaxSize()
            .background(colors.media)
    } else {
        val shape = RoundedCornerShape(corner)
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(colors.media)
            .then(
                if (compact) Modifier.border(0.5.dp, colors.hairline, shape) else Modifier,
            )
    }

    Box(modifier = cardModifier) {
        if (feed.videoTrack != null) {
            if (compact) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onTapCard),
                ) {
                    WebRtcLivePlayer(
                        videoTrack = feed.videoTrack,
                        zoomable = false,
                        onFirstFrameRendered = { firstFrameRendered = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                PinchZoomBox(
                    enabled = true,
                    resetKey = "${feed.camId.id}:$fullscreen:$focused",
                    onTap = if (fullscreen || focused) null else onTapCard,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    WebRtcLivePlayer(
                        videoTrack = feed.videoTrack,
                        zoomable = true,
                        onFirstFrameRendered = { firstFrameRendered = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = !fullscreen, onClick = onTapCard),
            )
        }

        // Instant JPEG snapshot placeholder while WebRTC negotiates & decodes its first frame
        if (!videoLive && snapshotBaseUrl.isNotBlank()) {
            AsyncImage(
                model = snapshotRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { snapshotReady = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Center connecting / error state (hidden once snapshot or live frame is visible)
        if (showStatusCenter && !compact) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.phase == LinkPhase.ERROR) {
                    Icon(
                        imageVector = Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = colors.onMediaDim,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = colors.onMediaDim,
                    )
                }
                Text(
                    text = when (state.phase) {
                        LinkPhase.RETRYING -> "${state.error ?: "Disconnected"} · retrying in ${state.retryIn ?: 1}s"
                        LinkPhase.ERROR -> state.error ?: "Offline"
                        LinkPhase.STALLED -> "Stalled · waiting for frames…"
                        else -> if (state.attempt > 1) "Reconnecting…" else "Connecting…"
                    },
                    color = colors.onMediaDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Minimal top-left camera label (compact status dot + shadowed name, no noisy fps/codec clutter)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dotTone = when {
                videoLive -> PcTone.Ok
                state.phase == LinkPhase.ERROR -> PcTone.Bad
                else -> PcTone.Warn
            }
            PcStatusDot(tone = dotTone, size = 6.dp)
            Text(
                text = feed.camId.displayName,
                color = Color.White,
                style = TextStyle(
                    fontSize = if (compact) 11.5.sp else 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.75f),
                        offset = Offset(0f, 1f),
                        blurRadius = 4f,
                    ),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Top-right single borderless Fullscreen icon (no dark square backdrop)
        if (!compact) {
            BorderlessOverlayIconButton(
                icon = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                onClick = onFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }

        // cat3 bottom-left control chips (Cam mic, HD, PTZ) + bottom-right PTZ D-pad
        if (!compact && feed.camId == CamId.CAT3) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(if (fullscreen) 16.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OverlayToggleChip(
                    icon = if (camMicOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (camMicOn) "Cam mic on" else "Cam mic off",
                    active = camMicOn,
                    onClick = onToggleCamMic,
                )
                OverlayToggleChip(
                    icon = Icons.Filled.Hd,
                    contentDescription = if (cat3Hd) "HD on" else "HD off",
                    active = cat3Hd,
                    onClick = onToggleCat3Hd,
                )
                OverlayToggleChip(
                    icon = Icons.Filled.ControlCamera,
                    contentDescription = "Pan / tilt",
                    active = ptzOpen,
                    onClick = onTogglePtz,
                )
            }

            if (ptzOpen) {
                PtzControlPad(
                    size = if (fullscreen) 172.dp else 142.dp,
                    onStart = onPtzStart,
                    onStop = onPtzStop,
                    onClose = onTogglePtz,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(if (fullscreen) 20.dp else 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BorderlessOverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .offset(x = 0.5.dp, y = 1.dp)
                .size(22.dp),
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun OverlayToggleChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) colors.accent else colors.mediaChip)
            .border(
                width = 0.5.dp,
                color = if (active) colors.accent else colors.mediaChipBorder,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) colors.onAccent else colors.onMedia,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Press-and-hold directional PTZ pad for `cat3` (`cam-ptz`).
 * Sends `/ptz/move?dir=...` on pointer down and immediately sends `/ptz/stop` on pointer release/cancel.
 */
@Composable
fun PtzControlPad(
    size: Dp,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val view = LocalView.current
    var activeDir by remember { mutableStateOf<String?>(null) }
    val cell = size / 3f

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.mediaChip)
            .border(0.5.dp, colors.mediaChipBorder, CircleShape),
    ) {
        PtzDirButton(
            dir = "up",
            icon = Icons.Filled.KeyboardArrowUp,
            cellSize = cell,
            active = activeDir == "up",
            onPress = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeDir = "up"
                onStart("up")
            },
            onRelease = {
                activeDir = null
                onStop()
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        PtzDirButton(
            dir = "down",
            icon = Icons.Filled.KeyboardArrowDown,
            cellSize = cell,
            active = activeDir == "down",
            onPress = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeDir = "down"
                onStart("down")
            },
            onRelease = {
                activeDir = null
                onStop()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        PtzDirButton(
            dir = "left",
            icon = Icons.Filled.KeyboardArrowLeft,
            cellSize = cell,
            active = activeDir == "left",
            onPress = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeDir = "left"
                onStart("left")
            },
            onRelease = {
                activeDir = null
                onStop()
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )
        PtzDirButton(
            dir = "right",
            icon = Icons.Filled.KeyboardArrowRight,
            cellSize = cell,
            active = activeDir == "right",
            onPress = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                activeDir = "right"
                onStart("right")
            },
            onRelease = {
                activeDir = null
                onStop()
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(cell * 0.78f)
                .clip(CircleShape)
                .background(colors.ripple)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close pan / tilt",
                tint = colors.onMedia,
                modifier = Modifier.size(cell * 0.4f),
            )
        }
    }
}

@Composable
private fun PtzDirButton(
    dir: String,
    icon: ImageVector,
    cellSize: Dp,
    active: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = modifier
            .size(cellSize)
            .clip(CircleShape)
            .background(if (active) colors.accent else colors.mediaChip.copy(alpha = 0f))
            .pointerInput(dir) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress()
                    waitForUpOrCancellation()
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Move $dir",
            tint = if (active) colors.onAccent else colors.onMedia,
            modifier = Modifier.size(cellSize * 0.62f),
        )
    }
}

@Composable
private fun PtzPresetsSection(
    presets: List<PtzPreset>,
    activePresetId: String?,
    onRunPreset: (PtzPreset) -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
) {
    val colors = LocalPcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PcDimens.Space2)) {
        PcSectionLabel(label = "Food bowl PTZ presets", icon = Icons.Filled.ControlCamera)
        PcCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                presets.forEach { preset ->
                    val busy = activePresetId == preset.id
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (busy) colors.accentSoft else colors.surfaceRaised)
                            .clickable(enabled = activePresetId == null) { onRunPreset(preset) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = colors.accent,
                            )
                        }
                        Text(
                            text = preset.label,
                            color = if (busy) colors.accent else colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(PcDimens.Space3))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            ) {
                listOf(
                    "left" to Icons.Filled.KeyboardArrowLeft,
                    "up" to Icons.Filled.KeyboardArrowUp,
                    "down" to Icons.Filled.KeyboardArrowDown,
                    "right" to Icons.Filled.KeyboardArrowRight,
                ).forEach { (dir, icon) ->
                    var held by remember { mutableStateOf(false) }
                    val view = LocalView.current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(PcDimens.RadiusSm))
                            .background(if (held) colors.accent else colors.surfaceRaised)
                            .pointerInput(dir) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    held = true
                                    onPtzStart(dir)
                                    waitForUpOrCancellation()
                                    held = false
                                    onPtzStop()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Pan $dir",
                            tint = if (held) colors.onAccent else colors.text,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamTelemetryLine(
    state: LinkState,
    hdDecoder: String?,
) {
    val colors = LocalPcColors.current
    val parts = buildList {
        state.codec?.let { add(it) }
        state.size?.let { add(it) }
        (state.decoder ?: hdDecoder)?.let { add(it) }
        state.bufferMs?.let { add("buffer $it ms") }
        state.rttMs?.let { add("rtt $it ms") }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        color = colors.textFaint,
        style = MonoTextStyle.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
