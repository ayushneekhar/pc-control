import assert from 'node:assert/strict';
import test from 'node:test';
import type { AgentUsageAccount } from './api';
import { groupAccountsByProvider, summarizeWindowGroup } from './usageSummary';

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
