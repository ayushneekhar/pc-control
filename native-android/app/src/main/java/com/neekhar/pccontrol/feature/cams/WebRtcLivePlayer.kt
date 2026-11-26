package com.neekhar.pccontrol.feature.cams

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin WebRTC live video surface for Jetpack Compose.
 *
 * - When [zoomable] is `false` (compact tiles, PiP, thumbnails), renders via `SurfaceViewRenderer`
 *   for minimal composition overhead and direct hardware surface presentation.
 * - When [zoomable] is `true` (main cards, fullscreen), renders via a `TextureView`-backed
 *   `SurfaceEglRenderer` (ported from `modules/pc-native/.../LivePlayerView.kt`), because Android
 *   `SurfaceView` ignores parent Compose `graphicsLayer` scale/translation transforms during pinch-to-zoom.
 */
@Composable
fun WebRtcLivePlayer(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    onFrameResolution: ((width: Int, height: Int) -> Unit)? = null,
) {
    if (zoomable) {
        WebRtcTextureVideoView(
            videoTrack = videoTrack,
            modifier = modifier,
            onFrameResolution = onFrameResolution,
        )
    } else {
        WebRtcSurfaceVideoView(
            videoTrack = videoTrack,
            modifier = modifier,
            onFrameResolution = onFrameResolution,
        )
    }
}

/** `SurfaceViewRenderer` wrapped in Compose `AndroidView` with automatic sink + EGL lifecycle cleanup. */
@Composable
fun WebRtcSurfaceVideoView(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    onFrameResolution: ((width: Int, height: Int) -> Unit)? = null,
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(rendererRef, videoTrack) {
        val renderer = rendererRef
        if (renderer != null && videoTrack != null) {
            runCatching { videoTrack.addSink(renderer) }
        }
        onDispose {
            if (renderer != null && videoTrack != null) {
                runCatching { videoTrack.removeSink(renderer) }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                setMirror(false)
                init(
                    WebRtcRuntime.getEglBaseContext(),
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit
                        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                            val rotated = rotation == 90 || rotation == 270
                            val w = if (rotated) videoHeight else videoWidth
                            val h = if (rotated) videoWidth else videoHeight
                            onFrameResolution?.invoke(w, h)
                        }
                    },
                )
                rendererRef = this
            }
        },
        update = { view ->
            if (rendererRef !== view) rendererRef = view
        },
        onRelease = { view ->
            if (videoTrack != null) {
                runCatching { videoTrack.removeSink(view) }
            }
            if (rendererRef === view) rendererRef = null
            runCatching { view.release() }
        },
    )
}

/**
 * `TextureView`-backed `SurfaceEglRenderer` wrapped in Compose `AndroidView`.
 * Honors Compose `graphicsLayer` pinch-to-zoom and pan smoothly while keeping hardware WebRTC decode.
 */
@Composable
fun WebRtcTextureVideoView(
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    onFrameResolution: ((width: Int, height: Int) -> Unit)? = null,
) {
    var viewRef by remember { mutableStateOf<LiveTextureView?>(null) }

    DisposableEffect(viewRef, videoTrack) {
        val view = viewRef
        if (view != null && videoTrack != null) {
            runCatching { videoTrack.addSink(view) }
        }
        onDispose {
            if (view != null && videoTrack != null) {
                runCatching { videoTrack.removeSink(view) }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LiveTextureView(ctx, onFrameResolution).also {
                it.start()
                viewRef = it
            }
        },
        update = { view ->
            view.onFrameResolution = onFrameResolution
            if (viewRef !== view) viewRef = view
        },
        onRelease = { view ->
            if (videoTrack != null) {
                runCatching { videoTrack.removeSink(view) }
            }
            if (viewRef === view) viewRef = null
            view.stop()
        },
    )
}

/**
 * Port of `LiveTextureView` from `modules/pc-native/.../LivePlayerView.kt`.
 * Never sets a background/foreground drawable on the TextureView (unsupported on API 24+).
 */
internal class LiveTextureView(
    context: Context,
    @Volatile var onFrameResolution: ((width: Int, height: Int) -> Unit)? = null,
) : TextureView(context),
    TextureView.SurfaceTextureListener,
    VideoSink,
    RendererCommon.RendererEvents {

    private val eglRenderer = SurfaceEglRenderer("PcLive")
    private var initialized = false
    private var currentSurface: SurfaceTexture? = null
    private var eglSurfaceFor: SurfaceTexture? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun start() {
        if (initialized) return
        try {
            eglRenderer.init(
                WebRtcRuntime.getEglBaseContext(),
                this,
                EglBase.CONFIG_PLAIN,
                GlRectDrawer(),
            )
            initialized = true
            currentSurface?.let { attachSurface(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "EGL renderer init failed", t)
        }
    }

    fun stop() {
        currentSurface = null
        eglSurfaceFor = null
        if (initialized) {
            initialized = false
            runCatching { eglRenderer.release() }
        }
    }

    private fun attachSurface(surface: SurfaceTexture) {
        if (!initialized || eglSurfaceFor === surface) return
        eglSurfaceFor = surface
        eglRenderer.createEglSurface(surface)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val h = (bottom - top).toFloat()
        if (h > 0f) {
            eglRenderer.setLayoutAspectRatio((right - left) / h)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (initialized) {
            eglRenderer.onFrame(frame)
        }
    }

    override fun onFirstFrameRendered() = Unit

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
        val rotated = rotation == 90 || rotation == 270
        val w = if (rotated) videoHeight else videoWidth
        val h = if (rotated) videoWidth else videoHeight
        onFrameResolution?.invoke(w, h)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        currentSurface = surface
        attachSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (currentSurface === surface) currentSurface = null
        if (eglSurfaceFor === surface) eglSurfaceFor = null
        if (initialized) {
            val done = CountDownLatch(1)
            runCatching {
                eglRenderer.releaseEglSurface { done.countDown() }
                done.await(500L, TimeUnit.MILLISECONDS)
            }
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    companion object {
        private const val TAG = "PcLiveTexture"
    }
}
