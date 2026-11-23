package expo.modules.pcnative

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams
import com.oney.WebRTCModule.EglUtils
import com.oney.WebRTCModule.PcWebRTCStreams
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live cat-cam renderer on a TextureView, so the JS PinchZoom wrapper can scale/translate it.
 *
 * react-native-webrtc's RTCView is a SurfaceViewRenderer, and Android does not honour parent transforms for a
 * SurfaceView. This view keeps the same decoder/link path (only the renderer changes): the first video track of
 * the stream is attached to a TextureView-backed SurfaceEglRenderer (the same EGL renderer WebRTC uses for its
 * SurfaceView), so hardware H.265 and the low-latency playout settings are untouched. Home LiveTiles keep RTCView.
 *
 * Never call setBackground/setForeground on the TextureView: it throws UnsupportedOperationException for apps
 * targeting N or later (Expo then replaces the whole view with an ErrorView, which is a plain black View).
 */
class LivePlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val texture = LiveTextureView(context)
  private var streamURL: String? = null
  private var track: VideoTrack? = null
  private var sinkAdded = false
  private var attached = false
  private var generation = 0

  init {
    Log.i(TAG, "view created")
    setBackgroundColor(Color.BLACK) // the ExpoView (LinearLayout) paints the letterbox; the TextureView draws over it
    addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  fun setStreamURL(url: String?) {
    val next = url?.takeIf { it.isNotEmpty() }
    if (next == streamURL) return
    Log.i(TAG, "stream URL set: $next")
    streamURL = next
    generation++
    detachTrack()
    if (next != null && attached) loadTrack(next, generation)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    Log.i(TAG, "attached")
    attached = true
    texture.start()
    streamURL?.let { loadTrack(it, generation) }
  }

  override fun onDetachedFromWindow() {
    Log.i(TAG, "detached")
    attached = false
    generation++
    detachTrack()
    texture.stop()
    super.onDetachedFromWindow()
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    val w = r - l
    val h = b - t
    // Fabric can lay this view out without a measure pass of its Android children, so measure the TextureView
    // with exact specs (what Fabric does for host views) before laying it out: a 0x0 surface draws nothing.
    if (texture.width != w || texture.height != h) {
      texture.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
      texture.layout(0, 0, w, h)
    }
  }

  fun release() {
    Log.i(TAG, "release")
    attached = false
    generation++
    detachTrack()
    texture.stop()
  }

  private fun loadTrack(url: String, gen: Int) {
    PcWebRTCStreams.getVideoTrack(context, url) { found ->
      if (!attached || gen != generation || streamURL != url) return@getVideoTrack
      detachTrack()
      if (found == null) {
        Log.w(TAG, "no video track for $url")
        return@getVideoTrack
      }
      track = found
      sinkAdded = true
      Log.i(TAG, "adding sink for $url")
      PcWebRTCStreams.addSink(found, texture)
    }
  }

  private fun detachTrack() {
    val old = track ?: return
    Log.i(TAG, "removing sink")
    track = null
    if (sinkAdded) {
      sinkAdded = false
      PcWebRTCStreams.removeSink(old, texture)
    }
  }

  companion object {
    private const val TAG = "PcLive"
  }
}

/** The TextureView half of the renderer: EGL surface lifecycle + VideoSink, adapted from WebRTC's TextureViewRenderer. */
private class LiveTextureView(context: Context) : TextureView(context),
  TextureView.SurfaceTextureListener, VideoSink, RendererCommon.RendererEvents {
  private val eglRenderer = SurfaceEglRenderer("PcLive")
  private var initialized = false
  private var surfaceTexture: SurfaceTexture? = null
  private var eglSurfaceFor: SurfaceTexture? = null
  private var frames = 0
  private var startTries = 0
  private val startRunnable = Runnable { start() }

  init {
    surfaceTextureListener = this
    isOpaque = true
  }

  fun start() {
    if (initialized) return
    removeCallbacks(startRunnable)
    val shared = try {
      EglUtils.getRootEglBaseContext()
    } catch (t: Throwable) {
      Log.w(TAG, "no root EGL context", t)
      null
    }
    if (shared == null) {
      if (startTries++ < 40) postDelayed(startRunnable, 50) else Log.e(TAG, "renderer start failed: no EGL context")
      return
    }
    try {
      eglRenderer.init(shared, this, EglBase.CONFIG_PLAIN, GlRectDrawer())
      initialized = true
      startTries = 0
      Log.i(TAG, "EGL renderer initialized ${width}x${height} surface=${surfaceTexture != null}")
      surfaceTexture?.let { createSurface(it) }
    } catch (t: Throwable) {
      Log.e(TAG, "renderer init failed", t)
      if (startTries++ < 40) postDelayed(startRunnable, 50)
    }
  }

  fun stop() {
    removeCallbacks(startRunnable)
    startTries = 0
    surfaceTexture = null
    eglSurfaceFor = null
    if (initialized) {
      initialized = false
      Log.i(TAG, "EGL renderer released")
      eglRenderer.release()
    }
  }

  private fun createSurface(surface: SurfaceTexture) {
    if (!initialized || eglSurfaceFor === surface) return
    eglSurfaceFor = surface
    Log.i(TAG, "EGL surface create ${width}x${height}")
    eglRenderer.createEglSurface(surface)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    val h = (bottom - top).toFloat()
    eglRenderer.setLayoutAspectRatio(if (h > 0) (right - left) / h else 1f)
  }

  override fun onFrame(frame: VideoFrame) {
    if (frames++ == 0) {
      Log.i(TAG, "first frame received ${frame.buffer.width}x${frame.buffer.height} init=$initialized surface=${eglSurfaceFor != null}")
    }
    eglRenderer.onFrame(frame)
  }

  override fun onFirstFrameRendered() {
    Log.i(TAG, "first frame rendered")
  }

  override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
    // The EglRenderer fits each frame to the current layout aspect ratio; no resize needed.
    Log.i(TAG, "frame resolution ${videoWidth}x$videoHeight rotation=$rotation")
  }

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    Log.i(TAG, "surface available ${width}x$height init=$initialized")
    surfaceTexture = surface
    createSurface(surface)
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    // EglRenderer uses the view's layout aspect ratio; a size change is handled by onLayout.
    Log.i(TAG, "surface size ${width}x$height")
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    Log.i(TAG, "surface destroyed")
    if (surfaceTexture === surface) surfaceTexture = null
    if (eglSurfaceFor === surface) eglSurfaceFor = null
    if (initialized) {
      val done = CountDownLatch(1)
      try {
        eglRenderer.releaseEglSurface { done.countDown() }
        if (!done.await(500, TimeUnit.MILLISECONDS)) Log.w(TAG, "surface release timed out")
      } catch (t: Throwable) {
        Log.w(TAG, "surface release failed", t)
      }
    }
    return true
  }

  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

  companion object {
    private const val TAG = "PcLive"
  }
}
