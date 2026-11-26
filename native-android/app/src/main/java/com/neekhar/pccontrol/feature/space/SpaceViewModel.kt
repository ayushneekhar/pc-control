package com.neekhar.pccontrol.feature.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neekhar.pccontrol.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SpaceConfirmAction {
    DELETE_LOCAL,
    OFFLOAD,
    RESTORE,
    DELETE_DRIVE,
}

enum class SpaceNoticeTone {
    OK,
    WARN,
    BAD,
}

data class SpaceNotice(
    val tone: SpaceNoticeTone,
    val title: String,
    val body: String? = null,
)

data class SpaceUiState(
    val tab: SpaceTab = SpaceTab.MOVIES,
    val localData: SpaceResponse? = null,
    val driveData: DriveStatusResponse? = null,
    val jobs: List<TransferJob> = emptyList(),
    val sseConnected: Boolean = false,
    val isLoadingLocal: Boolean = false,
    val isLoadingDrive: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val driveError: String? = null,
    val notice: SpaceNotice? = null,
    val freeFeedback: SpaceFreeResponse? = null,
    val driveDeleteFeedback: DriveDeleteResponse? = null,
    val query: String = "",
    val localStatusChip: LocalStatusChip = LocalStatusChip.ALL,
    val driveKindFilter: SpaceFilter = SpaceFilter.ALL,
    val sort: SpaceSort = SpaceSort.SIZE,
    val selectedLocalIds: Set<String> = emptySet(),
    val selectedDrivePaths: Set<String> = emptySet(),
    val confirmAction: SpaceConfirmAction? = null,
    val isBusyAction: Boolean = false,
    val cancellingJobId: String? = null,
) {
    val activeJobs: List<TransferJob>
        get() = activeTransferJobs(jobs)

    val allCandidates: List<SpaceCandidate>
        get() = localData?.candidates.orEmpty()

    val allDriveItems: List<DriveItem>
        get() = driveData?.items.orEmpty()

    val activeLocalJobs: Map<String, TransferJob>
        get() = activeOffloadJobsByLocalId(allCandidates, activeJobs)

    val lockedLocalIds: Set<String>
        get() = activeLocalJobs.keys

    val activeDriveJobs: Map<String, TransferJob>
        get() = activeRestoreJobsByDrivePath(allDriveItems, activeJobs)

    val lockedDrivePaths: Set<String>
        get() = activeDriveJobs.keys

    val effectiveSelectedLocalIds: Set<String>
        get() = pruneDisabledSelection(selectedLocalIds, lockedLocalIds)

    val effectiveSelectedDrivePaths: Set<String>
        get() = pruneDisabledSelection(selectedDrivePaths, lockedDrivePaths)

    val visibleLocal: List<SpaceCandidate>
        get() {
            val kindFilter = when (tab) {
                SpaceTab.MOVIES -> SpaceFilter.MOVIE
                SpaceTab.TV_SHOWS -> SpaceFilter.SEASON
                SpaceTab.OFFLOADED -> SpaceFilter.ALL
            }
            return filterAndSortCandidates(
                items = allCandidates,
                filter = kindFilter,
                sort = sort,
                query = query,
                statusChip = localStatusChip,
            )
        }

    val visibleDrive: List<DriveItem>
        get() = filterAndSortDriveItems(
            items = allDriveItems,
            filter = driveKindFilter,
            sort = sort,
            query = query,
        )

    val localSelection: LocalSelectionSummary
        get() = summarizeSelection(allCandidates, effectiveSelectedLocalIds)

    val driveSelection: DriveSelectionSummary
        get() = summarizeDriveSelection(allDriveItems, effectiveSelectedDrivePaths)

    val visibleSelection: VisibleSelectionState
        get() = if (tab == SpaceTab.OFFLOADED) {
            selectionStateForVisible(
                visibleKeys = visibleDrive.map { it.relPath },
                selectedKeys = effectiveSelectedDrivePaths,
                disabledKeys = lockedDrivePaths,
            )
        } else {
            selectionStateForVisible(
                visibleKeys = visibleLocal.map { it.id },
                selectedKeys = effectiveSelectedLocalIds,
                disabledKeys = lockedLocalIds,
            )
        }

    val visibleSelectableTotalBytes: Long
        get() = if (tab == SpaceTab.OFFLOADED) {
            visibleDrive
                .filter { it.relPath !in lockedDrivePaths }
                .sumOf { it.sizeBytes }
        } else {
            visibleLocal
                .filter { it.id !in lockedLocalIds }
                .sumOf { it.sizeBytes }
        }

    val hasSelection: Boolean
        get() = if (tab == SpaceTab.OFFLOADED) driveSelection.count > 0 else localSelection.count > 0

    val upstreamErrorBanner: String?
        get() = formatUpstreamErrors(
            if (tab == SpaceTab.OFFLOADED) driveData?.errors else localData?.errors,
        )

    val driveExplicitlyUnconfigured: Boolean
        get() = driveData != null && !driveData.configured
}

class SpaceViewModel(
    private val repository: SpaceRepository,
) : ViewModel() {

    private val initialLocal: SpaceResponse? =
        repository.getCachedSpace(SpaceScope.ALL) ?: repository.getCachedSpace(SpaceScope.WATCHED)
    private val initialDrive: DriveStatusResponse? = repository.getCachedDrive()

    private val _uiState = MutableStateFlow(
        SpaceUiState(
            localData = initialLocal,
            driveData = initialDrive,
            jobs = initialDrive?.jobs.orEmpty(),
        ),
    )
    val uiState: StateFlow<SpaceUiState> = _uiState.asStateFlow()

    private var sseJob: Job? = null
    private var fallbackPollJob: Job? = null
    private var jobsSeq: Long = 0L
    private var prevJobStatuses: Map<String, String> =
        initialDrive?.jobs?.associate { it.id to it.status }.orEmpty()

    init {
        loadAll(forceDrive = false)
    }

    fun startStream() {
        if (sseJob?.isActive == true) return
        sseJob = viewModelScope.launch {
            repository.streamSpaceJobs()
                .catch {
                    _uiState.update { state -> state.copy(sseConnected = false) }
                }
                .collect { update ->
                    when (update) {
                        is SpaceJobsStreamUpdate.Connected -> {
                            _uiState.update { state -> state.copy(sseConnected = true) }
                        }
                        is SpaceJobsStreamUpdate.Disconnected -> {
                            _uiState.update { state -> state.copy(sseConnected = false) }
                        }
                        is SpaceJobsStreamUpdate.Jobs -> {
                            if (!_uiState.value.sseConnected) {
                                _uiState.update { state -> state.copy(sseConnected = true) }
                            }
                            handleJobsUpdate(update.jobs)
                        }
                    }
                }
            _uiState.update { state -> state.copy(sseConnected = false) }
        }
        ensureFallbackPolling()
    }

    fun stopStream() {
        sseJob?.cancel()
        sseJob = null
        fallbackPollJob?.cancel()
        fallbackPollJob = null
        _uiState.update { it.copy(sseConnected = false) }
    }

    private fun ensureFallbackPolling() {
        if (fallbackPollJob?.isActive == true) return
        fallbackPollJob = viewModelScope.launch {
            while (isActive) {
                delay(FALLBACK_POLL_MS)
                val state = _uiState.value
                if (!state.sseConnected && state.activeJobs.isNotEmpty()) {
                    loadDrive(forceRefresh = false)
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                loadAllSuspend(forceDrive = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadAll(forceDrive: Boolean = false) {
        viewModelScope.launch {
            loadAllSuspend(forceDrive)
        }
    }

    private suspend fun loadAllSuspend(forceDrive: Boolean) = coroutineScope {
        val localDeferred = async { loadLocalSuspend() }
        val driveDeferred = async { loadDriveSuspend(forceRefresh = forceDrive) }
        localDeferred.await()
        driveDeferred.await()
    }

    private fun loadLocal() {
        viewModelScope.launch { loadLocalSuspend() }
    }

    private suspend fun loadLocalSuspend() {
        _uiState.update { it.copy(isLoadingLocal = it.localData == null) }
        when (val res = repository.getSpace(SpaceScope.ALL)) {
            is ApiResult.Success -> {
                val data = res.data
                val validIds = data.candidates.mapTo(HashSet()) { it.id }
                _uiState.update { state ->
                    val pruned = state.selectedLocalIds.filterTo(LinkedHashSet()) { it in validIds }
                    state.copy(
                        localData = data,
                        isLoadingLocal = false,
                        error = null,
                        selectedLocalIds = if (pruned.size == state.selectedLocalIds.size) {
                            state.selectedLocalIds
                        } else {
                            pruned
                        },
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { state ->
                    state.copy(
                        isLoadingLocal = false,
                        error = res.message,
                    )
                }
            }
        }
    }

    private fun loadDrive(forceRefresh: Boolean = false) {
        viewModelScope.launch { loadDriveSuspend(forceRefresh) }
    }

    private suspend fun loadDriveSuspend(forceRefresh: Boolean) {
        val reqSeq = jobsSeq
        _uiState.update { it.copy(isLoadingDrive = it.driveData == null) }
        when (val res = repository.getSpaceDrive(refresh = forceRefresh)) {
            is ApiResult.Success -> {
                val data = res.data
                val validPaths = data.items.mapTo(HashSet()) { it.relPath }
                val shouldUpdateJobs = jobsSeq == reqSeq
                if (shouldUpdateJobs) {
                    jobsSeq += 1
                    prevJobStatuses = data.jobs.associate { it.id to it.status }
                }
                _uiState.update { state ->
                    val nextJobs = if (shouldUpdateJobs) data.jobs else state.jobs
                    val prunedPaths = state.selectedDrivePaths.filterTo(LinkedHashSet()) { it in validPaths }
                    val lockedLocal = activeOffloadJobsByLocalId(state.allCandidates, nextJobs).keys
                    val lockedDrive = activeRestoreJobsByDrivePath(data.items, nextJobs).keys
                    state.copy(
                        driveData = data,
                        jobs = nextJobs,
                        isLoadingDrive = false,
                        driveError = null,
                        selectedLocalIds = pruneDisabledSelection(state.selectedLocalIds, lockedLocal),
                        selectedDrivePaths = pruneDisabledSelection(prunedPaths, lockedDrive),
                    )
                }
            }
            is ApiResult.Error -> {
                _uiState.update { state ->
                    state.copy(
                        isLoadingDrive = false,
                        driveError = res.message,
                    )
                }
            }
        }
    }

    private fun handleJobsUpdate(nextJobs: List<TransferJob>) {
        var anyFinished = false
        var latestFailed: TransferJob? = null
        for (job in nextJobs) {
            val prev = prevJobStatuses[job.id]
            if (prev != null && prev != job.status) {
                if (job.status == "done") anyFinished = true
                if (job.status == "failed") latestFailed = job
            }
        }
        prevJobStatuses = nextJobs.associate { it.id to it.status }
        jobsSeq += 1

        _uiState.update { state ->
            val lockedLocal = activeOffloadJobsByLocalId(state.allCandidates, nextJobs).keys
            val lockedDrive = activeRestoreJobsByDrivePath(state.allDriveItems, nextJobs).keys
            val nextNotice = if (latestFailed != null) {
                SpaceNotice(
                    tone = SpaceNoticeTone.BAD,
                    title = "${if (latestFailed.isRestore) "Restore" else "Offload"} failed: ${latestFailed.title}",
                    body = latestFailed.error?.takeIf { it.isNotBlank() } ?: "Original files were kept.",
                )
            } else {
                state.notice
            }
            state.copy(
                jobs = nextJobs,
                selectedLocalIds = pruneDisabledSelection(state.selectedLocalIds, lockedLocal),
                selectedDrivePaths = pruneDisabledSelection(state.selectedDrivePaths, lockedDrive),
                notice = nextNotice,
            )
        }

        if (anyFinished) {
            loadDrive(forceRefresh = true)
            loadLocal()
        }
    }

    fun selectTab(tab: SpaceTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update {
            it.copy(
                tab = tab,
                selectedLocalIds = emptySet(),
                selectedDrivePaths = emptySet(),
            )
        }
        if (tab == SpaceTab.OFFLOADED) {
            loadDrive(forceRefresh = false)
        } else if (_uiState.value.localData == null) {
            loadLocal()
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun selectLocalStatusChip(chip: LocalStatusChip) {
        _uiState.update { it.copy(localStatusChip = chip) }
    }

    fun selectDriveKindFilter(filter: SpaceFilter) {
        _uiState.update { it.copy(driveKindFilter = filter) }
    }

    fun selectSort(sort: SpaceSort) {
        _uiState.update { it.copy(sort = sort) }
    }

    fun cycleSort() {
        _uiState.update { state ->
            val next = when (state.sort) {
                SpaceSort.SIZE -> SpaceSort.TITLE
                SpaceSort.TITLE -> SpaceSort.WATCHED
                SpaceSort.WATCHED -> SpaceSort.SIZE
            }
            state.copy(sort = next)
        }
    }

    fun toggleLocalItem(id: String) {
        val state = _uiState.value
        if (id in state.lockedLocalIds) return
        _uiState.update { current ->
            val next = LinkedHashSet(current.selectedLocalIds)
            if (!next.add(id)) next.remove(id)
            current.copy(selectedLocalIds = next)
        }
    }

    fun toggleDriveItem(relPath: String) {
        val state = _uiState.value
        if (relPath in state.lockedDrivePaths) return
        _uiState.update { current ->
            val next = LinkedHashSet(current.selectedDrivePaths)
            if (!next.add(relPath)) next.remove(relPath)
            current.copy(selectedDrivePaths = next)
        }
    }

    fun toggleSelectAllVisible() {
        _uiState.update { state ->
            if (state.tab == SpaceTab.OFFLOADED) {
                val visibleKeys = state.visibleDrive.map { it.relPath }
                state.copy(
                    selectedDrivePaths = toggleSelectAllVisible(
                        visibleKeys = visibleKeys,
                        selectedKeys = state.selectedDrivePaths,
                        disabledKeys = state.lockedDrivePaths,
                    ),
                )
            } else {
                val visibleKeys = state.visibleLocal.map { it.id }
                state.copy(
                    selectedLocalIds = toggleSelectAllVisible(
                        visibleKeys = visibleKeys,
                        selectedKeys = state.selectedLocalIds,
                        disabledKeys = state.lockedLocalIds,
                    ),
                )
            }
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedLocalIds = emptySet(),
                selectedDrivePaths = emptySet(),
            )
        }
    }

    fun requestConfirm(action: SpaceConfirmAction) {
        _uiState.update { it.copy(confirmAction = action) }
    }

    fun dismissConfirm() {
        if (_uiState.value.isBusyAction) return
        _uiState.update { it.copy(confirmAction = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun dismissFreeFeedback() {
        _uiState.update { it.copy(freeFeedback = null) }
    }

    fun dismissDriveDeleteFeedback() {
        _uiState.update { it.copy(driveDeleteFeedback = null) }
    }

    fun cancelJob(jobId: String) {
        if (_uiState.value.cancellingJobId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(cancellingJobId = jobId) }
            try {
                when (val res = repository.cancelSpaceJob(jobId)) {
                    is ApiResult.Success -> {
                        val updatedJob = res.data.job
                        if (updatedJob != null) {
                            jobsSeq += 1
                            prevJobStatuses = prevJobStatuses + (updatedJob.id to updatedJob.status)
                            _uiState.update { state ->
                                state.copy(jobs = mergeEnqueuedJobs(state.jobs, listOf(updatedJob)))
                            }
                        }
                        loadDrive(forceRefresh = false)
                    }
                    is ApiResult.Error -> {
                        _uiState.update { it.copy(error = res.message) }
                    }
                }
            } finally {
                _uiState.update { it.copy(cancellingJobId = null) }
            }
        }
    }

    fun confirmPendingAction() {
        val state = _uiState.value
        val action = state.confirmAction ?: return
        if (state.isBusyAction) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusyAction = true, error = null) }
            try {
                when (action) {
                    SpaceConfirmAction.DELETE_LOCAL -> executeDeleteLocal(state.localSelection.selected)
                    SpaceConfirmAction.OFFLOAD -> executeOffload(state.localSelection.selected)
                    SpaceConfirmAction.RESTORE -> executeRestore(state.driveSelection.selected)
                    SpaceConfirmAction.DELETE_DRIVE -> executeDeleteDrive(state.driveSelection.selected)
                }
            } finally {
                _uiState.update { it.copy(isBusyAction = false) }
            }
        }
    }

    private suspend fun executeDeleteLocal(selected: List<SpaceCandidate>) {
        if (selected.isEmpty()) {
            _uiState.update { it.copy(confirmAction = null) }
            return
        }
        when (val res = repository.freeSpace(toFreeRequest(selected))) {
            is ApiResult.Success -> {
                val payload = res.data
                val succeededIds = payload.results.filter { it.ok }.mapTo(HashSet()) { it.id }
                _uiState.update { state ->
                    // Optimistically remove freed items and update HDD stats if returned
                    val updatedLocal = state.localData?.let { current ->
                        current.copy(
                            hdd = payload.hdd ?: current.hdd,
                            candidates = current.candidates.filter { it.id !in succeededIds },
                        )
                    }
                    state.copy(
                        localData = updatedLocal,
                        freeFeedback = payload,
                        confirmAction = null,
                        selectedLocalIds = state.selectedLocalIds.filterTo(LinkedHashSet()) { it !in succeededIds },
                    )
                }
                loadLocalSuspend()
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(confirmAction = null, error = res.message) }
            }
        }
    }

    private suspend fun executeOffload(selected: List<SpaceCandidate>) {
        if (selected.isEmpty()) {
            _uiState.update { it.copy(confirmAction = null) }
            return
        }
        when (val res = repository.offloadSpaceItems(toFreeRequest(selected))) {
            is ApiResult.Success -> {
                val payload = res.data
                if (payload.jobs.isNotEmpty()) {
                    prevJobStatuses = prevJobStatuses + payload.jobs.associate { it.id to it.status }
                    jobsSeq += 1
                }
                val n = payload.jobs.size
                val hasErrs = payload.errors.isNotEmpty()
                val tone = when {
                    hasErrs && n > 0 -> SpaceNoticeTone.WARN
                    hasErrs -> SpaceNoticeTone.BAD
                    else -> SpaceNoticeTone.OK
                }
                val title = when {
                    n > 0 -> "Queued $n item${if (n == 1) "" else "s"} for offload"
                    hasErrs -> "Could not queue offload"
                    else -> "Already queued for offload"
                }
                _uiState.update { state ->
                    state.copy(
                        confirmAction = null,
                        selectedLocalIds = emptySet(),
                        jobs = mergeEnqueuedJobs(state.jobs, payload.jobs),
                        notice = SpaceNotice(
                            tone = tone,
                            title = title,
                            body = if (hasErrs) payload.errors.joinToString("; ") { it.error } else null,
                        ),
                    )
                }
                loadDrive(forceRefresh = false)
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(confirmAction = null, error = res.message) }
            }
        }
    }

    private suspend fun executeRestore(selected: List<DriveItem>) {
        if (selected.isEmpty()) {
            _uiState.update { it.copy(confirmAction = null) }
            return
        }
        when (val res = repository.restoreDriveItems(selected.map { it.relPath })) {
            is ApiResult.Success -> {
                val payload = res.data
                if (payload.jobs.isNotEmpty()) {
                    prevJobStatuses = prevJobStatuses + payload.jobs.associate { it.id to it.status }
                    jobsSeq += 1
                }
                val n = payload.jobs.size
                _uiState.update { state ->
                    state.copy(
                        confirmAction = null,
                        selectedDrivePaths = emptySet(),
                        jobs = mergeEnqueuedJobs(state.jobs, payload.jobs),
                        notice = SpaceNotice(
                            tone = SpaceNoticeTone.OK,
                            title = if (n > 0) {
                                "Queued $n item${if (n == 1) "" else "s"} to restore"
                            } else {
                                "Already queued for restore"
                            },
                        ),
                    )
                }
                loadDrive(forceRefresh = false)
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(confirmAction = null, error = res.message) }
            }
        }
    }

    private suspend fun executeDeleteDrive(selected: List<DriveItem>) {
        if (selected.isEmpty()) {
            _uiState.update { it.copy(confirmAction = null) }
            return
        }
        when (val res = repository.deleteDriveItems(selected.map { it.relPath })) {
            is ApiResult.Success -> {
                val payload = res.data
                val deletedPaths = payload.results.filter { it.ok }.mapTo(HashSet()) { it.relPath }
                _uiState.update { state ->
                    val updatedDrive = state.driveData?.let { current ->
                        current.copy(items = current.items.filter { it.relPath !in deletedPaths })
                    }
                    state.copy(
                        driveData = updatedDrive,
                        confirmAction = null,
                        driveDeleteFeedback = payload,
                        selectedDrivePaths = emptySet(),
                    )
                }
                loadDriveSuspend(forceRefresh = true)
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(confirmAction = null, error = res.message) }
            }
        }
    }

    override fun onCleared() {
        stopStream()
        super.onCleared()
    }

    companion object {
        private const val FALLBACK_POLL_MS = 4_000L
    }
}
