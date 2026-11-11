// Replay's playback engine (no React): the /cams/ page's Replay logic. One wall clock T drives three native
// players (RecPlayer, one continuous HLS playlist each). While playing, T follows the big tile's player (media
// time -> wall time through index.json's spans); the others seek when they drift > 1.5 s. A cam without footage
// at T shows a message; if none has footage, T jumps to the next recorded moment. ReplayTab renders the snapshot
// this emits (emit(): at most every 250 ms tick, and on user actions).
import {
  clamp,
  DAY,
  latestEnd,
  m2w,
  needsReload,
  parseSightings,
  playlistUrl,
  recCamOf,
  REC_CAMS,
  RecCam,
  RecIndex,
  sightingSeek,
  Sighting,
  snapSeek,
  spanEnd,
  SPEEDS,
  startMedia,
  Visit,
  visitsOf,
  w2m,
  ZOOMS,
} from '../../lib/cams/replay';
import { NAMES } from '../../lib/cams/config';
import type { RecPlayerHandle, RecProgress } from './RecPlayer';

export type Tile = { msg: string | null; spin: boolean; label: string };
export type Source = { url: string; start: number } | null;
export type ReplayView = {
  T: number;
  now: number;
  tiles: Record<RecCam, Tile>;
  sources: Record<RecCam, Source>;
  playing: boolean;
  /** play prop per player: playing, not scrubbing, footage at T */
  play: Record<RecCam, boolean>;
  rate: number;
  big: RecCam;
  span: number;
  idx: Partial<Record<RecCam, RecIndex>>;
  sightings: Sighting[];
  visits: Visit[];
  detOn: boolean | null;
  error: string;
  decoder: string | null;
};

type Player = {
  snap: RecIndex | null;
  avc: boolean;
  loading: boolean;
  gen: number;
  prog: RecProgress | null;
  inGap: boolean;
  seekAt: number;
};
const newPlayer = (): Player => ({ snap: null, avc: false, loading: false, gen: 0, prog: null, inGap: false, seekAt: 0 });
const nowS = () => Date.now() / 1000;
const per = <V>(f: (c: RecCam) => V) => Object.fromEntries(REC_CAMS.map((c) => [c, f(c)])) as Record<RecCam, V>;

export class ReplayEngine {
  private idx: Partial<Record<RecCam, RecIndex>> = {};
  private ps: Record<RecCam, Player> = per(newPlayer);
  private handles: Partial<Record<RecCam, RecPlayerHandle | null>> = {};
  private sources: Record<RecCam, Source> = per(() => null);
  private tiles: Record<RecCam, Tile> = per((c) => ({ msg: 'Loading…', spin: false, label: NAMES[c] }));
  private T = 0;
  private dragging = false;
  private playing = true;
  private rate = 1;
  private big: RecCam = 'cat';
  private span = 3600;
  private sightings: Sighting[] = [];
  private visits: Visit[] = [];
  private detOn: boolean | null = null;
  private error = '';
  /** a cat alert's camera + epoch ms, applied once the recorder's index is here */
  private target: { cam: string; ts: number } | null = null;
  private open = false;
  private run = 0; // start/stop generation: a start() still awaiting its first fetch after stop() bails out
  private timers: ReturnType<typeof setInterval>[] = [];
  // gestures
  private g = { T0: 0, moved: false, pinch0: 0, span0: 0, preview: 0 };
  private ribbonW = 0;
  private overviewW = 0;

  constructor(private o: { base: () => string; hevcOk: boolean; onChange: (v: ReplayView) => void }) {}

  // ---------------------------------------------------------------- lifecycle
  async start() {
    this.open = true;
    const run = ++this.run;
    await Promise.all([this.refreshIdx(), this.refreshSightings()]);
    if (!this.open || run !== this.run) return;
    const applied = this.applyTarget();
    if (!applied && (!this.T || this.T < nowS() - DAY)) {
      const first = Math.min(...REC_CAMS.map((c) => this.idx[c]?.spans?.[0]?.[0] ?? Infinity));
      this.T = Math.max((latestEnd(this.idx) || nowS()) - 60, Number.isFinite(first) ? first : 0);
    }
    if (!applied) this.T = snapSeek(this.idx, this.T, nowS(), -1);
    for (const c of REC_CAMS) this.load(c, this.T);
    this.timers = [
      setInterval(() => this.tick(), 250),
      setInterval(() => this.refreshIdx(), 10000),
      setInterval(() => this.refreshSightings(), 30000),
    ];
    this.tick();
  }

  stop() {
    this.open = false;
    this.run++;
    this.timers.forEach(clearInterval);
    this.timers = [];
    for (const c of REC_CAMS) {
      const p = this.ps[c];
      p.gen++;
      p.snap = null;
      p.prog = null;
    }
    this.sources = per(() => null); // unmounts the players (releases ExoPlayer)
    this.dragging = false;
    this.emit();
  }

  // ---------------------------------------------------------------- data
  private async fetchIndex(cam: RecCam) {
    const r = await fetch(`${this.o.base()}/rec/${cam}/index.json`, { headers: { 'Cache-Control': 'no-store' } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const j = (await r.json()) as RecIndex;
    this.idx = { ...this.idx, [cam]: j };
    return j;
  }
  private async refreshIdx() {
    const res = await Promise.allSettled(REC_CAMS.map((c) => this.fetchIndex(c)));
    this.error = res.every((r) => r.status === 'rejected') ? "Can't reach the recorder (camrec service)." : '';
  }
  private async refreshSightings() {
    try {
      const r = await fetch(`${this.o.base()}/detect/sightings?since=${Math.floor(nowS() - DAY)}`, {
        headers: { 'Cache-Control': 'no-store' },
      });
      if (!r.ok) throw new Error(String(r.status));
      const p = parseSightings(await r.json());
      this.detOn = p.enabled;
      this.sightings = p.sightings;
      this.visits = visitsOf(p.sightings);
    } catch {
      this.detOn = null;
    }
  }

  // ---------------------------------------------------------------- players
  setHandle(cam: RecCam, h: RecPlayerHandle | null) {
    this.handles[cam] = h;
  }
  onProgress(cam: RecCam, p: RecProgress) {
    this.ps[cam].prog = p;
  }
  onError(cam: RecCam) {
    // e.g. chunks pruned under it: a fresh snapshot
    const gen = this.ps[cam].gen;
    setTimeout(() => this.open && gen === this.ps[cam].gen && this.load(cam, this.T), 2000);
  }

  private async load(cam: RecCam, at: number) {
    const p = this.ps[cam];
    const gen = ++p.gen;
    p.loading = true;
    try {
      const j = await this.fetchIndex(cam);
      if (gen !== p.gen || !this.open) return;
      p.snap = j.a && j.spans.length ? j : null;
      p.prog = null;
      if (!p.snap) {
        this.sources = { ...this.sources, [cam]: null };
        return;
      }
      p.avc = j.codec === 'hevc' && !this.o.hevcOk;
      p.seekAt = Date.now();
      this.sources = { ...this.sources, [cam]: { url: playlistUrl(this.o.base(), j, p.avc), start: startMedia(j.spans, at) } };
      this.emit();
    } catch {
      if (gen === p.gen) setTimeout(() => this.open && gen === p.gen && this.load(cam, this.T), 5000);
    } finally {
      if (gen === p.gen) p.loading = false;
    }
  }

  private seekPlayer(cam: RecCam, m: number, exact: boolean) {
    const p = this.ps[cam];
    p.seekAt = Date.now();
    if (p.prog) p.prog = { ...p.prog, position: m, seeking: true };
    this.handles[cam]?.seek(m, exact).catch(() => undefined);
  }

  /** explicit seek of one player to wall time `at` */
  private jump(cam: RecCam, at: number, exact = true) {
    const p = this.ps[cam];
    if (!p.snap) return;
    const latest = this.idx[cam];
    if (latest && latest.b !== p.snap.b && at > spanEnd(p.snap.spans[p.snap.spans.length - 1]) - 1) {
      this.load(cam, at);
      return;
    }
    const r = w2m(p.snap.spans, at);
    if (r.m != null) this.seekPlayer(cam, r.m, exact);
  }

  seekTo(at: number, dir = 0) {
    this.T = snapSeek(this.idx, at, nowS(), dir);
    for (const c of REC_CAMS) this.jump(c, this.T);
    this.tick();
  }

  /** Set by the index once loaded; returns true when the target became the clock. */
  private applyTarget(): boolean {
    const t = this.target;
    if (!t) return false;
    const r = sightingSeek(this.idx, t.cam, t.ts, nowS());
    if (!r) {
      this.target = null;
      return false;
    }
    if (!REC_CAMS.some((c) => (this.idx[c]?.spans?.length ?? 0) > 0)) return false; // wait for the index
    this.target = null;
    this.big = r.cam;
    this.playing = true;
    this.T = r.T;
    return true;
  }

  /**
   * A cat notification: select the camera that saw it and seek to ~5 s before the sighting. If the recorder index
   * isn't here yet the target waits for start()/refreshIdx(); an unknown cam or bad timestamp is ignored.
   */
  seekSighting(cam: unknown, tsMs: unknown): boolean {
    const rc = recCamOf(cam);
    const ts = typeof tsMs === 'number' ? tsMs : typeof tsMs === 'string' && tsMs.trim() ? Number(tsMs) : NaN;
    if (!rc || !Number.isFinite(ts)) return false;
    this.target = { cam: rc, ts };
    if (!this.applyTarget()) return true;
    for (const c of REC_CAMS) this.jump(c, this.T);
    this.tick();
    return true;
  }

  // ---------------------------------------------------------------- the clock
  private settled(p: Player) {
    return Date.now() - p.seekAt > 700 && !!p.prog && !p.prog.seeking;
  }

  tick() {
    if (!this.open || !this.T) return this.emit();
    const ps = this.ps;
    let src: RecCam | null = null;
    if (this.playing && !this.dragging) {
      src =
        [this.big, ...REC_CAMS].find((c) => {
          const p = ps[c];
          return p.snap && !p.inGap && !p.loading && this.settled(p) && p.prog!.playing && p.prog!.state === 'ready';
        }) ?? null;
      const w = src ? m2w(ps[src].snap!.spans, ps[src].prog!.position) : null;
      if (w != null) this.T = w;
      else {
        src = null;
        const rs = REC_CAMS.map((c) => w2m(this.idx[c]?.spans ?? [], this.T));
        if (rs.every((r) => r.gap)) {
          const nx = rs.map((r) => (r.gap ? r.next : null)).filter((x): x is number => x != null);
          if (nx.length) {
            this.T = Math.min(...nx);
            REC_CAMS.forEach((c) => this.jump(c, this.T));
          }
        }
      }
    }
    const tiles = {} as Record<RecCam, Tile>;
    for (const c of REC_CAMS) {
      const p = ps[c];
      const latest = this.idx[c];
      const label = NAMES[c] + (p.avc ? ' · SD' : '');
      if (!p.snap) {
        if (latest?.a && !p.loading) this.load(c, this.T);
        tiles[c] = { msg: p.loading || latest?.a ? 'Loading…' : 'No recordings yet', spin: false, label };
        continue;
      }
      if (!p.loading && needsReload(p.snap, latest, this.T, this.rate)) this.load(c, this.T);
      const r = w2m(p.snap.spans, this.T);
      p.inGap = r.m == null;
      if (r.m == null) {
        const sEnd = spanEnd(p.snap.spans[p.snap.spans.length - 1]);
        const upToDate = r.next == null && this.T >= sEnd - 0.5;
        tiles[c] = { msg: upToDate ? 'Up to date: waiting for new footage' : 'No recording at this time', spin: false, label };
        continue;
      }
      const drift = Math.max(1.5, this.rate * 0.75);
      if (c !== src && !this.dragging && this.settled(p) && Math.abs(p.prog!.position - r.m) > drift) this.seekPlayer(c, r.m, true);
      const want = this.playing && !this.dragging;
      tiles[c] = { msg: null, spin: want && (!p.prog || p.prog.seeking || p.prog.state === 'buffering'), label };
    }
    this.tiles = tiles;
    this.emit();
  }

  private emit() {
    this.o.onChange({
      T: this.T,
      now: nowS(),
      tiles: this.tiles,
      sources: this.sources,
      playing: this.playing,
      play: per((c) => this.playing && !this.dragging && !this.tiles[c].msg),
      rate: this.rate,
      big: this.big,
      span: this.span,
      idx: this.idx,
      sightings: this.sightings,
      visits: this.visits,
      detOn: this.detOn,
      error: this.error,
      decoder: this.ps[this.big].prog?.decoder || null,
    });
  }

  // ---------------------------------------------------------------- controls
  togglePlay() {
    this.playing = !this.playing;
    this.tick();
  }
  cycleRate() {
    this.rate = SPEEDS[(SPEEDS.indexOf(this.rate) + 1) % SPEEDS.length];
    this.emit();
  }
  cycleZoom() {
    const i = ZOOMS.findIndex((z) => z < this.span - 1);
    this.span = ZOOMS[i < 0 ? 0 : i];
    this.emit();
  }
  skip(d: number) {
    this.seekTo(this.T + d, Math.sign(d));
  }
  /** the live edge: the newest footage, 20 s back so it plays */
  goLive() {
    this.playing = true;
    this.seekTo((latestEnd(this.idx) || nowS()) - 20, -1);
  }
  setBig(c: RecCam) {
    this.big = c;
    this.emit();
  }
  jumpTo(cam: string, ts: number) {
    if ((REC_CAMS as readonly string[]).includes(cam)) this.big = cam as RecCam;
    this.playing = true;
    this.seekTo(ts - 4, -1);
  }
  /** previous = start of the visit we're in, or the one before if we're within its first seconds */
  prevVisit() {
    const v = this.visits.filter((x) => x.start < this.T - 6).at(-1);
    if (v) this.jumpTo(v.cam, v.start);
  }
  nextVisit() {
    const v = this.visits.find((x) => x.start > this.T + 5);
    if (v) this.jumpTo(v.cam, v.start);
  }
  lastSighting() {
    const s = this.sightings.at(-1);
    if (s) this.jumpTo(s.cam, s.ts);
  }
  snapshot(label: (T: number) => string): Promise<string> {
    const h = this.handles[this.big];
    if (!h) return Promise.reject(new Error('nothing playing'));
    return h.snapshot(label(this.T));
  }

  // ---------------------------------------------------------------- timeline gestures
  private lim(x: number) {
    return clamp(x, nowS() - DAY, latestEnd(this.idx) || nowS());
  }
  private preview() {
    if (Date.now() - this.g.preview < 300) return;
    this.g.preview = Date.now();
    for (const c of REC_CAMS) this.jump(c, this.T, false); // keyframe seeks: fast pictures while dragging
  }
  private endScrub(dir: number) {
    this.dragging = false;
    this.seekTo(this.T, dir);
  }
  setRibbonWidth(w: number) {
    this.ribbonW = w;
  }
  setOverviewWidth(w: number) {
    this.overviewW = w;
  }
  ribbonGrant() {
    this.g = { ...this.g, T0: this.T, moved: false, pinch0: 0 };
  }
  /** dx from the grant; pinch = distance between two fingers (0 = one finger) */
  ribbonMove(dx: number, pinch: number) {
    const g = this.g;
    if (pinch) {
      if (!g.pinch0) {
        g.pinch0 = pinch;
        g.span0 = this.span;
        g.moved = true;
      }
      this.span = clamp((g.span0 * g.pinch0) / pinch, 120, DAY);
      return this.emit();
    }
    if (g.pinch0) return; // fingers lifted one at a time after a pinch
    if (!g.moved && Math.abs(dx) > 6) {
      g.moved = true;
      this.dragging = true;
    }
    if (!g.moved || !this.ribbonW) return;
    this.T = this.lim(g.T0 - (dx * this.span) / this.ribbonW);
    this.preview();
    this.emit();
  }
  ribbonRelease(x: number, dx: number) {
    const g = this.g;
    if (!g.moved && this.ribbonW) {
      // tap: jump to that time
      const at = this.T + (x - this.ribbonW / 2) * (this.span / this.ribbonW);
      this.T = this.lim(at);
      return this.endScrub(Math.sign(at - g.T0));
    }
    if (this.dragging) this.endScrub(-Math.sign(dx));
    else this.emit();
  }
  private ovT(x: number) {
    return nowS() - DAY + clamp((x - 8) / Math.max(1, this.overviewW - 16), 0, 1) * DAY;
  }
  overviewMove(x: number) {
    this.dragging = true;
    this.T = this.lim(this.ovT(x));
    this.preview();
    this.emit();
  }
  overviewEnd() {
    if (this.dragging) this.endScrub(0);
  }
}
