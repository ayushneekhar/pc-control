package com.neekhar.pccontrol.feature.watch.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class WatchOfflineIndexPayload(
    val version: Int = 1,
    val items: List<OfflineWatchItem> = emptyList(),
)

/**
 * Atomic file-backed index for offline Watch downloads & local resume positions.
 * Pure JVM (no Android framework dependencies) so it can be unit-tested directly.
 */
class WatchOfflineStore(
    private val indexFile: File,
    val mediaRootDir: File,
    private val json: Json = DefaultStoreJson,
) {
    private val lock = Any()
    private val _items = MutableStateFlow<List<OfflineWatchItem>>(emptyList())
    val items: StateFlow<List<OfflineWatchItem>> = _items.asStateFlow()

    init {
        loadFromDisk()
    }

    fun itemDir(jellyfinItemId: String): File = File(mediaRootDir, jellyfinItemId)

    fun get(jellyfinItemId: String): OfflineWatchItem? =
        synchronized(lock) { _items.value.firstOrNull { it.jellyfinItemId == jellyfinItemId } }

    fun findByTmdb(tmdbId: Int, mediaType: String): OfflineWatchItem? {
        if (tmdbId <= 0) return null
        val norm = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("episode", ignoreCase = true)) {
            "episode"
        } else {
            "movie"
        }
        return synchronized(lock) {
            _items.value.firstOrNull { it.tmdbId == tmdbId && it.mediaType == norm }
        }
    }

    fun upsert(item: OfflineWatchItem): OfflineWatchItem {
        synchronized(lock) {
            val list = _items.value.toMutableList()
            val idx = list.indexOfFirst { it.jellyfinItemId == item.jellyfinItemId }
            if (idx >= 0) {
                list[idx] = item
            } else {
                list.add(0, item)
            }
            val sorted = sortItems(list)
            _items.value = sorted
            persistLocked(sorted)
            return item
        }
    }

    fun update(
        jellyfinItemId: String,
        persist: Boolean = true,
        transform: (OfflineWatchItem) -> OfflineWatchItem,
    ): OfflineWatchItem? {
        synchronized(lock) {
            val list = _items.value.toMutableList()
            val idx = list.indexOfFirst { it.jellyfinItemId == jellyfinItemId }
            if (idx < 0) return null
            val updated = transform(list[idx])
            list[idx] = updated
            val sorted = sortItems(list)
            _items.value = sorted
            if (persist) {
                persistLocked(sorted)
            }
            return updated
        }
    }

    fun updateResumePosition(
        jellyfinItemId: String,
        positionTicks: Long,
        runtimeTicks: Long? = null,
        markDirty: Boolean = true,
    ): OfflineWatchItem? = update(jellyfinItemId, persist = true) { cur ->
        val safePos = positionTicks.coerceAtLeast(0L)
        val effRuntime = (runtimeTicks ?: cur.runtimeTicks).coerceAtLeast(0L)
        cur.copy(
            resumePositionTicks = safePos,
            runtimeTicks = if (effRuntime > 0L) effRuntime else cur.runtimeTicks,
            resumeDirty = markDirty,
        )
    }

    fun markResumeSynced(jellyfinItemId: String) {
        update(jellyfinItemId, persist = true) { cur ->
            if (cur.resumeDirty) cur.copy(resumeDirty = false) else cur
        }
    }

    fun dirtyResumeItems(): List<OfflineWatchItem> =
        synchronized(lock) { _items.value.filter { it.resumeDirty && it.resumePositionTicks >= 0L } }

    fun remove(jellyfinItemId: String): Boolean {
        synchronized(lock) {
            val prev = _items.value
            val next = prev.filterNot { it.jellyfinItemId == jellyfinItemId }
            val removed = next.size != prev.size
            _items.value = next
            persistLocked(next)
            runCatching { itemDir(jellyfinItemId).deleteRecursively() }
            return removed
        }
    }

    fun removeAll() {
        synchronized(lock) {
            _items.value = emptyList()
            persistLocked(emptyList())
            runCatching {
                if (mediaRootDir.exists()) {
                    mediaRootDir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    fun totalStorageBytes(): Long = synchronized(lock) {
        _items.value.sumOf { item ->
            val dir = itemDir(item.jellyfinItemId)
            val diskSize = computeDirBytes(dir)
            if (diskSize > 0L) diskSize else item.downloadedBytes.coerceAtLeast(0L)
        }
    }

    fun completedCount(): Int = synchronized(lock) {
        _items.value.count { it.status == OfflineDownloadStatus.COMPLETED }
    }

    fun nextQueuedItem(): OfflineWatchItem? = synchronized(lock) {
        _items.value.lastOrNull { it.status == OfflineDownloadStatus.QUEUED }
    }

    fun recoverInterruptedOnStartup() {
        synchronized(lock) {
            var changed = false
            val fixed = _items.value.map { item ->
                if (item.status == OfflineDownloadStatus.DOWNLOADING) {
                    changed = true
                    item.copy(status = OfflineDownloadStatus.PAUSED, speedBytesPerSec = 0L)
                } else {
                    item
                }
            }
            if (changed) {
                _items.value = fixed
                persistLocked(fixed)
            }
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!indexFile.isFile) {
                _items.value = emptyList()
                return
            }
            val parsed = runCatching {
                val raw = indexFile.readText(Charsets.UTF_8)
                json.decodeFromString(WatchOfflineIndexPayload.serializer(), raw).items
            }.getOrDefault(emptyList())
            // Verify completed files still exist on disk
            val validated = parsed.mapNotNull { item ->
                if (item.status == OfflineDownloadStatus.COMPLETED) {
                    val f = File(item.localVideoPath)
                    if (f.isFile && f.length() > 0L) {
                        item.copy(downloadedBytes = f.length(), totalBytes = maxOf(item.totalBytes, f.length()))
                    } else {
                        null
                    }
                } else {
                    item
                }
            }
            _items.value = sortItems(validated)
        }
    }

    private fun persistLocked(items: List<OfflineWatchItem>) {
        runCatching {
            indexFile.parentFile?.mkdirs()
            val payload = json.encodeToString(
                WatchOfflineIndexPayload.serializer(),
                WatchOfflineIndexPayload(version = 1, items = items),
            )
            val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(indexFile)) {
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun sortItems(items: List<OfflineWatchItem>): List<OfflineWatchItem> {
        return items.sortedWith(
            compareBy<OfflineWatchItem>(
                {
                    when (it.status) {
                        OfflineDownloadStatus.DOWNLOADING -> 0
                        OfflineDownloadStatus.QUEUED -> 1
                        OfflineDownloadStatus.PAUSED -> 2
                        OfflineDownloadStatus.FAILED -> 3
                        OfflineDownloadStatus.COMPLETED -> 4
                    }
                },
            ).thenByDescending { it.completedAtMs ?: it.addedAtMs },
        )
    }

    private fun computeDirBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    companion object {
        val DefaultStoreJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
