package com.neekhar.pccontrol.feature.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteFormattersTest {

    @Test
    fun formatsDurationAndRemainingWithTabularStyle() {
        assertEquals("00:00", TvRemoteFormatters.formatDuration(0))
        assertEquals("05:09", TvRemoteFormatters.formatDuration(309))
        assertEquals("01:12:34", TvRemoteFormatters.formatDuration(4354))
        assertEquals("-42:10", TvRemoteFormatters.formatRemaining(100, 2630))
        assertEquals("-00:00", TvRemoteFormatters.formatRemaining(500, 400))
        assertEquals("--:--", TvRemoteFormatters.formatRemaining(10, 0))
    }

    @Test
    fun formatsMovieAndEpisodeTitles() {
        val movie = TvNowPlaying(
            itemId = "0123456789abcdef0123456789abcdef",
            name = "Dune: Part Two",
            type = "Movie",
            year = 2024,
        )
        assertEquals("Dune: Part Two", TvRemoteFormatters.primaryTitle(movie))
        assertEquals("2024", TvRemoteFormatters.secondarySubtitle(movie))
        assertEquals("Dune: Part Two", TvRemoteFormatters.miniBarTitle(movie))

        val ep = TvNowPlaying(
            itemId = "11112222333344445555666677778888",
            name = "Ozymandias",
            type = "Episode",
            seriesName = "Breaking Bad",
            seasonNumber = 5,
            episodeNumber = 14,
            year = 2013,
        )
        assertEquals("Breaking Bad", TvRemoteFormatters.primaryTitle(ep))
        assertEquals("S05 · E14 — Ozymandias", TvRemoteFormatters.secondarySubtitle(ep))
        assertEquals("Breaking Bad · S05 E14", TvRemoteFormatters.miniBarTitle(ep))
    }

    @Test
    fun resolvesActiveAudioSubtitleAndCapabilities() {
        val session = TvSessionResponse(
            connected = true,
            isPlaying = true,
            audioStreamIndex = 2,
            subtitleStreamIndex = -1,
            audioTracks = listOf(
                TvAudioTrack(index = 1, displayTitle = "English 5.1", isDefault = true),
                TvAudioTrack(index = 2, displayTitle = "Hindi Stereo"),
            ),
            subtitleTracks = listOf(
                TvSubtitleTrack(index = 3, displayTitle = "English SRT"),
            ),
            supportedCommands = listOf("VolumeUp", "ToggleMute"),
        )
        assertEquals("Hindi Stereo", TvRemoteFormatters.activeAudioLabel(session))
        assertEquals("Off", TvRemoteFormatters.activeSubtitleLabel(session))
        assertEquals("English SRT", TvRemoteFormatters.activeSubtitleLabel(session.copy(subtitleStreamIndex = 3)))
        assertTrue(TvRemoteFormatters.isVolumeSupported(session))
        assertTrue(TvRemoteFormatters.supportsCommand(session, "volumeup"))
        assertFalse(TvRemoteFormatters.supportsCommand(session, "SetVolume"))
    }
}
