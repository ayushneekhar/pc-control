import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Banner, Button, Card, KV, Mono, Pill, Screen } from '../components/ui';
import { get, post, Service } from '../lib/api';
import { bytes } from '../lib/format';
import { radius, space, useTheme } from '../theme';

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

export default function ServiceDetailScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const route = useRoute<any>();
  const initial = route.params?.service as Service;
  const unit = String(route.params?.unit || initial?.unit || '');
  const [svc, setSvc] = useState<Service | undefined>(initial);
  const [logs, setLogs] = useState<string[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const name = unit.replace(/\.service$/, '');

  const loadLogs = useCallback(async () => {
    try {
      const r = await get<{ lines: string[] }>(`/pc/api/services/${encodeURIComponent(unit)}/logs?n=120`);
      setLogs(r.lines);
    } catch (e) {
      setLogs([`(${(e as Error).message})`]);
    }
  }, [unit]);

  const loadStatus = useCallback(async () => {
    try {
      const r = await get<{ services: Service[] }>('/pc/api/services');
      const found = r.services.find((s) => s.unit === unit);
      if (found) setSvc(found);
    } catch {
      // keep the status we opened with
    }
  }, [unit]);

  useFocusEffect(
    useCallback(() => {
      void loadLogs();
    }, [loadLogs]),
  );

  const restart = () =>
    Alert.alert(`Restart ${name}?`, svc?.description, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Restart',
        onPress: async () => {
          setBusy(true);
          setErr(null);
          try {
            await post(`/pc/api/services/${encodeURIComponent(unit)}/restart`, {});
            setTimeout(async () => {
              await Promise.all([loadLogs(), loadStatus()]);
              setBusy(false);
            }, 1800);
          } catch (e) {
            setBusy(false);
            setErr((e as Error).message);
          }
        },
      },
    ]);

  if (!unit) {
    return (
      <Screen title="Service" onBack={() => nav.goBack()}>
        <Banner tone="bad" title="No service selected" />
      </Screen>
    );
  }

  const failed = svc?.active === 'failed';
  const color = failed ? t.bad : svc?.active === 'active' ? t.ok : t.textFaint;

  return (
    <Screen title={name} subtitle={svc?.description} onBack={() => nav.goBack()}>
      {err ? <Banner tone="bad" title="Restart failed">{err}</Banner> : null}
      {logs?.[0]?.startsWith('(') && logs.length === 1 ? <Banner tone="warn" title="Can't load logs">{logs[0]}</Banner> : null}

      <Card style={{ gap: space(3) }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
          <View style={[st.dot, { backgroundColor: color }]} />
          <View style={{ flex: 1 }}>
            <Text style={{ color, fontSize: 15, fontWeight: '800' }}>{svc?.active || 'unknown'}</Text>
            <Text style={{ color: t.textFaint, fontSize: 12.5 }}>{svc?.sub || ''}</Text>
          </View>
          <Pill label={svc?.enabled || 'static'} tone={svc?.enabled === 'enabled' ? 'ok' : 'dim'} />
        </View>
        <View style={{ gap: 1 }}>
          {svc?.since ? <KV k="Running for" v={since(svc.since)} /> : null}
          {svc?.pid ? <KV k="PID" v={String(svc.pid)} mono /> : null}
          {svc?.memory ? <KV k="Memory" v={bytes(svc.memory, 0)} /> : null}
          {svc?.restarts ? <KV k="Restarts" v={String(svc.restarts)} /> : null}
          {svc?.type ? <KV k="Type" v={svc.type} mono /> : null}
          {svc?.result && svc.result !== 'success' ? <KV k="Last result" v={svc.result} /> : null}
        </View>
      </Card>

      <Card style={{ gap: space(2) }}>
        <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '700' }}>Recent logs</Text>
        <ScrollView horizontal style={[st.logs, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
          <Mono style={{ fontSize: 11 }}>{logs ? logs.join('\n') || '(no log lines)' : 'Loading logs…'}</Mono>
        </ScrollView>
      </Card>

      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Refresh logs" kind="subtle" icon="refresh" compact style={{ flex: 1 }} onPress={loadLogs} />
        <Button label="Restart" kind="ok" icon="restart" compact style={{ flex: 1 }} busy={busy} onPress={restart} />
      </View>
    </Screen>
  );
}

const st = StyleSheet.create({
  dot: { width: 11, height: 11, borderRadius: 6 },
  logs: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, padding: space(3), maxHeight: 320 },
});
