package com.neekhar.pccontrol.feature.watch.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.network.ApiTransportManager
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PlayerSubtitleEntry(
    val id: String,
    val label: String,
    val language: String,
    val uri: String,
    val format: String = "vtt",
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

data class PlayerAudioEntry(
    val id: String,
    val index: Int,
    val label: String,
    val language: String,
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
    val chapters: List<WatchChapter> = emptyList(),
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
    val showNextEpisodeCard: Boolean
        get() = WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
            positionMs = currentPositionMs,
            durationMs = durationMs.takeIf { it > 0L } ?: runtimeMs,
            isEnded = isEnded,
            hasNextEpisode = nextEpisode != null,
            dismissed = nextEpisodeDismissed,
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
    private var nextEpCountdownJob: Job? = null
    private var toastClearJob: Job? = null
    private var startedReported = false
    private var fallbackAttempted = false
    private var lastLocalSaveMs = 0L

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
        )
    }

    private fun loadItem(
        jellyfinItemId: String?,
        tmdbId: Int?,
        mediaType: String?,
        season: Int?,
        episode: Int?,
        preferOffline: Boolean,
    ) {
        loadJob?.cancel()
        progressSyncJob?.cancel()
        nextEpCountdownJob?.cancel()
        startedReported = false
        fallbackAttempted = false

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    tailscaleRequired = false,
                    isEnded = false,
                    nextEpisodeDismissed = false,
                    nextEpisodeCountdownSec = null,
                    activeSheet = WatchPlayerSheet.NONE,
                )
            }

            // 1. Check for a completed local offline file first
            val offlineCandidate = findOfflineMatch(jellyfinItemId, tmdbId, mediaType)
            if (offlineCandidate != null && (preferOffline || !isTailscaleActive())) {
                activateOfflineItem(offlineCandidate)
                return@launch
            }
            if (offlineCandidate != null && offlineCandidate.isCompleted) {
                // Even when on Tailscale, playing an already-downloaded file locally is instant & zero-bandwidth
                activateOfflineItem(offlineCandidate)
                return@launch
            }

            // 2. Enforce Tailscale-only guard before streaming
            if (!isTailscaleActive()) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        tailscaleRequired = true,
                        error = "Connect to Tailscale to stream video.",
                    )
                }
                return@launch
            }

            // 3. Fetch playback info from backend over Tailscale
            val res = playerRepo.getPlaybackInfo(
                jellyfinItemId = jellyfinItemId,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
            )
            when (res) {
                is ApiResult.Error -> {
                    val isTs = res.statusCode == 403 && res.rawBody?.contains("tailscaleRequired") == true
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
                isDefault = sub.isDefault,
                isForced = sub.isForced,
            )
        }
        val defaultSub = subs.firstOrNull { it.isDefault }?.id
        val startMs = WatchPlayerHelpers.ticksToMs(item.resumePositionTicks)
        val runtimeMs = WatchPlayerHelpers.ticksToMs(item.runtimeTicks)

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
                chapters = emptyList(),
                nextEpisode = null,
                isBuffering = true,
            )
        }
    }

    private fun activateOnlinePlayback(info: WatchPlaybackInfoResponse) {
        val options = info.qualityOptions.map { opt ->
            opt.copy(streamUrl = playerRepo.resolveTailscaleUrl(opt.streamUrl))
        }
        val chosen = WatchPlayerHelpers.selectInitialQuality(options, info.recommendedQualityId)
        val chosenUrl = chosen?.streamUrl?.takeIf { it.isNotBlank() }
            ?: playerRepo.resolveTailscaleUrl(info.directStreamUrl.ifBlank { info.hlsUrl })
        val chosenIsHls = chosen?.isHls ?: chosenUrl.contains(".m3u8", ignoreCase = true)

        val audioEntries = info.audioTracks.map { tr ->
            PlayerAudioEntry(
                id = "aud:${tr.index}",
                index = tr.index,
                label = tr.displayTitle.ifBlank { tr.language.uppercase().ifBlank { "Audio ${tr.index}" } },
                language = tr.language,
                isDefault = tr.isDefault,
            )
        }
        val defaultAudioIdx = audioEntries.firstOrNull { it.isDefault }?.index
            ?: audioEntries.firstOrNull()?.index

        val subEntries = info.subtitleTracks.map { sub ->
            PlayerSubtitleEntry(
                id = "sub:${sub.index}",
                label = sub.displayTitle.ifBlank { sub.language.uppercase().ifBlank { "Subtitle ${sub.index}" } },
                language = sub.language,
                uri = playerRepo.resolveTailscaleUrl(sub.deliveryUrl),
                format = "vtt",
                isDefault = sub.isDefault,
                isForced = sub.isForced,
            )
        }
        val defaultSubId = subEntries.firstOrNull { it.isDefault }?.id

        val startMs = WatchPlayerHelpers.ticksToMs(info.resumePositionTicks)
        val runtimeMs = WatchPlayerHelpers.ticksToMs(info.runtimeTicks)

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
                chapters = info.chapters,
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
                delay(15_000L)
                val st = _uiState.value
                if (st.isPlaying && st.jellyfinItemId.isNotBlank()) {
                    sendOnlineProgress(event = "progress", isPaused = false)
                }
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
        val safePos = positionMs.coerceAtLeast(0L)

        _uiState.update { cur ->
            cur.copy(
                currentPositionMs = safePos,
                bufferedPositionMs = bufferedMs.coerceAtLeast(safePos),
                durationMs = safeDur,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                isEnded = isEnded,
            )
        }

        if (!startedReported && isPlaying && prev.jellyfinItemId.isNotBlank() && !prev.isOffline) {
            startedReported = true
            viewModelScope.launch { sendOnlineProgress(event = "start", isPaused = false) }
        }

        if (prev.isPlaying && !isPlaying && !isEnded && prev.jellyfinItemId.isNotBlank()) {
            persistCurrentPosition(event = "progress", isPaused = true)
        }

        // Periodically save offline resume ticks locally
        if (prev.isOffline && prev.jellyfinItemId.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastLocalSaveMs >= 5_000L) {
                lastLocalSaveMs = now
                downloadManager.updateOfflineResume(
                    jellyfinItemId = prev.jellyfinItemId,
                    positionTicks = WatchPlayerHelpers.msToTicks(safePos),
                    runtimeTicks = WatchPlayerHelpers.msToTicks(safeDur),
                )
            }
        }

        // Trigger Next Episode countdown when ended
        if (isEnded && !prev.isEnded) {
            persistCurrentPosition(event = "stop", isPaused = false)
            if (prev.nextEpisode != null && !prev.nextEpisodeDismissed) {
                startNextEpisodeCountdown()
            }
        }
    }

    fun onPlayerError(message: String) {
        val st = _uiState.value
        // Auto-fallback once from Direct play to 1080p NVENC HLS transcode if device decoder fails
        if (!st.isOffline && !st.isHls && !fallbackAttempted) {
            val hlsFallback = st.qualityOptions.firstOrNull { it.isHls && it.id == "1080p" }
                ?: st.qualityOptions.firstOrNull { it.isHls }
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

    fun selectQuality(qualityId: String) {
        val st = _uiState.value
        val opt = st.qualityOptions.firstOrNull { it.id == qualityId } ?: return
        var url = opt.streamUrl
        val audioIdx = st.selectedAudioIndex
        if (opt.isHls && audioIdx != null) {
            url = WatchPlayerHelpers.withAudioStreamIndex(url, audioIdx)
        }
        _uiState.update { cur ->
            cur.copy(
                selectedQualityId = opt.id,
                streamUri = url,
                isHls = opt.isHls,
                startPositionMs = cur.currentPositionMs.coerceAtLeast(0L),
                streamVersion = cur.streamVersion + 1,
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
            // Direct / local file: ExoPlayer switches track in-place without reloading
            _uiState.update {
                it.copy(
                    selectedAudioIndex = audioIndex,
                    activeSheet = WatchPlayerSheet.NONE,
                )
            }
        }
    }

    fun selectSubtitleTrack(subtitleId: String?) {
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

    fun castToTv() {
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
            )
            when (res) {
                is ApiResult.Success -> {
                    val dev = res.data.device?.takeIf { it.isNotBlank() } ?: "TV"
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

    fun reportStopSync() {
        persistCurrentPosition(event = "stop", isPaused = false)
    }

    private fun persistCurrentPosition(event: String, isPaused: Boolean) {
        val st = _uiState.value
        if (st.jellyfinItemId.isBlank()) return
        val posTicks = WatchPlayerHelpers.msToTicks(st.currentPositionMs)
        val runTicks = WatchPlayerHelpers.msToTicks(st.durationMs.takeIf { it > 0L } ?: st.runtimeMs)

        if (st.isOffline) {
            downloadManager.updateOfflineResume(
                jellyfinItemId = st.jellyfinItemId,
                positionTicks = posTicks,
                runtimeTicks = runTicks,
            )
            // Best-effort sync to Jellyfin if online
            viewModelScope.launch {
                downloadManager.syncDirtyResumePositions()
            }
        } else {
            viewModelScope.launch {
                sendOnlineProgress(event = event, isPaused = isPaused)
            }
        }
    }

    private suspend fun sendOnlineProgress(event: String, isPaused: Boolean) {
        val st = _uiState.value
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
        progressSyncJob?.cancel()
        nextEpCountdownJob?.cancel()
        super.onCleared()
    }
}
