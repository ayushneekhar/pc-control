package com.neekhar.pccontrol.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTransfersTest {
    private val mb = 1024L * 1024L

    private fun makeDownloads(
        count: Int,
        speed: Double,
        progress: Double? = 0.42,
    ): DownloadsReply = DownloadsReply(
        enabled = true,
        qbt = "ok",
        downloads = List(count) { i ->
            DownloadItem(
                id = "t$i",
                name = "Torrent $i",
                torrent = "t$i",
                category = "movies",
                state = if (speed > 0.0) "downloading" else "stalled",
                progress = progress ?: 0.0,
                speed = if (count > 0) speed / count else 0.0,
                eta = 60L,
                size = 1000L * mb,
                done = 420L * mb,
            )
        },
        total = DownloadsTotal(
            count = count,
            downloading = if (speed > 0.0) count else 0,
            progress = progress,
            speed = speed,
            eta = 60L,
        ),
    )

    private fun makeJob(
        id: String = "j1",
        op: String = "offload",
        status: String = "copying",
        bytesDone: Long = 500L * mb,
        bytesTotal: Long = 1000L * mb,
        sizeBytes: Long = 1000L * mb,
        pct: Double = 50.0,
        speedBps: Double = 11.2 * mb,
        createdAt: Long = 100L,
        finishedAt: Long? = null,
    ): TransferJob = TransferJob(
        id = id,
        op = op,
        itemId = "movie:1",
        kind = "movie",
        title = "Inception",
        subtitle = null,
        relPath = "Movies/Inception (2010)",
        sizeBytes = sizeBytes,
        status = status,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        pct = pct,
        speedBps = speedBps,
        etaS = 45L,
        createdAt = createdAt,
        startedAt = 101L,
        finishedAt = finishedAt,
    )

    @Test
    fun `empty summary when nothing is active`() {
        assertEquals(HomeTransfersSummary(activeCount = 0), summarizeHomeTransfers(null, emptyList()))
        assertEquals(
            HomeTransfersSummary(activeCount = 0),
            summarizeHomeTransfers(
                makeDownloads(0, 0.0, null),
                listOf(makeJob(status = "done"), makeJob(id = "j2", status = "failed")),
            ),
        )
    }

    @Test
    fun `torrent only downloads`() {
        val active = summarizeHomeTransfers(makeDownloads(2, 12.4 * mb, 0.65), emptyList())
        assertEquals(2, active.activeCount)
        assertEquals("2 active · ↓ 12.4 MB/s", active.subtitle)
        assertEquals("65%", active.trailing)
        assertEquals(HomeTransfersTone.WARN, active.trailingTone)
        assertEquals(0.65f, active.progressFraction ?: -1f, 0.001f)

        val stalled = summarizeHomeTransfers(makeDownloads(1, 0.0, null), emptyList())
        assertEquals(1, stalled.activeCount)
        assertEquals("1 active", stalled.subtitle)
        assertNull(stalled.trailing)
        assertNull(stalled.trailingTone)
    }

    @Test
    fun `drive upload only transfers`() {
        val single = summarizeHomeTransfers(
            null,
            listOf(makeJob(speedBps = 11.2 * mb, bytesDone = 350L * mb, bytesTotal = 1000L * mb)),
        )
        assertEquals(1, single.activeCount)
        assertEquals("1 uploading · ↑ 11.2 MB/s", single.subtitle)
        assertEquals("35%", single.trailing)
        assertEquals(HomeTransfersTone.ACCENT, single.trailingTone)
        assertEquals(true, single.isDriveOnly)

        val multi = summarizeHomeTransfers(
            null,
            listOf(
                makeJob(id = "j1", status = "copying", speedBps = 10.0 * mb, bytesDone = 500L * mb, bytesTotal = 1000L * mb),
                makeJob(id = "j2", status = "queued", speedBps = 0.0, bytesDone = 0L, bytesTotal = 1000L * mb),
            ),
        )
        assertEquals(2, multi.activeCount)
        assertEquals("2 uploading · ↑ 10.0 MB/s", multi.subtitle)
        assertEquals("25%", multi.trailing)
        assertEquals(HomeTransfersTone.ACCENT, multi.trailingTone)

        val verifying = summarizeHomeTransfers(null, listOf(makeJob(status = "verifying", speedBps = 0.0)))
        assertEquals(1, verifying.activeCount)
        assertEquals("1 uploading · verifying", verifying.subtitle)
        assertEquals("100%", verifying.trailing)
        assertEquals(HomeTransfersTone.ACCENT, verifying.trailingTone)

        val queuedOnly = summarizeHomeTransfers(null, listOf(makeJob(status = "queued", speedBps = 0.0, bytesDone = 0L)))
        assertEquals(1, queuedOnly.activeCount)
        assertEquals("1 uploading", queuedOnly.subtitle)
        assertNull(queuedOnly.trailing)
        assertNull(queuedOnly.trailingTone)
    }

    @Test
    fun `drive restore only transfers`() {
        val restoring = summarizeHomeTransfers(
            null,
            listOf(makeJob(op = "restore", speedBps = 8.5 * mb, bytesDone = 400L * mb, bytesTotal = 1000L * mb)),
        )
        assertEquals(1, restoring.activeCount)
        assertEquals("1 restoring · ↓ 8.5 MB/s", restoring.subtitle)
        assertEquals("40%", restoring.trailing)
        assertEquals(HomeTransfersTone.ACCENT, restoring.trailingTone)
    }

    @Test
    fun `combines torrent downloads and drive uploads`() {
        val both = summarizeHomeTransfers(
            makeDownloads(2, 4.2 * mb, 0.5),
            listOf(makeJob(speedBps = 10.8 * mb)),
        )
        assertEquals(3, both.activeCount)
        assertEquals("↓ 4.2 MB/s · ↑ 10.8 MB/s", both.subtitle)
        assertEquals("3 active", both.trailing)
        assertEquals(HomeTransfersTone.DIM, both.trailingTone)

        val queuedUpload = summarizeHomeTransfers(
            makeDownloads(1, 4.2 * mb, 0.5),
            listOf(makeJob(status = "queued", speedBps = 0.0)),
        )
        assertEquals(2, queuedUpload.activeCount)
        assertEquals("↓ 4.2 MB/s · 1 uploading", queuedUpload.subtitle)
        assertEquals("2 active", queuedUpload.trailing)
        assertEquals(HomeTransfersTone.DIM, queuedUpload.trailingTone)

        val stalledTorrent = summarizeHomeTransfers(
            makeDownloads(1, 0.0, 0.2),
            listOf(makeJob(speedBps = 10.8 * mb)),
        )
        assertEquals(2, stalledTorrent.activeCount)
        assertEquals("1 active · ↑ 10.8 MB/s", stalledTorrent.subtitle)
        assertEquals("2 active", stalledTorrent.trailing)
        assertEquals(HomeTransfersTone.DIM, stalledTorrent.trailingTone)

        val allThree = summarizeHomeTransfers(
            makeDownloads(1, 4.0 * mb, 0.5),
            listOf(
                makeJob(id = "j1", op = "restore", speedBps = 2.5 * mb),
                makeJob(id = "j2", op = "offload", speedBps = 9.0 * mb),
            ),
        )
        assertEquals(3, allThree.activeCount)
        assertEquals("↓ 6.5 MB/s · ↑ 9.0 MB/s", allThree.subtitle)
        assertEquals("3 active", allThree.trailing)
        assertEquals(HomeTransfersTone.DIM, allThree.trailingTone)
    }

    @Test
    fun `combines torrent downloads and drive restores`() {
        val combined = summarizeHomeTransfers(
            makeDownloads(1, 3.0 * mb, 0.6),
            listOf(makeJob(op = "restore", speedBps = 5.0 * mb)),
        )
        assertEquals(2, combined.activeCount)
        assertEquals("2 active · ↓ 8.0 MB/s", combined.subtitle)
        assertEquals("60%", combined.trailing)
        assertEquals(HomeTransfersTone.WARN, combined.trailingTone)
    }

    @Test
    fun `visibleDownloadDriveJobs filters stale done jobs but keeps failed and recent`() {
        val now = 10_000L
        val jobs = listOf(
            makeJob(id = "active", status = "copying", createdAt = 9_900L),
            makeJob(id = "recentDone", status = "done", createdAt = 9_000L, finishedAt = 9_500L),
            makeJob(id = "oldDone", status = "done", createdAt = 1_000L, finishedAt = 2_000L),
            makeJob(id = "oldFailed", status = "failed", createdAt = 1_000L, finishedAt = 2_000L),
        )
        val visible = visibleDownloadDriveJobs(jobs, nowSec = now)
        assertEquals(listOf("active", "recentDone", "oldFailed"), visible.map { it.id })
    }

    @Test
    fun `lastCatSighting picks most recent cam or falls back to last alert`() {
        val det = DetectState(
            enabled = true,
            cams = mapOf(
                "cat" to DetectCamState(name = "Living", lastSeenTs = 1000.0),
                "cat3" to DetectCamState(name = "Bowl", lastSeenTs = 1500.0),
            ),
        )
        assertEquals(CatSighting(ts = 1500.0, name = "Food bowl"), lastCatSighting(det))

        val alertOnly = DetectState(
            enabled = true,
            cams = emptyMap(),
            lastAlert = DetectLastAlert(cam = "cat2", ts = 900.0),
        )
        assertEquals(CatSighting(ts = 900.0, name = "Hall"), lastCatSighting(alertOnly))
    }
}
