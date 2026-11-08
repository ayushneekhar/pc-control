import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Banner, Button, Card, Icon, Mono, Pill, Screen } from '../components/ui';
import { get, post, Service } from '../lib/api';
import { bytes } from '../lib/format';
import { radius, space, useTheme } from '../theme';

function tone(s: Service): 'ok' | 'bad' | 'warn' | 'dim' {
  if (s.active === 'active') return 'ok';
  if (s.active === 'failed') return 'bad';
  if (s.active === 'activating' || s.active === 'deactivating' || s.active === 'reloading') return 'warn';
  return 'dim';
}

function since(s: string) {
  if (!s) return '';
  const d = new Date(s.replace(/^\w+ /, '').replace(/ [A-Z]{2,5}$/, ''));
  if (isNaN(d.getTime())) return s;
  const secs = (Date.now() - d.getTime()) / 1000;
  if (secs < 0) return '';
  if (secs < 3600) return `${Math.max(1, Math.round(secs / 60))}m`;
  if (secs < 86400) return `${Math.round(secs / 3600)}h`;
  return `${Math.round(secs / 86400)}d`;
}

function ServiceRow({ s, onChanged }: { s: Service; onChanged: () => void }) {
  const t = useTheme();
  const [open, setOpen] = useState(false);
  const [logs, setLogs] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const name = s.unit.replace(/\.service$/, '');
  const tn = tone(s);
  const color = tn === 'ok' ? t.ok : tn === 'bad' ? t.bad : tn === 'warn' ? t.warn : t.textFaint;

  const loadLogs = async () => {
    try {
      const r = await get<{ lines: string[] }>(`/pc/api/services/${encodeURIComponent(s.unit)}/logs?n=60`);
      setLogs(r.lines);
    } catch (e) {
      setLogs([`(${(e as Error).message})`]);
    }
  };

  const restart = () =>
    Alert.alert(`Restart ${name}?`, s.description, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Restart',
        onPress: async () => {
          setBusy(true);
          try {
            await post(`/pc/api/services/${encodeURIComponent(s.unit)}/restart`, {});
            setTimeout(() => {
              onChanged();
              if (open) loadLogs();
              setBusy(false);
            }, 1800);
          } catch (e) {
            setBusy(false);
            Alert.alert('Restart failed', (e as Error).message);
          }
        },
      },
    ]);

  return (
    <Card style={{ padding: 0 }}>
      <Pressable
        onPress={() => {
          setOpen((o) => !o);
          if (!open && !logs) loadLogs();
        }}
        android_ripple={{ color: t.accentSoft }}
        style={st.row}
      >
        <View style={[st.dot, { backgroundColor: color, shadowColor: color }]} />
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '700' }} numberOfLines={1}>
            {name}
          </Text>
          <Text style={{ color: t.textDim, fontSize: 12.5 }} numberOfLines={open ? 4 : 1}>
            {s.description}
          </Text>
        </View>
        <View style={{ alignItems: 'flex-end', gap: 3 }}>
          <Text style={{ color, fontSize: 12.5, fontWeight: '700' }}>{s.timer && s.active !== 'active' ? 'timer' : s.sub}</Text>
          <Text style={{ color: t.textFaint, fontSize: 11.5 }}>
            {[s.active === 'active' ? since(s.since) : '', s.memory ? bytes(s.memory, 0) : ''].filter(Boolean).join(' · ')}
          </Text>
        </View>
      </Pressable>
      {open ? (
        <View style={{ paddingHorizontal: space(4), paddingBottom: space(4), gap: space(3) }}>
          <View style={{ flexDirection: 'row', gap: space(2), flexWrap: 'wrap' }}>
            <Pill label={s.enabled || 'static'} tone={s.enabled === 'enabled' ? 'accent' : 'dim'} />
            {s.pid ? <Pill label={`pid ${s.pid}`} /> : null}
            {s.restarts ? <Pill tone="warn" label={`${s.restarts} restarts`} /> : null}
            {s.result && s.result !== 'success' ? <Pill tone="bad" label={s.result} /> : null}
          </View>
          <ScrollView horizontal style={[st.logs, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
            <Mono style={{ fontSize: 11 }}>{logs ? logs.join('\n') || '(no log lines)' : 'Loading logs…'}</Mono>
          </ScrollView>
          <View style={{ flexDirection: 'row', gap: space(2) }}>
            <Button label="Refresh logs" kind="subtle" icon="refresh" compact style={{ flex: 1 }} onPress={loadLogs} />
            <Button label="Restart" kind="primary" icon="restart" compact style={{ flex: 1 }} busy={busy} onPress={restart} />
          </View>
        </View>
      ) : null}
    </Card>
  );
}

export default function ServicesScreen() {
  const t = useTheme();
  const [list, setList] = useState<Service[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      const r = await get<{ services: Service[] }>('/pc/api/services');
      setList(r.services);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
      const iv = setInterval(load, 15000);
      return () => clearInterval(iv);
    }, [load]),
  );

  const order = { bad: 0, warn: 1, ok: 2, dim: 3 };
  const shown = (list ?? [])
    .filter((s) => !q || (s.unit + ' ' + s.description).toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => order[tone(a)] - order[tone(b)] || a.unit.localeCompare(b.unit));
  const counts = (list ?? []).reduce(
    (acc, s) => ((acc[tone(s)] += 1), acc),
    { ok: 0, bad: 0, warn: 0, dim: 0 } as Record<string, number>,
  );

  return (
    <Screen
      title="Services"
      subtitle={list ? `${counts.ok} running${counts.bad ? ` · ${counts.bad} failed` : ''} · ${list.length} units` : 'Your systemd user units'}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      <View style={[st.search, { backgroundColor: t.card, borderColor: t.border }]}>
        <Icon name="magnify" size={18} color={t.textFaint} />
        <TextInput
          value={q}
          onChangeText={setQ}
          placeholder="Filter"
          placeholderTextColor={t.textFaint}
          style={{ flex: 1, color: t.text, fontSize: 15, paddingVertical: 0 }}
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>
      {err ? <Banner tone="bad" title="Can't load services">{err}</Banner> : null}
      {shown.map((s) => (
        <ServiceRow key={s.unit} s={s} onChanged={load} />
      ))}
    </Screen>
  );
}

const st = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space(3), paddingHorizontal: space(4), paddingVertical: space(3.5) },
  dot: { width: 10, height: 10, borderRadius: 5, shadowOpacity: 0.8, shadowRadius: 4, elevation: 0 },
  logs: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, padding: space(3), maxHeight: 260 },
  search: { flexDirection: 'row', alignItems: 'center', gap: space(2), borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: space(3), height: 44 },
});
