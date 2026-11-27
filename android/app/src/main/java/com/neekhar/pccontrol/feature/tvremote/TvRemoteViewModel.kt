package com.neekhar.pccontrol.feature.tvremote

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class TvRemoteUiState(
    val session: TvSessionResponse? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val commandError: String? = null,
    val commandBusy: Boolean = false,
    val interpolatedPositionSeconds: Double = 0.0,
    val isScrubbing: Boolean = false,
    val scrubPositionSeconds: Float = 0f,
    val showRemainingTime: Boolean = true,
) {
    val connected: Boolean
        get() = session?.connected == true

    val isPlaying: Boolean
        get() = session?.connected == true && session.isPlaying && session.nowPlaying != null

    val isPaused: Boolean
        get() = session?.isPaused == true

    val deviceName: String
        get() = session?.deviceName?.takeIf { it.isNotBlank() } ?: "Lumio Arc 7"

    val connectedDeviceName: String?
        get() = if (session?.connected == true) {
            session.deviceName.takeIf { it.isNotBlank() }
        } else {
            null
        }

    val durationSeconds: Double
        get() = session?.durationSeconds?.coerceAtLeast(0.0) ?: 0.0

    val displayedPositionSeconds: Double
        get() = if (isScrubbing) scrubPositionSeconds.toDouble() else interpolatedPositionSeconds
}

class TvRemoteViewModel(
    private val repository: TvRemoteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvRemoteUiState())
    val uiState: StateFlow<TvRemoteUiState> = _uiState.asStateFlow()

    private var fullScreenActive: Boolean = false
    private val miniBarOwners = mutableSetOf<String>()

    private var pollJob: Job? = null
    private var activePollIntervalMs: Long = 0L
    private var tickJob: Job? = null
    private var resyncJob: Job? = null
    private var clearCommandErrorJob: Job? = null
    private var seekPositionLockUntilMs: Long = 0L

    fun resolveImageUrl(pathOrUrl: String?): String? = repository.resolveImageUrl(pathOrUrl)

    fun setFullScreenActive(active: Boolean) {
        if (fullScreenActive == active) return
        fullScreenActive = active
        reconcileLoops(immediateFetch = active)
    }

    fun setMiniBarOwnerActive(owner: String, active: Boolean) {
        val changed = if (active) miniBarOwners.add(owner) else miniBarOwners.remove(owner)
        if (!changed) return
        reconcileLoops(immediateFetch = active && !fullScreenActive)
    }

    fun refreshNow(userInitiated: Boolean = false) {
        viewModelScope.launch {
            if (userInitiated) {
                _uiState.update { it.copy(refreshing = true, commandError = null) }
            }
            fetchSessionInternal()
            if (userInitiated) {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun notifyPlaybackStarted() {
        scheduleResync(delayMs = 900L)
    }

    fun toggleTimeDisplayMode() {
        _uiState.update { it.copy(showRemainingTime = !it.showRemainingTime) }
    }

    fun onScrubChange(positionSeconds: Float) {
        val maxSec = _uiState.value.durationSeconds.toFloat().coerceAtLeast(0f)
        val clamped = positionSeconds.coerceIn(0f, maxSec)
        _uiState.update {
            it.copy(
                isScrubbing = true,
                scrubPositionSeconds = clamped,
            )
        }
    }

    fun onScrubCommit() {
        val state = _uiState.value
        if (!state.isScrubbing) return
        val targetSec = state.scrubPositionSeconds.toDouble().coerceIn(0.0, state.durationSeconds.coerceAtLeast(0.0))
        seekPositionLockUntilMs = SystemClock.elapsedRealtime() + SEEK_LOCK_MS
        _uiState.update {
            it.copy(
                isScrubbing = false,
                interpolatedPositionSeconds = targetSec,
            )
        }
        sendCommand(
            request = TvCommandRequest(
                sessionId = state.session?.sessionId,
                command = "Seek",
                positionSeconds = targetSec,
            ),
            resyncDelayMs = 600L,
        )
    }

    fun togglePlayPause() {
        val state = _uiState.value
        val cur = state.session ?: return
        _uiState.update {
            it.copy(session = cur.copy(isPaused = !cur.isPaused))
        }
        sendCommand(
            request = TvCommandRequest(
                sessionId = cur.sessionId,
                command = "PlayPause",
            ),
            resyncDelayMs = 450L,
        )
    }

    fun stop() {
        val sid = _uiState.value.session?.sessionId
        sendCommand(
            request = TvCommandRequest(sessionId = sid, command = "Stop"),
            resyncDelayMs = 500L,
        )
    }

    fun nextTrack() {
        val sid = _uiState.value.session?.sessionId
        sendCommand(
            request = TvCommandRequest(sessionId = sid, command = "NextTrack"),
            resyncDelayMs = 650L,
        )
    }

    fun previousTrack() {
        val sid = _uiState.value.session?.sessionId
        sendCommand(
            request = TvCommandRequest(sessionId = sid, command = "PreviousTrack"),
            resyncDelayMs = 650L,
        )
    }

    fun seekRelative(deltaSeconds: Double) {
        val state = _uiState.value
        val dur = state.durationSeconds
        val next = (state.displayedPositionSeconds + deltaSeconds).let { raw ->
            if (dur > 0.0) raw.coerceIn(0.0, dur) else raw.coerceAtLeast(0.0)
        }
        seekPositionLockUntilMs = SystemClock.elapsedRealtime() + SEEK_LOCK_MS
        _uiState.update { it.copy(interpolatedPositionSeconds = next) }
        sendCommand(
            request = TvCommandRequest(
                sessionId = state.session?.sessionId,
                command = "SeekRelative",
                deltaSeconds = deltaSeconds,
            ),
            resyncDelayMs = 600L,
            onSuccess = { resp ->
                resp.positionSeconds?.let { serverPos ->
                    _uiState.update { cur ->
                        if (cur.isScrubbing) cur else cur.copy(interpolatedPositionSeconds = serverPos)
                    }
                }
            },
        )
    }

    fun setAudioStream(index: Int) {
        val cur = _uiState.value.session ?: return
        _uiState.update { it.copy(session = cur.copy(audioStreamIndex = index)) }
        sendCommand(
            request = TvCommandRequest(
                sessionId = cur.sessionId,
                command = "SetAudioStreamIndex",
                index = index,
            ),
            resyncDelayMs = 550L,
        )
    }

    fun setSubtitleStream(index: Int) {
        val cur = _uiState.value.session ?: return
        _uiState.update { it.copy(session = cur.copy(subtitleStreamIndex = index)) }
        sendCommand(
            request = TvCommandRequest(
                sessionId = cur.sessionId,
                command = "SetSubtitleStreamIndex",
                index = index,
            ),
            resyncDelayMs = 550L,
        )
    }

    fun volumeUp() {
        val sid = _uiState.value.session?.sessionId
        sendCommand(
            request = TvCommandRequest(sessionId = sid, command = "VolumeUp"),
            resyncDelayMs = 450L,
        )
    }

    fun volumeDown() {
        val sid = _uiState.value.session?.sessionId
        sendCommand(
            request = TvCommandRequest(sessionId = sid, command = "VolumeDown"),
            resyncDelayMs = 450L,
        )
    }

    fun toggleMute() {
        val cur = _uiState.value.session
        if (cur != null) {
            _uiState.update { it.copy(session = cur.copy(isMuted = !cur.isMuted)) }
        }
        sendCommand(
            request = TvCommandRequest(sessionId = cur?.sessionId, command = "ToggleMute"),
            resyncDelayMs = 450L,
        )
    }

    private fun sendCommand(
        request: TvCommandRequest,
        resyncDelayMs: Long = 500L,
        onSuccess: ((TvCommandResponse) -> Unit)? = null,
    ) {
        clearCommandErrorJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(commandBusy = true, commandError = null) }
            when (val res = repository.sendCommand(request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(commandBusy = false, commandError = null) }
                    onSuccess?.invoke(res.data)
                    scheduleResync(resyncDelayMs)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(commandBusy = false, commandError = res.message) }
                    scheduleResync(resyncDelayMs)
                    clearCommandErrorJob = viewModelScope.launch {
                        delay(4_500L)
                        _uiState.update { it.copy(commandError = null) }
                    }
                }
            }
        }
    }

    private fun scheduleResync(delayMs: Long) {
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            delay(delayMs)
            fetchSessionInternal()
        }
    }

    private fun reconcileLoops(immediateFetch: Boolean) {
        val desiredIntervalMs = when {
            fullScreenActive -> FULL_SCREEN_POLL_MS
            miniBarOwners.isNotEmpty() -> MINI_BAR_POLL_MS
            else -> 0L
        }

        if (desiredIntervalMs == 0L) {
            pollJob?.cancel()
            pollJob = null
            activePollIntervalMs = 0L
            tickJob?.cancel()
            tickJob = null
            resyncJob?.cancel()
            resyncJob = null
            return
        }

        if (pollJob?.isActive != true || activePollIntervalMs != desiredIntervalMs) {
            pollJob?.cancel()
            activePollIntervalMs = desiredIntervalMs
            pollJob = viewModelScope.launch {
                fetchSessionInternal()
                while (isActive) {
                    delay(desiredIntervalMs)
                    fetchSessionInternal()
                }
            }
        } else if (immediateFetch) {
            viewModelScope.launch { fetchSessionInternal() }
        }

        if (fullScreenActive) {
            if (tickJob?.isActive != true) {
                tickJob = viewModelScope.launch {
                    while (isActive) {
                        delay(LOCAL_TICK_MS)
                        _uiState.update { cur ->
                            if (!cur.isPlaying || cur.isPaused || cur.isScrubbing) {
                                cur
                            } else {
                                val dur = cur.durationSeconds
                                val next = (cur.interpolatedPositionSeconds + 1.0).let { v ->
                                    if (dur > 0.0) v.coerceAtMost(dur) else v
                                }
                                cur.copy(interpolatedPositionSeconds = next)
                            }
                        }
                    }
                }
            }
        } else {
            tickJob?.cancel()
            tickJob = null
        }
    }

    private suspend fun fetchSessionInternal() {
        when (val res = repository.getSession()) {
            is ApiResult.Success -> {
                val data = res.data
                val nowMs = SystemClock.elapsedRealtime()
                _uiState.update { cur ->
                    val keepLocalPos = cur.isScrubbing || nowMs < seekPositionLockUntilMs
                    val nextPos = when {
                        !data.connected || !data.isPlaying -> 0.0
                        keepLocalPos -> cur.interpolatedPositionSeconds
                        else -> data.positionSeconds.coerceAtLeast(0.0)
                    }
                    cur.copy(
                        session = data,
                        loaded = true,
                        error = null,
                        interpolatedPositionSeconds = nextPos,
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { cur ->
                    cur.copy(
                        loaded = true,
                        error = res.message,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        tickJob?.cancel()
        resyncJob?.cancel()
        clearCommandErrorJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val FULL_SCREEN_POLL_MS = 3_000L
        private const val MINI_BAR_POLL_MS = 10_000L
        private const val LOCAL_TICK_MS = 1_000L
        private const val SEEK_LOCK_MS = 1_500L
    }
}
