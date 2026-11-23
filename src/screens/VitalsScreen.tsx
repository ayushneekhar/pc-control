import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useRef, useState } from 'react';
import { AppState, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Banner, Bar, Card, Icon, List, ListRow, Mono, Screen, Section, StatusDot } from '../components/ui';
import { get, Proc, Vitals } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { bytes, rate } from '../lib/format';
import { space, useTheme } from '../theme';

type Hist = Vitals['hist'][number];

type SparkSeries = { values: (number | null | undefined)[]; color: string };

function Spark({ series, max, height = 36 }: { series: SparkSeries | SparkSeries[]; max?: number; height?: number }) {
  const t = useTheme();
  const list = Array.isArray(series) ? series : [series];
  const [width, setWidth] = useState(0);
  const gap = 2;
  const bar = 2;
  const count = width > 0 ? Math.min(40, Math.max(1, Math.floor((width + gap) / (bar + gap)))) : 0;
  const rowHeight = Math.floor((height - gap * (list.length - 1)) / list.length);
  return (
    <View
      onLayout={(e) => setWidth(e.nativeEvent.layout.width)}
      style={{ height, gap, justifyContent: 'center' }}
    >
      {count
        ? list.map((s, si) => {
            const vals = s.values.slice(-count);
            const m = max ?? Math.max(1, ...vals.filter((x): x is number => x != null));
            return (
              <View key={si} style={{ height: rowHeight, flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'flex-end', gap }}>
                {vals.map((v, i) => (
                  <View
                    key={i}
                    style={{
                      width: bar,
                      height: v == null ? 0 : Math.max(2, (Math.min(v, m) / m) * rowHeight),
                      borderRadius: 1,
                      backgroundColor: i === vals.length - 1 && v != null ? t.text : s.color,
                    }}
                  />
                ))}
              </View>
            );
          })
        : null}
    </View>
  );
}

function Metric({
  label,
  value,
  valueColor,
  sub,
  pct,
  color,
  sparkColor,
  values,
  max = 100,
  footer,
}: {
  label: string;
  value: string;
  valueColor?: string;
  sub?: string;
  pct?: number;
  color: string;
  sparkColor?: string;
  values?: (number | null | undefined)[];
  max?: number;
  footer?: string;
}) {
  const t = useTheme();
  const over = pct != null && pct > 85;
  return (
    <Card style={{ flex: 1, gap: space(2), padding: space(3.5) }}>
      <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '500' }}>{label}</Text>
      <Text
        style={{
          color: over ? t.warn : valueColor ?? t.text,
          fontSize: 22,
          fontWeight: '500',
          letterSpacing: -0.3,
          fontVariant: ['tabular-nums'],
        }}
        numberOfLines={1}
        adjustsFontSizeToFit
        minimumFontScale={0.7}
      >
        {value}
      </Text>
      {pct != null ? <Bar pct={pct} color={over ? t.warn : color} height={5} /> : null}
      {sub ? (
        <Text style={{ color: over && sub.includes('%') ? t.warn : t.textFaint, fontSize: 12 }} numberOfLines={1}>
          {sub}
        </Text>
      ) : null}
      {values ? <Spark series={{ values, color: sparkColor ?? t.textDim }} max={max} height={36} /> : null}
      {footer ? (
        <Text style={{ color: t.textFaint, fontSize: 11.5 }} numberOfLines={1}>
          {footer}
        </Text>
      ) : null}
    </Card>
  );
}

function tempColor(t: ReturnType<typeof useTheme>, c?: number | null) {
  if (c == null) return t.textDim;
  return c >= 85 ? t.bad : c >= 70 ? t.warn : t.text;
}

function upFor(secs: number) {
  const s = Math.max(0, Math.floor(secs));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return `${d ? `${d}d ` : ''}${h}h ${m}m`;
}

type ProcSort = 'name' | 'pid' | 'user' | 'thr' | 'gpu' | 'rss' | 'cpu';

const SORTS: { k: ProcSort; label: string }[] = [
  { k: 'cpu', label: 'CPU' },
  { k: 'rss', label: 'Mem' },
  { k: 'name', label: 'Name' },
  { k: 'pid', label: 'PID' },
  { k: 'user', label: 'User' },
  { k: 'thr', label: 'Threads' },
  { k: 'gpu', label: 'GPU' },
];

function procCompare(a: Proc, b: Proc, k: ProcSort) {
  const x = a[k], y = b[k];
  if (typeof x === 'string' || typeof y === 'string') return String(x ?? '').localeCompare(String(y ?? ''));
  return (x ?? -1) - (y ?? -1);
}

export default function VitalsScreen() {
  const t = useTheme();
  const [v, setV] = useState<Vitals | null>(() => cacheGet<Vitals>('vitals:snapshot'));
  const [hist, setHist] = useState<Hist[]>(() => cacheGet<Vitals>('vitals:snapshot')?.hist?.slice(-300) ?? []);
  const [err, setErr] = useState<string | null>(null);
  const [live, setLive] = useState(true);
  const fails = useRef(0);
  const [procs, setProcs] = useState(false);
  const procsRef = useRef(false);
  const [q, setQ] = useState('');
  const [sortK, setSortK] = useState<ProcSort>('cpu');
  const [sortD, setSortD] = useState<1 | -1>(-1);

  const toggleProcs = (on: boolean) => {
    procsRef.current = on;
    setProcs(on);
  };

  useFocusEffect(
    useCallback(() => {
      let off = false;
      let timer: ReturnType<typeof setTimeout> | null = null;
      let full = true;
      const tick = async () => {
        if (AppState.currentState !== 'active') {
          full = true;
        } else {
          try {
            const params = `${full ? 'full=1&' : ''}${procsRef.current ? 'procs=1' : ''}`;
            const d = await get<Vitals>(`/vitals/api${params ? `?${params}` : ''}`, 6000);
            if (!off) {
              setV(d);
              setErr(null);
              setLive(true);
              fails.current = 0;
              if (full) {
                const nextHist = d.hist.slice(-300);
                setHist(nextHist);
                cacheSet('vitals:snapshot', { ...d, hist: nextHist, procs: null });
                full = false;
              } else {
                const h = d.hist[0];
                if (h) {
                  setHist((prev) => {
                    const nextHist = prev.length && prev[prev.length - 1].t === h.t ? prev : [...prev, h].slice(-300);
                    cacheSet('vitals:snapshot', { ...d, hist: nextHist, procs: null });
                    return nextHist;
                  });
                } else {
                  cacheSet('vitals:snapshot', { ...d, procs: null });
                }
              }
            }
          } catch (e) {
            if (!off) {
              fails.current += 1;
              if (fails.current > 2) {
                setErr((e as Error).message);
                setLive(false);
              }
            }
          }
        }
        if (!off) timer = setTimeout(tick, 1000);
      };
      tick();
      return () => {
        off = true;
        if (timer) clearTimeout(timer);
      };
    }, []),
  );

  const nics = Object.entries(v?.nics ?? {});
  const rx = nics.reduce((a, [, n]) => a + n.rx, 0);
  const tx = nics.reduce((a, [, n]) => a + n.tx, 0);
  const activeNics = nics.filter(([, n]) => n.rx + n.tx > 1024);
  const allProcs = v?.procs ?? [];
  const query = q.trim().toLowerCase();
  const filtered = allProcs.filter(
    (p) => !query || p.cmd.toLowerCase().includes(query) || p.name.toLowerCase().includes(query) || String(p.pid) === query,
  );
  const sorted = filtered.slice().sort((a, b) => sortD * procCompare(a, b, sortK));
  const shown = sorted.slice(0, query ? 100 : 40);
  const temps = Object.entries(v?.temps ?? {})
    .filter(([, c]) => c != null)
    .sort(([a], [b]) => (a === 'cpu' ? -1 : b === 'cpu' ? 1 : a.localeCompare(b))) as [string, number][];

  const chooseSort = (k: ProcSort) => {
    if (k === sortK) setSortD((d) => (d === -1 ? 1 : -1));
    else {
      setSortK(k);
      setSortD(k === 'name' || k === 'user' ? 1 : -1);
    }
  };

  return (
    <Screen
      title="Vitals"
      subtitle={
        v ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <StatusDot tone={live ? 'ok' : 'bad'} />
            <Text style={{ color: t.textDim, fontSize: 12.5 }}>
              {live ? `Up ${upFor(v.uptime)} · live` : 'Disconnected, retrying…'}
            </Text>
          </View>
        ) : undefined
      }
    >
      {err && !v ? <Banner tone="bad" title="Can't reach /vitals">{err}</Banner> : null}
      {v ? (
        <>
          <Card style={{ gap: space(3) }}>
            <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: space(3) }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '500' }}>CPU</Text>
                <Text style={{ color: t.text, fontSize: 44, fontWeight: '500', letterSpacing: -1.5, fontVariant: ['tabular-nums'] }}>
                  {Math.round(v.cpu)}
                  <Text style={{ fontSize: 20, color: t.textDim }}>%</Text>
                </Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={{ color: t.textDim, fontSize: 12.5 }}>{v.percpu.length} threads</Text>
                <Text style={{ color: t.textFaint, fontSize: 12 }}>
                  {v.freq != null ? `${(v.freq / 1000).toFixed(2)} GHz` : '–'}
                </Text>
              </View>
            </View>
            <Spark series={{ values: hist.map((h) => h.cpu), color: t.textDim }} max={100} />
            <View style={{ flexDirection: 'row', gap: 3, height: 6, alignItems: 'flex-end' }}>
              {v.percpu.map((c, i) => (
                <View key={i} style={{ flex: 1, height: 6, backgroundColor: t.cardAlt, borderRadius: 3, justifyContent: 'flex-end', overflow: 'hidden' }}>
                  <View
                    style={{
                      height: Math.max(1, (Math.min(100, c) / 100) * 6),
                      backgroundColor: c > 60 ? t.warn : t.textDim,
                      borderRadius: 3,
                    }}
                  />
                </View>
              ))}
            </View>
            <Text style={{ color: t.textFaint, fontSize: 12 }}>Load {v.load.map((l) => l.toFixed(2)).join(' ')}</Text>
          </Card>

          <View style={st.row}>
            <Metric
              label="Memory"
              value={`${(v.mem.used / 1024 ** 3).toFixed(1)} / ${(v.mem.total / 1024 ** 3).toFixed(0)} GB`}
              pct={v.mem.pct}
              color={t.text}
              sub={`${Math.round(v.mem.pct)}% used`}
              values={hist.map((h) => h.mem)}
            />
            <Metric
              label="Swap"
              value={`${(v.swap.used / 1024 ** 3).toFixed(1)} / ${(v.swap.total / 1024 ** 3).toFixed(0)} GB`}
              pct={v.swap.pct}
              color={t.text}
              sub={`${Math.round(v.swap.pct)}% used`}
            />
          </View>

          <View style={st.row}>
            <Metric
              label="GPU"
              value={v.gpu ? `${Math.round(v.gpu.util)}%` : '–'}
              pct={v.gpu?.util}
              color={t.textDim}
              sub={v.gpu ? `${Math.round(v.gpu.temp)}° · ${v.gpu.power.toFixed(0)} W` : 'Not available'}
              values={hist.map((h) => h.gpu)}
            />
            <Metric
              label="VRAM"
              value={
                v.gpu
                  ? `${(v.gpu.mem_used / 1024).toFixed(1)} / ${(v.gpu.mem_total / 1024).toFixed(0)} GB`
                  : '–'
              }
              pct={v.gpu && v.gpu.mem_total ? (v.gpu.mem_used / v.gpu.mem_total) * 100 : undefined}
              color={t.textDim}
              sub={v.gpu && v.gpu.mem_total ? `${Math.round((v.gpu.mem_used / v.gpu.mem_total) * 100)}% used` : 'Not available'}
            />
          </View>

          {temps.length ? (
            <Section label="Temperatures">
              <List>
                {temps.map(([name, c], i) => (
                  <ListRow
                    key={name}
                    title={name === 'cpu' ? 'CPU' : name === 'gpu' ? 'GPU' : name === 'nvme' ? 'NVMe' : name}
                    trailing={
                      <Text
                        style={{
                          color: tempColor(t, c),
                          fontSize: 15,
                          fontWeight: '500',
                          fontVariant: ['tabular-nums'],
                        }}
                      >
                        {Math.round(c)}°
                      </Text>
                    }
                    last={i === temps.length - 1}
                  />
                ))}
              </List>
            </Section>
          ) : null}

          <Section label="Power">
            <List>
              <ListRow
                title="Battery"
                subtitle={v.bat ? (v.bat.plugged ? 'Plugged in' : 'On battery') : 'No battery'}
                trailing={v.bat ? `${Math.round(v.bat.pct)}%` : '–'}
                last={false}
              />
              <ListRow title="Disk I/O" trailing={`↓ ${rate(v.disk_io.r)} · ↑ ${rate(v.disk_io.w)}`} last />
            </List>
          </Section>

          <Section label="Network">
            <Card style={{ gap: space(3) }}>
              <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space(3) }}>
                <Text style={{ color: t.text, fontSize: 18, fontWeight: '500', fontVariant: ['tabular-nums'] }}>↓ {rate(rx)}</Text>
                <Text style={{ color: t.textDim, fontSize: 15, fontWeight: '500', fontVariant: ['tabular-nums'] }}>↑ {rate(tx)}</Text>
                <Text style={{ color: t.textFaint, fontSize: 12, flex: 1, textAlign: 'right' }} numberOfLines={1}>
                  {activeNics.length ? activeNics.map(([n]) => n).join(', ') : 'idle'}
                </Text>
              </View>
              <Spark
                series={[
                  { values: hist.map((h) => h.rx), color: t.textDim },
                  { values: hist.map((h) => h.tx), color: t.textFaint },
                ]}
              />
              {nics.length ? (
                nics.map(([n, x]) => (
                  <View key={n} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                    <Text style={{ color: t.text, fontFamily: t.mono, fontSize: 13, flex: 1 }} numberOfLines={1}>
                      {n}
                    </Text>
                    <Icon name="arrow-down" size={14} color={t.textDim} />
                    <Text style={{ color: t.textDim, fontSize: 13, width: 86 }}>{rate(x.rx)}</Text>
                    <Icon name="arrow-up" size={14} color={t.textDim} />
                    <Text style={{ color: t.textDim, fontSize: 13, width: 86 }}>{rate(x.tx)}</Text>
                  </View>
                ))
              ) : (
                <Text style={{ color: t.textFaint, fontSize: 13 }}>No network interfaces.</Text>
              )}
            </Card>
          </Section>

          <Section label="Disks">
            <Card style={{ gap: space(3) }}>
              {v.disks.length ? (
                v.disks.map((d) => (
                  <View key={d.mount} style={{ gap: 6 }}>
                    <View style={{ flexDirection: 'row' }}>
                      <Text style={{ color: t.text, fontSize: 13.5, flex: 1 }} numberOfLines={1}>
                        {d.mount === '/' ? '/' : d.mount.split('/').pop()}
                        <Text style={{ color: t.textFaint }}> · {d.mount}</Text>
                      </Text>
                      <Text style={{ color: t.textDim, fontSize: 13 }}>
                        {bytes(d.used)} / {bytes(d.total)}
                      </Text>
                    </View>
                    <Bar pct={d.pct} />
                  </View>
                ))
              ) : (
                <Text style={{ color: t.textFaint, fontSize: 13 }}>No disks reported.</Text>
              )}
            </Card>
          </Section>

          <Section
            label="Processes"
            right={<Switch value={procs} onValueChange={toggleProcs} trackColor={{ true: t.accent, false: t.border }} thumbColor={t.onMedia} />}
          >
            {procs ? (
              v.procs ? (
                <Card style={{ gap: space(3) }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                    <View style={[st.inputWrap, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
                      <Icon name="magnify" size={16} color={t.textFaint} />
                      <TextInput
                        value={q}
                        onChangeText={setQ}
                        placeholder="Filter name, command or PID"
                        placeholderTextColor={t.textFaint}
                        autoCapitalize="none"
                        autoCorrect={false}
                        selectionColor={t.accent}
                        style={[st.input, { color: t.text }]}
                      />
                    </View>
                    <Text style={{ color: t.textFaint, fontSize: 12 }}>
                      {filtered.length}/{allProcs.length}
                    </Text>
                  </View>
                  <View style={st.chips}>
                    {SORTS.map((s) => {
                      const on = s.k === sortK;
                      return (
                        <Pressable
                          key={s.k}
                          onPress={() => chooseSort(s.k)}
                          android_ripple={{ color: t.ripple }}
                          style={[st.chip, { borderColor: on ? t.accent : t.border, backgroundColor: on ? t.accentSoft : 'transparent' }]}
                        >
                          <Text style={{ color: on ? t.accent : t.textDim, fontSize: 12, fontWeight: '500' }}>
                            {s.label}
                            {on ? (sortD < 0 ? ' ▾' : ' ▴') : ''}
                          </Text>
                        </Pressable>
                      );
                    })}
                  </View>
                  {shown.length ? (
                    shown.map((p, i) => (
                      <View
                        key={p.pid}
                        style={[st.procRow, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border }]}
                      >
                        <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space(2) }}>
                          <Text style={{ color: t.text, fontSize: 13, fontWeight: '600', flex: 1 }} numberOfLines={1}>
                            {p.name}
                          </Text>
                          <Text style={{ color: p.cpu > 50 ? t.bad : t.text, fontSize: 13, fontWeight: '500', width: 58, textAlign: 'right', fontVariant: ['tabular-nums'] }}>
                            {p.cpu.toFixed(1)}%
                          </Text>
                          <Text style={{ color: t.textDim, fontSize: 12, width: 68, textAlign: 'right', fontVariant: ['tabular-nums'] }}>{bytes(p.rss, 0)}</Text>
                        </View>
                        <Text style={{ color: t.textFaint, fontSize: 11.5 }} numberOfLines={1}>
                          PID {p.pid} · {p.user || '–'} · {p.thr ?? '–'} thr
                          {p.gpu != null ? ` · GPU ${Math.round(p.gpu)} MB` : ''}
                        </Text>
                        <Mono style={{ fontSize: 10.5, color: t.textFaint, lineHeight: 14 }} selectable={false} numberOfLines={1}>
                          {p.cmd.slice(0, 120)}
                        </Mono>
                      </View>
                    ))
                  ) : (
                    <Text style={{ color: t.textFaint, fontSize: 13 }}>No matching processes.</Text>
                  )}
                  {filtered.length > shown.length ? (
                    <Text style={{ color: t.textFaint, fontSize: 12 }}>
                      Showing {shown.length} of {filtered.length}
                    </Text>
                  ) : null}
                </Card>
              ) : (
                <Card>
                  <Text style={{ color: t.textDim }}>Sampling…</Text>
                </Card>
              )
            ) : null}
          </Section>
        </>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  row: { flexDirection: 'row', gap: space(3) },
  inputWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 9,
    paddingHorizontal: space(2.5),
    flex: 1,
    height: 38,
  },
  input: { flex: 1, fontSize: 13.5, padding: 0 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 99, borderWidth: StyleSheet.hairlineWidth },
  procRow: { gap: 2, paddingTop: space(2) },
});
