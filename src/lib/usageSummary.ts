import type { AgentUsageAccount, AgentUsageWindow } from './api';
import { formatCompactDuration, parseDurationMs } from './formatResetTime';

export type UsageWindowKey = 'five_hour' | 'weekly';
export type UsageProviderKey = 'claude' | 'gemini' | 'opencode';
export type PaceStatus = 'Under pace' | 'On pace' | 'Ahead of pace';

export const WINDOW_SECONDS: Record<UsageWindowKey, number> = {
  five_hour: 5 * 3600,
  weekly: 7 * 86400,
};

export const WINDOW_TITLES: Record<UsageWindowKey, string> = {
  five_hour: '5-hour',
  weekly: 'Weekly',
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
