package com.neekhar.pccontrol.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceHelpersTest {

    private val sampleCandidates = listOf(
        SpaceCandidate(
            id = "movie:12",
            kind = "movie",
            radarrId = 12,
            title = "Inception",
            subtitle = null,
            year = 2010,
            sizeBytes = 10_000_000_000L,
            watched = true,
            watchedCount = 1,
            totalUsers = 2,
            watchedUsers = listOf("Ayush"),
            unwatchedUsers = listOf("Amrita"),
            warnUnwatched = true,
            monitored = true,
        ),
        SpaceCandidate(
            id = "movie:15",
            kind = "movie",
            radarrId = 15,
            title = "Arrival",
            subtitle = null,
            year = 2016,
            sizeBytes = 18_000_000_000L,
            watched = true,
            watchedCount = 2,
            totalUsers = 2,
            watchedUsers = listOf("Amrita", "Ayush"),
            unwatchedUsers = emptyList(),
            warnUnwatched = false,
            monitored = false,
        ),
        SpaceCandidate(
            id = "season:5:2",
            kind = "season",
            seriesId = 5,
            seasonNumber = 2,
            title = "Severance",
            subtitle = "Season 2",
            year = 2022,
            episodeCount = 10,
            sizeBytes = 14_000_000_000L,
            watched = true,
            watchedCount = 1,
            totalUsers = 2,
            watchedUsers = listOf("Ayush"),
            unwatchedUsers = listOf("Amrita"),
            warnUnwatched = true,
            monitored = true,
        ),
        SpaceCandidate(
            id = "season:5:1",
            kind = "season",
            seriesId = 5,
            seasonNumber = 1,
            title = "Severance",
            subtitle = "Season 1",
            year = 2022,
            episodeCount = 9,
            sizeBytes = 12_000_000_000L,
            watched = false,
            watchedCount = 0,
            totalUsers = 2,
            watchedUsers = emptyList(),
            unwatchedUsers = listOf("Amrita", "Ayush"),
            warnUnwatched = true,
            monitored = true,
        ),
    )

    private val sampleDriveItems = listOf(
        DriveItem(
            id = "drive:Movies/Inception (2010)",
            kind = "movie",
            relPath = "Movies/Inception (2010)",
            title = "Inception",
            subtitle = null,
            year = 2010,
            episodeCount = null,
            sizeBytes = 10_000_000_000L,
            offloadedAt = 1000L,
        ),
        DriveItem(
            id = "drive:Shows/Severance/Season 02",
            kind = "season",
            relPath = "Shows/Severance/Season 02",
            title = "Severance",
            subtitle = "Season 2",
            year = 2022,
            episodeCount = 10,
            seasonNumber = 2,
            sizeBytes = 16_000_000_000L,
            offloadedAt = 3000L,
        ),
        DriveItem(
            id = "drive:Shows/Severance/Season 01",
            kind = "season",
            relPath = "Shows/Severance/Season 01",
            title = "Severance",
            subtitle = "Season 1",
            year = 2022,
            episodeCount = 9,
            seasonNumber = 1,
            sizeBytes = 12_000_000_000L,
            offloadedAt = 2000L,
        ),
    )

    private fun makeJob(
        id: String,
        op: String = "offload",
        status: String = "copying",
        itemId: String = "movie:$id",
        relPath: String = "Movies/Movie $id",
        finishedAt: Long? = null,
        pct: Double = 0.0,
    ): TransferJob = TransferJob(
        id = id,
        op = op,
        itemId = itemId,
        kind = "movie",
        title = "Movie $id",
        subtitle = null,
        relPath = relPath,
        sizeBytes = 1000L,
        status = status,
        bytesDone = 0L,
        bytesTotal = 1000L,
        pct = pct,
        speedBps = 0L,
        etaS = null,
        error = if (status == "failed") "boom" else null,
        warnings = emptyList(),
        createdAt = 1000L,
        startedAt = 1001L,
        finishedAt = finishedAt,
    )

    @Test
    fun filterAndSortCandidates_filtersByKindStatusQueryAndSorts() {
        assertEquals(
            listOf("movie:15", "season:5:2", "season:5:1", "movie:12"),
            filterAndSortCandidates(sampleCandidates, SpaceFilter.ALL, SpaceSort.SIZE).map { it.id },
        )
        assertEquals(
            listOf("movie:15", "movie:12", "season:5:1", "season:5:2"),
            filterAndSortCandidates(sampleCandidates, SpaceFilter.ALL, SpaceSort.TITLE).map { it.id },
        )
        assertEquals(
            listOf("movie:15", "movie:12"),
            filterAndSortCandidates(sampleCandidates, SpaceFilter.MOVIE, SpaceSort.SIZE).map { it.id },
        )
        assertEquals(
            listOf("season:5:1", "season:5:2"),
            filterAndSortCandidates(sampleCandidates, SpaceFilter.SEASON, SpaceSort.TITLE).map { it.id },
        )
        assertEquals(
            listOf("movie:15", "season:5:2", "movie:12"),
            filterAndSortCandidates(
                sampleCandidates,
                SpaceFilter.ALL,
                SpaceSort.SIZE,
                statusChip = LocalStatusChip.WATCHED,
            ).map { it.id },
        )
        assertEquals(
            listOf("season:5:1"),
            filterAndSortCandidates(
                sampleCandidates,
                SpaceFilter.ALL,
                SpaceSort.SIZE,
                statusChip = LocalStatusChip.UNWATCHED,
            ).map { it.id },
        )
        assertEquals(
            listOf("season:5:2", "season:5:1"),
            filterAndSortCandidates(
                sampleCandidates,
                SpaceFilter.ALL,
                SpaceSort.SIZE,
                query = "sev",
            ).map { it.id },
        )
        assertEquals(
            listOf("movie:15", "season:5:2", "movie:12", "season:5:1"),
            filterAndSortCandidates(sampleCandidates, SpaceFilter.ALL, SpaceSort.WATCHED).map { it.id },
        )
    }

    @Test
    fun summarizeSelection_computesTotalsAndUnwatchedItems() {
        val summary = summarizeSelection(
            sampleCandidates,
            setOf("movie:12", "movie:15", "season:5:2"),
        )
        assertEquals(3, summary.count)
        assertEquals(42_000_000_000L, summary.totalBytes)
        assertEquals(listOf("movie:12", "season:5:2"), summary.unwatched.map { it.id })
    }

    @Test
    fun toFreeRequest_mapsMoviesAndSeasonsToStructuredRequests() {
        assertEquals(
            listOf(
                SpaceFreeItemRequest(kind = "movie", radarrId = 12),
                SpaceFreeItemRequest(kind = "season", seriesId = 5, seasonNumber = 2),
            ),
            toFreeRequest(listOf(sampleCandidates[0], sampleCandidates[2])),
        )
    }

    @Test
    fun formatUpstreamErrors_ignoresHddAndFormatsServiceNames() {
        assertNull(formatUpstreamErrors(emptyMap()))
        assertNull(formatUpstreamErrors(mapOf("hdd" to "HDD not mounted")))
        assertEquals(
            "Radarr, qBittorrent unavailable",
            formatUpstreamErrors(linkedMapOf("radarr" to "down", "qbt" to "timeout")),
        )
        assertEquals(
            "Drive quota unavailable",
            formatUpstreamErrors(mapOf("quota" to "err")),
        )
    }

    @Test
    fun filterAndSortDriveItems_andSummarizeDriveSelection() {
        assertEquals(
            listOf("Shows/Severance/Season 02", "Shows/Severance/Season 01", "Movies/Inception (2010)"),
            filterAndSortDriveItems(sampleDriveItems, SpaceFilter.ALL, SpaceSort.SIZE).map { it.relPath },
        )
        assertEquals(
            listOf("Shows/Severance/Season 01", "Shows/Severance/Season 02"),
            filterAndSortDriveItems(sampleDriveItems, SpaceFilter.SEASON, SpaceSort.TITLE).map { it.relPath },
        )
        assertEquals(
            listOf("Movies/Inception (2010)"),
            filterAndSortDriveItems(sampleDriveItems, SpaceFilter.ALL, SpaceSort.SIZE, query = "2010").map { it.relPath },
        )
        val sel = summarizeDriveSelection(
            sampleDriveItems,
            setOf("Movies/Inception (2010)", "Shows/Severance/Season 01"),
        )
        assertEquals(2, sel.count)
        assertEquals(22_000_000_000L, sel.totalBytes)
    }

    @Test
    fun activeTransferJobs_transferStatusLabel_andParseSpaceJobsSseBlocks() {
        val baseJob = makeJob(id = "j1", op = "offload", status = "copying", pct = 50.0)
        val doneJob = baseJob.copy(id = "j2", status = "done", pct = 100.0)
        val restoreJob = baseJob.copy(id = "j3", op = "restore", status = "verifying")

        assertEquals(listOf("j1", "j3"), activeTransferJobs(listOf(baseJob, doneJob, restoreJob)).map { it.id })
        assertEquals("Offloading", transferStatusLabel(baseJob))
        assertEquals("Restoring", transferStatusLabel(baseJob.copy(op = "restore")))
        assertEquals("Verifying", transferStatusLabel(restoreJob))
        assertEquals("Done", transferStatusLabel(doneJob))

        val payloadJson = SpaceJson.encodeToString(
            SpaceJobsSsePayload.serializer(),
            SpaceJobsSsePayload(jobs = listOf(baseJob)),
        )
        val raw = ": ping\n\nevent: snapshot\ndata: $payloadJson\n\nevent: jobs\ndata: {\"jobs\":"
        val parsed = parseSpaceJobsSseBlocks(raw)
        assertEquals(1, parsed.events.size)
        assertEquals("snapshot", parsed.events[0].event)
        assertEquals("j1", parsed.events[0].jobs[0].id)
        assertEquals("event: jobs\ndata: {\"jobs\":", parsed.rest)
    }

    @Test
    fun selectionStateForVisible_andToggleSelectAllVisible() {
        val visible = listOf("movie:12", "movie:15")
        assertEquals(
            VisibleSelectionState(visibleCount = 2, selectedVisibleCount = 0, allSelected = false, someSelected = false),
            selectionStateForVisible(visible, emptySet()),
        )

        val all = toggleSelectAllVisible(visible, setOf("season:5:1"))
        assertEquals(listOf("movie:12", "movie:15", "season:5:1"), all.sorted())
        assertEquals(
            VisibleSelectionState(visibleCount = 2, selectedVisibleCount = 2, allSelected = true, someSelected = false),
            selectionStateForVisible(visible, all),
        )

        val deselected = toggleSelectAllVisible(visible, all)
        assertEquals(listOf("season:5:1"), deselected.toList())

        val partial = setOf("movie:12")
        assertEquals(
            VisibleSelectionState(visibleCount = 2, selectedVisibleCount = 1, allSelected = false, someSelected = true),
            selectionStateForVisible(visible, partial),
        )
        assertEquals(listOf("movie:12", "movie:15"), toggleSelectAllVisible(visible, partial).sorted())
    }

    @Test
    fun mergeEnqueuedJobs_andVisibleDownloadDriveJobs() {
        val j1 = makeJob("1", status = "copying", finishedAt = null)
        val j2 = makeJob("2", status = "queued", finishedAt = null)
        val merged = mergeEnqueuedJobs(listOf(j1), listOf(j1.copy(pct = 25.0), j2))
        assertEquals(2, merged.size)
        assertEquals(25.0, merged[0].pct, 0.001)
        assertEquals("2", merged[1].id)

        val now = 5000L
        val recentDone = makeJob("3", status = "done", finishedAt = now - 120L)
        val oldDone = makeJob("4", status = "done", finishedAt = now - 7200L)
        val oldFailed = makeJob("5", status = "failed", finishedAt = now - 7200L)
        val visible = visibleDownloadDriveJobs(listOf(oldDone, recentDone, j1, oldFailed, j2), now)
        assertEquals(listOf("1", "2", "3", "5"), visible.map { it.id })
    }

    @Test
    fun activeJobsMatching_byItemIdOrRelPath_andInFlightBadgeLabel() {
        val localItems = listOf(
            sampleCandidates[0].copy(relPath = "Movies/Inception (2010)"),
            sampleCandidates[1].copy(relPath = "Movies/Arrival (2016)"),
            sampleCandidates[2],
        )
        val jobs = listOf(
            makeJob("j1", op = "offload", status = "queued", itemId = "movie:12", relPath = "Movies/Inception (2010)"),
            makeJob("j2", op = "offload", status = "copying", itemId = "other-id", relPath = "Movies/Arrival (2016)"),
            makeJob("j3", op = "offload", status = "done", itemId = "season:5:2", relPath = "Shows/Severance/Season 02"),
            makeJob("j4", op = "restore", status = "copying", itemId = "drive:Movies/Inception (2010)", relPath = "Movies/Inception (2010)"),
            makeJob("j5", op = "restore", status = "queued", itemId = "drive:Shows/Severance/Season 02", relPath = "Shows/Other"),
            makeJob("j6", op = "restore", status = "failed", itemId = "drive:Shows/Severance/Season 01", relPath = "Shows/Severance/Season 01"),
        )

        val localMap = activeOffloadJobsByLocalId(localItems, jobs)
        assertEquals("j1", localMap["movie:12"]?.id)
        assertEquals("j2", localMap["movie:15"]?.id)
        assertFalse(localMap.containsKey("season:5:2"))

        val driveMap = activeRestoreJobsByDrivePath(sampleDriveItems, jobs)
        assertEquals("j4", driveMap["Movies/Inception (2010)"]?.id)
        assertEquals("j5", driveMap["Shows/Severance/Season 02"]?.id)
        assertFalse(driveMap.containsKey("Shows/Severance/Season 01"))

        assertEquals("Queued", inFlightBadgeLabel(jobs[0]))
        assertEquals("Uploading", inFlightBadgeLabel(jobs[1]))
        assertEquals("Verifying", inFlightBadgeLabel(jobs[1].copy(status = "verifying")))
        assertEquals("Finishing", inFlightBadgeLabel(jobs[1].copy(status = "cleaning")))
        assertEquals("Restoring", inFlightBadgeLabel(jobs[3]))
        assertEquals("Queued", inFlightBadgeLabel(jobs[4]))
    }

    @Test
    fun lockedKeys_areExcludedFromSelectionAndSelectAll() {
        val visible = listOf("movie:12", "movie:15", "season:5:2")
        val locked = setOf("movie:12")

        val initialSelected = setOf("movie:12", "movie:15")
        val pruned = pruneDisabledSelection(initialSelected, locked)
        assertEquals(listOf("movie:15"), pruned.toList())
        assertSame(pruned, pruneDisabledSelection(pruned, locked))

        assertEquals(
            VisibleSelectionState(visibleCount = 2, selectedVisibleCount = 1, allSelected = false, someSelected = true),
            selectionStateForVisible(visible, pruned, locked),
        )

        val selectedAll = toggleSelectAllVisible(visible, initialSelected, locked)
        assertEquals(listOf("movie:15", "season:5:2"), selectedAll.sorted())
        assertTrue(selectionStateForVisible(visible, selectedAll, locked).allSelected)

        val deselectedAll = toggleSelectAllVisible(visible, selectedAll, locked)
        assertTrue(deselectedAll.isEmpty())

        val allLocked = visible.toSet()
        assertEquals(
            VisibleSelectionState(visibleCount = 0, selectedVisibleCount = 0, allSelected = false, someSelected = false),
            selectionStateForVisible(visible, emptySet(), allLocked),
        )
        assertTrue(toggleSelectAllVisible(visible, emptySet(), allLocked).isEmpty())
    }

    @Test
    fun formatters_formatBytesRateAndDuration() {
        assertEquals("–", formatBytes(null))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.5 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.0 TB", formatBytes(2L * 1024 * 1024 * 1024 * 1024))
        assertEquals("10.0 MB/s", formatRate(10L * 1024 * 1024))
        assertEquals("–", formatRate(null))
        assertEquals("45s", formatDuration(45L))
        assertEquals("3m", formatDuration(185L))
        assertEquals("2h 5m", formatDuration(7500L))
        assertEquals("1d 2h", formatDuration(93600L))
    }

    @Test
    fun resolveSpacePosterUrl_handlesFullUrlsTmdbPathsAndMissingValues() {
        assertEquals(
            "https://image.tmdb.org/t/p/w300/abc.jpg",
            resolveSpacePosterUrl(poster = "https://image.tmdb.org/t/p/w300/abc.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w185/xyz.jpg",
            resolveSpacePosterUrl(posterPath = "/xyz.jpg"),
        )
        assertEquals(
            "http://example.com/poster.png",
            resolveSpacePosterUrl(posterUrl = "http://example.com/poster.png"),
        )
        assertNull(resolveSpacePosterUrl(poster = "  ", posterUrl = null, posterPath = "invalid"))

        val candidateWithPoster = sampleCandidates[0].copy(posterPath = "/inception.jpg")
        assertEquals("https://image.tmdb.org/t/p/w185/inception.jpg", candidateWithPoster.resolvedPosterUrl)
        assertNull(sampleCandidates[0].resolvedPosterUrl)

        val driveWithPoster = sampleDriveItems[0].copy(poster = "https://image.tmdb.org/t/p/w300/inc.jpg")
        assertEquals("https://image.tmdb.org/t/p/w300/inc.jpg", driveWithPoster.resolvedPosterUrl)
    }
}
