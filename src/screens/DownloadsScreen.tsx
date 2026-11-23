import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Linking, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { Banner, Bar, Button, Card, Empty, Icon, Pill, Screen, Section, Tone } from '../components/ui';
import {
  ArrQueue,
  deleteTorrent,
  DownloadsAllResponse,
  getDownloadsAll,
  pauseTorrent,
  refreshJellyfinLibrary,
  resumeTorrent,
  retryArrImport,
  serverUrl,
  setAltSpeed,
  TorrentItem,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { bytes, duration, rate } from '../lib/format';
import { radius, space, useTheme } from '../theme';

const CACHE_KEY = 'downloads:all';

function stateTone(state: string): Tone {
  if (state === 'downloading') return 'accent';
  if (state === 'seeding') return 'ok';
  if (state === 'stalled' || state === 'metadata' || state === 'checking' || state === 'moving') return 'warn';
  if (state === 'error') return 'bad';
  return 'dim';
}

function TorrentSkeletonCard() {
  const t = useTheme();
  return (
    <Card style={{ gap: space(2.5) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <View style={[st.skel, { flex: 1, height: 15, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: 56, height: 22, borderRadius: radius.pill, backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skel, { height: 5, borderRadius: 3, backgroundColor: t.cardAlt }]} />
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <View style={[st.skel, { width: 110, height: 12, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: 70, height: 12, backgroundColor: t.cardAlt }]} />
      </View>
    </Card>
  );
}

function TorrentCard({
  item,
  busy,
  onTogglePause,
  onDelete,
}: {
  item: TorrentItem;
  busy: boolean;
  onTogglePause: (item: TorrentItem) => void;
  onDelete: (item: TorrentItem) => void;
}) {
  const t = useTheme();
  const pct = Math.round(item.progress * 100);
  const barColor =
    item.state === 'error'
      ? t.bad
      : item.state === 'stalled'
        ? t.warn
        : item.state === 'seeding'
          ? t.ok
          : item.paused
            ? t.textFaint
            : t.accent;

  return (
    <Card style={{ gap: space(2.5) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600', flex: 1 }} numberOfLines={2}>
          {item.name}
        </Text>
        <Pill label={item.state} tone={stateTone(item.state)} />
      </View>

      <Bar pct={item.progress * 100} color={barColor} height={5} />

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Text style={{ color: t.textDim, fontSize: 12, flex: 1, fontVariant: ['tabular-nums'] }} numberOfLines={1}>
          {pct}%
          {item.size ? ` · ${bytes(item.done, 1)} / ${bytes(item.size, 1)}` : ''}
          {item.eta ? ` · ${duration(item.eta)}` : ''}
        </Text>
        <Text style={{ color: t.textFaint, fontSize: 12, fontVariant: ['tabular-nums'] }} numberOfLines={1}>
          {item.speed > 0 ? `↓ ${rate(item.speed)}` : ''}
          {item.speed > 0 && item.upspeed > 0 ? ' · ' : ''}
          {item.upspeed > 0 ? `↑ ${rate(item.upspeed)}` : ''}
          {item.speed === 0 && item.upspeed === 0 && item.ratio > 0 ? `${item.ratio.toFixed(2)}x` : ''}
        </Text>
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        {item.category ? <Pill label={item.category} tone="faint" /> : null}
        <View style={{ flex: 1 }} />
        <Pressable
          onPress={() => onTogglePause(item)}
          disabled={busy}
          hitSlop={6}
          accessibilityLabel={item.paused ? `Resume ${item.name}` : `Pause ${item.name}`}
          style={({ pressed }) => [
            st.actionBtn,
            { backgroundColor: t.cardAlt, opacity: busy ? 0.5 : pressed ? 0.8 : 1 },
          ]}
        >
          {busy ? (
            <ActivityIndicator size="small" color={t.text} />
          ) : (
            <Icon name={item.paused ? 'play' : 'pause'} size={17} color={t.text} />
          )}
          <Text style={{ color: t.text, fontSize: 12.5, fontWeight: '500' }}>{item.paused ? 'Resume' : 'Pause'}</Text>
        </Pressable>
        <Pressable
          onPress={() => onDelete(item)}
          disabled={busy}
          hitSlop={6}
          accessibilityLabel={`Delete ${item.name}`}
          style={({ pressed }) => [
            st.actionBtn,
            { backgroundColor: t.badSoft, opacity: busy ? 0.5 : pressed ? 0.8 : 1 },
          ]}
        >
          <Icon name="delete-outline" size={17} color={t.bad} />
        </Pressable>
      </View>
    </Card>
  );
}

function StuckArrCard({
  app,
  label,
  port,
  queue,
  retrying,
  onRetry,
}: {
  app: 'radarr' | 'sonarr';
  label: string;
  port: number;
  queue: ArrQueue;
  retrying: boolean;
  onRetry: (app: 'radarr' | 'sonarr') => void;
}) {
  const t = useTheme();
  const items = queue.items ?? [];
  if (!queue.available || !items.length) return null;
  const openUrl = `${serverUrl()}:${port}/activity/queue`;

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Icon name="alert-outline" size={18} color={t.bad} />
        <Text style={{ color: t.text, fontSize: 15, fontWeight: '600', flex: 1 }}>{label}</Text>
        <Pill tone="bad" label={`${queue.needs_action ?? items.length} stuck`} />
      </View>

      <View style={{ gap: space(2) }}>
        {items.map((x, i) => (
          <View key={`${x.title}-${i}`} style={{ gap: 2 }}>
            <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '500' }} numberOfLines={1}>
              {x.title}
            </Text>
            <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={2}>
              {x.reason}
            </Text>
          </View>
        ))}
      </View>

      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button
          label="Retry import"
          icon="refresh"
          kind="subtle"
          compact
          busy={retrying}
          style={{ flex: 1 }}
          onPress={() => onRetry(app)}
        />
        <Button
          label={`Open ${label}`}
          icon="open-in-new"
          kind="subtle"
          compact
          style={{ flex: 1 }}
          onPress={() => Linking.openURL(openUrl)}
        />
      </View>
    </Card>
  );
}

export default function DownloadsScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const [data, setData] = useState<DownloadsAllResponse | null>(() => cacheGet<DownloadsAllResponse>(CACHE_KEY));
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [busyHash, setBusyHash] = useState<string | null>(null);
  const [altPending, setAltPending] = useState<boolean | null>(null);
  const [altBusy, setAltBusy] = useState(false);
  const [retryingApp, setRetryingApp] = useState<'radarr' | 'sonarr' | null>(null);
  const [jfBusy, setJfBusy] = useState(false);
  const [jfDone, setJfDone] = useState(false);

  const load = useCallback(async () => {
    try {
      const next = await getDownloadsAll();
      cacheSet(CACHE_KEY, next);
      setData(next);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      let off = false;
      let timer: ReturnType<typeof setTimeout> | null = null;
      const tick = async () => {
        if (AppState.currentState === 'active') await load();
        if (!off) timer = setTimeout(tick, 4000);
      };
      void tick();
      return () => {
        off = true;
        if (timer) clearTimeout(timer);
      };
    }, [load]),
  );

  const onToggleAltSpeed = async (enabled: boolean) => {
    if (altBusy) return;
    setAltPending(enabled);
    setAltBusy(true);
    try {
      const res = await setAltSpeed(enabled);
      setData((prev) => (prev ? { ...prev, alt_speed: res.alt_speed } : prev));
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setAltBusy(false);
      setAltPending(null);
    }
  };

  const onTogglePause = async (item: TorrentItem) => {
    if (busyHash) return;
    setBusyHash(item.hash);
    try {
      if (item.paused) await resumeTorrent(item.hash);
      else await pauseTorrent(item.hash);
      await load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusyHash(null);
    }
  };

  const onDelete = (item: TorrentItem) => {
    Alert.alert(`Delete ${item.name}?`, 'Delete downloaded files from disk too?', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Keep files',
        onPress: () => void runDelete(item.hash, false),
      },
      {
        text: 'Delete files',
        style: 'destructive',
        onPress: () => void runDelete(item.hash, true),
      },
    ]);
  };

  const runDelete = async (hash: string, deleteFiles: boolean) => {
    setBusyHash(hash);
    try {
      await deleteTorrent(hash, deleteFiles);
      await load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusyHash(null);
    }
  };

  const onRetryArr = async (app: 'radarr' | 'sonarr') => {
    setRetryingApp(app);
    try {
      await retryArrImport(app);
      await load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setRetryingApp(null);
    }
  };

  const onRefreshJellyfin = async () => {
    if (jfBusy) return;
    setJfBusy(true);
    setJfDone(false);
    try {
      await refreshJellyfinLibrary();
      setJfDone(true);
      setTimeout(() => setJfDone(false), 2500);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setJfBusy(false);
    }
  };

  const altSpeed = altPending ?? data?.alt_speed ?? false;
  const torrents = data?.torrents ?? [];
  const total = data?.total;
  const hasStuck = Boolean((data?.radarr?.needs_action ?? 0) > 0 || (data?.sonarr?.needs_action ?? 0) > 0);

  return (
    <Screen
      title="Downloads"
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err ? <Banner tone="bad" title="Downloads">{err}</Banner> : null}

      <Card style={{ gap: space(3) }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
          <Icon name="speedometer-slow" size={20} color={altSpeed ? t.warn : t.textDim} />
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '500', flex: 1 }}>Alt speed limit</Text>
          <Switch
            value={altSpeed}
            disabled={!data || altBusy}
            onValueChange={(v) => void onToggleAltSpeed(v)}
            accessibilityLabel="Alt speed limit"
            trackColor={{ true: t.warn, false: t.cardAlt }}
            thumbColor={altSpeed ? t.onMedia : t.textFaint}
          />
        </View>

        <View style={{ flexDirection: 'row', gap: space(2) }}>
          <Button
            label={jfDone ? 'Jellyfin refreshed' : 'Refresh Jellyfin library'}
            icon={jfDone ? 'check' : 'refresh'}
            kind="subtle"
            compact
            busy={jfBusy}
            style={{ flex: 1 }}
            onPress={() => void onRefreshJellyfin()}
          />
        </View>
      </Card>

      {hasStuck && data ? (
        <Section label="Needs attention">
          <StuckArrCard
            app="radarr"
            label="Radarr"
            port={7878}
            queue={data.radarr}
            retrying={retryingApp === 'radarr'}
            onRetry={onRetryArr}
          />
          <StuckArrCard
            app="sonarr"
            label="Sonarr"
            port={8989}
            queue={data.sonarr}
            retrying={retryingApp === 'sonarr'}
            onRetry={onRetryArr}
          />
        </Section>
      ) : null}

      <Section
        label={total ? `Torrents (${total.count})` : 'Torrents'}
        right={
          total ? (
            <Text style={{ color: t.textFaint, fontSize: 12, fontVariant: ['tabular-nums'] }}>
              ↓ {rate(total.speed)} · ↑ {rate(total.upspeed)}
            </Text>
          ) : undefined
        }
      >
        {!data ? (
          <View style={{ gap: space(2.5) }}>
            <TorrentSkeletonCard />
            <TorrentSkeletonCard />
            <TorrentSkeletonCard />
          </View>
        ) : data.qbt !== 'ok' ? (
          <Banner tone="warn" title={`qBittorrent ${data.qbt === 'auth' ? 'sign-in failed' : 'unavailable'}`} />
        ) : torrents.length ? (
          <View style={{ gap: space(2.5) }}>
            {torrents.map((item) => (
              <TorrentCard
                key={item.hash}
                item={item}
                busy={busyHash === item.hash}
                onTogglePause={onTogglePause}
                onDelete={onDelete}
              />
            ))}
          </View>
        ) : (
          <Card>
            <Empty icon="download-outline" title="No torrents" />
          </Card>
        )}
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  skel: { borderRadius: 4 },
  actionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    height: 32,
    paddingHorizontal: 10,
    borderRadius: 10,
  },
});
