package com.neekhar.pccontrol.feature.cams

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for Replay HLS segment/timeline math, [ReplayEngine] playback state machine,
 * and Cams WebRTC / YouTube helpers.
 * Mirrors and extends `src/lib/cams/replay.test.ts`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayEngineTest {

    // Two stretches: wall 1000..1060 (media 0..60) and wall 2000..2030 (media 60..90)
    private val spans = listOf(
        RecSpan(wallStart = 1000.0, mediaStart = 0.0, duration = 60.0),
        RecSpan(wallStart = 2000.0, mediaStart = 60.0, duration = 30.0),
    )

    private fun idx(cam: RecCam, sp: List<RecSpan>, b: String = "b1", codec: String = "avc"): RecIndex = RecIndex(
        cam = cam.id,
        codec = codec,
        a = "a1",
        b = b,
        spans = sp,
    )

    @Test
    fun w2m_insideInGapBeforeAndAfter() {
        assertEquals(WallToMedia.Hit(10.0), ReplayMath.w2m(spans, 1010.0))
        assertEquals(WallToMedia.Hit(65.0), ReplayMath.w2m(spans, 2005.0))
        assertEquals(WallToMedia.Gap(prev = 1060.0, next = 2000.0), ReplayMath.w2m(spans, 1500.0))
        assertEquals(WallToMedia.Gap(prev = null, next = 1000.0), ReplayMath.w2m(spans, 500.0))
        assertEquals(WallToMedia.Gap(prev = 2030.0, next = null), ReplayMath.w2m(spans, 3000.0))
    }

    @Test
    fun m2w_inverseInsideFootageAndClamps() {
        for (t in listOf(1000.0, 1033.5, 2000.0, 2029.0)) {
            val hit = ReplayMath.w2m(spans, t) as WallToMedia.Hit
            assertEquals(t, ReplayMath.m2w(spans, hit.mediaTime)!!, 1e-6)
        }
        assertEquals(1059.9, ReplayMath.m2w(spans, 59.9)!!, 1e-6)
        // Within 0.05s of the next span snaps to it
        assertEquals(2000.0, ReplayMath.m2w(spans, 59.99)!!, 1e-6)
        // Past the end clamps to end of last span
        assertEquals(2030.0, ReplayMath.m2w(spans, 500.0)!!, 1e-6)
        // Before the first span returns null
        assertNull(ReplayMath.m2w(spans, -1.0))
    }

    @Test
    fun continuousPlaylist_mediaTimeNeverJumpsAcrossGap() {
        val beforeGap = (ReplayMath.w2m(spans, 1059.9) as WallToMedia.Hit).mediaTime
        val afterGap = (ReplayMath.w2m(spans, 2000.0) as WallToMedia.Hit).mediaTime
        assertTrue(abs((beforeGap + 0.1) - afterGap) < 1e-9)
    }

    @Test
    fun startMedia_movesToNextFootageOrNearEnd() {
        assertEquals(60.0, ReplayMath.startMedia(spans, 1500.0), 1e-6)
        assertEquals(87.0, ReplayMath.startMedia(spans, 9999.0), 1e-6)
        assertEquals(0.0, ReplayMath.startMedia(emptyList(), 5.0), 1e-6)
    }

    @Test
    fun snapSeek_snapsOutOfSharedGapInTravelDirection() {
        val map = mapOf(
            RecCam.CAT to idx(RecCam.CAT, spans),
            RecCam.CAT2 to idx(RecCam.CAT2, listOf(RecSpan(1100.0, 0.0, 50.0))),
            RecCam.CAT3HD to idx(RecCam.CAT3HD, emptyList()),
        )
        val now = 2100.0
        assertEquals(2000.0, ReplayMath.snapSeek(map, 1500.0, now, dir = 1), 1e-6)
        assertEquals(1148.0, ReplayMath.snapSeek(map, 1500.0, now, dir = -1), 1e-6)
        assertEquals(1120.0, ReplayMath.snapSeek(map, 1120.0, now, dir = 1), 1e-6)
        assertEquals(2028.0, ReplayMath.snapSeek(map, 5000.0, now, dir = 0), 1e-6)
    }

    @Test
    fun snapSeekCam_staysOnOneCamAndFallsBackToNearestFootage() {
        val now = 2100.0
        assertEquals(1010.0, ReplayMath.snapSeekCam(spans, 1010.0, now, dir = -1), 1e-6)
        assertEquals(1058.0, ReplayMath.snapSeekCam(spans, 1500.0, now, dir = -1), 1e-6)
        assertEquals(2000.0, ReplayMath.snapSeekCam(spans, 1500.0, now, dir = 1), 1e-6)
        assertEquals(2028.0, ReplayMath.snapSeekCam(spans, 5000.0, now, dir = -1), 1e-6)
        assertEquals(1000.0, ReplayMath.snapSeekCam(spans, 500.0, now, dir = -1), 1e-6)
        assertEquals(2100.0, ReplayMath.snapSeekCam(emptyList(), 5000.0, now, dir = 0), 1e-6)
    }

    @Test
    fun parseSightingTarget_acceptsValidAndRejectsJunk() {
        assertEquals(SightingTarget(RecCam.CAT3HD, 2005000L), ReplayMath.parseSightingTarget("cat3", "2005000"))
        assertEquals(SightingTarget(RecCam.CAT, 1010000L), ReplayMath.parseSightingTarget("cat", 1010000L))
        assertNull(ReplayMath.parseSightingTarget("nope", 1010000L))
        assertNull(ReplayMath.parseSightingTarget("cat", ""))
        assertNull(ReplayMath.parseSightingTarget("cat", 0L))
        assertNull(ReplayMath.parseSightingTarget(null, 1010000L))
    }

    @Test
    fun sightingSeek_mapsDetectorCamAndLeadsByFiveSeconds() {
        val map = mapOf(
            RecCam.CAT to idx(RecCam.CAT, spans),
            RecCam.CAT3HD to idx(RecCam.CAT3HD, listOf(RecSpan(2000.0, 0.0, 30.0))),
        )
        assertEquals(SightingSeekResult(RecCam.CAT, 1005.0), ReplayMath.sightingSeek(map, "cat", 1010_000L, 2100.0))
        assertEquals(SightingSeekResult(RecCam.CAT3HD, 2000.0), ReplayMath.sightingSeek(map, "cat3", 2005_000L, 2100.0))
        assertEquals(SightingSeekResult(RecCam.CAT, 1058.0), ReplayMath.sightingSeek(map, "cat", 1505_000L, 2100.0))
        assertEquals(RecCam.CAT3HD, RecCam.fromAny("cat3"))
        assertNull(RecCam.fromAny("nope"))
        assertNull(ReplayMath.sightingSeek(map, "nope", 1010_000L, 2100.0))
    }

    @Test
    fun sightingSeek_fallsBackWhenOutside24hWindow() {
        val now = 1_700_000_000.0
        val map = mapOf(
            RecCam.CAT to idx(RecCam.CAT, listOf(RecSpan(now - 2000.0, 0.0, 100.0))),
        )
        val result = ReplayMath.sightingSeek(map, "cat", ((now - 10 * 3600) * 1000).toLong(), now)
        assertEquals(SightingSeekResult(RecCam.CAT, now - 2000.0), result)
    }

    @Test
    fun visitsOf_mergesSightingsWithin90SecondsPerCam() {
        val visits = ReplayMath.visitsOf(
            listOf(
                Sighting(100.0, RecCam.CAT),
                Sighting(150.0, RecCam.CAT),
                Sighting(120.0, RecCam.CAT3HD),
                Sighting(400.0, RecCam.CAT),
            ),
        )
        assertEquals(
            listOf(
                Visit(RecCam.CAT, 100.0, 150.0),
                Visit(RecCam.CAT3HD, 120.0, 120.0),
                Visit(RecCam.CAT, 400.0, 400.0),
            ),
            visits,
        )
    }

    @Test
    fun parseSightings_mapsDetectorCamsAndDropsInvalid() {
        val parsed = ReplayMath.parseSightings(
            SightingsResponse(
                enabled = true,
                sightings = listOf(
                    RawSighting(ts = 5.0, cam = "cat3"),
                    RawSighting(ts = 1.0, cam = "cat"),
                    RawSighting(ts = null, cam = "cat"),
                    RawSighting(ts = 2.0, cam = "unknown"),
                ),
            ),
        )
        assertEquals(true, parsed.enabled)
        assertEquals(
            listOf(
                Sighting(1.0, RecCam.CAT),
                Sighting(5.0, RecCam.CAT3HD),
            ),
            parsed.sightings,
        )
        assertEquals(ParsedSightings(null, emptyList()), ReplayMath.parseSightings(null))
    }

    @Test
    fun needsReload_triggersOnlyNearEndOfOlderSnapshot() {
        val old = idx(RecCam.CAT, spans, b = "b1")
        val newer = idx(RecCam.CAT, spans + RecSpan(2030.0, 90.0, 60.0), b = "b2")
        assertFalse(ReplayMath.needsReload(old, newer, 2010.0, 1f))
        assertTrue(ReplayMath.needsReload(old, newer, 2029.0, 1f))
        assertFalse(ReplayMath.needsReload(old, idx(RecCam.CAT, spans, b = "b1"), 2029.0, 1f))
    }

    @Test
    fun ticks_alignToLocalTime() {
        val res = ReplayMath.ticks(t0 = 0.0, t1 = 7200.0, spp = 3600.0 / 300.0, tzOffsetS = 0.0)
        assertEquals(900.0, res.step, 1e-6)
        assertEquals(0.0, res.positions.first(), 1e-6)
        assertEquals(9, res.positions.size)
    }

    @Test
    fun playlistUrl_formatsAvcAndHevcCorrectly() {
        val hevcIdx = RecIndex(cam = "cat3hd", codec = "hevc", a = "seg1", b = "seg99", spans = spans)
        assertEquals(
            "https://pc.example.com/rec/cat3hd/index.m3u8?a=seg1&b=seg99",
            ReplayMath.playlistUrl("https://pc.example.com/", hevcIdx, avc = false),
        )
        assertEquals(
            "https://pc.example.com/rec/cat3hd/index.m3u8?a=seg1&b=seg99&v=avc",
            ReplayMath.playlistUrl("https://pc.example.com", hevcIdx, avc = true),
        )
    }

    @Test
    fun fmtSpan_formatsHoursAndMinutes() {
        assertEquals("24 h", ReplayMath.fmtSpan(ReplayMath.DAY))
        assertEquals("6 h", ReplayMath.fmtSpan(6 * 3600.0))
        assertEquals("1 h", ReplayMath.fmtSpan(3600.0))
        assertEquals("10 min", ReplayMath.fmtSpan(600.0))
        assertEquals("1.5 h", ReplayMath.fmtSpan(5400.0))
    }

    @Test
    fun extractYoutubeLink_extractsFromSharedText() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            CamsConfig.extractYoutubeLink("Cat relaxation music https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12",
            CamsConfig.extractYoutubeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12"),
        )
        assertNull(CamsConfig.extractYoutubeLink("just some plain text"))
    }

    @Test
    fun webRtcHelpers_wsUrlAndAnswerVideoCodec() {
        assertEquals(
            "wss://pc.example.com/api/ws?src=cat3hd",
            WebRtcRuntime.wsUrl("https://pc.example.com/", "cat3hd"),
        )
        assertEquals(
            "ws://10.0.0.2:1984/api/ws?src=cat",
            WebRtcRuntime.wsUrl("http://10.0.0.2:1984", "cat"),
        )
        val sdp = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96 98
            a=rtpmap:96 H265/90000
            a=rtpmap:98 H264/90000
        """.trimIndent()
        assertEquals("H265", WebRtcRuntime.answerVideoCodec(sdp))
        assertNull(WebRtcRuntime.answerVideoCodec(null))
    }

    @Test
    fun replayEngine_loadsIndicesSkipsCyclesSpeedAndSeeksSightings() = runTest {
        val now = 2100.0
        val fakeDataSource = object : ReplayDataSource {
            override fun baseUrl(): String = "https://pc.example.com"
            override suspend fun fetchRecIndex(cam: RecCam): RecIndex = when (cam) {
                RecCam.CAT -> idx(RecCam.CAT, spans)
                RecCam.CAT2 -> idx(RecCam.CAT2, listOf(RecSpan(2000.0, 0.0, 30.0)))
                RecCam.CAT3HD -> idx(RecCam.CAT3HD, listOf(RecSpan(2000.0, 0.0, 30.0)), codec = "hevc")
            }

            override suspend fun fetchSightings(sinceEpochSec: Long): SightingsResponse = SightingsResponse(
                enabled = true,
                sightings = listOf(
                    RawSighting(ts = 1020.0, cam = "cat"),
                    RawSighting(ts = 2015.0, cam = "cat3"),
                ),
            )
        }

        val seeks = mutableListOf<Pair<RecCam, Double>>()
        val engine = ReplayEngine(
            scope = backgroundScope,
            dataSource = fakeDataSource,
            hevcOk = false,
            nowSeconds = { now },
            nowMillis = { (now * 1000).toLong() },
        )
        RecCam.ALL.forEach { cam ->
            engine.setHandle(
                cam,
                object : ReplayPlayerHandle {
                    override fun seek(seconds: Double, exact: Boolean) {
                        seeks.add(cam to seconds)
                    }

                    override suspend fun snapshot(label: String): String = "content://snap/$label"
                },
            )
        }

        engine.start()
        runCurrent()

        val initial = engine.state.value
        assertTrue(initial.wallTime in 1000.0..2030.0)
        assertNotNull(initial.sources[RecCam.CAT])
        // hevcOk = false -> cat3hd should request &v=avc transcode
        assertTrue(initial.sources[RecCam.CAT3HD]!!.url.endsWith("&v=avc"))
        assertEquals(2, initial.visits.size)

        // Cycle playback speeds: 1x -> 2x -> 4x -> 1x
        assertEquals(1f, engine.state.value.rate, 1e-6f)
        engine.cycleRate()
        assertEquals(2f, engine.state.value.rate, 1e-6f)
        engine.cycleRate()
        assertEquals(4f, engine.state.value.rate, 1e-6f)
        engine.cycleRate()
        assertEquals(1f, engine.state.value.rate, 1e-6f)

        // Sighting seek switches big camera to cat3hd and seeks 5s ahead of sighting
        assertTrue(engine.seekSighting("cat3", 2015_000L))
        assertEquals(RecCam.CAT3HD, engine.state.value.big)
        assertEquals(2010.0, engine.state.value.wallTime, 1e-6)
        assertTrue(seeks.any { it.first == RecCam.CAT3HD && abs(it.second - 10.0) < 1e-6 })

        // Skip +10s advances wallTime to 2020.0
        engine.skip(10.0)
        assertEquals(2020.0, engine.state.value.wallTime, 1e-6)

        // Stop releases sources
        engine.stop()
        advanceTimeBy(300L)
        assertNull(engine.state.value.sources[RecCam.CAT])
        assertNull(engine.state.value.sources[RecCam.CAT3HD])
    }
}
