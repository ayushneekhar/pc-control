package com.neekhar.pccontrol.feature.watch.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.neekhar.pccontrol.MainActivity
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.theme.DarkPcColors
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.feature.tvremote.TvRemoteViewModel
import com.neekhar.pccontrol.feature.tvremote.rememberSmartCastController
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

private enum class GestureOverlayKind {
    BRIGHTNESS,
    VOLUME,
    SEEK_BACK,
    SEEK_FORWARD,
}

private data class GestureOverlayState(
    val kind: GestureOverlayKind,
    val label: String,
    val fraction: Float? = null,
)

@OptIn(UnstableApi::class)
@Composable
fun WatchPlayerScreen(
    viewModel: WatchPlayerViewModel,
    activityProvider: () -> FragmentActivity?,
    isInPictureInPictureMode: Boolean,
    onBack: () -> Unit,
    onHandedOffToTv: () -> Unit,
    mediaStreamingClient: OkHttpClient? = null,
    tvRemoteViewModel: TvRemoteViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnHandedOff by rememberUpdatedState(onHandedOffToTv)
    val latestInPip by rememberUpdatedState(isInPictureInPictureMode)
    val latestState by rememberUpdatedState(state)
    val smartCast = rememberSmartCastController(
        tvRemoteViewModel = tvRemoteViewModel,
        onNoDevices = { msg -> viewModel.showCastMessage(msg) },
    )

    var interactionTick by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var gestureOverlay by remember { mutableStateOf<GestureOverlayState?>(null) }
    var gestureOverlayTick by remember { mutableIntStateOf(0) }
    var videoAspect by remember { mutableStateOf(16 to 9) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var exoTracksVersion by remember { mutableIntStateOf(0) }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // Memory-safe LRU cache for Trickplay sprite sheets (evicted on item change or player exit)
    val trickplayCache = remember(state.jellyfinItemId, state.trickplay?.tileUrlTemplate, mediaStreamingClient) {
        TrickplayTileCache(httpClient = mediaStreamingClient)
    }
    DisposableEffect(trickplayCache) {
        onDispose { trickplayCache.clear() }
    }

    // Build ExoPlayer once for this player route
    val exoPlayer = remember(context, mediaStreamingClient) {
        val httpFactory: DataSource.Factory = if (mediaStreamingClient != null) {
            OkHttpDataSource.Factory(mediaStreamingClient)
                .setUserAgent(PcApiClient.USER_AGENT)
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(PcApiClient.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // Listen to ExoPlayer state changes
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width to videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                exoTracksVersion++
                val currentState = latestState
                if (!currentState.isOffline && !currentState.isHls) {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isNotEmpty()) {
                        val hasSupportedAudio = audioGroups.any { group ->
                            (0 until group.length).any { idx -> group.isTrackSupported(idx) }
                        }
                        if (!hasSupportedAudio) {
                            viewModel.onDirectPlayAudioUnsupported()
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.onPlaybackProgress(
                    positionMs = exoPlayer.currentPosition,
                    bufferedMs = exoPlayer.bufferedPosition,
                    durationMs = if (exoPlayer.duration != C.TIME_UNSET) exoPlayer.duration else 0L,
                    isPlaying = exoPlayer.isPlaying,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isEnded = playbackState == Player.STATE_ENDED,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.onPlaybackProgress(
                    positionMs = exoPlayer.currentPosition,
                    bufferedMs = exoPlayer.bufferedPosition,
                    durationMs = if (exoPlayer.duration != C.TIME_UNSET) exoPlayer.duration else 0L,
                    isPlaying = isPlaying,
                    isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                    isEnded = exoPlayer.playbackState == Player.STATE_ENDED,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onPlayerError(error.message ?: error.errorCodeName)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            viewModel.reportStopSync(explicitPositionMs = pos.takeIf { it > 0L })
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pause when backgrounded unless in PiP
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !latestInPip) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.pause()
                viewModel.reportStopSync(explicitPositionMs = pos.takeIf { it > 0L })
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Immersive landscape mode + FLAG_KEEP_SCREEN_ON + clean restore on exit
    DisposableEffect(Unit) {
        val activity = activityProvider()
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            val act = activityProvider() ?: activity
            if (act != null) {
                (act as? MainActivity)?.updateAutoPip(enabled = false)
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = act.window.attributes
                if (lp.screenBrightness >= 0f) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    act.window.attributes = lp
                }
                val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = prevOrientation
            }
        }
    }

    // Keep MainActivity auto-PiP synced with playback state & aspect ratio
    LaunchedEffect(state.isPlaying, videoAspect) {
        val mainAct = activityProvider() as? MainActivity ?: return@LaunchedEffect
        mainAct.updateAutoPip(
            enabled = state.isPlaying,
            aspectWidth = videoAspect.first,
            aspectHeight = videoAspect.second,
        )
    }

    // Handle TV handoff exit event
    LaunchedEffect(viewModel) {
        viewModel.events.collect { ev ->
            if (ev is WatchPlayerEvent.HandedOffToTv) {
                exoPlayer.pause()
                latestOnHandedOff()
            }
        }
    }

    // Load MediaItem whenever streamUri or streamVersion changes
    LaunchedEffect(state.streamUri, state.streamVersion) {
        val uriStr = state.streamUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val attachedSubs = WatchPlayerHelpers.subtitlesToAttach(
            tracks = state.subtitleTracks,
            isHls = state.isHls,
            hlsAttachedSubtitleId = state.hlsAttachedSubtitleId,
        )
        val subConfigs = attachedSubs.map { sub ->
            val mime = if (sub.format.equals("srt", ignoreCase = true)) {
                MimeTypes.APPLICATION_SUBRIP
            } else {
                MimeTypes.TEXT_VTT
            }
            var flags = 0
            if (sub.isDefault) flags = flags or C.SELECTION_FLAG_DEFAULT
            if (sub.isForced) flags = flags or C.SELECTION_FLAG_FORCED
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.uri))
                .setId(sub.id)
                .setMimeType(mime)
                .setLanguage(sub.language.ifBlank { null })
                .setLabel(sub.label)
                .setSelectionFlags(flags)
                .build()
        }
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(uriStr)
            .setSubtitleConfigurations(subConfigs)
        if (state.isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        exoPlayer.setMediaItem(mediaItemBuilder.build(), state.startPositionMs.coerceAtLeast(0L))
        exoPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    // Sync playback speed
    LaunchedEffect(state.playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
    }

    // Apply subtitle track selection to ExoPlayer
    LaunchedEffect(state.selectedSubtitleId, exoTracksVersion) {
        applySubtitleSelection(exoPlayer, state.selectedSubtitleId, state.subtitleTracks)
    }

    // Apply in-place audio track selection for Direct/Offline streams
    LaunchedEffect(state.selectedAudioIndex, state.isHls, exoTracksVersion) {
        if (!state.isHls && state.selectedAudioIndex != null) {
            applyDirectAudioSelection(exoPlayer, state.selectedAudioIndex!!, state.audioTracks)
        }
    }

    // Poll ExoPlayer position every 500ms while active
    LaunchedEffect(exoPlayer, state.streamUri) {
        if (state.streamUri == null) return@LaunchedEffect
        while (true) {
            val dur = if (exoPlayer.duration != C.TIME_UNSET) exoPlayer.duration else 0L
            viewModel.onPlaybackProgress(
                positionMs = exoPlayer.currentPosition,
                bufferedMs = exoPlayer.bufferedPosition,
                durationMs = dur,
                isPlaying = exoPlayer.isPlaying,
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                isEnded = exoPlayer.playbackState == Player.STATE_ENDED,
            )
            delay(500L)
        }
    }

    // Auto-hide HUD 3.5s after last interaction while playing
    LaunchedEffect(state.controlsVisible, state.isPlaying, isScrubbing, state.activeSheet, interactionTick) {
        if (state.controlsVisible && state.isPlaying && !isScrubbing && state.activeSheet == WatchPlayerSheet.NONE) {
            delay(3_500L)
            viewModel.setControlsVisible(false)
        }
    }

    // Auto-clear gesture overlay pill
    LaunchedEffect(gestureOverlayTick) {
        if (gestureOverlay != null) {
            delay(900L)
            gestureOverlay = null
        }
    }

    BackHandler {
        when {
            state.activeSheet != WatchPlayerSheet.NONE -> viewModel.closeSheet()
            state.isLocked -> viewModel.toggleLock()
            else -> {
                exoPlayer.pause()
                viewModel.reportStopSync()
                latestOnBack()
            }
        }
    }

    val bumpHud: () -> Unit = {
        interactionTick++
    }

    val seekByMs: (Long) -> Unit = { deltaMs ->
        val dur = state.durationMs.takeIf { it > 0L } ?: state.runtimeMs
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(
            0L,
            if (dur > 0L) dur else Long.MAX_VALUE,
        )
        exoPlayer.seekTo(target)
        viewModel.onUserSeek(target)
        gestureOverlay = GestureOverlayState(
            kind = if (deltaMs < 0) GestureOverlayKind.SEEK_BACK else GestureOverlayKind.SEEK_FORWARD,
            label = if (deltaMs < 0) "-10s" else "+10s",
        )
        gestureOverlayTick++
        bumpHud()
    }

    val enterPip: () -> Unit = {
        val act = activityProvider()
        if (act != null && act.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val w = videoAspect.first.coerceAtLeast(1)
            val h = videoAspect.second.coerceAtLeast(1)
            val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.42f, 2.35f)
            val rational = Rational((ratio * 100f).roundToInt(), 100)
            runCatching {
                act.enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(rational)
                        .build(),
                )
            }
        }
    }

    // True black canvas (docs/DESIGN.md)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        // 1. Native Media3 Video Surface + Native WebVTT/SRT Subtitle Renderer
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                AndroidColor.WHITE,
                                AndroidColor.argb(170, 0, 0, 0),
                                AndroidColor.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                AndroidColor.BLACK,
                                null,
                            ),
                        )
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                        setFractionalTextSize(0.058f)
                    }
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            },
            onRelease = { view ->
                view.player = null
            },
        )

        // 2. Touch Gesture Surface (disabled in PiP)
        if (!isInPictureInPictureMode && state.error == null && !state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.isLocked, containerSize) {
                        detectTapGestures(
                            onTap = {
                                if (state.activeSheet != WatchPlayerSheet.NONE) {
                                    viewModel.closeSheet()
                                } else {
                                    viewModel.toggleControlsVisible()
                                    bumpHud()
                                }
                            },
                            onDoubleTap = { offset ->
                                if (state.isLocked) return@detectTapGestures
                                val w = containerSize.width.toFloat().coerceAtLeast(1f)
                                when {
                                    offset.x < w * 0.35f -> seekByMs(-10_000L)
                                    offset.x > w * 0.65f -> seekByMs(10_000L)
                                    else -> {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                        bumpHud()
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(state.isLocked, containerSize) {
                        var isLeftSide = true
                        var startBrightness = 0.5f
                        var startVolume = 0
                        var maxVolume = 15
                        var cumulativeDy = 0f

                        detectVerticalDragGestures(
                            onDragStart = { startOffset ->
                                if (state.isLocked) return@detectVerticalDragGestures
                                cumulativeDy = 0f
                                val halfW = containerSize.width / 2f
                                isLeftSide = startOffset.x < halfW
                                if (isLeftSide) {
                                    val lp = activityProvider()?.window?.attributes
                                    startBrightness = lp?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                } else {
                                    maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                if (state.isLocked) return@detectVerticalDragGestures
                                cumulativeDy += dragAmount
                                val heightPx = (containerSize.height * 0.65f).coerceAtLeast(200f)
                                val deltaFraction = -cumulativeDy / heightPx
                                if (isLeftSide) {
                                    val next = WatchPlayerHelpers.computeDragBrightness(startBrightness, deltaFraction)
                                    val act = activityProvider()
                                    if (act != null) {
                                        val lp = act.window.attributes
                                        lp.screenBrightness = next
                                        act.window.attributes = lp
                                    }
                                    gestureOverlay = GestureOverlayState(
                                        kind = GestureOverlayKind.BRIGHTNESS,
                                        label = "${(next * 100f).roundToInt()}%",
                                        fraction = next,
                                    )
                                    gestureOverlayTick++
                                } else {
                                    val nextVol = WatchPlayerHelpers.computeDragVolume(startVolume, maxVolume, deltaFraction)
                                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                    val frac = if (maxVolume > 0) nextVol.toFloat() / maxVolume.toFloat() else 0f
                                    gestureOverlay = GestureOverlayState(
                                        kind = GestureOverlayKind.VOLUME,
                                        label = "${(frac * 100f).roundToInt()}%",
                                        fraction = frac,
                                    )
                                    gestureOverlayTick++
                                }
                            },
                        )
                    },
            )
        }

        // 3. Loading / Error / Tailscale-Required states
        if (!isInPictureInPictureMode) {
            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = DarkPcColors.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp),
                    )
                }
            } else if (!state.error.isNullOrBlank()) {
                PlayerErrorOverlay(
                    tailscaleRequired = state.tailscaleRequired,
                    message = state.error!!,
                    onRetry = viewModel::retry,
                    onBack = {
                        viewModel.reportStopSync()
                        onBack()
                    },
                )
            }
        }

        // 4. Gesture HUD indicator (Brightness / Volume / ±10s seek)
        if (!isInPictureInPictureMode) {
            AnimatedVisibility(
                visible = gestureOverlay != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                gestureOverlay?.let { g ->
                    PlayerGesturePill(overlay = g)
                }
            }
        }

        // 5. Locked HUD (minimal Unlock button only)
        if (!isInPictureInPictureMode && state.isLocked && state.error == null) {
            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(DarkPcColors.surface.copy(alpha = 0.92f))
                        .border(0.5.dp, DarkPcColors.hairline, RoundedCornerShape(999.dp))
                        .clickable {
                            viewModel.toggleLock()
                            bumpHud()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LockOpen,
                        contentDescription = "Unlock screen",
                        tint = DarkPcColors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Unlock controls",
                        color = DarkPcColors.text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 6. Custom Netflix-style Compose HUD (hidden in PiP and when locked)
        if (!isInPictureInPictureMode && !state.isLocked && state.error == null && !state.loading) {
            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Subtle top scrim for HUD legibility
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(104.dp)
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Black.copy(alpha = 0.82f),
                                    1.0f to Color.Transparent,
                                ),
                            ),
                    )
                    // Subtle bottom scrim for HUD legibility
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(148.dp)
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    1.0f to Color.Black.copy(alpha = 0.88f),
                                ),
                            ),
                    )

                    // Top bar
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlayerIconCircle(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            onClick = {
                                exoPlayer.pause()
                                viewModel.reportStopSync()
                                onBack()
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = state.title.ifBlank { "Watch" },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (state.isOffline) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(DarkPcColors.accentSoft)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.OfflinePin,
                                            contentDescription = null,
                                            tint = DarkPcColors.accent,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = "Offline",
                                            color = DarkPcColors.accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                            if (!state.subtitle.isNullOrBlank()) {
                                Text(
                                    text = state.subtitle!!,
                                    color = DarkPcColors.onMediaDim,
                                    style = TabularNumStyle.copy(fontSize = 12.5.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (!state.isOffline && state.qualityOptions.isNotEmpty()) {
                            PlayerTopPill(
                                icon = Icons.Rounded.HighQuality,
                                label = state.selectedQualityId.uppercase(),
                                onClick = {
                                    viewModel.openSheet(WatchPlayerSheet.QUALITY)
                                    bumpHud()
                                },
                            )
                        }

                        if (!state.isOffline && state.jellyfinItemId.isNotBlank()) {
                            PlayerIconCircle(
                                icon = Icons.Rounded.Cast,
                                contentDescription = "Cast to TV",
                                busy = state.castingToTv,
                                onClick = {
                                    smartCast.cast(forcePicker = false) { picked ->
                                        viewModel.castToTv(
                                            sessionId = picked?.sessionId,
                                            deviceName = picked?.deviceName,
                                        )
                                    }
                                    bumpHud()
                                },
                            )
                        }

                        PlayerIconCircle(
                            icon = Icons.Rounded.PictureInPictureAlt,
                            contentDescription = "Picture-in-Picture",
                            onClick = enterPip,
                        )
                    }

                    // Center transport controls: -10s · Play/Pause · +10s
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(38.dp),
                    ) {
                        PlayerIconCircle(
                            icon = Icons.Rounded.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            sizeDp = 50,
                            iconSizeDp = 28,
                            onClick = { seekByMs(-10_000L) },
                        )
                        Box(
                            modifier = Modifier
                                .size(66.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.56f))
                                .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                                .clickable {
                                    if (state.isEnded) {
                                        exoPlayer.seekTo(0L)
                                        exoPlayer.play()
                                    } else if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                    }
                                    bumpHud()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.isBuffering) {
                                CircularProgressIndicator(
                                    color = DarkPcColors.accent,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                        PlayerIconCircle(
                            icon = Icons.Rounded.Forward10,
                            contentDescription = "Forward 10 seconds",
                            sizeDp = 50,
                            iconSizeDp = 28,
                            onClick = { seekByMs(10_000L) },
                        )
                    }

                    // Bottom bar: floating scrub preview + scrubber + timestamps + utility actions
                    val effectiveDuration = state.durationMs.takeIf { it > 0L } ?: state.runtimeMs
                    val displayPosition = if (isScrubbing) scrubPositionMs else state.currentPositionMs
                    val remainingMs = (effectiveDuration - displayPosition).coerceAtLeast(0L)
                    var safeBottomOriginX by remember { mutableFloatStateOf(0f) }
                    var safeBottomWidthPx by remember { mutableFloatStateOf(0f) }
                    var scrubberLeftInSafePx by remember { mutableFloatStateOf(0f) }
                    var scrubberWidthPx by remember { mutableFloatStateOf(1f) }
                    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .onGloballyPositioned { coords ->
                                safeBottomOriginX = coords.positionInRoot().x
                                safeBottomWidthPx = coords.size.width.toFloat()
                            },
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { bottomBarHeightPx = it.height }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = WatchPlayerHelpers.formatTimeMs(displayPosition),
                                    color = Color.White,
                                    style = TabularNumStyle.copy(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                                WatchScrubberBar(
                                    positionMs = displayPosition,
                                    bufferedMs = state.bufferedPositionMs,
                                    durationMs = effectiveDuration,
                                    chapters = state.chapters,
                                    onScrubStart = { posMs ->
                                        isScrubbing = true
                                        scrubPositionMs = posMs
                                        bumpHud()
                                    },
                                    onScrubMove = { posMs ->
                                        scrubPositionMs = posMs
                                    },
                                    onScrubEnd = { posMs ->
                                        isScrubbing = false
                                        val safe = posMs.coerceAtLeast(0L)
                                        exoPlayer.seekTo(safe)
                                        viewModel.onUserSeek(safe)
                                        bumpHud()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coords ->
                                            scrubberLeftInSafePx = coords.positionInRoot().x - safeBottomOriginX
                                            scrubberWidthPx = coords.size.width.toFloat().coerceAtLeast(1f)
                                        },
                                )
                                Text(
                                    text = "-${WatchPlayerHelpers.formatTimeMs(remainingMs)}",
                                    color = DarkPcColors.onMediaDim,
                                    style = TabularNumStyle.copy(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                PlayerBottomAction(
                                    icon = Icons.Rounded.Subtitles,
                                    label = "Audio & Subtitles",
                                    highlighted = state.selectedSubtitleId != null,
                                    onClick = {
                                        viewModel.openSheet(WatchPlayerSheet.TRACKS)
                                        bumpHud()
                                    },
                                )
                                if (state.chapters.isNotEmpty()) {
                                    PlayerBottomAction(
                                        icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                                        label = "Chapters",
                                        onClick = {
                                            viewModel.openSheet(WatchPlayerSheet.CHAPTERS)
                                            bumpHud()
                                        },
                                    )
                                }
                                PlayerBottomAction(
                                    icon = Icons.Rounded.Speed,
                                    label = "Speed (${WatchPlayerHelpers.formatSpeed(state.playbackSpeed)})",
                                    highlighted = state.playbackSpeed != 1.0f,
                                    onClick = {
                                        viewModel.openSheet(WatchPlayerSheet.SPEED)
                                        bumpHud()
                                    },
                                )
                                PlayerBottomAction(
                                    icon = Icons.Rounded.Lock,
                                    label = "Lock",
                                    onClick = {
                                        viewModel.toggleLock()
                                        bumpHud()
                                    },
                                )
                                if (state.nextEpisode != null) {
                                    PlayerBottomAction(
                                        icon = Icons.Rounded.SkipNext,
                                        label = "Next Episode",
                                        onClick = {
                                            viewModel.playNextEpisodeNow()
                                            bumpHud()
                                        },
                                    )
                                }
                            }
                        }

                        if (isScrubbing && effectiveDuration > 0L) {
                            WatchScrubPreviewPopup(
                                positionMs = scrubPositionMs,
                                durationMs = effectiveDuration,
                                trickplay = state.trickplay,
                                chapters = state.chapters,
                                tileCache = trickplayCache,
                                scrubberLeftPx = scrubberLeftInSafePx,
                                scrubberWidthPx = scrubberWidthPx,
                                containerWidthPx = safeBottomWidthPx,
                                bottomClearancePx = bottomBarHeightPx,
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                        }
                    }
                }
            }
        }

        // 7. Buffering spinner when HUD is hidden
        if (!isInPictureInPictureMode && state.isBuffering && !state.controlsVisible && !state.loading && state.error == null) {
            CircularProgressIndicator(
                color = DarkPcColors.accent,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
            )
        }

        // 7b. Netflix-style Skip Intro / Skip Credits / Skip Recap floating pill (visible even when HUD is hidden)
        val activeSeg = state.activeSegment
        var lastRenderedSegment by remember(state.jellyfinItemId) { mutableStateOf<WatchMediaSegment?>(null) }
        if (activeSeg != null) {
            lastRenderedSegment = activeSeg
        }
        val showSkipPill = !isInPictureInPictureMode &&
            !state.isLocked &&
            !state.loading &&
            state.error == null &&
            state.activeSheet == WatchPlayerSheet.NONE &&
            !state.showNextEpisodeCard &&
            !isScrubbing &&
            activeSeg != null &&
            activeSeg.key !in state.dismissedSegmentKeys
        val skipBottomPad by animateDpAsState(
            targetValue = if (state.controlsVisible) 104.dp else 28.dp,
            animationSpec = tween(durationMillis = 220),
            label = "skip_pill_bottom_pad",
        )
        AnimatedVisibility(
            visible = showSkipPill,
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 20.dp, bottom = skipBottomPad),
        ) {
            val seg = activeSeg ?: lastRenderedSegment
            if (seg != null) {
                SkipSegmentPill(
                    label = WatchPlayerHelpers.segmentButtonLabel(seg.type),
                    onSkip = {
                        val dur = state.durationMs.takeIf { it > 0L } ?: state.runtimeMs
                        val target = if (dur > 0L) seg.endMs.coerceIn(0L, dur) else seg.endMs.coerceAtLeast(0L)
                        exoPlayer.seekTo(target)
                        viewModel.onUserSeek(target)
                    },
                    onDismiss = {
                        viewModel.dismissSegment(seg.key)
                    },
                )
            }
        }

        // 8. Next Episode auto-play overlay (final 15s, Outro segment, or ended)
        if (!isInPictureInPictureMode && state.showNextEpisodeCard && state.activeSheet == WatchPlayerSheet.NONE) {
            state.nextEpisode?.let { nextEp ->
                NextEpisodeOverlayCard(
                    nextEpisode = nextEp,
                    countdownSec = state.nextEpisodeCountdownSec,
                    onPlayNow = viewModel::playNextEpisodeNow,
                    onCancel = viewModel::dismissNextEpisode,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(end = 20.dp, bottom = if (state.controlsVisible) 100.dp else 24.dp),
                )
            }
        }

        // 9. Transient Toast message (e.g. HLS fallback or Cast error)
        if (!isInPictureInPictureMode && !state.toastMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DarkPcColors.surface.copy(alpha = 0.94f))
                    .border(0.5.dp, DarkPcColors.hairline, RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = state.toastMessage!!,
                    color = DarkPcColors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 10. Landscape-friendly Bottom/Overlay Sheets (Quality / Audio & Subtitles / Chapters / Speed)
        if (!isInPictureInPictureMode && state.activeSheet != WatchPlayerSheet.NONE) {
            PlayerSelectionOverlay(
                sheet = state.activeSheet,
                state = state,
                onSelectQuality = viewModel::selectQuality,
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
                onSelectChapter = { startMs ->
                    val dur = state.durationMs.takeIf { it > 0L } ?: state.runtimeMs
                    val target = if (dur > 0L) startMs.coerceIn(0L, dur) else startMs.coerceAtLeast(0L)
                    exoPlayer.seekTo(target)
                    viewModel.onUserSeek(target)
                    viewModel.closeSheet()
                    bumpHud()
                },
                onOpenSubtitleSearch = viewModel::openSubtitleSearch,
                onCloseSubtitleSearch = viewModel::closeSubtitleSearch,
                onDownloadRemoteSubtitle = viewModel::downloadRemoteSubtitle,
                onSelectSpeed = viewModel::selectPlaybackSpeed,
                onDismiss = viewModel::closeSheet,
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun applySubtitleSelection(
    player: ExoPlayer,
    selectedSubtitleId: String?,
    subtitleTracks: List<PlayerSubtitleEntry>,
) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (selectedSubtitleId == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        player.trackSelectionParameters = builder.build()
        return
    }
    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)

    val selectedEntry = subtitleTracks.firstOrNull { it.id == selectedSubtitleId }
    val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    var matched = false

    fun matchesAttachedId(fmtId: String?, targetId: String): Boolean {
        if (fmtId.isNullOrBlank()) return false
        return fmtId == targetId || fmtId.endsWith(":$targetId") || fmtId.endsWith(targetId)
    }

    // 1. Direct match against attached external / on-demand HTTP sidecar tracks (by id or exact label)
    for (group in textGroups) {
        for (i in 0 until group.length) {
            val fmt = group.getTrackFormat(i)
            val idMatch = matchesAttachedId(fmt.id, selectedSubtitleId)
            val labelMatch = selectedEntry != null &&
                !fmt.label.isNullOrBlank() &&
                fmt.label.equals(selectedEntry.label, ignoreCase = true)
            if (idMatch || labelMatch) {
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                matched = true
                break
            }
        }
        if (matched) break
    }

    // 2. Native embedded MKV/MP4 text tracks demuxed by ExoPlayer (Direct Play)
    if (!matched && selectedEntry != null && !selectedEntry.isExternal) {
        val externalIds = subtitleTracks
            .filter { it.isExternal || it.uri.startsWith("file://", ignoreCase = true) }
            .map { it.id }
            .toSet()
        val embeddedGroups = textGroups.filter { group ->
            (0 until group.length).none { idx ->
                val fid = group.getTrackFormat(idx).id
                externalIds.any { extId -> matchesAttachedId(fid, extId) }
            }
        }
        val embeddedEntries = subtitleTracks.filter {
            !it.isExternal && !it.uri.startsWith("file://", ignoreCase = true)
        }
        val embeddedPos = embeddedEntries.indexOfFirst { it.id == selectedSubtitleId }

        // 2a. Container index match when track count aligns or language is compatible
        if (embeddedPos in embeddedGroups.indices) {
            val grp = embeddedGroups[embeddedPos]
            val fmt = if (grp.length > 0) grp.getTrackFormat(0) else null
            val langCompatible = fmt == null ||
                fmt.language.isNullOrBlank() ||
                fmt.language.equals("und", ignoreCase = true) ||
                selectedEntry.language.isBlank() ||
                WatchPlayerHelpers.languagesMatch(fmt.language, selectedEntry.language)
            if (langCompatible || embeddedGroups.size == embeddedEntries.size) {
                builder.setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, 0))
                matched = true
            }
        }

        // 2b. Language + forced flag match across native embedded groups
        if (!matched && selectedEntry.language.isNotBlank()) {
            var fallbackLangCandidate: Pair<Tracks.Group, Int>? = null
            for (group in embeddedGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (WatchPlayerHelpers.languagesMatch(fmt.language, selectedEntry.language)) {
                        val isForced = (fmt.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                        if (isForced == selectedEntry.isForced) {
                            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            matched = true
                            break
                        } else if (fallbackLangCandidate == null) {
                            fallbackLangCandidate = group to i
                        }
                    }
                }
                if (matched) break
            }
            if (!matched && fallbackLangCandidate != null) {
                builder.setOverrideForType(
                    TrackSelectionOverride(fallbackLangCandidate.first.mediaTrackGroup, fallbackLangCandidate.second),
                )
                matched = true
            }
        }

        // 2c. Final fallback to container order if available
        if (!matched && embeddedPos in embeddedGroups.indices) {
            builder.setOverrideForType(TrackSelectionOverride(embeddedGroups[embeddedPos].mediaTrackGroup, 0))
            matched = true
        }
    }

    if (!matched && selectedEntry != null && selectedEntry.language.isNotBlank()) {
        builder.setPreferredTextLanguage(selectedEntry.language)
    }
    player.trackSelectionParameters = builder.build()
}

@OptIn(UnstableApi::class)
private fun applyDirectAudioSelection(
    player: ExoPlayer,
    selectedAudioIndex: Int,
    audioTracks: List<PlayerAudioEntry>,
) {
    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isEmpty()) return
    val targetEntry = audioTracks.firstOrNull { it.index == selectedAudioIndex }
    val posInList = audioTracks.indexOfFirst { it.index == selectedAudioIndex }
    val builder = player.trackSelectionParameters.buildUpon()
    var matched = false

    // Match by order when track count aligns — ONLY if the track is actually supported by MediaCodec
    // (never force TrackSelectionOverride onto an unsupported TrueHD/DTS track).
    if (posInList in audioGroups.indices && audioGroups[posInList].isTrackSupported(0)) {
        builder.setOverrideForType(TrackSelectionOverride(audioGroups[posInList].mediaTrackGroup, 0))
        matched = true
    } else if (targetEntry != null && targetEntry.language.isNotBlank()) {
        for (group in audioGroups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                if (WatchPlayerHelpers.languagesMatch(fmt.language, targetEntry.language)) {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    matched = true
                    break
                }
            }
            if (matched) break
        }
    }
    if (matched) {
        player.trackSelectionParameters = builder.build()
    }
}

@Composable
private fun WatchScrubberBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    chapters: List<WatchChapter>,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var barWidthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableLongStateOf(positionMs) }

    val playedFrac = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedFrac = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs <= 0L) return@detectTapGestures
                    val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                    onScrubEnd((frac * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (durationMs <= 0L) return@detectHorizontalDragGestures
                        dragging = true
                        val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                        dragPosMs = (frac * durationMs).toLong()
                        onScrubStart(dragPosMs)
                    },
                    onHorizontalDrag = { change, _ ->
                        if (durationMs <= 0L) return@detectHorizontalDragGestures
                        change.consume()
                        val frac = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                        dragPosMs = (frac * durationMs).toLong()
                        onScrubMove(dragPosMs)
                    },
                    onDragEnd = {
                        if (dragging) {
                            dragging = false
                            onScrubEnd(dragPosMs)
                        }
                    },
                    onDragCancel = {
                        if (dragging) {
                            dragging = false
                            onScrubEnd(dragPosMs)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            val trackH = if (dragging) 5.dp.toPx() else 3.5.dp.toPx()
            val cy = size.height / 2f
            val top = cy - trackH / 2f
            val radius = CornerRadius(trackH / 2f, trackH / 2f)

            // Base track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.24f),
                topLeft = Offset(0f, top),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // Buffered track
            if (bufferedFrac > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width * bufferedFrac, trackH),
                    cornerRadius = radius,
                )
            }
            // Mint played track
            if (playedFrac > 0f) {
                drawRoundRect(
                    color = DarkPcColors.accent,
                    topLeft = Offset(0f, top),
                    size = Size(size.width * playedFrac, trackH),
                    cornerRadius = radius,
                )
            }
            // Subtle crisp vertical chapter tick markers (when 2+ chapters)
            if (durationMs > 0L && chapters.size >= 2) {
                val tickW = 1.5.dp.toPx()
                val tickH = trackH + 4.dp.toPx()
                val tickTop = cy - tickH / 2f
                chapters.forEach { ch ->
                    val chMs = WatchPlayerHelpers.ticksToMs(ch.startPositionTicks)
                    val cf = (chMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    if (cf > 0.01f && cf < 0.99f) {
                        val x = size.width * cf
                        drawRoundRect(
                            color = if (cf <= playedFrac) Color.Black.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f),
                            topLeft = Offset(x - tickW / 2f, tickTop),
                            size = Size(tickW, tickH),
                            cornerRadius = CornerRadius(tickW / 2f, tickW / 2f),
                        )
                    }
                }
            }
            // Thumb
            val thumbRadius = if (dragging) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(
                color = DarkPcColors.accent,
                radius = thumbRadius,
                center = Offset(size.width * playedFrac, cy),
            )
        }
    }
}

@Composable
private fun SkipSegmentPill(
    label: String,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(DarkPcColors.surface.copy(alpha = 0.92f))
            .border(1.dp, DarkPcColors.accent, RoundedCornerShape(999.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onSkip)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = null,
                tint = DarkPcColors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(DarkPcColors.hairline),
        )
        Box(
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(start = 9.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss",
                tint = DarkPcColors.textDim,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun WatchScrubPreviewPopup(
    positionMs: Long,
    durationMs: Long,
    trickplay: WatchTrickplayInfo?,
    chapters: List<WatchChapter>,
    tileCache: TrickplayTileCache,
    scrubberLeftPx: Float,
    scrubberWidthPx: Float,
    containerWidthPx: Float,
    bottomClearancePx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frame = remember(trickplay, positionMs) {
        trickplay?.let { WatchPlayerHelpers.trickplayFrameForPosition(it, positionMs) }
    }
    val chapterTitle = remember(chapters, positionMs) {
        WatchPlayerHelpers.activeChapterTitle(chapters, positionMs)
    }

    var tileBitmap by remember(tileCache, frame?.tileIndex) {
        mutableStateOf<Bitmap?>(frame?.let { tileCache.getCached(it.tileIndex) })
    }
    LaunchedEffect(tileCache, frame?.tileIndex, frame?.tileUrl) {
        val f = frame ?: run {
            tileBitmap = null
            return@LaunchedEffect
        }
        val cached = tileCache.getCached(f.tileIndex)
        if (cached != null) {
            tileBitmap = cached
        } else {
            tileBitmap = tileCache.loadTile(f.tileIndex, f.tileUrl)
        }
    }

    val hasThumb = trickplay != null && frame != null
    val thumbWidthDp = 160.dp
    val thumbHeightDp = if (trickplay != null && trickplay.width > 0 && trickplay.height > 0) {
        (160f * (trickplay.height.toFloat() / trickplay.width.toFloat())).coerceIn(72f, 112f).dp
    } else {
        90.dp
    }

    var popupWidthPx by remember { mutableIntStateOf(0) }
    val fallbackCardWidthPx = with(density) { (if (hasThumb) 168.dp else 88.dp).toPx() }
    val cardWidthPx = if (popupWidthPx > 0) popupWidthPx.toFloat() else fallbackCardWidthPx
    val scrubFrac = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val thumbCenterX = if (scrubberWidthPx > 1f) {
        scrubberLeftPx + scrubberWidthPx * scrubFrac
    } else {
        containerWidthPx * scrubFrac
    }
    val edgeMarginPx = with(density) { 16.dp.toPx() }
    val maxLeftPx = (containerWidthPx - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val clampedLeftPx = (thumbCenterX - cardWidthPx / 2f).coerceIn(edgeMarginPx, maxLeftPx)
    val bottomPaddingDp = with(density) {
        (bottomClearancePx.takeIf { it > 0 } ?: 92.dp.roundToPx()).toDp() + 6.dp
    }

    Column(
        modifier = modifier
            .padding(bottom = bottomPaddingDp)
            .offset { IntOffset(clampedLeftPx.roundToInt(), 0) }
            .onSizeChanged { popupWidthPx = it.width }
            .widthIn(max = if (hasThumb) 172.dp else 180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkPcColors.surface.copy(alpha = 0.95f))
            .border(1.dp, DarkPcColors.hairline, RoundedCornerShape(10.dp))
            .padding(if (hasThumb) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hasThumb && trickplay != null && frame != null) {
            Box(
                modifier = Modifier
                    .width(thumbWidthDp)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = tileBitmap
                if (bmp != null && !bmp.isRecycled) {
                    val imgBitmap = remember(bmp) { bmp.asImageBitmap() }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val srcX = (frame.col * trickplay.width).coerceIn(0, (imgBitmap.width - 1).coerceAtLeast(0))
                        val srcY = (frame.row * trickplay.height).coerceIn(0, (imgBitmap.height - 1).coerceAtLeast(0))
                        val srcW = trickplay.width.coerceAtMost((imgBitmap.width - srcX).coerceAtLeast(1))
                        val srcH = trickplay.height.coerceAtMost((imgBitmap.height - srcY).coerceAtLeast(1))
                        drawImage(
                            image = imgBitmap,
                            srcOffset = IntOffset(srcX, srcY),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        color = DarkPcColors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Text(
            text = WatchPlayerHelpers.formatTimeMs(positionMs),
            color = Color.White,
            style = TabularNumStyle.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (!chapterTitle.isNullOrBlank()) {
            Text(
                text = chapterTitle,
                color = DarkPcColors.textDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = thumbWidthDp)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PlayerIconCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    sizeDp: Int = 40,
    iconSizeDp: Int = 22,
    busy: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.46f))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = DarkPcColors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSizeDp.dp),
            )
        }
    }
}

@Composable
private fun PlayerTopPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.56f))
            .border(0.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DarkPcColors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = Color.White,
            style = TabularNumStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun PlayerBottomAction(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (highlighted) DarkPcColors.accent else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = if (highlighted) DarkPcColors.accent else Color.White,
            style = TabularNumStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerGesturePill(
    overlay: GestureOverlayState,
) {
    val icon = when (overlay.kind) {
        GestureOverlayKind.BRIGHTNESS -> Icons.Rounded.WbSunny
        GestureOverlayKind.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
        GestureOverlayKind.SEEK_BACK -> Icons.Rounded.Replay10
        GestureOverlayKind.SEEK_FORWARD -> Icons.Rounded.Forward10
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkPcColors.surface.copy(alpha = 0.90f))
            .border(0.5.dp, DarkPcColors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DarkPcColors.accent,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = overlay.label,
            color = DarkPcColors.text,
            style = TabularNumStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        overlay.fraction?.let { frac ->
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DarkPcColors.meterTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(DarkPcColors.accent),
                )
            }
        }
    }
}

@Composable
private fun NextEpisodeOverlayCard(
    nextEpisode: WatchNextEpisode,
    countdownSec: Int?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 250.dp, max = 330.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkPcColors.surface.copy(alpha = 0.95f))
            .border(0.5.dp, DarkPcColors.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (countdownSec != null) "Next episode in ${countdownSec}s" else "Up next",
                color = DarkPcColors.accent,
                style = TabularNumStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss",
                tint = DarkPcColors.textDim,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onCancel),
            )
        }
        Text(
            text = nextEpisode.label.ifBlank { "Next episode" },
            color = DarkPcColors.text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PcButton(
                label = "Play now",
                onClick = onPlayNow,
                variant = PcButtonVariant.Primary,
                icon = Icons.Rounded.PlayArrow,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            PcButton(
                label = "Cancel",
                onClick = onCancel,
                variant = PcButtonVariant.Subtle,
                compact = true,
            )
        }
    }
}

@Composable
private fun PlayerErrorOverlay(
    tailscaleRequired: Boolean,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkPcColors.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (tailscaleRequired) Icons.Rounded.WifiOff else Icons.Rounded.Refresh,
                contentDescription = null,
                tint = if (tailscaleRequired) DarkPcColors.warn else DarkPcColors.bad,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = if (tailscaleRequired) "Tailscale required" else "Playback unavailable",
                color = DarkPcColors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = DarkPcColors.textDim,
                fontSize = 13.5.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PcButton(
                    label = "Back",
                    onClick = onBack,
                    variant = PcButtonVariant.Subtle,
                    modifier = Modifier.weight(1f),
                )
                PcButton(
                    label = "Retry",
                    onClick = onRetry,
                    variant = PcButtonVariant.Primary,
                    icon = Icons.Rounded.Refresh,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlayerSelectionOverlay(
    sheet: WatchPlayerSheet,
    state: WatchPlayerUiState,
    onSelectQuality: (String) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onSelectChapter: (Long) -> Unit,
    onOpenSubtitleSearch: (String) -> Unit,
    onCloseSubtitleSearch: () -> Unit,
    onDownloadRemoteSubtitle: (WatchRemoteSubtitle) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 580.dp)
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkPcColors.surface)
                .clickable(enabled = false) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sheet == WatchPlayerSheet.TRACKS && state.subtitleSearchOpen) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(DarkPcColors.surfaceRaised)
                                .clickable(onClick = onCloseSubtitleSearch),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to tracks",
                                tint = DarkPcColors.text,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    val sheetTitle = when (sheet) {
                        WatchPlayerSheet.QUALITY -> "Video quality"
                        WatchPlayerSheet.TRACKS -> if (state.subtitleSearchOpen) "Online subtitles" else "Audio & Subtitles"
                        WatchPlayerSheet.CHAPTERS -> "Chapters"
                        WatchPlayerSheet.SPEED -> "Playback speed"
                        WatchPlayerSheet.NONE -> ""
                    }
                    Text(
                        text = sheetTitle,
                        color = DarkPcColors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DarkPcColors.surfaceRaised)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = DarkPcColors.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when (sheet) {
                WatchPlayerSheet.QUALITY -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.qualityOptions, key = { it.id }) { opt ->
                            PlayerSheetOptionRow(
                                label = opt.label,
                                selected = opt.id == state.selectedQualityId,
                                onClick = { onSelectQuality(opt.id) },
                            )
                        }
                    }
                }

                WatchPlayerSheet.SPEED -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(WatchPlayerHelpers.SPEED_OPTIONS, key = { it.toString() }) { spd ->
                            val lbl = if (spd == 1.0f) "1x (Normal)" else WatchPlayerHelpers.formatSpeed(spd)
                            PlayerSheetOptionRow(
                                label = lbl,
                                selected = state.playbackSpeed == spd,
                                onClick = { onSelectSpeed(spd) },
                            )
                        }
                    }
                }

                WatchPlayerSheet.CHAPTERS -> {
                    val activeIdx = WatchPlayerHelpers.activeChapterIndex(
                        state.chapters,
                        state.currentPositionMs,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = state.chapters,
                            key = { idx, ch -> "ch_${idx}_${ch.startPositionTicks}" },
                        ) { idx, chapter ->
                            val isActive = idx == activeIdx
                            val startMs = WatchPlayerHelpers.ticksToMs(chapter.startPositionTicks)
                            val timeStr = WatchPlayerHelpers.formatTimeMs(startMs)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isActive) DarkPcColors.accentSoft else DarkPcColors.surfaceRaised)
                                    .clickable { onSelectChapter(startMs) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!chapter.imageUrl.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .width(92.dp)
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.Black),
                                    ) {
                                        AsyncImage(
                                            model = chapter.imageUrl,
                                            contentDescription = chapter.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 34.dp, height = 28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isActive) DarkPcColors.accent.copy(alpha = 0.22f)
                                                else DarkPcColors.surface,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = String.format(java.util.Locale.US, "%02d", idx + 1),
                                            color = if (isActive) DarkPcColors.accent else DarkPcColors.textFaint,
                                            style = TabularNumStyle.copy(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                            ),
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = chapter.name.ifBlank { "Chapter ${idx + 1}" },
                                        color = if (isActive) DarkPcColors.accent else DarkPcColors.text,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = timeStr,
                                        color = if (isActive) DarkPcColors.accent.copy(alpha = 0.85f) else DarkPcColors.textDim,
                                        style = TabularNumStyle.copy(fontSize = 12.sp),
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Current chapter",
                                        tint = DarkPcColors.accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                WatchPlayerSheet.TRACKS -> {
                    if (state.subtitleSearchOpen) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(WatchPlayerHelpers.SUBTITLE_LANGUAGES, key = { it.first }) { (code, label) ->
                                    val selected = state.subtitleSearchLanguage.equals(code, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (selected) DarkPcColors.accentSoft else DarkPcColors.surfaceRaised)
                                            .clickable(enabled = !state.subtitleSearchLoading) {
                                                onOpenSubtitleSearch(code)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (selected) DarkPcColors.accent else DarkPcColors.textDim,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        )
                                    }
                                }
                            }

                            if (!state.subtitleSearchError.isNullOrBlank()) {
                                Text(
                                    text = state.subtitleSearchError,
                                    color = DarkPcColors.bad,
                                    fontSize = 12.5.sp,
                                )
                            }

                            when {
                                state.subtitleSearchLoading -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = DarkPcColors.accent,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                }
                                state.subtitleSearchResults.isEmpty() -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "No online subtitles found for this language",
                                            color = DarkPcColors.textDim,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                                else -> {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(state.subtitleSearchResults, key = { it.id }) { sub ->
                                            val downloading = state.downloadingSubtitleId == sub.id
                                            val meta = WatchPlayerHelpers.formatSubtitleMeta(sub)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(DarkPcColors.surfaceRaised)
                                                    .clickable(enabled = state.downloadingSubtitleId == null) {
                                                        onDownloadRemoteSubtitle(sub)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        if (sub.isHashMatch) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(999.dp))
                                                                    .background(DarkPcColors.accentSoft)
                                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                                            ) {
                                                                Text(
                                                                    text = "Exact match",
                                                                    color = DarkPcColors.accent,
                                                                    fontSize = 10.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                )
                                                            }
                                                        }
                                                        Text(
                                                            text = sub.name.ifBlank { "Subtitle" },
                                                            color = DarkPcColors.text,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                    if (meta.isNotEmpty()) {
                                                        Text(
                                                            text = meta,
                                                            color = DarkPcColors.textDim,
                                                            style = TabularNumStyle.copy(fontSize = 11.5.sp),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (downloading) {
                                                    CircularProgressIndicator(
                                                        color = DarkPcColors.accent,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Download,
                                                        contentDescription = "Download subtitle",
                                                        tint = if (sub.isHashMatch) DarkPcColors.accent else DarkPcColors.textDim,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // Left column: Audio
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Audiotrack,
                                        contentDescription = null,
                                        tint = DarkPcColors.textDim,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Audio",
                                        color = DarkPcColors.textDim,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (state.audioTracks.isEmpty()) {
                                    Text(
                                        text = "Default audio",
                                        color = DarkPcColors.textFaint,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(state.audioTracks, key = { it.id }) { track ->
                                            PlayerSheetOptionRow(
                                                label = track.label,
                                                selected = state.selectedAudioIndex == track.index,
                                                onClick = { onSelectAudio(track.index) },
                                            )
                                        }
                                    }
                                }
                            }

                            // Right column: Subtitles + Search online subtitles
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Subtitles,
                                        contentDescription = null,
                                        tint = DarkPcColors.textDim,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Subtitles",
                                        color = DarkPcColors.textDim,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item(key = "sub_off") {
                                        PlayerSheetOptionRow(
                                            label = "Off",
                                            selected = state.selectedSubtitleId == null,
                                            onClick = { onSelectSubtitle(null) },
                                        )
                                    }
                                    items(state.subtitleTracks, key = { it.id }) { sub ->
                                        PlayerSheetOptionRow(
                                            label = sub.label,
                                            selected = state.selectedSubtitleId == sub.id,
                                            onClick = { onSelectSubtitle(sub.id) },
                                        )
                                    }
                                    if (!state.isOffline && state.jellyfinItemId.isNotBlank()) {
                                        item(key = "sub_search_online") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(DarkPcColors.surfaceRaised)
                                                    .border(0.5.dp, DarkPcColors.hairline, RoundedCornerShape(10.dp))
                                                    .clickable { onOpenSubtitleSearch(state.subtitleSearchLanguage) }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = null,
                                                    tint = DarkPcColors.accent,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Text(
                                                    text = "Search online subtitles",
                                                    color = DarkPcColors.text,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                WatchPlayerSheet.NONE -> {}
            }
        }
    }
}

@Composable
private fun PlayerSheetOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DarkPcColors.accentSoft else DarkPcColors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) DarkPcColors.accent else DarkPcColors.text,
            style = TabularNumStyle.copy(
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = DarkPcColors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
