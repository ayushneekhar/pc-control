import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useRef, useState } from 'react';
import { AppState, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Banner, Bar, Card, Icon, Mono, Screen, Section } from '../components/ui';
import { get, Proc, Vitals } from '../lib/api';
import { bytes, rate } from '../lib/format';
import { space, useTheme } from '../theme';

type Hist = Vitals['hist'][number];

function Spark({
  series,
  max,
  height = 38,
}: {
  series: { values: (number | null | undefined)[]; color: string } | { values: (number | null | undefined)[]; color: string }[];
  max?: number;
  height?: number;
}) {
  const list = Array.isArray(series) ? series : [series];
  const sliced = list.map((s) => s.values.slice(-90));
  const n = Math.max(0, ...sliced.map((s) => s.length));
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', height, gap: 1 }}>
      {Array.from({ length: n }).map((_, i) => (
        <View key={i} style={{ flex: 1, height, flexDirection: 'row', alignItems: 'flex-end', gap: 1 }}>
          {sliced.map((vals, si) => {
            const v = vals[i - (n - vals.length)];
            const m = max ?? Math.max(1, ...vals.filter((x): x is number => x != null));
            const h = v == null ? 0 : Math.max(1, (Math.min(v, m) / m) * (height - 2));
            return <View key={si} style={{ flex: 1, height: h, backgroundColor: list[si].color, borderRadius: 1, opacity: 0.9 }} />;
          })}
        </View>
      ))}
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
  values?: (number | null | undefined)[];
  max?: number;
  footer?: string;
}) {
  const t = useTheme();
  return (
    <Card style={{ flex: 1, gap: space(2), padding: space(3.5) }}>
      <Text style={{ color: t.textDim, fontSize: 12, fontWeight: '700', letterSpacing: 0.4 }}>{label}</Text>
      <Text style={{ color: valueColor ?? t.text, fontSize: 22, fontWeight: '800', letterSpacing: -0.4 }}>{value}</Text>
      {pct != null ? <Bar pct={pct} color={color} height={5} /> : null}
      {sub ? (
        <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
          {sub}
        </Text>
      ) : null}
      {values ? <Spark series={{ values, color }} max={max} height={30} /> : null}
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
  return c >= 85 ? t.bad : c >= 70 ? t.warn : t.ok;
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
  const [v, setV] = useState<Vitals | null>(null);
  const [hist, setHist] = useState<Hist[]>([]);
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
                setHist(d.hist.slice(-300));
                full = false;
              } else {
                const h = d.hist[0];
                if (h) {
                  setHist((prev) => (prev.length && prev[prev.length - 1].t === h.t ? prev : [...prev, h].slice(-300)));
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

  const cpuTemp = v?.temps?.cpu;
  const nics = Object.entries(v?.nics ?? {});
  const rx = nics.reduce((a, [, n]) => a + n.rx, 0);
  const tx = nics.reduce((a, [, n]) => a + n.tx, 0);
  const activeNics = nics.filter(([, n]) => n.rx + n.tx > 1024);
  const otherTemps = Object.entries(v?.temps ?? {}).filter(([k, c]) => k !== 'cpu' && c != null);
  const allProcs = v?.procs ?? [];
  const query = q.trim().toLowerCase();
  const filtered = allProcs.filter(
    (p) => !query || p.cmd.toLowerCase().includes(query) || p.name.toLowerCase().includes(query) || String(p.pid) === query,
  );
  const sorted = filtered.slice().sort((a, b) => sortD * procCompare(a, b, sortK));
  const shown = sorted.slice(0, query ? 100 : 40);

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
            <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: live ? t.ok : t.bad }} />
            <Text style={{ color: t.textDim, fontSize: 14 }}>
              {live ? `Up ${upFor(v.uptime)} · live` : 'Disconnected, retrying…'}
            </Text>
          </View>
        ) : 'Live from the laptop'
      }
    >
      {err && !v ? <Banner tone="bad" title="Can't reach /vitals">{err}</Banner> : null}
      {v ? (
        <>
          <Card style={{ gap: space(3) }}>
            <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: space(3) }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textDim, fontSize: 13, fontWeight: '600' }}>CPU</Text>
                <Text style={{ color: t.text, fontSize: 44, fontWeight: '800', letterSpacing: -1.5 }}>
                  {Math.round(v.cpu)}
                  <Text style={{ fontSize: 22, color: t.textDim }}>%</Text>
                </Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={{ color: t.textDim, fontSize: 13 }}>{v.percpu.length} threads</Text>
                <Text style={{ color: t.textFaint, fontSize: 12 }}>
                  {v.freq != null ? `${(v.freq / 1000).toFixed(2)} GHz` : '–'}
                </Text>
              </View>
            </View>
            <Spark series={{ values: hist.map((h) => h.cpu), color: t.textDim }} max={100} />
            <View style={{ flexDirection: 'row', gap: 3, height: 26, alignItems: 'flex-end' }}>
              {v.percpu.map((c, i) => (
                <View key={i} style={{ flex: 1, height: 26, backgroundColor: t.cardAlt, borderRadius: 3, justifyContent: 'flex-end', overflow: 'hidden' }}>
                  <View
                    style={{
                      height: `${Math.max(4, c)}%`,
                      backgroundColor: c > 85 ? t.bad : c > 60 ? t.warn : t.textDim,
                      opacity: 0.85,
                    }}
                  />
                </View>
              ))}
            </View>
            <Text style={{ color: t.textFaint, fontSize: 12 }}>Load {v.load.map((l) => l.toFixed(2)).join(' ')}</Text>
          </Card>

          <View style={st.row}>
            <Metric
              label="MEMORY"
              value={`${Math.round(v.mem.pct)}%`}
              pct={v.mem.pct}
              color={t.ok}
              sub={`${bytes(v.mem.used)} / ${bytes(v.mem.total)} · swap ${Math.round(v.swap.pct)}%`}
              values={hist.map((h) => h.mem)}
            />
            <Metric
              label="CPU TEMP"
              value={cpuTemp == null ? '–' : `${Math.round(cpuTemp)}°C`}
              valueColor={tempColor(t, cpuTemp)}
              color={tempColor(t, cpuTemp)}
              sub={
                otherTemps.length
                  ? otherTemps.map(([k, c]) => `${k} ${Math.round(c as number)}°`).join(' · ')
                  : 'No other sensors'
              }
              values={hist.map((h) => h.temp)}
            />
          </View>

          <View style={st.row}>
            <Metric
              label="GPU"
              value={v.gpu ? `${Math.round(v.gpu.util)}%` : '–'}
              pct={v.gpu?.util}
              color={t.textDim}
              sub={
                v.gpu
                  ? `${Math.round(v.gpu.temp)}° · ${(v.gpu.mem_used / 1024).toFixed(1)}/${(v.gpu.mem_total / 1024).toFixed(0)} GB · ${v.gpu.power.toFixed(0)} W`
                  : 'Not available'
              }
              values={hist.map((h) => h.gpu)}
            />
            <Metric
              label="BATTERY"
              value={v.bat ? `${Math.round(v.bat.pct)}%` : '–'}
              pct={v.bat?.pct}
              color={v.bat && v.bat.pct < 20 && !v.bat.plugged ? t.bad : t.ok}
              sub={v.bat ? (v.bat.plugged ? 'Plugged in' : 'On battery') : 'No battery'}
              footer={`Disk read ${rate(v.disk_io.r)} · write ${rate(v.disk_io.w)}`}
            />
          </View>

          <Section label="Network">
            <Card style={{ gap: space(3) }}>
              <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space(3) }}>
                <Text style={{ color: t.text, fontSize: 18, fontWeight: '800' }}>↓ {rate(rx)}</Text>
                <Text style={{ color: t.textDim, fontSize: 15, fontWeight: '700' }}>↑ {rate(tx)}</Text>
                <Text style={{ color: t.textFaint, fontSize: 12, flex: 1, textAlign: 'right' }} numberOfLines={1}>
                  {activeNics.length ? activeNics.map(([n]) => n).join(', ') : 'idle'}
                </Text>
              </View>
              <Spark
                series={[
                  { values: hist.map((h) => h.rx), color: t.ok },
                  { values: hist.map((h) => h.tx), color: t.textDim },
                ]}
              />
              {nics.length ? (
                nics.map(([n, x]) => (
                  <View key={n} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                    <Text style={{ color: t.text, fontFamily: t.mono, fontSize: 13, flex: 1 }} numberOfLines={1}>
                      {n}
                    </Text>
                    <Icon name="arrow-down" size={14} color={t.ok} />
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
                    <Bar pct={d.pct} color={d.pct > 90 ? t.bad : t.ok} />
                  </View>
                ))
              ) : (
                <Text style={{ color: t.textFaint, fontSize: 13 }}>No disks reported.</Text>
              )}
            </Card>
          </Section>

          <Section
            label="Processes"
            right={<Switch value={procs} onValueChange={toggleProcs} trackColor={{ true: t.textDim, false: t.border }} thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'} />}
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
                        selectionColor={t.textDim}
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
                          android_ripple={{ color: t.cardAlt }}
                          style={[st.chip, { borderColor: on ? t.textDim : t.border, backgroundColor: on ? t.cardAlt : 'transparent' }]}
                        >
                          <Text style={{ color: on ? t.text : t.textDim, fontSize: 12, fontWeight: '700' }}>
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
                          <Text style={{ color: p.cpu > 50 ? t.bad : t.text, fontSize: 13, fontWeight: '700', width: 58, textAlign: 'right' }}>
                            {p.cpu.toFixed(1)}%
                          </Text>
                          <Text style={{ color: t.textDim, fontSize: 12, width: 68, textAlign: 'right' }}>{bytes(p.rss, 0)}</Text>
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
            ) : (
              <Text style={{ color: t.textFaint, paddingHorizontal: space(1), fontSize: 13 }}>
                Off. Sampling processes costs a little CPU on the laptop.
              </Text>
            )}
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
