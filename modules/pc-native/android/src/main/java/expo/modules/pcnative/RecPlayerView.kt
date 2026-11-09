package expo.modules.pcnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.ByteArrayOutputStream

/**
 * Replay player for one camrec recording: Media3 ExoPlayer playing one VOD HLS playlist (fMP4 chunks, the whole
 * last 24 h as one continuous timeline) into a TextureView (so the current frame can be saved), muted. MediaCodec
 * picks the hardware decoder (H.265 for cat3hd). JS drives it: source {url, start}, playing, rate; seek() and
 * snapshot() on the ref; onProgress every 250 ms and right after a seek.
 */
class RecPlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onProgress by EventDispatcher()
  private val onPlayerError by EventDispatcher()

  private val texture = TextureView(context)
  private var player: ExoPlayer? = null
  private var url: String? = null
  private var start = 0.0
  private var wantPlay = false
  private var rate = 1f
  private var decoder: String? = null
  private var video = VideoSize.UNKNOWN
  private val main = Handler(Looper.getMainLooper())
  private val ticker = object : Runnable {
    override fun run() {
      emit()
      main.postDelayed(this, 250)
    }
  }

  init {
    addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  // ---- props
  fun setSource(src: Map<String, Any?>?) {
    val u = src?.get("url") as? String
    val s = (src?.get("start") as? Number)?.toDouble() ?: 0.0
    if (u == null) return release()
    // same playlist and start: nothing to do, unless the player failed (JS reloads after an error)
    if (u == url && s == start && player != null && player?.playerError == null) return
    url = u
    start = s
    load()
  }

  fun setPlaying(on: Boolean) {
    wantPlay = on
    player?.playWhenReady = on
  }

  fun setRate(r: Double) {
    rate = r.toFloat().coerceIn(0.25f, 32f)
    player?.playbackParameters = PlaybackParameters(rate)
  }

  // ---- imperative
  fun seek(seconds: Double, exact: Boolean) {
    val p = player ?: return
    p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
    p.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
    emit(seeking = true)
  }

  /** The frame on screen as JPEG, saved to the gallery. */
  fun snapshot(label: String): String {
    val w = if (video.width > 0) video.width else texture.width
    val h = if (video.height > 0) video.height else texture.height
    if (w <= 0 || h <= 0 || player == null) throw PcException("E_SNAPSHOT", "nothing on screen yet")
    // full video resolution, independent of how big the tile is
    val bmp = texture.getBitmap(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
      ?: throw PcException("E_SNAPSHOT", "no frame")
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
    bmp.recycle()
    return CamsTools.saveJpeg(context, out.toByteArray(), label, "replay")
  }

  // ---- player
  private fun load() {
    val u = url ?: return
    val p = player ?: ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
      .build().also { np ->
        player = np
        np.volume = 0f // recordings play muted
        np.setVideoTextureView(texture)
        np.addListener(object : Player.Listener {
          override fun onVideoSizeChanged(size: VideoSize) {
            video = size
            fit()
          }
          override fun onPlayerError(error: PlaybackException) {
            onPlayerError(mapOf<String, Any>("message" to (error.message ?: error.errorCodeName)))
          }
          override fun onPlaybackStateChanged(state: Int) = emit()
          override fun onIsPlayingChanged(isPlaying: Boolean) = emit()
        })
        np.addAnalyticsListener(object : AnalyticsListener {
          override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
          ) {
            decoder = decoderName
          }
        })
        main.post(ticker)
      }
    p.setMediaItem(MediaItem.Builder().setUri(u).setMimeType(MimeTypes.APPLICATION_M3U8).build(), (start * 1000).toLong())
    p.playbackParameters = PlaybackParameters(rate)
    p.playWhenReady = wantPlay
    p.prepare()
  }

  /** Also called by the module when React drops the view (OnViewDestroys). */
  fun release() {
    main.removeCallbacks(ticker)
    player?.release()
    player = null
    url = null
    decoder = null
  }

  private fun emit(seeking: Boolean = false) {
    val p = player ?: return
    onProgress(
      mapOf<String, Any>(
        "position" to p.currentPosition / 1000.0,
        "duration" to if (p.duration > 0) p.duration / 1000.0 else 0.0,
        "state" to when (p.playbackState) {
          Player.STATE_BUFFERING -> "buffering"
          Player.STATE_READY -> "ready"
          Player.STATE_ENDED -> "ended"
          else -> "idle"
        },
        "playing" to p.isPlaying,
        "seeking" to seeking,
        "decoder" to (decoder ?: ""),
        "width" to video.width,
        "height" to video.height,
      ),
    )
  }

  /** object-fit: contain, centred (TextureView stretches by default) */
  private fun fit() {
    val vw = video.width * video.pixelWidthHeightRatio
    val vh = video.height.toFloat()
    val w = texture.width.toFloat()
    val h = texture.height.toFloat()
    if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
    val scale = minOf(w / vw, h / vh)
    val m = Matrix()
    m.setScale(vw * scale / w, vh * scale / h, w / 2, h / 2)
    texture.setTransform(m)
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    texture.layout(0, 0, r - l, b - t)
    fit()
  }

}
