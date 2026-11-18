package expo.modules.pcnative

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
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

/**
 * Live cat-cam renderer on a TextureView, so the JS PinchZoom wrapper can scale/translate it.
 *
 * react-native-webrtc's RTCView is a SurfaceViewRenderer, and Android does not honour parent transforms for a
 * SurfaceView. This view keeps the same decoder/link path (only the renderer changes): the first video track of
 * the stream is attached to a TextureView-backed SurfaceEglRenderer (the same EGL renderer WebRTC uses for its
 * SurfaceView), so hardware H.265 and the low-latency playout settings are untouched. Home LiveTiles keep RTCView.
 */
class LivePlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val texture = LiveTextureView(context)
  private var streamURL: String? = null
  private var track: VideoTrack? = null
  private var sinkAdded = false
  private var attached = false
  private var generation = 0

  init {
    setBackgroundColor(Color.BLACK)
    texture.setBackgroundColor(Color.BLACK)
    addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  fun setStreamURL(url: String?) {
    val next = url?.takeIf { it.isNotEmpty() }
    if (next == streamURL) return
    streamURL = next
    generation++
    detachTrack()
    if (next != null && attached) loadTrack(next, generation)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attached = true
    texture.start()
    streamURL?.let { loadTrack(it, generation) }
  }

  override fun onDetachedFromWindow() {
    attached = false
    generation++
    detachTrack()
    texture.stop()
    super.onDetachedFromWindow()
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    texture.layout(0, 0, r - l, b - t)
  }

  fun release() {
    attached = false
    generation++
    detachTrack()
    texture.stop()
  }

  private fun loadTrack(url: String, gen: Int) {
    PcWebRTCStreams.getVideoTrack(context, url) { found ->
      if (!attached || gen != generation || streamURL != url) return@getVideoTrack
      detachTrack()
      track = found
      if (found != null) {
        sinkAdded = true
        PcWebRTCStreams.addSink(found, texture)
      }
    }
  }

  private fun detachTrack() {
    val old = track ?: return
    track = null
    if (sinkAdded) {
      sinkAdded = false
      PcWebRTCStreams.removeSink(old, texture)
    }
  }
}

/** The TextureView half of the renderer: EGL surface lifecycle + VideoSink, adapted from WebRTC's TextureViewRenderer. */
private class LiveTextureView(context: Context) : TextureView(context),
  TextureView.SurfaceTextureListener, VideoSink, RendererCommon.RendererEvents {
  private val eglRenderer = SurfaceEglRenderer("PcLive")
  private var initialized = false
  private var surfaceReady = false
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
      if (startTries++ < 40) postDelayed(startRunnable, 50)
      return
    }
    try {
      eglRenderer.init(shared, this, EglBase.CONFIG_PLAIN, GlRectDrawer())
      initialized = true
      startTries = 0
      if (surfaceReady) surfaceTexture?.let { eglRenderer.createEglSurface(it) }
    } catch (t: Throwable) {
      Log.e(TAG, "renderer init failed", t)
      if (startTries++ < 40) postDelayed(startRunnable, 50)
    }
  }

  fun stop() {
    removeCallbacks(startRunnable)
    startTries = 0
    surfaceReady = false
    if (initialized) {
      initialized = false
      eglRenderer.release()
    }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    val h = (bottom - top).toFloat()
    eglRenderer.setLayoutAspectRatio(if (h > 0) (right - left) / h else 1f)
  }

  override fun onFrame(frame: VideoFrame) {
    eglRenderer.onFrame(frame)
  }

  override fun onFirstFrameRendered() {
    Log.d(TAG, "first frame")
  }

  override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
    // The EglRenderer fits each frame to the current layout aspect ratio; no resize needed.
  }

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    surfaceReady = true
    if (initialized) eglRenderer.createEglSurface(surface)
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    // EglRenderer uses the view's layout aspect ratio; a size change is handled by onLayout.
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    surfaceReady = false
    if (initialized) {
      val done = CountDownLatch(1)
      try {
        eglRenderer.releaseEglSurface { done.countDown() }
        done.await()
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
