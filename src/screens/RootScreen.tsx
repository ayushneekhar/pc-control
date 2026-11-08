import { useNavigation, useRoute } from '@react-navigation/native';
import { useCallback, useEffect, useState } from 'react';
import { Alert, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PcNative } from '../../modules/pc-native';
import { Banner, Button, Card, Empty, Icon, KV, Mono, Pill, Screen, Section } from '../components/ui';
import { ApiError, get, RootRequest } from '../lib/api';
import { commandLine, decide } from '../lib/approve';
import { clock, duration } from '../lib/format';
import { pendingList, reconnectRootStore, useRootStore } from '../lib/rootStore';
import { showArg } from '../lib/rootPayload';
import { useNow } from '../lib/useNow';
import { radius, space, useTheme } from '../theme';

const STATUS: Record<RootRequest['status'], { label: string; tone: 'ok' | 'warn' | 'bad' | 'accent' | 'dim'; icon: string }> = {
  pending: { label: 'Waiting', tone: 'warn', icon: 'clock-outline' },
  running: { label: 'Running', tone: 'accent', icon: 'progress-clock' },
  done: { label: 'Done', tone: 'ok', icon: 'check' },
  denied: { label: 'Denied', tone: 'bad', icon: 'close' },
  expired: { label: 'Expired', tone: 'dim', icon: 'timer-off-outline' },
  cancelled: { label: 'Cancelled', tone: 'dim', icon: 'cancel' },
  lost: { label: 'Lost', tone: 'bad', icon: 'help-circle-outline' },
};

function statusPill(r: RootRequest) {
  const s = STATUS[r.status] ?? STATUS.lost;
  if (r.status === 'done' && r.exit !== 0)
    return <Pill tone="bad" icon="alert" label={r.timed_out ? 'Timed out' : `Exit ${r.exit}`} />;
  if (r.status === 'done') return <Pill tone="ok" icon="check" label="Exit 0" />;
  return <Pill tone={s.tone} icon={s.icon} label={s.label} />;
}

/** The exact argv, one element per row, escapes visible. This is what gets signed. */
function ArgvList({ argv }: { argv: string[] }) {
  const t = useTheme();
  return (
    <View style={[st.argv, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
      {argv.map((a, i) => (
        <View key={i} style={st.argRow}>
          <Text style={[st.argIdx, { color: t.textFaint, fontFamily: t.mono }]}>{i}</Text>
          <Mono style={{ flex: 1 }}>
            {a === '' ? <Text style={{ color: t.textFaint }}>(empty)</Text> : showArg(a)}
          </Mono>
        </View>
      ))}
    </View>
  );
}

function useDecision() {
  const navigation = useNavigation<any>();
  const [busy, setBusy] = useState<string | null>(null);
  const run = useCallback(
    async (req: RootRequest, decision: 'approve' | 'deny') => {
      setBusy(req.id + decision);
      const res = await decide(req, decision);
      setBusy(null);
      if (!res.ok && !res.cancelled) {
        Alert.alert(
          decision === 'approve' ? 'Not approved' : 'Not denied',
          res.message,
          res.reenroll
            ? [{ text: 'Close', style: 'cancel' }, { text: 'Open Settings', onPress: () => navigation.navigate('Settings') }]
            : undefined,
        );
      }
      return res.ok;
    },
    [navigation],
  );
  return { busy, run };
}

function PendingCard({ req, onOpen, decision }: { req: RootRequest; onOpen: () => void; decision: ReturnType<typeof useDecision> }) {
  const t = useTheme();
  const now = useNow(1000, req.status === 'pending');
  const left = Math.max(0, Math.round(req.expires - now / 1000));
  const [showArgs, setShowArgs] = useState(false);
  const pending = req.status === 'pending';
  return (
    <Card style={{ gap: space(3), borderColor: pending ? t.warn : t.border, borderWidth: pending ? 1 : StyleSheet.hairlineWidth }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Icon name="shield-key-outline" size={20} color={pending ? t.warn : t.accent} />
        <Text style={{ color: t.text, fontWeight: '700', fontSize: 16, flex: 1 }} numberOfLines={2}>
          {req.reason || 'Root command'}
        </Text>
        {pending ? <Pill tone={left < 30 ? 'bad' : 'warn'} icon="timer-sand" label={`${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`} /> : statusPill(req)}
      </View>
      <Pressable onPress={() => setShowArgs((v) => !v)} style={[st.cmd, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
        <Mono style={{ fontSize: 14 }}>
          <Text style={{ color: t.bad, fontWeight: '700' }}># </Text>
          {commandLine(req.argv)}
        </Mono>
        <Text style={{ color: t.accent, fontSize: 12.5, marginTop: 6, fontWeight: '600' }}>
          {showArgs ? 'Hide' : 'Show'} {req.argv.length} argument{req.argv.length === 1 ? '' : 's'} exactly
        </Text>
      </Pressable>
      {showArgs ? <ArgvList argv={req.argv} /> : null}
      <View style={{ flexDirection: 'row', gap: space(2), flexWrap: 'wrap' }}>
        <Text style={{ color: t.textFaint, fontSize: 12.5 }}>
          {clock(req.created)} · timeout {duration(req.timeout)} · id {req.id.slice(0, 8)}
        </Text>
      </View>
      {pending ? (
        <View style={{ flexDirection: 'row', gap: space(2) }}>
          <Button
            label="Deny"
            kind="danger"
            icon="close"
            style={{ flex: 1 }}
            busy={decision.busy === req.id + 'deny'}
            disabled={!!decision.busy}
            onPress={() => decision.run(req, 'deny')}
          />
          <Button
            label="Approve"
            kind="ok"
            icon="fingerprint"
            style={{ flex: 1.4 }}
            busy={decision.busy === req.id + 'approve'}
            disabled={!!decision.busy || left <= 0}
            onPress={() => decision.run(req, 'approve')}
          />
        </View>
      ) : (
        <Button label="View output" kind="subtle" icon="console" onPress={onOpen} compact />
      )}
    </Card>
  );
}

function Detail({ id, onClose, decision }: { id: string | null; onClose: () => void; decision: ReturnType<typeof useDecision> }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const store = useRootStore();
  const [loaded, setLoaded] = useState<{ id: string; req?: RootRequest; err?: string } | null>(null);
  const live = id ? store.requests[id] : undefined;

  useEffect(() => {
    if (!id) return;
    let off = false;
    get<{ request: RootRequest }>(`/pc/api/root/request/${id}`)
      .then((r) => !off && setLoaded({ id, req: r.request }))
      .catch((e: ApiError) => !off && setLoaded({ id, err: e.message }));
    return () => {
      off = true;
    };
  }, [id, live?.status]);
  const req = loaded?.id === id ? loaded.req ?? null : null;
  const err = loaded?.id === id ? loaded.err ?? null : null;

  const r = req ? { ...req, ...(live && live.status !== req.status ? live : {}) } : live ?? null;
  return (
    <Modal visible={!!id} animationType="slide" onRequestClose={onClose} transparent statusBarTranslucent navigationBarTranslucent>
      <View style={[st.sheetWrap, { backgroundColor: 'rgba(0,0,0,0.45)' }]}>
        <Pressable style={{ flex: 1 }} onPress={onClose} />
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4), borderColor: t.border }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />
          <ScrollView contentContainerStyle={{ gap: space(3), padding: space(4) }}>
            {!r && !err ? <Text style={{ color: t.textDim }}>Loading…</Text> : null}
            {err && !r ? <Banner tone="bad" title="Couldn't load this request">{err}</Banner> : null}
            {r ? (
              <>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                  <Text style={{ color: t.text, fontSize: 20, fontWeight: '800', flex: 1 }} numberOfLines={2}>
                    {r.reason || 'Root command'}
                  </Text>
                  {statusPill(r)}
                </View>
                <ArgvList argv={r.argv} />
                {r.status === 'pending' ? (
                  <View style={{ flexDirection: 'row', gap: space(2) }}>
                    <Button label="Deny" kind="danger" icon="close" style={{ flex: 1 }} disabled={!!decision.busy}
                      busy={decision.busy === r.id + 'deny'} onPress={() => decision.run(r, 'deny')} />
                    <Button label="Approve" kind="ok" icon="fingerprint" style={{ flex: 1.4 }} disabled={!!decision.busy}
                      busy={decision.busy === r.id + 'approve'} onPress={() => decision.run(r, 'approve')} />
                  </View>
                ) : null}
                <Card style={{ paddingVertical: space(2) }}>
                  <KV k="Requested" v={clock(r.created)} />
                  {r.decided ? <KV k={r.status === 'denied' ? 'Denied' : 'Approved'} v={`${clock(r.decided)} · ${r.device ?? ''}`} /> : null}
                  {r.finished && r.started ? <KV k="Ran for" v={duration(r.finished - r.started) || '<1s'} /> : null}
                  {r.exit != null ? <KV k="Exit code" v={String(r.exit)} mono /> : null}
                  <KV k="Request id" v={r.id} mono />
                </Card>
                {r.stdout ? <Output title="stdout" text={r.stdout} /> : null}
                {r.stderr ? <Output title="stderr" text={r.stderr} tone="bad" /> : null}
                {r.status === 'done' && !r.stdout && !r.stderr ? <Text style={{ color: t.textFaint }}>No output.</Text> : null}
              </>
            ) : null}
          </ScrollView>
          <View style={{ paddingHorizontal: space(4) }}>
            <Button label="Close" kind="subtle" onPress={onClose} />
          </View>
        </View>
      </View>
    </Modal>
  );
}

function Output({ title, text, tone }: { title: string; text: string; tone?: 'bad' }) {
  const t = useTheme();
  return (
    <View style={{ gap: space(1) }}>
      <Text style={{ color: tone === 'bad' ? t.bad : t.textFaint, fontSize: 12, fontWeight: '700', letterSpacing: 0.8 }}>
        {title.toUpperCase()}
      </Text>
      <ScrollView horizontal style={[st.out, { backgroundColor: t.cardAlt, borderColor: t.border }]}>
        <Mono style={{ fontSize: 12 }}>{text.length > 60000 ? text.slice(0, 30000) + '\n…\n' + text.slice(-30000) : text}</Mono>
      </ScrollView>
    </View>
  );
}

export default function RootScreen() {
  const t = useTheme();
  const navigation = useNavigation<any>();
  const route = useRoute<any>();
  const store = useRootStore();
  const decision = useDecision();
  const [history, setHistory] = useState<RootRequest[] | null>(null);
  const [histErr, setHistErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const enrolled = !!PcNative?.getSettings().deviceFingerprint;

  const loadHistory = useCallback(async () => {
    try {
      const res = await get<{ history: RootRequest[] }>('/pc/api/root/history?limit=60');
      setHistory(res.history);
      setHistErr(null);
    } catch (e) {
      setHistErr((e as ApiError).message);
    }
  }, []);

  useEffect(() => {
    // data fetch: state is only set after the request resolves
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (store.rootd === 'ok') loadHistory();
  }, [store.historyVersion, store.rootd, loadHistory]);

  // deep link pccontrol://root/<id> from a notification
  const linked = route.params?.requestId as string | undefined;
  const shownId = open ?? linked ?? null;
  const closeDetail = () => {
    setOpen(null);
    if (linked) navigation.setParams({ requestId: undefined });
  };

  const pending = pendingList(store);
  const waiting = pending.filter((r) => r.status === 'pending').length;

  const status =
    store.rootd === 'ok' ? (
      <Pill tone={store.live ? 'ok' : 'warn'} label={store.live ? 'rootd · live' : 'rootd · polling'} />
    ) : store.rootd === 'not_installed' ? (
      <Pill tone="dim" label="rootd not installed" />
    ) : store.rootd === 'down' ? (
      <Pill tone="bad" label="rootd not running" />
    ) : (
      <Pill tone="dim" label="connecting…" />
    );

  return (
    <>
      <Screen
        title="Root"
        subtitle={<View style={{ marginTop: 6 }}>{status}</View>}
        right={waiting ? <Pill tone="warn" icon="bell-ring-outline" label={`${waiting} waiting`} /> : undefined}
        refreshing={refreshing}
        onRefresh={async () => {
          setRefreshing(true);
          reconnectRootStore();
          await loadHistory();
          setRefreshing(false);
        }}
      >
        {store.rootd === 'not_installed' ? (
          <Banner tone="accent" icon="shield-lock-outline" title="rootd isn't installed yet">
            <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>
              Root approvals need the root daemon on the laptop. Run this once in a terminal on the laptop:
            </Text>
            <Mono style={{ marginTop: 6 }}>sudo ~/code/pc-control/server/install-rootd.sh</Mono>
          </Banner>
        ) : null}
        {store.rootd === 'down' ? (
          <Banner tone="bad" title="rootd is not running">
            <Mono>sudo systemctl status rootd</Mono>
          </Banner>
        ) : null}
        {store.rootd === 'ok' && !enrolled ? (
          <Banner tone="warn" icon="fingerprint" title="This phone can’t approve yet">
            <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>Set up root approvals to enroll this phone’s key.</Text>
            <Button label="Set up" compact icon="arrow-right" kind="subtle" style={{ alignSelf: 'flex-start', marginTop: 8 }}
              onPress={() => navigation.navigate('Settings')} />
          </Banner>
        ) : null}

        <Section label="Waiting for you">
          {pending.length === 0 ? (
            <Card>
              <Empty icon="shield-check-outline" title="Nothing waiting" body="When the agent asks to run something as root, it shows up here with the exact command." />
            </Card>
          ) : (
            pending.map((r) => <PendingCard key={r.id} req={r} decision={decision} onOpen={() => setOpen(r.id)} />)
          )}
        </Section>

        <Section label="History">
          {histErr ? <Text style={{ color: t.textDim, paddingHorizontal: space(1) }}>{histErr}</Text> : null}
          {history && history.length === 0 ? <Text style={{ color: t.textFaint, paddingHorizontal: space(1) }}>No commands yet.</Text> : null}
          {history && history.length > 0 ? (
            <Card style={{ padding: 0 }}>
              {history.map((h, i) => (
                <Pressable
                  key={h.id}
                  onPress={() => setOpen(h.id)}
                  android_ripple={{ color: t.accentSoft }}
                  style={[st.histRow, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border }]}
                >
                  <View style={{ flex: 1, gap: 3 }}>
                    <Mono style={{ fontSize: 13 }} selectable={false}>
                      {commandLine(h.argv).slice(0, 120)}
                    </Mono>
                    <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
                      {clock(h.finished ?? h.created)}
                      {h.reason ? ` · ${h.reason}` : ''}
                    </Text>
                  </View>
                  {statusPill(h)}
                </Pressable>
              ))}
            </Card>
          ) : null}
        </Section>
      </Screen>
      <Detail id={shownId} onClose={closeDetail} decision={decision} />
    </>
  );
}

const st = StyleSheet.create({
  argv: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, paddingVertical: space(2), paddingHorizontal: space(3), gap: 4 },
  argRow: { flexDirection: 'row', gap: space(3), alignItems: 'flex-start' },
  argIdx: { width: 18, textAlign: 'right', fontSize: 12, lineHeight: 19 },
  cmd: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, padding: space(3) },
  histRow: { flexDirection: 'row', alignItems: 'center', gap: space(3), paddingHorizontal: space(4), paddingVertical: space(3) },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '88%', borderTopLeftRadius: 24, borderTopRightRadius: 24, borderWidth: StyleSheet.hairlineWidth },
  grabber: { width: 40, height: 4, borderRadius: 2, alignSelf: 'center', marginTop: 10 },
  out: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, padding: space(3), maxHeight: 320 },
});
