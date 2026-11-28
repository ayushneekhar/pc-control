package com.neekhar.pccontrol.feature.watch

import com.neekhar.pccontrol.feature.watch.player.PlayerSubtitleEntry
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerHelpers
import com.neekhar.pccontrol.feature.watch.player.WatchQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
