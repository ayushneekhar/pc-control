package com.neekhar.pccontrol.feature.watch

import com.neekhar.pccontrol.feature.watch.downloads.OfflineDownloadStatus
import com.neekhar.pccontrol.feature.watch.downloads.OfflineSubtitleTrack
import com.neekhar.pccontrol.feature.watch.downloads.OfflineWatchItem
import com.neekhar.pccontrol.feature.watch.downloads.WatchOfflineHelpers
import com.neekhar.pccontrol.feature.watch.downloads.WatchOfflineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatchOfflineStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore(): WatchOfflineStore {
        val root = tempFolder.root
        return WatchOfflineStore(
            indexFile = File(root, "watch_offline_index.json"),
            mediaRootDir = File(root, "watch_offline"),
        )
    }

    @Test
    fun `upsert persists atomically and reloads completed items with existing files`() {
        val store1 = createStore()
        val itemDir = store1.itemDir("abc123").apply { mkdirs() }
        val videoFile = File(itemDir, "movie.mp4").apply { writeBytes(ByteArray(2048)) }

        store1.upsert(
            OfflineWatchItem(
                jellyfinItemId = "abc123",
                tmdbId = 466272,
                mediaType = "movie",
                title = "Once Upon a Time",
                fileName = "movie.mp4",
                totalBytes = 2048L,
                downloadedBytes = 2048L,
                status = OfflineDownloadStatus.COMPLETED,
                localVideoPath = videoFile.absolutePath,
                subtitles = listOf(
                    OfflineSubtitleTrack(
                        index = 3,
                        language = "eng",
                        displayTitle = "English",
                        localFileName = "sub_3_eng.vtt",
                    ),
                ),
                runtimeTicks = 96_000_000_000L,
                addedAtMs = 1000L,
                completedAtMs = 2000L,
            ),
        )

        val store2 = createStore()
        val loaded = store2.get("abc123")
        assertNotNull(loaded)
        assertEquals("Once Upon a Time", loaded!!.title)
        assertEquals(OfflineDownloadStatus.COMPLETED, loaded.status)
        assertEquals(2048L, loaded.downloadedBytes)
        assertEquals(1, loaded.subtitles.size)
        assertEquals(1, store2.completedCount())
        assertEquals(2048L, store2.totalStorageBytes())
        assertNotNull(store2.findByTmdb(466272, "movie"))
    }

    @Test
    fun `resume position marks dirty and clears after sync`() {
        val store = createStore()
        val itemDir = store.itemDir("ep1").apply { mkdirs() }
        val videoFile = File(itemDir, "ep1.mkv").apply { writeBytes(ByteArray(1024)) }

        store.upsert(
            OfflineWatchItem(
                jellyfinItemId = "ep1",
                tmdbId = 95480,
                mediaType = "episode",
                title = "Slow Horses",
                subtitle = "S1 · E1",
                status = OfflineDownloadStatus.COMPLETED,
                localVideoPath = videoFile.absolutePath,
                runtimeTicks = 30_000_000_000L,
            ),
        )

        store.updateResumePosition("ep1", positionTicks = 15_000_000_000L, runtimeTicks = 30_000_000_000L)
        val dirty = store.dirtyResumeItems()
        assertEquals(1, dirty.size)
        assertEquals(15_000_000_000L, dirty[0].resumePositionTicks)
        assertEquals(0.5f, dirty[0].resumeProgressFraction, 0.001f)

        store.markResumeSynced("ep1")
        assertTrue(store.dirtyResumeItems().isEmpty())
        assertFalse(store.get("ep1")!!.resumeDirty)
    }

    @Test
    fun `remove deletes item folder and recoverInterrupted pauses active downloads`() {
        val store = createStore()
        val itemDir = store.itemDir("dl1").apply { mkdirs() }
        File(itemDir, "partial.mp4").writeBytes(ByteArray(512))

        store.upsert(
            OfflineWatchItem(
                jellyfinItemId = "dl1",
                title = "Downloading Movie",
                status = OfflineDownloadStatus.DOWNLOADING,
                downloadedBytes = 512L,
                totalBytes = 1024L,
                localVideoPath = File(itemDir, "partial.mp4").absolutePath,
            ),
        )

        store.recoverInterruptedOnStartup()
        assertEquals(OfflineDownloadStatus.PAUSED, store.get("dl1")!!.status)

        assertTrue(store.remove("dl1"))
        assertNull(store.get("dl1"))
        assertFalse(itemDir.exists())
    }

    @Test
    fun `helper formatters produce clean human-readable strings`() {
        assertEquals("0 B", WatchOfflineHelpers.formatBytes(0L))
        assertEquals("512 KB", WatchOfflineHelpers.formatBytes(512L * 1024L))
        assertEquals("250 MB", WatchOfflineHelpers.formatBytes(250L * 1024L * 1024L))
        assertEquals("2.5 GB", WatchOfflineHelpers.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("12.5 MB/s", WatchOfflineHelpers.formatSpeed((12.5 * 1024 * 1024).toLong()))
        assertEquals(
            "2.0 GB downloaded · 3 titles",
            WatchOfflineHelpers.formatStorageSummary(2L * 1024L * 1024L * 1024L, 3),
        )
        assertEquals(
            "500 MB downloaded · 1 title",
            WatchOfflineHelpers.formatStorageSummary(500L * 1024L * 1024L, 1),
        )
        assertEquals("Slow Horses S01E01.mkv", WatchOfflineHelpers.sanitizeFileName("Slow:Horses/S01E01?.mkv"))
    }
}
