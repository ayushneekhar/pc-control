package com.neekhar.pccontrol.feature.cams

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

data class ReplayTileState(
    val msg: String? = "Loading…",
    val spinning: Boolean = false,
    val label: String = "",
)

data class ReplayUiState(
    val wallTime: Double = 0.0,
    val now: Double = System.currentTimeMillis() / 1000.0,
    val tiles: Map<RecCam, ReplayTileState> = RecCam.ALL.associateWith {
        ReplayTileState(msg = "Loading…", spinning = false, label = it.displayName)
    },
    val sources: Map<RecCam, ReplaySource?> = RecCam.ALL.associateWith { null },
    val playing: Boolean = true,
    val playPerCam: Map<RecCam, Boolean> = RecCam.ALL.associateWith { false },
    val rate: Float = 1f,
    val big: RecCam = RecCam.CAT,
    val span: Double = 3600.0,
    val indices: Map<RecCam, RecIndex> = emptyMap(),
    val sightings: List<Sighting> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val detectorEnabled: Boolean? = null,
    val error: String = "",
    val decoder: String? = null,
)

interface ReplayDataSource {
    fun baseUrl(): String
    suspend fun fetchRecIndex(cam: RecCam): RecIndex
    suspend fun fetchSightings(sinceEpochSec: Long): SightingsResponse
}

/**
 * Pure Kotlin + Coroutines port of `src/screens/cams/replayEngine.ts`.
 *
 * One wall clock (`wallTime`) drives three Media3 HLS players (`cat`, `cat2`, `cat3hd`).
 * While playing, `wallTime` follows the big tile's player (media time -> wall time through
 * `index.json`'s spans); the other two seek whenever they drift beyond `max(1.5, rate * 0.75)` s.
 */
class ReplayEngine(
    private val scope: CoroutineScope,
    private val dataSource: ReplayDataSource,
    private val hevcOk: Boolean,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private data class PlayerSlot(
        var snap: RecIndex? = null,
        var avc: Boolean = false,
        var loading: Boolean = false,
        var gen: Int = 0,
        var prog: ReplayProgress? = null,
        var inGap: Boolean = false,
        var seekAtMs: Long = 0L,
    )

    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()

    private val indices = mutableMapOf<RecCam, RecIndex>()
    private val slots: Map<RecCam, PlayerSlot> = RecCam.ALL.associateWith { PlayerSlot() }
    private val handles = mutableMapOf<RecCam, ReplayPlayerHandle?>()
    private val sources = mutableMapOf<RecCam, ReplaySource?>().apply {
        RecCam.ALL.forEach { put(it, null) }
    }
    private val tiles = mutableMapOf<RecCam, ReplayTileState>().apply {
        RecCam.ALL.forEach { put(it, ReplayTileState(msg = "Loading…", spinning = false, label = it.displayName)) }
    }

    private var wallTime: Double = 0.0
    private var dragging: Boolean = false
    private var playing: Boolean = true
    private var rate: Float = 1f
    private var big: RecCam = RecCam.CAT
    private var span: Double = 3600.0
    private var sightings: List<Sighting> = emptyList()
    private var visits: List<Visit> = emptyList()
    private var detectorEnabled: Boolean? = null
    private var error: String = ""

    private var pendingTarget: SightingTarget? = null
    private var open: Boolean = false
    private var runGen: Int = 0
    private var loopJobs = mutableListOf<Job>()

    // Gesture state
    private var dragStartWallTime: Double = 0.0
    private var dragMoved: Boolean = false
    private var pinchStartDist: Float = 0f
    private var pinchStartSpan: Double = 3600.0
    private var lastPreviewMs: Long = 0L
    private var ribbonWidthPx: Float = 0f
    private var overviewWidthPx: Float = 0f

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (open) return
        open = true
        val currentRun = ++runGen
        loopJobs.forEach { it.cancel() }
        loopJobs.clear()

        val startJob = scope.launch {
            coroutineScope {
                val idxDeferred = async { refreshIndices() }
                val sightDeferred = async { refreshSightings() }
                idxDeferred.await()
                sightDeferred.await()
            }
            if (!open || currentRun != runGen) return@launch

            val applied = applyPendingTarget()
            if (!applied && (wallTime <= 0.0 || wallTime < nowSeconds() - ReplayMath.DAY)) {
                val first = ReplayMath.earliestStart(indices)
                val latest = ReplayMath.latestEnd(indices)
                val endRef = if (latest > 0.0) latest else nowSeconds()
                wallTime = max(endRef - 60.0, if (first.isFinite()) first else 0.0)
            }
            if (!applied) {
                wallTime = ReplayMath.snapSeek(indices, wallTime, nowSeconds(), dir = -1)
            }

            RecCam.ALL.forEach { loadCam(it, wallTime) }
            tick()

            loopJobs += launch {
                while (isActive && open && currentRun == runGen) {
                    delay(250L)
                    tick()
                }
            }
            loopJobs += launch {
                while (isActive && open && currentRun == runGen) {
                    delay(10_000L)
                    refreshIndices()
                }
            }
            loopJobs += launch {
                while (isActive && open && currentRun == runGen) {
                    delay(30_000L)
                    refreshSightings()
                }
            }
        }
        loopJobs += startJob
    }

    fun stop() {
        open = false
        runGen++
        loopJobs.forEach { it.cancel() }
        loopJobs.clear()
        RecCam.ALL.forEach { cam ->
            val p = slots.getValue(cam)
            p.gen++
            p.snap = null
            p.prog = null
            p.loading = false
            sources[cam] = null
        }
        dragging = false
        emit()
    }

    // ---------------------------------------------------------------- data fetch

    private suspend fun fetchIndex(cam: RecCam): RecIndex {
        val fetched = dataSource.fetchRecIndex(cam)
        indices[cam] = fetched
        return fetched
    }

    private suspend fun refreshIndices() = coroutineScope {
        val results = RecCam.ALL.map { cam ->
            async { runCatching { fetchIndex(cam) } }
        }.awaitAll()
        error = if (results.all { it.isFailure }) {
            "Can't reach the recorder (camrec service)."
        } else {
            ""
        }
    }

    private suspend fun refreshSightings() {
        runCatching {
            val since = (nowSeconds() - ReplayMath.DAY).toLong()
            val resp = dataSource.fetchSightings(since)
            val parsed = ReplayMath.parseSightings(resp)
            detectorEnabled = parsed.enabled
            sightings = parsed.sightings
            visits = ReplayMath.visitsOf(parsed.sightings)
        }.onFailure {
            detectorEnabled = null
        }
    }

    // ---------------------------------------------------------------- player callbacks

    fun setHandle(cam: RecCam, handle: ReplayPlayerHandle?) {
        handles[cam] = handle
    }

    fun onProgress(cam: RecCam, progress: ReplayProgress) {
        slots.getValue(cam).prog = progress
    }

    fun onPlayerError(cam: RecCam) {
        val slot = slots.getValue(cam)
        val gen = slot.gen
        scope.launch {
            delay(2000L)
            if (open && gen == slot.gen) {
                loadCam(cam, wallTime)
            }
        }
    }

    private fun loadCam(cam: RecCam, at: Double) {
        val slot = slots.getValue(cam)
        val gen = ++slot.gen
        slot.loading = true
        scope.launch {
            try {
                val fetched = fetchIndex(cam)
                if (gen != slot.gen || !open) return@launch
                slot.snap = if (!fetched.a.isNullOrEmpty() && fetched.spans.isNotEmpty()) fetched else null
                slot.prog = null
                val snap = slot.snap
                if (snap == null) {
                    sources[cam] = null
                    emit()
                    return@launch
                }
                slot.avc = snap.codec.equals("hevc", ignoreCase = true) && !hevcOk
                slot.seekAtMs = nowMillis()
                sources[cam] = ReplaySource(
                    url = ReplayMath.playlistUrl(dataSource.baseUrl(), snap, slot.avc),
                    startSeconds = ReplayMath.startMedia(snap.spans, at),
                    version = gen,
                )
                emit()
            } catch (_: Throwable) {
                if (gen == slot.gen && open) {
                    delay(5000L)
                    if (open && gen == slot.gen) loadCam(cam, wallTime)
                }
            } finally {
                if (gen == slot.gen) slot.loading = false
            }
        }
    }

    private fun seekPlayer(cam: RecCam, mediaSec: Double, exact: Boolean) {
        val slot = slots.getValue(cam)
        slot.seekAtMs = nowMillis()
        slot.prog = slot.prog?.copy(position = mediaSec, seeking = true)
        handles[cam]?.seek(mediaSec, exact)
    }

    private fun jumpCam(cam: RecCam, at: Double, exact: Boolean = true) {
        val slot = slots.getValue(cam)
        val snap = slot.snap ?: return
        val latest = indices[cam]
        val lastSnapEnd = snap.spans.lastOrNull()?.wallEnd
        if (latest != null && latest.b != snap.b && lastSnapEnd != null && at > lastSnapEnd - 1.0) {
            loadCam(cam, at)
            return
        }
        val mapped = ReplayMath.w2m(snap.spans, at)
        if (mapped is WallToMedia.Hit) {
            seekPlayer(cam, mapped.mediaTime, exact)
        }
    }

    fun seekTo(at: Double, dir: Int = 0) {
        wallTime = ReplayMath.snapSeek(indices, at, nowSeconds(), dir)
        RecCam.ALL.forEach { jumpCam(it, wallTime, exact = true) }
        tick()
    }

    private fun applyPendingTarget(): Boolean {
        val target = pendingTarget ?: return false
        val resolved = ReplayMath.sightingSeek(indices, target.cam.id, target.tsMs, nowSeconds())
        if (resolved == null) {
            pendingTarget = null
            return false
        }
        val anySpans = RecCam.ALL.any { (indices[it]?.spans?.size ?: 0) > 0 }
        if (!anySpans) return false
        pendingTarget = null
        big = resolved.cam
        playing = true
        wallTime = resolved.wallTime
        return true
    }

    fun seekSighting(cam: String?, tsMs: Long?): Boolean {
        val target = ReplayMath.parseSightingTarget(cam, tsMs) ?: return false
        pendingTarget = target
        if (!applyPendingTarget()) return true
        RecCam.ALL.forEach { jumpCam(it, wallTime, exact = true) }
        tick()
        return true
    }

    // ---------------------------------------------------------------- clock tick

    private fun isSettled(slot: PlayerSlot): Boolean {
        val prog = slot.prog ?: return false
        return nowMillis() - slot.seekAtMs > 700L && !prog.seeking
    }

    fun tick() {
        if (!open || wallTime <= 0.0) {
            emit()
            return
        }
        var masterCam: RecCam? = null
        if (playing && !dragging) {
            val priority = listOf(big) + RecCam.ALL.filter { it != big }
            masterCam = priority.firstOrNull { cam ->
                val p = slots.getValue(cam)
                p.snap != null &&
                    !p.inGap &&
                    !p.loading &&
                    isSettled(p) &&
                    p.prog?.playing == true &&
                    p.prog?.state == ReplayPlayerState.READY
            }
            val mappedWall = masterCam?.let { cam ->
                val p = slots.getValue(cam)
                ReplayMath.m2w(p.snap!!.spans, p.prog!!.position)
            }
            if (mappedWall != null) {
                wallTime = mappedWall
            } else {
                masterCam = null
                val mappedAll = RecCam.ALL.map { cam ->
                    ReplayMath.w2m(indices[cam]?.spans.orEmpty(), wallTime)
                }
                if (mappedAll.all { it is WallToMedia.Gap }) {
                    val nextTimes = mappedAll.filterIsInstance<WallToMedia.Gap>().mapNotNull { it.next }
                    if (nextTimes.isNotEmpty()) {
                        wallTime = nextTimes.min()
                        RecCam.ALL.forEach { jumpCam(it, wallTime, exact = true) }
                    }
                }
            }
        }

        for (cam in RecCam.ALL) {
            val slot = slots.getValue(cam)
            val latest = indices[cam]
            val label = cam.displayName + if (slot.avc) " · SD" else ""
            val snap = slot.snap
            if (snap == null) {
                if (latest?.a != null && !slot.loading) {
                    loadCam(cam, wallTime)
                }
                tiles[cam] = ReplayTileState(
                    msg = if (slot.loading || latest?.a != null) "Loading…" else "No recordings yet",
                    spinning = false,
                    label = label,
                )
                continue
            }

            if (!slot.loading && ReplayMath.needsReload(snap, latest, wallTime, rate)) {
                loadCam(cam, wallTime)
            }

            when (val r = ReplayMath.w2m(snap.spans, wallTime)) {
                is WallToMedia.Gap -> {
                    slot.inGap = true
                    val sEnd = snap.spans.last().wallEnd
                    val upToDate = r.next == null && wallTime >= sEnd - 0.5
                    tiles[cam] = ReplayTileState(
                        msg = if (upToDate) "Up to date · waiting for footage" else "No recording at this time",
                        spinning = false,
                        label = label,
                    )
                }

                is WallToMedia.Hit -> {
                    slot.inGap = false
                    val driftLimit = max(1.5, rate * 0.75)
                    val prog = slot.prog
                    if (cam != masterCam && !dragging && isSettled(slot) && prog != null && abs(prog.position - r.mediaTime) > driftLimit) {
                        seekPlayer(cam, r.mediaTime, exact = true)
                    }
                    val wantPlay = playing && !dragging
                    val spin = wantPlay && (prog == null || prog.seeking || prog.state == ReplayPlayerState.BUFFERING)
                    tiles[cam] = ReplayTileState(msg = null, spinning = spin, label = label)
                }
            }
        }
        emit()
    }

    private fun emit() {
        val tilesCopy = tiles.toMap()
        val playMap = RecCam.ALL.associateWith { cam ->
            playing && !dragging && tilesCopy[cam]?.msg == null
        }
        _state.value = ReplayUiState(
            wallTime = wallTime,
            now = nowSeconds(),
            tiles = tilesCopy,
            sources = sources.toMap(),
            playing = playing,
            playPerCam = playMap,
            rate = rate,
            big = big,
            span = span,
            indices = indices.toMap(),
            sightings = sightings,
            visits = visits,
            detectorEnabled = detectorEnabled,
            error = error,
            decoder = slots.getValue(big).prog?.decoder?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- controls

    fun togglePlay() {
        playing = !playing
        tick()
    }

    fun setRate(newRate: Float) {
        rate = newRate
        emit()
    }

    fun cycleRate() {
        val idx = ReplayMath.SPEEDS.indexOf(rate)
        rate = ReplayMath.SPEEDS[(idx + 1) % ReplayMath.SPEEDS.size]
        emit()
    }

    fun cycleZoom() {
        val next = ReplayMath.ZOOMS.firstOrNull { it < span - 1.0 } ?: ReplayMath.ZOOMS.first()
        span = next
        emit()
    }

    fun skip(deltaSec: Double) {
        seekTo(wallTime + deltaSec, dir = sign(deltaSec).toInt())
    }

    fun goLive() {
        playing = true
        val end = ReplayMath.latestEnd(indices)
        val ref = if (end > 0.0) end else nowSeconds()
        seekTo(ref - 20.0, dir = -1)
    }

    fun setBig(cam: RecCam) {
        big = cam
        emit()
    }

    fun jumpTo(cam: RecCam, ts: Double) {
        big = cam
        playing = true
        seekTo(ts - 4.0, dir = -1)
    }

    fun prevVisit() {
        val target = visits.lastOrNull { it.start < wallTime - 6.0 } ?: return
        jumpTo(target.cam, target.start)
    }

    fun nextVisit() {
        val target = visits.firstOrNull { it.start > wallTime + 5.0 } ?: return
        jumpTo(target.cam, target.start)
    }

    fun lastSighting() {
        val target = sightings.lastOrNull() ?: return
        jumpTo(target.cam, target.ts)
    }

    suspend fun snapshot(labelBuilder: (Double) -> String): String {
        val handle = handles[big] ?: throw IllegalStateException("Nothing playing")
        return handle.snapshot(labelBuilder(wallTime))
    }

    // ---------------------------------------------------------------- timeline gestures

    private fun clampToWindow(t: Double): Double {
        val end = ReplayMath.latestEnd(indices)
        val hi = if (end > 0.0) end else nowSeconds()
        return ReplayMath.clamp(t, nowSeconds() - ReplayMath.DAY, hi)
    }

    private fun previewSeek() {
        val now = nowMillis()
        if (now - lastPreviewMs < 300L) return
        lastPreviewMs = now
        RecCam.ALL.forEach { jumpCam(it, wallTime, exact = false) }
    }

    private fun endScrub(dir: Int) {
        dragging = false
        seekTo(wallTime, dir = dir)
    }

    fun setRibbonWidth(widthPx: Float) {
        ribbonWidthPx = widthPx
    }

    fun setOverviewWidth(widthPx: Float) {
        overviewWidthPx = widthPx
    }

    fun overviewMove(xPx: Float) {
        dragging = true
        val pad = 8f
        val usable = max(1f, overviewWidthPx - 2f * pad)
        val frac = ReplayMath.clamp(((xPx - pad) / usable).toDouble(), 0.0, 1.0)
        wallTime = clampToWindow(nowSeconds() - ReplayMath.DAY + frac * ReplayMath.DAY)
        previewSeek()
        emit()
    }

    fun overviewEnd() {
        if (dragging) endScrub(0)
    }

    fun ribbonDragStart() {
        dragStartWallTime = wallTime
        dragMoved = false
        pinchStartDist = 0f
    }

    fun ribbonDragDelta(totalDxPx: Float) {
        if (!dragMoved && abs(totalDxPx) > 6f) {
            dragMoved = true
            dragging = true
        }
        if (!dragMoved || ribbonWidthPx <= 0f) return
        wallTime = clampToWindow(dragStartWallTime - (totalDxPx.toDouble() * span) / ribbonWidthPx.toDouble())
        previewSeek()
        emit()
    }

    fun ribbonPinchZoom(zoomFactor: Float) {
        if (zoomFactor <= 0f) return
        span = ReplayMath.clamp(span / zoomFactor.toDouble(), 120.0, ReplayMath.DAY)
        emit()
    }

    fun ribbonTap(xPx: Float) {
        if (ribbonWidthPx <= 0f) return
        val at = wallTime + (xPx - ribbonWidthPx / 2f).toDouble() * (span / ribbonWidthPx.toDouble())
        val prev = wallTime
        wallTime = clampToWindow(at)
        endScrub(sign(at - prev).toInt())
    }

    fun ribbonDragEnd(totalDxPx: Float) {
        if (dragging) {
            endScrub(-sign(totalDxPx).toInt())
        } else {
            emit()
        }
    }
}
