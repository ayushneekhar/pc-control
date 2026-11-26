package com.neekhar.pccontrol.feature.cams

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ReplayPlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

data class ReplayProgress(
    val position: Double,
    val duration: Double,
    val state: ReplayPlayerState,
    val playing: Boolean,
    val seeking: Boolean,
    val decoder: String,
    val width: Int,
    val height: Int,
)

interface ReplayPlayerHandle {
    fun seek(seconds: Double, exact: Boolean)
    suspend fun snapshot(label: String): String
}

data class ReplaySource(
    val url: String,
    val startSeconds: Double,
    val version: Int = 0,
)

/**
 * Native Media3 `ExoPlayer` + `HlsMediaSource` wrapped in Compose `AndroidView` (`PlayerView`).
 * Ported from `modules/pc-native/.../RecPlayerView.kt`:
 * - Plays one continuous VOD HLS playlist per camera (muted, hardware H.264/H.265 decode).
 * - Supports exact seek and fast closest-sync keyframe scrubbing.
 * - Reports playback progress every 250 ms and on state transitions.
 * - Captures current frame as JPEG into `Pictures/PC Control` via `PixelCopy` or `TextureView`.
 */
@OptIn(UnstableApi::class)
@Composable
fun HlsReplayPlayer(
    source: ReplaySource?,
    playing: Boolean,
    rate: Float,
    modifier: Modifier = Modifier,
    onHandle: (ReplayPlayerHandle?) -> Unit,
    onProgress: (ReplayProgress) -> Unit,
    onPlayerError: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnError by rememberUpdatedState(onPlayerError)
    val controller = remember(context) {
        HlsPlayerController(
            context = context,
            onProgress = { latestOnProgress(it) },
            onPlayerError = { latestOnError(it) },
        )
    }

    LaunchedEffect(controller, source?.url, source?.startSeconds, source?.version) {
        controller.setSource(source)
    }

    LaunchedEffect(controller, playing) {
        controller.setPlaying(playing)
    }

    LaunchedEffect(controller, rate) {
        controller.setRate(rate)
    }

    LaunchedEffect(controller, source) {
        if (source != null) {
            while (true) {
                controller.emitProgress(seeking = false)
                delay(250L)
            }
        }
    }

    DisposableEffect(controller) {
        onHandle(controller)
        onDispose {
            onHandle(null)
            controller.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                controller.attachView(this)
            }
        },
        update = { view ->
            controller.attachView(view)
        },
        onRelease = { view ->
            controller.detachView(view)
        },
    )
}

@OptIn(UnstableApi::class)
private class HlsPlayerController(
    private val context: Context,
    private val onProgress: (ReplayProgress) -> Unit,
    private val onPlayerError: (String) -> Unit,
) : ReplayPlayerHandle {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentSource: ReplaySource? = null
    private var wantPlay: Boolean = false
    private var playbackRate: Float = 1f
    private var decoderName: String = ""
    private var videoSize: VideoSize = VideoSize.UNKNOWN

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    private val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)

    fun attachView(view: PlayerView) {
        playerView = view
        if (view.player !== player) {
            view.player = player
        }
    }

    fun detachView(view: PlayerView) {
        if (playerView === view) {
            view.player = null
            playerView = null
        }
    }

    fun setSource(source: ReplaySource?) {
        if (source == null) {
            release()
            return
        }
        val prev = currentSource
        if (prev != null &&
            prev.url == source.url &&
            prev.startSeconds == source.startSeconds &&
            prev.version == source.version &&
            player != null &&
            player?.playerError == null
        ) {
            return
        }
        currentSource = source

        val exo = player ?: ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).setEnableDecoderFallback(true),
        ).build().also { np ->
            player = np
            np.volume = 0f // recordings play muted
            np.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    videoSize = size
                }

                override fun onPlayerError(error: PlaybackException) {
                    onPlayerError(error.message ?: error.errorCodeName)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    emitProgress(seeking = false)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    emitProgress(seeking = false)
                }
            })
            np.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    this@HlsPlayerController.decoderName = decoderName
                }
            })
            playerView?.player = np
        }

        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val mediaSource = hlsFactory.createMediaSource(mediaItem)
        exo.setMediaSource(mediaSource, (source.startSeconds * 1000.0).toLong().coerceAtLeast(0L))
        exo.playbackParameters = PlaybackParameters(playbackRate)
        exo.playWhenReady = wantPlay
        exo.prepare()
    }

    fun setPlaying(on: Boolean) {
        wantPlay = on
        player?.playWhenReady = on
    }

    fun setRate(rate: Float) {
        playbackRate = rate.coerceIn(0.25f, 32f)
        player?.playbackParameters = PlaybackParameters(playbackRate)
    }

    override fun seek(seconds: Double, exact: Boolean) {
        val p = player ?: return
        p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
        emitProgress(seeking = true)
    }

    override suspend fun snapshot(label: String): String {
        val view = playerView ?: throw IllegalStateException("Nothing playing")
        val surface = view.videoSurfaceView ?: throw IllegalStateException("No video surface")
        val w = if (videoSize.width > 0) videoSize.width else surface.width
        val h = if (videoSize.height > 0) videoSize.height else surface.height
        if (w <= 0 || h <= 0 || player == null) {
            throw IllegalStateException("No frame available yet")
        }

        val bitmap: Bitmap = when {
            surface is TextureView -> {
                surface.getBitmap(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
                    ?: throw IllegalStateException("No frame available")
            }

            surface is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                suspendCancellableCoroutine { cont ->
                    PixelCopy.request(
                        surface,
                        target,
                        { copyResult ->
                            if (copyResult == PixelCopy.SUCCESS) {
                                cont.resume(target)
                            } else {
                                target.recycle()
                                cont.resumeWithException(IllegalStateException("PixelCopy failed ($copyResult)"))
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }

            else -> throw IllegalStateException("Snapshot unsupported on this surface")
        }

        val jpegBytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        }
        return CamsMediaTools.saveJpeg(context, jpegBytes, label, "replay")
    }

    fun emitProgress(seeking: Boolean) {
        val p = player ?: return
        val state = when (p.playbackState) {
            Player.STATE_BUFFERING -> ReplayPlayerState.BUFFERING
            Player.STATE_READY -> ReplayPlayerState.READY
            Player.STATE_ENDED -> ReplayPlayerState.ENDED
            else -> ReplayPlayerState.IDLE
        }
        onProgress(
            ReplayProgress(
                position = p.currentPosition / 1000.0,
                duration = if (p.duration > 0) p.duration / 1000.0 else 0.0,
                state = state,
                playing = p.isPlaying,
                seeking = seeking,
                decoder = decoderName,
                width = videoSize.width,
                height = videoSize.height,
            ),
        )
    }

    fun release() {
        playerView?.player = null
        player?.release()
        player = null
        currentSource = null
        decoderName = ""
        videoSize = VideoSize.UNKNOWN
    }
}
