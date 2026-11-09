// Replay timeline maths, ported from the /cams/ page (no React, no native: unit-tested in replay.test.ts).
// camrec serves each cam's last 24 h as ONE VOD HLS playlist (index.m3u8?a=<first>&b=<last>): chunk gaps are
// simply left out, so media time runs continuously while wall time jumps. index.json's spans map between them:
// [wall_start, media_start, duration] per stretch of continuous recording.

export type Span = [number, number, number];
export type RecIndex = {
  cam: string;
  name?: string;
  codec: 'avc' | 'hevc' | string;
  recording?: boolean;
  now?: number;
  a: string | null;
  b: string | null;
  spans: Span[];
};
export type Sighting = { ts: number; cam: string };
export type Visit = { cam: string; start: number; end: number };

export const REC_CAMS = ['cat', 'cat2', 'cat3hd'] as const;
export type RecCam = (typeof REC_CAMS)[number];
/** catdetect cam ids -> camrec cam ids */
export const DET2REC: Record<string, RecCam> = { cat: 'cat', cat2: 'cat2', cat3: 'cat3hd', cat3hd: 'cat3hd' };
export const SHORT: Record<RecCam, string> = { cat: 'Living', cat2: 'Hall', cat3hd: 'Bowl' };
export const DAY = 86400;
export const VISIT_GAP = 90;
export const ZOOMS = [DAY, 6 * 3600, 3600, 600];
export const SPEEDS = [1, 4, 16];

export const clamp = (x: number, a: number, b: number) => Math.min(Math.max(x, a), b);
export const spanEnd = (s: Span) => s[0] + s[2];

export type W2M = { m: number; gap?: undefined } | { gap: true; next: number | null; prev: number | null; m?: undefined };

/** wall time -> media time: {m} inside footage, else {gap, prev (end of the footage before), next (start after)} */
export function w2m(spans: Span[], T: number): W2M {
  for (let i = 0; i < spans.length; i++) {
    const s = spans[i];
    if (T < s[0]) return { gap: true, next: s[0], prev: i ? spanEnd(spans[i - 1]) : null };
    if (T < spanEnd(s)) return { m: s[1] + (T - s[0]) };
  }
  return { gap: true, next: null, prev: spans.length ? spanEnd(spans[spans.length - 1]) : null };
}

/** media time -> wall time (null before the first span) */
export function m2w(spans: Span[], t: number): number | null {
  let best: Span | null = null;
  for (const s of spans) {
    if (t < s[1] - 0.05) break;
    best = s;
  }
  return best ? best[0] + clamp(t - best[1], 0, best[2]) : null;
}

/** Where a player should start for wall time T: inside footage, else the next footage, else just before the end. */
export function startMedia(spans: Span[], T: number): number {
  const r = w2m(spans, T);
  if (r.m != null) return r.m;
  if (r.next != null) return w2m(spans, r.next).m ?? 0;
  const last = spans[spans.length - 1];
  return last ? Math.max(0, w2m(spans, spanEnd(last) - 3).m ?? 0) : 0;
}

export function latestEnd(idx: Partial<Record<string, RecIndex>>, cams: readonly string[] = REC_CAMS): number {
  return Math.max(
    0,
    ...cams.map((c) => {
      const s = idx[c]?.spans ?? [];
      return s.length ? spanEnd(s[s.length - 1]) : 0;
    }),
  );
}

export function earliestStart(idx: Partial<Record<string, RecIndex>>, cams: readonly string[] = REC_CAMS): number {
  return Math.min(...cams.map((c) => idx[c]?.spans?.[0]?.[0] ?? Infinity));
}

/**
 * Explicit seek: clamp to the last 24 h of what exists; if no cam has footage at T, snap to the nearest recorded
 * moment in the direction of travel (dir < 0: the end of the footage before, else the next start).
 */
export function snapSeek(
  idx: Partial<Record<string, RecIndex>>,
  T: number,
  now: number,
  dir = 0,
  cams: readonly string[] = REC_CAMS,
): number {
  const hi = latestEnd(idx, cams) || now;
  T = clamp(T, Math.max(now - DAY, Math.min(earliestStart(idx, cams), hi)), hi);
  const rs = cams.map((c) => w2m(idx[c]?.spans ?? [], T));
  if (rs.length && rs.every((r) => r.gap)) {
    const nx = rs.map((r) => (r.gap ? r.next : null)).filter((x): x is number => x != null);
    const pv = rs.map((r) => (r.gap ? r.prev : null)).filter((x): x is number => x != null);
    if (dir < 0 && pv.length) T = Math.max(...pv) - 2;
    else if (nx.length) T = Math.min(...nx);
    else if (pv.length) T = Math.max(...pv) - 2;
  }
  return T;
}

/** Visits: runs of sightings of one cam less than VISIT_GAP apart, by start time. */
export function visitsOf(sightings: Sighting[]): Visit[] {
  const open: Record<string, Visit> = {};
  const out: Visit[] = [];
  for (const s of [...sightings].sort((a, b) => a.ts - b.ts)) {
    const v = open[s.cam];
    if (v && s.ts - v.end <= VISIT_GAP) v.end = s.ts;
    else out.push((open[s.cam] = { cam: s.cam, start: s.ts, end: s.ts }));
  }
  return out.sort((a, b) => a.start - b.start);
}

/** catdetect's /detect/sightings -> sorted sightings of the recorded cams */
export function parseSightings(j: unknown): { enabled: boolean | null; sightings: Sighting[] } {
  const o = j as { enabled?: boolean; sightings?: { ts?: number; cam?: string }[] } | null;
  if (!o || !Array.isArray(o.sightings)) return { enabled: null, sightings: [] };
  const sightings = o.sightings
    .filter((s) => typeof s.ts === 'number' && typeof s.cam === 'string' && DET2REC[s.cam])
    .map((s) => ({ ts: s.ts as number, cam: DET2REC[s.cam as string] }))
    .sort((a, b) => a.ts - b.ts);
  return { enabled: typeof o.enabled === 'boolean' ? o.enabled : null, sightings };
}

/** Playlist URL for one index snapshot (avc: HEVC transcoded to H.264 on the laptop, for phones without HEVC). */
export function playlistUrl(base: string, idx: RecIndex, avc: boolean): string {
  return `${base}/rec/${idx.cam}/index.m3u8?a=${encodeURIComponent(idx.a ?? '')}&b=${encodeURIComponent(idx.b ?? '')}${avc ? '&v=avc' : ''}`;
}

/**
 * A player holding an older snapshot should reload when the clock gets near the end of what it knows and newer
 * footage exists (the page's rule: within max(2 s, 2 x rate) of its end, and either 10 s more exists or T is past it).
 */
export function needsReload(snap: RecIndex, latest: RecIndex | undefined, T: number, rate: number): boolean {
  if (!latest || latest.b === snap.b || !latest.spans.length || !snap.spans.length) return false;
  const sEnd = spanEnd(snap.spans[snap.spans.length - 1]);
  const lEnd = spanEnd(latest.spans[latest.spans.length - 1]);
  return T > sEnd - Math.max(2, 2 * rate) && (lEnd - sEnd > 10 || T > sEnd + 1);
}

/** Ribbon ticks: step (s) so labels are >= minPx apart, aligned to local time. */
const STEPS = [10, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600];
export function ticks(t0: number, t1: number, spp: number, tzOffsetS: number, minPx = 64): { step: number; at: number[] } {
  const step = STEPS.find((s) => s / spp >= minPx) ?? 21600;
  const at: number[] = [];
  for (let t = Math.ceil((t0 + tzOffsetS) / step) * step - tzOffsetS; t <= t1; t += step) at.push(t);
  return { step, at };
}

export const fmtSpan = (s: number) => (s >= 3600 ? `${+(s / 3600).toFixed(s % 3600 ? 1 : 0)} h` : `${Math.round(s / 60)} min`);
