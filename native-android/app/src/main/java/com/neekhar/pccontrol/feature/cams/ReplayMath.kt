package com.neekhar.pccontrol.feature.cams

import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin replay timeline math, ported from `src/lib/cams/replay.ts`.
 *
 * camrec serves each cam's last 24 h as one continuous VOD HLS playlist
 * (`index.m3u8?a=<first>&b=<last>`): chunk gaps are omitted so media time runs
 * continuously while wall time jumps across gaps. `index.json`'s spans map
 * between them: `(wallStart, mediaStart, duration)` per continuous recording stretch.
 */
enum class RecCam(val id: String, val displayName: String, val shortName: String) {
    CAT("cat", "Living room", "Living"),
    CAT2("cat2", "Hall", "Hall"),
    CAT3HD("cat3hd", "Food bowl", "Bowl");

    companion object {
        val ALL: List<RecCam> = listOf(CAT, CAT2, CAT3HD)

        private val DET_TO_REC: Map<String, RecCam> = mapOf(
            "cat" to CAT,
            "cat2" to CAT2,
            "cat3" to CAT3HD,
            "cat3hd" to CAT3HD,
        )

        /** Maps detector/live cam id (`cat`, `cat2`, `cat3`, `cat3hd`) to a recorder cam, or null. */
        fun fromAny(cam: String?): RecCam? = if (cam != null) DET_TO_REC[cam] else null
    }
}

data class RecSpan(
    val wallStart: Double,
    val mediaStart: Double,
    val duration: Double,
) {
    val wallEnd: Double get() = wallStart + duration
}

data class RecIndex(
    val cam: String,
    val name: String? = null,
    val codec: String = "avc",
    val recording: Boolean = false,
    val now: Double? = null,
    val a: String? = null,
    val b: String? = null,
    val spans: List<RecSpan> = emptyList(),
)

data class Sighting(
    val ts: Double,
    val cam: RecCam,
    val confidence: Double? = null,
)

data class Visit(
    val cam: RecCam,
    val start: Double,
    val end: Double,
)

data class SightingTarget(
    val cam: RecCam,
    val tsMs: Long,
)

data class SightingSeekResult(
    val cam: RecCam,
    val wallTime: Double,
)

data class ParsedSightings(
    val enabled: Boolean?,
    val sightings: List<Sighting>,
)

data class TimelineTicks(
    val step: Double,
    val positions: List<Double>,
)

sealed interface WallToMedia {
    data class Hit(val mediaTime: Double) : WallToMedia
    data class Gap(val prev: Double?, val next: Double?) : WallToMedia
}

object ReplayMath {
    const val DAY = 86_400.0
    const val VISIT_GAP = 90.0
    const val SEEK_LEAD = 5.0

    val ZOOMS: List<Double> = listOf(DAY, 6 * 3600.0, 3600.0, 600.0)
    val SPEEDS: List<Float> = listOf(1f, 2f, 4f)
    private val TICK_STEPS: List<Double> = listOf(
        10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0, 10800.0, 21600.0,
    )

    fun clamp(x: Double, lo: Double, hi: Double): Double = min(max(x, lo), hi)

    /** Wall time (epoch s) -> media time (s), or [WallToMedia.Gap] with surrounding boundaries. */
    fun w2m(spans: List<RecSpan>, wallTime: Double): WallToMedia {
        for (i in spans.indices) {
            val s = spans[i]
            if (wallTime < s.wallStart) {
                val prev = if (i > 0) spans[i - 1].wallEnd else null
                return WallToMedia.Gap(prev = prev, next = s.wallStart)
            }
            if (wallTime < s.wallEnd) {
                return WallToMedia.Hit(s.mediaStart + (wallTime - s.wallStart))
            }
        }
        val prev = spans.lastOrNull()?.wallEnd
        return WallToMedia.Gap(prev = prev, next = null)
    }

    /** Media time (s) -> wall time (epoch s), or null before the first span. */
    fun m2w(spans: List<RecSpan>, mediaTime: Double): Double? {
        var best: RecSpan? = null
        for (s in spans) {
            if (mediaTime < s.mediaStart - 0.05) break
            best = s
        }
        return best?.let { it.wallStart + clamp(mediaTime - it.mediaStart, 0.0, it.duration) }
    }

    /** Where a player should start for wall time [wallTime]: inside footage, next footage, or near end. */
    fun startMedia(spans: List<RecSpan>, wallTime: Double): Double {
        return when (val r = w2m(spans, wallTime)) {
            is WallToMedia.Hit -> r.mediaTime
            is WallToMedia.Gap -> {
                if (r.next != null) {
                    (w2m(spans, r.next) as? WallToMedia.Hit)?.mediaTime ?: 0.0
                } else {
                    val last = spans.lastOrNull() ?: return 0.0
                    val nearEnd = (w2m(spans, last.wallEnd - 3.0) as? WallToMedia.Hit)?.mediaTime ?: 0.0
                    max(0.0, nearEnd)
                }
            }
        }
    }

    fun latestEnd(
        idx: Map<RecCam, RecIndex>,
        cams: List<RecCam> = RecCam.ALL,
    ): Double {
        var best = 0.0
        for (c in cams) {
            val end = idx[c]?.spans?.lastOrNull()?.wallEnd ?: 0.0
            if (end > best) best = end
        }
        return best
    }

    fun earliestStart(
        idx: Map<RecCam, RecIndex>,
        cams: List<RecCam> = RecCam.ALL,
    ): Double {
        var best = Double.POSITIVE_INFINITY
        for (c in cams) {
            val start = idx[c]?.spans?.firstOrNull()?.wallStart ?: Double.POSITIVE_INFINITY
            if (start < best) best = start
        }
        return best
    }

    /**
     * Explicit seek across all cameras: clamp to the last 24 h of recorded footage; if no camera
     * has footage at [wallTime], snap to the nearest recorded moment in the direction of travel
     * ([dir] < 0: end of prior footage - 2s; otherwise next start).
     */
    fun snapSeek(
        idx: Map<RecCam, RecIndex>,
        wallTime: Double,
        now: Double,
        dir: Int = 0,
        cams: List<RecCam> = RecCam.ALL,
    ): Double {
        val latest = latestEnd(idx, cams)
        val hi = if (latest > 0.0) latest else now
        val lo = max(now - DAY, min(earliestStart(idx, cams), hi))
        var t = clamp(wallTime, lo, hi)
        val results = cams.map { c -> w2m(idx[c]?.spans.orEmpty(), t) }
        if (results.isNotEmpty() && results.all { it is WallToMedia.Gap }) {
            val gaps = results.filterIsInstance<WallToMedia.Gap>()
            val nexts = gaps.mapNotNull { it.next }
            val prevs = gaps.mapNotNull { it.prev }
            t = when {
                dir < 0 && prevs.isNotEmpty() -> prevs.max() - 2.0
                nexts.isNotEmpty() -> nexts.min()
                prevs.isNotEmpty() -> prevs.max() - 2.0
                else -> t
            }
        }
        return t
    }

    /** Seek within a single camera's spans; clamps to last 24 h and snaps out of gaps. */
    fun snapSeekCam(
        spans: List<RecSpan>,
        at: Double,
        now: Double,
        dir: Int = 0,
    ): Double {
        if (spans.isEmpty()) return clamp(at, now - DAY, now)
        val first = spans.first().wallStart
        val last = spans.last().wallEnd
        var t = clamp(at, max(now - DAY, min(first, last)), last)
        val r = w2m(spans, t)
        if (r is WallToMedia.Gap) {
            t = if (dir < 0) {
                if (r.prev != null) max(first, r.prev - 2.0) else (r.next ?: t)
            } else {
                r.next ?: if (r.prev != null) max(first, r.prev - 2.0) else t
            }
        }
        return t
    }

    /** Parse notification/detection target cam + epoch ms safely. */
    fun parseSightingTarget(cam: String?, tsMs: Long?): SightingTarget? {
        val rc = RecCam.fromAny(cam) ?: return null
        if (tsMs == null || tsMs <= 0L) return null
        return SightingTarget(cam = rc, tsMs = tsMs)
    }

    fun parseSightingTarget(cam: String?, tsRaw: String?): SightingTarget? {
        val parsed = tsRaw?.trim()?.toLongOrNull() ?: return null
        return parseSightingTarget(cam, parsed)
    }

    /** Compute camera + wall time (~[lead]s before sighting) for a cat alert or detection jump. */
    fun sightingSeek(
        idx: Map<RecCam, RecIndex>,
        cam: String?,
        tsMs: Long?,
        now: Double,
        lead: Double = SEEK_LEAD,
    ): SightingSeekResult? {
        val target = parseSightingTarget(cam, tsMs) ?: return null
        val at = target.tsMs / 1000.0 - lead
        val spans = idx[target.cam]?.spans.orEmpty()
        val wallTime = if (spans.isNotEmpty()) {
            snapSeekCam(spans, at, now, -1)
        } else {
            snapSeek(idx, at, now, -1)
        }
        return SightingSeekResult(cam = target.cam, wallTime = wallTime)
    }

    /** Group runs of sightings on the same camera within [VISIT_GAP] seconds of each other. */
    fun visitsOf(sightings: List<Sighting>): List<Visit> {
        val open = mutableMapOf<RecCam, Visit>()
        val out = mutableListOf<Visit>()
        for (s in sightings.sortedBy { it.ts }) {
            val cur = open[s.cam]
            if (cur != null && s.ts - cur.end <= VISIT_GAP) {
                val updated = cur.copy(end = s.ts)
                open[s.cam] = updated
                val idx = out.indexOfLast { it.cam == s.cam && it.start == cur.start }
                if (idx >= 0) out[idx] = updated
            } else {
                val created = Visit(cam = s.cam, start = s.ts, end = s.ts)
                open[s.cam] = created
                out.add(created)
            }
        }
        return out.sortedBy { it.start }
    }

    /** Convert `/detect/sightings` payload into sorted sightings for recorded cameras. */
    fun parseSightings(response: SightingsResponse?): ParsedSightings {
        if (response == null) return ParsedSightings(enabled = null, sightings = emptyList())
        val parsed = response.sightings.mapNotNull { raw ->
            val ts = raw.ts ?: return@mapNotNull null
            if (!ts.isFinite()) return@mapNotNull null
            val rc = RecCam.fromAny(raw.cam) ?: return@mapNotNull null
            Sighting(ts = ts, cam = rc, confidence = raw.confidence)
        }.sortedBy { it.ts }
        return ParsedSightings(enabled = response.enabled, sightings = parsed)
    }

    /** VOD HLS playlist URL for one snapshot (`avc = true` requests server H.264 transcode for HEVC cams). */
    fun playlistUrl(base: String, idx: RecIndex, avc: Boolean): String {
        val cleanBase = base.trimEnd('/')
        val a = URLEncoder.encode(idx.a.orEmpty(), "UTF-8")
        val b = URLEncoder.encode(idx.b.orEmpty(), "UTF-8")
        val suffix = if (avc) "&v=avc" else ""
        return "$cleanBase/rec/${idx.cam}/index.m3u8?a=$a&b=$b$suffix"
    }

    /**
     * Whether a player holding an older snapshot should reload as the playhead approaches
     * the end of its snapshot and newer footage has arrived.
     */
    fun needsReload(
        snap: RecIndex,
        latest: RecIndex?,
        wallTime: Double,
        rate: Float,
    ): Boolean {
        if (latest == null || latest.b == snap.b || latest.spans.isEmpty() || snap.spans.isEmpty()) {
            return false
        }
        val sEnd = snap.spans.last().wallEnd
        val lEnd = latest.spans.last().wallEnd
        return wallTime > sEnd - max(2.0, 2.0 * rate) && (lEnd - sEnd > 10.0 || wallTime > sEnd + 1.0)
    }

    /** Local-time aligned ribbon ticks with spacing >= [minPx] pixels. */
    fun ticks(
        t0: Double,
        t1: Double,
        spp: Double,
        tzOffsetS: Double,
        minPx: Double = 64.0,
    ): TimelineTicks {
        val safeSpp = if (spp > 0.0) spp else 1.0
        val step = TICK_STEPS.firstOrNull { it / safeSpp >= minPx } ?: 21_600.0
        val positions = mutableListOf<Double>()
        var t = ceil((t0 + tzOffsetS) / step) * step - tzOffsetS
        while (t <= t1 + 1e-6) {
            positions.add(t)
            t += step
        }
        return TimelineTicks(step = step, positions = positions)
    }

    /** Format timeline zoom span (e.g. "24 h", "1.5 h", "10 min"). */
    fun fmtSpan(seconds: Double): String {
        return if (seconds >= 3600.0) {
            val hours = seconds / 3600.0
            val whole = hours.roundToInt().toDouble()
            if (kotlin.math.abs(hours - whole) < 0.05) {
                "${whole.toInt()} h"
            } else {
                String.format(java.util.Locale.US, "%.1f h", hours)
            }
        } else {
            "${(seconds / 60.0).roundToInt()} min"
        }
    }
}
