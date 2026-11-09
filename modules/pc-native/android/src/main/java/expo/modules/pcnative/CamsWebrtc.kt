package expo.modules.pcnative

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import com.oney.WebRTCModule.EglUtils
import com.oney.WebRTCModule.WebRTCModuleOptions
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Native cams player (react-native-webrtc): options that must be in place before its WebRTCModule is created,
 * so they are set from Application.onCreate (CamsPackage).
 *
 * At that point libjingle_peerconnection_so is NOT loaded yet (react-native-webrtc loads it in
 * PeerConnectionFactory.initialize, when its module is created), so nothing built here may touch JNI:
 * 1.4.0 constructed SoftwareVideoEncoderFactory here, whose constructor calls native code, and configure()
 * failed with UnsatisfiedLinkError on the phone. Everything below is plain Java/Kotlin until first use, which
 * is always after the library is loaded. scripts/cams/check-startup-jni.sh checks the compiled classes for this.
 *
 * - Video decoders: H.264 and H.265 on MediaCodec hardware only (react-native-webrtc's default factory offers
 *   hardware H.264 but never H.265, so go2rtc could not send the CP Plus HD stream as is). VP8/VP9/AV1 stay in
 *   software; go2rtc never sends them here.
 * - Field trial WebRTC-ForcePlayoutDelay min=max=0: libwebrtc's low-latency renderer, every frame is shown as soon
 *   as it is decoded instead of waiting for the jitter estimate (same thing a playout-delay 0 header asks for in
 *   cloud gaming). A jittery link shows uneven motion rather than extra delay. Video receive streams only.
 * - Audio plays as media (loudspeaker, media volume) instead of the default voice-call usage, which Android
 *   routes to the earpiece. The mic (Talk) still records with the VOICE_COMMUNICATION source, so the phone's
 *   echo canceller and noise suppressor stay on.
 */
object CamsWebrtc {
  private const val TAG = "PcCams"
  const val FIELD_TRIALS = "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/"

  @Volatile var configured = false
    private set
  @Volatile var error: String? = null
    private set

  fun configure(ctx: Context) {
    if (configured) return
    try {
      val o = WebRTCModuleOptions.getInstance()
      o.videoDecoderFactory = HwH26xDecoderFactory()
      o.videoEncoderFactory = NoVideoEncoderFactory()
      o.fieldTrials = FIELD_TRIALS
      o.audioDeviceModule = LazyAudioDeviceModule(ctx.applicationContext)
      configured = true
    } catch (t: Throwable) {
      // react-native-webrtc then falls back to its own defaults (H.264 only): the app shows SD for the CP Plus.
      error = t.toString()
      Log.w(TAG, "WebRTC options not set", t)
    }
  }

  /** Hardware decoders on this phone for the cams' codecs (name, max size, low-latency feature). */
  fun hardwareDecoders(): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    for ((key, mime) in listOf("h264" to "video/avc", "h265" to "video/hevc")) {
      val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
        !it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals(mime, true) }
      }
      out[key] = info?.let {
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
    }
    out["configured"] = configured
    out["error"] = error
    out["fieldTrials"] = FIELD_TRIALS
    return out
  }
}

/**
 * H.264 + H.265 from MediaCodec hardware (HardwareVideoDecoderFactory only lists codecs with a hardware decoder,
 * so a phone without hardware HEVC never offers H.265 and go2rtc can't pick it), everything else in software.
 * The hardware factory is created on first use, on WebRTC's thread, with react-native-webrtc's shared EGL
 * context (texture output, no copy to the CPU).
 */
class HwH26xDecoderFactory : VideoDecoderFactory {
  private val hw by lazy { HardwareVideoDecoderFactory(EglUtils.getRootEglBaseContext()) }
  private val sw by lazy { SoftwareVideoDecoderFactory() }

  private fun isH26x(name: String) = name.equals("H264", true) || name.equals("H265", true)

  override fun createDecoder(info: VideoCodecInfo): VideoDecoder? =
    if (isH26x(info.name)) hw.createDecoder(info) else sw.createDecoder(info)

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val hwCodecs = hw.supportedCodecs.filter { isH26x(it.name) }
    val swCodecs = sw.supportedCodecs.filter { !isH26x(it.name) }
    // H.265 first: it only matters for cat3hd (go2rtc picks the stream's own codec), but a decoder list that
    // starts with it is what makes the offer advertise it prominently.
    return (hwCodecs.sortedBy { if (it.name.equals("H265", true)) 0 else 1 } + swCodecs).toTypedArray()
  }
}

/** The app only receives video: no encoders (and no native factory to create before the library is loaded). */
class NoVideoEncoderFactory : VideoEncoderFactory {
  override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = null
  override fun getSupportedCodecs(): Array<VideoCodecInfo> = emptyArray()
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
