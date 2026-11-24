import assert from 'node:assert/strict';
import test from 'node:test';
import type { AgentUsageAccount } from './api';
import {
  formatExactResetDateTime,
  formatLocalDateTime,
  formatUpdatedAgo,
  getAccountDetailSummary,
  groupAccountsByProvider,
  summarizeAccountWindowDetail,
  summarizeWindowGroup,
} from './usageSummary';

function makeAcc(overrides: Partial<AgentUsageAccount> & { id: string }): AgentUsageAccount {
  return {
    harness: 'gemini',
    label: overrides.id,
    email: null,
    signed_in: true,
    status: null,
    error: null,
    five_hour: null,
    weekly: null,
    ...overrides,
  };
}

test('groupAccountsByProvider orders Claude, Antigravity, OpenCode and omits empty groups', () => {
  const accounts: AgentUsageAccount[] = [
    makeAcc({ id: 'agy-1', harness: 'gemini', label: 'Antigravity 1' }),
    makeAcc({ id: 'opencode', harness: 'opencode', label: 'OpenCode / DeepSeek' }),
    makeAcc({ id: 'agy-2', harness: 'gemini', label: 'Antigravity 2' }),
    makeAcc({ id: 'claude', harness: 'claude', label: 'Claude' }),
  ];

  const groups = groupAccountsByProvider(accounts);
  assert.deepEqual(
    groups.map((g) => [g.key, g.name, g.accounts.map((a) => a.id)]),
    [
      ['claude', 'Claude', ['claude']],
      ['gemini', 'Antigravity', ['agy-1', 'agy-2']],
      ['opencode', 'OpenCode', ['opencode']],
    ],
  );
});

test('summarizeWindowGroup computes aggregate, Under pace, replenishment, and ticks for 5-hour pool', () => {
  const now = new Date('2026-11-24T12:00:00Z');
  const inMins = (m: number) => new Date(now.getTime() + m * 60_000).toISOString();

  const accounts: AgentUsageAccount[] = [
    makeAcc({
      id: 'agy-1',
      label: 'Antigravity 1',
      five_hour: { remaining_pct: 89, used_pct: 11, resets_at: inMins(2 * 60 + 44) },
    }),
    makeAcc({
      id: 'agy-2',
      label: 'Antigravity 2',
      five_hour: { remaining_pct: 96, used_pct: 4, resets_at: inMins(4 * 60 + 4) },
    }),
    makeAcc({
      id: 'agy-3',
      label: 'Antigravity 3',
      five_hour: { remaining_pct: 90, used_pct: 10, resets_at: inMins(2 * 60 + 38) },
    }),
    makeAcc({
      id: 'agy-4',
      label: 'Antigravity 4',
      five_hour: { remaining_pct: 100, used_pct: 0, resets_at: inMins(4 * 60 + 35) },
    }),
  ];

  const summary = summarizeWindowGroup(accounts, 'five_hour', now);
  assert.equal(summary.title, '5-hour');
  assert.equal(summary.aggregateRemainingPct, 94);
  assert.equal(summary.paceStatus, 'Under pace');
  // Soonest with usage > 0 is agy-3 (2h 38m, used 10% -> round(10/4) = 3%)
  assert.deepEqual(summary.replenishment, {
    gainPct: 3,
    durationLabel: '2h 38m',
    resetsInMs: (2 * 60 + 38) * 60_000,
  });
  assert.equal(summary.rows[0].countdownLabel, '2h 44m');
  assert.equal(summary.rows[3].countdownLabel, null); // omitted at 100%
  assert.ok(summary.rows[0].expectedRemainingPct != null && summary.rows[0].expectedRemainingPct > 50);
});

test('summarizeWindowGroup detects On pace and Ahead of pace for weekly windows', () => {
  const now = new Date('2026-11-24T12:00:00Z');
  const inHours = (h: number) => new Date(now.getTime() + h * 3_600_000).toISOString();

  // Single account (Claude): 68% left, resets in 4d 10h (106h / 168h = 63.1% expected -> diff +4.9% -> On pace)
  const onPace = summarizeWindowGroup(
    [
      makeAcc({
        id: 'claude',
        harness: 'claude',
        label: 'Claude',
        weekly: { remaining_pct: 68, used_pct: 32, resets_at: inHours(4 * 24 + 10) },
      }),
    ],
    'weekly',
    now,
  );
  assert.equal(onPace.aggregateRemainingPct, 68);
  assert.equal(onPace.paceStatus, 'On pace');
  assert.equal(onPace.replenishment?.gainPct, 32);
  assert.equal(onPace.replenishment?.durationLabel, '4d 10h');
  assert.equal(onPace.rows[0].countdownLabel, '4d 10h');

  // Ahead of pace: 80% left with 6d 11h remaining (155h / 168h = 92.3% expected -> diff -12.3%)
  const ahead = summarizeWindowGroup(
    [
      makeAcc({
        id: 'agy-1',
        label: 'Antigravity 1',
        weekly: { remaining_pct: 80, used_pct: 20, resets_at: inHours(155) },
      }),
    ],
    'weekly',
    now,
  );
  assert.equal(ahead.paceStatus, 'Ahead of pace');
  assert.equal(ahead.rows[0].countdownLabel, '6d 11h');
});

test('summarizeWindowGroup handles signed-out, error, missing window, and 100% idle cleanly', () => {
  const now = new Date('2026-11-24T12:00:00Z');
  const accounts: AgentUsageAccount[] = [
    makeAcc({ id: 'a1', signed_in: false, status: 'not signed in' }),
    makeAcc({ id: 'a2', signed_in: true, error: 'HTTP 429' }),
    makeAcc({ id: 'a3', signed_in: true, five_hour: null }),
    makeAcc({
      id: 'a4',
      signed_in: true,
      five_hour: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
    }),
  ];

  const summary = summarizeWindowGroup(accounts, 'five_hour', now);
  assert.equal(summary.aggregateRemainingPct, 100);
  assert.equal(summary.paceStatus, null);
  assert.equal(summary.replenishment, null);
  assert.deepEqual(
    summary.rows.map((r) => r.state),
    ['signed_out', 'error', 'missing', 'ok'],
  );
  assert.equal(summary.rows[3].expectedRemainingPct, null);
  assert.equal(summary.rows[3].countdownLabel, null);
});

test('formatLocalDateTime and formatExactResetDateTime format local dates cleanly', () => {
  assert.equal(formatLocalDateTime(new Date(2026, 11, 1, 1, 33, 0)), 'Dec 1, 2026, 1:33 AM');
  assert.equal(formatLocalDateTime(new Date(2026, 0, 15, 0, 5, 0)), 'Jan 15, 2026, 12:05 AM');
  assert.equal(formatLocalDateTime(new Date(2026, 5, 9, 12, 0, 0)), 'Jun 9, 2026, 12:00 PM');
  assert.equal(formatLocalDateTime(new Date(2026, 5, 9, 23, 45, 0)), 'Jun 9, 2026, 11:45 PM');

  const now = new Date(2026, 10, 25, 4, 30, 0);
  const future = new Date(2026, 11, 1, 1, 33, 0);
  const past = new Date(2026, 10, 24, 4, 30, 0);

  assert.equal(formatExactResetDateTime(future.toISOString(), now), 'Dec 1, 2026, 1:33 AM');
  assert.equal(formatExactResetDateTime(past.toISOString(), now), null);
  assert.equal(formatExactResetDateTime(null, now), null);
  assert.equal(formatExactResetDateTime('not-a-date', now), null);
});

test('formatUpdatedAgo formats relative timestamps concisely', () => {
  const nowMs = 1_764_000_000_000;
  const nowSec = nowMs / 1000;
  assert.equal(formatUpdatedAgo(nowSec - 2, nowMs), 'Updated just now');
  assert.equal(formatUpdatedAgo(nowSec - 12, nowMs), 'Updated 12s ago');
  assert.equal(formatUpdatedAgo(nowSec - 180, nowMs), 'Updated 3m ago');
  assert.equal(formatUpdatedAgo(nowSec - 3900, nowMs), 'Updated 1h 5m ago');
  assert.equal(formatUpdatedAgo(null, nowMs), '—');
});

test('getAccountDetailSummary computes multi-account pool detail, pace delta, restoration, and projection', () => {
  const now = new Date(2026, 10, 25, 4, 33, 0);
  const inHours = (h: number) => new Date(now.getTime() + h * 3_600_000).toISOString();

  // 141h left out of 168h = 83.93% expected left -> rounds to 84%
  // 87% left (13% used) -> +3% buffer (On pace, since |+3.07| <= 5)
  // Pool restoration across 4 active accounts: round(13 / 4) = 3%
  const accounts: AgentUsageAccount[] = [
    makeAcc({
      id: 'antigravity-1',
      label: 'Antigravity 1',
      five_hour: { remaining_pct: 90, used_pct: 10, resets_at: inHours(3) },
      weekly: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
    }),
    makeAcc({
      id: 'antigravity-2',
      label: 'Antigravity 2',
      five_hour: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
      weekly: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
    }),
    makeAcc({
      id: 'antigravity-3',
      label: 'Antigravity 3',
      five_hour: { remaining_pct: 50, used_pct: 50, resets_at: inHours(4) },
      weekly: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
    }),
    makeAcc({
      id: 'antigravity-4',
      label: 'Antigravity 4',
      email: 'user@example.com',
      five_hour: { remaining_pct: 80, used_pct: 20, resets_at: inHours(2) },
      weekly: { remaining_pct: 87, used_pct: 13, resets_at: inHours(141) },
    }),
  ];

  const detail = getAccountDetailSummary(accounts, 'antigravity-4', now);
  assert.ok(detail);
  assert.equal(detail.providerKey, 'gemini');
  assert.equal(detail.providerName, 'Antigravity');
  assert.equal(detail.providerSubtitle, 'Google account · Antigravity');
  assert.equal(detail.poolIndex, 4);
  assert.equal(detail.poolSize, 4);

  // Weekly window assertions
  const w = detail.weekly;
  assert.equal(w.headerTitle, 'Gemini · Weekly');
  assert.equal(w.remainingPct, 87);
  assert.equal(w.usedPct, 13);
  assert.equal(w.evenPaceExpectedLeftPct, 84);
  assert.equal(w.paceDeltaPct, 3);
  assert.equal(w.paceStatus, 'On pace');
  assert.equal(w.countdownLabel, '5d 21h');
  assert.equal(w.resetDateTimeLabel, 'Dec 1, 2026, 1:33 AM');
  assert.equal(w.isFullIdle, false);
  assert.equal(w.poolRestorePct, 3);
  // Share: 87 / (100 + 100 + 100 + 87) = 87 / 387 = 22.48% -> 22%
  assert.equal(w.poolSharePct, 22);
  // Elapsed = 27h / 168h = 16.07%, projected used = 13 / 0.1607 = 80.9% -> ~19% left
  assert.equal(w.projection?.kind, 'headroom');
  assert.equal(w.projection?.label, 'On track to finish with ~19% left');

  // 5-hour window assertions: 80% left, 2h remaining out of 5h -> 40% expected -> +40% buffer -> Under pace
  const f = detail.fiveHour;
  assert.equal(f.headerTitle, 'Gemini · 5-hour');
  assert.equal(f.evenPaceExpectedLeftPct, 40);
  assert.equal(f.paceDeltaPct, 40);
  assert.equal(f.paceStatus, 'Under pace');
  assert.equal(f.poolRestorePct, 5); // round(20 / 4) = 5%
  assert.equal(f.projection?.kind, 'headroom');
  assert.equal(f.projection?.label, 'On track to finish with ~67% left');

  // Account 3 on 5-hour: 50% used in 1h (20% elapsed) -> projected 250% used -> runs out in ~1h
  const d3 = getAccountDetailSummary(accounts, 'antigravity-3', now);
  assert.ok(d3);
  assert.equal(d3.fiveHour.paceStatus, 'Ahead of pace');
  assert.equal(d3.fiveHour.paceDeltaPct, -30);
  assert.equal(d3.fiveHour.projection?.kind, 'runs_out');
  assert.equal(d3.fiveHour.projection?.label, 'At current pace, runs out in ~1h');
});

test('summarizeAccountWindowDetail handles single-account, 100% idle, signed out, missing resets_at, and refreshes_in fallback', () => {
  const now = new Date(2026, 10, 25, 10, 0, 0);

  // Single Claude account with refreshes_in fallback (no resets_at)
  const claude = makeAcc({
    id: 'claude',
    harness: 'claude',
    label: 'Claude',
    weekly: { remaining_pct: 60, used_pct: 40, refreshes_in: '4d 12h' },
  });
  const claudeDetail = getAccountDetailSummary([claude], 'claude', now);
  assert.ok(claudeDetail);
  assert.equal(claudeDetail.providerSubtitle, 'Anthropic · Claude');
  assert.equal(claudeDetail.poolSize, 1);
  assert.equal(claudeDetail.weekly.headerTitle, 'Claude · Weekly');
  assert.equal(claudeDetail.weekly.poolRestorePct, null);
  assert.equal(claudeDetail.weekly.poolSharePct, null);
  assert.equal(claudeDetail.weekly.countdownLabel, '4d 12h');
  assert.equal(claudeDetail.weekly.resetDateTimeLabel, 'Nov 29, 2026, 10:00 PM');

  // 100% idle account
  const idle = makeAcc({
    id: 'idle-1',
    five_hour: { remaining_pct: 100, used_pct: 0, status: 'Quota available' },
  });
  const idleDetail = summarizeAccountWindowDetail([idle], 'idle-1', 'five_hour', now);
  assert.ok(idleDetail);
  assert.equal(idleDetail.isFullIdle, true);
  assert.equal(idleDetail.resetDateTimeLabel, null);
  assert.equal(idleDetail.countdownLabel, null);
  assert.equal(idleDetail.evenPaceExpectedLeftPct, null);
  assert.equal(idleDetail.paceStatus, null);
  assert.equal(idleDetail.projection, null);

  // Signed-out account
  const signedOut = makeAcc({ id: 'out-1', signed_in: false });
  const outDetail = summarizeAccountWindowDetail([signedOut], 'out-1', 'weekly', now);
  assert.ok(outDetail);
  assert.equal(outDetail.state, 'signed_out');
  assert.equal(outDetail.remainingPct, null);
  assert.equal(outDetail.resetDateTimeLabel, null);

  // Missing resets_at and refreshes_in
  const noReset = makeAcc({
    id: 'no-reset',
    weekly: { remaining_pct: 75, used_pct: 25 },
  });
  const noResetDetail = summarizeAccountWindowDetail([noReset], 'no-reset', 'weekly', now);
  assert.ok(noResetDetail);
  assert.equal(noResetDetail.state, 'ok');
  assert.equal(noResetDetail.resetDateTimeLabel, null);
  assert.equal(noResetDetail.evenPaceExpectedLeftPct, null);
  assert.equal(noResetDetail.projection, null);

  // Exhausted (0% left)
  const exhausted = makeAcc({
    id: 'exhausted',
    five_hour: {
      remaining_pct: 0,
      used_pct: 100,
      resets_at: new Date(now.getTime() + 2 * 3_600_000).toISOString(),
    },
  });
  const exDetail = summarizeAccountWindowDetail([exhausted], 'exhausted', 'five_hour', now);
  assert.ok(exDetail);
  assert.equal(exDetail.projection?.kind, 'exhausted');
  assert.equal(exDetail.projection?.label, 'Quota exhausted until reset');

  // Unknown account id
  assert.equal(getAccountDetailSummary([claude], 'does-not-exist', now), null);
});
