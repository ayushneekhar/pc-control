package expo.modules.pcnative

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import com.oney.WebRTCModule.EglUtils
import com.oney.WebRTCModule.WebRTCModuleOptions
import com.oney.WebRTCModule.webrtcutils.H264AndSoftwareVideoDecoderFactory
import com.oney.WebRTCModule.webrtcutils.H264AndSoftwareVideoEncoderFactory
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Native cams player (react-native-webrtc) options. Each one can be switched off, and safe mode turns them all off
 * (react-native-webrtc's own defaults, which is what 1.4.0 ran):
 *
 * - decoder: H.265 on MediaCodec hardware next to react-native-webrtc's own H.264 + software decoders. Only when
 *   the bundled libwebrtc can carry H.265 over RTP (LIBWEBRTC_H265). org.jitsi:webrtc 155 can't: it has no H.265
 *   depacketizer. Offering H.265 anyway (1.4.1 to 1.5.0) made go2rtc send cat3hd as H.265, and libwebrtc died
 *   natively on the first packets, which crashed the app as soon as Cat cams opened.
 * - lowLatency: field trial WebRTC-ForcePlayoutDelay min=max=0, libwebrtc's low-latency renderer.
 * - mediaAudio: audio plays as media (loudspeaker) instead of voice-call usage (earpiece on some phones).
 *
 * They must be set before react-native-webrtc creates its WebRTCModule (it reads them once, in its constructor),
 * and libjingle isn't loaded at that point, so nothing built here may call native code (1.4.0 did and got an
 * UnsatisfiedLinkError; scripts/cams/check-startup-jni.sh guards this). prepare() applies them. The app calls it
 * right before the Cams screen loads react-native-webrtc (App.tsx), so nothing here runs at app start.
 *
 * Crash guard: a marker in SharedPreferences, written synchronously, set by the first prepare() and by the Cams
 * screen each time its live video starts; the screen clears it after 15 s of live video, or when that stops. If the next app start finds the marker and Android's last exit record
 * is a crash, that run died while Cat cams was opening: safe mode turns itself on. The next open runs on the
 * defaults and says so, and the menu can turn safe mode off again. Native crashes can't be caught, so this is the
 * only fallback for them.
 */
object CamsWebrtc {
  private const val TAG = "PcCams"
  const val FIELD_TRIALS = "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/"
  private const val FILE = "pc_cams"

  /**
   * The bundled libwebrtc depacketizes H.265 (built with rtc_use_h265). scripts/cams/check-webrtc-h265.sh (run by
   * build-apk.sh) fails the build if this is true but the packaged libjingle_peerconnection_so has no H.265 RTP code.
   */
  const val LIBWEBRTC_H265 = false
  private const val NO_H265 = "this build's libwebrtc can't receive H.265 (org.jitsi:webrtc has no H.265 RTP support)"

  private val KEYS = setOf("safeMode", "decoder", "lowLatency", "mediaAudio")

  /** react-native-webrtc's module reads the options once per process: later changes need a restart. */
  @Volatile var prepared = false
    private set
  @Volatile var applied: Map<String, Boolean> = emptyMap()
    private set
  @Volatile var error: String? = null
    private set
  /** Marker found at this app start (the previous run died while Cat cams was opening), ms since epoch, else 0. */
  @Volatile var crashedWhileOpening: Long = 0
    private set

  private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  // ---- settings
  fun settings(ctx: Context): Map<String, Boolean> {
    val p = sp(ctx)
    return mapOf(
      "safeMode" to p.getBoolean("safeMode", false),
      "decoder" to p.getBoolean("decoder", true),
      "lowLatency" to p.getBoolean("lowLatency", true),
      "mediaAudio" to p.getBoolean("mediaAudio", true),
    )
  }

  fun setSetting(ctx: Context, key: String, on: Boolean) {
    if (key !in KEYS) throw PcException("E_SETTING", "unknown setting $key")
    sp(ctx).edit().putBoolean(key, on).commit()
  }

  // ---- crash marker
  /**
   * Application.onCreate. Reads the marker left by the previous run. If that run then crashed, turns safe mode on.
   * SharedPreferences and ApplicationExitInfo only: no WebRTC, never throws.
   */
  fun onAppStart(ctx: Context) {
    try {
      val p = sp(ctx)
      val marker = p.getLong("opening", 0)
      if (marker == 0L) return
      p.edit().remove("opening").commit()
      // no crash record after the marker = killed or swiped away while opening: not ours
      val exit = CrashReporter.lastExitWasCrash(ctx)
      if (exit < marker) return
      crashedWhileOpening = marker
      p.edit().putBoolean("safeMode", true).putLong("safeSince", exit).commit()
      Log.w(TAG, "previous run crashed while Cat cams was opening: safe mode on")
    } catch (t: Throwable) {
      Log.w(TAG, "crash marker check failed", t)
    }
  }

  fun markOpening(ctx: Context, on: Boolean) {
    val e = sp(ctx).edit()
    if (on) e.putLong("opening", System.currentTimeMillis()) else e.remove("opening")
    e.commit() // synchronous: the process may die right after
  }

  fun isOpening(ctx: Context) = sp(ctx).getLong("opening", 0) != 0L

  fun clearCrashedWhileOpening() {
    crashedWhileOpening = 0
  }

  /**
   * Right before react-native-webrtc is loaded: sets the marker, and this process's options (once, because the
   * module is created once per process). In safe mode, or with every option off, react-native-webrtc keeps its own
   * defaults and none of our factories is built. Never throws: whatever fails, the defaults stay.
   */
  @Synchronized
  fun prepare(ctx: Context): Map<String, Any?> {
    if (!prepared) {
      prepared = true
      // covers react-native-webrtc's module creation; the Cams screen sets it again each time live video starts
      try {
        markOpening(ctx, true)
      } catch (t: Throwable) {
        Log.w(TAG, "marker not set", t)
      }
      val s = settings(ctx)
      val safe = s["safeMode"] == true
      val want = mapOf(
        "decoder" to (!safe && LIBWEBRTC_H265 && s["decoder"] == true),
        "lowLatency" to (!safe && s["lowLatency"] == true),
        "mediaAudio" to (!safe && s["mediaAudio"] == true),
      )
      if (!LIBWEBRTC_H265) error = NO_H265
      try {
        val o = WebRTCModuleOptions.getInstance()
        if (want["decoder"] == true) {
          o.videoDecoderFactory = HwH265PlusDefaultDecoderFactory()
          o.videoEncoderFactory = LazyDefaultEncoderFactory()
        }
        if (want["lowLatency"] == true) o.fieldTrials = FIELD_TRIALS
        if (want["mediaAudio"] == true) o.audioDeviceModule = LazyAudioDeviceModule(ctx.applicationContext)
        applied = want
      } catch (t: Throwable) {
        // back to react-native-webrtc's defaults
        try {
          val o = WebRTCModuleOptions.getInstance()
          o.videoDecoderFactory = null
          o.videoEncoderFactory = null
          o.fieldTrials = null
          o.audioDeviceModule = null
        } catch (_: Throwable) {
        }
        applied = want.mapValues { false }
        error = t.toString()
        Log.w(TAG, "WebRTC options not set", t)
      }
    }
    return status(ctx)
  }

  fun status(ctx: Context): Map<String, Any?> = mapOf(
    "prepared" to prepared,
    "applied" to applied,
    "error" to error,
    "settings" to settings(ctx),
    "crashedWhileOpening" to crashedWhileOpening.toDouble(),
  )

  /** Hardware decoders on this phone for the cams' codecs (name, max size, low-latency feature) + option state. */
  fun hardwareDecoders(ctx: Context): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    for ((key, mime) in listOf("h264" to "video/avc", "h265" to "video/hevc")) {
      out[key] = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
          !it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals(mime, true) }
        }?.let {
          val caps = it.getCapabilitiesForType(mime)
          val v = caps.videoCapabilities
          mapOf(
            "name" to it.name,
            "maxWidth" to v.supportedWidths.upper,
            "maxHeight" to v.supportedHeights.upper,
            "hd" to v.isSizeSupported(2304, 1296),
            "lowLatency" to caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
          )
        }
      } catch (t: Throwable) {
        null
      }
    }
    // "configured" = H.265 over WebRTC is on in this process (custom decoder factory applied)
    out["configured"] = applied["decoder"] == true
    out["safeMode"] = settings(ctx)["safeMode"] == true
    out["error"] = error ?: if (!prepared) "not prepared" else if (settings(ctx)["safeMode"] == true) "safe mode" else "H.265 decoder option off"
    out["fieldTrials"] = if (applied["lowLatency"] == true) FIELD_TRIALS else ""
    return out
  }
}

/**
 * react-native-webrtc's own default decoders (hardware H.264 high + baseline, software VP8/VP9/AV1: exactly what
 * 1.4.0 ran) plus H.265 from MediaCodec hardware, appended (only listed when the phone has a hardware HEVC
 * decoder). Everything is created on first use, after libjingle is loaded, with react-native-webrtc's shared EGL
 * context.
 */
class HwH265PlusDefaultDecoderFactory : VideoDecoderFactory {
  private val base by lazy { H264AndSoftwareVideoDecoderFactory(EglUtils.getRootEglBaseContext()) }
  private val hw by lazy { HardwareVideoDecoderFactory(EglUtils.getRootEglBaseContext()) }

  private fun isH265(name: String) = name.equals("H265", true)

  override fun createDecoder(info: VideoCodecInfo): VideoDecoder? =
    if (isH265(info.name)) hw.createDecoder(info) else base.createDecoder(info)

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val h265 = try {
      hw.supportedCodecs.filter { isH265(it.name) }
    } catch (t: Throwable) {
      Log.w("PcCams", "no H.265 decoder list", t)
      emptyList()
    }
    return base.supportedCodecs + h265
  }
}

/** react-native-webrtc's own default encoders (the app never sends video), created on first use. */
class LazyDefaultEncoderFactory : VideoEncoderFactory {
  private val real by lazy { H264AndSoftwareVideoEncoderFactory(EglUtils.getRootEglBaseContext()) }
  override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = real.createEncoder(info)
  override fun getSupportedCodecs(): Array<VideoCodecInfo> = real.supportedCodecs
  override fun getImplementations(): Array<VideoCodecInfo> = real.implementations
  override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = real.encoderSelector
}

/**
 * JavaAudioDeviceModule, built on first use (PeerConnectionFactory asks for its native pointer while
 * react-native-webrtc creates the factory, after loading the library). Playback as media (loudspeaker), capture
 * with the voice-communication source plus the phone's echo canceller and noise suppressor.
 */
class LazyAudioDeviceModule(private val ctx: Context) : AudioDeviceModule {
  private var real: JavaAudioDeviceModule? = null

  @Synchronized
  private fun adm(): JavaAudioDeviceModule = real ?: JavaAudioDeviceModule.builder(ctx)
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    )
    .setUseHardwareAcousticEchoCanceler(true)
    .setUseHardwareNoiseSuppressor(true)
    .setEnableVolumeLogger(false)
    .createAudioDeviceModule()
    .also { real = it }

  override fun getNative(webrtcEnvRef: Long): Long = adm().getNative(webrtcEnvRef)
  @Synchronized override fun release() { real?.release() }
  override fun setSpeakerMute(mute: Boolean) = adm().setSpeakerMute(mute)
  override fun setMicrophoneMute(mute: Boolean) = adm().setMicrophoneMute(mute)
  override fun setNoiseSuppressorEnabled(enabled: Boolean): Boolean = adm().setNoiseSuppressorEnabled(enabled)
}
