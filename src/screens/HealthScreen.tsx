import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { AppState, StyleSheet, Text, View } from 'react-native';
import {
  Banner,
  Bar,
  Card,
  Icon,
  List,
  ListRow,
  Pill,
  Screen,
  Section,
  Segmented,
  StatusDot,
  StatusRing,
  Tone,
} from '../components/ui';
import { ArrQueue, get, Health, HealthDownload, HealthProblem, Service } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { useCatCam } from '../lib/catcam';
import { bytes, clock, duration, rate } from '../lib/format';
import { space, useTheme } from '../theme';

type ServiceTone = 'ok' | 'bad' | 'warn' | 'dim';
type Filter = 'all' | 'media' | 'cams' | 'agents';

function tone(s: Service): ServiceTone {
  if (s.active === 'active') return 'ok';
  if (s.active === 'failed') return 'bad';
  if (s.active === 'activating' || s.active === 'deactivating' || s.active === 'reloading') return 'warn';
  return 'dim';
}

const ORDER: Record<ServiceTone, number> = { bad: 0, warn: 1, ok: 2, dim: 3 };

function statusLabel(s: Service) {
  if (s.timer && s.active !== 'active') return 'timer';
  return s.active || 'unknown';
}

function group(s: Service): Filter {
  const u = s.unit.replace(/\.service$/, '');
  if (/^(cam-|camrec|catcam|catdetect|cat-)/.test(u)) return 'cams';
  if (/^(jellyfin|qbittorrent|radarr|sonarr|seerr|bazarr|prowlarr|flaresolverr|import-watch|media)/.test(u)) return 'media';
  if (/(agent|qwen|ollama)/.test(u)) return 'agents';
  return 'all';
}

const FRIENDLY: Record<string, string> = {
  jellyfin: 'Jellyfin',
  qbittorrent: 'qBittorrent',
  radarr: 'Radarr',
  sonarr: 'Sonarr',
  seerr: 'Seerr',
  bazarr: 'Bazarr',
  prowlarr: 'Prowlarr',
  flaresolverr: 'FlareSolverr',
  catcam: 'Cat cameras',
  catdetect: 'Cat detection',
  camrec: 'Recording',
  'cam-music': 'Cam music',
  'cam-ptz': 'Cam PTZ',
};

function pretty(unit: string) {
  const u = unit.replace(/\.service$/, '');
  return FRIENDLY[u] ?? u.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
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

function Unavailable({ label, error }: { label: string; error?: string }) {
  const t = useTheme();
  return (
    <Card style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
      <Icon name="cloud-off-outline" size={20} color={t.textFaint} />
      <View style={{ flex: 1 }}>
        <Text style={{ color: t.text, fontWeight: '500', fontSize: 14 }}>{label} unavailable</Text>
        <Text style={{ color: t.textFaint, fontSize: 12.5 }}>{error || 'No answer from the laptop'}</Text>
      </View>
    </Card>
  );
}

function ProblemList({ problems }: { problems: HealthProblem[] }) {
  const t = useTheme();
  return (
    <View style={{ gap: space(2.5) }}>
      {problems.map((p, i) => (
        <View key={`${p.kind}-${i}`} style={{ gap: 2 }}>
          <Text style={{ color: p.severity === 'bad' ? t.bad : t.warn, fontSize: 13.5, fontWeight: '500' }}>{p.title}</Text>
          {p.detail ? (
            <Text style={{ color: t.textDim, fontSize: 12.5, lineHeight: 17 }} numberOfLines={2}>
              {p.detail}
            </Text>
          ) : null}
        </View>
      ))}
    </View>
  );
}

function DownloadRow({ d }: { d: HealthDownload }) {
  const t = useTheme();
  const stalled = d.state === 'stalled';
  return (
    <View style={{ gap: 5 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '500', flex: 1 }} numberOfLines={1}>
          {d.name}
        </Text>
        <Text
          style={{
            color: stalled ? t.warn : t.textDim,
            fontSize: 12.5,
            fontWeight: '500',
            fontVariant: ['tabular-nums'],
          }}
        >
          {Math.round(d.progress * 100)}%
        </Text>
      </View>
      <Bar pct={d.progress * 100} color={stalled ? t.warn : t.text} height={5} />
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Text style={{ color: stalled ? t.warn : t.textFaint, fontSize: 11.5, flex: 1 }} numberOfLines={1}>
          {stalled ? 'stalled' : d.state}
          {d.state === 'downloading' && d.eta ? ` · ${duration(d.eta)} left` : ''}
        </Text>
        <Text style={{ color: t.textFaint, fontSize: 11.5, fontVariant: ['tabular-nums'] }}>
          {rate(d.speed)}
          {d.upspeed ? ` · ↑ ${rate(d.upspeed)}` : ''}
        </Text>
      </View>
    </View>
  );
}

function ArrRow({ label, q, last }: { label: string; q: ArrQueue; last?: boolean }) {
  const t = useTheme();
  const need = q.needs_action ?? 0;
  return (
    <View style={{ gap: space(2) }}>
      <View style={[st.arrRow, !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border }]}>
        <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '500', flex: 1 }}>{label}</Text>
        {q.available ? (
          <>
            <Text style={{ color: t.textDim, fontSize: 12.5 }}>{q.queue ?? 0} queued</Text>
            <Pill
              tone={need ? 'bad' : q.queue ? 'ok' : 'dim'}
              label={need ? `${need} action` : q.queue ? 'waiting' : 'clear'}
            />
          </>
        ) : (
          <Pill tone="warn" label="unavailable" />
        )}
      </View>
      {need ? (
        <View style={{ gap: 3, paddingBottom: space(1) }}>
          {(q.items ?? []).slice(0, 3).map((x, i) => (
            <View key={`${x.title}-${i}`} style={{ flexDirection: 'row', gap: space(2) }}>
              <Icon name="alert-outline" size={14} color={t.bad} />
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.text, fontSize: 12.5 }} numberOfLines={1}>
                  {x.title}
                </Text>
                <Text style={{ color: t.textFaint, fontSize: 11.5 }} numberOfLines={1}>
                  {x.reason}
                </Text>
              </View>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

export default function HealthScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const catCam = useCatCam();
  const [h, setH] = useState<Health | null>(() => cacheGet<Health>('health:report'));
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<Filter>('all');

  const load = useCallback(async () => {
    try {
      const next = await get<Health>('/pc/api/health', 8000);
      cacheSet('health:report', next);
      setH(next);
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
        if (!off) timer = setTimeout(tick, 5000);
      };
      tick();
      return () => {
        off = true;
        if (timer) clearTimeout(timer);
      };
    }, [load]),
  );

  const effectiveFilter: Filter = !catCam.enabled && filter === 'cams' ? 'all' : filter;
  const units = (h?.services.units ?? []).slice().sort((a, b) => ORDER[tone(a)] - ORDER[tone(b)] || a.unit.localeCompare(b.unit));
  const shownUnits = effectiveFilter === 'all' ? units : units.filter((s) => group(s) === effectiveFilter);
  const counts = h?.services.counts;
  const dl = h?.downloads;
  const total = dl?.total;
  const streams = h?.jellyfin.streams ?? [];
  const disks = h?.disks.disks ?? [];
  const problems = h?.problems ?? [];
  const bad = problems.some((p) => p.severity === 'bad');
  const summaryTone: Tone = problems.length ? (bad ? 'bad' : 'warn') : 'ok';

  return (
    <Screen
      title="Services"
      subtitle={
        h ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <StatusDot tone={problems.length ? (bad ? 'bad' : 'warn') : 'ok'} />
            <Text style={{ color: t.textDim, fontSize: 12.5 }}>Checked {clock(h.ts)}</Text>
          </View>
        ) : undefined
      }
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err && !h ? <Banner tone="bad" title="Can't reach the laptop">{err}</Banner> : null}

      {h ? (
        <Card style={{ gap: space(3) }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3.5) }}>
            <StatusRing tone={summaryTone} icon={problems.length ? 'alert-outline' : 'check'} />
            <Text style={{ color: t.text, fontSize: 16, fontWeight: '600', flex: 1 }}>
              {problems.length
                ? `${problems.length} problem${problems.length === 1 ? '' : 's'}`
                : `All ${counts?.running ?? units.filter((s) => s.active === 'active').length} running`}
            </Text>
          </View>
          {problems.length ? <ProblemList problems={problems} /> : null}
        </Card>
      ) : null}

      <Section label="Downloads">
        {!dl?.available ? (
          <Unavailable label="Downloads" error={dl?.error} />
        ) : (
          <Card style={{ gap: space(3) }}>
            {dl.qbt && dl.qbt !== 'ok' ? (
              <Text style={{ color: t.warn, fontSize: 13 }}>
                qBittorrent {dl.qbt === 'auth' ? 'sign-in failed' : 'is not answering'}
              </Text>
            ) : null}
            <View style={{ flexDirection: 'row' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textFaint, fontSize: 11.5 }}>Active</Text>
                <Text style={{ color: t.text, fontSize: 20, fontWeight: '500', fontVariant: ['tabular-nums'] }}>
                  {total?.count ?? 0}
                </Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textFaint, fontSize: 11.5 }}>Down</Text>
                <Text
                  style={{
                    color: t.text,
                    fontSize: 16,
                    fontWeight: '500',
                    fontVariant: ['tabular-nums'],
                  }}
                >
                  {rate(total?.speed ?? 0)}
                </Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textFaint, fontSize: 11.5 }}>Up</Text>
                <Text
                  style={{
                    color: t.textDim,
                    fontSize: 16,
                    fontWeight: '500',
                    fontVariant: ['tabular-nums'],
                  }}
                >
                  {rate(total?.upspeed ?? 0)}
                </Text>
              </View>
            </View>
            {(dl.items ?? []).length ? (
              (dl.items ?? []).slice(0, 4).map((d) => <DownloadRow key={d.id} d={d} />)
            ) : (
              <Text style={{ color: t.textFaint, fontSize: 13 }}>Nothing downloading.</Text>
            )}
            <View style={{ gap: space(1), paddingTop: space(1) }}>
              <ArrRow label="Radarr" q={h!.radarr} />
              <ArrRow label="Sonarr" q={h!.sonarr} last />
            </View>
          </Card>
        )}
      </Section>

      <Section label="Jellyfin">
        {!h?.jellyfin.available ? (
          <Unavailable label="Jellyfin" error={h?.jellyfin.error} />
        ) : streams.length ? (
          <Card style={{ gap: space(3) }}>
            {streams.map((s, i) => (
              <View
                key={`${s.user}-${s.title}-${i}`}
                style={[st.stream, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border }]}
              >
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2), flexWrap: 'wrap' }}>
                  <Icon name="account-outline" size={16} color={t.textDim} />
                  <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '500' }}>{s.user}</Text>
                  <Pill
                    tone={s.method === 'transcode' ? 'warn' : 'ok'}
                    label={s.method === 'transcode' ? 'Transcode' : s.method === 'direct_stream' ? 'Direct stream' : 'Direct play'}
                  />
                  {s.paused ? <Pill tone="dim" label="Paused" /> : null}
                </View>
                <Text style={{ color: t.text, fontSize: 13.5 }} numberOfLines={1}>
                  {s.title}
                </Text>
                {s.subtitle ? (
                  <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
                    {s.subtitle}
                  </Text>
                ) : null}
                {s.progress != null ? <Bar pct={s.progress * 100} color={t.textFaint} height={4} /> : null}
              </View>
            ))}
          </Card>
        ) : (
          <Card>
            <Text style={{ color: t.textFaint, fontSize: 13 }}>No one watching.</Text>
          </Card>
        )}
      </Section>

      <Section label="Disks">
        {!h?.disks.available ? (
          <Unavailable label="Disks" error={h?.disks.error} />
        ) : (
          <Card style={{ gap: space(4) }}>
            {disks.map((d) => {
              const free = Math.max(0, d.total - d.used);
              const freePct = d.total ? (free / d.total) * 100 : 0;
              const color = freePct < 10 ? t.bad : freePct < 20 ? t.warn : t.text;
              return (
                <View key={d.mount} style={{ gap: 6 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space(2) }}>
                    <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '500', flex: 1 }} numberOfLines={1}>
                      {d.kind}
                      <Text style={{ color: t.textFaint, fontWeight: '400' }}> · {d.mount}</Text>
                    </Text>
                    <Text style={{ color, fontSize: 12.5, fontWeight: '500', fontVariant: ['tabular-nums'] }}>
                      {Math.round(freePct)}% free
                    </Text>
                  </View>
                  <Bar pct={d.pct} color={color} />
                  <View style={{ flexDirection: 'row' }}>
                    <Text style={{ color: t.textFaint, fontSize: 11.5, flex: 1, fontVariant: ['tabular-nums'] }}>
                      {bytes(d.used, 0)} / {bytes(d.total, 0)}
                    </Text>
                    <Text style={{ color: t.textFaint, fontSize: 11.5, fontVariant: ['tabular-nums'] }}>{bytes(free, 0)} free</Text>
                  </View>
                </View>
              );
            })}
          </Card>
        )}
      </Section>

      {h?.services.available && units.length ? (
        <Segmented
          value={effectiveFilter}
          onChange={setFilter}
          options={[
            { id: 'all', label: 'All' },
            { id: 'media', label: 'Media' },
            ...(catCam.enabled ? [{ id: 'cams' as const, label: 'Cams' }] : []),
            { id: 'agents', label: 'Agents' },
          ]}
        />
      ) : null}

      {h ? (
        !h.services.available ? (
          <Unavailable label="Services" error={h.services.error} />
        ) : (
          <List>
            {shownUnits.length ? (
              shownUnits.map((s, i) => {
                const tn = tone(s);
                return (
                  <ListRow
                    key={s.unit}
                    leading={<StatusDot tone={tn === 'dim' ? 'faint' : tn} />}
                    title={pretty(s.unit)}
                    trailing={s.active === 'active' ? since(s.since) : statusLabel(s)}
                    chevron
                    last={i === shownUnits.length - 1}
                    onPress={() => nav.navigate('ServiceDetail', { service: s })}
                  />
                );
              })
            ) : (
              <View style={{ padding: space(4) }}>
                <Text style={{ color: t.textFaint, fontSize: 13 }}>No services in this group.</Text>
              </View>
            )}
          </List>
        )
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  arrRow: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingVertical: space(1) },
  stream: { gap: 5, paddingTop: space(1) },
});
