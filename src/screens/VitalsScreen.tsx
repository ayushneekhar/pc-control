import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useRef, useState } from 'react';
import { AppState, StyleSheet, Switch, Text, View } from 'react-native';
import { Banner, Bar, Card, Icon, KV, Mono, Screen, Section } from '../components/ui';
import { get, Vitals } from '../lib/api';
import { bytes, duration, rate } from '../lib/format';
import { space, useTheme } from '../theme';

function Spark({ values, max = 100, color, height = 44 }: { values: number[]; max?: number; color: string; height?: number }) {
  const vals = values.slice(-60);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', height, gap: 2 }}>
      {vals.map((v, i) => (
        <View
          key={i}
          style={{
            flex: 1,
            height: Math.max(2, (Math.min(v, max) / max) * height),
            backgroundColor: color,
            opacity: 0.35 + (0.65 * (i + 1)) / vals.length,
            borderRadius: 2,
          }}
        />
      ))}
    </View>
  );
}

function Tile({ icon, label, value, sub, pct, color }: { icon: string; label: string; value: string; sub?: string; pct?: number; color?: string }) {
  const t = useTheme();
  return (
    <Card style={{ flex: 1, gap: space(2), padding: space(3.5) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
        <Icon name={icon} size={16} color={color ?? t.textDim} />
        <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '600' }}>{label}</Text>
      </View>
      <Text style={{ color: t.text, fontSize: 22, fontWeight: '800', letterSpacing: -0.4 }}>{value}</Text>
      {pct != null ? <Bar pct={pct} color={color} /> : null}
      {sub ? (
        <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
          {sub}
        </Text>
      ) : null}
    </Card>
  );
}

function tempColor(t: ReturnType<typeof useTheme>, c?: number) {
  if (c == null) return t.textDim;
  return c >= 85 ? t.bad : c >= 70 ? t.warn : t.ok;
}

export default function VitalsScreen() {
  const t = useTheme();
  const [v, setV] = useState<Vitals | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [procs, setProcs] = useState(false);
  const procsRef = useRef(false);
  const toggleProcs = (on: boolean) => {
    procsRef.current = on;
    setProcs(on);
  };

  useFocusEffect(
    useCallback(() => {
      let off = false;
      let timer: ReturnType<typeof setTimeout> | null = null;
      const tick = async () => {
        if (AppState.currentState === 'active') {
          try {
            const d = await get<Vitals>(`/vitals/api${procsRef.current ? '?procs=1' : ''}`, 6000);
            if (!off) {
              setV(d);
              setErr(null);
            }
          } catch (e) {
            if (!off) setErr((e as Error).message);
          }
        }
        if (!off) timer = setTimeout(tick, 2000);
      };
      tick();
      return () => {
        off = true;
        if (timer) clearTimeout(timer);
      };
    }, []),
  );

  const hist = v?.hist ?? [];
  const cpuTemp = v?.temps?.cpu;
  const nics = Object.entries(v?.nics ?? {}).filter(([n]) => n !== 'lo');

  return (
    <Screen title="Vitals" subtitle={v ? `Up ${duration(v.uptime)} · load ${v.load.map((l) => l.toFixed(2)).join(' ')}` : 'Live from the laptop'}>
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
                <Text style={{ color: tempColor(t, cpuTemp), fontSize: 22, fontWeight: '800' }}>{cpuTemp != null ? `${Math.round(cpuTemp)}°` : '–'}</Text>
                <Text style={{ color: t.textFaint, fontSize: 12 }}>{(v.freq / 1000).toFixed(2)} GHz</Text>
              </View>
            </View>
            <Spark values={hist.map((h) => h.cpu)} color={t.accent} />
            <View style={{ flexDirection: 'row', gap: 3, height: 26, alignItems: 'flex-end' }}>
              {v.percpu.map((c, i) => (
                <View key={i} style={{ flex: 1, height: 26, backgroundColor: t.cardAlt, borderRadius: 3, justifyContent: 'flex-end', overflow: 'hidden' }}>
                  <View style={{ height: `${Math.max(4, c)}%`, backgroundColor: c > 85 ? t.bad : c > 60 ? t.warn : t.accent, opacity: 0.85 }} />
                </View>
              ))}
            </View>
          </Card>

          <View style={st.row}>
            <Tile icon="memory" label="Memory" value={`${Math.round(v.mem.pct)}%`} pct={v.mem.pct} sub={`${bytes(v.mem.used)} of ${bytes(v.mem.total)}`} />
            <Tile
              icon="expansion-card"
              label="GPU"
              value={v.gpu ? `${Math.round(v.gpu.util)}%` : '–'}
              pct={v.gpu?.util}
              color={t.accent}
              sub={v.gpu ? `${Math.round(v.gpu.temp)}° · ${Math.round(v.gpu.power)} W · ${bytes(v.gpu.mem_used * 1048576, 1)}` : 'not available'}
            />
          </View>
          <View style={st.row}>
            <Tile icon="swap-horizontal" label="Swap" value={`${Math.round(v.swap.pct)}%`} pct={v.swap.pct} sub={`${bytes(v.swap.used)} of ${bytes(v.swap.total)}`} />
            <Tile
              icon={v.bat ? (v.bat.plugged ? 'battery-charging' : 'battery') : 'battery-unknown'}
              label="Battery"
              value={v.bat ? `${Math.round(v.bat.pct)}%` : '–'}
              pct={v.bat?.pct}
              color={v.bat && v.bat.pct < 20 && !v.bat.plugged ? t.bad : t.ok}
              sub={v.bat ? (v.bat.plugged ? 'Plugged in' : 'On battery') : ''}
            />
          </View>

          <Section label="Temperatures">
            <Card style={{ paddingVertical: space(2) }}>
              {Object.entries(v.temps).map(([k, c]) => (
                <KV key={k} k={k.toUpperCase()} v={<Text style={{ color: tempColor(t, c), fontWeight: '700' }}>{`${c.toFixed(0)}°C`}</Text>} />
              ))}
            </Card>
          </Section>

          <Section label="Network">
            <Card style={{ gap: space(2) }}>
              <Spark values={hist.map((h) => h.rx + h.tx)} max={Math.max(1, ...hist.map((h) => h.rx + h.tx))} color={t.ok} height={30} />
              {nics.map(([n, x]) => (
                <View key={n} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                  <Text style={{ color: t.text, fontFamily: t.mono, fontSize: 13, flex: 1 }}>{n}</Text>
                  <Icon name="arrow-down" size={14} color={t.ok} />
                  <Text style={{ color: t.textDim, fontSize: 13, width: 86 }}>{rate(x.rx)}</Text>
                  <Icon name="arrow-up" size={14} color={t.accent} />
                  <Text style={{ color: t.textDim, fontSize: 13, width: 86 }}>{rate(x.tx)}</Text>
                </View>
              ))}
            </Card>
          </Section>

          <Section label="Disks">
            <Card style={{ gap: space(3) }}>
              {v.disks.map((d) => (
                <View key={d.mount} style={{ gap: 6 }}>
                  <View style={{ flexDirection: 'row' }}>
                    <Text style={{ color: t.text, fontSize: 13.5, flex: 1 }} numberOfLines={1}>
                      {d.mount}
                    </Text>
                    <Text style={{ color: t.textDim, fontSize: 13 }}>
                      {bytes(d.used, 0)} / {bytes(d.total, 0)}
                    </Text>
                  </View>
                  <Bar pct={d.pct} />
                </View>
              ))}
              <Text style={{ color: t.textFaint, fontSize: 12 }}>
                I/O read {rate(v.disk_io.r)} · write {rate(v.disk_io.w)}
              </Text>
            </Card>
          </Section>

          <Section
            label="Processes"
            right={<Switch value={procs} onValueChange={toggleProcs} trackColor={{ true: t.accent, false: t.border }} thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'} />}
          >
            {procs ? (
              <Card style={{ gap: space(2) }}>
                {!v.procs ? <Text style={{ color: t.textDim }}>Sampling…</Text> : null}
                {(v.procs ?? [])
                  .slice()
                  .sort((a, b) => b.cpu - a.cpu)
                  .slice(0, 15)
                  .map((p) => (
                    <View key={p.pid} style={{ flexDirection: 'row', gap: space(2), alignItems: 'center' }}>
                      <Text style={{ color: t.text, fontSize: 13, width: 48, textAlign: 'right', fontWeight: '700' }}>{p.cpu.toFixed(1)}%</Text>
                      <View style={{ flex: 1 }}>
                        <Text style={{ color: t.text, fontSize: 13 }} numberOfLines={1}>
                          {p.name} <Text style={{ color: t.textFaint }}>· {p.user}</Text>
                        </Text>
                        <Mono style={{ fontSize: 10.5, color: t.textFaint, lineHeight: 14 }} selectable={false}>
                          {p.cmd.slice(0, 80)}
                        </Mono>
                      </View>
                      <Text style={{ color: t.textDim, fontSize: 12 }}>{bytes(p.rss, 0)}</Text>
                    </View>
                  ))}
              </Card>
            ) : (
              <Text style={{ color: t.textFaint, paddingHorizontal: space(1), fontSize: 13 }}>Off. Sampling processes costs a little CPU on the laptop.</Text>
            )}
          </Section>
        </>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  row: { flexDirection: 'row', gap: space(3) },
});
