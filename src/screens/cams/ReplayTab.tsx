// Replay: the last 24 h of cat, cat2 and cat3hd from camrec (/rec), ported from the /cams/ page's Replay tab.
// The logic is ReplayEngine (replayEngine.ts); this renders what it emits. Each cam is ONE continuous VOD HLS
// playlist (gaps left out) in a native Media3 player (RecPlayer). Timeline: a 24 h overview strip (tap / drag)
// and a zoomable ribbon (drag to scrub, pinch or the zoom button, tap to jump) with the playhead in the middle;
// amber = cat sightings (logged only while Cat alerts are on).
import { useEffect, useState } from 'react';
import { ActivityIndicator, GestureResponderEvent, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { PcNative } from '../../../modules/pc-native';
import { Icon } from '../../components/ui';
import { serverUrl } from '../../lib/api';
import { NAMES } from '../../lib/cams/config';
import { clamp, DAY, fmtSpan, REC_CAMS, RecCam, RecIndex, SHORT, Sighting, spanEnd, ticks, Visit } from '../../lib/cams/replay';
import { radius, space, useTheme } from '../../theme';
import { RecPlayer } from './RecPlayer';
import { ReplayEngine, ReplayView } from './replayEngine';

const LANE = 'rgba(138,180,255,';
const CAT = '#FBBF24';
const tzOff = () => -new Date().getTimezoneOffset() * 60;
const fHM = (t: number) => new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const fHMS = (t: number) =>
  new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
const fWD = (t: number) => new Date(t * 1000).toLocaleDateString([], { weekday: 'short' });
function dayName(t: number) {
  const d = new Date(t * 1000);
  const n = new Date();
  if (d.toDateString() === n.toDateString()) return 'Today';
  n.setDate(n.getDate() - 1);
  return d.toDateString() === n.toDateString() ? 'Yesterday' : fWD(t);
}
function ago(ts: number, now: number) {
  const m = Math.floor((now - ts) / 60);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  return h < 24 ? `${h} h ago` : `${Math.floor(h / 24)} d ago`;
}

type Toast = (m: string, ms?: number, action?: { label: string; run: () => void }) => void;

const pinchOf = (e: GestureResponderEvent) => {
  const ts = e.nativeEvent.touches;
  return ts.length >= 2 ? Math.abs(ts[0].pageX - ts[1].pageX) || 1 : 0;
};

export function ReplayTab({ visible, hevcOk, toast }: { visible: boolean; hevcOk: boolean; toast: Toast }) {
  const t = useTheme();
  const [v, setV] = useState<ReplayView | null>(null);
  const [eng] = useState(() => new ReplayEngine({ base: serverUrl, hevcOk, onChange: setV }));
  const [width, setWidth] = useState(0);
  const [ribbonW, setRibbonW] = useState(0);
  const [ovW, setOvW] = useState(0);
  const [x0, setX0] = useState(0);
  const [snapBusy, setSnapBusy] = useState(false);

  useEffect(() => {
    if (!visible) return;
    eng.start();
    return () => eng.stop();
  }, [visible, eng]);

  const snapshot = async () => {
    if (snapBusy) return;
    setSnapBusy(true);
    try {
      const uri = await eng.snapshot((T) => `${NAMES[v?.big ?? 'cat']} ${fHMS(T)}`);
      toast('Saved to Pictures/PC Control', 4500, { label: 'Share', run: () => PcNative?.camsShare(uri).catch(() => undefined) });
    } catch (e) {
      toast(`Snapshot failed: ${(e as Error)?.message ?? e}`, 4000);
    }
    setSnapBusy(false);
  };

  // layout of the three tiles (absolute, so a player is never re-parented when the big one changes)
  const big = v?.big ?? 'cat';
  const W = width;
  const bigH = (W * 9) / 16;
  const smallW = (W - space(2)) / 2;
  const smallH = (smallW * 9) / 16;
  const others = REC_CAMS.filter((c) => c !== big);
  const box = (c: RecCam) =>
    c === big
      ? { left: 0, top: 0, width: W, height: bigH }
      : { left: others.indexOf(c) * (smallW + space(2)), top: bigH + space(2), width: smallW, height: smallH };

  const T = v?.T ?? 0;
  const now = v?.now ?? 0;
  const lastS = v?.sightings.at(-1);
  const visits = v?.visits ?? [];
  const playing = v?.playing ?? true;
  const rate = v?.rate ?? 1;

  return (
    <View style={{ gap: space(3) }}>
      {v?.error ? <Text style={[s.err, { color: t.bad, backgroundColor: t.badSoft }]}>{v.error}</Text> : null}
      <View onLayout={(e) => setWidth(e.nativeEvent.layout.width)} style={{ height: W ? bigH + space(2) + smallH : 220 }}>
        {W && v
          ? REC_CAMS.map((c) => {
              const tile = v.tiles[c];
              const isBig = c === big;
              return (
                <Pressable
                  key={c}
                  onPress={() => (isBig ? eng.togglePlay() : eng.setBig(c))}
                  style={[s.tile, box(c), { borderRadius: isBig ? radius.lg : radius.md }]}
                >
                  {v.sources[c] ? (
                    <RecPlayer
                      ref={(h) => eng.setHandle(c, h)}
                      source={v.sources[c]}
                      playing={v.play[c]}
                      rate={rate}
                      style={StyleSheet.absoluteFill}
                      onProgress={(e) => eng.onProgress(c, e.nativeEvent)}
                      onPlayerError={() => eng.onError(c)}
                    />
                  ) : null}
                  {tile.msg ? (
                    <View style={s.tmsg} pointerEvents="none">
                      <Text style={{ color: 'rgba(255,255,255,0.7)', fontSize: isBig ? 14 : 12, textAlign: 'center' }}>{tile.msg}</Text>
                    </View>
                  ) : null}
                  <Text style={[s.tname, !isBig && { fontSize: 12, top: 6, left: 9 }]} numberOfLines={1} pointerEvents="none">
                    {tile.label}
                  </Text>
                  {tile.spin ? <ActivityIndicator style={s.spin} size="small" color="#fff" /> : null}
                  {isBig && !playing && !tile.msg ? (
                    <View style={s.paused} pointerEvents="none">
                      <Icon name="play" size={30} color="#fff" />
                    </View>
                  ) : null}
                  {isBig ? (
                    <Pressable onPress={snapshot} accessibilityLabel="Save this frame" hitSlop={4} style={s.snapBtn}>
                      {snapBusy ? <ActivityIndicator size="small" color="#fff" /> : <Icon name="camera-outline" size={22} color="#fff" />}
                    </Pressable>
                  ) : null}
                </Pressable>
              );
            })
          : null}
      </View>

      {/* clock */}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ color: t.text, fontFamily: t.mono, fontSize: 26, fontWeight: '700', letterSpacing: -0.5 }}>
            {T ? fHMS(T) : '--:--:--'}
          </Text>
          <Text style={{ color: t.textDim, fontSize: 12 }} numberOfLines={1}>
            {T
              ? `${dayName(T)} · ${now - T < 90 ? 'moments ago' : ago(T, now)}${rate > 1 ? ` · ${rate}× speed` : ''}${v?.decoder ? ` · ${v.decoder}` : ''}`
              : 'Loading recordings…'}
          </Text>
        </View>
        <SmallBtn label={fmtSpan(v?.span ?? 3600)} onPress={() => eng.cycleZoom()} a11y="Timeline zoom" />
        <SmallBtn label="Now" icon="skip-next" onPress={() => eng.goLive()} a11y="Jump to the latest footage" />
      </View>

      {/* timeline */}
      <View style={[s.tl, { backgroundColor: t.card, borderColor: t.border }]}>
        <View
          style={{ height: 40, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border }}
          onLayout={(e) => {
            eng.setOverviewWidth(e.nativeEvent.layout.width);
            setOvW(e.nativeEvent.layout.width);
          }}
          onStartShouldSetResponder={() => true}
          onMoveShouldSetResponder={() => true}
          onResponderTerminationRequest={() => false}
          onResponderGrant={(e) => eng.overviewMove(e.nativeEvent.locationX)}
          onResponderMove={(e) => eng.overviewMove(e.nativeEvent.locationX)}
          onResponderRelease={() => eng.overviewEnd()}
          onResponderTerminate={() => eng.overviewEnd()}
        >
          {v ? <Overview idx={v.idx} visits={visits} T={T} span={v.span} width={ovW} now={now} /> : null}
        </View>
        <View
          style={{ height: 100 }}
          onLayout={(e) => {
            eng.setRibbonWidth(e.nativeEvent.layout.width);
            setRibbonW(e.nativeEvent.layout.width);
          }}
          onStartShouldSetResponder={() => true}
          onMoveShouldSetResponder={() => true}
          onResponderTerminationRequest={() => false}
          onResponderGrant={(e) => {
            setX0(e.nativeEvent.pageX);
            eng.ribbonGrant();
          }}
          onResponderMove={(e) => eng.ribbonMove(e.nativeEvent.pageX - x0, pinchOf(e))}
          onResponderRelease={(e) => eng.ribbonRelease(e.nativeEvent.locationX, e.nativeEvent.pageX - x0)}
          onResponderTerminate={(e) => eng.ribbonRelease(e.nativeEvent.locationX, e.nativeEvent.pageX - x0)}
        >
          {v ? <Ribbon idx={v.idx} sightings={v.sightings} T={T} span={v.span} width={ribbonW} big={big} now={now} /> : null}
        </View>
      </View>

      {/* transport */}
      <View style={s.row}>
        <RoundBtn label="−1m" onPress={() => eng.skip(-60)} />
        <RoundBtn label="−10s" onPress={() => eng.skip(-10)} />
        <Pressable
          onPress={() => eng.togglePlay()}
          accessibilityLabel={playing ? 'Pause' : 'Play'}
          style={[s.play, { backgroundColor: t.accent }]}
        >
          <Icon name={playing ? 'pause' : 'play'} size={28} color="#0B1530" />
        </Pressable>
        <RoundBtn label="+10s" onPress={() => eng.skip(10)} />
        <RoundBtn label="+1m" onPress={() => eng.skip(60)} />
        <RoundBtn label={`${rate}×`} onPress={() => eng.cycleRate()} on={rate > 1} />
      </View>

      {/* sightings */}
      <View style={s.row}>
        <SmallBtn
          icon="chevron-left"
          onPress={() => eng.prevVisit()}
          a11y="Previous cat visit"
          disabled={!visits.some((x) => x.start < T - 6)}
        />
        <Pressable
          disabled={!lastS}
          onPress={() => eng.lastSighting()}
          style={[
            s.seen,
            { backgroundColor: lastS ? 'rgba(251,191,36,0.1)' : t.card, borderColor: lastS ? 'rgba(251,191,36,0.35)' : t.border },
          ]}
        >
          <Icon name="cat" size={18} color={lastS ? CAT : t.textDim} />
          <Text style={{ color: lastS ? (t.dark ? '#FDE68A' : '#92400E') : t.textDim, fontWeight: '600', flex: 1 }} numberOfLines={1}>
            {lastS
              ? `Last seen: ${NAMES[lastS.cam]}, ${fHM(lastS.ts)}`
              : v?.detOn === false
                ? 'Cat alerts are off: no sightings logged'
                : 'No cat sightings in the last 24 h'}
          </Text>
        </Pressable>
        <SmallBtn
          icon="chevron-right"
          onPress={() => eng.nextVisit()}
          a11y="Next cat visit"
          disabled={!visits.some((x) => x.start > T + 5)}
        />
      </View>
      {visits.length ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space(2) }}>
          {[...visits]
            .reverse()
            .slice(0, 40)
            .map((x) => {
              const cur = T >= x.start - 5 && T <= x.end + 10;
              return (
                <Pressable
                  key={`${x.cam}${x.start}`}
                  onPress={() => eng.jumpTo(x.cam, x.start)}
                  style={[s.visit, { borderColor: cur ? CAT : t.border, backgroundColor: cur ? 'rgba(251,191,36,0.12)' : t.card }]}
                >
                  <Text style={{ color: t.text, fontWeight: '700', fontSize: 13 }}>{fHM(x.start)}</Text>
                  <Text style={{ color: t.textDim, fontSize: 12 }}>
                    {SHORT[x.cam as RecCam]}
                    {x.end - x.start >= 60 ? ` · ${Math.round((x.end - x.start) / 60)} min` : ''}
                  </Text>
                </Pressable>
              );
            })}
        </ScrollView>
      ) : null}
    </View>
  );
}

function SmallBtn({
  label,
  icon,
  onPress,
  a11y,
  disabled,
}: {
  label?: string;
  icon?: string;
  onPress: () => void;
  a11y: string;
  disabled?: boolean;
}) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityLabel={a11y}
      style={[s.small, { backgroundColor: t.card, borderColor: t.border, opacity: disabled ? 0.4 : 1 }]}
    >
      {icon ? <Icon name={icon} size={18} color={t.text} /> : null}
      {label ? <Text style={{ color: t.text, fontWeight: '600', fontSize: 13 }}>{label}</Text> : null}
    </Pressable>
  );
}

function RoundBtn({ label, onPress, on }: { label: string; onPress: () => void; on?: boolean }) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        s.rb,
        { backgroundColor: on ? t.accentSoft : t.card, borderColor: on ? t.accent : t.border, transform: [{ scale: pressed ? 0.96 : 1 }] },
      ]}
    >
      <Text style={{ color: on ? t.accent : t.text, fontWeight: '600', fontSize: 14 }}>{label}</Text>
    </Pressable>
  );
}

/** 24 h overview: lanes, cat visits, the ribbon's window and the playhead. */
function Overview({
  idx,
  visits,
  T,
  span,
  width,
  now,
}: {
  idx: Partial<Record<RecCam, RecIndex>>;
  visits: Visit[];
  T: number;
  span: number;
  width: number;
  now: number;
}) {
  const t = useTheme();
  if (!width) return null;
  const lo = now - DAY;
  const pad = 8;
  const w = width - 2 * pad;
  const X = (x: number) => pad + ((x - lo) / DAY) * w;
  const marks: number[] = [];
  for (let x = Math.ceil((lo + tzOff()) / 21600) * 21600 - tzOff(); x <= now; x += 21600) marks.push(x);
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {marks.map((m) => (
        <View key={m}>
          <View style={{ position: 'absolute', left: X(m), top: 3, width: 1, height: 22, backgroundColor: t.border }} />
          <Text
            style={{
              position: 'absolute',
              left: clamp(X(m) - 16, 0, width - 32),
              width: 32,
              top: 26,
              textAlign: 'center',
              color: t.textFaint,
              fontSize: 10,
            }}
          >
            {fHM(m)}
          </Text>
        </View>
      ))}
      {REC_CAMS.map((c, i) => (
        <View
          key={c}
          style={{ position: 'absolute', left: pad, top: 4 + i * 7, width: w, height: 5, backgroundColor: 'rgba(127,127,127,0.12)' }}
        >
          {(idx[c]?.spans ?? []).map((sp) => (
            <View
              key={sp[0]}
              style={{
                position: 'absolute',
                left: X(sp[0]) - pad,
                width: Math.max(1, X(spanEnd(sp)) - X(sp[0])),
                height: 5,
                backgroundColor: LANE + '0.65)',
              }}
            />
          ))}
        </View>
      ))}
      {visits.map((v) => (
        <View
          key={`${v.cam}${v.start}`}
          style={{
            position: 'absolute',
            left: X(v.start) - 1,
            top: 4 + REC_CAMS.indexOf(v.cam as RecCam) * 7,
            width: Math.max(2, X(v.end) - X(v.start) + 2),
            height: 5,
            backgroundColor: CAT,
          }}
        />
      ))}
      {T ? (
        <>
          <View
            style={{
              position: 'absolute',
              left: Math.max(pad - 2, X(T - span / 2)),
              width: Math.max(4, Math.min(width - pad + 2, X(T + span / 2)) - Math.max(pad - 2, X(T - span / 2))),
              top: 1.5,
              height: 24,
              borderWidth: 1.5,
              borderColor: 'rgba(138,180,255,0.9)',
              borderRadius: 4,
            }}
          />
          <View style={{ position: 'absolute', left: X(T) - 1, top: 0, width: 2, height: 27, backgroundColor: t.text }} />
        </>
      ) : null}
    </View>
  );
}

/** Zoomable ribbon: time ticks, one lane per cam (blue = recorded, amber = sightings), playhead in the middle. */
function Ribbon({
  idx,
  sightings,
  T,
  span,
  width,
  now,
  big,
}: {
  idx: Partial<Record<RecCam, RecIndex>>;
  sightings: Sighting[];
  T: number;
  span: number;
  width: number;
  now: number;
  big: RecCam;
}) {
  const t = useTheme();
  if (!width || !T) return null;
  const spp = span / width;
  const t0 = T - (width / 2) * spp;
  const t1 = t0 + width * spp;
  const X = (x: number) => (x - t0) / spp;
  const { step, at } = ticks(t0, t1, spp, tzOff());
  const off = tzOff();
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {X(now - DAY) > 0 ? (
        <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: X(now - DAY), backgroundColor: 'rgba(0,0,0,0.25)' }} />
      ) : null}
      {X(now) < width ? (
        <View
          style={{ position: 'absolute', left: Math.max(0, X(now)), top: 0, bottom: 0, right: 0, backgroundColor: 'rgba(0,0,0,0.25)' }}
        />
      ) : null}
      {at.map((x) => {
        const midnight = (x + off) % DAY === 0;
        return (
          <View key={x}>
            <View
              style={{
                position: 'absolute',
                left: Math.round(X(x)),
                top: 20,
                width: 1,
                height: 80,
                backgroundColor: 'rgba(127,127,127,0.18)',
              }}
            />
            <Text
              style={{
                position: 'absolute',
                left: X(x) - 30,
                width: 60,
                top: 3,
                textAlign: 'center',
                fontSize: 11,
                color: midnight ? t.text : t.textDim,
              }}
            >
              {midnight ? fWD(x) : step < 60 ? fHMS(x) : fHM(x)}
            </Text>
          </View>
        );
      })}
      {REC_CAMS.map((c, i) => {
        const y = 26 + i * 23;
        return (
          <View key={c} style={{ position: 'absolute', left: 0, right: 0, top: y, height: 15 }}>
            <View
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                right: 0,
                bottom: 0,
                backgroundColor: 'rgba(127,127,127,0.08)',
                borderRadius: 4,
              }}
            />
            {(idx[c]?.spans ?? [])
              .filter((sp) => X(spanEnd(sp)) >= 0 && X(sp[0]) <= width)
              .map((sp) => (
                <View
                  key={sp[0]}
                  style={{
                    position: 'absolute',
                    top: 0,
                    height: 15,
                    borderRadius: 3,
                    left: Math.max(-4, X(sp[0])),
                    width: Math.max(0.5, Math.min(width + 4, X(spanEnd(sp))) - Math.max(-4, X(sp[0]))),
                    backgroundColor: LANE + (c === big ? '0.95)' : '0.3)'),
                  }}
                />
              ))}
            {sightings
              .filter((x) => x.cam === c && X(x.ts) > -10 && X(x.ts) < width + 10)
              .map((x) => (
                <View
                  key={x.ts}
                  style={{
                    position: 'absolute',
                    top: 3,
                    height: 9,
                    borderRadius: 2,
                    left: X(x.ts) - 2,
                    width: Math.max(4, 20 / spp),
                    backgroundColor: CAT,
                  }}
                />
              ))}
            <View style={[s.lane, { backgroundColor: t.dark ? 'rgba(20,23,28,0.85)' : 'rgba(255,255,255,0.85)' }]}>
              <Text style={{ fontSize: 10, fontWeight: '600', color: t.text }}>{SHORT[c]}</Text>
            </View>
          </View>
        );
      })}
      <View style={{ position: 'absolute', left: width / 2 - 1, top: 18, width: 2, bottom: 0, backgroundColor: t.text }} />
      <View style={[s.head, { left: width / 2 - 6, borderTopColor: t.text }]} />
    </View>
  );
}

const s = StyleSheet.create({
  err: { padding: space(3), borderRadius: radius.md, fontSize: 14 },
  tile: { position: 'absolute', overflow: 'hidden', backgroundColor: '#000' },
  tmsg: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 12,
    backgroundColor: 'rgba(11,13,16,0.8)',
  },
  tname: {
    position: 'absolute',
    top: 10,
    left: 12,
    right: 44,
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
    textShadowColor: 'rgba(0,0,0,0.7)',
    textShadowRadius: 3,
  },
  spin: { position: 'absolute', top: 10, right: 52 },
  paused: {
    position: 'absolute',
    left: '50%',
    top: '50%',
    width: 64,
    height: 64,
    marginLeft: -32,
    marginTop: -32,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  snapBtn: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 44,
    height: 44,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tl: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 6 },
  play: { width: 60, height: 60, borderRadius: 30, alignItems: 'center', justifyContent: 'center' },
  rb: {
    height: 48,
    minWidth: 46,
    paddingHorizontal: 8,
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  small: {
    height: 40,
    minWidth: 40,
    paddingHorizontal: 10,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    gap: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  seen: {
    flex: 1,
    height: 44,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 12,
    borderRadius: 99,
    borderWidth: StyleSheet.hairlineWidth,
  },
  visit: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 12, borderWidth: 1, alignItems: 'center' },
  lane: { position: 'absolute', left: 3, top: 1.5, height: 12, paddingHorizontal: 5, borderRadius: 4, justifyContent: 'center' },
  head: {
    position: 'absolute',
    top: 18,
    width: 0,
    height: 0,
    borderLeftWidth: 6,
    borderRightWidth: 6,
    borderTopWidth: 7,
    borderLeftColor: 'transparent',
    borderRightColor: 'transparent',
  },
});
