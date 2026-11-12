import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { AppState, Pressable, StyleSheet, Text, View } from 'react-native';
import { Banner, Bar, Card, Icon, Pill, Screen, Section } from '../components/ui';
import { ArrQueue, get, Health, HealthDownload, HealthProblem, Service } from '../lib/api';
import { bytes, clock, duration, rate } from '../lib/format';
import { space, useTheme } from '../theme';

type Tone = 'ok' | 'bad' | 'warn' | 'dim';

function tone(s: Service): Tone {
  if (s.active === 'active') return 'ok';
  if (s.active === 'failed') return 'bad';
  if (s.active === 'activating' || s.active === 'deactivating' || s.active === 'reloading') return 'warn';
  return 'dim';
}

const ORDER: Record<Tone, number> = { bad: 0, warn: 1, ok: 2, dim: 3 };

function statusLabel(s: Service) {
  if (s.timer && s.active !== 'active') return 'timer';
  return s.active || 'unknown';
}

function Unavailable({ label, error }: { label: string; error?: string }) {
  const t = useTheme();
  return (
    <Card style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
      <Icon name="cloud-off-outline" size={20} color={t.textFaint} />
      <View style={{ flex: 1 }}>
        <Text style={{ color: t.text, fontWeight: '600', fontSize: 14 }}>{label} unavailable</Text>
        <Text style={{ color: t.textFaint, fontSize: 12.5 }}>{error || 'No answer from the laptop'}</Text>
      </View>
    </Card>
  );
}

function ProblemList({ problems }: { problems: HealthProblem[] }) {
  const t = useTheme();
  return (
    <View style={{ gap: space(2) }}>
      {problems.map((p, i) => (
        <View key={`${p.kind}-${i}`} style={{ gap: 1 }}>
          <Text style={{ color: p.severity === 'bad' ? t.bad : t.warn, fontSize: 13.5, fontWeight: '700' }}>
            {p.title}
          </Text>
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
        <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '600', flex: 1 }} numberOfLines={1}>
          {d.name}
        </Text>
        <Text style={{ color: stalled ? t.warn : t.textDim, fontSize: 12.5, fontWeight: '700' }}>
          {Math.round(d.progress * 100)}%
        </Text>
      </View>
      <Bar pct={d.progress * 100} color={stalled ? t.warn : t.ok} height={5} />
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Text style={{ color: stalled ? t.warn : t.textFaint, fontSize: 11.5, flex: 1 }} numberOfLines={1}>
          {stalled ? 'stalled' : d.state}
          {d.state === 'downloading' && d.eta ? ` · ${duration(d.eta)} left` : ''}
        </Text>
        <Text style={{ color: t.textFaint, fontSize: 11.5 }}>
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
        <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '700', flex: 1 }}>{label}</Text>
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
  const [h, setH] = useState<Health | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      setH(await get<Health>('/pc/api/health', 8000));
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

  const units = (h?.services.units ?? []).slice().sort((a, b) => ORDER[tone(a)] - ORDER[tone(b)] || a.unit.localeCompare(b.unit));
  const counts = h?.services.counts;
  const dl = h?.downloads;
  const total = dl?.total;
  const streams = h?.jellyfin.streams ?? [];
  const disks = h?.disks.disks ?? [];
  const bad = !!h?.problems.some((p) => p.severity === 'bad');

  return (
    <Screen
      title="Health"
      subtitle={h ? `Updated ${clock(h.ts)}` : 'Home server at a glance'}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err && !h ? <Banner tone="bad" title="Can't reach the laptop">{err}</Banner> : null}

      {h ? (
        h.problems.length ? (
          <Banner tone={bad ? 'bad' : 'warn'} title={`${h.problems.length} problem${h.problems.length === 1 ? '' : 's'}`}>
            <ProblemList problems={h.problems} />
          </Banner>
        ) : (
          <Banner tone="ok" icon="check-circle-outline" title="All good" />
        )
      ) : null}

      <Section
        label="Services"
        right={
          counts ? (
            <Text style={{ color: t.textFaint, fontSize: 11.5 }}>
              {counts.running} running{counts.failed ? ` · ${counts.failed} failed` : ''}
            </Text>
          ) : undefined
        }
      >
        {!h?.services.available ? (
          <Unavailable label="Services" error={h?.services.error} />
        ) : (
          <Card style={{ padding: 0 }}>
            {units.map((s, i) => {
              const tn = tone(s);
              const color = tn === 'ok' ? t.ok : tn === 'bad' ? t.bad : tn === 'warn' ? t.warn : t.textFaint;
              return (
                <Pressable
                  key={s.unit}
                  onPress={() => nav.navigate('ServiceDetail', { service: s })}
                  android_ripple={{ color: t.cardAlt }}
                  style={({ pressed }) => [
                    st.serviceRow,
                    i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border },
                    pressed && { opacity: 0.75 },
                  ]}
                >
                  <View style={[st.dot, { backgroundColor: color }]} />
                  <View style={{ flex: 1, gap: 1 }}>
                    <Text style={{ color: t.text, fontSize: 14, fontWeight: '700' }} numberOfLines={1}>
                      {s.unit.replace(/\.service$/, '')}
                    </Text>
                    <Text style={{ color: t.textFaint, fontSize: 11.5 }} numberOfLines={1}>
                      {s.description}
                    </Text>
                  </View>
                  <Text style={{ color, fontSize: 11.5, fontWeight: '700' }}>{statusLabel(s)}</Text>
                  <Icon name="chevron-right" size={18} color={t.textFaint} />
                </Pressable>
              );
            })}
          </Card>
        )}
      </Section>

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
            <View style={{ flexDirection: 'row', gap: space(2) }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textDim, fontSize: 11.5, fontWeight: '600' }}>ACTIVE</Text>
                <Text style={{ color: t.text, fontSize: 20, fontWeight: '800' }}>{total?.count ?? 0}</Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textDim, fontSize: 11.5, fontWeight: '600' }}>DOWN</Text>
                <Text style={{ color: t.ok, fontSize: 16, fontWeight: '800' }}>{rate(total?.speed ?? 0)}</Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.textDim, fontSize: 11.5, fontWeight: '600' }}>UP</Text>
                <Text style={{ color: t.textDim, fontSize: 16, fontWeight: '800' }}>{rate(total?.upspeed ?? 0)}</Text>
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
                  <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '700' }}>{s.user}</Text>
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
              const color = freePct < 10 ? t.bad : freePct < 20 ? t.warn : t.ok;
              return (
                <View key={d.mount} style={{ gap: 6 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: space(2) }}>
                    <Text style={{ color: t.text, fontSize: 13.5, fontWeight: '700', flex: 1 }} numberOfLines={1}>
                      {d.kind}
                      <Text style={{ color: t.textFaint, fontWeight: '400' }}> · {d.mount}</Text>
                    </Text>
                    <Text style={{ color, fontSize: 12.5, fontWeight: '700' }}>{Math.round(freePct)}% free</Text>
                  </View>
                  <Bar pct={d.pct} color={color} />
                  <View style={{ flexDirection: 'row' }}>
                    <Text style={{ color: t.textFaint, fontSize: 11.5, flex: 1 }}>
                      {bytes(d.used, 0)} / {bytes(d.total, 0)}
                    </Text>
                    <Text style={{ color: t.textFaint, fontSize: 11.5 }}>{bytes(free, 0)} free</Text>
                  </View>
                </View>
              );
            })}
          </Card>
        )}
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  serviceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(3),
    paddingHorizontal: space(4),
    paddingVertical: space(3),
  },
  dot: { width: 9, height: 9, borderRadius: 5 },
  arrRow: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingVertical: space(1) },
  stream: { gap: 5, paddingTop: space(1) },
});
