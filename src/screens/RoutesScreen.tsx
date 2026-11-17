import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { Image, Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import {
  Banner,
  Card,
  Icon,
  List,
  ListRow,
  Screen,
  Section,
  StatStrip,
  StatusDot,
} from '../components/ui';
import { PcNative } from '../../modules/pc-native';
import { get, Health, Info, Route, serverUrl, Vitals } from '../lib/api';
import { CAMS, CamId, NAMES } from '../lib/cams/config';
import { ago, bytes, duration, rate } from '../lib/format';
import { DownloadsReply } from '../lib/live';
import { radius, space, useTheme } from '../theme';

/** Routes the app plays natively instead of opening the browser (the screen keeps a link to the web page). */
const NATIVE: Record<string, string> = { '/cams/': 'Cams', '/agentview': 'AgentView' };

/** Phone-side names for routes that open a native screen. */
const TILE: Record<string, Partial<Pick<Route, 'name' | 'icon' | 'subtitle'>>> = {
  '/agentview': { name: 'Agent view', icon: 'robot-outline', subtitle: 'What agents see' },
};

type DetState = {
  enabled: boolean;
  cams?: Record<string, { name?: string; last_seen_ts?: number }>;
  last_alert?: { cam: string; name?: string; ts: number };
};

function open(r: Route) {
  if (r.app && PcNative?.launchApp?.(r.app)) return;
  Linking.openURL(r.url);
}

async function safe<T>(p: Promise<T>): Promise<T | null> {
  try {
    return await p;
  } catch {
    return null;
  }
}

function lastSighting(det: DetState | null): { ts: number; name: string } | null {
  if (!det) return null;
  let best: { ts: number; name: string } | null = null;
  for (const [k, c] of Object.entries(det.cams ?? {}))
    if (c.last_seen_ts && (!best || c.last_seen_ts > best.ts)) best = { ts: c.last_seen_ts, name: NAMES[k] || c.name || k };
  if (!best && det.last_alert)
    best = { ts: det.last_alert.ts, name: NAMES[det.last_alert.cam] || det.last_alert.name || det.last_alert.cam };
  return best;
}

function routeView(r: Route) {
  const view = r.port === 443 ? TILE[r.path] : undefined;
  return {
    name: view?.name ?? r.name,
    icon: view?.icon ?? r.icon,
    subtitle: view?.subtitle ?? r.subtitle,
    screen: r.port === 443 ? NATIVE[r.path] : undefined,
  };
}

function RouteTile({ r }: { r: Route }) {
  const t = useTheme();
  const nav = useNavigation<any>();
  const view = routeView(r);
  return (
    <Card
      onPress={() => (view.screen ? nav.navigate(view.screen) : open(r))}
      style={{ gap: space(2.5), minHeight: 104, justifyContent: 'space-between' }}
    >
      <Icon name={view.icon} size={22} color={t.textDim} />
      <View style={{ gap: 2 }}>
        <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '500' }} numberOfLines={1}>
          {view.name}
        </Text>
        <Text style={{ color: t.textFaint, fontSize: 11.5, fontFamily: t.mono }} numberOfLines={1}>
          {r.kind === 'app' ? 'app' : r.port === 443 ? r.path : `:${r.port}`}
        </Text>
      </View>
    </Card>
  );
}

function CameraTile({
  id,
  name,
  tick,
  failed,
  onFail,
}: {
  id: CamId;
  name: string;
  tick: number;
  failed: boolean;
  onFail: () => void;
}) {
  const t = useTheme();
  return (
    <View style={st.cam}>
      {failed ? (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="video-off-outline" size={20} color={t.onMediaDim} />
        </View>
      ) : (
        <Image
          source={{ uri: `${serverUrl()}/api/frame.jpeg?src=${id}&t=${tick}` }}
          style={StyleSheet.absoluteFill}
          resizeMode="cover"
          onError={onFail}
        />
      )}
      <View style={[st.camName, { backgroundColor: t.mediaChip }]}>
        <Text style={{ color: t.onMedia, fontSize: 11 }} numberOfLines={1}>
          {name}
        </Text>
      </View>
    </View>
  );
}

function StatusLine({ tone, label }: { tone: 'ok' | 'warn' | 'bad' | 'faint'; label: string }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 2 }}>
      <StatusDot tone={tone} />
      <Text style={{ color: t.textDim, fontSize: 12.5 }}>{label}</Text>
    </View>
  );
}

export default function RoutesScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const [routes, setRoutes] = useState<Route[] | null>(null);
  const [vitals, setVitals] = useState<Vitals | null>(null);
  const [info, setInfo] = useState<Info | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [det, setDet] = useState<DetState | null>(null);
  const [downloads, setDownloads] = useState<DownloadsReply | null>(null);
  const [tick, setTick] = useState(0);
  const [failedCams, setFailedCams] = useState<Record<string, boolean>>({});
  const [loaded, setLoaded] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    const [i, v, h, r, d, dl] = await Promise.all([
      safe(get<Info>('/pc/api/info')),
      safe(get<Vitals>('/vitals/api')),
      safe(get<Health>('/pc/api/health')),
      safe(get<{ routes: Route[] }>('/pc/api/routes')),
      safe(get<DetState>('/detect/state')),
      safe(get<DownloadsReply>('/pc/api/downloads')),
    ]);
    setInfo(i);
    setVitals(v);
    setHealth(h);
    if (r) setRoutes(r.routes.filter((x) => !x.hidden));
    setDet(d);
    setDownloads(dl);
    setLoaded(true);
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
      setFailedCams({});
      const id = setInterval(() => {
        setTick((n) => n + 1);
        setFailedCams({});
      }, 5000);
      return () => clearInterval(id);
    }, [load]),
  );

  const latest = lastSighting(det);
  const problems = health?.problems?.length ?? 0;
  const offline = loaded && !info && !vitals && !routes;
  const statusTone = offline ? 'bad' : problems ? 'warn' : info ? 'ok' : 'faint';
  const statusLabel = offline
    ? "Can't reach the laptop"
    : info
      ? `Connected · ${info.host}`
      : 'Connecting to the laptop…';
  const temp = vitals
    ? Object.values(vitals.temps ?? {}).reduce<number | null>((m, v) => (v == null ? m : m == null ? v : Math.max(m, v)), null)
    : null;
  const active = downloads?.downloads ?? [];
  const openScreen = routes?.length ? routes : [];

  return (
    <Screen
      title="Home"
      subtitle={<StatusLine tone={statusTone} label={statusLabel} />}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {offline ? (
        <Banner tone="bad" title="Can't reach the laptop" icon="wifi-off">
          Open Settings to check the server address, and make sure Tailscale is connected.
        </Banner>
      ) : null}

      {vitals ? (
        <StatStrip
          onPress={() => nav.navigate('Vitals')}
          items={[
            { label: 'CPU', value: Math.round(vitals.cpu), unit: '%' },
            { label: 'Memory', value: Math.round(vitals.mem.pct), unit: '%' },
            { label: 'Temp', value: temp == null ? '–' : Math.round(temp), unit: temp == null ? undefined : '°C' },
            { label: 'Uptime', value: duration(vitals.uptime) },
          ]}
        />
      ) : !loaded ? (
        <Card>
          <Text style={{ color: t.textDim }}>Loading…</Text>
        </Card>
      ) : null}

      <Section
        label="Cameras"
        right={
          <Pressable onPress={() => nav.navigate('Cams')} hitSlop={10}>
            <Text style={{ color: t.accent, fontSize: 12.5, fontWeight: '500' }}>Open</Text>
          </Pressable>
        }
      >
        <Card style={{ padding: space(2), gap: space(2) }}>
          <View style={{ flexDirection: 'row', gap: space(2) }}>
            {CAMS.map((c) => (
              <CameraTile
                key={c.id}
                id={c.id}
                name={c.name}
                tick={tick}
                failed={!!failedCams[c.id]}
                onFail={() => setFailedCams((m) => ({ ...m, [c.id]: true }))}
              />
            ))}
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: space(1) }}>
            <StatusDot tone={latest ? 'ok' : 'faint'} size={6} />
            <Text style={{ color: t.textDim, fontSize: 12 }} numberOfLines={1}>
              {latest ? `Cat seen ${ago(latest.ts)} · ${latest.name}` : det ? 'No cats yet' : 'Detector offline'}
            </Text>
          </View>
        </Card>
      </Section>

      {active.length ? (
        <Section label="Downloads">
          <List>
            {active.slice(0, 4).map((d, i) => (
              <ListRow
                key={d.id}
                icon={d.state === 'stalled' ? 'alert-circle-outline' : 'download-outline'}
                title={d.name}
                subtitle={`${Math.round(d.progress * 100)}% · ${rate(d.speed)}${
                  d.state === 'stalled' ? ' · stalled' : ''
                }`}
                trailing={d.eta == null ? undefined : duration(d.eta)}
                last={i === Math.min(active.length, 4) - 1}
              />
            ))}
          </List>
        </Section>
      ) : null}

      <Section label="Shortcuts">
        {openScreen.length ? (
          <View style={st.grid}>
            {openScreen.map((r) => (
              <View key={r.id} style={st.cell}>
                <RouteTile r={r} />
              </View>
            ))}
          </View>
        ) : (
          <Card>
            <Text style={{ color: t.textFaint }}>{loaded ? 'No shortcuts on this server.' : 'Loading…'}</Text>
          </Card>
        )}
      </Section>

      {health?.downloads.total && health.downloads.total.downloading > 0 ? (
        <Text style={{ color: t.textFaint, fontSize: 12, textAlign: 'center' }}>
          {health.downloads.total.downloading} downloading · {bytes(health.downloads.total.done, 0)} of{' '}
          {bytes(health.downloads.total.size, 0)}
        </Text>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', marginHorizontal: -space(1) },
  cell: { width: '50%', padding: space(1) },
  cam: {
    flex: 1,
    aspectRatio: 4 / 3,
    borderRadius: radius.sm,
    overflow: 'hidden',
    alignItems: 'stretch',
    justifyContent: 'flex-end',
  },
  camName: { paddingHorizontal: 6, paddingVertical: 3, borderRadius: 6, margin: 5, alignSelf: 'flex-start' },
});
