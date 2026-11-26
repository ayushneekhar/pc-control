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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.LocalCache
import com.neekhar.pccontrol.core.theme.LocalPcColors
import com.neekhar.pccontrol.core.theme.PcDimens
import com.neekhar.pccontrol.core.theme.TabularNumStyle
import com.neekhar.pccontrol.core.ui.PcButton
import com.neekhar.pccontrol.core.ui.PcButtonVariant
import com.neekhar.pccontrol.core.ui.PcCard
import com.neekhar.pccontrol.core.ui.PcErrorBanner
import com.neekhar.pccontrol.core.ui.PcSegmentOption
import com.neekhar.pccontrol.core.ui.PcSegmentedControl
import com.neekhar.pccontrol.core.ui.PcStatusDot
import com.neekhar.pccontrol.core.ui.PcTone
import com.neekhar.pccontrol.feature.cams.CamsMediaTools.findActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
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
    onBack: () -> Unit = {},
) {
    val colors = LocalPcColors.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val repository = remember(apiClient, cache) { CamsRepository(apiClient, cache) }
    val hardwareCaps = remember(context) { WebRtcRuntime.inspectHardware(context) }

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
    var focusedCam by remember { mutableStateOf<CamId?>(null) }
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
    var snapBusyCam by remember { mutableStateOf<CamId?>(null) }

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
                Lifecycle.Event.ON_RESUME -> isForeground = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> {
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

    // Detector state polling while active (15 s)
    var detectorState by remember { mutableStateOf<DetectorState?>(null) }
    var detectorLoaded by remember { mutableStateOf(false) }
    var detectorBusy by remember { mutableStateOf(false) }

    LaunchedEffect(isForeground, stackActive) {
        if (!isForeground || !stackActive) return@LaunchedEffect
        while (isActive) {
            if (!detectorBusy) {
                when (val res = repository.getDetectorState()) {
                    is ApiResult.Success -> detectorState = res.data
                    is ApiResult.Error -> detectorState = null
                }
                detectorLoaded = true
            }
            delay(15_000L)
        }
    }

    fun toggleDetector(on: Boolean) {
        val prev = detectorState ?: return
        if (detectorBusy) return
        detectorBusy = true
        detectorState = prev.copy(enabled = on)
        scope.launch {
            when (val res = repository.toggleDetector(on)) {
                is ApiResult.Success -> {
                    detectorState = res.data
                    showToast(if (on) "Cat alerts on" else "Cat alerts off: detector paused", 2000L)
                }
                is ApiResult.Error -> {
                    detectorState = prev
                    showToast("Couldn't reach the cat detector. Nothing changed.")
                }
            }
            detectorBusy = false
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
            showToast("Microphone allowed. Hold or tap Talk to speak.", 3000L)
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
    val liveCount = feeds.values.count { it.state.fps > 0 }

    fun takeLiveSnapshot(camId: CamId) {
        if (snapBusyCam != null) return
        snapBusyCam = camId
        scope.launch {
            runCatching {
                // Always request `cat3` for CP Plus (go2rtc frame.jpeg fails on cat3hd)
                CamsMediaTools.saveLiveSnapshot(
                    context = context,
                    client = apiClient.okHttpClient,
                    baseUrl = apiClient.baseUrl,
                    stream = camId.id,
                    label = camId.displayName,
                )
            }.onSuccess { uri ->
                showToast("Saved to Pictures/PC Control", 4500L, "Share") {
                    CamsMediaTools.shareImage(context, uri)
                }
            }.onFailure { err ->
                showToast("Snapshot failed: ${err.message ?: "error"}", 4000L)
            }
            snapBusyCam = null
        }
    }

    fun enterPipFor(camId: CamId) {
        val act = activity ?: return
        focusedCam = camId
        val sizeStr = feeds[camId]?.state?.size
        val (w, h) = parseVideoDimensions(sizeStr)
        val entered = CamsMediaTools.enterPictureInPicture(act, w, h)
        if (!entered) {
            showToast("Picture-in-Picture is not enabled for this activity.", 3200L)
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

    // ---- Fullscreen landscape mode
    val fsCam = fullscreenCam
    if (fsCam != null && stackActive) {
        val fsFeed = feeds[fsCam] ?: LiveCamFeed(fsCam, LinkState(), null)
        Box(modifier = Modifier.fillMaxSize()) {
            LiveFullscreenOverlay(
                feed = fsFeed,
                cat3Hd = cat3Hd,
                camMicOn = camMicOn,
                ptzOpen = ptzOpen,
                snapBusy = snapBusyCam == fsCam,
                onExitFullscreen = { fullscreenCam = null },
                onSnapshot = { takeLiveSnapshot(fsCam) },
                onPip = {
                    fullscreenCam = null
                    enterPipFor(fsCam)
                },
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
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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

    val detectorLine = remember(detectorState, detectorLoaded) {
        buildDetectorLine(detectorState, detectorLoaded)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with Back + title + Cat Cams master power switch + overflow menu (hidden in Replay fullscreen)
            if (!inReplayFullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.text,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cat cams",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (!masterStatusText.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            PcStatusDot(tone = masterDotTone, size = 6.dp)
                            Text(
                                text = masterStatusText,
                                color = colors.textDim,
                                style = TabularNumStyle.copy(fontSize = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Master power toggle directly in header
                if (catCamBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.textDim,
                    )
                }
                Switch(
                    checked = catCamEnabled,
                    onCheckedChange = { toggleMasterCatCams(it) },
                    enabled = !catCamBusy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textDim,
                        uncheckedTrackColor = colors.surfaceRaised,
                        uncheckedBorderColor = colors.hairline,
                    ),
                )

                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
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
                                    text = if (catCamEnabled) "Turn cat cams off" else "Turn cat cams on",
                                    color = colors.text,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = colors.textDim,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (!catCamBusy) toggleMasterCatCams(!catCamEnabled)
                            },
                        )
                        if (stackActive && activeTab == CamsTab.LIVE) {
                            DropdownMenuItem(
                                text = { Text("Picture-in-Picture", color = colors.text) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.PictureInPictureAlt,
                                        contentDescription = null,
                                        tint = colors.textDim,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    enterPipFor(focusedCam ?: CamId.CAT)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Open web page", color = colors.text) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Language,
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
                                    imageVector = Icons.Filled.Shield,
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

            // Segmented tab bar (Live / Replay / Detections / Music)
            if (stackActive) {
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
                        bottom = 10.dp,
                    ),
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            } // end !inReplayFullscreen

            // Main body
            when {
                !stackActive -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space4,
                                bottom = PcDimens.Space4 + navBottom,
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
                        bottomInset = (if (showAudioBar) 108.dp else 28.dp) + navBottom,
                        onToggleFullscreen = { replayFullscreen = !replayFullscreen },
                        onTargetConsumed = { replayTarget = null },
                        onToast = { msg, actLabel, act ->
                            showToast(msg, if (act != null) 4500L else 2800L, actLabel, act)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                activeTab == CamsTab.DETECTIONS -> {
                    DetectionsTab(
                        visible = isForeground,
                        repository = repository,
                        bottomInset = (if (showAudioBar) 96.dp else 16.dp) + navBottom,
                        onJumpToReplay = { cam, tsMs ->
                            val parsed = ReplayMath.parseSightingTarget(cam, tsMs)
                            if (parsed != null) {
                                replayTarget = parsed
                                activeTab = CamsTab.REPLAY
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = PcDimens.ScreenPad,
                                end = PcDimens.ScreenPad,
                                top = PcDimens.Space3,
                                bottom = (if (showAudioBar) 108.dp else 28.dp) + navBottom,
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

                        when (activeTab) {
                            CamsTab.LIVE -> {
                                LiveTab(
                                    feeds = feeds,
                                    focusedCam = focusedCam,
                                    cat3Hd = cat3Hd,
                                    hardwareCaps = hardwareCaps,
                                    camMicOn = camMicOn,
                                    ptzOpen = ptzOpen,
                                    ptzPresets = ptzPresets,
                                    activePresetId = activePresetId,
                                    snapBusyCam = snapBusyCam,
                                    detectorState = detectorState,
                                    detectorLoaded = detectorLoaded,
                                    detectorBusy = detectorBusy,
                                    detectorLine = detectorLine,
                                    onSelectFocus = { cam ->
                                        focusedCam = cam
                                        if (cam != null) {
                                            scope.launch { prefs.setLastSelectedCam(cam.id) }
                                        }
                                    },
                                    onToggleCat3Hd = ::toggleCat3Hd,
                                    onToggleCamMic = { camMicOn = !camMicOn },
                                    onTogglePtzOpen = { ptzOpen = !ptzOpen },
                                    onPtzStart = ::startPtz,
                                    onPtzStop = ::stopPtz,
                                    onRunPtzPreset = ::runPtzPreset,
                                    onSnapshot = ::takeLiveSnapshot,
                                    onEnterFullscreen = { fullscreenCam = it },
                                    onEnterPip = ::enterPipFor,
                                    onToggleDetector = ::toggleDetector,
                                )
                            }

                            CamsTab.MUSIC -> {
                                MusicTab(
                                    visible = isForeground,
                                    repository = repository,
                                    sharedUrl = pendingSharedUrl,
                                    onSharedConsumed = { pendingSharedUrl = null },
                                    onToast = { showToast(it) },
                                )
                            }

                            CamsTab.REPLAY,
                            CamsTab.DETECTIONS,
                            -> Unit
                        }
                    }
                }
            }
        }

        // Bottom persistent Listen / Talk bar
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
                    val wasOn = talkMode != TalkMode.OFF
                    if (!wasOn) {
                        if (!hasMicPermission()) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            talkStartedAtMs = System.currentTimeMillis()
                            nowMs = talkStartedAtMs
                            talkMode = TalkMode.HELD
                            true
                        }
                    } else {
                        false
                    }
                },
                onTalkRelease = { pressDurationMs, wasAlreadyOn ->
                    if (wasAlreadyOn) {
                        talkMode = TalkMode.OFF
                    } else if (talkMode != TalkMode.OFF) {
                        talkMode = if (pressDurationMs < 400L) TalkMode.LATCHED else TalkMode.OFF
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Floating toast banner
        CamsToastBanner(
            toast = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (showAudioBar) 92.dp else 20.dp),
        )
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
    PcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcDimens.Space3),
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = if (enabled && !busy) colors.accent else colors.textDim,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cat cams",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcStatusDot(tone = dotTone, size = 7.dp)
                    Text(
                        text = statusText,
                        color = colors.textDim,
                        style = TabularNumStyle.copy(fontSize = 13.sp),
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
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
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
    onTalkRelease: (pressDurationMs: Long, wasAlreadyOn: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val view = LocalView.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PcDimens.ScreenPad, vertical = PcDimens.Space3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Listen button
            val listenVisual = when {
                !listenOn -> AudioBtnState.OFF
                earState.phase == LinkPhase.LIVE -> AudioBtnState.ON
                earState.phase == LinkPhase.ERROR -> AudioBtnState.BLOCKED
                else -> AudioBtnState.BUSY
            }
            AudioActionTile(
                visual = listenVisual,
                icon = if (listenOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                title = if (listenOn) "Listening" else "Listen",
                subtitle = when {
                    !listenOn -> "Mic off"
                    earState.phase == LinkPhase.LIVE -> null
                    earState.phase == LinkPhase.ERROR -> earState.error ?: "Failed"
                    else -> "Connecting…"
                },
                onClick = onToggleListen,
                modifier = Modifier.weight(1f),
            )

            // Talk button (hold to talk, or quick tap <400ms to latch)
            val talkLive = talkState.phase == LinkPhase.LIVE
            val talkVisual = when {
                talkMode == TalkMode.OFF -> AudioBtnState.OFF
                talkLive -> AudioBtnState.TALKING
                else -> AudioBtnState.BUSY
            }
            val talkTimer = if (talkMode == TalkMode.OFF) {
                null
            } else {
                val m = talkSeconds / 60
                val s = talkSeconds % 60
                "$m:${s.toString().padStart(2, '0')}"
            }
            AudioActionTile(
                visual = talkVisual,
                icon = if (talkMode == TalkMode.OFF) Icons.Filled.MicOff else Icons.Filled.Mic,
                title = when {
                    talkMode == TalkMode.OFF -> "Talk"
                    talkLive -> if (talkMode == TalkMode.LATCHED) "Talking · latched" else "Talking"
                    else -> "Connecting…"
                },
                subtitle = talkTimer,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(talkMode) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val wasAlreadyOn = talkMode != TalkMode.OFF
                            val startMs = System.currentTimeMillis()
                            val started = onTalkPressDown()
                            if (started) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                            waitForUpOrCancellation()
                            val elapsed = System.currentTimeMillis() - startMs
                            if (wasAlreadyOn || started) {
                                onTalkRelease(elapsed, wasAlreadyOn)
                            }
                        }
                    },
            )
        }
    }
}

private enum class AudioBtnState { OFF, ON, BUSY, TALKING, BLOCKED }

@Composable
private fun AudioActionTile(
    visual: AudioBtnState,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
    val bg = when (visual) {
        AudioBtnState.TALKING -> colors.bad
        AudioBtnState.ON -> colors.okSoft
        AudioBtnState.BLOCKED -> colors.warnSoft
        else -> colors.surface
    }
    val iconBg = when (visual) {
        AudioBtnState.TALKING -> colors.onMedia
        AudioBtnState.ON -> colors.ok
        else -> colors.surfaceRaised
    }
    val iconFg = when (visual) {
        AudioBtnState.TALKING -> colors.bad
        AudioBtnState.ON -> colors.onAccent
        else -> colors.textDim
    }
    val titleColor = if (visual == AudioBtnState.TALKING) colors.onMedia else colors.text
    val subColor = when (visual) {
        AudioBtnState.TALKING -> colors.onMediaDim
        AudioBtnState.ON -> colors.ok
        else -> colors.textDim
    }
    val borderColor = if (visual == AudioBtnState.OFF || visual == AudioBtnState.BUSY) {
        colors.hairline
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(PcDimens.RadiusSm))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(PcDimens.RadiusSm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            if (visual == AudioBtnState.BUSY) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = iconFg,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconFg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = subColor,
                    style = TabularNumStyle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CamsToastBanner(
    toast: CamsToast?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPcColors.current
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
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = current.message,
                color = colors.text,
                fontSize = 13.5.sp,
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

private fun buildDetectorLine(det: DetectorState?, loaded: Boolean): String {
    if (det == null) return if (loaded) "Detector offline" else "Connecting to detector…"
    var bestTs = 0.0
    var bestName: String? = null
    for ((k, info) in det.cams) {
        val ts = info.lastSeenTs ?: continue
        if (ts > bestTs) {
            bestTs = ts
            bestName = CamsConfig.NAMES[k] ?: info.name ?: k
        }
    }
    if (bestName == null && det.lastAlert != null && det.lastAlert.ts > 0.0) {
        bestTs = det.lastAlert.ts
        bestName = CamsConfig.NAMES[det.lastAlert.cam] ?: det.lastAlert.name ?: det.lastAlert.cam
    }
    if (bestName == null) return "No cats yet"
    val mins = floor((System.currentTimeMillis() / 1000.0 - bestTs) / 60.0).toInt()
    val agoStr = when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        mins < 48 * 60 -> "yesterday"
        else -> "${mins / (24 * 60)} days ago"
    }
    return "Cat seen $agoStr · $bestName"
}
