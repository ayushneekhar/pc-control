// yarn test:js (scripts/test-js.mjs transpiles this and replay.ts, then runs node --test)
/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  m2w,
  needsReload,
  parseSightingTarget,
  parseSightings,
  RecIndex,
  recCamOf,
  sightingSeek,
  snapSeek,
  snapSeekCam,
  Span,
  startMedia,
  ticks,
  visitsOf,
  w2m,
} from './replay';

// two stretches: wall 1000..1060 (media 0..60) and wall 2000..2030 (media 60..90)
const spans: Span[] = [
  [1000, 0, 60],
  [2000, 60, 30],
];
const idx = (cam: string, sp: Span[], b = 'b1'): RecIndex => ({ cam, codec: 'avc', a: 'a', b, spans: sp });

test('w2m inside, in a gap, before and after', () => {
  assert.deepEqual(w2m(spans, 1010), { m: 10 });
  assert.deepEqual(w2m(spans, 2005), { m: 65 });
  assert.deepEqual(w2m(spans, 1500), { gap: true, next: 2000, prev: 1060 });
  assert.deepEqual(w2m(spans, 500), { gap: true, next: 1000, prev: null });
  assert.deepEqual(w2m(spans, 3000), { gap: true, next: null, prev: 2030 });
});

test('m2w is the inverse inside footage and clamps', () => {
  for (const T of [1000, 1033.5, 2000, 2029]) assert.equal(m2w(spans, w2m(spans, T).m!), T);
  assert.equal(m2w(spans, 59.9), 1059.9);
  assert.equal(m2w(spans, 59.99), 2000); // within 0.05 s of the next span: snaps to it (as the page does)
  assert.equal(m2w(spans, 500), 2030); // past the end: end of the last span
});

test('one continuous playlist: media time never jumps across a gap', () => {
  // the last frame before the gap and the first after are adjacent in media time
  assert.ok(Math.abs(w2m(spans, 1059.9).m! + 0.1 - w2m(spans, 2000).m!) < 1e-9);
});

test('startMedia goes to the next footage, or near the end', () => {
  assert.equal(startMedia(spans, 1500), 60);
  assert.equal(startMedia(spans, 9999), 87);
  assert.equal(startMedia([], 5), 0);
});

test('snapSeek snaps out of a gap shared by every cam, in the direction of travel', () => {
  const I = { cat: idx('cat', spans), cat2: idx('cat2', [[1100, 0, 50]]), cat3hd: idx('cat3hd', []) };
  const now = 2100;
  assert.equal(snapSeek(I, 1500, now, 1), 2000); // forward: next start
  assert.equal(snapSeek(I, 1500, now, -1), 1148); // backward: end of the latest footage before, minus 2 s
  assert.equal(snapSeek(I, 1120, now, 1), 1120); // cat2 has footage: stays
  assert.equal(snapSeek(I, 5000, now, 0), 2028); // clamped to the newest footage's (exclusive) end, then 2 s back
});

test('snapSeekCam stays on one cam and falls back to its nearest footage', () => {
  const now = 2100;
  assert.equal(snapSeekCam(spans, 1010, now, -1), 1010); // inside footage
  assert.equal(snapSeekCam(spans, 1500, now, -1), 1058); // gap: end of the footage before, minus 2 s
  assert.equal(snapSeekCam(spans, 1500, now, 1), 2000); // forward: next start
  assert.equal(snapSeekCam(spans, 5000, now, -1), 2028); // not recorded yet: near the latest
  assert.equal(snapSeekCam(spans, 500, now, -1), 1000); // before the first footage: its start
  assert.equal(snapSeekCam([], 5000, now), 2100); // no recordings: just clamp (to now here)
});

test('parseSightingTarget accepts notification payload strings and rejects junk', () => {
  assert.deepEqual(parseSightingTarget('cat3', '2005000'), { cam: 'cat3hd', ts: 2005000 });
  assert.deepEqual(parseSightingTarget('cat', 1010000), { cam: 'cat', ts: 1010000 });
  assert.equal(parseSightingTarget('nope', 1010000), null);
  assert.equal(parseSightingTarget('cat', ''), null);
  assert.equal(parseSightingTarget('cat', 0), null);
  assert.equal(parseSightingTarget(undefined, 1010000), null);
});

test('sightingSeek maps the detector cam and seeks 5 s before the sighting', () => {
  const I = { cat: idx('cat', spans), cat3hd: idx('cat3hd', [[2000, 0, 30]]) };
  assert.deepEqual(sightingSeek(I, 'cat', 1010_000, 2100), { cam: 'cat', T: 1005 });
  assert.deepEqual(sightingSeek(I, 'cat3', 2005_000, 2100), { cam: 'cat3hd', T: 2000 });
  assert.deepEqual(sightingSeek(I, 'cat', 1505_000, 2100), { cam: 'cat', T: 1058 }); // in a gap
  assert.equal(recCamOf('cat3'), 'cat3hd');
  assert.equal(recCamOf('nope'), null);
  assert.equal(sightingSeek(I, 'nope', 1010_000, 2100), null);
  assert.equal(sightingSeek(I, 'cat', 'soon' as unknown as number, 2100), null);
});

test('sightingSeek falls back when the moment is outside the 24 h window', () => {
  const now = 1_700_000_000;
  const I = { cat: idx('cat', [[now - 2000, 0, 100]]) }; // footage inside the window
  // 10 h older than the window: nearest available = the first footage
  assert.deepEqual(sightingSeek(I, 'cat', (now - 10 * 3600) * 1000, now), { cam: 'cat', T: now - 2000 });
});

test('visits merge sightings of one cam less than 90 s apart', () => {
  const v = visitsOf([
    { ts: 100, cam: 'cat' },
    { ts: 150, cam: 'cat' },
    { ts: 120, cam: 'cat3hd' },
    { ts: 400, cam: 'cat' },
  ]);
  assert.deepEqual(v, [
    { cam: 'cat', start: 100, end: 150 },
    { cam: 'cat3hd', start: 120, end: 120 },
    { cam: 'cat', start: 400, end: 400 },
  ]);
});

test('parseSightings maps detector cams to recorder cams and drops junk', () => {
  const r = parseSightings({
    enabled: true,
    sightings: [
      { ts: 5, cam: 'cat3' },
      { ts: 1, cam: 'cat' },
      { ts: 'x', cam: 'cat' },
      { ts: 2, cam: 'nope' },
    ],
  });
  assert.deepEqual(r, {
    enabled: true,
    sightings: [
      { ts: 1, cam: 'cat' },
      { ts: 5, cam: 'cat3hd' },
    ],
  });
  assert.deepEqual(parseSightings(null), { enabled: null, sightings: [] });
});

test('needsReload only near the end of an older snapshot', () => {
  const old = idx('cat', spans, 'b1');
  const newer = idx('cat', [...spans, [2030, 90, 60]], 'b2');
  assert.equal(needsReload(old, newer, 2010, 1), false);
  assert.equal(needsReload(old, newer, 2029, 1), true);
  assert.equal(needsReload(old, idx('cat', spans, 'b1'), 2029, 1), false);
});

test('ticks align to local time', () => {
  const { step, at } = ticks(0, 7200, 3600 / 300, 0); // 1 h over 300 px
  assert.equal(step, 900);
  assert.equal(at[0], 0);
  assert.equal(at.length, 9);
});
