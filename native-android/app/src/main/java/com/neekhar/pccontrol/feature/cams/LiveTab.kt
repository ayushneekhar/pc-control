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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.MonoTextStyle
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
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
 * Live tab: pure-Kotlin WebRTC camera wall (`cat`, `cat2`, `cat3`/`cat3hd`), camera selector
 * pills, HD toggle, cat alerts toggle, pinch-to-zoom video cards, snapshot save, fullscreen
 * landscape, Picture-in-Picture, and native `cat3` PTZ control pad + saved presets.
 */
@Composable
fun LiveTab(
    feeds: Map<CamId, LiveCamFeed>,
    focusedCam: CamId?,
    cat3Hd: Boolean,
    hardwareCaps: CamsHardwareCapabilities,
    camMicOn: Boolean,
    ptzOpen: Boolean,
    ptzPresets: List<PtzPreset>,
    activePresetId: String?,
    snapBusyCam: CamId?,
    detectorState: DetectorState?,
    detectorLoaded: Boolean,
    detectorBusy: Boolean,
    detectorLine: String,
    onSelectFocus: (CamId?) -> Unit,
    onToggleCat3Hd: () -> Unit,
    onToggleCamMic: () -> Unit,
    onTogglePtzOpen: () -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
    onRunPtzPreset: (PtzPreset) -> Unit,
    onSnapshot: (CamId) -> Unit,
    onEnterFullscreen: (CamId) -> Unit,
    onEnterPip: (CamId) -> Unit,
    onToggleDetector: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val cat3Mode = if (cat3Hd) "HD·WebRTC" else "SD·WebRTC"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
    ) {
        // Camera selector pills + HD toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraSelectorPill(
                label = "All",
                selected = focusedCam == null,
                live = feeds.values.any { it.state.fps > 0 },
                onClick = { onSelectFocus(null) },
            )
            CamsConfig.CAMS.forEach { cam ->
                val feed = feeds[cam]
                CameraSelectorPill(
                    label = cam.displayName,
                    selected = focusedCam == cam,
                    live = (feed?.state?.fps ?: 0) > 0,
                    onClick = { onSelectFocus(if (focusedCam == cam) null else cam) },
                )
            }
            // cat3 HD toggle pill
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(if (cat3Hd) colors.accentSoft else colors.surface)
                    .clickable(onClick = onToggleCat3Hd)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Hd,
                    contentDescription = "Food bowl HD",
                    tint = if (cat3Hd) colors.accent else colors.textDim,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (cat3Hd) "HD" else "SD",
                    color = if (cat3Hd) colors.accent else colors.textDim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Cat alerts toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            val detOn = detectorState?.enabled == true
            val detReady = detectorState != null && !detectorBusy
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(if (detOn) colors.accentSoft else colors.surface)
                    .alpha(if (detectorState != null) 1f else 0.55f)
                    .clickable(enabled = detReady) { onToggleDetector(!detOn) }
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = if (detOn) colors.accent else colors.textDim,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Cat alerts",
                    color = if (detOn) colors.text else colors.textDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = detOn,
                    onCheckedChange = { onToggleDetector(it) },
                    enabled = detReady,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textDim,
                        uncheckedTrackColor = colors.surfaceRaised,
                        uncheckedBorderColor = colors.hairline,
                    ),
                )
            }

            Text(
                text = if (detectorState == null && !detectorLoaded) "Connecting to detector…" else detectorLine,
                color = colors.textDim,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // Video feed cards: focused (1 big + telemetry + 2 compact thumbs) or full 3-card stack
        if (focusedCam != null) {
            val mainFeed = feeds[focusedCam] ?: LiveCamFeed(focusedCam, LinkState(), null)
            LiveCamCard(
                feed = mainFeed,
                modeBadge = if (focusedCam == CamId.CAT3) cat3Mode else null,
                focused = true,
                fullscreen = false,
                compact = false,
                camMicOn = camMicOn,
                cat3Hd = cat3Hd,
                ptzOpen = ptzOpen,
                snapBusy = snapBusyCam == focusedCam,
                onTapCard = { onSelectFocus(null) },
                onToggleFocus = { onSelectFocus(null) },
                onSnapshot = { onSnapshot(focusedCam) },
                onFullscreen = { onEnterFullscreen(focusedCam) },
                onPip = { onEnterPip(focusedCam) },
                onToggleCamMic = onToggleCamMic,
                onToggleCat3Hd = onToggleCat3Hd,
                onTogglePtz = onTogglePtzOpen,
                onPtzStart = onPtzStart,
                onPtzStop = onPtzStop,
            )

            StreamTelemetryLine(
                state = mainFeed.state,
                hdDecoder = if (focusedCam == CamId.CAT3 && cat3Hd) hardwareCaps.h265?.name else null,
            )

            if (focusedCam == CamId.CAT3) {
                PtzPresetsSection(
                    presets = ptzPresets,
                    activePresetId = activePresetId,
                    onRunPreset = onRunPtzPreset,
                    onPtzStart = onPtzStart,
                    onPtzStop = onPtzStop,
                )
            }

            val others = CamsConfig.CAMS.filter { it != focusedCam }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                others.forEach { cam ->
                    val feed = feeds[cam] ?: LiveCamFeed(cam, LinkState(), null)
                    LiveCamCard(
                        feed = feed,
                        modeBadge = null,
                        focused = false,
                        fullscreen = false,
                        compact = true,
                        camMicOn = false,
                        cat3Hd = cat3Hd,
                        ptzOpen = false,
                        snapBusy = false,
                        onTapCard = { onSelectFocus(cam) },
                        onToggleFocus = { onSelectFocus(cam) },
                        onSnapshot = {},
                        onFullscreen = {},
                        onPip = {},
                        onToggleCamMic = {},
                        onToggleCat3Hd = {},
                        onTogglePtz = {},
                        onPtzStart = {},
                        onPtzStop = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            CamsConfig.CAMS.forEach { cam ->
                val feed = feeds[cam] ?: LiveCamFeed(cam, LinkState(), null)
                LiveCamCard(
                    feed = feed,
                    modeBadge = if (cam == CamId.CAT3) cat3Mode else null,
                    focused = false,
                    fullscreen = false,
                    compact = false,
                    camMicOn = camMicOn,
                    cat3Hd = cat3Hd,
                    ptzOpen = ptzOpen,
                    snapBusy = snapBusyCam == cam,
                    onTapCard = { onSelectFocus(cam) },
                    onToggleFocus = { onSelectFocus(cam) },
                    onSnapshot = { onSnapshot(cam) },
                    onFullscreen = { onEnterFullscreen(cam) },
                    onPip = { onEnterPip(cam) },
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

        if (!hardwareCaps.liveHdSupported && !hardwareCaps.hdReason.isNullOrBlank()) {
            Text(
                text = "Food bowl HD unavailable: ${hardwareCaps.hdReason}.",
                color = colors.textFaint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Fullscreen landscape live camera view. */
@Composable
fun LiveFullscreenOverlay(
    feed: LiveCamFeed,
    cat3Hd: Boolean,
    camMicOn: Boolean,
    ptzOpen: Boolean,
    snapBusy: Boolean,
    onExitFullscreen: () -> Unit,
    onSnapshot: () -> Unit,
    onPip: () -> Unit,
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
            modeBadge = if (feed.camId == CamId.CAT3) (if (cat3Hd) "HD·WebRTC" else "SD·WebRTC") else null,
            focused = true,
            fullscreen = true,
            compact = false,
            camMicOn = camMicOn,
            cat3Hd = cat3Hd,
            ptzOpen = ptzOpen,
            snapBusy = snapBusy,
            onTapCard = {},
            onToggleFocus = {},
            onSnapshot = onSnapshot,
            onFullscreen = onExitFullscreen,
            onPip = onPip,
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
private fun CameraSelectorPill(
    label: String,
    selected: Boolean,
    live: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PcStatusDot(
            tone = if (live) PcTone.Ok else PcTone.Faint,
            size = 6.dp,
        )
        Text(
            text = label,
            color = if (selected) colors.accent else colors.textDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveCamCard(
    feed: LiveCamFeed,
    modeBadge: String?,
    focused: Boolean,
    fullscreen: Boolean,
    compact: Boolean,
    camMicOn: Boolean,
    cat3Hd: Boolean,
    ptzOpen: Boolean,
    snapBusy: Boolean,
    onTapCard: () -> Unit,
    onToggleFocus: () -> Unit,
    onSnapshot: () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onToggleCamMic: () -> Unit,
    onToggleCat3Hd: () -> Unit,
    onTogglePtz: () -> Unit,
    onPtzStart: (String) -> Unit,
    onPtzStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val state = feed.state
    val showStatusCenter = feed.videoTrack == null || state.fps == 0
    val corner = when {
        fullscreen -> 0.dp
        compact -> PcDimens.RadiusSm
        else -> PcDimens.RadiusLg
    }

    val cardModifier = if (fullscreen) {
        modifier
            .fillMaxSize()
            .background(colors.media)
    } else {
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(colors.media)
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                PinchZoomBox(
                    enabled = true,
                    resetKey = "${feed.camId.id}:$fullscreen:$focused",
                    onTap = if (fullscreen) null else onTapCard,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    WebRtcLivePlayer(
                        videoTrack = feed.videoTrack,
                        zoomable = true,
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

        // Center connecting / error state
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

        // Top-left camera name + live badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 6.dp else 10.dp),
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
                    text = feed.camId.displayName,
                    color = colors.onMedia,
                    fontSize = if (compact) 11.5.sp else 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                LiveStatusBadge(state = state, modeBadge = modeBadge)
            }
        }

        // Top-right overlay actions (Snapshot, PiP, Fullscreen, Focus)
        if (!compact) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OverlayIconButton(
                    icon = Icons.Filled.CameraAlt,
                    contentDescription = "Save snapshot",
                    busy = snapBusy,
                    onClick = onSnapshot,
                )
                OverlayIconButton(
                    icon = Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Picture-in-Picture",
                    onClick = onPip,
                )
                OverlayIconButton(
                    icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                    onClick = onFullscreen,
                )
                if (!fullscreen) {
                    OverlayIconButton(
                        icon = if (focused) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                        contentDescription = if (focused) "Back to all cameras" else "Focus camera",
                        onClick = onToggleFocus,
                    )
                }
            }
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
private fun LiveStatusBadge(
    state: LinkState,
    modeBadge: String?,
) {
    val colors = LocalPcColors.current
    val tone = when {
        state.fps >= 4 -> PcTone.Ok
        state.fps > 0 -> PcTone.Warn
        else -> PcTone.Bad
    }
    val text = when {
        state.fps > 0 -> "${state.fps} fps${if (modeBadge != null) " · $modeBadge" else ""}"
        state.phase == LinkPhase.RETRYING -> "retry in ${state.retryIn ?: 1}s"
        state.phase == LinkPhase.ERROR -> "offline"
        state.phase == LinkPhase.STALLED -> "stalled"
        else -> "connecting"
    }
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(colors.mediaChip)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PcStatusDot(tone = tone, size = 6.dp)
        Text(
            text = text,
            color = colors.onMediaDim,
            style = TabularNumStyle.copy(fontSize = 11.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    val colors = LocalPcColors.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.mediaChip)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.onMedia,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.onMedia,
                modifier = Modifier.size(20.dp),
            )
        }
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
