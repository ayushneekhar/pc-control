package com.neekhar.pccontrol.feature.cams

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neekhar.pccontrol.MainActivity
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.LocalPcType
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.tabular
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcScaffold
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.cams.CamsMediaTools.findActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

private data class CamsToast(
    val message: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * Native Cat Cams screen (`feature/cams/CamsScreen.kt`).
 *
 * - Top bar with Back navigation, Cat Cams master power toggle, and overflow menu.
 * - Segmented tabs: **Live**, **Replay**, **Detections**, **Music**.
 * - Lifecycle-aware pure-Kotlin WebRTC links for Live video (`cat`, `cat2`, `cat3`/`cat3hd`),
 *   cam mic (`cat3hd`), laptop mic Listen (`mic`), and hold/latch Talk backchannel (`talk`).
 * - Native Media3 ExoPlayer HLS Replay tab, YOLO11s Detections gallery, and `cam-music` Music tab.
 */
@Composable
fun CamsScreen(
    apiClient: PcApiClient,
    prefs: AppPrefs,
    cache: LocalCache,
    initialTab: String? = null,
    sharedYoutubeUrl: String? = null,
    initialReplayCam: String? = null,
    initialReplayTsMs: Long? = null,
    isInPictureInPictureMode: Boolean = false,
    onBack: () -> Unit = {},
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val repository = remember(apiClient, cache) { CamsRepository(apiClient, cache) }
    val hardwareCaps = remember(context) { WebRtcRuntime.inspectHardware(context) }

    LaunchedEffect(Unit) {
        WebRtcRuntime.prewarmAsync(context)
    }

    // Toast state
    var toast by remember { mutableStateOf<CamsToast?>(null) }
    var toastJob by remember { mutableStateOf<Job?>(null) }
    fun showToast(message: String, durationMs: Long = 2800L, actionLabel: String? = null, action: (() -> Unit)? = null) {
        toast = CamsToast(message, actionLabel, action)
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(durationMs)
            toast = null
        }
    }

    // Active tab & deep-link / share targets
    var activeTab by remember { mutableStateOf(parseTab(initialTab)) }
    var pendingSharedUrl by remember { mutableStateOf(sharedYoutubeUrl) }
    var replayTarget by remember {
        mutableStateOf(ReplayMath.parseSightingTarget(initialReplayCam, initialReplayTsMs))
    }

    LaunchedEffect(initialTab) {
        if (!initialTab.isNullOrBlank()) activeTab = parseTab(initialTab)
    }
    LaunchedEffect(sharedYoutubeUrl) {
        if (!sharedYoutubeUrl.isNullOrBlank()) {
            pendingSharedUrl = sharedYoutubeUrl
            activeTab = CamsTab.MUSIC
        }
    }
    LaunchedEffect(initialReplayCam, initialReplayTsMs) {
        val parsed = ReplayMath.parseSightingTarget(initialReplayCam, initialReplayTsMs)
        if (parsed != null) {
            replayTarget = parsed
            activeTab = CamsTab.REPLAY
        }
    }

    // Foreground lifecycle awareness
    var isForeground by remember { mutableStateOf(true) }

    // Cat cams master power switch state (backed by LocalCache for instant paint)
    var catCamState by remember { mutableStateOf(repository.cachedCatCamState()) }
    var catCamBusy by remember { mutableStateOf(false) }
    var catCamError by remember { mutableStateOf<String?>(null) }
    val catCamEnabled = catCamState?.enabled ?: true
    val stackActive = catCamEnabled && !catCamBusy

    // Live view controls
    var cat3Hd by remember { mutableStateOf(hardwareCaps.liveHdSupported) }
    var heroCam by remember {
        mutableStateOf(CamId.fromId(prefs.current.value.lastSelectedCam) ?: CamId.CAT)
    }
    var stackedLayout by remember { mutableStateOf(false) }
    var fullscreenCam by remember { mutableStateOf<CamId?>(null) }
    var replayFullscreen by remember { mutableStateOf(false) }
    var ptzOpen by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var safeMode by remember { mutableStateOf(WebRtcRuntime.isSafeMode(context)) }
    var camMicOn by remember { mutableStateOf(false) }
    var listenOn by remember { mutableStateOf(false) }
    var talkMode by remember { mutableStateOf(TalkMode.OFF) }
    var talkStartedAtMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var replayPlaying by remember { mutableStateOf(false) }

    // PTZ state & saved presets
    var ptzMoving by remember { mutableStateOf(false) }
    var ptzPresets by remember { mutableStateOf(repository.loadPtzPresets()) }
    var activePresetId by remember { mutableStateOf<String?>(null) }
    var ptzPresetJob by remember { mutableStateOf<Job?>(null) }

    fun stopPtz() {
        ptzPresetJob?.cancel()
        ptzPresetJob = null
        activePresetId = null
        if (!ptzMoving) return
        ptzMoving = false
        scope.launch { repository.ptzStop() }
    }

    fun startPtz(dir: String) {
        ptzPresetJob?.cancel()
        activePresetId = null
        ptzMoving = true
        scope.launch { repository.ptzMove(dir) }
    }

    fun runPtzPreset(preset: PtzPreset) {
        ptzPresetJob?.cancel()
        activePresetId = preset.id
        ptzPresetJob = scope.launch {
            val serverResult = preset.serverPresetId?.let { repository.ptzInvokePreset(it) }
            if (serverResult !is ApiResult.Success) {
                for (step in preset.directionSequence) {
                    ptzMoving = true
                    repository.ptzMove(step.dir)
                    delay(step.durationMs)
                    repository.ptzStop()
                    ptzMoving = false
                    delay(120L)
                }
            }
            activePresetId = null
            showToast("Moved to ${preset.label}", 1800L)
        }
    }

    // Stop active audio / PTZ / fullscreen on background or stack turn-off
    fun resetInteractiveMedia() {
        camMicOn = false
        listenOn = false
        talkMode = TalkMode.OFF
        ptzOpen = false
        fullscreenCam = null
        replayFullscreen = false
        stopPtz()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                -> isForeground = true

                Lifecycle.Event.ON_PAUSE -> {
                    // Stop interactive mic/PTZ controls immediately, but keep isForeground true so
                    // Live WebRTC and Replay keep playing smoothly when transitioning into PiP.
                    talkMode = TalkMode.OFF
                    ptzOpen = false
                    stopPtz()
                }

                Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    resetInteractiveMedia()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopPtz()
        }
    }

    // Refresh Cat Cams master state on foreground entry
    LaunchedEffect(isForeground) {
        if (!isForeground) return@LaunchedEffect
        if (catCamBusy) return@LaunchedEffect
        when (val res = repository.getCatCam()) {
            is ApiResult.Success -> {
                catCamState = res.data
                catCamError = null
            }
            is ApiResult.Error -> {
                catCamError = res.message
            }
        }
    }

    fun toggleMasterCatCams(nextEnabled: Boolean) {
        if (catCamBusy) return
        if (!nextEnabled) resetInteractiveMedia()
        val before = catCamState
        catCamState = CatCamState(enabled = nextEnabled, units = before?.units.orEmpty())
        catCamBusy = true
        catCamError = null
        scope.launch {
            when (val res = repository.setCatCam(nextEnabled)) {
                is ApiResult.Success -> {
                    catCamState = res.data
                    catCamError = null
                }
                is ApiResult.Error -> {
                    catCamState = before
                    catCamError = res.message
                }
            }
            catCamBusy = false
        }
    }

    // Screen keep-awake while watching
    DisposableEffect(isForeground, stackActive, activity) {
        if (activity != null && isForeground && stackActive) {
            CamsMediaTools.keepAwake(activity, true)
        }
        onDispose {
            if (activity != null) CamsMediaTools.keepAwake(activity, false)
        }
    }

    // Fullscreen immersive landscape + hardware back (Live or Replay)
    val inReplayFullscreen = stackActive && activeTab == CamsTab.REPLAY && replayFullscreen
    val isAnyFullscreen = stackActive && (fullscreenCam != null || inReplayFullscreen)
    DisposableEffect(isAnyFullscreen, activity) {
        if (activity != null && isAnyFullscreen) {
            CamsMediaTools.setFullscreen(activity, true)
        }
        onDispose {
            if (activity != null) CamsMediaTools.setFullscreen(activity, false)
        }
    }
    BackHandler(enabled = isAnyFullscreen) {
        fullscreenCam = null
        replayFullscreen = false
    }

    // WebRTC live connections (video runs only on Live tab; audio runs across tabs while active)
    val runLiveVideo = isForeground && stackActive && activeTab == CamsTab.LIVE
    val runAudioLinks = isForeground && stackActive

    // Crash guard marker while live video opens (cleared after 15 s or on stop)
    LaunchedEffect(runLiveVideo) {
        if (!runLiveVideo) {
            WebRtcRuntime.markOpening(context, false)
            return@LaunchedEffect
        }
        WebRtcRuntime.markOpening(context, true)
        try {
            delay(15_000L)
        } finally {
            WebRtcRuntime.markOpening(context, false)
        }
    }

    val catFeed = rememberLiveCamFeed(
        src = if (runLiveVideo) CamId.CAT.id else null,
        camId = CamId.CAT,
        apiClient = apiClient,
    )
    val cat2Feed = rememberLiveCamFeed(
        src = if (runLiveVideo) CamId.CAT2.id else null,
        camId = CamId.CAT2,
        apiClient = apiClient,
    )
    val cat3Feed = rememberLiveCamFeed(
        src = if (runLiveVideo) (if (cat3Hd) CamsConfig.HD_STREAM else CamId.CAT3.id) else null,
        camId = CamId.CAT3,
        apiClient = apiClient,
        firstFrameMs = if (cat3Hd) CamsConfig.HD_WAIT_MS else null,
        maxAttempts = if (cat3Hd) 2 else null,
        onStateChange = { s ->
            if (cat3Hd && s.phase == LinkPhase.ERROR) {
                cat3Hd = false
                showToast("HD didn't start, switched back to standard stream.", 3800L)
            }
        },
    )

    // Audio links
    rememberAudioWebRtcLink(
        src = if (runAudioLinks && camMicOn) CamsConfig.CAM_MIC_STREAM else null,
        kind = LinkKind.AUDIO,
        apiClient = apiClient,
        onStateChange = { s ->
            if (s.phase == LinkPhase.RETRYING && s.attempt > 2) {
                camMicOn = false
                showToast("Cam's mic didn't connect (${s.error ?: "error"}).", 3500L)
            }
        },
    )
    val earState = rememberAudioWebRtcLink(
        src = if (runAudioLinks && listenOn) CamsConfig.LAPTOP_MIC_STREAM else null,
        kind = LinkKind.AUDIO,
        apiClient = apiClient,
    )
    val talkState = rememberAudioWebRtcLink(
        src = if (runAudioLinks && talkMode != TalkMode.OFF) CamsConfig.TALK_STREAM else null,
        kind = LinkKind.TALK,
        apiClient = apiClient,
        maxAttempts = 2,
        onStateChange = { s ->
            if (s.phase == LinkPhase.ERROR) {
                talkMode = TalkMode.OFF
                showToast("Talk dropped: ${s.error ?: "connection failed"}. Try again.", 3500L)
            }
        },
    )

    // Talk elapsed clock
    LaunchedEffect(talkMode) {
        if (talkMode == TalkMode.OFF) return@LaunchedEffect
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    // Mic runtime permission launcher for Talk
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showToast("Microphone allowed. Hold to talk.", 3000L)
        } else {
            showToast("Talk needs microphone permission in Android settings.", 4200L)
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val feeds = remember(catFeed, cat2Feed, cat3Feed) {
        mapOf(
            CamId.CAT to catFeed,
            CamId.CAT2 to cat2Feed,
            CamId.CAT3 to cat3Feed,
        )
    }

    // Automatic Picture-in-Picture whenever Live or Replay video is actively playing
    val activePipCam = fullscreenCam ?: heroCam
    val livePlaying = stackActive && activeTab == CamsTab.LIVE &&
        feeds.values.any { it.videoTrack != null || it.state.fps > 0 || it.state.phase == LinkPhase.LIVE }
    val replayActive = stackActive && activeTab == CamsTab.REPLAY && replayPlaying
    val shouldAutoPip = isForeground && (livePlaying || replayActive)
    val pipDimensions = if (activeTab == CamsTab.LIVE) {
        parseVideoDimensions(feeds[activePipCam]?.state?.size)
    } else {
        16 to 9
    }

    DisposableEffect(activity, shouldAutoPip, pipDimensions) {
        val mainAct = activity as? MainActivity
        if (mainAct != null) {
            mainAct.updateAutoPip(
                enabled = shouldAutoPip,
                aspectWidth = pipDimensions.first,
                aspectHeight = pipDimensions.second,
            )
        } else if (activity != null) {
            CamsMediaTools.setAutoPictureInPicture(
                activity = activity,
                enabled = shouldAutoPip,
                aspectWidth = pipDimensions.first,
                aspectHeight = pipDimensions.second,
            )
        }
        onDispose {
            if (mainAct != null) {
                mainAct.updateAutoPip(enabled = false)
            } else if (activity != null) {
                CamsMediaTools.setAutoPictureInPicture(activity = activity, enabled = false)
            }
        }
    }

    fun toggleCat3Hd() {
        if (!cat3Hd && !hardwareCaps.liveHdSupported) {
            showToast("HD needs hardware H.265 over WebRTC. ${hardwareCaps.hdReason.orEmpty()}", 4500L)
            return
        }
        cat3Hd = !cat3Hd
        showToast(if (cat3Hd) "Switching to HD (3MP)" else "Back to standard stream", 1800L)
    }

    // ---- Automatic Picture-in-Picture pure full-bleed video mode
    if (isInPictureInPictureMode && stackActive) {
        if (activeTab == CamsTab.REPLAY) {
            ReplayTab(
                visible = isForeground,
                hevcOk = hardwareCaps.replayHevcSupported,
                dataSource = repository,
                okHttpClient = apiClient.okHttpClient,
                target = replayTarget,
                fullscreen = true,
                isInPip = true,
                onPlayingChange = { replayPlaying = it },
                onToggleFullscreen = {},
                onTargetConsumed = { replayTarget = null },
                onToast = { _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
            return
        } else if (activeTab == CamsTab.LIVE) {
            val pipFeed = feeds[activePipCam] ?: LiveCamFeed(activePipCam, LinkState(), null)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.media),
                contentAlignment = Alignment.Center,
            ) {
                if (pipFeed.videoTrack != null) {
                    WebRtcLivePlayer(
                        videoTrack = pipFeed.videoTrack,
                        zoomable = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.onMediaDim,
                    )
                }
            }
            return
        }
    }

    // ---- Fullscreen landscape mode
    val fsCam = fullscreenCam
    if (fsCam != null && stackActive) {
        val fsFeed = feeds[fsCam] ?: LiveCamFeed(fsCam, LinkState(), null)
        Box(modifier = Modifier.fillMaxSize()) {
            LiveFullscreenOverlay(
                feed = fsFeed,
                snapshotBaseUrl = apiClient.baseUrl,
                cat3Hd = cat3Hd,
                camMicOn = camMicOn,
                ptzOpen = ptzOpen,
                onExitFullscreen = { fullscreenCam = null },
                onToggleCamMic = { camMicOn = !camMicOn },
                onToggleCat3Hd = ::toggleCat3Hd,
                onTogglePtz = { ptzOpen = !ptzOpen },
                onPtzStart = ::startPtz,
                onPtzStop = ::stopPtz,
            )
            CamsToastBanner(
                toast = toast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
        return
    }

    val showAudioBar = !inReplayFullscreen && stackActive && (activeTab == CamsTab.LIVE || listenOn || talkMode != TalkMode.OFF)
    val units = catCamState?.units?.values.orEmpty()
    val runningUnits = units.count { it == "active" }
    val masterStatusText = when {
        catCamBusy -> if (catCamEnabled) "Turning on…" else "Turning off…"
        !catCamEnabled -> "Off"
        units.isNotEmpty() && runningUnits == 0 -> "0/${units.size} running"
        else -> null
    }
    val masterDotTone = when {
        catCamBusy -> PcTone.Warn
        catCamEnabled -> if (units.isNotEmpty() && runningUnits == 0) PcTone.Warn else PcTone.Ok
        else -> PcTone.Faint
    }

    PcScaffold(
        title = if (inReplayFullscreen) null else "Cat cams",
        statusText = masterStatusText,
        statusTone = masterDotTone,
        showStatusDot = !masterStatusText.isNullOrBlank(),
        onBack = if (inReplayFullscreen) null else onBack,
        applyStatusBarPadding = !inReplayFullscreen,
        applyNavigationBarPadding = !inReplayFullscreen,
        showStatusBarScrim = !inReplayFullscreen,
        actions = {
            if (!inReplayFullscreen) {
                // Toggle between Hero + thumbnail strip (default) and stacked grid
                if (stackActive && activeTab == CamsTab.LIVE) {
                    IconButton(
                        onClick = { stackedLayout = !stackedLayout },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (stackedLayout) Icons.Rounded.Dashboard else Icons.Rounded.ViewAgenda,
                            contentDescription = if (stackedLayout) "Switch to hero view" else "Switch to stacked view",
                            tint = if (stackedLayout) colors.accent else colors.textDim,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "More options",
                            tint = colors.text,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = colors.surfaceRaised,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (catCamEnabled) "Turn cams off" else "Turn cams on",
                                    color = if (catCamBusy) colors.textFaint else colors.text,
                                )
                            },
                            enabled = !catCamBusy,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = if (catCamEnabled) colors.bad else colors.accent,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (!catCamBusy) toggleMasterCatCams(!catCamEnabled)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open web page", color = colors.text) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = colors.textDim,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                val hash = when (activeTab) {
                                    CamsTab.REPLAY -> "#replay"
                                    CamsTab.MUSIC -> "#music"
                                    else -> ""
                                }
                                val uri = Uri.parse(apiClient.baseUrl.trimEnd('/') + CamsConfig.WEB_PATH + hash)
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (safeMode) "Leave safe mode" else "Safe mode (WebRTC defaults)",
                                    color = colors.text,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = colors.textDim,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                val next = !safeMode
                                WebRtcRuntime.setSafeMode(context, next)
                                safeMode = next
                                showToast(
                                    if (next) "Safe mode on from next app start." else "Safe mode off from next app start.",
                                    3500L,
                                )
                            },
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (showAudioBar) {
                val talkSeconds = if (talkStartedAtMs > 0L) max(0L, (nowMs - talkStartedAtMs) / 1000L) else 0L
                ListenTalkBar(
                    listenOn = listenOn,
                    earState = earState,
                    talkMode = talkMode,
                    talkState = talkState,
                    talkSeconds = talkSeconds,
                    onToggleListen = { listenOn = !listenOn },
                    onTalkPressDown = {
                        if (!hasMicPermission()) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            talkStartedAtMs = System.currentTimeMillis()
                            nowMs = talkStartedAtMs
                            talkMode = TalkMode.HELD
                            true
                        }
                    },
                    onTalkRelease = {
                        talkMode = TalkMode.OFF
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            // Segmented tab bar (Live / Replay / Detections / Music)
            if (!inReplayFullscreen && stackActive) {
                PcSegmentedControl(
                    options = listOf(
                        PcSegmentOption(CamsTab.LIVE, "Live"),
                        PcSegmentOption(CamsTab.REPLAY, "Replay"),
                        PcSegmentOption(CamsTab.DETECTIONS, "Detections"),
                        PcSegmentOption(CamsTab.MUSIC, "Music"),
                    ),
                    selected = activeTab,
                    onSelect = {
                        if (it != CamsTab.REPLAY) replayFullscreen = false
                        activeTab = it
                    },
                    modifier = Modifier.padding(
                        start = PcDimens.ScreenPad,
                        end = PcDimens.ScreenPad,
                        bottom = 8.dp,
                    ),
                )
            }

            // Main body with seamless top & bottom vertical fades (no hard hairlines)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    !stackActive -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = PcDimens.ScreenPad,
                                    end = PcDimens.ScreenPad,
                                    top = PcDimens.Space4,
                                    bottom = PcDimens.Space6,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
                        ) {
                            MasterPowerOffCard(
                                enabled = catCamEnabled,
                                busy = catCamBusy,
                                statusText = masterStatusText ?: "Off",
                                dotTone = masterDotTone,
                                error = catCamError,
                                onToggle = { toggleMasterCatCams(it) },
                            )
                        }
                    }

                    activeTab == CamsTab.REPLAY -> {
                        ReplayTab(
                            visible = isForeground,
                            hevcOk = hardwareCaps.replayHevcSupported,
                            dataSource = repository,
                            okHttpClient = apiClient.okHttpClient,
                            target = replayTarget,
                            fullscreen = replayFullscreen,
                            isInPip = false,
                            bottomInset = 24.dp,
                            onPlayingChange = { replayPlaying = it },
                            onToggleFullscreen = { replayFullscreen = !replayFullscreen },
                            onTargetConsumed = { replayTarget = null },
                            onToast = { msg, actLabel, act ->
                                showToast(msg, if (act != null) 4500L else 2800L, actLabel, act)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    activeTab == CamsTab.DETECTIONS -> {
                        DetectionsTab(
                            visible = isForeground,
                            repository = repository,
                            bottomInset = 20.dp,
                            onJumpToReplay = { cam, tsMs ->
                                val parsed = ReplayMath.parseSightingTarget(cam, tsMs)
                                if (parsed != null) {
                                    replayTarget = parsed
                                    activeTab = CamsTab.REPLAY
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    activeTab == CamsTab.LIVE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = PcDimens.Space3,
                                    bottom = 20.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
                        ) {
                            if (catCamError != null) {
                                Box(modifier = Modifier.padding(horizontal = PcDimens.ScreenPad)) {
                                    PcErrorBanner(
                                        title = "Cat cams",
                                        message = catCamError,
                                        tone = PcTone.Bad,
                                    )
                                }
                            }

                            LiveTab(
                                feeds = feeds,
                                snapshotBaseUrl = apiClient.baseUrl,
                                heroCam = heroCam,
                                stackedLayout = stackedLayout,
                                cat3Hd = cat3Hd,
                                hardwareCaps = hardwareCaps,
                                camMicOn = camMicOn,
                                ptzOpen = ptzOpen,
                                ptzPresets = ptzPresets,
                                activePresetId = activePresetId,
                                onSelectHero = { cam ->
                                    heroCam = cam
                                    stackedLayout = false
                                    scope.launch { prefs.setLastSelectedCam(cam.id) }
                                },
                                onToggleCat3Hd = ::toggleCat3Hd,
                                onToggleCamMic = { camMicOn = !camMicOn },
                                onTogglePtzOpen = { ptzOpen = !ptzOpen },
                                onPtzStart = ::startPtz,
                                onPtzStop = ::stopPtz,
                                onRunPtzPreset = ::runPtzPreset,
                                onEnterFullscreen = { fullscreenCam = it },
                            )
                        }
                    }

                    activeTab == CamsTab.MUSIC -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = PcDimens.ScreenPad,
                                    end = PcDimens.ScreenPad,
                                    top = PcDimens.Space3,
                                    bottom = 24.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PcDimens.Space3),
                        ) {
                            if (catCamError != null) {
                                PcErrorBanner(
                                    title = "Cat cams",
                                    message = catCamError,
                                    tone = PcTone.Bad,
                                )
                            }

                            MusicTab(
                                visible = isForeground,
                                repository = repository,
                                sharedUrl = pendingSharedUrl,
                                onSharedConsumed = { pendingSharedUrl = null },
                                onToast = { showToast(it) },
                            )
                        }
                    }
                }

                // Seamless top vertical fade under the segmented tabs
                if (!inReplayFullscreen && stackActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(colors.bg, Color.Transparent),
                                ),
                            ),
                    )
                }

                // Seamless bottom vertical fade above the Listen / Hold-to-Talk bar
                if (showAudioBar) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, colors.bg),
                                ),
                            ),
                    )
                }

                // Floating toast banner
                CamsToastBanner(
                    toast = toast,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MasterPowerOffCard(
    enabled: Boolean,
    busy: Boolean,
    statusText: String,
    dotTone: PcTone,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            Icon(
                imageVector = if (enabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                contentDescription = null,
                tint = if (enabled && !busy) colors.accent else colors.textDim,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cat cams",
                    color = colors.text,
                    style = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(tone = dotTone, size = 7.dp)
                    Text(
                        text = statusText,
                        color = colors.textDim,
                        style = type.caption.tabular(),
                    )
                }
            }
            PcButton(
                label = if (enabled) "Turn off" else "Turn on",
                variant = if (enabled) PcButtonVariant.Subtle else PcButtonVariant.Primary,
                busy = busy,
                compact = true,
                onClick = { onToggle(!enabled) },
            )
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.hairline,
            modifier = Modifier.padding(vertical = PcDimens.Space3),
        )

        Text(
            text = if (busy) {
                if (enabled) {
                    "Starting cameras, recorder, detector, and music…"
                } else {
                    "Stopping cameras, recorder, detector, and music…"
                }
            } else {
                "Live cameras, recording, cat detector, and music are stopped. Turn on to reconnect."
            },
            color = colors.textDim,
            style = type.body,
        )

        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(PcDimens.Space3))
            PcErrorBanner(title = "Cat cams", message = error, tone = PcTone.Bad)
        }
    }
}

@Composable
private fun ListenTalkBar(
    listenOn: Boolean,
    earState: LinkState,
    talkMode: TalkMode,
    talkState: LinkState,
    talkSeconds: Long,
    onToggleListen: () -> Unit,
    onTalkPressDown: () -> Boolean,
    onTalkRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    val view = LocalView.current
    val currentOnTalkPressDown by rememberUpdatedState(onTalkPressDown)
    val currentOnTalkRelease by rememberUpdatedState(onTalkRelease)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = PcDimens.ScreenPad, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Compact Listen (room audio) toggle
        val listenBusy = listenOn && earState.phase != LinkPhase.LIVE && earState.phase != LinkPhase.ERROR
        val listenLive = listenOn && earState.phase == LinkPhase.LIVE
        val listenErr = listenOn && earState.phase == LinkPhase.ERROR

        val listenBg = when {
            listenLive -> colors.okSoft
            listenErr -> colors.warnSoft
            else -> colors.surface
        }
        val listenBorder = when {
            listenLive -> colors.ok.copy(alpha = 0.4f)
            listenErr -> colors.warn.copy(alpha = 0.4f)
            else -> colors.hairline
        }
        val listenFg = when {
            listenLive -> colors.ok
            listenErr -> colors.warn
            else -> colors.textDim
        }
        val listenLabel = when {
            !listenOn -> "Listen"
            listenLive -> "Listening"
            listenErr -> "Audio error"
            else -> "Connecting…"
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(listenBg)
                .border(0.5.dp, listenBorder, RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleListen)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (listenBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = listenFg,
                )
            } else {
                Icon(
                    imageVector = if (listenOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = listenFg,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = listenLabel,
                color = if (listenLive || listenErr) listenFg else colors.text,
                style = type.body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Compact Hold-to-Talk press-and-hold mic control
        val talkActive = talkMode != TalkMode.OFF
        val talkLive = talkActive && talkState.phase == LinkPhase.LIVE
        val talkBusy = talkActive && !talkLive
        val talkTimer = if (talkActive) {
            val m = talkSeconds / 60
            val s = talkSeconds % 60
            "$m:${s.toString().padStart(2, '0')}"
        } else {
            null
        }

        val talkBg = when {
            talkLive -> colors.bad
            talkBusy -> colors.surfaceRaised
            else -> colors.surface
        }
        val talkBorder = if (talkLive) Color.Transparent else colors.hairline
        val talkIconTint = when {
            talkLive -> colors.onMedia
            else -> colors.textDim
        }
        val talkTextColor = if (talkLive) colors.onMedia else colors.text
        val talkLabel = when {
            !talkActive -> "Hold to talk"
            talkLive -> if (talkTimer != null) "Talking · $talkTimer" else "Talking"
            else -> "Connecting…"
        }

        Row(
            modifier = Modifier
                .weight(1.2f)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(talkBg)
                .border(0.5.dp, talkBorder, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val started = currentOnTalkPressDown()
                        if (started) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        waitForUpOrCancellation()
                        if (started) {
                            currentOnTalkRelease()
                        }
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (talkBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = talkIconTint,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = talkIconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = talkLabel,
                color = talkTextColor,
                style = type.body.tabular().copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CamsToastBanner(
    toast: CamsToast?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val type = LocalPcType.current
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val current = toast ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .padding(horizontal = PcDimens.ScreenPad)
                .clip(RoundedCornerShape(PcDimens.RadiusSm))
                .background(colors.surfaceRaised)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(PcDimens.RadiusSm))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = current.message,
                color = colors.text,
                style = type.caption,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (current.actionLabel != null && current.action != null) {
                PcButton(
                    label = current.actionLabel,
                    variant = PcButtonVariant.Ghost,
                    compact = true,
                    onClick = current.action,
                )
            }
        }
    }
}

@Composable
private fun rememberLiveCamFeed(
    src: String?,
    camId: CamId,
    apiClient: PcApiClient,
    firstFrameMs: Long? = null,
    maxAttempts: Int? = null,
    onStateChange: ((LinkState) -> Unit)? = null,
): LiveCamFeed {
    val context = LocalContext.current
    val baseUrl = apiClient.baseUrl
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val latestCallback by rememberUpdatedState(onStateChange)
    val idleState = remember { kotlinx.coroutines.flow.MutableStateFlow(LinkState()) }
    val idleTrack = remember { kotlinx.coroutines.flow.MutableStateFlow<org.webrtc.VideoTrack?>(null) }

    val link = remember(src, baseUrl, firstFrameMs, maxAttempts) {
        if (src == null) {
            null
        } else {
            WebRtcCamLink(
                context = context,
                okHttpClient = apiClient.okHttpClient,
                baseUrl = baseUrl,
                src = src,
                kind = LinkKind.VIDEO,
                firstFrameMs = firstFrameMs,
                maxAttempts = maxAttempts,
                onStateCallback = { next ->
                    latestCallback?.let { cb -> mainHandler.post { cb(next) } }
                },
            )
        }
    }

    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }

    val linkState by (link?.state ?: idleState).collectAsState()
    val track by (link?.videoTrack ?: idleTrack).collectAsState()

    return LiveCamFeed(
        camId = camId,
        state = if (src != null) linkState else LinkState(),
        videoTrack = if (src != null) track else null,
    )
}

@Composable
private fun rememberAudioWebRtcLink(
    src: String?,
    kind: LinkKind,
    apiClient: PcApiClient,
    maxAttempts: Int? = null,
    onStateChange: ((LinkState) -> Unit)? = null,
): LinkState {
    val context = LocalContext.current
    val baseUrl = apiClient.baseUrl
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val latestCallback by rememberUpdatedState(onStateChange)
    val idleState = remember { kotlinx.coroutines.flow.MutableStateFlow(LinkState()) }

    val link = remember(src, kind, baseUrl, maxAttempts) {
        if (src == null) {
            null
        } else {
            WebRtcCamLink(
                context = context,
                okHttpClient = apiClient.okHttpClient,
                baseUrl = baseUrl,
                src = src,
                kind = kind,
                maxAttempts = maxAttempts,
                onStateCallback = { next ->
                    latestCallback?.let { cb -> mainHandler.post { cb(next) } }
                },
            )
        }
    }

    DisposableEffect(link) {
        link?.start()
        onDispose { link?.stop() }
    }

    val state by (link?.state ?: idleState).collectAsState()
    return if (src != null) state else LinkState()
}

private fun parseTab(raw: String?): CamsTab = when (raw?.trim()?.lowercase()) {
    "replay" -> CamsTab.REPLAY
    "detections" -> CamsTab.DETECTIONS
    "music" -> CamsTab.MUSIC
    else -> CamsTab.LIVE
}

private fun parseVideoDimensions(sizeStr: String?): Pair<Int, Int> {
    if (sizeStr.isNullOrBlank()) return 16 to 9
    val match = Regex("""(\d+)\s*[x×]\s*(\d+)""", RegexOption.IGNORE_CASE).find(sizeStr)
        ?: return 16 to 9
    val w = match.groupValues[1].toIntOrNull() ?: return 16 to 9
    val h = match.groupValues[2].toIntOrNull() ?: return 16 to 9
    return if (w > 0 && h > 0) w to h else 16 to 9
}
