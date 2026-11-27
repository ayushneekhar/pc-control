package com.neekhar.pccontrol.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageSummaryTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun makeAcc(
        id: String,
        harness: String = "gemini",
        label: String = id,
        email: String? = null,
        signedIn: Boolean = true,
        status: String? = null,
        error: String? = null,
        fiveHour: AgentUsageWindow? = null,
        weekly: AgentUsageWindow? = null,
    ) = AgentUsageAccount(
        id = id,
        harness = harness,
        label = label,
        email = email,
        signedIn = signedIn,
        status = status,
        error = error,
        fiveHour = fiveHour,
        weekly = weekly,
    )

    @Test
    fun groupAccountsByProviderOrdersClaudeAntigravityOpenCode() {
        val accounts = listOf(
            makeAcc(id = "agy-1", harness = "gemini", label = "Antigravity 1"),
            makeAcc(id = "opencode", harness = "opencode", label = "OpenCode / DeepSeek"),
            makeAcc(id = "agy-2", harness = "gemini", label = "Antigravity 2"),
            makeAcc(id = "claude", harness = "claude", label = "Claude"),
        )
        val groups = UsageSummary.groupAccountsByProvider(accounts)
        assertEquals(3, groups.size)
        assertEquals(UsageProviderKey.CLAUDE, groups[0].key)
        assertEquals("Claude", groups[0].name)
        assertEquals(listOf("claude"), groups[0].accounts.map { it.id })

        assertEquals(UsageProviderKey.GEMINI, groups[1].key)
        assertEquals("Antigravity", groups[1].name)
        assertEquals(listOf("agy-1", "agy-2"), groups[1].accounts.map { it.id })

        assertEquals(UsageProviderKey.OPENCODE, groups[2].key)
        assertEquals("OpenCode", groups[2].name)
        assertEquals(listOf("opencode"), groups[2].accounts.map { it.id })
    }

    @Test
    fun summarizeWindowGroupComputesAggregateUnderPaceReplenishmentAndTicks() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        fun inMins(m: Long) = now.plusSeconds(m * 60L).toString()

        val accounts = listOf(
            makeAcc(
                id = "agy-1",
                label = "Antigravity 1",
                fiveHour = AgentUsageWindow(remainingPct = 89.0, usedPct = 11.0, resetsAt = inMins(2 * 60 + 44)),
            ),
            makeAcc(
                id = "agy-2",
                label = "Antigravity 2",
                fiveHour = AgentUsageWindow(remainingPct = 96.0, usedPct = 4.0, resetsAt = inMins(4 * 60 + 4)),
            ),
            makeAcc(
                id = "agy-3",
                label = "Antigravity 3",
                fiveHour = AgentUsageWindow(remainingPct = 90.0, usedPct = 10.0, resetsAt = inMins(2 * 60 + 38)),
            ),
            makeAcc(
                id = "agy-4",
                label = "Antigravity 4",
                fiveHour = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, resetsAt = inMins(4 * 60 + 35)),
            ),
        )

        val summary = UsageSummary.summarizeWindowGroup(accounts, UsageWindowKey.FIVE_HOUR, now)
        assertEquals("5-hour", summary.title)
        assertEquals(94, summary.aggregateRemainingPct)
        assertEquals(PaceStatus.UNDER_PACE, summary.paceStatus)
        assertEquals(
            ReplenishmentInfo(gainPct = 3, durationLabel = "2h 38m", resetsInMs = (2 * 60 + 38) * 60_000L),
            summary.replenishment,
        )
        assertEquals("2h 44m", summary.rows[0].countdownLabel)
        assertNull(summary.rows[3].countdownLabel)
        assertTrue((summary.rows[0].expectedRemainingPct ?: 0.0) > 50.0)
    }

    @Test
    fun summarizeWindowGroupDetectsOnPaceAndAheadOfPaceWeekly() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        fun inHours(h: Long) = now.plusSeconds(h * 3600L).toString()

        val onPace = UsageSummary.summarizeWindowGroup(
            listOf(
                makeAcc(
                    id = "claude",
                    harness = "claude",
                    label = "Claude",
                    weekly = AgentUsageWindow(remainingPct = 68.0, usedPct = 32.0, resetsAt = inHours(4 * 24 + 10)),
                ),
            ),
            UsageWindowKey.WEEKLY,
            now,
        )
        assertEquals(68, onPace.aggregateRemainingPct)
        assertEquals(PaceStatus.ON_PACE, onPace.paceStatus)
        assertEquals(32, onPace.replenishment?.gainPct)
        assertEquals("4d 10h", onPace.replenishment?.durationLabel)
        assertEquals("4d 10h", onPace.rows[0].countdownLabel)

        val ahead = UsageSummary.summarizeWindowGroup(
            listOf(
                makeAcc(
                    id = "agy-1",
                    label = "Antigravity 1",
                    weekly = AgentUsageWindow(remainingPct = 80.0, usedPct = 20.0, resetsAt = inHours(155)),
                ),
            ),
            UsageWindowKey.WEEKLY,
            now,
        )
        assertEquals(PaceStatus.AHEAD_OF_PACE, ahead.paceStatus)
        assertEquals("6d 11h", ahead.rows[0].countdownLabel)
    }

    @Test
    fun summarizeWindowGroupHandlesSignedOutErrorMissingAndIdle() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val accounts = listOf(
            makeAcc(id = "a1", signedIn = false, status = "not signed in"),
            makeAcc(id = "a2", signedIn = true, error = "HTTP 429"),
            makeAcc(id = "a3", signedIn = true, fiveHour = null),
            makeAcc(
                id = "a4",
                signedIn = true,
                fiveHour = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, status = "Quota available"),
            ),
        )

        val summary = UsageSummary.summarizeWindowGroup(accounts, UsageWindowKey.FIVE_HOUR, now)
        assertEquals(100, summary.aggregateRemainingPct)
        assertNull(summary.paceStatus)
        assertNull(summary.replenishment)
        assertEquals(
            listOf(
                AccountWindowRowState.SIGNED_OUT,
                AccountWindowRowState.ERROR,
                AccountWindowRowState.MISSING,
                AccountWindowRowState.OK,
            ),
            summary.rows.map { it.state },
        )
        assertNull(summary.rows[3].expectedRemainingPct)
        assertNull(summary.rows[3].countdownLabel)
    }

    @Test
    fun formatLocalDateTimeAndExactResetDateTimeFormatCleanly() {
        assertEquals("Dec 1, 2026, 1:33 AM", UsageSummary.formatLocalDateTime(ZonedDateTime.of(2026, 12, 1, 1, 33, 0, 0, utc)))
        assertEquals("Jan 15, 2026, 12:05 AM", UsageSummary.formatLocalDateTime(ZonedDateTime.of(2026, 1, 15, 0, 5, 0, 0, utc)))
        assertEquals("Jun 9, 2026, 12:00 PM", UsageSummary.formatLocalDateTime(ZonedDateTime.of(2026, 6, 9, 12, 0, 0, 0, utc)))
        assertEquals("Jun 9, 2026, 11:45 PM", UsageSummary.formatLocalDateTime(ZonedDateTime.of(2026, 6, 9, 23, 45, 0, 0, utc)))

        val now = ZonedDateTime.of(2026, 11, 25, 4, 30, 0, 0, utc).toInstant()
        val future = ZonedDateTime.of(2026, 12, 1, 1, 33, 0, 0, utc).toInstant()
        val past = ZonedDateTime.of(2026, 11, 24, 4, 30, 0, 0, utc).toInstant()

        assertEquals("Dec 1, 2026, 1:33 AM", UsageSummary.formatExactResetDateTime(future.toString(), now, utc))
        assertNull(UsageSummary.formatExactResetDateTime(past.toString(), now, utc))
        assertNull(UsageSummary.formatExactResetDateTime(null, now, utc))
        assertNull(UsageSummary.formatExactResetDateTime("not-a-date", now, utc))
    }

    @Test
    fun formatUpdatedAgoFormatsRelativeTimestamps() {
        val nowMs = 1_764_000_000_000L
        val nowSec = nowMs / 1000.0
        assertEquals("Updated just now", UsageSummary.formatUpdatedAgo(nowSec - 2, nowMs))
        assertEquals("Updated 12s ago", UsageSummary.formatUpdatedAgo(nowSec - 12, nowMs))
        assertEquals("Updated 3m ago", UsageSummary.formatUpdatedAgo(nowSec - 180, nowMs))
        assertEquals("Updated 1h 5m ago", UsageSummary.formatUpdatedAgo(nowSec - 3900, nowMs))
        assertEquals("—", UsageSummary.formatUpdatedAgo(null, nowMs))
    }

    @Test
    fun getAccountDetailSummaryComputesMultiAccountPoolDetailAndProjections() {
        val now = ZonedDateTime.of(2026, 11, 25, 4, 33, 0, 0, utc).toInstant()
        fun inHours(h: Long) = now.plusSeconds(h * 3600L).toString()

        val accounts = listOf(
            makeAcc(
                id = "antigravity-1",
                label = "Antigravity 1",
                fiveHour = AgentUsageWindow(remainingPct = 90.0, usedPct = 10.0, resetsAt = inHours(3)),
                weekly = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, status = "Quota available"),
            ),
            makeAcc(
                id = "antigravity-2",
                label = "Antigravity 2",
                fiveHour = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, status = "Quota available"),
                weekly = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, status = "Quota available"),
            ),
            makeAcc(
                id = "antigravity-3",
                label = "Antigravity 3",
                fiveHour = AgentUsageWindow(remainingPct = 50.0, usedPct = 50.0, resetsAt = inHours(4)),
                weekly = AgentUsageWindow(remainingPct = 100.0, usedPct = 0.0, status = "Quota available"),
            ),
            makeAcc(
                id = "antigravity-4",
                label = "Antigravity 4",
                email = "user@example.com",
                fiveHour = AgentUsageWindow(remainingPct = 80.0, usedPct = 20.0, resetsAt = inHours(2)),
                weekly = AgentUsageWindow(remainingPct = 87.0, usedPct = 13.0, resetsAt = inHours(141)),
            ),
        )

        val detail = UsageSummary.getAccountDetailSummary(accounts, "antigravity-4", now, utc)
        assertNotNull(detail)
        detail!!
        assertEquals(UsageProviderKey.GEMINI, detail.providerKey)
        assertEquals("Antigravity", detail.providerName)
        assertEquals("Google account · Antigravity", detail.providerSubtitle)
        assertEquals(4, detail.poolIndex)
        assertEquals(4, detail.poolSize)

        val w = detail.weekly
        assertEquals("Gemini · Weekly", w.headerTitle)
        assertEquals(84, w.evenPaceExpectedLeftPct)
        assertEquals(3, w.paceDeltaPct)
        assertEquals(PaceStatus.ON_PACE, w.paceStatus)
        assertEquals("5d 21h", w.countdownLabel)
        assertEquals("Dec 1, 2026, 1:33 AM", w.resetDateTimeLabel)
        assertEquals(3, w.poolRestorePct)
        assertEquals(22, w.poolSharePct)
        assertTrue(w.projection is AccountWindowProjection.Headroom)
        assertEquals("On track to finish with ~19% left", w.projection?.label)

        val d3 = UsageSummary.getAccountDetailSummary(accounts, "antigravity-3", now, utc)!!
        assertEquals(PaceStatus.AHEAD_OF_PACE, d3.fiveHour.paceStatus)
        assertEquals(-30, d3.fiveHour.paceDeltaPct)
        assertTrue(d3.fiveHour.projection is AccountWindowProjection.RunsOut)
        assertEquals("At current pace, runs out in ~1h", d3.fiveHour.projection?.label)
    }

    @Test
    fun formatSingleAccountWindowMetaFormatsCleanSingleLine() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val claude = makeAcc(
            id = "claude",
            harness = "claude",
            label = "Claude",
            fiveHour = AgentUsageWindow(
                remainingPct = 98.0,
                usedPct = 2.0,
                resetsAt = now.plusSeconds(69L * 60L).toString(),
            ),
            weekly = AgentUsageWindow(
                remainingPct = 100.0,
                usedPct = 0.0,
                status = "Quota available",
            ),
        )

        val fiveHourRow = UsageSummary.summarizeWindowGroup(listOf(claude), UsageWindowKey.FIVE_HOUR, now).rows.first()
        assertEquals("98% left · resets 1h 9m", UsageSummary.formatSingleAccountWindowMeta(fiveHourRow))

        val weeklyRow = UsageSummary.summarizeWindowGroup(listOf(claude), UsageWindowKey.WEEKLY, now).rows.first()
        assertEquals("100% left", UsageSummary.formatSingleAccountWindowMeta(weeklyRow))

        val signedOutRow = UsageSummary.summarizeWindowGroup(
            listOf(makeAcc(id = "so", harness = "claude", signedIn = false)),
            UsageWindowKey.FIVE_HOUR,
            now,
        ).rows.first()
        assertEquals("Not signed in", UsageSummary.formatSingleAccountWindowMeta(signedOutRow))

        val errRow = UsageSummary.summarizeWindowGroup(
            listOf(makeAcc(id = "er", harness = "claude", error = "429")),
            UsageWindowKey.FIVE_HOUR,
            now,
        ).rows.first()
        assertEquals("Error", UsageSummary.formatSingleAccountWindowMeta(errRow))
    }
}
