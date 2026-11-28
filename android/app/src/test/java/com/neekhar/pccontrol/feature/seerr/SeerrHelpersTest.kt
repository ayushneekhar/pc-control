package com.neekhar.pccontrol.feature.seerr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrHelpersTest {

    @Test
    fun formatRuntime_formatsMinutesAndHoursCleanly() {
        assertNull(SeerrHelpers.formatRuntime(null))
        assertNull(SeerrHelpers.formatRuntime(0))
        assertNull(SeerrHelpers.formatRuntime(-10))
        assertEquals("47m", SeerrHelpers.formatRuntime(47))
        assertEquals("2h", SeerrHelpers.formatRuntime(120))
        assertEquals("2h 28m", SeerrHelpers.formatRuntime(148))
    }

    @Test
    fun formatDate_formatsIsoDatesAndPassesThroughUnparseableValues() {
        assertNull(SeerrHelpers.formatDate(null))
        assertNull(SeerrHelpers.formatDate(""))
        assertEquals("Jul 15, 2010", SeerrHelpers.formatDate("2010-07-15"))
        assertEquals("Nov 11, 1974", SeerrHelpers.formatDate("1974-11-11"))
        assertEquals("TBA", SeerrHelpers.formatDate("TBA"))
    }

    @Test
    fun computeAge_calculatesLivingAndDeceasedAgesAccurately() {
        // Reference date: Nov 26, 2026
        assertEquals(52, SeerrHelpers.computeAge("1974-11-11", null, 2026, 11, 26))
        assertEquals(51, SeerrHelpers.computeAge("1974-12-01", null, 2026, 11, 26))
        assertEquals(69, SeerrHelpers.computeAge("1950-05-10", "2020-05-09", 2026, 11, 26))
        assertEquals(70, SeerrHelpers.computeAge("1950-05-10", "2020-05-10", 2026, 11, 26))
        assertNull(SeerrHelpers.computeAge(null, null, 2026, 11, 26))
    }

    @Test
    fun formatMoneyAndCompactCount_formatLargeNumbersConcisely() {
        assertEquals("$160M", SeerrHelpers.formatMoney(160_000_000L))
        assertEquals("$12.5M", SeerrHelpers.formatMoney(12_500_000L))
        assertEquals("$1.45B", SeerrHelpers.formatMoney(1_450_000_000L))
        assertEquals("$500K", SeerrHelpers.formatMoney(500_000L))
        assertEquals("$420", SeerrHelpers.formatMoney(420L))
        assertNull(SeerrHelpers.formatMoney(0L))
        assertNull(SeerrHelpers.formatMoney(null))

        assertEquals("41k", SeerrHelpers.formatCompactCount(40_829))
        assertEquals("2.9M", SeerrHelpers.formatCompactCount(2_891_347))
        assertEquals("1.3k", SeerrHelpers.formatCompactCount(1_250))
        assertEquals("420", SeerrHelpers.formatCompactCount(420))
        assertNull(SeerrHelpers.formatCompactCount(0))
        assertNull(SeerrHelpers.formatCompactCount(null))
    }

    @Test
    fun isRequestableMovieAndSeason_allowNoneAndDeletedOnly() {
        assertTrue(SeerrHelpers.isRequestableMovie(SeerrStatus.NONE))
        assertTrue(SeerrHelpers.isRequestableMovie(SeerrStatus.DELETED))
        assertFalse(SeerrHelpers.isRequestableMovie(SeerrStatus.AVAILABLE))
        assertFalse(SeerrHelpers.isRequestableMovie(SeerrStatus.REQUESTED))
        assertFalse(SeerrHelpers.isRequestableMovie(SeerrStatus.PENDING))
        assertFalse(SeerrHelpers.isRequestableMovie(SeerrStatus.BLOCKLISTED))

        assertTrue(SeerrHelpers.isRequestableSeason(SeerrStatus.NONE))
        assertTrue(SeerrHelpers.isRequestableSeason(SeerrStatus.DELETED))
        assertFalse(SeerrHelpers.isRequestableSeason(SeerrStatus.PARTIAL))
        assertFalse(SeerrHelpers.isRequestableSeason(SeerrStatus.GRABBING))
    }

    @Test
    fun statusBadge_resolvesAllKnownStatusesIncludingDeletedAndBlocklisted() {
        assertEquals(SeerrStatusBadge("Available", SeerrBadgeTone.OK), SeerrHelpers.statusBadge(SeerrStatus.AVAILABLE))
        assertEquals(SeerrStatusBadge("Grabbing", SeerrBadgeTone.ACCENT), SeerrHelpers.statusBadge(SeerrStatus.GRABBING))
        assertEquals(SeerrStatusBadge("Partial", SeerrBadgeTone.WARN), SeerrHelpers.statusBadge(SeerrStatus.PARTIAL))
        assertEquals(SeerrStatusBadge("Pending", SeerrBadgeTone.WARN), SeerrHelpers.statusBadge(SeerrStatus.PENDING))
        assertEquals(SeerrStatusBadge("Processing", SeerrBadgeTone.DIM), SeerrHelpers.statusBadge(SeerrStatus.REQUESTED))
        assertEquals(SeerrStatusBadge("Deleted", SeerrBadgeTone.FAINT), SeerrHelpers.statusBadge(SeerrStatus.DELETED))
        assertEquals(SeerrStatusBadge("Blocklisted", SeerrBadgeTone.WARN), SeerrHelpers.statusBadge(SeerrStatus.BLOCKLISTED))
        assertEquals(SeerrStatusBadge("Failed", SeerrBadgeTone.BAD), SeerrHelpers.statusBadge(SeerrStatus.FAILED))
        assertEquals(SeerrStatusBadge("Declined", SeerrBadgeTone.BAD), SeerrHelpers.statusBadge(SeerrStatus.DECLINED))
        assertNull(SeerrHelpers.statusBadge(SeerrStatus.NONE))
    }

    @Test
    fun seerrStatusFromMediaCode_handlesNumericCodesIncludingDeleted7() {
        assertEquals(SeerrStatus.AVAILABLE, SeerrStatus.fromMediaCode(5))
        assertEquals(SeerrStatus.DELETED, SeerrStatus.fromMediaCode(7))
        assertEquals(SeerrStatus.BLOCKLISTED, SeerrStatus.fromMediaCode(6))
        assertEquals(SeerrStatus.PARTIAL, SeerrStatus.fromMediaCode(4))
        assertEquals(SeerrStatus.GRABBING, SeerrStatus.fromMediaCode(3, hasActiveDownload = true))
        assertEquals(SeerrStatus.REQUESTED, SeerrStatus.fromMediaCode(3))
        assertEquals(SeerrStatus.REQUESTED, SeerrStatus.fromMediaCode(2))
        assertEquals(SeerrStatus.NONE, SeerrStatus.fromMediaCode(1))
        assertEquals(SeerrStatus.NONE, SeerrStatus.fromMediaCode(null))
    }

    @Test
    fun collectionHelpers_calculateMissingAndSummaryCorrectly() {
        val collection = SeerrCollection(
            id = 10,
            name = "The Matrix Collection",
            parts = listOf(
                SeerrItem(id = 1, mediaType = "movie", title = "The Matrix", status = SeerrStatus.AVAILABLE),
                SeerrItem(id = 2, mediaType = "movie", title = "Reloaded", status = SeerrStatus.NONE),
                SeerrItem(id = 3, mediaType = "movie", title = "Revolutions", status = SeerrStatus.DELETED),
                SeerrItem(id = 4, mediaType = "movie", title = "Resurrections", status = SeerrStatus.REQUESTED),
            ),
        )
        val missing = SeerrHelpers.collectionMissingParts(collection)
        assertEquals(listOf(2, 3), missing.map { it.id })
        assertEquals(1, SeerrHelpers.collectionAvailableCount(collection))
        assertEquals("1 of 4 available", SeerrHelpers.collectionSummary(collection))

        val noneAvail = collection.copy(
            parts = listOf(
                SeerrItem(id = 2, mediaType = "movie", title = "Reloaded", status = SeerrStatus.NONE),
            ),
        )
        assertEquals("1 movie", SeerrHelpers.collectionSummary(noneAvail))
    }

    @Test
    fun visibleRequestFilterTabs_appendsDeclinedAndBlocklistedWhenNonZero() {
        val base = SeerrHelpers.visibleRequestFilterTabs(mapOf("all" to 10, "declined" to 0, "blocklisted" to 0))
        assertFalse(base.contains(SeerrRequestFilter.DECLINED))
        assertFalse(base.contains(SeerrRequestFilter.BLOCKLISTED))

        val expanded = SeerrHelpers.visibleRequestFilterTabs(mapOf("all" to 10, "declined" to 2, "blocklisted" to 1))
        assertTrue(expanded.contains(SeerrRequestFilter.DECLINED))
        assertTrue(expanded.contains(SeerrRequestFilter.BLOCKLISTED))
    }

    @Test
    fun filterSearchResults_filtersByMediaType() {
        val items = listOf(
            SeerrItem(id = 1, mediaType = "collection", title = "Collection"),
            SeerrItem(id = 2, mediaType = "movie", title = "Movie"),
            SeerrItem(id = 3, mediaType = "tv", title = "Show"),
        )
        assertEquals(3, SeerrHelpers.filterSearchResults(items, SeerrSearchMediaFilter.ALL).size)
        assertEquals(listOf(2), SeerrHelpers.filterSearchResults(items, SeerrSearchMediaFilter.MOVIE).map { it.id })
        assertEquals(listOf(3), SeerrHelpers.filterSearchResults(items, SeerrSearchMediaFilter.TV).map { it.id })
        assertEquals(listOf(1), SeerrHelpers.filterSearchResults(items, SeerrSearchMediaFilter.COLLECTION).map { it.id })
    }

    @Test
    fun filterAndSortPersonCredits_filtersAndSortsByNewest() {
        val credits = listOf(
            SeerrPersonCredit(id = 1, mediaType = "movie", title = "Old Hit", releaseDate = "1999-03-31", popularity = 90.0),
            SeerrPersonCredit(id = 2, mediaType = "tv", title = "New Show", releaseDate = "2024-05-01", popularity = 20.0),
            SeerrPersonCredit(id = 3, mediaType = "movie", title = "New Movie", releaseDate = "2025-01-10", popularity = 50.0),
        )
        val moviesNewest = SeerrHelpers.filterAndSortPersonCredits(
            credits,
            SeerrPersonMediaFilter.MOVIE,
            SeerrPersonSortMode.NEWEST,
        )
        assertEquals(listOf(3, 1), moviesNewest.map { it.id })

        val moviesPopular = SeerrHelpers.filterAndSortPersonCredits(
            credits,
            SeerrPersonMediaFilter.MOVIE,
            SeerrPersonSortMode.POPULAR,
        )
        assertEquals(listOf(1, 3), moviesPopular.map { it.id })
    }

    @Test
    fun formatSeasonTag_formatsTvSeasonsAndMovie() {
        assertEquals("S1, S2", SeerrHelpers.formatSeasonTag("tv", listOf(1, 2)))
        assertEquals("TV", SeerrHelpers.formatSeasonTag("tv", emptyList()))
        assertEquals("Movie", SeerrHelpers.formatSeasonTag("movie", emptyList()))
    }

    @Test
    fun watchHelpers_formatRemainingMinutesAndDownloadBytes() {
        assertNull(SeerrHelpers.formatRemainingMinutes(null))
        assertNull(SeerrHelpers.formatRemainingMinutes(0))
        assertEquals("34m left", SeerrHelpers.formatRemainingMinutes(34))
        assertEquals("2h left", SeerrHelpers.formatRemainingMinutes(120))
        assertEquals("1h 42m left", SeerrHelpers.formatRemainingMinutes(102))

        assertEquals("0 B", SeerrHelpers.formatDownloadBytes(0L))
        assertEquals("512 MB", SeerrHelpers.formatDownloadBytes(512L * 1024L * 1024L))
        assertEquals("2.5 GB", SeerrHelpers.formatDownloadBytes((2.5 * 1024.0 * 1024.0 * 1024.0).toLong()))
    }

    @Test
    fun watchHelpers_watchlistKeyPendingBadgeAndDefaultEpisodeSeason() {
        assertEquals("movie:466272", SeerrHelpers.watchlistKey("MOVIE", 466272))
        assertEquals("tv:95480", SeerrHelpers.watchlistKey("tv", 95480))

        assertEquals(0, SeerrHelpers.pendingRequestsBadgeCount(null))
        assertEquals(5, SeerrHelpers.pendingRequestsBadgeCount(mapOf("pending" to 2, "processing" to 3, "available" to 10)))

        val seasons = listOf(
            SeerrSeason(seasonNumber = 0, name = "Specials", status = SeerrStatus.AVAILABLE),
            SeerrSeason(seasonNumber = 1, name = "Season 1", status = SeerrStatus.NONE),
            SeerrSeason(seasonNumber = 2, name = "Season 2", status = SeerrStatus.AVAILABLE),
        )
        assertEquals(2, SeerrHelpers.defaultEpisodeSeason(seasons))
        assertEquals(
            1,
            SeerrHelpers.defaultEpisodeSeason(
                listOf(
                    SeerrSeason(seasonNumber = 0, name = "Specials", status = SeerrStatus.NONE),
                    SeerrSeason(seasonNumber = 1, name = "Season 1", status = SeerrStatus.NONE),
                ),
            ),
        )
        assertNull(SeerrHelpers.defaultEpisodeSeason(emptyList()))
    }

    @Test
    fun formatResumeTimestampAndPlayButtonLabel_handlesThresholdAndHours() {
        assertNull(SeerrHelpers.formatResumeTimestamp(0L))
        assertNull(SeerrHelpers.formatResumeTimestamp(10L * 10_000_000L))
        assertEquals("0:11", SeerrHelpers.formatResumeTimestamp(11L * 10_000_000L))
        assertEquals("14:22", SeerrHelpers.formatResumeTimestamp((14L * 60L + 22L) * 10_000_000L))
        assertEquals("1:14:22", SeerrHelpers.formatResumeTimestamp((3600L + 14L * 60L + 22L) * 10_000_000L))

        assertEquals("Play", SeerrHelpers.formatPlayButtonLabel(0L, isOffline = false))
        assertEquals("Play offline", SeerrHelpers.formatPlayButtonLabel(0L, isOffline = true))
        assertEquals(
            "Resume from 14:22",
            SeerrHelpers.formatPlayButtonLabel((14L * 60L + 22L) * 10_000_000L),
        )
        assertEquals(
            "Resume S1 · E3 from 1:14:22",
            SeerrHelpers.formatPlayButtonLabel(
                (3600L + 14L * 60L + 22L) * 10_000_000L,
                episodeLabel = "S1 · E3",
            ),
        )
    }

    @Test
    fun mediaBadgePills_and_boxSetCountLabel_formatCleanly() {
        assertEquals(emptyList<String>(), SeerrHelpers.mediaBadgePills(null))
        val full = WatchMediaBadges(
            resolution = "4K",
            videoCodec = "HEVC",
            hdr = "Dolby Vision",
            audioCodec = "TrueHD 7.1",
            sizeBytes = 19_649_475_379L,
            sizeLabel = "18.3 GB",
        )
        assertEquals(
            listOf("4K", "HEVC", "Dolby Vision", "TrueHD 7.1", "18.3 GB"),
            SeerrHelpers.mediaBadgePills(full),
        )
        val fallbackSize = WatchMediaBadges(
            resolution = "1080p",
            videoCodec = "H.264",
            hdr = null,
            audioCodec = "EAC3 5.1",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            sizeLabel = null,
        )
        assertEquals(
            listOf("1080p", "H.264", "EAC3 5.1", "2.0 GB"),
            SeerrHelpers.mediaBadgePills(fallbackSize),
        )
        assertEquals("0 movies", SeerrHelpers.boxSetCountLabel(0))
        assertEquals("1 movie", SeerrHelpers.boxSetCountLabel(1))
        assertEquals("6 movies", SeerrHelpers.boxSetCountLabel(6))
    }
}
