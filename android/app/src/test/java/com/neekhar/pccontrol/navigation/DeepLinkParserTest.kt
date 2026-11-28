package com.neekhar.pccontrol.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkParserTest {

    @Test
    fun parsesPcControlSchemeRoutesAndLegacyAliases() {
        assertEquals(DeepLinkTarget.Route(PcRoute.Home), DeepLinkParser.parseUri("pccontrol://routes"))
        assertEquals(DeepLinkTarget.Route(PcRoute.Home), DeepLinkParser.parseUri("pccontrol://home"))
        assertEquals(
            DeepLinkTarget.Route(PcRoute.System(initialTab = "vitals")),
            DeepLinkParser.parseUri("pccontrol://vitals"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.System(initialTab = "services")),
            DeepLinkParser.parseUri("pccontrol://health"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.System(initialTab = "services")),
            DeepLinkParser.parseUri("pccontrol://services"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.ServiceDetail(unit = "catdetect.service")),
            DeepLinkParser.parseUri("pccontrol://service/catdetect.service"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.Root(requestId = "req-42")),
            DeepLinkParser.parseUri("pccontrol://root/req-42"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.SeerrDetail(mediaType = "movie", id = 550)),
            DeepLinkParser.parseUri("pccontrol://seerr/movie/550"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.Seerr),
            DeepLinkParser.parseUri("pccontrol://watch"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.SeerrDetail(mediaType = "tv", id = 95480)),
            DeepLinkParser.parseUri("pccontrol://watch/tv/95480"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.SeerrPerson(id = 287)),
            DeepLinkParser.parseUri("pccontrol://seerr/person/287"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.UsageAccountDetail(accountId = "claude-main")),
            DeepLinkParser.parseUri("pccontrol://agent-usage/claude-main"),
        )
        assertEquals(
            DeepLinkTarget.Route(PcRoute.Cams(initialTab = "replay")),
            DeepLinkParser.parseUri("pccontrol://cams?tab=replay"),
        )
        assertEquals(DeepLinkTarget.Route(PcRoute.Chat), DeepLinkParser.parseUri("pccontrol://chat"))
        assertEquals(DeepLinkTarget.Route(PcRoute.Space), DeepLinkParser.parseUri("pccontrol://space"))
        assertEquals(DeepLinkTarget.Route(PcRoute.Downloads), DeepLinkParser.parseUri("pccontrol://downloads"))
        assertEquals(DeepLinkTarget.Route(PcRoute.AgentSettings), DeepLinkParser.parseUri("pccontrol://agent-settings"))
        assertEquals(DeepLinkTarget.Route(PcRoute.AgentView), DeepLinkParser.parseUri("pccontrol://agent-view"))
        assertEquals(
            DeepLinkTarget.Route(PcRoute.AgentRule(ruleId = "r-1")),
            DeepLinkParser.parseUri("pccontrol://agent-rule/r-1"),
        )
        assertEquals(DeepLinkTarget.Route(PcRoute.TvRemote), DeepLinkParser.parseUri("pccontrol://tv-remote"))
        assertEquals(DeepLinkTarget.Route(PcRoute.TvRemote), DeepLinkParser.parseUri("pccontrol://tv"))
    }

    @Test
    fun parsesT3ThreadAppLink() {
        val url = "https://app.t3.codes/threads/env_123/thread_abc"
        val parsed = DeepLinkParser.parseUri(url)
        assertEquals(
            DeepLinkTarget.T3Thread(
                environmentId = "env_123",
                threadId = "thread_abc",
                originalUrl = url,
            ),
            parsed,
        )
    }

    @Test
    fun ignoresUnknownSchemesAndHosts() {
        assertNull(DeepLinkParser.parseUri("https://example.com/threads/a/b"))
        assertNull(DeepLinkParser.parseUri(""))
        assertNull(DeepLinkParser.parseUri(null))
    }

    @Test
    fun parsesSharedYoutubeMagnetAndMediaUrls() {
        val yt = DeepLinkParser.parseSharedText("Check out this track https://youtu.be/dQw4w9WgXcQ?si=abc")
        assertTrue(yt is SharedContent.Youtube)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", (yt as SharedContent.Youtube).url)

        val mag = DeepLinkParser.parseSharedText("magnet:?xt=urn:btih:ABCDEF1234567890&dn=Ubuntu+ISO")
        assertTrue(mag is SharedContent.Magnet)
        assertEquals("Ubuntu ISO", (mag as SharedContent.Magnet).name)

        val imdb = DeepLinkParser.parseSharedText("https://www.imdb.com/title/tt0137523/")
        assertEquals(
            SharedContent.MediaUrl(
                url = "https://www.imdb.com/title/tt0137523/",
                source = SharedContent.MediaSource.Imdb,
            ),
            imdb,
        )

        val tmdb = DeepLinkParser.parseSharedText("https://www.themoviedb.org/tv/1396")
        assertEquals(
            SharedContent.MediaUrl(
                url = "https://www.themoviedb.org/tv/1396",
                source = SharedContent.MediaSource.Tmdb,
            ),
            tmdb,
        )

        val boxd = DeepLinkParser.parseSharedText("https://boxd.it/2a9q")
        assertEquals(
            SharedContent.MediaUrl(
                url = "https://boxd.it/2a9q",
                source = SharedContent.MediaSource.Letterboxd,
            ),
            boxd,
        )

        val unsupported = DeepLinkParser.parseSharedText("hello world")
        assertTrue(unsupported is SharedContent.Unsupported)
    }
}
