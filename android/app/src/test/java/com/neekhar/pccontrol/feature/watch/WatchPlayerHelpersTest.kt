package com.neekhar.pccontrol.feature.watch

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
}
