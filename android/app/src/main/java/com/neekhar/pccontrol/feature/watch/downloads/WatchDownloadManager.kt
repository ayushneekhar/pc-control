package com.neekhar.pccontrol.feature.watch.downloads

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.ApiTransport
import com.neekhar.pccontrol.core.network.ApiTransportManager
import com.neekhar.pccontrol.core.network.PcApiClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class SyncProgressBody(
    val jellyfinItemId: String,
    val mediaSourceId: String,
    val positionTicks: Long,
    val runtimeTicks: Long,
    val event: String = "stop",
    val isPaused: Boolean = false,
)

/**
 * Coordinates offline video + subtitle downloads over Tailscale with HTTP Range pause/resume,
 * local atomic JSON indexing, and offline-to-Jellyfin resume position sync.
 */
class WatchDownloadManager(
    private val context: Context,
    private val api: PcApiClient,
    private val transportManager: ApiTransportManager,
) {
    private val appContext: Context = context.applicationContext

    private val mediaRootDir: File = run {
        val ext = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        File(ext ?: appContext.filesDir, "watch_offline").apply { mkdirs() }
    }

    private val indexFile: File = File(appContext.filesDir, "watch_offline_index.json")

    val store: WatchOfflineStore = WatchOfflineStore(
        indexFile = indexFile,
        mediaRootDir = mediaRootDir,
        json = api.json,
    )

    val items: StateFlow<List<OfflineWatchItem>>
        get() = store.items

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val pausedFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val cancelledFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val subtitleQueueInfo = ConcurrentHashMap<String, List<WatchDownloadSubtitleInfo>>()

    init {
        store.recoverInterruptedOnStartup()
    }

    fun isTailscaleActive(): Boolean = transportManager.resolveTransport() == ApiTransport.TAILSCALE

    fun getItem(jellyfinItemId: String): OfflineWatchItem? = store.get(jellyfinItemId)

    fun findByTmdb(tmdbId: Int, mediaType: String): OfflineWatchItem? = store.findByTmdb(tmdbId, mediaType)

    fun totalStorageBytes(): Long = store.totalStorageBytes()

    fun completedCount(): Int = store.completedCount()

    suspend fun enqueueDownload(
        jellyfinItemId: String? = null,
        tmdbId: Int? = null,
        mediaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): WatchDownloadEnqueueResult {
        val existing = jellyfinItemId?.let { store.get(it) }
            ?: if (tmdbId != null && tmdbId > 0 && !mediaType.isNullOrBlank()) {
                store.findByTmdb(tmdbId, mediaType)
            } else {
                null
            }
        if (existing != null && existing.isCompleted && File(existing.localVideoPath).isFile) {
            return WatchDownloadEnqueueResult.AlreadyDownloaded(existing)
        }

        val params = buildList {
            if (!jellyfinItemId.isNullOrBlank()) {
                add("jellyfinItemId=${URLEncoder.encode(jellyfinItemId, "UTF-8")}")
            }
            if (tmdbId != null && tmdbId > 0) add("tmdbId=$tmdbId")
            if (!mediaType.isNullOrBlank()) add("mediaType=${URLEncoder.encode(mediaType, "UTF-8")}")
            if (season != null && season >= 0) add("season=$season")
            if (episode != null && episode >= 0) add("episode=$episode")
        }.joinToString("&")

        val res = api.getJson<WatchDownloadInfoResponse>(
            pathOrUrl = "/pc/api/watch/download-info?$params",
            timeoutMs = 15_000L,
            forceTailscale = true,
        )
        return when (res) {
            is ApiResult.Error -> {
                if (res.statusCode == 0 || (res.statusCode == 403 && res.rawBody?.contains("tailscaleRequired") == true)) {
                    WatchDownloadEnqueueResult.TailscaleRequired
                } else {
                    WatchDownloadEnqueueResult.Error(res.message)
                }
            }
            is ApiResult.Success -> {
                val info = res.data
                val id = info.jellyfinItemId.trim()
                if (id.isEmpty()) {
                    return WatchDownloadEnqueueResult.Error("Invalid download metadata from server.")
                }
                val already = store.get(id)
                if (already != null && already.isCompleted && File(already.localVideoPath).isFile) {
                    return WatchDownloadEnqueueResult.AlreadyDownloaded(already)
                }

                val itemDir = store.itemDir(id).apply { mkdirs() }
                val safeFile = WatchOfflineHelpers.sanitizeFileName(info.fileName, fallback = "$id.mp4")
                val videoFile = File(itemDir, safeFile)
                val existingBytes = if (videoFile.isFile) videoFile.length() else 0L

                subtitleQueueInfo[id] = info.subtitles
                pausedFlags.remove(id)
                cancelledFlags.remove(id)

                val entry = OfflineWatchItem(
                    jellyfinItemId = id,
                    mediaSourceId = info.mediaSourceId.ifBlank { id },
                    tmdbId = info.tmdbId ?: (tmdbId ?: 0),
                    mediaType = info.mediaType.ifBlank { "movie" },
                    title = info.title.ifBlank { "Untitled" },
                    subtitle = info.subtitle,
                    seriesId = info.seriesId,
                    seasonNumber = info.seasonNumber,
                    episodeNumber = info.episodeNumber,
                    fileName = safeFile,
                    mimeType = info.mimeType.ifBlank { "video/mp4" },
                    downloadUrl = info.downloadUrl,
                    totalBytes = info.sizeBytes.coerceAtLeast(existingBytes),
                    downloadedBytes = existingBytes,
                    speedBytesPerSec = 0L,
                    status = OfflineDownloadStatus.QUEUED,
                    errorMessage = null,
                    localVideoPath = videoFile.absolutePath,
                    localPosterPath = already?.localPosterPath,
                    localStillPath = already?.localStillPath,
                    remotePosterUrl = info.posterUrl,
                    remoteStillUrl = info.stillUrl,
                    subtitles = already?.subtitles.orEmpty(),
                    runtimeTicks = info.runtimeTicks,
                    resumePositionTicks = already?.resumePositionTicks ?: 0L,
                    resumeDirty = already?.resumeDirty ?: false,
                    addedAtMs = already?.addedAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    completedAtMs = null,
                )
                store.upsert(entry)
                startDownloadService()
                WatchDownloadEnqueueResult.Enqueued(entry)
            }
        }
    }

    fun pauseDownload(jellyfinItemId: String) {
        pausedFlags.computeIfAbsent(jellyfinItemId) { AtomicBoolean(false) }.set(true)
        activeCalls.remove(jellyfinItemId)?.cancel()
        store.update(jellyfinItemId) { cur ->
            if (cur.status == OfflineDownloadStatus.DOWNLOADING || cur.status == OfflineDownloadStatus.QUEUED) {
                cur.copy(status = OfflineDownloadStatus.PAUSED, speedBytesPerSec = 0L)
            } else {
                cur
            }
        }
    }

    fun resumeDownload(jellyfinItemId: String): WatchDownloadEnqueueResult {
        val cur = store.get(jellyfinItemId)
            ?: return WatchDownloadEnqueueResult.Error("Download not found")
        if (cur.isCompleted && File(cur.localVideoPath).isFile) {
            return WatchDownloadEnqueueResult.AlreadyDownloaded(cur)
        }
        pausedFlags.remove(jellyfinItemId)
        cancelledFlags.remove(jellyfinItemId)
        val updated = store.update(jellyfinItemId) {
            it.copy(status = OfflineDownloadStatus.QUEUED, errorMessage = null, speedBytesPerSec = 0L)
        } ?: cur
        startDownloadService()
        return WatchDownloadEnqueueResult.Enqueued(updated)
    }

    fun cancelDownload(jellyfinItemId: String) {
        cancelledFlags.computeIfAbsent(jellyfinItemId) { AtomicBoolean(false) }.set(true)
        pausedFlags.remove(jellyfinItemId)
        activeCalls.remove(jellyfinItemId)?.cancel()
        subtitleQueueInfo.remove(jellyfinItemId)
        store.remove(jellyfinItemId)
    }

    fun deleteDownload(jellyfinItemId: String) {
        cancelDownload(jellyfinItemId)
    }

    fun deleteAllDownloads() {
        val currentIds = store.items.value.map { it.jellyfinItemId }
        currentIds.forEach { id ->
            cancelledFlags.computeIfAbsent(id) { AtomicBoolean(false) }.set(true)
            activeCalls.remove(id)?.cancel()
        }
        pausedFlags.clear()
        subtitleQueueInfo.clear()
        store.removeAll()
    }

    fun updateOfflineResume(
        jellyfinItemId: String,
        positionTicks: Long,
        runtimeTicks: Long,
    ) {
        store.updateResumePosition(
            jellyfinItemId = jellyfinItemId,
            positionTicks = positionTicks,
            runtimeTicks = runtimeTicks,
            markDirty = true,
        )
    }

    suspend fun syncDirtyResumePositions() {
        val dirty = store.dirtyResumeItems()
        if (dirty.isEmpty()) return
        for (item in dirty) {
            val res = api.postJson<SyncProgressBody, Unit>(
                pathOrUrl = "/pc/api/watch/progress",
                body = SyncProgressBody(
                    jellyfinItemId = item.jellyfinItemId,
                    mediaSourceId = item.mediaSourceId.ifBlank { item.jellyfinItemId },
                    positionTicks = item.resumePositionTicks,
                    runtimeTicks = item.runtimeTicks,
                    event = "stop",
                    isPaused = false,
                ),
                timeoutMs = 8_000L,
            )
            if (res is ApiResult.Success) {
                store.markResumeSynced(item.jellyfinItemId)
            }
        }
    }

    fun pauseAllActive() {
        store.items.value
            .filter { it.isActive }
            .forEach { pauseDownload(it.jellyfinItemId) }
    }

    private fun startDownloadService() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, WatchDownloadService::class.java)
                    .setAction(WatchDownloadService.ACTION_START),
            )
        }.onFailure { err ->
            Log.w(TAG, "Failed to start WatchDownloadService: ${err.message}")
        }
    }

    /**
     * Blocking sequential download loop invoked on a background worker thread by [WatchDownloadService].
     * Calls [onProgress] periodically with the latest snapshot and [onItemCompleted] when an item finishes.
     */
    internal fun drainQueueBlocking(
        onProgress: (OfflineWatchItem) -> Unit,
        onItemCompleted: (OfflineWatchItem) -> Unit,
    ) {
        while (true) {
            val next = store.nextQueuedItem() ?: break
            val id = next.jellyfinItemId
            if (cancelledFlags[id]?.get() == true) {
                store.remove(id)
                continue
            }
            if (pausedFlags[id]?.get() == true) {
                store.update(id) { it.copy(status = OfflineDownloadStatus.PAUSED, speedBytesPerSec = 0L) }
                continue
            }
            downloadSingleItemBlocking(next, onProgress, onItemCompleted)
        }
    }

    private fun downloadSingleItemBlocking(
        initial: OfflineWatchItem,
        onProgress: (OfflineWatchItem) -> Unit,
        onItemCompleted: (OfflineWatchItem) -> Unit,
    ) {
        val id = initial.jellyfinItemId
        val itemDir = store.itemDir(id).apply { mkdirs() }
        val videoFile = File(initial.localVideoPath.ifBlank { File(itemDir, initial.fileName).absolutePath })
        val startOffset = if (videoFile.isFile) videoFile.length() else 0L

        val activeItem = store.update(id) {
            it.copy(
                status = OfflineDownloadStatus.DOWNLOADING,
                downloadedBytes = startOffset,
                speedBytesPerSec = 0L,
                errorMessage = null,
            )
        } ?: initial
        onProgress(activeItem)

        val base = api.tailscaleBaseUrl.trimEnd('/')
        val fullUrl = resolveTailscaleUrl(base, initial.downloadUrl)
        if (fullUrl.isBlank()) {
            store.update(id) {
                it.copy(
                    status = OfflineDownloadStatus.FAILED,
                    errorMessage = "Missing download URL",
                    speedBytesPerSec = 0L,
                )
            }
            return
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = downloadClient,
            url = fullUrl,
            userAgent = PcApiClient.USER_AGENT,
            videoFile = videoFile,
            initialTotalBytes = initial.totalBytes,
            isCancelled = { cancelledFlags[id]?.get() == true },
            isPaused = { pausedFlags[id]?.get() == true },
            onCallCreated = { call -> activeCalls[id] = call },
            onCallFinished = { activeCalls.remove(id) },
            onProgress = { downloadedBytes, totalBytes, speedBps, persist ->
                val snap = store.update(id, persist = persist) {
                    it.copy(
                        status = OfflineDownloadStatus.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = maxOf(it.totalBytes, totalBytes),
                        speedBytesPerSec = speedBps,
                        errorMessage = null,
                    )
                }
                if (snap != null) onProgress(snap)
            },
            clockMs = { SystemClock.elapsedRealtime() },
        )

        when (result) {
            is WatchDownloadTransferResult.Completed -> {
                finalizeCompletedItem(id, itemDir, videoFile, result.totalBytes, onItemCompleted)
            }
            is WatchDownloadTransferResult.Cancelled -> {
                store.remove(id)
            }
            is WatchDownloadTransferResult.Paused -> {
                store.update(id) {
                    it.copy(
                        status = OfflineDownloadStatus.PAUSED,
                        downloadedBytes = result.downloadedBytes,
                        totalBytes = maxOf(it.totalBytes, result.totalBytes),
                        speedBytesPerSec = 0L,
                    )
                }
            }
            is WatchDownloadTransferResult.TailscaleRequired -> {
                store.update(id) {
                    it.copy(
                        status = OfflineDownloadStatus.PAUSED,
                        downloadedBytes = result.downloadedBytes,
                        totalBytes = maxOf(it.totalBytes, result.totalBytes),
                        speedBytesPerSec = 0L,
                        errorMessage = "Connect to Tailscale to download video",
                    )
                }
            }
            is WatchDownloadTransferResult.Failed -> {
                store.update(id) {
                    it.copy(
                        status = OfflineDownloadStatus.FAILED,
                        downloadedBytes = result.downloadedBytes,
                        totalBytes = maxOf(it.totalBytes, result.totalBytes),
                        speedBytesPerSec = 0L,
                        errorMessage = result.errorMessage,
                    )
                }
            }
        }
    }

    private fun finalizeCompletedItem(
        id: String,
        itemDir: File,
        videoFile: File,
        writtenBytes: Long,
        onItemCompleted: (OfflineWatchItem) -> Unit,
    ) {
        val base = api.tailscaleBaseUrl.trimEnd('/')
        val current = store.get(id) ?: return

        val posterPath = downloadSidecarBestEffort(
            url = resolveTailscaleUrl(base, current.remotePosterUrl),
            target = File(itemDir, "poster.jpg"),
        )
        val stillPath = downloadSidecarBestEffort(
            url = resolveTailscaleUrl(base, current.remoteStillUrl),
            target = File(itemDir, "still.jpg"),
        )

        val subsInfo = subtitleQueueInfo.remove(id).orEmpty()
        val savedSubs = mutableListOf<OfflineSubtitleTrack>()
        for (sub in subsInfo) {
            val safeSubName = WatchOfflineHelpers.sanitizeFileName(
                sub.fileName.ifBlank { "sub_${sub.index}.vtt" },
                fallback = "sub_${sub.index}.vtt",
            )
            val subUrl = resolveTailscaleUrl(base, sub.downloadUrl)
            val savedPath = downloadSidecarBestEffort(subUrl, File(itemDir, safeSubName))
            if (savedPath != null) {
                savedSubs.add(
                    OfflineSubtitleTrack(
                        index = sub.index,
                        language = sub.language,
                        displayTitle = sub.displayTitle,
                        format = sub.format.ifBlank { "vtt" },
                        localFileName = safeSubName,
                        isDefault = sub.isDefault,
                        isForced = sub.isForced,
                    ),
                )
            }
        }

        val finalSize = videoFile.length().coerceAtLeast(writtenBytes)
        val done = store.update(id, persist = true) {
            it.copy(
                status = OfflineDownloadStatus.COMPLETED,
                downloadedBytes = finalSize,
                totalBytes = maxOf(it.totalBytes, finalSize),
                speedBytesPerSec = 0L,
                errorMessage = null,
                localVideoPath = videoFile.absolutePath,
                localPosterPath = posterPath ?: it.localPosterPath,
                localStillPath = stillPath ?: it.localStillPath,
                subtitles = if (savedSubs.isNotEmpty()) savedSubs else it.subtitles,
                completedAtMs = System.currentTimeMillis(),
            )
        }
        if (done != null) {
            onItemCompleted(done)
        }
    }

    private fun downloadSidecarBestEffort(url: String, target: File): String? {
        if (url.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", PcApiClient.USER_AGENT)
                .build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                FileOutputStream(target).use { out ->
                    body.byteStream().copyTo(out)
                }
                if (target.isFile && target.length() > 0L) target.absolutePath else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTailscaleUrl(base: String, pathOrUrl: String?): String {
        val raw = pathOrUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val norm = if (raw.startsWith("/")) raw else "/$raw"
        return "$base$norm"
    }

    companion object {
        private const val TAG = "WatchDownloadMgr"
    }
}
