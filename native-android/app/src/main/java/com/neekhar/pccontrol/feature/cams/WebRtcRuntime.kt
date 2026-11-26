package com.neekhar.pccontrol.feature.cams

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.neekhar.pccontrol.core.platform.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

data class CodecHardwareInfo(
    val name: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val supports2304x1296: Boolean,
    val lowLatency: Boolean,
)

data class CamsHardwareCapabilities(
    val h264: CodecHardwareInfo?,
    val h265: CodecHardwareInfo?,
    val webrtcH265Supported: Boolean,
    val hdReason: String?,
) {
    val replayHevcSupported: Boolean get() = h265?.supports2304x1296 == true
    val liveHdSupported: Boolean get() = webrtcH265Supported && replayHevcSupported
}

/**
 * Process-wide pure-Kotlin WebRTC runtime using `org.webrtc` (`stream-webrtc-android`).
 * Ported from `modules/pc-native/.../CamsWebrtc.kt`:
 * - Shared [EglBase] for hardware decoding + rendering.
 * - `WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/` for ultra-low-latency live viewing.
 * - `JavaAudioDeviceModule` configured with `USAGE_MEDIA` so camera/laptop audio routes to loudspeaker.
 * - Crash-marker safe mode in SharedPreferences (`pc_cams`).
 */
object WebRtcRuntime {
    private const val TAG = "PcCamsWebRtc"
    private const val PREFS_FILE = "pc_cams"
    private const val KEY_SAFE_MODE = "safeMode"
    private const val KEY_OPENING = "opening"
    private const val KEY_SAFE_SINCE = "safeSince"
    const val LOW_LATENCY_FIELD_TRIALS = "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/"

    /**
     * Whether the bundled `libjingle_peerconnection_so.so` was built with `rtc_use_h265`
     * (H.265 RTP depacketizer). `io.getstream:stream-webrtc-android:1.3.8` has NO H.265
     * depacketizer; advertising H.265 in SDP causes go2rtc to send H.265 packets and
     * crashes `worker_thread` with `SIGSEGV` at `0x0`.
     */
    const val LIBWEBRTC_H265 = false

    @Volatile
    private var initialized = false

    @Volatile
    private var rootEglBase: EglBase? = null

    @Volatile
    private var peerConnectionFactory: PeerConnectionFactory? = null

    @Volatile
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Called from `Application.onCreate()`. Checks if the previous run died natively while
     * opening Cat cams and switches safe mode on automatically. SharedPreferences +
     * ApplicationExitInfo only — never loads WebRTC native code.
     */
    fun onAppStart(context: Context) {
        try {
            val p = prefs(context)
            val marker = p.getLong(KEY_OPENING, 0L)
            if (marker == 0L) return
            p.edit().remove(KEY_OPENING).commit()
            val exitTs = CrashReporter.lastExitWasCrash(context)
            if (exitTs < marker) return
            p.edit()
                .putBoolean(KEY_SAFE_MODE, true)
                .putLong(KEY_SAFE_SINCE, exitTs)
                .commit()
            Log.w(TAG, "Previous run crashed while Cat cams was opening; safe mode enabled")
        } catch (t: Throwable) {
            Log.w(TAG, "Crash marker check failed", t)
        }
    }

    fun isSafeMode(context: Context): Boolean = prefs(context).getBoolean(KEY_SAFE_MODE, false)

    fun setSafeMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAFE_MODE, enabled).commit()
    }

    fun markOpening(context: Context, opening: Boolean) {
        val ed = prefs(context).edit()
        if (opening) ed.putLong(KEY_OPENING, System.currentTimeMillis()) else ed.remove(KEY_OPENING)
        ed.commit()
    }

    fun getEglBase(): EglBase {
        return rootEglBase ?: synchronized(this) {
            rootEglBase ?: EglBase.create().also { rootEglBase = it }
        }
    }

    fun getEglBaseContext(): EglBase.Context = getEglBase().eglBaseContext

    /**
     * Lazily initializes the process-wide [PeerConnectionFactory]. Call from a background/IO
     * thread only (never from UI composition).
     */
    @Synchronized
    fun ensureFactory(context: Context): PeerConnectionFactory {
        peerConnectionFactory?.let { return it }
        val appCtx = context.applicationContext
        val safeMode = isSafeMode(appCtx)

        if (!initialized) {
            val initBuilder = PeerConnectionFactory.InitializationOptions.builder(appCtx)
            if (!safeMode) {
                initBuilder.setFieldTrials(LOW_LATENCY_FIELD_TRIALS)
            }
            PeerConnectionFactory.initialize(initBuilder.createInitializationOptions())
            initialized = true
        }

        val eglContext = getEglBaseContext()
        val adm = JavaAudioDeviceModule.builder(appCtx)
            .setEnableVolumeLogger(false)
            .apply {
                if (!safeMode) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    if (JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()) {
                        setUseHardwareAcousticEchoCanceler(true)
                    }
                    if (JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()) {
                        setUseHardwareNoiseSuppressor(true)
                    }
                }
            }
            .createAudioDeviceModule()
            .also { audioDeviceModule = it }

        val decoderFactory = SafeVideoDecoderFactory(eglContext, allowH265 = LIBWEBRTC_H265)
        val encoderFactory = SafeVideoEncoderFactory(eglContext, allowH265 = LIBWEBRTC_H265)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
            .also { peerConnectionFactory = it }
    }

    /**
     * Inspect Android MediaCodec hardware decoders purely via [MediaCodecList] without touching
     * native WebRTC or EGL on the UI thread.
     */
    fun inspectHardware(@Suppress("UNUSED_PARAMETER") context: Context): CamsHardwareCapabilities {
        val h264 = findHwDecoder("video/avc")
        val h265 = findHwDecoder("video/hevc")
        val webrtcHasH265 = LIBWEBRTC_H265

        val reason = when {
            h265 == null -> "This phone has no hardware H.265 decoder"
            !h265.supports2304x1296 -> "${h265.name} cannot decode 2304×1296"
            !webrtcHasH265 -> "WebRTC build does not offer H.265 RTP"
            else -> null
        }

        return CamsHardwareCapabilities(
            h264 = h264,
            h265 = h265,
            webrtcH265Supported = webrtcHasH265,
            hdReason = reason,
        )
    }

    private fun findHwDecoder(mime: String): CodecHardwareInfo? {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder &&
                    isHardwareOnly(info) &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.let { info ->
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities
                CodecHardwareInfo(
                    name = info.name,
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    supports2304x1296 = video.isSizeSupported(2304, 1296),
                    lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                )
            }
        }.getOrNull()
    }

    private fun isHardwareOnly(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            val n = info.name.lowercase()
            !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
        }
    }

    /** Convert http(s) base URL to go2rtc WebSocket signaling URL (`/api/ws?src=<src>`). */
    fun wsUrl(baseUrl: String, src: String): String {
        val wsBase = baseUrl.trimEnd('/')
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        val encoded = URLEncoder.encode(src, "UTF-8")
        return "$wsBase/api/ws?src=$encoded"
    }

    /** Extract the video codec name chosen by go2rtc in its SDP answer (e.g. `H264`, `H265`). */
    fun answerVideoCodec(sdp: String?): String? {
        if (sdp.isNullOrBlank()) return null
        val mLine = Regex("""^m=video \d+ [A-Z/]+ (\d+)""", RegexOption.MULTILINE).find(sdp) ?: return null
        val pt = mLine.groupValues[1]
        val rtpmap = Regex("""^a=rtpmap:$pt ([A-Za-z0-9-]+)/""", RegexOption.MULTILINE).find(sdp) ?: return null
        return rtpmap.groupValues[1].uppercase()
    }
}

/**
 * Wraps [DefaultVideoDecoderFactory] and strips H.265/HEVC codecs when the bundled
 * `libjingle_peerconnection_so.so` lacks an H.265 RTP depacketizer (preventing native SIGSEGV).
 */
private class SafeVideoDecoderFactory(
    eglContext: EglBase.Context,
    private val allowH265: Boolean,
) : VideoDecoderFactory {
    private val delegate = DefaultVideoDecoderFactory(eglContext)

    private fun isDisallowed(name: String?): Boolean =
        !allowH265 && (name.equals("H265", ignoreCase = true) || name.equals("HEVC", ignoreCase = true))

    override fun createDecoder(codecType: VideoCodecInfo): VideoDecoder? {
        if (isDisallowed(codecType.name)) return null
        return delegate.createDecoder(codecType)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val all = delegate.supportedCodecs
        return if (allowH265) all else all.filterNot { isDisallowed(it.name) }.toTypedArray()
    }
}

/**
 * Wraps [DefaultVideoEncoderFactory] and strips H.265/HEVC codecs when unsupported by libwebrtc.
 */
private class SafeVideoEncoderFactory(
    eglContext: EglBase.Context,
    private val allowH265: Boolean,
) : VideoEncoderFactory {
    private val delegate = DefaultVideoEncoderFactory(eglContext, true, true)

    private fun isDisallowed(name: String?): Boolean =
        !allowH265 && (name.equals("H265", ignoreCase = true) || name.equals("HEVC", ignoreCase = true))

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (isDisallowed(info.name)) return null
        return delegate.createEncoder(info)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val all = delegate.supportedCodecs
        return if (allowH265) all else all.filterNot { isDisallowed(it.name) }.toTypedArray()
    }

    override fun getImplementations(): Array<VideoCodecInfo> {
        val all = delegate.implementations
        return if (allowH265) all else all.filterNot { isDisallowed(it.name) }.toTypedArray()
    }

    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = delegate.encoderSelector
}

/**
 * One pure-Kotlin WebRTC connection to a go2rtc stream (`/api/ws?src=<stream>`).
 * Ported from `src/lib/cams/link.ts` and `src/lib/cams/signal.ts`:
 * - Video links are recvonly video (no audio piggybacking, avoiding ~400 ms lip-sync delay).
 * - Audio links are recvonly audio (laptop mic `mic`, or CP Plus mic `cat3hd`).
 * - Talk links are sendonly audio from phone mic to go2rtc `talk` backchannel.
 */
class WebRtcCamLink(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    val src: String,
    val kind: LinkKind,
    private val firstFrameMs: Long? = null,
    private val maxAttempts: Int? = null,
    private val onStateCallback: ((LinkState) -> Unit)? = null,
) {
    private val rtcDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + rtcDispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    val videoTrack: StateFlow<VideoTrack?> = _videoTrack.asStateFlow()

    private val gen = AtomicInteger(0)
    private var fails = 0
    private var lastFrames = -1L
    private var lastFrameAt = 0L
    private var startedAt = 0L

    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null
    private var answerSdp: String? = null
    private var offerSent = false
    private val candidateOutbox = mutableListOf<String>()
    private val candidateInbox = mutableListOf<String>()
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var statsJob: Job? = null
    private var retryJob: Job? = null
    private var disconnectGraceJob: Job? = null

    fun start() {
        val currentGen = gen.incrementAndGet()
        scope.launch {
            if (currentGen != gen.get()) return@launch
            fails = 0
            connect(currentGen)
        }
    }

    fun restart() {
        val currentGen = gen.incrementAndGet()
        scope.launch {
            if (currentGen != gen.get()) return@launch
            fails = 0
            connect(currentGen)
        }
    }

    fun stop() {
        gen.incrementAndGet()
        _videoTrack.value = null
        updateState { it.copy(phase = LinkPhase.STOPPED, fps = 0, retryIn = null) }
        scope.launch {
            teardown()
        }
    }

    private fun updateState(transform: (LinkState) -> LinkState) {
        val next = transform(_state.value)
        _state.value = next
        onStateCallback?.invoke(next)
    }

    @Synchronized
    private fun teardown() {
        statsJob?.cancel()
        statsJob = null
        retryJob?.cancel()
        retryJob = null
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null

        val ws = webSocket
        webSocket = null
        runCatching { ws?.close(1000, "closed") }

        offerSent = false
        answerSdp = null
        candidateOutbox.clear()
        candidateInbox.clear()

        _videoTrack.value = null

        val pc = peerConnection
        peerConnection = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }

        val track = localAudioTrack
        localAudioTrack = null
        runCatching {
            track?.setEnabled(false)
            track?.dispose()
        }

        val source = localAudioSource
        localAudioSource = null
        runCatching { source?.dispose() }
    }

    private fun scheduleRetry(reason: String, forGen: Int) {
        scope.launch {
            if (forGen != gen.get()) return@launch
            teardown()
            fails++
            if (maxAttempts != null && fails >= maxAttempts) {
                updateState { it.copy(phase = LinkPhase.ERROR, error = reason, fps = 0, retryIn = null) }
                return@launch
            }
            val backoffMs = BACKOFF_MS[minOf(fails - 1, BACKOFF_MS.lastIndex)]
            val nextGen = gen.incrementAndGet()
            updateState {
                it.copy(
                    phase = LinkPhase.RETRYING,
                    error = reason,
                    fps = 0,
                    retryIn = ceil(backoffMs / 1000.0).toInt(),
                )
            }
            retryJob = scope.launch {
                delay(backoffMs)
                if (nextGen == gen.get()) {
                    connect(nextGen)
                }
            }
        }
    }

    private fun connect(currentGen: Int) {
        if (currentGen != gen.get()) return
        teardown()
        if (currentGen != gen.get()) return
        lastFrames = -1L
        lastFrameAt = 0L
        startedAt = System.currentTimeMillis()
        updateState {
            it.copy(
                phase = LinkPhase.CONNECTING,
                fps = 0,
                retryIn = null,
                attempt = it.attempt + 1,
                bufferMs = null,
                rttMs = null,
            )
        }

        val factory = try {
            WebRtcRuntime.ensureFactory(context)
        } catch (t: Throwable) {
            Log.e(TAG, "PeerConnectionFactory init failed", t)
            updateState { it.copy(phase = LinkPhase.ERROR, error = t.message ?: "WebRTC unavailable", retryIn = null) }
            return
        }
        if (currentGen != gen.get()) return

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dc: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (currentGen != gen.get()) return
                val sdp = candidate?.sdp ?: return
                if (sdp.isBlank()) return
                synchronized(this@WebRtcCamLink) {
                    if (offerSent) {
                        sendWsMessage("webrtc/candidate", sdp)
                    } else {
                        candidateOutbox.add(sdp)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (currentGen != gen.get() || kind != LinkKind.VIDEO) return
                val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                track.setEnabled(true)
                _videoTrack.value = track
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                if (currentGen != gen.get() || kind != LinkKind.VIDEO) return
                val track = receiver?.track() as? VideoTrack ?: return
                track.setEnabled(true)
                if (_videoTrack.value == null) {
                    _videoTrack.value = track
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (currentGen != gen.get() || newState == null) return
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = null
                        // Signaling is done; go2rtc keeps the peer connection alive.
                        val ws = webSocket
                        webSocket = null
                        runCatching { ws?.close(1000, "connected") }
                        fails = 0
                        if (kind != LinkKind.VIDEO) {
                            updateState { it.copy(phase = LinkPhase.LIVE, error = null, everPlayed = true) }
                        }
                    }

                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED,
                    -> scheduleRetry("Connection lost", currentGen)

                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        if (disconnectGraceJob == null) {
                            disconnectGraceJob = scope.launch {
                                delay(DISCONNECT_GRACE_MS)
                                if (currentGen == gen.get() &&
                                    peerConnection?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED
                                ) {
                                    scheduleRetry("Connection lost", currentGen)
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        })

        if (pc == null) {
            scheduleRetry("Could not create PeerConnection", currentGen)
            return
        }
        peerConnection = pc

        try {
            when (kind) {
                LinkKind.VIDEO -> {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                    )
                }

                LinkKind.AUDIO -> {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                    )
                }

                LinkKind.TALK -> {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    }
                    val source = factory.createAudioSource(constraints)
                    val track = factory.createAudioTrack("pc-talk-${currentGen}", source)
                    track.setEnabled(true)
                    localAudioSource = source
                    localAudioTrack = track
                    pc.addTransceiver(
                        track,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
                    )
                }
            }
        } catch (t: Throwable) {
            if (currentGen != gen.get()) return
            teardown()
            updateState {
                it.copy(
                    phase = LinkPhase.ERROR,
                    error = if (kind == LinkKind.TALK) "Microphone unavailable" else (t.message ?: "Track error"),
                    retryIn = null,
                )
            }
            return
        }

        openSignalingWebSocket(pc, currentGen)
        startStatsPolling(pc, currentGen)
    }

    private fun openSignalingWebSocket(pc: PeerConnection, currentGen: Int) {
        val url = WebRtcRuntime.wsUrl(baseUrl, src)
        val req = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (currentGen != gen.get()) {
                    ws.close(1000, "stale")
                    return
                }
                pc.createOffer(
                    simpleSdpObserver(
                        onCreated = { sdp ->
                            if (currentGen != gen.get()) return@simpleSdpObserver
                            pc.setLocalDescription(
                                simpleSdpObserver(
                                    onSet = {
                                        if (currentGen != gen.get()) return@simpleSdpObserver
                                        synchronized(this@WebRtcCamLink) {
                                            sendWsMessage("webrtc/offer", sdp.description)
                                            offerSent = true
                                            val queued = candidateOutbox.toList()
                                            candidateOutbox.clear()
                                            queued.forEach { sendWsMessage("webrtc/candidate", it) }
                                        }
                                    },
                                    onFailed = { err -> scheduleRetry("offer: $err", currentGen) },
                                ),
                                sdp,
                            )
                        },
                        onFailed = { err -> scheduleRetry("offer: $err", currentGen) },
                    ),
                    MediaConstraints(),
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (currentGen != gen.get()) return
                val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
                val value = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (type) {
                    "webrtc/answer" -> {
                        val remote = SessionDescription(SessionDescription.Type.ANSWER, value)
                        pc.setRemoteDescription(
                            simpleSdpObserver(
                                onSet = {
                                    if (currentGen != gen.get()) return@simpleSdpObserver
                                    synchronized(this@WebRtcCamLink) {
                                        answerSdp = value
                                        val queued = candidateInbox.toList()
                                        candidateInbox.clear()
                                        queued.forEach { addRemoteCandidate(pc, it) }
                                    }
                                },
                                onFailed = { err -> scheduleRetry("answer: $err", currentGen) },
                            ),
                            remote,
                        )
                    }

                    "webrtc/candidate" -> {
                        if (value.isBlank()) return
                        synchronized(this@WebRtcCamLink) {
                            if (answerSdp != null) {
                                addRemoteCandidate(pc, value)
                            } else {
                                candidateInbox.add(value)
                            }
                        }
                    }

                    "error" -> {
                        scheduleRetry(value.ifBlank { "go2rtc error" }, currentGen)
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (currentGen != gen.get()) return
                if (answerSdp == null) {
                    scheduleRetry(t.message ?: "WebSocket error", currentGen)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (currentGen != gen.get()) return
                if (answerSdp == null) {
                    scheduleRetry("WebSocket closed before go2rtc answered", currentGen)
                }
            }
        })
    }

    private fun sendWsMessage(type: String, value: String) {
        val payload = buildJsonObject {
            put("type", type)
            put("value", value)
        }.toString()
        webSocket?.send(payload)
    }

    private fun addRemoteCandidate(pc: PeerConnection, candidate: String) {
        runCatching {
            pc.addIceCandidate(IceCandidate("0", 0, candidate))
        }
    }

    private fun startStatsPolling(pc: PeerConnection, currentGen: Int) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && currentGen == gen.get()) {
                delay(1000L)
                if (currentGen != gen.get() || peerConnection !== pc) break
                runCatching {
                    pc.getStats { report ->
                        if (currentGen != gen.get() || peerConnection !== pc) return@getStats
                        processStats(report.statsMap.values, currentGen)
                    }
                }
            }
        }
    }

    private fun processStats(stats: Collection<org.webrtc.RTCStats>, currentGen: Int) {
        var inbound: Map<String, Any>? = null
        var outbound: Map<String, Any>? = null
        var pair: Map<String, Any>? = null
        val codecs = mutableMapOf<String, String>()

        val targetKind = if (kind == LinkKind.VIDEO) "video" else "audio"

        for (stat in stats) {
            val m = stat.members
            when (stat.type) {
                "inbound-rtp" -> {
                    val mediaKind = (m["kind"] ?: m["mediaType"]) as? String
                    if (mediaKind == targetKind) inbound = m
                }

                "outbound-rtp" -> outbound = m

                "candidate-pair" -> {
                    val state = m["state"] as? String
                    val nominated = (m["nominated"] as? Boolean) == true || (m["selected"] as? Boolean) == true
                    if (nominated && state == "succeeded") pair = m
                }

                "codec" -> {
                    val mime = m["mimeType"] as? String
                    if (mime != null) codecs[stat.id] = mime
                }
            }
        }

        val now = System.currentTimeMillis()
        val rttSec = (pair?.get("currentRoundTripTime") as? Number)?.toDouble()
        val rttMs = rttSec?.let { (it * 1000.0).roundToInt() } ?: _state.value.rttMs

        if (kind != LinkKind.VIDEO) {
            val codecId = (inbound?.get("codecId") ?: outbound?.get("codecId")) as? String
            val codec = codecId?.let { codecs[it]?.substringAfter('/', "") }?.takeIf { it.isNotEmpty() }
            updateState { it.copy(rttMs = rttMs, codec = codec ?: it.codec) }
            return
        }

        val frames = (inbound?.get("framesDecoded") as? Number)?.toLong() ?: 0L
        val fps = if (lastFrames < 0L || frames < lastFrames) 0 else (frames - lastFrames).toInt()
        lastFrames = frames
        if (fps > 0) lastFrameAt = now

        val codecId = inbound?.get("codecId") as? String
        val codecFromStats = codecId?.let { codecs[it]?.substringAfter('/', "")?.uppercase() }
        val codec = codecFromStats ?: WebRtcRuntime.answerVideoCodec(answerSdp) ?: _state.value.codec
        val decoder = (inbound?.get("decoderImplementation") as? String) ?: _state.value.decoder

        val fw = (inbound?.get("frameWidth") as? Number)?.toInt() ?: 0
        val fh = (inbound?.get("frameHeight") as? Number)?.toInt() ?: 0
        val size = if (fw > 0 && fh > 0) "${fw}×${fh}" else _state.value.size

        val jbCount = (inbound?.get("jitterBufferEmittedCount") as? Number)?.toDouble() ?: 0.0
        val jbDelay = (inbound?.get("jitterBufferDelay") as? Number)?.toDouble() ?: 0.0
        val bufferMs = if (jbCount > 0.0) ((1000.0 * jbDelay) / jbCount).roundToInt() else _state.value.bufferMs

        when {
            fps > 0 -> {
                updateState {
                    it.copy(
                        phase = LinkPhase.LIVE,
                        fps = fps,
                        codec = codec,
                        decoder = decoder,
                        size = size,
                        bufferMs = bufferMs,
                        rttMs = rttMs,
                        everPlayed = true,
                        error = null,
                    )
                }
            }

            lastFrameAt > 0L -> {
                if (now - lastFrameAt > STALL_MS) {
                    scheduleRetry("No frames", currentGen)
                } else {
                    updateState {
                        it.copy(
                            phase = LinkPhase.STALLED,
                            fps = 0,
                            codec = codec,
                            decoder = decoder,
                            size = size,
                            bufferMs = bufferMs,
                            rttMs = rttMs,
                        )
                    }
                }
            }

            now - startedAt > (firstFrameMs ?: FIRST_FRAME_MS) -> {
                if (firstFrameMs != null) {
                    scope.launch {
                        if (currentGen != gen.get()) return@launch
                        teardown()
                        updateState {
                            it.copy(
                                phase = LinkPhase.ERROR,
                                fps = 0,
                                codec = codec,
                                decoder = decoder,
                                size = size,
                                bufferMs = bufferMs,
                                rttMs = rttMs,
                                error = "No picture",
                                retryIn = null,
                            )
                        }
                    }
                } else {
                    scheduleRetry("No picture", currentGen)
                }
            }

            else -> {
                updateState {
                    it.copy(
                        fps = 0,
                        codec = codec,
                        decoder = decoder,
                        size = size,
                        bufferMs = bufferMs,
                        rttMs = rttMs,
                    )
                }
            }
        }
    }

    private fun simpleSdpObserver(
        onCreated: ((SessionDescription) -> Unit)? = null,
        onSet: (() -> Unit)? = null,
        onFailed: (String) -> Unit,
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (sdp != null) onCreated?.invoke(sdp) else onFailed("null SDP")
        }

        override fun onSetSuccess() {
            onSet?.invoke()
        }

        override fun onCreateFailure(error: String?) {
            onFailed(error ?: "create failed")
        }

        override fun onSetFailure(error: String?) {
            onFailed(error ?: "set failed")
        }
    }

    companion object {
        private const val TAG = "WebRtcCamLink"
        private val BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L)
        private const val STALL_MS = 6_000L
        private const val FIRST_FRAME_MS = 15_000L
        private const val DISCONNECT_GRACE_MS = 2_500L
    }
}
