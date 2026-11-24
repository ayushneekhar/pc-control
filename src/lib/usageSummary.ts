import type { AgentUsageAccount, AgentUsageWindow } from './api';
import { formatCompactDuration, parseDurationMs } from './formatResetTime';

export const CACHE_AGENT_USAGE = 'agent:usage';

export type UsageWindowKey = 'five_hour' | 'weekly';
export type UsageProviderKey = 'claude' | 'gemini' | 'opencode';
export type PaceStatus = 'Under pace' | 'On pace' | 'Ahead of pace';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;

export const WINDOW_SECONDS: Record<UsageWindowKey, number> = {
  five_hour: 5 * 3600,
  weekly: 7 * 86400,
};

export const WINDOW_TITLES: Record<UsageWindowKey, string> = {
  five_hour: '5-hour',
  weekly: 'Weekly',
};

export const PROVIDER_WINDOW_LABELS: Record<UsageProviderKey, string> = {
  claude: 'Claude',
  gemini: 'Gemini',
  opencode: 'OpenCode',
};

export const PROVIDER_SUBTITLES: Record<UsageProviderKey, string> = {
  claude: 'Anthropic · Claude',
  gemini: 'Google account · Antigravity',
  opencode: 'OpenCode · DeepSeek',
};

export type UsageProviderGroup = {
  key: UsageProviderKey;
  name: string;
  accounts: AgentUsageAccount[];
};

export type AccountWindowState = {
  index: number;
  account: AgentUsageAccount;
  state: 'ok' | 'signed_out' | 'error' | 'missing';
  remainingPct: number | null;
  usedPct: number | null;
  /** Clamped 2..98% position for the vertical pace tick mark, or null if no active future reset within the window. */
  expectedRemainingPct: number | null;
  /** Unclamped 0..100% expected remaining % used for aggregate pace comparison. */
  rawExpectedRemainingPct: number | null;
  resetsInMs: number | null;
  /** Compact countdown ("2h 44m", "6d 11h"), omitted when 100% or no active reset. */
  countdownLabel: string | null;
};

export type ReplenishmentInfo = {
  gainPct: number;
  durationLabel: string;
  resetsInMs: number;
};

export type WindowGroupSummary = {
  windowKey: UsageWindowKey;
  title: string;
  aggregateRemainingPct: number | null;
  paceStatus: PaceStatus | null;
  replenishment: ReplenishmentInfo | null;
  rows: AccountWindowState[];
};

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

const PROVIDER_ORDER: readonly { key: UsageProviderKey; name: string }[] = [
  { key: 'claude', name: 'Claude' },
  { key: 'gemini', name: 'Antigravity' },
  { key: 'opencode', name: 'OpenCode' },
];

export function groupAccountsByProvider(accounts: AgentUsageAccount[]): UsageProviderGroup[] {
  const byKey = new Map<UsageProviderKey, AgentUsageAccount[]>();
  for (const acc of accounts) {
    const key: UsageProviderKey =
      acc.harness === 'claude' ? 'claude' : acc.harness === 'opencode' ? 'opencode' : 'gemini';
    const list = byKey.get(key);
    if (list) list.push(acc);
    else byKey.set(key, [acc]);
  }
  const groups: UsageProviderGroup[] = [];
  for (const { key, name } of PROVIDER_ORDER) {
    const list = byKey.get(key);
    if (list && list.length > 0) {
      groups.push({ key, name, accounts: list });
    }
  }
  return groups;
}

function resolveResetsInMs(win: AgentUsageWindow, nowMs: number): number | null {
  if (win.resets_at) {
    const t = Date.parse(win.resets_at);
    if (!Number.isNaN(t)) {
      const diff = t - nowMs;
      return diff > 0 ? diff : null;
    }
  }
  if (win.refreshes_in) {
    return parseDurationMs(win.refreshes_in);
  }
  return null;
}

export function summarizeWindowGroup(
  accounts: AgentUsageAccount[],
  windowKey: UsageWindowKey,
  now: Date = new Date(),
): WindowGroupSummary {
  const nowMs = now.getTime();
  const windowMs = WINDOW_SECONDS[windowKey] * 1000;

  const rows: AccountWindowState[] = accounts.map((account, i) => {
    const index = i + 1;
    if (!account.signed_in) {
      return {
        index,
        account,
        state: 'signed_out',
        remainingPct: null,
        usedPct: null,
        expectedRemainingPct: null,
        rawExpectedRemainingPct: null,
        resetsInMs: null,
        countdownLabel: null,
      };
    }
    if (account.error) {
      return {
        index,
        account,
        state: 'error',
        remainingPct: null,
        usedPct: null,
        expectedRemainingPct: null,
        rawExpectedRemainingPct: null,
        resetsInMs: null,
        countdownLabel: null,
      };
    }
    const win = account[windowKey];
    const remRaw =
      win?.remaining_pct != null && !Number.isNaN(win.remaining_pct)
        ? win.remaining_pct
        : win?.used_pct != null && !Number.isNaN(win.used_pct)
          ? 100 - win.used_pct
          : null;

    if (!win || remRaw == null) {
      return {
        index,
        account,
        state: 'missing',
        remainingPct: null,
        usedPct: null,
        expectedRemainingPct: null,
        rawExpectedRemainingPct: null,
        resetsInMs: null,
        countdownLabel: null,
      };
    }

    const remainingPct = clamp(remRaw, 0, 100);
    const usedPct =
      win.used_pct != null && !Number.isNaN(win.used_pct)
        ? clamp(win.used_pct, 0, 100)
        : clamp(100 - remainingPct, 0, 100);

    const resetsInMs = resolveResetsInMs(win, nowMs);
    const isFullIdle = remainingPct >= 100 && win.status?.toLowerCase() === 'quota available';
    const hasActiveReset = resetsInMs != null && resetsInMs > 0 && !isFullIdle;
    const withinWindow = hasActiveReset && resetsInMs <= windowMs * 1.05;

    const rawExpectedRemainingPct = withinWindow ? clamp((resetsInMs / windowMs) * 100, 0, 100) : null;
    const expectedRemainingPct = rawExpectedRemainingPct != null ? clamp(rawExpectedRemainingPct, 2, 98) : null;
    const countdownLabel =
      remainingPct < 100 && hasActiveReset ? formatCompactDuration(resetsInMs) : null;

    return {
      index,
      account,
      state: 'ok',
      remainingPct,
      usedPct,
      expectedRemainingPct,
      rawExpectedRemainingPct,
      resetsInMs: hasActiveReset ? resetsInMs : null,
      countdownLabel,
    };
  });

  const activeRows = rows.filter((r) => r.state === 'ok' && r.remainingPct != null);
  const aggregateRemainingPct =
    activeRows.length > 0
      ? Math.round(activeRows.reduce((sum, r) => sum + r.remainingPct!, 0) / activeRows.length)
      : null;

  // Pace status: compare average remaining % vs average expected remaining % when any active account has usage.
  const paceRows = activeRows.filter((r) => r.rawExpectedRemainingPct != null);
  const hasUsage = activeRows.some((r) => (r.usedPct ?? 0) > 0);
  let paceStatus: PaceStatus | null = null;
  if (paceRows.length > 0 && hasUsage) {
    const avgRem = paceRows.reduce((sum, r) => sum + r.remainingPct!, 0) / paceRows.length;
    const avgExp = paceRows.reduce((sum, r) => sum + r.rawExpectedRemainingPct!, 0) / paceRows.length;
    const diff = avgRem - avgExp;
    if (Math.abs(diff) <= 5) paceStatus = 'On pace';
    else if (diff > 5) paceStatus = 'Under pace';
    else paceStatus = 'Ahead of pace';
  }

  // Next replenishment: soonest resetting account with usedPct > 0.
  let replenishment: ReplenishmentInfo | null = null;
  const replenishCandidates = activeRows
    .filter((r) => (r.usedPct ?? 0) > 0 && r.resetsInMs != null && r.resetsInMs > 0)
    .sort((a, b) => a.resetsInMs! - b.resetsInMs!);
  if (replenishCandidates.length > 0) {
    const soonest = replenishCandidates[0];
    const durationLabel = formatCompactDuration(soonest.resetsInMs!);
    if (durationLabel) {
      replenishment = {
        gainPct: Math.max(1, Math.round(soonest.usedPct! / activeRows.length)),
        durationLabel,
        resetsInMs: soonest.resetsInMs!,
      };
    }
  }

  return {
    windowKey,
    title: WINDOW_TITLES[windowKey],
    aggregateRemainingPct,
    paceStatus,
    replenishment,
    rows,
  };
}

export type AccountWindowProjection =
  | { kind: 'headroom'; projectedRemainingPct: number; label: string }
  | { kind: 'runs_out'; exhaustInMs: number; label: string }
  | { kind: 'exhausted'; label: string };

export type AccountWindowDetail = {
  windowKey: UsageWindowKey;
  windowTitle: string;
  headerTitle: string;
  state: AccountWindowState['state'];
  remainingPct: number | null;
  usedPct: number | null;
  expectedRemainingPct: number | null;
  rawExpectedRemainingPct: number | null;
  /** Rounded expected remaining % at even pace, or null when no active window timer. */
  evenPaceExpectedLeftPct: number | null;
  /** Signed difference (remainingPct - rawExpectedRemainingPct), rounded. Positive = buffer, negative = ahead. */
  paceDeltaPct: number | null;
  paceStatus: PaceStatus | null;
  resetsInMs: number | null;
  /** Formatted exact local reset date & time (e.g. "Dec 1, 2026, 1:33 AM"), or null when no active reset. */
  resetDateTimeLabel: string | null;
  /** Compact countdown (e.g. "5d 21h"), or null when no active reset. */
  countdownLabel: string | null;
  /** True when at 100% remaining with no active cooldown timer. */
  isFullIdle: boolean;
  /** Total accounts in this provider group. */
  poolSize: number;
  /** Active (`ok`) accounts in this provider group. */
  activePoolSize: number;
  /** Percentage points restored to the multi-account pool when this account resets, or null if single-account or 0% used. */
  poolRestorePct: number | null;
  /** Percentage of the current pool's remaining quota contributed by this account, or null if single-account. */
  poolSharePct: number | null;
  /** Projected headroom or exhaustion at current burn rate (skipped at 0% used or early in the window). */
  projection: AccountWindowProjection | null;
};

export type AccountDetailSummary = {
  account: AgentUsageAccount;
  providerKey: UsageProviderKey;
  providerName: string;
  providerSubtitle: string;
  poolIndex: number;
  poolSize: number;
  fiveHour: AccountWindowDetail;
  weekly: AccountWindowDetail;
};

/**
 * Format a Date in the device's local timezone as "Dec 1, 2026, 1:33 AM".
 */
export function formatLocalDateTime(d: Date): string {
  const mon = MONTHS[d.getMonth()];
  const day = d.getDate();
  const year = d.getFullYear();
  const h24 = d.getHours();
  const h12 = h24 % 12 || 12;
  const mins = String(d.getMinutes()).padStart(2, '0');
  const ampm = h24 >= 12 ? 'PM' : 'AM';
  return `${mon} ${day}, ${year}, ${h12}:${mins} ${ampm}`;
}

/**
 * Format an ISO 8601 reset timestamp in local time (e.g. "Dec 1, 2026, 1:33 AM").
 * Returns null when missing, invalid, or not in the future relative to `now`.
 */
export function formatExactResetDateTime(
  resetsAt: string | null | undefined,
  now: Date = new Date(),
): string | null {
  if (!resetsAt) return null;
  const target = new Date(resetsAt);
  if (Number.isNaN(target.getTime())) return null;
  if (target.getTime() <= now.getTime()) return null;
  return formatLocalDateTime(target);
}

/**
 * Format a Unix epoch timestamp (seconds) as a crisp relative "Updated X ago" string.
 */
export function formatUpdatedAgo(tsSeconds: number | null | undefined, nowMs: number = Date.now()): string {
  if (tsSeconds == null || !Number.isFinite(tsSeconds)) return '—';
  const diffSec = Math.max(0, Math.floor(nowMs / 1000 - tsSeconds));
  if (diffSec < 5) return 'Updated just now';
  if (diffSec < 60) return `Updated ${diffSec}s ago`;
  const dur = formatCompactDuration(diffSec * 1000);
  return dur ? `Updated ${dur} ago` : `Updated ${diffSec}s ago`;
}

function providerKeyForAccount(account: AgentUsageAccount): UsageProviderKey {
  return account.harness === 'claude' ? 'claude' : account.harness === 'opencode' ? 'opencode' : 'gemini';
}

const MIN_PROJECTION_ELAPSED_FRACTION = 0.05;

export function summarizeAccountWindowDetail(
  poolAccounts: AgentUsageAccount[],
  accountId: string,
  windowKey: UsageWindowKey,
  now: Date = new Date(),
): AccountWindowDetail | null {
  const groupSummary = summarizeWindowGroup(poolAccounts, windowKey, now);
  const row = groupSummary.rows.find((r) => r.account.id === accountId);
  if (!row) return null;

  const providerKey = providerKeyForAccount(row.account);
  const windowTitle = WINDOW_TITLES[windowKey];
  const headerTitle = `${PROVIDER_WINDOW_LABELS[providerKey]} · ${windowTitle}`;
  const activeRows = groupSummary.rows.filter((r) => r.state === 'ok' && r.remainingPct != null);
  const poolSize = poolAccounts.length;
  const activePoolSize = activeRows.length;

  const isOk = row.state === 'ok' && row.remainingPct != null && row.usedPct != null;
  const resetsInMs = row.resetsInMs;
  const hasActiveReset = resetsInMs != null && resetsInMs > 0;
  const isFullIdle = isOk && row.remainingPct! >= 100 && !hasActiveReset;

  const win = row.account[windowKey];
  let resetDateTimeLabel: string | null = null;
  if (hasActiveReset) {
    resetDateTimeLabel =
      formatExactResetDateTime(win?.resets_at, now) ??
      formatLocalDateTime(new Date(now.getTime() + resetsInMs));
  }
  const countdownLabel = hasActiveReset ? formatCompactDuration(resetsInMs) : null;

  const evenPaceExpectedLeftPct =
    row.rawExpectedRemainingPct != null ? Math.round(row.rawExpectedRemainingPct) : null;

  let paceDeltaPct: number | null = null;
  let paceStatus: PaceStatus | null = null;
  if (isOk && row.rawExpectedRemainingPct != null) {
    const diff = row.remainingPct! - row.rawExpectedRemainingPct;
    paceDeltaPct = Math.round(diff);
    if (row.usedPct! > 0) {
      if (Math.abs(diff) <= 5) paceStatus = 'On pace';
      else if (diff > 5) paceStatus = 'Under pace';
      else paceStatus = 'Ahead of pace';
    }
  }

  let poolRestorePct: number | null = null;
  let poolSharePct: number | null = null;
  if (isOk && activePoolSize > 1) {
    if (row.usedPct! > 0) {
      poolRestorePct = Math.max(1, Math.round(row.usedPct! / activePoolSize));
    }
    const totalRemaining = activeRows.reduce((sum, r) => sum + r.remainingPct!, 0);
    if (totalRemaining > 0) {
      poolSharePct = Math.round((row.remainingPct! / totalRemaining) * 100);
    }
  }

  let projection: AccountWindowProjection | null = null;
  if (isOk && row.usedPct! > 0 && hasActiveReset) {
    const windowMs = WINDOW_SECONDS[windowKey] * 1000;
    const elapsedMs = windowMs - resetsInMs;
    const elapsedFraction = elapsedMs / windowMs;
    if (row.remainingPct! <= 0) {
      projection = { kind: 'exhausted', label: 'Quota exhausted until reset' };
    } else if (elapsedFraction >= MIN_PROJECTION_ELAPSED_FRACTION && elapsedFraction < 1) {
      const projectedUsedAtReset = row.usedPct! / elapsedFraction;
      if (projectedUsedAtReset <= 100) {
        const projectedRemainingPct = Math.round(clamp(100 - projectedUsedAtReset, 0, 100));
        projection = {
          kind: 'headroom',
          projectedRemainingPct,
          label: `On track to finish with ~${projectedRemainingPct}% left`,
        };
      } else {
        const exhaustInMs = (row.remainingPct! / row.usedPct!) * elapsedMs;
        const dur = formatCompactDuration(exhaustInMs);
        projection = {
          kind: 'runs_out',
          exhaustInMs,
          label: dur
            ? `At current pace, runs out in ~${dur}`
            : 'At current pace, may run out before reset',
        };
      }
    }
  }

  return {
    windowKey,
    windowTitle,
    headerTitle,
    state: row.state,
    remainingPct: row.remainingPct,
    usedPct: row.usedPct,
    expectedRemainingPct: row.expectedRemainingPct,
    rawExpectedRemainingPct: row.rawExpectedRemainingPct,
    evenPaceExpectedLeftPct,
    paceDeltaPct,
    paceStatus,
    resetsInMs,
    resetDateTimeLabel,
    countdownLabel,
    isFullIdle,
    poolSize,
    activePoolSize,
    poolRestorePct,
    poolSharePct,
    projection,
  };
}

export function getAccountDetailSummary(
  accounts: AgentUsageAccount[],
  accountId: string,
  now: Date = new Date(),
): AccountDetailSummary | null {
  const groups = groupAccountsByProvider(accounts);
  for (const group of groups) {
    const idx = group.accounts.findIndex((a) => a.id === accountId);
    if (idx >= 0) {
      const account = group.accounts[idx];
      const fiveHour = summarizeAccountWindowDetail(group.accounts, accountId, 'five_hour', now);
      const weekly = summarizeAccountWindowDetail(group.accounts, accountId, 'weekly', now);
      if (!fiveHour || !weekly) return null;
      return {
        account,
        providerKey: group.key,
        providerName: group.name,
        providerSubtitle: PROVIDER_SUBTITLES[group.key],
        poolIndex: idx + 1,
        poolSize: group.accounts.length,
        fiveHour,
        weekly,
      };
    }
  }
  return null;
}
