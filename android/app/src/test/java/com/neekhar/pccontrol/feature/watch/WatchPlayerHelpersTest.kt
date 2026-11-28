package com.neekhar.pccontrol.feature.watch

import com.neekhar.pccontrol.feature.watch.player.PlayerSubtitleEntry
import com.neekhar.pccontrol.feature.watch.player.WatchChapter
import com.neekhar.pccontrol.feature.watch.player.WatchMediaSegment
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerHelpers
import com.neekhar.pccontrol.feature.watch.player.WatchQualityOption
import com.neekhar.pccontrol.feature.watch.player.WatchTrickplayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlayerHelpersTest {

    @Test
    fun `formatTimeMs formats minutes and hours with zero padding`() {
        assertEquals("00:00", WatchPlayerHelpers.formatTimeMs(0L))
        assertEquals("00:09", WatchPlayerHelpers.formatTimeMs(9_000L))
        assertEquals("04:12", WatchPlayerHelpers.formatTimeMs(252_000L))
        assertEquals("1:05:03", WatchPlayerHelpers.formatTimeMs(3_903_000L))
    }

    @Test
    fun `tick conversions are exact`() {
        assertEquals(90_000L, WatchPlayerHelpers.ticksToMs(900_000_000L))
        assertEquals(900_000_000L, WatchPlayerHelpers.msToTicks(90_000L))
        assertEquals(0L, WatchPlayerHelpers.ticksToMs(-100L))
        assertEquals(0L, WatchPlayerHelpers.msToTicks(-5L))
    }

    @Test
    fun `next episode overlay shows in final 15 seconds or when ended unless dismissed`() {
        assertFalse(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 100_000L,
                durationMs = 200_000L,
                isEnded = false,
                hasNextEpisode = true,
                dismissed = false,
            ),
        )
        assertTrue(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 186_000L,
                durationMs = 200_000L,
                isEnded = false,
                hasNextEpisode = true,
                dismissed = false,
            ),
        )
        assertTrue(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 200_000L,
                durationMs = 200_000L,
                isEnded = true,
                hasNextEpisode = true,
                dismissed = false,
            ),
        )
        assertFalse(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 200_000L,
                durationMs = 200_000L,
                isEnded = true,
                hasNextEpisode = true,
                dismissed = true,
            ),
        )
        assertFalse(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 195_000L,
                durationMs = 200_000L,
                isEnded = false,
                hasNextEpisode = false,
                dismissed = false,
            ),
        )
    }

    @Test
    fun `selectInitialQuality honors recommended id and falls back cleanly`() {
        val opts = listOf(
            WatchQualityOption(id = "original", label = "Original (Direct)", isHls = false),
            WatchQualityOption(id = "1080p", label = "1080p (8 Mbps)", isHls = true),
            WatchQualityOption(id = "720p", label = "720p (4 Mbps)", isHls = true),
        )
        assertEquals("original", WatchPlayerHelpers.selectInitialQuality(opts, "original")?.id)
        assertEquals("720p", WatchPlayerHelpers.selectInitialQuality(opts, "720p")?.id)
        assertEquals("1080p", WatchPlayerHelpers.selectInitialQuality(opts, "missing")?.id)
    }

    @Test
    fun `withAudioStreamIndex replaces or appends AudioStreamIndex param`() {
        val replaced = WatchPlayerHelpers.withAudioStreamIndex(
            "https://host/pc/api/watch/stream/id/master.m3u8?AudioStreamIndex=1&VideoCodec=h264",
            2,
        )
        assertEquals(
            "https://host/pc/api/watch/stream/id/master.m3u8?AudioStreamIndex=2&VideoCodec=h264",
            replaced,
        )
        val appended = WatchPlayerHelpers.withAudioStreamIndex(
            "https://host/pc/api/watch/stream/id/master.m3u8?VideoCodec=h264",
            3,
        )
        assertEquals(
            "https://host/pc/api/watch/stream/id/master.m3u8?VideoCodec=h264&AudioStreamIndex=3",
            appended,
        )
    }

    @Test
    fun `drag brightness and volume clamp to valid bounds`() {
        assertEquals(1.0f, WatchPlayerHelpers.computeDragBrightness(0.8f, 0.5f), 0.001f)
        assertEquals(0.05f, WatchPlayerHelpers.computeDragBrightness(0.2f, -0.5f), 0.001f)
        assertEquals(15, WatchPlayerHelpers.computeDragVolume(10, 15, 0.8f))
        assertEquals(0, WatchPlayerHelpers.computeDragVolume(3, 15, -0.8f))
    }

    @Test
    fun `isUnsupportedDirectAudioCodec detects TrueHD DTS and MLP codecs`() {
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("truehd"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("TrueHD"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("audio/true-hd"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("dts"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("dca"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("dtshd"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("DTS-HD MA"))
        assertTrue(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("mlp"))

        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("aac"))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("ac3"))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("eac3"))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("opus"))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec("flac"))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec(""))
        assertFalse(WatchPlayerHelpers.isUnsupportedDirectAudioCodec(null))
    }

    @Test
    fun `selectHlsFallbackQuality prefers 1080p HLS then first HLS`() {
        val with1080 = listOf(
            WatchQualityOption(id = "original", isHls = false),
            WatchQualityOption(id = "1080p", isHls = true),
            WatchQualityOption(id = "720p", isHls = true),
        )
        assertEquals("1080p", WatchPlayerHelpers.selectHlsFallbackQuality(with1080)?.id)

        val only720 = listOf(
            WatchQualityOption(id = "original", isHls = false),
            WatchQualityOption(id = "720p", isHls = true),
        )
        assertEquals("720p", WatchPlayerHelpers.selectHlsFallbackQuality(only720)?.id)
    }

    @Test
    fun `subtitlesToAttach never attaches embedded HTTP subtitles in Direct Play or HLS startup`() {
        val embedded = PlayerSubtitleEntry(
            id = "sub:2",
            label = "English (MKV)",
            language = "eng",
            uri = "https://host/pc/api/watch/subtitles/abc/ms/2.vtt",
            isExternal = false,
            isDefault = true,
        )
        val external = PlayerSubtitleEntry(
            id = "sub:5",
            label = "English (External)",
            language = "eng",
            uri = "https://host/pc/api/watch/subtitles/abc/ms/5.vtt",
            isExternal = true,
        )
        val offlineLocal = PlayerSubtitleEntry(
            id = "sub:6",
            label = "English (Offline)",
            language = "eng",
            uri = "file:///data/user/0/app/files/sub_6_eng.vtt",
            isExternal = false,
        )
        val all = listOf(embedded, external, offlineLocal)

        // Direct Play: only external & file:// tracks are attached; embedded MKV is demuxed natively
        val directAttached = WatchPlayerHelpers.subtitlesToAttach(all, isHls = false, hlsAttachedSubtitleId = null)
        assertEquals(listOf("sub:5", "sub:6"), directAttached.map { it.id })

        // HLS initial load: only external & file:// tracks attached (no eager ffmpeg extraction)
        val hlsInitial = WatchPlayerHelpers.subtitlesToAttach(all, isHls = true, hlsAttachedSubtitleId = null)
        assertEquals(listOf("sub:5", "sub:6"), hlsInitial.map { it.id })

        // HLS after user explicitly picks embedded subtitle sub:2: attaches sub:2 on demand
        val hlsOnDemand = WatchPlayerHelpers.subtitlesToAttach(all, isHls = true, hlsAttachedSubtitleId = "sub:2")
        assertEquals(listOf("sub:2", "sub:5", "sub:6"), hlsOnDemand.map { it.id })
    }

    @Test
    fun `languagesMatch compares ISO-639-1 and ISO-639-2 codes`() {
        assertTrue(WatchPlayerHelpers.languagesMatch("en", "eng"))
        assertTrue(WatchPlayerHelpers.languagesMatch("ENG", "en"))
        assertTrue(WatchPlayerHelpers.languagesMatch("ja", "jpn"))
        assertFalse(WatchPlayerHelpers.languagesMatch("en", "es"))
        assertFalse(WatchPlayerHelpers.languagesMatch("", "eng"))
        assertFalse(WatchPlayerHelpers.languagesMatch(null, "eng"))
    }

    @Test
    fun `activeSegment matches half-open interval and ignores invalid or unknown segments`() {
        val intro = WatchMediaSegment(
            type = "Intro",
            startTicks = 5_000_000_000L, // 500_000 ms
            endTicks = 5_600_000_000L,   // 560_000 ms
        )
        val outro = WatchMediaSegment(
            type = "Outro",
            startTicks = 31_700_000_000L, // 3_170_000 ms
            endTicks = 32_300_000_000L,   // 3_230_000 ms
        )
        val invalidZeroLen = WatchMediaSegment(type = "Recap", startTicks = 100_000L, endTicks = 100_000L)
        val unknownType = WatchMediaSegment(type = "Chapter", startTicks = 0L, endTicks = 1_000_000_000L)
        val segments = listOf(invalidZeroLen, unknownType, intro, outro)

        assertNull(WatchPlayerHelpers.activeSegment(segments, -1L))
        assertNull(WatchPlayerHelpers.activeSegment(segments, 499_999L))
        assertEquals(intro, WatchPlayerHelpers.activeSegment(segments, 500_000L))
        assertEquals(intro, WatchPlayerHelpers.activeSegment(segments, 530_000L))
        // Exactly at endMs must NOT match so skipping to endMs dismisses the button immediately
        assertNull(WatchPlayerHelpers.activeSegment(segments, 560_000L))
        assertEquals(outro, WatchPlayerHelpers.activeSegment(segments, 3_170_000L))
        assertNull(WatchPlayerHelpers.activeSegment(segments, 3_230_000L))
    }

    @Test
    fun `segmentButtonLabel maps every Jellyfin segment type cleanly`() {
        assertEquals("Skip Intro", WatchPlayerHelpers.segmentButtonLabel("Intro"))
        assertEquals("Skip Intro", WatchPlayerHelpers.segmentButtonLabel("intro"))
        assertEquals("Skip Recap", WatchPlayerHelpers.segmentButtonLabel("Recap"))
        assertEquals("Skip Credits", WatchPlayerHelpers.segmentButtonLabel("Outro"))
        assertEquals("Next Episode", WatchPlayerHelpers.segmentButtonLabel("Outro", hasNextEpisode = true))
        assertEquals("Skip Preview", WatchPlayerHelpers.segmentButtonLabel("Preview"))
        assertEquals("Skip Ad", WatchPlayerHelpers.segmentButtonLabel("Commercial"))
        assertEquals("Skip", WatchPlayerHelpers.segmentButtonLabel("Other"))
    }

    @Test
    fun `shouldShowNextEpisodeOverlay triggers during Outro segment unless dismissed`() {
        assertTrue(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 1_000_000L,
                durationMs = 3_000_000L,
                isEnded = false,
                hasNextEpisode = true,
                dismissed = false,
                inOutroSegment = true,
            ),
        )
        assertFalse(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 1_000_000L,
                durationMs = 3_000_000L,
                isEnded = false,
                hasNextEpisode = true,
                dismissed = true,
                inOutroSegment = true,
            ),
        )
        assertFalse(
            WatchPlayerHelpers.shouldShowNextEpisodeOverlay(
                positionMs = 1_000_000L,
                durationMs = 3_000_000L,
                isEnded = false,
                hasNextEpisode = false,
                dismissed = false,
                inOutroSegment = true,
            ),
        )
    }

    @Test
    fun `trickplayFrameForPosition computes tile index row col and edge cases accurately`() {
        val tp = WatchTrickplayInfo(
            width = 320,
            height = 160,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = 323,
            intervalMs = 10_000L,
            tileUrlTemplate = "https://host.ts.net/pc/api/watch/trickplay/abc/320/{tileIndex}.jpg?mediaSourceId=abc",
        )

        // 0ms -> frame 0, tile 0, row 0, col 0
        val f0 = WatchPlayerHelpers.trickplayFrameForPosition(tp, 0L)
        assertNotNull(f0)
        assertEquals(0, f0!!.frameIndex)
        assertEquals(0, f0.tileIndex)
        assertEquals(0, f0.row)
        assertEquals(0, f0.col)
        assertEquals("https://host.ts.net/pc/api/watch/trickplay/abc/320/0.jpg?mediaSourceId=abc", f0.tileUrl)

        // Multi-tile boundary: frame 99 (990_000 ms) -> tile 0, row 9, col 9
        val f99 = WatchPlayerHelpers.trickplayFrameForPosition(tp, 990_000L)
        assertNotNull(f99)
        assertEquals(99, f99!!.frameIndex)
        assertEquals(0, f99.tileIndex)
        assertEquals(9, f99.row)
        assertEquals(9, f99.col)
        assertEquals("https://host.ts.net/pc/api/watch/trickplay/abc/320/0.jpg?mediaSourceId=abc", f99.tileUrl)

        // Multi-tile boundary: frame 100 (1_000_000 ms) -> tile 1, row 0, col 0
        val f100 = WatchPlayerHelpers.trickplayFrameForPosition(tp, 1_000_000L)
        assertNotNull(f100)
        assertEquals(100, f100!!.frameIndex)
        assertEquals(1, f100.tileIndex)
        assertEquals(0, f100.row)
        assertEquals(0, f100.col)
        assertEquals("https://host.ts.net/pc/api/watch/trickplay/abc/320/1.jpg?mediaSourceId=abc", f100.tileUrl)

        // Beyond duration clamps cleanly to last frame (322 -> tile 3, row 2, col 2)
        val fLast = WatchPlayerHelpers.trickplayFrameForPosition(tp, 99_999_000L)
        assertNotNull(fLast)
        assertEquals(322, fLast!!.frameIndex)
        assertEquals(3, fLast.tileIndex)
        assertEquals(2, fLast.row)
        assertEquals(2, fLast.col)
        assertEquals("https://host.ts.net/pc/api/watch/trickplay/abc/320/3.jpg?mediaSourceId=abc", fLast.tileUrl)

        // Negative position clamps to frame 0
        val fNeg = WatchPlayerHelpers.trickplayFrameForPosition(tp, -5_000L)
        assertEquals(0, fNeg!!.frameIndex)

        // Invalid trickplay metadata returns null
        assertNull(WatchPlayerHelpers.trickplayFrameForPosition(tp.copy(thumbnailCount = 0), 10_000L))
        assertNull(WatchPlayerHelpers.trickplayFrameForPosition(tp.copy(intervalMs = 0L), 10_000L))
        assertNull(WatchPlayerHelpers.trickplayFrameForPosition(tp.copy(tileUrlTemplate = ""), 10_000L))
    }

    @Test
    fun `activeChapterTitle selects latest chapter at or before position`() {
        val chapters = listOf(
            WatchChapter(name = "Cold Open", startPositionTicks = 0L),
            WatchChapter(name = "Title Sequence", startPositionTicks = 3_000_000_000L), // 300s
            WatchChapter(name = "Act I", startPositionTicks = 4_000_000_000L),          // 400s
        )
        assertEquals("Cold Open", WatchPlayerHelpers.activeChapterTitle(chapters, 120_000L))
        assertEquals("Title Sequence", WatchPlayerHelpers.activeChapterTitle(chapters, 300_000L))
        assertEquals("Act I", WatchPlayerHelpers.activeChapterTitle(chapters, 550_000L))
        assertNull(WatchPlayerHelpers.activeChapterTitle(emptyList(), 100_000L))
    }
}
