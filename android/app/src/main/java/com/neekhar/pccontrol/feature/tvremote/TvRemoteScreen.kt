package com.neekhar.pccontrol.feature.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcEmptyState
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcIconButton
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcTone
import kotlin.math.roundToLong

private enum class ActiveTrackPicker {
    AUDIO,
    SUBTITLE,
}

private data class TrackPickerOption(
    val index: Int,
    val title: String,
    val subtitle: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteScreen(
    viewModel: TvRemoteViewModel,
    onBack: () -> Unit,
    onOpenSeerr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val pullState = rememberPullToRefreshState()
    var activePicker by remember { mutableStateOf<ActiveTrackPicker?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.setFullScreenActive(true)
        viewModel.refreshDevices()
        onPauseOrDispose {
            viewModel.setFullScreenActive(false)
        }
    }

    if (showDevicePicker) {
        TvDevicePickerSheet(
            devices = state.availableDevices,
            loading = state.loadingDevices,
            selectedSessionId = state.selectedSessionId,
            selectedDeviceName = state.selectedDeviceName,
            onSelect = { device ->
                showDevicePicker = false
                viewModel.selectDevice(device)
            },
            onRefresh = { viewModel.refreshDevices() },
            onDismiss = { showDevicePicker = false },
            title = "Switch TV",
        )
    }

    val (statusText, statusTone) = when {
        !state.loaded && state.session == null -> "Connecting…" to PcTone.Faint
        state.isPlaying && !state.isPaused -> "Playing" to PcTone.Ok
        state.isPlaying && state.isPaused -> "Paused" to PcTone.Warn
        state.connected -> "Connected · Idle" to PcTone.Dim
        else -> "Offline" to PcTone.Faint
    }

    PcScaffold(
        modifier = modifier,
        title = state.deviceName,
        statusText = statusText,
        statusTone = statusTone,
        showStatusDot = true,
        onBack = onBack,
        collapsing = false,
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surfaceRaised)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(999.dp))
                        .clickable {
                            viewModel.refreshDevices()
                            showDevicePicker = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = "Switch TV",
                        tint = if (state.connected) colors.accent else colors.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (state.availableDevices.size > 1) {
                            "${state.availableDevices.size} TVs"
                        } else {
                            "Devices"
                        },
                        color = colors.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                PcIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Refresh TV session",
                    onClick = { viewModel.refreshNow(userInitiated = true) },
                    tint = colors.textDim,
                )
            }
        },
    ) { innerPadding ->
        val topPad = innerPadding.calculateTopPadding()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refreshNow(userInitiated = true) },
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    containerColor = colors.surfaceRaised,
                    color = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPad),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = PcDimens.ScreenPad,
                        end = PcDimens.ScreenPad,
                        top = topPad + 4.dp,
                        bottom = PcDimens.Space8,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.commandError.isNullOrBlank()) {
                    PcErrorBanner(
                        title = "TV Remote",
                        message = state.commandError,
                        tone = PcTone.Warn,
                    )
                }

                val session = state.session
                val nowPlaying = session?.nowPlaying
                when {
                    !state.loaded && session == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    session == null || !session.connected -> {
                        if (!state.error.isNullOrBlank()) {
                            PcErrorBanner(
                                title = "Can't reach Jellyfin",
                                message = state.error,
                                tone = PcTone.Bad,
                                actionLabel = "Retry",
                                onAction = { viewModel.refreshNow(userInitiated = true) },
                            )
                        } else {
                            PcCard {
                                PcEmptyState(
                                    icon = Icons.Rounded.Tv,
                                    title = "${state.deviceName} isn't connected",
                                    body = "Open Jellyfin on any TV to control playback from here.",
                                    action = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PcButton(
                                                label = "Switch TV",
                                                onClick = {
                                                    viewModel.refreshDevices()
                                                    showDevicePicker = true
                                                },
                                                variant = PcButtonVariant.Subtle,
                                                icon = Icons.Rounded.Tv,
                                            )
                                            PcButton(
                                                label = "Retry",
                                                onClick = { viewModel.refreshNow(userInitiated = true) },
                                                variant = PcButtonVariant.Subtle,
                                                icon = Icons.Rounded.Refresh,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    !session.isPlaying || nowPlaying == null -> {
                        PcCard {
                            PcEmptyState(
                                icon = Icons.Rounded.Tv,
                                title = "Nothing playing on ${state.deviceName}",
                                body = "Pick something from Watch to start playing on your TV.",
                                action = {
                                    PcButton(
                                        label = "Browse Watch",
                                        onClick = onOpenSeerr,
                                        variant = PcButtonVariant.Primary,
                                        icon = Icons.Rounded.Movie,
                                    )
                                },
                            )
                        }
                        if (TvRemoteFormatters.isVolumeSupported(session)) {
                            TvVolumeCard(
                                session = session,
                                onVolumeDown = viewModel::volumeDown,
                                onToggleMute = viewModel::toggleMute,
                                onVolumeUp = viewModel::volumeUp,
                            )
                        }
                    }

                    else -> {
                        val posterUrl = viewModel.resolveImageUrl(nowPlaying.posterUrl)
                        val subtitleLine = TvRemoteFormatters.secondarySubtitle(nowPlaying)

                        // 1. Poster + Title Hero
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(164.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(PcDimens.RadiusLg))
                                    .background(colors.surfaceRaised),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (nowPlaying.isEpisode) Icons.Rounded.Tv else Icons.Rounded.Movie,
                                    contentDescription = null,
                                    tint = colors.textFaint,
                                    modifier = Modifier.size(36.dp),
                                )
                                if (!posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterUrl,
                                        contentDescription = nowPlaying.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = TvRemoteFormatters.primaryTitle(nowPlaying),
                                    color = colors.text,
                                    fontSize = 20.sp,
                                    lineHeight = 25.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!subtitleLine.isNullOrBlank()) {
                                    Text(
                                        text = subtitleLine,
                                        color = colors.textDim,
                                        style = type.caption.tabular(),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // 2. Live Scrubber & Tabular Timestamps
                        val durationSec = state.durationSeconds.roundToLong().coerceAtLeast(0L)
                        val displayedSec = state.displayedPositionSeconds.roundToLong().coerceIn(0L, durationSec.coerceAtLeast(0L))
                        val sliderMax = state.durationSeconds.toFloat().coerceAtLeast(1f)
                        val sliderVal = state.displayedPositionSeconds.toFloat().coerceIn(0f, sliderMax)

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Slider(
                                value = sliderVal,
                                onValueChange = viewModel::onScrubChange,
                                onValueChangeFinished = viewModel::onScrubCommit,
                                valueRange = 0f..sliderMax,
                                enabled = session.canSeek && durationSec > 0L,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent,
                                    inactiveTrackColor = colors.meterTrack,
                                    disabledThumbColor = colors.textFaint,
                                    disabledActiveTrackColor = colors.textFaint,
                                    disabledInactiveTrackColor = colors.meterTrack,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = TvRemoteFormatters.formatDuration(displayedSec),
                                    color = colors.textDim,
                                    style = TabularNumStyle.copy(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                                Text(
                                    text = if (state.showRemainingTime) {
                                        TvRemoteFormatters.formatRemaining(displayedSec, durationSec)
                                    } else {
                                        TvRemoteFormatters.formatDuration(durationSec)
                                    },
                                    color = colors.textDim,
                                    style = TabularNumStyle.copy(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = viewModel::toggleTimeDisplayMode)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }

                        // 3. Primary Transport Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PcIconButton(
                                icon = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                onClick = viewModel::previousTrack,
                                tint = colors.text,
                                iconSize = 26.dp,
                                visualSize = 44.dp,
                            )
                            PcIconButton(
                                icon = Icons.Rounded.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                onClick = { viewModel.seekRelative(-10.0) },
                                enabled = session.canSeek,
                                tint = colors.text,
                                iconSize = 28.dp,
                                visualSize = 44.dp,
                            )
                            PlayPausePrimaryButton(
                                isPaused = session.isPaused,
                                onClick = viewModel::togglePlayPause,
                            )
                            PcIconButton(
                                icon = Icons.Rounded.Forward30,
                                contentDescription = "Forward 30 seconds",
                                onClick = { viewModel.seekRelative(30.0) },
                                enabled = session.canSeek,
                                tint = colors.text,
                                iconSize = 28.dp,
                                visualSize = 44.dp,
                            )
                            PcIconButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                onClick = viewModel::nextTrack,
                                tint = colors.text,
                                iconSize = 26.dp,
                                visualSize = 44.dp,
                            )
                        }

                        // 4. Audio & Subtitle Track Pickers
                        val audioEnabled = session.audioTracks.isNotEmpty() &&
                            TvRemoteFormatters.supportsCommand(session, "SetAudioStreamIndex")
                        val subtitlesEnabled = session.subtitleTracks.isNotEmpty() &&
                            TvRemoteFormatters.supportsCommand(session, "SetSubtitleStreamIndex")
                        val subtitleActive = (session.subtitleStreamIndex ?: -1) >= 0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvTrackSummaryCard(
                                label = "Audio",
                                value = TvRemoteFormatters.activeAudioLabel(session),
                                icon = Icons.Rounded.Audiotrack,
                                valueColor = colors.text,
                                enabled = audioEnabled,
                                onClick = { activePicker = ActiveTrackPicker.AUDIO },
                                modifier = Modifier.weight(1f),
                            )
                            TvTrackSummaryCard(
                                label = "Subtitles",
                                value = TvRemoteFormatters.activeSubtitleLabel(session),
                                icon = if (subtitleActive) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                                valueColor = if (subtitleActive) colors.accent else colors.textDim,
                                enabled = subtitlesEnabled,
                                onClick = { activePicker = ActiveTrackPicker.SUBTITLE },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 5. Volume Controls
                        if (TvRemoteFormatters.isVolumeSupported(session)) {
                            TvVolumeCard(
                                session = session,
                                onVolumeDown = viewModel::volumeDown,
                                onToggleMute = viewModel::toggleMute,
                                onVolumeUp = viewModel::volumeUp,
                            )
                        }

                        // 6. Stop Playback
                        PcButton(
                            label = "Stop playback",
                            onClick = viewModel::stop,
                            variant = PcButtonVariant.Danger,
                            icon = Icons.Rounded.Stop,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    // Track Picker Dialog
    val currentSession = state.session
    if (activePicker != null && currentSession != null && currentSession.isPlaying) {
        when (activePicker) {
            ActiveTrackPicker.AUDIO -> {
                val options = currentSession.audioTracks.map { track ->
                    TrackPickerOption(
                        index = track.index,
                        title = track.displayTitle.ifBlank { "Track ${track.index}" },
                        subtitle = TvRemoteFormatters.formatAudioTrackMeta(track),
                    )
                }
                TvTrackPickerDialog(
                    title = "Audio track",
                    options = options,
                    selectedIndex = currentSession.audioStreamIndex,
                    onSelect = { idx ->
                        activePicker = null
                        viewModel.setAudioStream(idx)
                    },
                    onDismiss = { activePicker = null },
                )
            }

            ActiveTrackPicker.SUBTITLE -> {
                val options = buildList {
                    add(TrackPickerOption(index = -1, title = "Off", subtitle = "Disable subtitles"))
                    currentSession.subtitleTracks.forEach { track ->
                        add(
                            TrackPickerOption(
                                index = track.index,
                                title = track.displayTitle.ifBlank { "Subtitle ${track.index}" },
                                subtitle = TvRemoteFormatters.formatSubtitleTrackMeta(track),
                            ),
                        )
                    }
                }
                val selectedSub = currentSession.subtitleStreamIndex?.takeIf { it >= 0 } ?: -1
                TvTrackPickerDialog(
                    title = "Subtitles",
                    options = options,
                    selectedIndex = selectedSub,
                    onSelect = { idx ->
                        activePicker = null
                        viewModel.setSubtitleStream(idx)
                    },
                    onDismiss = { activePicker = null },
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun PlayPausePrimaryButton(
    isPaused: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPcColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            contentDescription = if (isPaused) "Play" else "Pause",
            tint = colors.onAccent,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun TvTrackSummaryCard(
    label: String,
    value: String,
    icon: ImageVector,
    valueColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    PcCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textDim,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    color = colors.textFaint,
                    style = type.micro,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    color = valueColor,
                    style = type.cardTitle.copy(fontSize = 13.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvVolumeCard(
    session: TvSessionResponse,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeUp: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val volDownEnabled = TvRemoteFormatters.supportsCommand(session, "VolumeDown")
    val muteEnabled = TvRemoteFormatters.supportsCommand(session, "ToggleMute")
    val volUpEnabled = TvRemoteFormatters.supportsCommand(session, "VolumeUp")

    PcCard(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "Volume",
                    color = colors.text,
                    style = type.cardTitle,
                )
                val volSubtitle = when {
                    session.isMuted -> "Muted"
                    session.volumeLevel != null -> "${session.volumeLevel}%"
                    else -> "Projector audio"
                }
                Text(
                    text = volSubtitle,
                    color = if (session.isMuted) colors.warn else colors.textFaint,
                    style = type.caption.tabular(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PcIconButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeDown,
                    contentDescription = "Volume down",
                    onClick = onVolumeDown,
                    enabled = volDownEnabled,
                    tint = colors.text,
                    containerColor = colors.surfaceRaised,
                    iconSize = 20.dp,
                    visualSize = 40.dp,
                )
                PcIconButton(
                    icon = if (session.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (session.isMuted) "Unmute" else "Mute",
                    onClick = onToggleMute,
                    enabled = muteEnabled,
                    tint = if (session.isMuted) colors.accent else colors.text,
                    containerColor = if (session.isMuted) colors.accentSoft else colors.surfaceRaised,
                    iconSize = 20.dp,
                    visualSize = 40.dp,
                )
                PcIconButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "Volume up",
                    onClick = onVolumeUp,
                    enabled = volUpEnabled,
                    tint = colors.text,
                    containerColor = colors.surfaceRaised,
                    iconSize = 20.dp,
                    visualSize = 40.dp,
                )
            }
        }
    }
}

@Composable
private fun TvTrackPickerDialog(
    title: String,
    options: List<TrackPickerOption>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PcDimens.RadiusLg))
                .background(colors.surface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusLg))
                .padding(top = 18.dp, bottom = 12.dp),
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { idx, option ->
                    val selected = option.index == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(
                                role = Role.RadioButton,
                                interactionSource = interactionSource,
                                indication = ripple(),
                                onClick = { onSelect(option.index) },
                            )
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) {
                                Icons.Rounded.RadioButtonChecked
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (selected) colors.accent else colors.textFaint,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = option.title,
                                color = if (selected) colors.text else colors.textDim,
                                style = type.cardTitle.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!option.subtitle.isNullOrBlank()) {
                                Text(
                                    text = option.subtitle,
                                    color = colors.textFaint,
                                    style = type.caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (idx < options.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            thickness = 0.5.dp,
                            color = colors.hairline,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                PcButton(
                    label = "Close",
                    onClick = onDismiss,
                    variant = PcButtonVariant.Subtle,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
