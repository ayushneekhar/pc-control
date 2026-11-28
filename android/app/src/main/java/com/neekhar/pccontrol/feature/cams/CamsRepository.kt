package com.neekhar.pccontrol.feature.cams

import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.network.getOrThrow
import com.neekhar.pccontrol.core.storage.LocalCache
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable
private data class CatCamToggleBody(val enabled: Boolean)

@Serializable
private data class MusicPlayBody(val url: String)

@Serializable
private data class MusicLoopBody(val loop: Boolean)

@Serializable
private data class MusicVolumeBody(val percent: Int)

@Serializable
private data class MusicMuteBody(val muted: Boolean)

@Serializable
private data class PtzPresetsEnvelope(val presets: List<PtzPreset> = emptyList())

/**
 * Network & cache repository for all Cat Cams backend endpoints:
 * - `/pc/api/catcam` (master switch)
 * - `/pc/api/detect/state`, `/pc/api/detect/toggle`, `/pc/api/detect/sightings`, `/pc/api/catdetect/snaps`
 * - `/pc/api/ptz/move`, `/pc/api/ptz/stop`, `/pc/api/ptz/preset`
 * - `/rec/<cam>/index.json` (Tailscale-only HLS replay)
 * - `/pc/api/music/api/...`
 */
class CamsRepository(
    val apiClient: PcApiClient,
    private val cache: LocalCache,
) : ReplayDataSource {

    /** Tailscale base URL for HLS Replay playlists and recordings (`/rec/...`). */
    override fun baseUrl(): String = apiClient.tailscaleBaseUrl

    /** Resolves an authenticated detection snapshot URL + scoped headers for Coil. */
    fun resolveDetectionImage(pathOrUrl: String): Pair<String, Map<String, String>> =
        apiClient.resolveAuthenticatedImage(pathOrUrl)

    // ---------------------------------------------------------------- Cat cam master switch

    fun cachedCatCamState(): CatCamState? = cache.get<CatCamState>(CACHE_CATCAM)

    suspend fun getCatCam(): ApiResult<CatCamState> {
        val result = apiClient.getJson<CatCamState>(
            pathOrUrl = "/pc/api/catcam",
            headers = NO_STORE,
        )
        if (result is ApiResult.Success) {
            cache.put(CACHE_CATCAM, result.data)
        }
        return result
    }

    suspend fun setCatCam(enabled: Boolean): ApiResult<CatCamState> {
        val result = apiClient.postJson<CatCamToggleBody, CatCamState>(
            pathOrUrl = "/pc/api/catcam",
            body = CatCamToggleBody(enabled = enabled),
            headers = NO_STORE,
            timeoutMs = 30_000L,
        )
        if (result is ApiResult.Success) {
            cache.put(CACHE_CATCAM, result.data)
        }
        return result
    }

    // ---------------------------------------------------------------- Detector & alerts

    suspend fun getDetectorState(): ApiResult<DetectorState> = apiClient.getJson(
        pathOrUrl = "/pc/api/detect/state",
        headers = NO_STORE,
        timeoutMs = 6_000L,
    )

    suspend fun toggleDetector(on: Boolean): ApiResult<DetectorState> = apiClient.postJson(
        pathOrUrl = "/pc/api/detect/toggle?on=${if (on) 1 else 0}",
        bodyJson = "{}",
        headers = NO_STORE,
        timeoutMs = 6_000L,
    )

    suspend fun getDetectionSnaps(
        cam: String? = null,
        limit: Int = 300,
    ): ApiResult<DetectionSnapsResponse> {
        val params = buildList {
            if (!cam.isNullOrBlank() && cam != "all") {
                add("cam=${URLEncoder.encode(cam, "UTF-8")}")
            }
            if (limit != 300) {
                add("limit=$limit")
            }
        }
        val qs = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return apiClient.getJson(
            pathOrUrl = "/pc/api/catdetect/snaps$qs",
            headers = NO_STORE,
            timeoutMs = 15_000L,
        )
    }

    // ---------------------------------------------------------------- ReplayDataSource

    override suspend fun fetchRecIndex(cam: RecCam): RecIndex {
        val raw = apiClient.getJson<RawRecIndex>(
            pathOrUrl = "/rec/${cam.id}/index.json",
            headers = NO_STORE,
            timeoutMs = 10_000L,
            forceTailscale = true,
        ).getOrThrow()
        return raw.toRecIndex()
    }

    override suspend fun fetchSightings(sinceEpochSec: Long): SightingsResponse {
        return apiClient.getJson<SightingsResponse>(
            pathOrUrl = "/pc/api/detect/sightings?since=$sinceEpochSec",
            headers = NO_STORE,
            timeoutMs = 10_000L,
        ).getOrThrow()
    }

    // ---------------------------------------------------------------- PTZ (cat3 / cam-ptz)

    suspend fun ptzMove(direction: String): ApiResult<Unit> = apiClient.postJson(
        pathOrUrl = "/pc/api/ptz/move?dir=${URLEncoder.encode(direction, "UTF-8")}",
        bodyJson = "{}",
        headers = NO_STORE,
        timeoutMs = 5_000L,
    )

    suspend fun ptzStop(): ApiResult<Unit> = apiClient.postJson(
        pathOrUrl = "/pc/api/ptz/stop",
        bodyJson = "{}",
        headers = NO_STORE,
        timeoutMs = 5_000L,
    )

    /** Try invoking a server-side PTZ preset if supported by `cam-ptz`. */
    suspend fun ptzInvokePreset(presetId: String): ApiResult<Unit> = apiClient.postJson(
        pathOrUrl = "/pc/api/ptz/preset?name=${URLEncoder.encode(presetId, "UTF-8")}",
        bodyJson = "{}",
        headers = NO_STORE,
        timeoutMs = 6_000L,
    )

    fun loadPtzPresets(): List<PtzPreset> {
        val cached = cache.get<PtzPresetsEnvelope>(CACHE_PTZ_PRESETS)?.presets
        if (!cached.isNullOrEmpty()) return cached
        return DEFAULT_PTZ_PRESETS
    }

    fun savePtzPresets(presets: List<PtzPreset>) {
        cache.put(CACHE_PTZ_PRESETS, PtzPresetsEnvelope(presets))
    }

    // ---------------------------------------------------------------- Music (/pc/api/music/api/*)

    suspend fun getMusicStatus(): ApiResult<MusicStatus> = apiClient.getJson(
        pathOrUrl = "/pc/api/music/api/status",
        headers = NO_STORE,
        timeoutMs = 8_000L,
    )

    suspend fun getMusicRecent(): ApiResult<MusicRecentResponse> = apiClient.getJson(
        pathOrUrl = "/pc/api/music/api/recent",
        headers = NO_STORE,
        timeoutMs = 8_000L,
    )

    suspend fun playMusic(urlOrQuery: String): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/play",
        body = MusicPlayBody(url = urlOrQuery),
        timeoutMs = 70_000L, // yt-dlp resolution can take several seconds
    )

    suspend fun pauseMusic(): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/pause",
        bodyJson = "{}",
        timeoutMs = 10_000L,
    )

    suspend fun resumeMusic(): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/resume",
        bodyJson = "{}",
        timeoutMs = 10_000L,
    )

    suspend fun stopMusic(): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/stop",
        bodyJson = "{}",
        timeoutMs = 10_000L,
    )

    suspend fun setMusicLoop(loop: Boolean): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/loop",
        body = MusicLoopBody(loop = loop),
        timeoutMs = 10_000L,
    )

    suspend fun setMusicVolume(percent: Int): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/volume",
        body = MusicVolumeBody(percent = percent.coerceIn(0, 100)),
        timeoutMs = 10_000L,
    )

    suspend fun setMusicMuted(muted: Boolean): ApiResult<MusicStatus> = apiClient.postJson(
        pathOrUrl = "/pc/api/music/api/mute",
        body = MusicMuteBody(muted = muted),
        timeoutMs = 10_000L,
    )

    companion object {
        private const val CACHE_CATCAM = "catcam:state"
        private const val CACHE_PTZ_PRESETS = "catcam:ptz_presets"
        private val NO_STORE = mapOf("Cache-Control" to "no-store")

        val DEFAULT_PTZ_PRESETS = listOf(
            PtzPreset(
                id = "bowl",
                label = "Food bowl",
                directionSequence = listOf(PtzStep("down", 600L)),
                serverPresetId = "bowl",
            ),
            PtzPreset(
                id = "center",
                label = "Center",
                directionSequence = listOf(PtzStep("up", 450L)),
                serverPresetId = "center",
            ),
            PtzPreset(
                id = "left",
                label = "Left sweep",
                directionSequence = listOf(PtzStep("left", 750L)),
                serverPresetId = "left",
            ),
            PtzPreset(
                id = "right",
                label = "Right sweep",
                directionSequence = listOf(PtzStep("right", 750L)),
                serverPresetId = "right",
            ),
        )
    }
}
