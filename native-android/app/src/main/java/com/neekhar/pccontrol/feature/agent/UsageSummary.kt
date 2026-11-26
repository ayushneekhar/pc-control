package com.neekhar.pccontrol.feature.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// ---- Serializable API models for /pc/api/agent/usage ----

@Serializable
data class AgentUsageWindow(
    @SerialName("remaining_pct") val remainingPct: Double? = null,
    @SerialName("used_pct") val usedPct: Double? = null,
    @SerialName("refreshes_in") val refreshesIn: String? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    val status: String? = null,
)

@Serializable
data class AgentUsageDeepSeek(
    @SerialName("is_available") val isAvailable: Boolean? = null,
    val currency: String? = null,
    @SerialName("total_balance") val totalBalance: Double? = null,
    val formatted: String? = null,
    val error: String? = null,
)

@Serializable
data class AgentUsageZenModel(
    val model: String,
    val messages: Int = 0,
    val cost: String? = null,
)

@Serializable
data class AgentUsageZen(
    val status: String = "",
    val days: Int? = null,
    val sessions: Int? = null,
    val messages: Int? = null,
    @SerialName("total_cost") val totalCost: String? = null,
    @SerialName("zen_messages") val zenMessages: Int? = null,
    val models: List<AgentUsageZenModel>? = null,
    val error: String? = null,
)

@Serializable
data class AgentUsageAccount(
    val id: String,
    val harness: String, // "claude" | "gemini" | "opencode"
    val label: String,
    val email: String? = null,
    @SerialName("signed_in") val signedIn: Boolean = true,
    val status: String? = null,
    val error: String? = null,
    @SerialName("five_hour") val fiveHour: AgentUsageWindow? = null,
    val weekly: AgentUsageWindow? = null,
    val deepseek: AgentUsageDeepSeek? = null,
    val zen: AgentUsageZen? = null,
)

@Serializable
data class AgentUsageResponse(
    val ts: Double = 0.0,
    val cached: Boolean = false,
    val stale: Boolean? = null,
    val accounts: List<AgentUsageAccount> = emptyList(),
)

// ---- Domain summary models ----

enum class UsageWindowKey(val wireValue: String, val title: String, val durationSeconds: Long) {
    FIVE_HOUR("five_hour", "5-hour", 5L * 3600L),
    WEEKLY("weekly", "Weekly", 7L * 86400L);

    companion object {
        fun fromWire(value: String?): UsageWindowKey =
            if (value == "weekly") WEEKLY else FIVE_HOUR
    }
}

enum class UsageProviderKey(val wireValue: String, val displayName: String, val windowLabel: String, val subtitle: String) {
    CLAUDE("claude", "Claude", "Claude", "Anthropic · Claude"),
    GEMINI("gemini", "Antigravity", "Gemini", "Google account · Antigravity"),
    OPENCODE("opencode", "OpenCode", "OpenCode", "OpenCode · DeepSeek");

    companion object {
        fun forHarness(harness: String): UsageProviderKey = when (harness) {
            "claude" -> CLAUDE
            "opencode" -> OPENCODE
            else -> GEMINI
        }
    }
}

enum class PaceStatus(val label: String) {
    UNDER_PACE("Under pace"),
    ON_PACE("On pace"),
    AHEAD_OF_PACE("Ahead of pace"),
}

enum class AccountWindowRowState {
    OK,
    SIGNED_OUT,
    ERROR,
    MISSING,
}

data class UsageProviderGroup(
    val key: UsageProviderKey,
    val name: String,
    val accounts: List<AgentUsageAccount>,
)

data class AccountWindowState(
    val index: Int,
    val account: AgentUsageAccount,
    val state: AccountWindowRowState,
    val remainingPct: Double?,
    val usedPct: Double?,
    /** Clamped 2..98% position for the vertical pace tick mark, or null if no active future reset within the window. */
    val expectedRemainingPct: Double?,
    /** Unclamped 0..100% expected remaining % used for aggregate pace comparison. */
    val rawExpectedRemainingPct: Double?,
    val resetsInMs: Long?,
    /** Compact countdown ("2h 44m", "6d 11h"), omitted when 100% or no active reset. */
    val countdownLabel: String?,
)

data class ReplenishmentInfo(
    val gainPct: Int,
    val durationLabel: String,
    val resetsInMs: Long,
)

data class WindowGroupSummary(
    val windowKey: UsageWindowKey,
    val title: String,
    val aggregateRemainingPct: Int?,
    val paceStatus: PaceStatus?,
    val replenishment: ReplenishmentInfo?,
    val rows: List<AccountWindowState>,
)

sealed interface AccountWindowProjection {
    val label: String

    data class Headroom(
        val projectedRemainingPct: Int,
        override val label: String,
    ) : AccountWindowProjection

    data class RunsOut(
        val exhaustInMs: Double,
        override val label: String,
    ) : AccountWindowProjection

    data class Exhausted(
        override val label: String = "Quota exhausted until reset",
    ) : AccountWindowProjection
}

data class AccountWindowDetail(
    val windowKey: UsageWindowKey,
    val windowTitle: String,
    val headerTitle: String,
    val state: AccountWindowRowState,
    val remainingPct: Double?,
    val usedPct: Double?,
    val expectedRemainingPct: Double?,
    val rawExpectedRemainingPct: Double?,
    val evenPaceExpectedLeftPct: Int?,
    val paceDeltaPct: Int?,
    val paceStatus: PaceStatus?,
    val resetsInMs: Long?,
    val resetDateTimeLabel: String?,
    val countdownLabel: String?,
    val isFullIdle: Boolean,
    val poolSize: Int,
    val activePoolSize: Int,
    val poolRestorePct: Int?,
    val poolSharePct: Int?,
    val projection: AccountWindowProjection?,
)

data class AccountDetailSummary(
    val account: AgentUsageAccount,
    val providerKey: UsageProviderKey,
    val providerName: String,
    val providerSubtitle: String,
    val poolIndex: Int,
    val poolSize: Int,
    val fiveHour: AccountWindowDetail,
    val weekly: AccountWindowDetail,
)

/**
 * Pure AI usage aggregation, pace, replenishment, and projection logic,
 * ported from src/lib/usageSummary.ts.
 */
object UsageSummary {
    const val CACHE_AGENT_USAGE: String = "agent:usage"
    private const val MIN_PROJECTION_ELAPSED_FRACTION = 0.05
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val PROVIDER_ORDER = listOf(UsageProviderKey.CLAUDE, UsageProviderKey.GEMINI, UsageProviderKey.OPENCODE)

    private fun clamp(v: Double, min: Double, max: Double): Double = v.coerceIn(min, max)

    fun groupAccountsByProvider(accounts: List<AgentUsageAccount>): List<UsageProviderGroup> {
        val byKey = mutableMapOf<UsageProviderKey, MutableList<AgentUsageAccount>>()
        for (acc in accounts) {
            val key = UsageProviderKey.forHarness(acc.harness)
            byKey.getOrPut(key) { mutableListOf() }.add(acc)
        }
        return PROVIDER_ORDER.mapNotNull { key ->
            val list = byKey[key]
            if (!list.isNullOrEmpty()) UsageProviderGroup(key = key, name = key.displayName, accounts = list) else null
        }
    }

    private fun resolveResetsInMs(win: AgentUsageWindow, nowMs: Long): Long? {
        val parsed = FormatResetTime.parseIsoInstant(win.resetsAt)
        if (parsed != null) {
            val diff = parsed.toEpochMilli() - nowMs
            return if (diff > 0L) diff else null
        }
        if (!win.refreshesIn.isNullOrBlank()) {
            return FormatResetTime.parseDurationMs(win.refreshesIn)
        }
        return null
    }

    private fun AgentUsageAccount.windowFor(key: UsageWindowKey): AgentUsageWindow? = when (key) {
        UsageWindowKey.FIVE_HOUR -> fiveHour
        UsageWindowKey.WEEKLY -> weekly
    }

    fun summarizeWindowGroup(
        accounts: List<AgentUsageAccount>,
        windowKey: UsageWindowKey,
        now: Instant = Instant.now(),
    ): WindowGroupSummary {
        val nowMs = now.toEpochMilli()
        val windowMs = windowKey.durationSeconds * 1000.0

        val rows = accounts.mapIndexed { i, account ->
            val index = i + 1
            if (!account.signedIn) {
                return@mapIndexed AccountWindowState(
                    index = index,
                    account = account,
                    state = AccountWindowRowState.SIGNED_OUT,
                    remainingPct = null,
                    usedPct = null,
                    expectedRemainingPct = null,
                    rawExpectedRemainingPct = null,
                    resetsInMs = null,
                    countdownLabel = null,
                )
            }
            if (!account.error.isNullOrEmpty()) {
                return@mapIndexed AccountWindowState(
                    index = index,
                    account = account,
                    state = AccountWindowRowState.ERROR,
                    remainingPct = null,
                    usedPct = null,
                    expectedRemainingPct = null,
                    rawExpectedRemainingPct = null,
                    resetsInMs = null,
                    countdownLabel = null,
                )
            }

            val win = account.windowFor(windowKey)
            val remRaw = when {
                win?.remainingPct != null && !win.remainingPct.isNaN() -> win.remainingPct
                win?.usedPct != null && !win.usedPct.isNaN() -> 100.0 - win.usedPct
                else -> null
            }

            if (win == null || remRaw == null) {
                return@mapIndexed AccountWindowState(
                    index = index,
                    account = account,
                    state = AccountWindowRowState.MISSING,
                    remainingPct = null,
                    usedPct = null,
                    expectedRemainingPct = null,
                    rawExpectedRemainingPct = null,
                    resetsInMs = null,
                    countdownLabel = null,
                )
            }

            val remainingPct = clamp(remRaw, 0.0, 100.0)
            val usedPct = if (win.usedPct != null && !win.usedPct.isNaN()) {
                clamp(win.usedPct, 0.0, 100.0)
            } else {
                clamp(100.0 - remainingPct, 0.0, 100.0)
            }

            val resetsInMs = resolveResetsInMs(win, nowMs)
            val isFullIdle = remainingPct >= 100.0 && win.status?.lowercase() == "quota available"
            val hasActiveReset = resetsInMs != null && resetsInMs > 0L && !isFullIdle
            val withinWindow = hasActiveReset && resetsInMs!! <= windowMs * 1.05

            val rawExpected = if (withinWindow) clamp((resetsInMs!! / windowMs) * 100.0, 0.0, 100.0) else null
            val expected = rawExpected?.let { clamp(it, 2.0, 98.0) }
            val countdownLabel = if (remainingPct < 100.0 && hasActiveReset) {
                FormatResetTime.formatCompactDuration(resetsInMs!!)
            } else {
                null
            }

            AccountWindowState(
                index = index,
                account = account,
                state = AccountWindowRowState.OK,
                remainingPct = remainingPct,
                usedPct = usedPct,
                expectedRemainingPct = expected,
                rawExpectedRemainingPct = rawExpected,
                resetsInMs = if (hasActiveReset) resetsInMs else null,
                countdownLabel = countdownLabel,
            )
        }

        val activeRows = rows.filter { it.state == AccountWindowRowState.OK && it.remainingPct != null }
        val aggregateRemainingPct = if (activeRows.isNotEmpty()) {
            (activeRows.sumOf { it.remainingPct!! } / activeRows.size).roundToInt()
        } else {
            null
        }

        val paceRows = activeRows.filter { it.rawExpectedRemainingPct != null }
        val hasUsage = activeRows.any { (it.usedPct ?: 0.0) > 0.0 }
        val paceStatus = if (paceRows.isNotEmpty() && hasUsage) {
            val avgRem = paceRows.sumOf { it.remainingPct!! } / paceRows.size
            val avgExp = paceRows.sumOf { it.rawExpectedRemainingPct!! } / paceRows.size
            val diff = avgRem - avgExp
            when {
                abs(diff) <= 5.0 -> PaceStatus.ON_PACE
                diff > 5.0 -> PaceStatus.UNDER_PACE
                else -> PaceStatus.AHEAD_OF_PACE
            }
        } else {
            null
        }

        val replenishCandidates = activeRows
            .filter { (it.usedPct ?: 0.0) > 0.0 && (it.resetsInMs ?: 0L) > 0L }
            .sortedBy { it.resetsInMs!! }
        val replenishment = replenishCandidates.firstOrNull()?.let { soonest ->
            val durationLabel = FormatResetTime.formatCompactDuration(soonest.resetsInMs!!)
            if (durationLabel != null) {
                ReplenishmentInfo(
                    gainPct = max(1, (soonest.usedPct!! / activeRows.size).roundToInt()),
                    durationLabel = durationLabel,
                    resetsInMs = soonest.resetsInMs,
                )
            } else {
                null
            }
        }

        return WindowGroupSummary(
            windowKey = windowKey,
            title = windowKey.title,
            aggregateRemainingPct = aggregateRemainingPct,
            paceStatus = paceStatus,
            replenishment = replenishment,
            rows = rows,
        )
    }

    /** Format a ZonedDateTime as "Dec 1, 2026, 1:33 AM". */
    fun formatLocalDateTime(zdt: ZonedDateTime): String {
        val mon = MONTHS[zdt.monthValue - 1]
        val day = zdt.dayOfMonth
        val year = zdt.year
        val h24 = zdt.hour
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val mins = zdt.minute.toString().padStart(2, '0')
        val ampm = if (h24 >= 12) "PM" else "AM"
        return "$mon $day, $year, $h12:$mins $ampm"
    }

    fun formatExactResetDateTime(
        resetsAt: String?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val target = FormatResetTime.parseIsoInstant(resetsAt) ?: return null
        if (target.toEpochMilli() <= now.toEpochMilli()) return null
        return formatLocalDateTime(target.atZone(zone))
    }

    fun formatUpdatedAgo(tsSeconds: Double?, nowMs: Long = System.currentTimeMillis()): String {
        if (tsSeconds == null || !tsSeconds.isFinite()) return "—"
        val diffSec = max(0L, floor(nowMs / 1000.0 - tsSeconds).toLong())
        if (diffSec < 5L) return "Updated just now"
        if (diffSec < 60L) return "Updated ${diffSec}s ago"
        val dur = FormatResetTime.formatCompactDuration(diffSec * 1000L)
        return if (dur != null) "Updated $dur ago" else "Updated ${diffSec}s ago"
    }

    fun summarizeAccountWindowDetail(
        poolAccounts: List<AgentUsageAccount>,
        accountId: String,
        windowKey: UsageWindowKey,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AccountWindowDetail? {
        val groupSummary = summarizeWindowGroup(poolAccounts, windowKey, now)
        val row = groupSummary.rows.find { it.account.id == accountId } ?: return null

        val providerKey = UsageProviderKey.forHarness(row.account.harness)
        val windowTitle = windowKey.title
        val headerTitle = "${providerKey.windowLabel} · $windowTitle"
        val activeRows = groupSummary.rows.filter { it.state == AccountWindowRowState.OK && it.remainingPct != null }
        val poolSize = poolAccounts.size
        val activePoolSize = activeRows.size

        val isOk = row.state == AccountWindowRowState.OK && row.remainingPct != null && row.usedPct != null
        val resetsInMs = row.resetsInMs
        val hasActiveReset = resetsInMs != null && resetsInMs > 0L
        val isFullIdle = isOk && row.remainingPct!! >= 100.0 && !hasActiveReset

        val win = row.account.windowFor(windowKey)
        val resetDateTimeLabel = if (hasActiveReset) {
            formatExactResetDateTime(win?.resetsAt, now, zone)
                ?: formatLocalDateTime(Instant.ofEpochMilli(now.toEpochMilli() + resetsInMs!!).atZone(zone))
        } else {
            null
        }
        val countdownLabel = if (hasActiveReset) FormatResetTime.formatCompactDuration(resetsInMs!!) else null

        val evenPaceExpectedLeftPct = row.rawExpectedRemainingPct?.roundToInt()

        var paceDeltaPct: Int? = null
        var paceStatus: PaceStatus? = null
        if (isOk && row.rawExpectedRemainingPct != null) {
            val diff = row.remainingPct!! - row.rawExpectedRemainingPct
            paceDeltaPct = diff.roundToInt()
            if (row.usedPct!! > 0.0) {
                paceStatus = when {
                    abs(diff) <= 5.0 -> PaceStatus.ON_PACE
                    diff > 5.0 -> PaceStatus.UNDER_PACE
                    else -> PaceStatus.AHEAD_OF_PACE
                }
            }
        }

        var poolRestorePct: Int? = null
        var poolSharePct: Int? = null
        if (isOk && activePoolSize > 1) {
            if (row.usedPct!! > 0.0) {
                poolRestorePct = max(1, (row.usedPct / activePoolSize).roundToInt())
            }
            val totalRemaining = activeRows.sumOf { it.remainingPct!! }
            if (totalRemaining > 0.0) {
                poolSharePct = ((row.remainingPct!! / totalRemaining) * 100.0).roundToInt()
            }
        }

        var projection: AccountWindowProjection? = null
        if (isOk && row.usedPct!! > 0.0 && hasActiveReset) {
            val windowMs = windowKey.durationSeconds * 1000.0
            val elapsedMs = windowMs - resetsInMs!!
            val elapsedFraction = elapsedMs / windowMs
            if (row.remainingPct!! <= 0.0) {
                projection = AccountWindowProjection.Exhausted()
            } else if (elapsedFraction >= MIN_PROJECTION_ELAPSED_FRACTION && elapsedFraction < 1.0) {
                val projectedUsedAtReset = row.usedPct / elapsedFraction
                if (projectedUsedAtReset <= 100.0) {
                    val projectedRemainingPct = clamp(100.0 - projectedUsedAtReset, 0.0, 100.0).roundToInt()
                    projection = AccountWindowProjection.Headroom(
                        projectedRemainingPct = projectedRemainingPct,
                        label = "On track to finish with ~$projectedRemainingPct% left",
                    )
                } else {
                    val exhaustInMs = (row.remainingPct / row.usedPct) * elapsedMs
                    val dur = FormatResetTime.formatCompactDuration(exhaustInMs)
                    projection = AccountWindowProjection.RunsOut(
                        exhaustInMs = exhaustInMs,
                        label = if (dur != null) {
                            "At current pace, runs out in ~$dur"
                        } else {
                            "At current pace, may run out before reset"
                        },
                    )
                }
            }
        }

        return AccountWindowDetail(
            windowKey = windowKey,
            windowTitle = windowTitle,
            headerTitle = headerTitle,
            state = row.state,
            remainingPct = row.remainingPct,
            usedPct = row.usedPct,
            expectedRemainingPct = row.expectedRemainingPct,
            rawExpectedRemainingPct = row.rawExpectedRemainingPct,
            evenPaceExpectedLeftPct = evenPaceExpectedLeftPct,
            paceDeltaPct = paceDeltaPct,
            paceStatus = paceStatus,
            resetsInMs = resetsInMs,
            resetDateTimeLabel = resetDateTimeLabel,
            countdownLabel = countdownLabel,
            isFullIdle = isFullIdle,
            poolSize = poolSize,
            activePoolSize = activePoolSize,
            poolRestorePct = poolRestorePct,
            poolSharePct = poolSharePct,
            projection = projection,
        )
    }

    fun getAccountDetailSummary(
        accounts: List<AgentUsageAccount>,
        accountId: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AccountDetailSummary? {
        val groups = groupAccountsByProvider(accounts)
        for (group in groups) {
            val idx = group.accounts.indexOfFirst { it.id == accountId }
            if (idx >= 0) {
                val account = group.accounts[idx]
                val fiveHour = summarizeAccountWindowDetail(group.accounts, accountId, UsageWindowKey.FIVE_HOUR, now, zone)
                val weekly = summarizeAccountWindowDetail(group.accounts, accountId, UsageWindowKey.WEEKLY, now, zone)
                if (fiveHour == null || weekly == null) return null
                return AccountDetailSummary(
                    account = account,
                    providerKey = group.key,
                    providerName = group.name,
                    providerSubtitle = group.key.subtitle,
                    poolIndex = idx + 1,
                    poolSize = group.accounts.size,
                    fiveHour = fiveHour,
                    weekly = weekly,
                )
            }
        }
        return null
    }
}
