import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { type ComponentType, useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, AppState, Image, InteractionManager, Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import { KeyringUnlockModal } from '../components/KeyringUnlockModal';
import {
  Banner,
  Button,
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
import { cacheGet, cacheSet } from '../lib/cache';
import { refreshCatCam, useCatCam } from '../lib/catcam';
import { clearKeyringFeedback, refreshKeyring, unlockKeyringWithSaved, useKeyring } from '../lib/keyring';
import { CAMS, CamId, NAMES } from '../lib/cams/config';
import { prepareCamsOnce } from '../lib/cams/prepare';
import { bytes, rate } from '../lib/format';
import { DownloadsReply } from '../lib/live';
import { space, useTheme } from '../theme';

/** Routes the app plays natively instead of opening the browser (the screen keeps a link to the web page). */
const NATIVE: Record<string, string> = { '/cams/': 'Cams', '/agentview': 'AgentView' };

/** Phone-side names for routes that open a native screen. */
const TILE: Record<string, Partial<Pick<Route, 'name' | 'icon'>>> = {
  '/agentview': { name: 'Agent view', icon: 'robot' },
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
    screen: r.port === 443 ? NATIVE[r.path] : undefined,
  };
}

function shortHost(host: string) {
  return host.replace(/^ayushlive-/i, '').toLowerCase();
}

function upShort(secs: number) {
  const d = Math.floor(secs / 86400);
  if (d) return `${d} d`;
  const h = Math.floor((secs % 86400) / 3600);
  if (h) return `${h} h`;
  return `${Math.floor((secs % 3600) / 60)} min`;
}

function seenAgo(ts: number) {
  const s = Date.now() / 1000 - ts;
  if (s < 60) return 'just now';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.floor(h / 24);
  return d === 1 ? 'yesterday' : `${d} days ago`;
}

function CameraTile({
  name,
  uri,
  failed,
  style,
}: {
  name: string;
  uri: string | null;
  failed: boolean;
  style?: object;
}) {
  const t = useTheme();
  return (
    <View style={[st.cam, style]}>
      {uri ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" fadeDuration={0} />
      ) : failed ? (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="video-off" size={20} color={t.onMediaDim} />
        </View>
      ) : (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator size="small" color={t.onMediaDim} />
        </View>
      )}
      <View style={[st.camName, { backgroundColor: t.mediaChip }]}>
        <Text style={{ color: t.onMedia, fontSize: 11 }} numberOfLines={1}>
          {name}
        </Text>
      </View>
    </View>
  );
}

function CamTile({
  cam,
  live,
  active,
  frames,
  failedCams,
  style,
}: {
  cam: (typeof CAMS)[number];
  live: LiveTileModule | null;
  active: boolean;
  frames: Partial<Record<CamId, string>>;
  failedCams: Partial<Record<CamId, boolean>>;
  style?: object;
}) {
  const poster = frames[cam.id] ?? null;
  const failed = !!failedCams[cam.id];
  if (!live) return <CameraTile name={cam.name} uri={poster} failed={failed} style={style} />;
  const Live = live.LiveTile;
  return <Live stream={cam.id} label={cam.name} poster={poster} posterFailed={failed} active={active} style={style} />;
}

type LiveTileProps = {
  stream: string;
  label: string;
  poster: string | null;
  posterFailed: boolean;
  active: boolean;
  style?: object;
};
type LiveTileModule = {
  LiveTile: ComponentType<LiveTileProps>;
  stopHomeLive: () => void;
};

function useLazyLiveTile(enabled: boolean) {
  const [mod, setMod] = useState<LiveTileModule | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const task = InteractionManager.runAfterInteractions(() => {
      if (cancelled) return;
      prepareCamsOnce();
      try {
        // eslint-disable-next-line @typescript-eslint/no-require-imports
        const m = require('../components/LiveTile') as LiveTileModule;
        if (!cancelled) setMod(m);
      } catch {
        /* keep the snapshots */
      }
    });
    return () => {
      cancelled = true;
      task.cancel();
    };
  }, [enabled]);
  return enabled ? mod : null;
}

function StatusLine({ tone, label }: { tone: 'ok' | 'warn' | 'bad' | 'faint'; label: string }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 2 }}>
      <StatusDot tone={tone} />
      <Text style={{ color: t.textDim, fontSize: 12.5, flexShrink: 1 }} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8}>
        {label}
      </Text>
    </View>
  );
}

export default function RoutesScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const route = useRoute<any>();
  const catCam = useCatCam();
  const keyring = useKeyring();
  const [unlockModal, setUnlockModal] = useState(false);
  const [routes, setRoutes] = useState<Route[] | null>(() => cacheGet<Route[]>('home:routes'));
  const [vitals, setVitals] = useState<Vitals | null>(() => cacheGet<Vitals>('home:vitals'));
  const [info, setInfo] = useState<Info | null>(() => cacheGet<Info>('home:info'));
  const [health, setHealth] = useState<Health | null>(() => cacheGet<Health>('health:report'));
  const [det, setDet] = useState<DetState | null>(() => cacheGet<DetState>('home:det'));
  const [downloads, setDownloads] = useState<DownloadsReply | null>(() => cacheGet<DownloadsReply>('home:downloads'));
  const [tick, setTick] = useState(0);
  const [frames, setFrames] = useState<Partial<Record<CamId, string>>>({});
  const [failedCams, setFailedCams] = useState<Partial<Record<CamId, boolean>>>({});
  const framesRef = useRef<Partial<Record<CamId, string>>>({});
  const [loaded, setLoaded] = useState(false);
  const [offlineErr, setOfflineErr] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [focused, setFocused] = useState(false);
  const [appActive, setAppActive] = useState(AppState.currentState === 'active');
  const live = useLazyLiveTile(catCam.enabled);

  const load = useCallback(async () => {
    const [i, v, h, r, d, dl] = await Promise.all([
      safe(get<Info>('/pc/api/info')),
      safe(get<Vitals>('/vitals/api')),
      safe(get<Health>('/pc/api/health')),
      safe(get<{ routes: Route[] }>('/pc/api/routes')),
      catCam.enabled ? safe(get<DetState>('/detect/state')) : Promise.resolve(null),
      safe(get<DownloadsReply>('/pc/api/downloads')),
      refreshCatCam(),
      refreshKeyring(),
    ]);
    if (i) {
      cacheSet('home:info', i);
      setInfo(i);
    }
    if (v) {
      cacheSet('home:vitals', v);
      setVitals(v);
    }
    if (h) {
      cacheSet('health:report', h);
      setHealth(h);
    }
    if (r) {
      const visible = r.routes.filter((x) => !x.hidden);
      cacheSet('home:routes', visible);
      setRoutes(visible);
    }
    if (d) {
      cacheSet('home:det', d);
      setDet(d);
    }
    if (dl) {
      cacheSet('home:downloads', dl);
      setDownloads(dl);
    }
    setOfflineErr(!i && !v && !r);
    setLoaded(true);
  }, [catCam.enabled]);

  useEffect(() => {
    framesRef.current = frames;
  }, [frames]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (st) => setAppActive(st === 'active'));
    return () => sub.remove();
  }, []);

  useFocusEffect(
    useCallback(() => {
      setFocused(true);
      load();
      setTick((n) => n + 1);
      const id = setInterval(() => setTick((n) => n + 1), 3000);
      return () => {
        setFocused(false);
        clearInterval(id);
      };
    }, [load]),
  );

  useEffect(() => {
    if (!catCam.enabled) return;
    let cancelled = false;
    for (const c of CAMS) {
      const next = `${serverUrl()}/api/frame.jpeg?src=${c.id}&t=${tick}`;
      Image.prefetch(next)
        .then((ok) => {
          if (cancelled) return;
          if (ok) {
            setFrames((f) => ({ ...f, [c.id]: next }));
            setFailedCams((m) => {
              if (!m[c.id]) return m;
              const copy = { ...m };
              delete copy[c.id];
              return copy;
            });
          } else if (!framesRef.current[c.id]) {
            setFailedCams((m) => ({ ...m, [c.id]: true }));
          }
        })
        .catch(() => {
          if (!cancelled && !framesRef.current[c.id]) setFailedCams((m) => ({ ...m, [c.id]: true }));
        });
    }
    return () => {
      cancelled = true;
    };
  }, [tick, catCam.enabled]);

  const openCams = useCallback(
    (params?: { tab?: 'replay' | 'music'; tabRequest?: number }) => {
      live?.stopHomeLive();
      nav.navigate('Cams', params);
    },
    [live, nav],
  );
  const triggerKeyringUnlock = useCallback(async () => {
    if (keyring.hasSaved) {
      const res = await unlockKeyringWithSaved();
      if (!res.ok && res.invalidated) setUnlockModal(true);
    } else {
      setUnlockModal(true);
    }
  }, [keyring.hasSaved]);

  useEffect(() => {
    if (!route.params?.unlockKeyring) return;
    nav.setParams({ unlockKeyring: undefined });
    const task = InteractionManager.runAfterInteractions(() => {
      void triggerKeyringUnlock();
    });
    return () => task.cancel();
  }, [route.params?.unlockKeyring, nav, triggerKeyringUnlock]);
  const liveActive = focused && appActive && catCam.enabled;
  const latest = lastSighting(det);
  const problems = health?.problems?.length ?? 0;
  const offline = loaded && offlineErr;
  const statusTone = offline ? 'bad' : problems ? 'warn' : info ? 'ok' : 'faint';
  const statusLabel = offline
    ? "Can't reach the laptop"
    : info
      ? `${shortHost(info.host)}${vitals ? ` · up ${upShort(vitals.uptime)}` : ''}`
      : 'Connecting to the laptop…';
  const temp = vitals
    ? Object.values(vitals.temps ?? {}).reduce<number | null>((m, v) => (v == null ? m : m == null ? v : Math.max(m, v)), null)
    : null;
  const cpuTemp = vitals?.temps?.cpu ?? temp;
  const active = downloads?.downloads ?? [];
  const total = downloads?.total;
  const openScreen = (routes ?? []).filter((r) => r.path !== '/cams/' && r.name !== 'Cat cams');
  const loadedCams = CAMS.filter((c) => !!frames[c.id]).length;
  const hasDownloads = active.length > 0;
  const progress = total?.progress == null ? null : Math.round(total.progress * 100);
  const hddDisk = health?.disks?.disks?.find((d) => d.kind === 'HDD');
  const hddFree = hddDisk ? Math.max(0, hddDisk.total - hddDisk.used) : null;

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

      {keyring.status?.locked ? (
        <Banner tone="warn" icon="lock" title="Keyring locked: RDP unavailable">
          <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>
            Unlock the laptop’s login keyring to restart remote desktop ({keyring.status.rdp}).
          </Text>
          {keyring.error ? (
            <Text style={{ color: t.bad, fontSize: 13, marginTop: 4 }}>{keyring.error}</Text>
          ) : null}
          <Button
            label={keyring.hasSaved ? 'Unlock (fingerprint)' : 'Unlock'}
            icon={keyring.hasSaved ? 'fingerprint' : 'lock-open'}
            compact
            busy={keyring.busy}
            style={{ alignSelf: 'flex-start', marginTop: 8 }}
            onPress={() => void triggerKeyringUnlock()}
          />
        </Banner>
      ) : keyring.notice ? (
        <Banner tone="ok" icon="lock-open" title={keyring.notice}>
          <Button label="Dismiss" compact kind="subtle" style={{ alignSelf: 'flex-start', marginTop: 4 }} onPress={clearKeyringFeedback} />
        </Banner>
      ) : null}

      <KeyringUnlockModal visible={unlockModal} onClose={() => setUnlockModal(false)} />

      {vitals ? (
        <StatStrip
          onPress={() => nav.navigate('System', { tab: 'vitals', tabRequest: Date.now() })}
          items={[
            { label: 'CPU', value: Math.round(vitals.cpu), unit: '%' },
            {
              label: 'Memory',
              value: (vitals.mem.used / 1024 ** 3).toFixed(1),
              unit: `/${Math.round(vitals.mem.total / 1024 ** 3)}G`,
            },
            { label: 'Temp', value: cpuTemp == null ? '–' : Math.round(cpuTemp), unit: cpuTemp == null ? undefined : '°C' },
          ]}
        />
      ) : !loaded ? (
        <Card>
          <Text style={{ color: t.textDim }}>Loading…</Text>
        </Card>
      ) : null}

      {catCam.enabled ? (
        <Section
          label="Cameras"
          right={
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <Pressable onPress={() => openCams({ tab: 'replay', tabRequest: Date.now() })} hitSlop={8}>
                <Text style={{ color: t.textFaint, fontSize: 12.5 }}>Replay</Text>
              </Pressable>
              <Text style={{ color: t.textFaint, fontSize: 12.5 }}>·</Text>
              <Pressable onPress={() => openCams({ tab: 'music', tabRequest: Date.now() })} hitSlop={8}>
                <Text style={{ color: t.textFaint, fontSize: 12.5 }}>Music</Text>
              </Pressable>
            </View>
          }
        >
          <Card onPress={() => openCams()} style={{ padding: 0 }}>
            <View style={st.camWall}>
              <View style={{ flexDirection: 'row', gap: 2, flex: 1 }}>
                <CamTile cam={CAMS[0]} live={live} active={liveActive} frames={frames} failedCams={failedCams} style={{ flex: 2 }} />
                <View style={{ flex: 1, gap: 2 }}>
                  <CamTile cam={CAMS[1]} live={live} active={liveActive} frames={frames} failedCams={failedCams} style={{ flex: 1 }} />
                  <CamTile cam={CAMS[2]} live={live} active={liveActive} frames={frames} failedCams={failedCams} style={{ flex: 1 }} />
                </View>
              </View>
            </View>
            <View style={st.camFoot}>
              <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '600' }}>{loadedCams} cams</Text>
              <Text style={{ color: t.textDim, fontSize: 12.5 }} numberOfLines={1}>
                {latest ? `Cat seen ${seenAgo(latest.ts)} · ${latest.name}` : det ? 'No cats yet' : 'Detector offline'}
              </Text>
            </View>
          </Card>
        </Section>
      ) : null}

      <Section label="Open">
        <List>
          {openScreen.map((r) => {
            const view = routeView(r);
            return (
              <ListRow
                key={r.id}
                icon={view.icon}
                title={view.name}
                chevron
                onPress={() => (view.screen ? nav.navigate(view.screen) : open(r))}
              />
            );
          })}
          <ListRow
            icon="movie-search"
            title="Seerr"
            chevron
            onPress={() => nav.navigate('Seerr')}
          />
          <ListRow
            icon="download"
            title="Downloads"
            subtitle={hasDownloads ? `${active.length} active · ${rate(total?.speed ?? 0)}` : undefined}
            trailing={
              hasDownloads && progress != null ? (
                <Text style={{ color: t.warn, fontSize: 12, fontWeight: '500', fontVariant: ['tabular-nums'] }}>
                  {progress}%
                </Text>
              ) : undefined
            }
            chevron
            onPress={() => nav.navigate('Downloads')}
          />
          <ListRow
            icon="harddisk"
            title="Space"
            trailing={
              hddFree != null ? (
                <Text
                  style={{
                    color: hddFree < 150 * 1024 ** 3 ? t.warn : t.textDim,
                    fontSize: 12.5,
                    fontVariant: ['tabular-nums'],
                  }}
                >
                  {bytes(hddFree, 0)} free
                </Text>
              ) : undefined
            }
            chevron
            last
            onPress={() => nav.navigate('Space')}
          />
        </List>
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  camWall: { height: 176, flexDirection: 'row' },
  cam: { overflow: 'hidden', alignItems: 'stretch', justifyContent: 'flex-end' },
  camName: { paddingHorizontal: 6, paddingVertical: 3, borderRadius: 6, margin: 6, alignSelf: 'flex-start' },
  camFoot: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(3), padding: 14 },
});
