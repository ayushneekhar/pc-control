import assert from 'node:assert/strict';
import test from 'node:test';
import { formatResetTime } from './formatResetTime';

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
