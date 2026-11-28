package com.neekhar.pccontrol.feature.watch.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.network.ApiTransportManager
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayerSubtitleEntry(
    val id: String,
    val label: String,
    val language: String,
    val uri: String,
    val format: String = "vtt",
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

data class PlayerAudioEntry(
    val id: String,
    val index: Int,
    val label: String,
    val language: String,
    val codec: String = "",
    val isDefault: Boolean = false,
)

data class WatchPlayerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tailscaleRequired: Boolean = false,
    val isOffline: Boolean = false,
    val jellyfinItemId: String = "",
    val mediaSourceId: String = "",
    val playSessionId: String = "",
    val mediaType: String = "movie",
    val title: String = "",
    val subtitle: String? = null,
    val streamUri: String? = null,
    val isHls: Boolean = false,
    val streamVersion: Int = 0,
    val startPositionMs: Long = 0L,
    val runtimeMs: Long = 0L,
    val qualityOptions: List<WatchQualityOption> = emptyList(),
    val selectedQualityId: String = "original",
    val audioTracks: List<PlayerAudioEntry> = emptyList(),
    val selectedAudioIndex: Int? = null,
    val subtitleTracks: List<PlayerSubtitleEntry> = emptyList(),
    val selectedSubtitleId: String? = null,
    val hlsAttachedSubtitleId: String? = null,
    val chapters: List<WatchChapter> = emptyList(),
    val segments: List<WatchMediaSegment> = emptyList(),
    val dismissedSegmentKeys: Set<String> = emptySet(),
    val trickplay: WatchTrickplayInfo? = null,
    val nextEpisode: WatchNextEpisode? = null,
    val nextEpisodeDismissed: Boolean = false,
    val nextEpisodeCountdownSec: Int? = null,
    val playbackSpeed: Float = 1.0f,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val controlsVisible: Boolean = true,
    val isLocked: Boolean = false,
    val activeSheet: WatchPlayerSheet = WatchPlayerSheet.NONE,
    val castingToTv: Boolean = false,
    val toastMessage: String? = null,
) {
    val activeSegment: WatchMediaSegment?
        get() = WatchPlayerHelpers.activeSegment(segments, currentPositionMs)

    val isInOutroSegment: Boolean
        get() = activeSegment?.type?.equals("Outro", ignoreCase = true) == true

    val showNextEpisodeCard: Boolean
        get() = WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
            positionMs = currentPositionMs,
            durationMs = durationMs.takeIf { it > 0L } ?: runtimeMs,
            isEnded = isEnded,
            hasNextEpisode = nextEpisode != null,
            dismissed = nextEpisodeDismissed,
            inOutroSegment = isInOutroSegment,
        )

    val selectedQualityLabel: String
        get() = qualityOptions.firstOrNull { it.id == selectedQualityId }?.label
            ?: if (isOffline) "Offline" else "Original"
}

sealed interface WatchPlayerEvent {
    data object HandedOffToTv : WatchPlayerEvent
}

class WatchPlayerViewModel(
    private val playerRepo: WatchPlayerRepository,
    private val downloadManager: WatchDownloadManager,
    private val transportManager: ApiTransportManager,
    private val initialJellyfinItemId: String?,
    private val initialTmdbId: Int?,
    private val initialMediaType: String?,
    private val initialSeason: Int?,
    private val initialEpisode: Int?,
    private val preferOffline: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchPlayerUiState())
    val uiState: StateFlow<WatchPlayerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WatchPlayerEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<WatchPlayerEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var progressSyncJob: Job? = null
    private var seekDebounceJob: Job? = null
    private var nextEpCountdownJob: Job? = null
    private var toastClearJob: Job? = null
    private var startedReported = false
    private var fallbackAttempted = false
    private var lastLocalSaveMs = 0L
    private var pendingInitialSeekMs = 0L
    private var hasPlaybackStartedOrSeeked = false
    private var lastStopReportedPosMs: Long = -1L
    private var lastStopReportedItemId: String = ""

    companion object {
        // Application-lifetime scope so Back/onCleared NEVER cancels final progress/stop POSTs
        private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val PERIODIC_SYNC_MS = 10_000L
        private const val SEEK_SYNC_DEBOUNCE_MS = 2_000L
    }

    init {
        loadItem(
            jellyfinItemId = initialJellyfinItemId,
            tmdbId = initialTmdbId,
            mediaType = initialMediaType,
            season = initialSeason,
            episode = initialEpisode,
            preferOffline = preferOffline,
        )
    }

    fun retry() {
        transportManager.reprobeNow()
        val cur = _uiState.value
        loadItem(
            jellyfinItemId = cur.jellyfinItemId.takeIf { it.isNotBlank() } ?: initialJellyfinItemId,
            tmdbId = initialTmdbId,
            mediaType = initialMediaType,
            season = initialSeason,
            episode = initialEpisode,
            preferOffline = preferOffline,
            forceRefresh = true,
        )
    }

    private fun loadItem(
        jellyfinItemId: String?,
        tmdbId: Int?,
        mediaType: String?,
        season: Int?,
        episode: Int?,
        preferOffline: Boolean,
        forceRefresh: Boolean = false,
    ) {
        loadJob?.cancel()
        progressSyncJob?.cancel()
        seekDebounceJob?.cancel()
        nextEpCountdownJob?.cancel()
        startedReported = false
        fallbackAttempted = false
        pendingInitialSeekMs = 0L
        hasPlaybackStartedOrSeeked = false
        lastStopReportedPosMs = -1L
        lastStopReportedItemId = ""

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    tailscaleRequired = false,
                    isEnded = false,
                    segments = emptyList(),
                    dismissedSegmentKeys = emptySet(),
                    trickplay = null,
                    nextEpisodeDismissed = false,
                    nextEpisodeCountdownSec = null,
                    activeSheet = WatchPlayerSheet.NONE,
                )
            }

            // 1. Check for a completed local offline file first
            val offlineCandidate = findOfflineMatch(jellyfinItemId, tmdbId, mediaType)
            if (offlineCandidate != null && offlineCandidate.isCompleted) {
                // Playing an already-downloaded file locally is instant & zero-bandwidth
                activateOfflineItem(offlineCandidate)
                return@launch
            }

            // 2. Directly fetch playback info over Tailscale serverUrl (never gate on cached transport state)
            val res = playerRepo.getPlaybackInfo(
                jellyfinItemId = jellyfinItemId,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                useCache = !forceRefresh,
            )
            when (res) {
                is ApiResult.Error -> {
                    val isTs = res.statusCode == 0 ||
                        (res.statusCode == 403 && res.rawBody?.contains("tailscaleRequired") == true)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            tailscaleRequired = isTs,
                            error = if (isTs) {
                                "Connect to Tailscale to stream video."
                            } else {
                                res.message
                            },
                        )
                    }
                }
                is ApiResult.Success -> {
                    transportManager.markTailscaleReachable()
                    activateOnlinePlayback(res.data)
                }
            }
        }
    }

    private fun isTailscaleActive(): Boolean =
        transportManager.resolveTransport() == ApiTransport.TAILSCALE

    private fun findOfflineMatch(
        jellyfinItemId: String?,
        tmdbId: Int?,
        mediaType: String?,
    ): OfflineWatchItem? {
        val byId = jellyfinItemId?.takeIf { it.isNotBlank() }?.let { downloadManager.getItem(it) }
        if (byId != null && byId.isCompleted && File(byId.localVideoPath).isFile) {
            return byId
        }
        if (tmdbId != null && tmdbId > 0 && !mediaType.isNullOrBlank()) {
            val byTmdb = downloadManager.findByTmdb(tmdbId, mediaType)
            if (byTmdb != null && byTmdb.isCompleted && File(byTmdb.localVideoPath).isFile) {
                return byTmdb
            }
        }
        return null
    }

    private fun activateOfflineItem(item: OfflineWatchItem) {
        val itemDir = downloadManager.store.itemDir(item.jellyfinItemId)
        val subs = item.subtitles.mapNotNull { sub ->
            val f = File(itemDir, sub.localFileName)
            if (!f.isFile || f.length() <= 0L) return@mapNotNull null
            PlayerSubtitleEntry(
                id = "sub:${sub.index}",
                label = sub.displayTitle.ifBlank { sub.language.uppercase().ifBlank { "Subtitle ${sub.index}" } },
                language = sub.language,
                uri = f.toURI().toString(),
                format = sub.format,
                isExternal = true,
                isDefault = sub.isDefault,
                isForced = sub.isForced,
            )
        }
        val defaultSub = subs.firstOrNull { it.isDefault }?.id
        val startMs = WatchPlayerHelpers.ticksToMs(item.resumePositionTicks)
        val runtimeMs = WatchPlayerHelpers.ticksToMs(item.runtimeTicks)
        pendingInitialSeekMs = startMs
        hasPlaybackStartedOrSeeked = false

        _uiState.update { cur ->
            cur.copy(
                loading = false,
                error = null,
                tailscaleRequired = false,
                isOffline = true,
                jellyfinItemId = item.jellyfinItemId,
                mediaSourceId = item.mediaSourceId,
                playSessionId = "",
                mediaType = item.mediaType,
                title = item.title,
                subtitle = item.subtitle,
                streamUri = File(item.localVideoPath).toURI().toString(),
                isHls = false,
                streamVersion = cur.streamVersion + 1,
                startPositionMs = startMs,
                currentPositionMs = startMs,
                runtimeMs = runtimeMs,
                durationMs = runtimeMs,
                qualityOptions = emptyList(),
                selectedQualityId = "offline",
                audioTracks = emptyList(),
                selectedAudioIndex = null,
                subtitleTracks = subs,
                selectedSubtitleId = defaultSub,
                hlsAttachedSubtitleId = null,
                chapters = emptyList(),
                segments = emptyList(),
                dismissedSegmentKeys = emptySet(),
                trickplay = null,
                nextEpisode = null,
                isBuffering = true,
            )
        }
    }

    private fun activateOnlinePlayback(info: WatchPlaybackInfoResponse) {
        val options = info.qualityOptions.map { opt ->
            opt.copy(streamUrl = playerRepo.resolveTailscaleUrl(opt.streamUrl))
        }
        val audioEntries = info.audioTracks.map { tr ->
            PlayerAudioEntry(
                id = "aud:${tr.index}",
                index = tr.index,
                label = tr.displayTitle.ifBlank { tr.language.uppercase().ifBlank { "Audio ${tr.index}" } },
                language = tr.language,
                codec = tr.codec,
                isDefault = tr.isDefault,
            )
        }
        val defaultAudio = audioEntries.firstOrNull { it.isDefault } ?: audioEntries.firstOrNull()
        val defaultAudioIdx = defaultAudio?.index

        // With Jellyfin's Media3 FFmpeg audio decoder enabled (EXTENSION_RENDERER_MODE_ON),
        // TrueHD / DTS / DTS-HD / EAC3 / FLAC / ALAC / Opus / Vorbis decode natively on-device
        // during Direct Play with zero server transcoding; reactive HLS fallback stays as a safety net.
        val chosen = WatchPlayerHelpers.selectInitialQuality(options, info.recommendedQualityId)
        var chosenUrl = chosen?.streamUrl?.takeIf { it.isNotBlank() }
            ?: playerRepo.resolveTailscaleUrl(info.directStreamUrl.ifBlank { info.hlsUrl })
        val chosenIsHls = chosen?.isHls ?: chosenUrl.contains(".m3u8", ignoreCase = true)
        if (chosenIsHls && defaultAudioIdx != null) {
            chosenUrl = WatchPlayerHelpers.withAudioStreamIndex(chosenUrl, defaultAudioIdx)
        }

        val subEntries = info.subtitleTracks.map { sub ->
            PlayerSubtitleEntry(
                id = "sub:${sub.index}",
                label = sub.displayTitle.ifBlank { sub.language.uppercase().ifBlank { "Subtitle ${sub.index}" } },
                language = sub.language,
                uri = playerRepo.resolveTailscaleUrl(sub.deliveryUrl),
                format = "vtt",
                isExternal = sub.isExternal,
                isDefault = sub.isDefault,
                isForced = sub.isForced,
            )
        }
        // In HLS mode, only default-select external subtitles on startup so we never trigger
        // a slow server-side ffmpeg subtitle extraction before playback begins.
        val defaultSubId = if (chosenIsHls) {
            subEntries.firstOrNull { it.isDefault && it.isExternal }?.id
        } else {
            subEntries.firstOrNull { it.isDefault }?.id
        }

        val resolvedTrickplay = info.trickplay?.takeIf {
            it.thumbnailCount > 0 && it.tileUrlTemplate.isNotBlank()
        }?.let { tp ->
            tp.copy(tileUrlTemplate = playerRepo.resolveTailscaleUrl(tp.tileUrlTemplate))
        }

        val startMs = WatchPlayerHelpers.ticksToMs(info.resumePositionTicks)
        val runtimeMs = WatchPlayerHelpers.ticksToMs(info.runtimeTicks)
        pendingInitialSeekMs = startMs
        hasPlaybackStartedOrSeeked = false

        _uiState.update { cur ->
            cur.copy(
                loading = false,
                error = null,
                tailscaleRequired = false,
                isOffline = false,
                jellyfinItemId = info.jellyfinItemId,
                mediaSourceId = info.mediaSourceId,
                playSessionId = info.playSessionId,
                mediaType = info.mediaType,
                title = info.title,
                subtitle = info.subtitle,
                streamUri = chosenUrl,
                isHls = chosenIsHls,
                streamVersion = cur.streamVersion + 1,
                startPositionMs = startMs,
                currentPositionMs = startMs,
                runtimeMs = runtimeMs,
                durationMs = runtimeMs,
                qualityOptions = options,
                selectedQualityId = chosen?.id ?: "original",
                audioTracks = audioEntries,
                selectedAudioIndex = defaultAudioIdx,
                subtitleTracks = subEntries,
                selectedSubtitleId = defaultSubId,
                hlsAttachedSubtitleId = null,
                chapters = info.chapters,
                segments = info.segments,
                dismissedSegmentKeys = emptySet(),
                trickplay = resolvedTrickplay,
                nextEpisode = info.nextEpisode,
                isBuffering = true,
            )
        }
        startPeriodicProgressSync()
    }

    private fun startPeriodicProgressSync() {
        progressSyncJob?.cancel()
        progressSyncJob = viewModelScope.launch {
            while (true) {
                delay(PERIODIC_SYNC_MS)
                val st = _uiState.value
                if (st.isPlaying && st.jellyfinItemId.isNotBlank() && canPersistPosition(st)) {
                    sendOnlineProgressSnapshot(st, event = "progress", isPaused = false)
                }
            }
        }
    }

    /**
     * Guard against the classic `0:00` overwrite bug: do not report `position == 0`
     * before an initial resume seek (`pendingInitialSeekMs > 0`) has settled and playback started.
     */
    private fun canPersistPosition(st: WatchPlayerUiState): Boolean {
        if (st.jellyfinItemId.isBlank()) return false
        if (pendingInitialSeekMs > 0L && !hasPlaybackStartedOrSeeked) return false
        if (st.currentPositionMs <= 0L && !hasPlaybackStartedOrSeeked && !st.isEnded) return false
        return true
    }

    fun onUserSeek(targetPositionMs: Long) {
        val safePos = targetPositionMs.coerceAtLeast(0L)
        hasPlaybackStartedOrSeeked = true
        pendingInitialSeekMs = 0L
        lastStopReportedPosMs = -1L
        _uiState.update { cur ->
            cur.copy(currentPositionMs = safePos)
        }
        seekDebounceJob?.cancel()
        seekDebounceJob = viewModelScope.launch {
            delay(SEEK_SYNC_DEBOUNCE_MS)
            val st = _uiState.value
            if (st.jellyfinItemId.isNotBlank() && canPersistPosition(st)) {
                persistCurrentPosition(event = "progress", isPaused = !st.isPlaying)
            }
        }
    }

    fun onPlaybackProgress(
        positionMs: Long,
        bufferedMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        isEnded: Boolean,
    ) {
        val prev = _uiState.value
        val safeDur = if (durationMs > 0L) durationMs else prev.durationMs
        val rawPos = positionMs.coerceAtLeast(0L)

        // Guard against ExoPlayer reporting 0ms while preparing an initial resume seek
        val waitingForInitialSeek = pendingInitialSeekMs > 0L && !hasPlaybackStartedOrSeeked
        val effectivePos = if (waitingForInitialSeek && rawPos < 1_000L && !isEnded) {
            prev.currentPositionMs.coerceAtLeast(pendingInitialSeekMs)
        } else {
            if (isPlaying && (pendingInitialSeekMs <= 0L || rawPos >= 1_000L)) {
                hasPlaybackStartedOrSeeked = true
                pendingInitialSeekMs = 0L
            }
            rawPos
        }

        if (isPlaying) {
            lastStopReportedPosMs = -1L
        }

        _uiState.update { cur ->
            cur.copy(
                currentPositionMs = effectivePos,
                bufferedPositionMs = bufferedMs.coerceAtLeast(effectivePos),
                durationMs = safeDur,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                isEnded = isEnded,
            )
        }

        val updated = _uiState.value
        if (!startedReported && isPlaying && updated.jellyfinItemId.isNotBlank() && !updated.isOffline && canPersistPosition(updated)) {
            startedReported = true
            progressScope.launch {
                withContext(NonCancellable) {
                    sendOnlineProgressSnapshot(updated, event = "start", isPaused = false)
                }
            }
        }

        if (prev.isPlaying && !isPlaying && !isEnded && canPersistPosition(updated)) {
            persistCurrentPosition(event = "progress", isPaused = true)
        }

        // Periodically save offline resume ticks locally
        if (updated.isOffline && canPersistPosition(updated)) {
            val now = System.currentTimeMillis()
            if (now - lastLocalSaveMs >= 5_000L) {
                lastLocalSaveMs = now
                downloadManager.updateOfflineResume(
                    jellyfinItemId = updated.jellyfinItemId,
                    positionTicks = WatchPlayerHelpers.msToTicks(effectivePos),
                    runtimeTicks = WatchPlayerHelpers.msToTicks(safeDur),
                )
            }
        }

        // Trigger Next Episode countdown when ended
        if (isEnded && !prev.isEnded) {
            hasPlaybackStartedOrSeeked = true
            persistCurrentPosition(event = "stop", isPaused = false)
            if (prev.nextEpisode != null && !prev.nextEpisodeDismissed) {
                startNextEpisodeCountdown()
            }
        }
    }

    fun onPlayerError(message: String) {
        val st = _uiState.value
        // Auto-fallback once from Direct Play to 1080p NVENC HLS transcode on any PlaybackException
        // (including MediaCodecAudioRenderer / NO_UNSUPPORTED_TYPE / decoder init failures)
        if (!st.isOffline && !st.isHls && !fallbackAttempted) {
            val hlsFallback = WatchPlayerHelpers.selectHlsFallbackQuality(st.qualityOptions)
            if (hlsFallback != null) {
                fallbackAttempted = true
                showToast("Switching to transcoded stream…")
                selectQuality(hlsFallback.id)
                return
            }
        }
        _uiState.update {
            it.copy(
                isBuffering = false,
                error = message.ifBlank { "Playback error occurred." },
            )
        }
    }

    fun onDirectPlayAudioUnsupported() {
        val st = _uiState.value
        if (st.isOffline || st.isHls || fallbackAttempted) return
        val hlsFallback = WatchPlayerHelpers.selectHlsFallbackQuality(st.qualityOptions) ?: return
        fallbackAttempted = true
        showToast("Switching to transcoded audio…")
        selectQuality(hlsFallback.id)
    }

    fun selectQuality(qualityId: String) {
        val st = _uiState.value
        val opt = st.qualityOptions.firstOrNull { it.id == qualityId } ?: return
        if (!opt.isHls) {
            fallbackAttempted = false
        }
        var url = opt.streamUrl
        val audioIdx = st.selectedAudioIndex
        if (opt.isHls && audioIdx != null) {
            url = WatchPlayerHelpers.withAudioStreamIndex(url, audioIdx)
        }
        val nextSelectedSub = if (opt.isHls) {
            val currentSub = st.subtitleTracks.firstOrNull { it.id == st.selectedSubtitleId }
            if (currentSub != null && currentSub.isExternal) currentSub.id else null
        } else {
            st.selectedSubtitleId
        }
        _uiState.update { cur ->
            cur.copy(
                selectedQualityId = opt.id,
                streamUri = url,
                isHls = opt.isHls,
                startPositionMs = cur.currentPositionMs.coerceAtLeast(0L),
                streamVersion = cur.streamVersion + 1,
                selectedSubtitleId = nextSelectedSub,
                hlsAttachedSubtitleId = null,
                activeSheet = WatchPlayerSheet.NONE,
                isBuffering = true,
                error = null,
            )
        }
    }

    fun selectAudioTrack(audioIndex: Int) {
        val st = _uiState.value
        if (st.selectedAudioIndex == audioIndex) {
            closeSheet()
            return
        }
        if (st.isHls && !st.streamUri.isNullOrBlank()) {
            // HLS transcode must specify AudioStreamIndex on upstream URL
            val updatedUrl = WatchPlayerHelpers.withAudioStreamIndex(st.streamUri, audioIndex)
            _uiState.update { cur ->
                cur.copy(
                    selectedAudioIndex = audioIndex,
                    streamUri = updatedUrl,
                    startPositionMs = cur.currentPositionMs.coerceAtLeast(0L),
                    streamVersion = cur.streamVersion + 1,
                    activeSheet = WatchPlayerSheet.NONE,
                    isBuffering = true,
                )
            }
        } else {
            // Direct / local file: ExoPlayer (with FFmpeg audio extension) switches track in-place without reloading
            _uiState.update {
                it.copy(
                    selectedAudioIndex = audioIndex,
                    activeSheet = WatchPlayerSheet.NONE,
                )
            }
        }
    }

    fun selectSubtitleTrack(subtitleId: String?) {
        val st = _uiState.value
        if (st.isHls && subtitleId != null) {
            val entry = st.subtitleTracks.firstOrNull { it.id == subtitleId }
            val needsOnDemandAttach = entry != null &&
                !entry.isExternal &&
                !entry.uri.startsWith("file://", ignoreCase = true) &&
                st.hlsAttachedSubtitleId != subtitleId
            if (needsOnDemandAttach) {
                _uiState.update { cur ->
                    cur.copy(
                        selectedSubtitleId = subtitleId,
                        hlsAttachedSubtitleId = subtitleId,
                        startPositionMs = cur.currentPositionMs.coerceAtLeast(0L),
                        streamVersion = cur.streamVersion + 1,
                        activeSheet = WatchPlayerSheet.NONE,
                        isBuffering = true,
                    )
                }
                return
            }
        }
        _uiState.update {
            it.copy(
                selectedSubtitleId = subtitleId,
                activeSheet = WatchPlayerSheet.NONE,
            )
        }
    }

    fun selectPlaybackSpeed(speed: Float) {
        _uiState.update {
            it.copy(
                playbackSpeed = speed.coerceIn(0.5f, 2.0f),
                activeSheet = WatchPlayerSheet.NONE,
            )
        }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(controlsVisible = visible) }
    }

    fun toggleControlsVisible() {
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun toggleLock() {
        _uiState.update {
            val nextLocked = !it.isLocked
            it.copy(
                isLocked = nextLocked,
                activeSheet = WatchPlayerSheet.NONE,
                controlsVisible = !nextLocked,
            )
        }
    }

    fun openSheet(sheet: WatchPlayerSheet) {
        _uiState.update { it.copy(activeSheet = sheet, controlsVisible = true) }
    }

    fun closeSheet() {
        _uiState.update { it.copy(activeSheet = WatchPlayerSheet.NONE) }
    }

    fun dismissNextEpisode() {
        nextEpCountdownJob?.cancel()
        _uiState.update { it.copy(nextEpisodeDismissed = true, nextEpisodeCountdownSec = null) }
    }

    fun dismissSegment(segmentKey: String) {
        if (segmentKey.isBlank()) return
        _uiState.update { cur ->
            cur.copy(dismissedSegmentKeys = cur.dismissedSegmentKeys + segmentKey)
        }
    }

    fun playNextEpisodeNow() {
        nextEpCountdownJob?.cancel()
        val next = _uiState.value.nextEpisode ?: return
        if (next.jellyfinItemId.isBlank()) return
        reportStopSync()
        loadItem(
            jellyfinItemId = next.jellyfinItemId,
            tmdbId = null,
            mediaType = "episode",
            season = next.seasonNumber,
            episode = next.episodeNumber,
            preferOffline = false,
        )
    }

    fun castToTv(sessionId: String? = null, deviceName: String? = null) {
        val st = _uiState.value
        if (st.castingToTv || st.jellyfinItemId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(castingToTv = true) }
            val posTicks = WatchPlayerHelpers.msToTicks(st.currentPositionMs)
            val res = playerRepo.handoffToTv(
                jellyfinItemId = st.jellyfinItemId,
                mediaType = st.mediaType,
                title = st.title,
                positionTicks = posTicks,
                sessionId = sessionId,
                deviceName = deviceName,
            )
            when (res) {
                is ApiResult.Success -> {
                    val dev = res.data.device?.takeIf { it.isNotBlank() } ?: deviceName ?: "TV"
                    showToast("Handed off to $dev")
                    _uiState.update { it.copy(castingToTv = false) }
                    reportStopSync()
                    _events.tryEmit(WatchPlayerEvent.HandedOffToTv)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(castingToTv = false) }
                    showToast(res.message)
                }
            }
        }
    }

    fun showCastMessage(message: String) {
        showToast(message)
    }

    fun reportStopSync(explicitPositionMs: Long? = null) {
        val st = _uiState.value
        val effectiveSt = if (explicitPositionMs != null && explicitPositionMs > 0L) {
            hasPlaybackStartedOrSeeked = true
            st.copy(currentPositionMs = explicitPositionMs)
        } else {
            st
        }
        if (!canPersistPosition(effectiveSt)) return
        // Dedupe back-to-back stop reports for the same item & position (e.g. ON_STOP -> onDispose -> onCleared)
        if (lastStopReportedItemId == effectiveSt.jellyfinItemId &&
            lastStopReportedPosMs >= 0L &&
            kotlin.math.abs(effectiveSt.currentPositionMs - lastStopReportedPosMs) < 1_000L
        ) {
            return
        }
        lastStopReportedItemId = effectiveSt.jellyfinItemId
        lastStopReportedPosMs = effectiveSt.currentPositionMs
        persistSnapshot(effectiveSt, event = "stop", isPaused = false)
    }

    private fun persistCurrentPosition(event: String, isPaused: Boolean) {
        val st = _uiState.value
        if (!canPersistPosition(st)) return
        persistSnapshot(st, event = event, isPaused = isPaused)
    }

    private fun persistSnapshot(st: WatchPlayerUiState, event: String, isPaused: Boolean) {
        val posTicks = WatchPlayerHelpers.msToTicks(st.currentPositionMs)
        val runTicks = WatchPlayerHelpers.msToTicks(st.durationMs.takeIf { it > 0L } ?: st.runtimeMs)

        if (st.isOffline) {
            downloadManager.updateOfflineResume(
                jellyfinItemId = st.jellyfinItemId,
                positionTicks = posTicks,
                runtimeTicks = runTicks,
            )
            // Best-effort sync to Jellyfin in NonCancellable application scope
            progressScope.launch {
                withContext(NonCancellable) {
                    downloadManager.syncDirtyResumePositions()
                }
            }
        } else {
            progressScope.launch {
                withContext(NonCancellable) {
                    sendOnlineProgressSnapshot(st, event = event, isPaused = isPaused)
                }
            }
        }
    }

    private suspend fun sendOnlineProgressSnapshot(st: WatchPlayerUiState, event: String, isPaused: Boolean) {
        if (st.jellyfinItemId.isBlank() || st.isOffline) return
        playerRepo.reportProgress(
            WatchProgressRequest(
                jellyfinItemId = st.jellyfinItemId,
                mediaSourceId = st.mediaSourceId.ifBlank { st.jellyfinItemId },
                playSessionId = st.playSessionId,
                positionTicks = WatchPlayerHelpers.msToTicks(st.currentPositionMs),
                runtimeTicks = WatchPlayerHelpers.msToTicks(st.durationMs.takeIf { it > 0L } ?: st.runtimeMs),
                event = event,
                isPaused = isPaused,
            ),
        )
    }

    private fun startNextEpisodeCountdown() {
        nextEpCountdownJob?.cancel()
        nextEpCountdownJob = viewModelScope.launch {
            for (sec in 10 downTo 1) {
                if (_uiState.value.nextEpisodeDismissed) return@launch
                _uiState.update { it.copy(nextEpisodeCountdownSec = sec) }
                delay(1_000L)
            }
            if (!_uiState.value.nextEpisodeDismissed) {
                playNextEpisodeNow()
            }
        }
    }

    private fun showToast(message: String) {
        toastClearJob?.cancel()
        _uiState.update { it.copy(toastMessage = message) }
        toastClearJob = viewModelScope.launch {
            delay(3_000L)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    override fun onCleared() {
        reportStopSync()
        progressSyncJob?.cancel()
        seekDebounceJob?.cancel()
        nextEpCountdownJob?.cancel()
        super.onCleared()
    }
}
