import assert from 'node:assert/strict';
import test from 'node:test';
import {
  formatCompactDuration,
  formatCompactResetDuration,
  formatResetTime,
  parseDurationMs,
} from './formatResetTime';

// Helper: construct a Date in local timezone so tests pass regardless of host TZ.
function localDate(y: number, m: number, d: number, h: number, min: number, s = 0): Date {
  return new Date(y, m - 1, d, h, min, s);
}

test('formatResetTime: null/invalid returns null', () => {
  assert.equal(formatResetTime(null), null);
  assert.equal(formatResetTime(undefined), null);
  assert.equal(formatResetTime('not-a-date'), null);
});

test('formatResetTime: past or zero returns Resets now', () => {
  const now = localDate(2026, 11, 23, 10, 0);
  assert.equal(formatResetTime(localDate(2026, 11, 23, 9, 55).toISOString(), now), 'Resets now');
  assert.equal(formatResetTime(now.toISOString(), now), 'Resets now');
});

test('formatResetTime: under 1 hour formats as minutes', () => {
  const now = localDate(2026, 11, 23, 10, 0);
  const in42 = localDate(2026, 11, 23, 10, 42);
  assert.equal(formatResetTime(in42.toISOString(), now), 'Resets in 42 min');
  const in30s = localDate(2026, 11, 23, 10, 0, 30);
  assert.equal(formatResetTime(in30s.toISOString(), now), 'Resets in 1 min');
});

test('formatResetTime: later today formats as today + 12h clock', () => {
  const now = localDate(2026, 11, 23, 10, 0);
  const target = localDate(2026, 11, 23, 12, 20);
  assert.equal(formatResetTime(target.toISOString(), now), 'Resets today, 12:20 pm');
});

test('formatResetTime: tomorrow formats as tomorrow + 12h clock', () => {
  const now = localDate(2026, 11, 23, 22, 0);
  const target = localDate(2026, 11, 24, 9, 5);
  assert.equal(formatResetTime(target.toISOString(), now), 'Resets tomorrow, 9:05 am');
});

test('formatResetTime: future days include weekday, ordinal day, short month', () => {
  const now = localDate(2026, 11, 23, 10, 0);
  // 2026-11-29 is Sunday
  assert.equal(
    formatResetTime(localDate(2026, 11, 29, 6, 30).toISOString(), now),
    'Resets Sun 29th Nov, 6:30 am',
  );
  // 2026-12-01 is Tuesday (1st), 2026-12-02 is Wednesday (2nd), 2026-12-03 is Thursday (3rd)
  assert.equal(
    formatResetTime(localDate(2026, 12, 1, 0, 15).toISOString(), now),
    'Resets Tue 1st Dec, 12:15 am',
  );
  assert.equal(
    formatResetTime(localDate(2026, 12, 2, 14, 0).toISOString(), now),
    'Resets Wed 2nd Dec, 2:00 pm',
  );
  assert.equal(
    formatResetTime(localDate(2026, 12, 3, 23, 59).toISOString(), now),
    'Resets Thu 3rd Dec, 11:59 pm',
  );
  // 2026-12-11..13 use "th"
  assert.equal(
    formatResetTime(localDate(2026, 12, 11, 8, 0).toISOString(), now),
    'Resets Fri 11th Dec, 8:00 am',
  );
});

test('formatCompactDuration: formats minutes, hours, and days cleanly', () => {
  assert.equal(formatCompactDuration(0), null);
  assert.equal(formatCompactDuration(-5000), null);
  assert.equal(formatCompactDuration(Number.NaN), null);
  assert.equal(formatCompactDuration(30_000), '1m');
  assert.equal(formatCompactDuration(42 * 60_000), '42m');
  assert.equal(formatCompactDuration(2 * 3_600_000), '2h');
  assert.equal(formatCompactDuration((2 * 60 + 44) * 60_000), '2h 44m');
  assert.equal(formatCompactDuration(24 * 3_600_000), '1d');
  // 155h 9m == 6d 11h 9m -> "6d 11h"
  assert.equal(formatCompactDuration((155 * 60 + 9) * 60_000), '6d 11h');
  assert.equal(formatCompactDuration((4 * 24 + 10) * 3_600_000), '4d 10h');
});

test('formatCompactResetDuration: formats future ISO timestamps and rejects past/invalid', () => {
  const now = new Date('2026-11-24T10:00:00Z');
  assert.equal(formatCompactResetDuration(null, now), null);
  assert.equal(formatCompactResetDuration('invalid', now), null);
  assert.equal(formatCompactResetDuration('2026-11-24T09:59:00Z', now), null);
  assert.equal(formatCompactResetDuration('2026-11-24T12:38:00Z', now), '2h 38m');
  assert.equal(formatCompactResetDuration('2026-11-30T21:09:00Z', now), '6d 11h');
});

test('parseDurationMs: parses hour/day/minute strings including >=24h hours', () => {
  assert.equal(parseDurationMs(null), null);
  assert.equal(parseDurationMs(''), null);
  assert.equal(parseDurationMs('155h 9m'), (155 * 3600 + 9 * 60) * 1000);
  assert.equal(formatCompactDuration(parseDurationMs('155h 9m')!), '6d 11h');
  assert.equal(parseDurationMs('4d 10h'), (4 * 86400 + 10 * 3600) * 1000);
  assert.equal(parseDurationMs('2h 38m'), (2 * 3600 + 38 * 60) * 1000);
});
