import { useFocusEffect, useNavigation } from '@react-navigation/native';
import Constants from 'expo-constants';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Animated, AppState, LayoutAnimation, Linking, Platform, Pressable, StyleSheet, Switch, Text, TextInput, UIManager, View } from 'react-native';
import { KeyInfo, PcNative } from '../../modules/pc-native';
import { Banner, Button, Card, Icon, KV, List, ListRow, Mono, Pill, Screen, Section, StatusDot } from '../components/ui';
import { AgentState, ApiError, DEFAULT_SERVER, get, getAgent, Info, post, serverUrl, VpnState } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { refreshCatCam, toggleCatCam, useCatCam } from '../lib/catcam';
import { ApkRelease, APK_URLS, binaryTarget, clearOtaRecovery, latestApk, otaRecovery, otaSnapshot } from '../lib/ota';
import { applyOta, checkOta, useOta } from '../lib/otaStore';
import { liveStatus } from '../lib/live';
import { askNotificationPermission, registerPush } from '../lib/push';
import { reconnectRootStore, useRootStore } from '../lib/rootStore';
import { keyFingerprint } from '../lib/rootPayload';
import { radius, space, useTheme } from '../theme';

type Phase =
  | { kind: 'idle' }
  | { kind: 'working'; what: string }
  | { kind: 'waiting'; code: string }
  | { kind: 'error'; message: string };

function useAppActiveTick() {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const s = AppState.addEventListener('change', (st) => st === 'active' && setTick((n) => n + 1));
    return () => s.remove();
  }, []);
  return tick;
}

if (Platform.OS === 'android' && UIManager.setLayoutAnimationEnabledExperimental) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

function Toggle({ value, onValueChange, label, disabled, dim = false }: {
  value: boolean;
  onValueChange: (v: boolean) => void;
  label: string;
  disabled?: boolean;
  dim?: boolean;
}) {
  const t = useTheme();
  return (
    <Switch
      value={value}
      onValueChange={onValueChange}
      disabled={disabled}
      accessibilityLabel={label}
      trackColor={{ true: dim ? t.border : t.accent, false: t.cardAlt }}
      thumbColor={value ? (dim ? t.textFaint : t.onMedia) : t.textFaint}
    />
  );
}

/** Line item in Settings that navigates to the dedicated Agent settings screen. */
function AgentRow() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const [s, setS] = useState<AgentState | null>(() => cacheGet<AgentState>('agent:state'));

  const refresh = useCallback(async () => {
    try {
      const next = await getAgent();
      cacheSet('agent:state', next);
      setS(next);
    } catch {
      // keep previous summary
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  const label = s?.engine === 'gemini' ? 'Gemini' : 'Claude';
  const active = s ? (s.engine === 'gemini' ? s.gemini_active : s.claude_active) : false;
  const status = !s
    ? null
    : !s.available
      ? 'Unavailable'
      : s.switching
        ? `${label} · Switching…`
        : `${label} · ${active ? 'Running' : 'Stopped'}`;
  const dot = !s || !s.available ? 'faint' : s.switching ? 'warn' : active ? 'ok' : 'warn';

  return (
    <List>
      <ListRow
        icon="robot-outline"
        title="Agent"
        subtitle={
          status ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 1 }}>
              <StatusDot tone={dot} />
              <Text style={{ color: t.textFaint, fontSize: 12.5 }} numberOfLines={1}>
                {status}
              </Text>
            </View>
          ) : undefined
        }
        chevron
        last
        onPress={() => nav.navigate('AgentSettings')}
      />
    </List>
  );
}

/** Master switch for the cat cam systemd stack (catcam, cam-ptz, camrec, catdetect, cam-music). */
function CatCamCard() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const cat = useCatCam();

  useFocusEffect(
    useCallback(() => {
      void refreshCatCam();
    }, []),
  );

  useEffect(() => {
    if (tick > 0) void refreshCatCam();
  }, [tick]);

  const units = Object.values(cat.data?.units ?? {});
  const running = units.filter((u) => u === 'active').length;
  const enabled = cat.enabled;
  const status = cat.busy
    ? enabled
      ? 'Turning on…'
      : 'Turning off…'
    : !enabled
      ? 'Off'
      : units.length
        ? `On · ${running}/${units.length} running`
        : 'On';
  const dot = cat.busy ? 'warn' : enabled ? (units.length && running === 0 ? 'warn' : 'ok') : 'faint';

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
        <Icon name={enabled ? 'cctv' : 'cctv-off'} size={22} color={enabled ? t.accent : t.textDim} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '600', fontSize: 16 }}>Cat cam</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <StatusDot tone={dot} />
            <Text style={{ color: t.textDim, fontSize: 13 }}>{status}</Text>
          </View>
        </View>
        <Toggle
          value={enabled}
          onValueChange={(v) => void toggleCatCam(v)}
          disabled={cat.busy}
          label="Cat cam master switch"
        />
      </View>
      {cat.error ? <Banner tone="bad" title="Cat cam">{cat.error}</Banner> : null}
    </Card>
  );
}

/** qBittorrent -> Proton WireGuard only. The switch is optimistic: it rolls back if pc-api rejects the change. */
function TorrentVpn() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const [s, setS] = useState<VpnState | null>(() => cacheGet<VpnState>('settings:vpn'));
  const [err, setErr] = useState<string | null>(null);
  const [pending, setPending] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const next = await get<VpnState>('/pc/api/vpn');
      cacheSet('settings:vpn', next);
      setS(next);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // one check on mount, then every 8 s while Settings is open
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
    const iv = setInterval(refresh, 8000);
    return () => clearInterval(iv);
  }, [refresh, tick]);

  const toggle = async (v: boolean) => {
    if (busy) return;
    const before = s;
    setPending(v);
    setBusy(true);
    try {
      const next = await post<VpnState>('/pc/api/vpn', { enabled: v });
      cacheSet('settings:vpn', next);
      setS(next);
      setErr(null);
    } catch (e) {
      setS(before);
      setErr((e as Error).message);
    } finally {
      setBusy(false);
      setPending(null);
    }
  };

  const enabled = pending ?? s?.enabled ?? false;
  const down = enabled && s != null && !s.tunnel_up;
  const noPort = enabled && s != null && s.tunnel_up && s.forwarded_port == null;
  const status = busy
    ? enabled ? 'Turning on…' : 'Turning off…'
    : !enabled ? 'Off'
    : down ? 'Tunnel down'
    : noPort ? 'Connected, no forwarded port'
    : 'Connected via Proton SG';
  const dot = down || noPort ? 'warn' : enabled ? 'ok' : 'faint';

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
        <Icon name="shield-lock-outline" size={22} color={down || noPort ? t.warn : enabled ? t.accent : t.textDim} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '600', fontSize: 16 }}>Torrent VPN</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <StatusDot tone={dot} />
            <Text style={{ color: t.textDim, fontSize: 13 }}>{status}</Text>
          </View>
        </View>
        <Toggle value={enabled} onValueChange={toggle} disabled={!s || busy} label="Route qBittorrent through Proton VPN" />
      </View>

      {err ? <Banner tone="bad" title="Torrent VPN">{err}</Banner> : null}
      {s?.last_error ? <Text style={{ color: down ? t.warn : t.textFaint, fontSize: 12.5, lineHeight: 18 }}>{s.last_error}</Text> : null}

      <View>
        <KV k="Forwarded port" v={enabled ? (s?.forwarded_port ?? 'Waiting for NAT-PMP…') : '—'} mono={enabled && s?.forwarded_port != null} />
        <KV k="VPN public IP" v={enabled ? (s?.public_ip ?? '—') : '—'} mono={enabled && !!s?.public_ip} />
      </View>
    </Card>
  );
}

function Enrollment() {
  const t = useTheme();
  const store = useRootStore();
  const [key, setKey] = useState<KeyInfo | null>(() => PcNative?.keyInfo() ?? null);
  const [fp, setFp] = useState<string | null>(() => PcNative?.getSettings().deviceFingerprint ?? null);
  const [serverEnrolled, setServerEnrolled] = useState<boolean | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const lastPropose = useRef(0);
  const localCode = key ? keyFingerprint(key.publicKeyDer) : null;

  const propose = useCallback(async (k: KeyInfo) => {
    const code = keyFingerprint(k.publicKeyDer);
    const res = await post<{ fingerprint: string; enrolled: boolean }>('/pc/api/enroll', {
      name: PcNative?.deviceName() ?? 'phone',
      pubkey: k.publicKeyPem,
    });
    lastPropose.current = Date.now();
    if (res.fingerprint !== code) {
      // The server (pc-api runs as the agent's user) reported a different key code than this phone's own key.
      throw new Error(`The laptop reported code ${res.fingerprint}, but this phone's key is ${code}. Something is tampering with enrollment. Do NOT type any code into rootd-admin.`);
    }
    return res.enrolled;
  }, []);

  // While waiting: poll the status, and re-propose every 60 s (pending proposals expire on the laptop).
  useEffect(() => {
    if (phase.kind !== 'waiting' || !key) return;
    let off = false;
    const tick = async () => {
      try {
        const st = await get<{ enrolled: boolean }>(`/pc/api/enroll/status?fingerprint=${encodeURIComponent(phase.code)}`);
        if (off) return;
        if (st.enrolled) {
          PcNative?.setDeviceFingerprint(phase.code);
          setFp(phase.code);
          setServerEnrolled(true);
          setPhase({ kind: 'idle' });
          return;
        }
        if (Date.now() - lastPropose.current > 60000) await propose(key);
      } catch (e) {
        if (!off && e instanceof Error && !(e instanceof ApiError)) setPhase({ kind: 'error', message: e.message });
      }
    };
    const iv = setInterval(tick, 2500);
    return () => {
      off = true;
      clearInterval(iv);
    };
  }, [phase, key, propose, store.enrolledTick]);

  // Verify enrollment with the laptop whenever we show the screen.
  useEffect(() => {
    if (!fp || store.rootd !== 'ok') return;
    get<{ enrolled: boolean }>(`/pc/api/enroll/status?fingerprint=${encodeURIComponent(fp)}`)
      .then((r) => setServerEnrolled(r.enrolled))
      .catch(() => setServerEnrolled(null));
  }, [fp, store.rootd, store.enrolledTick]);

  const setUp = async () => {
    if (!PcNative) return;
    const bio = PcNative.biometricStatus();
    if (bio !== 'ok') {
      setPhase({
        kind: 'error',
        message:
          bio === 'none_enrolled'
            ? 'Add a fingerprint in Android settings first (Security > Fingerprint).'
            : `Strong biometrics are not available on this phone (${bio}).`,
      });
      return;
    }
    try {
      setPhase({ kind: 'working', what: 'Creating a key in the phone’s secure hardware…' });
      const k = await PcNative.createKey();
      setKey(k);
      PcNative.setDeviceFingerprint(null);
      setFp(null);
      setServerEnrolled(null);
      setPhase({ kind: 'working', what: 'Sending the public key to the laptop…' });
      const code = keyFingerprint(k.publicKeyDer);
      const already = await propose(k);
      if (already) {
        PcNative.setDeviceFingerprint(code);
        setFp(code);
        setServerEnrolled(true);
        setPhase({ kind: 'idle' });
      } else setPhase({ kind: 'waiting', code });
    } catch (e) {
      setPhase({ kind: 'error', message: (e as Error).message });
    }
  };

  const remove = () =>
    Alert.alert('Remove approval key?', 'This phone will no longer be able to approve root commands until you set it up again. Also revoke it on the laptop: sudo rootd-admin revoke ' + (fp ?? ''), [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          PcNative?.deleteKey();
          setKey(null);
          setFp(null);
          setServerEnrolled(null);
          setPhase({ kind: 'idle' });
        },
      },
    ]);

  if (!PcNative) return <Banner tone="bad" title="Native module missing">This build can’t do root approvals.</Banner>;

  const enrolledHere = !!fp && !!key && fp === localCode;
  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
        <Icon name="fingerprint" size={22} color={enrolledHere && serverEnrolled !== false ? t.ok : t.textDim} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '600', fontSize: 16 }}>Root approvals</Text>
          <Text style={{ color: t.textDim, fontSize: 13 }}>
            {enrolledHere
              ? serverEnrolled === false
                ? 'Enrolled here, but the laptop no longer knows this key'
                : 'This phone is enrolled'
              : key
                ? 'Key created, not enrolled yet'
                : 'Not set up'}
          </Text>
        </View>
        {enrolledHere && serverEnrolled !== false ? <Pill tone="ok" icon="check" label="Active" /> : null}
      </View>

      {enrolledHere ? (
        <View style={{ gap: 2 }}>
          <KV k="Key code" v={fp!} mono />
          <KV k="Stored in" v={key?.securityLevel === 'strongbox' ? 'StrongBox' : key?.securityLevel === 'tee' ? 'Secure hardware (TEE)' : key?.securityLevel ?? '–'} />
        </View>
      ) : null}

      {phase.kind === 'working' ? <Text style={{ color: t.textDim }}>{phase.what}</Text> : null}
      {phase.kind === 'error' ? <Banner tone="bad" title="Setup stopped">{phase.message}</Banner> : null}
      {phase.kind === 'waiting' ? (
        <View style={{ gap: space(3) }}>
          <Mono style={{ backgroundColor: t.cardAlt, padding: space(3), borderRadius: radius.md }}>sudo rootd-admin enroll</Mono>
          <View style={[st.code, { backgroundColor: t.accentSoft }]}>
            <Text selectable style={{ color: t.accent, fontFamily: t.mono, fontSize: 26, fontWeight: '600', letterSpacing: 1.5 }}>
              {phase.code}
            </Text>
          </View>
          <Text style={{ color: t.textFaint, fontSize: 12.5 }}>Waiting for confirmation…</Text>
        </View>
      ) : null}

      <View style={{ flexDirection: 'row', gap: space(2) }}>
        {phase.kind !== 'waiting' ? (
          <Button
            label={enrolledHere ? 'Re-enroll' : 'Set up'}
            icon="key-plus"
            kind={enrolledHere ? 'subtle' : 'primary'}
            style={{ flex: 1 }}
            busy={phase.kind === 'working'}
            disabled={store.rootd !== 'ok'}
            onPress={() =>
              enrolledHere
                ? Alert.alert('Re-enroll?', 'This creates a new key; the old one stops working here. Revoke it on the laptop afterwards.', [
                    { text: 'Cancel', style: 'cancel' },
                    { text: 'Re-enroll', onPress: setUp },
                  ])
                : setUp()
            }
          />
        ) : (
          <Button label="Cancel" kind="subtle" style={{ flex: 1 }} onPress={() => setPhase({ kind: 'idle' })} />
        )}
        {key ? <Button label="Remove" kind="danger" icon="delete-outline" onPress={remove} /> : null}
      </View>
    </Card>
  );
}

/** One warning only when something blocks notifications; a healthy setup adds no card. */
function NotificationHealth() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const [notifs, setNotifs] = useState(() => PcNative?.notificationsEnabled() ?? false);
  const [push, setPush] = useState(() => PcNative?.pushStatus() ?? { available: false, token: null, error: null });
  const [server, setServer] = useState<{ configured: boolean; registered?: boolean } | null>(null);
  const [live, setLive] = useState(() => liveStatus());
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!PcNative) return;
    setBusy(true);
    try {
      const allowed = PcNative.notificationsEnabled();
      setNotifs(allowed);
      setLive(liveStatus());
      if (!allowed) {
        setServer(null);
        return;
      }
      await registerPush();
      const st = PcNative.pushStatus();
      setPush(st);
      try {
        setServer(await get<{ configured: boolean; registered?: boolean }>(`/pc/api/push/status?token=${encodeURIComponent(st.token ?? '')}`));
      } catch {
        setServer(null);
      }
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    // re-check (and re-register) whenever the screen is shown or the app comes back to the foreground
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh, tick]);

  const allow = async () => {
    if (await askNotificationPermission()) {
      await refresh();
      return;
    }
    Alert.alert('Notifications are off', 'pc-control needs notifications to tell you about root requests.', [
      { text: 'Not now', style: 'cancel' },
      { text: 'Open settings', onPress: () => PcNative?.openNotificationSettings() },
    ]);
  };

  const registered = !!push.token && server?.registered === true;
  const problem = !notifs
    ? {
        title: 'Notifications are off',
        body: 'Root approvals and results will not reach this phone.',
        fix: 'Allow',
        run: allow,
      }
    : !registered
      ? {
          title: 'Push notifications are not connected',
          body: push.error ?? 'This phone is not registered with the laptop yet.',
          fix: 'Retry',
          run: refresh,
        }
      : live && !live.channelEnabled
        ? {
            title: 'Live download progress is off',
            body: 'Turn the “Live download progress” notification channel back on.',
            fix: 'Open settings',
            run: () => PcNative?.openNotificationSettings(),
          }
        : null;

  if (!problem) return null;
  return (
    <Banner tone="warn" title={problem.title}>
      <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>{problem.body}</Text>
      <Button
        label={problem.fix}
        compact
        kind="subtle"
        busy={busy && problem.fix === 'Retry'}
        style={{ alignSelf: 'flex-start', marginTop: 8 }}
        onPress={problem.run}
      />
    </Banner>
  );
}

type Channel = 'telegram' | 'app';
type NotifCategory = { id: string; label: string; description: string; icon: string; telegram: boolean; app: boolean; channels?: Channel[] };
type NotifSettings = {
  masters: Record<Channel, boolean>;
  categories: NotifCategory[];
  channels: { telegram: { configured: boolean }; app: { configured: boolean; devices: number } };
};

/** Per-category delivery: Telegram (Qwen bot) and/or this app. Stored on the laptop (pc-api). */
function NotificationPrefs() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const catCam = useCatCam();
  const [s, setS] = useState<NotifSettings | null>(() => cacheGet<NotifSettings>('settings:notify'));
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    get<NotifSettings>('/pc/api/notify/settings')
      .then((r) => {
        cacheSet('settings:notify', r);
        setS(r);
        setErr(null);
      })
      .catch((e) => setErr((e as Error).message));
  }, [tick]);

  const save = async (patch: { masters?: Partial<Record<Channel, boolean>>; categories?: Record<string, Partial<Record<Channel, boolean>>> }, optimistic: NotifSettings) => {
    const before = s;
    setS(optimistic);
    try {
      const next = await post<NotifSettings>('/pc/api/notify/settings', patch);
      cacheSet('settings:notify', next);
      setS(next);
      setErr(null);
    } catch (e) {
      setS(before);
      setErr(`Not saved: ${(e as Error).message}`);
    }
  };
  const setMaster = (ch: Channel, v: boolean) => s && save({ masters: { [ch]: v } }, { ...s, masters: { ...s.masters, [ch]: v } });
  const setCat = (id: string, ch: Channel, v: boolean) =>
    s && save({ categories: { [id]: { [ch]: v } } }, { ...s, categories: s.categories.map((c) => (c.id === id ? { ...c, [ch]: v } : c)) });

  const [open, setOpen] = useState(false);
  const [anim] = useState(() => new Animated.Value(0));
  useEffect(() => {
    Animated.timing(anim, { toValue: open ? 1 : 0, duration: 220, useNativeDriver: false }).start();
  }, [open, anim]);

  const toggleOpen = () => {
    LayoutAnimation.configureNext({
      duration: 220,
      create: { type: LayoutAnimation.Types.easeInEaseOut, property: LayoutAnimation.Properties.opacity },
      update: { type: LayoutAnimation.Types.easeInEaseOut },
      delete: { type: LayoutAnimation.Types.easeInEaseOut, property: LayoutAnimation.Properties.opacity },
    });
    setOpen((v) => !v);
  };

  if (!s)
    return (
      <Card>
        {err ? <Banner tone="bad" title="Can't load notification settings">{err}</Banner> : <Text style={{ color: t.textDim }}>Loading…</Text>}
      </Card>
    );

  const sw = (on: boolean, dim: boolean, onChange: (v: boolean) => void, label: string) => (
    <Toggle value={on} onValueChange={onChange} label={label} dim={dim} />
  );
  return (
    <Card style={{ gap: space(1), paddingHorizontal: 0 }}>
      <Pressable
        onPress={toggleOpen}
        accessibilityRole="button"
        accessibilityLabel="Show or hide notification categories"
        accessibilityState={{ expanded: open }}
        style={({ pressed }) => [
          { flexDirection: 'row', alignItems: 'center', gap: space(3), paddingHorizontal: space(4), marginBottom: space(2) },
          pressed && { opacity: 0.8 },
        ]}
      >
        <Icon name="bell-cog-outline" size={22} color={t.accent} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '600', fontSize: 16 }}>Where notifications go</Text>
        </View>
        <Animated.View
          style={{
            transform: [
              { rotate: anim.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '180deg'] }) },
            ],
          }}
        >
          <Icon name="chevron-down" size={22} color={t.textDim} />
        </Animated.View>
      </Pressable>
      {err ? <Banner tone="bad" title="Notification settings">{err}</Banner> : null}

      <View style={[st.masters, { backgroundColor: t.cardAlt }]}>
        {(['telegram', 'app'] as Channel[]).map((ch) => (
          <View key={ch} style={st.master}>
            <Icon name={ch === 'telegram' ? 'send-outline' : 'cellphone'} size={18} color={s.masters[ch] ? t.accent : t.textFaint} />
            <View style={{ flex: 1 }}>
              <Text style={{ color: t.text, fontWeight: '600', fontSize: 14 }}>{ch === 'telegram' ? 'Telegram' : 'App'}</Text>
              <Text style={{ color: t.textFaint, fontSize: 11.5 }}>
                {!s.channels[ch].configured ? 'not set up' : s.masters[ch] ? 'on' : 'all off'}
              </Text>
            </View>
            {sw(s.masters[ch], false, (v) => setMaster(ch, v), `${ch} master switch`)}
          </View>
        ))}
      </View>

      {open ? (
        <View>
          <View style={[st.colHead, { borderBottomColor: t.border }]}>
            <Text style={{ flex: 1, color: t.textDim, fontSize: 11.5, fontWeight: '500' }}>Category</Text>
            <Text style={[st.col, { color: t.textDim }]}>Telegram</Text>
            <Text style={[st.col, { color: t.textDim }]}>App</Text>
          </View>
          {s.categories
            .filter((c) => catCam.enabled || c.id !== 'cats')
            .map((c, i, visible) => (
            <View key={c.id} style={[st.catRow, i < visible.length - 1 && { borderBottomColor: t.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
              <Icon name={c.icon} size={20} color={c.telegram || c.app ? t.text : t.textFaint} />
              <View style={{ flex: 1, paddingRight: space(1) }}>
                <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }}>{c.label}</Text>
              </View>
              <View style={st.colCell}>
                {(c.channels ?? ['telegram', 'app']).includes('telegram') ? (
                  sw(c.telegram, !s.masters.telegram, (v) => setCat(c.id, 'telegram', v), `${c.label} on Telegram`)
                ) : (
                  <Text style={{ color: t.textFaint, fontSize: 16 }} accessibilityLabel={`${c.label}: app only`}>
                    –
                  </Text>
                )}
              </View>
              <View style={st.colCell}>{sw(c.app, !s.masters.app, (v) => setCat(c.id, 'app', v), `${c.label} in the app`)}</View>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

/** APK releases (GitHub) + over-the-air JS updates (signed, for this exact build). */
function Updates() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const ota = useOta();
  const [apk, setApk] = useState<ApkRelease | { error: string } | undefined>(undefined);
  const [recovery, setRecovery] = useState(() => otaRecovery());
  const snap = otaSnapshot();
  const myCode = Number(Constants.expoConfig?.android?.versionCode ?? 0);

  useEffect(() => {
    latestApk().then(setApk, (e) => setApk({ error: (e as Error).message }));
    checkOta();
  }, [tick]);

  const newer = apk && 'versionCode' in apk && apk.versionCode > myCode ? apk : null;
  const apkError = apk && 'error' in apk ? apk.error : null;
  const otaReady = ota.check?.hasUpdate && ota.check.isCompatible;
  return (
    <Card style={{ gap: space(3) }}>
      {recovery ? (
        <Banner tone="warn" title="An update was rolled back">
          {recovery.message}
          <Button label="OK" compact kind="subtle" style={{ alignSelf: 'flex-start', marginTop: 8 }} onPress={() => (clearOtaRecovery(), setRecovery(null))} />
        </Banner>
      ) : null}
      <View>
        <KV k="App version" v={`${Constants.expoConfig?.version ?? '?'} (${myCode})`} />
        <KV k="Build" v={binaryTarget()} mono />
        <KV k="JS update" v={snap.otaVersion ? `OTA ${snap.otaVersion}${snap.fromDownload ? '' : ' (built in)'}` : 'built in'} />
      </View>

      {otaReady ? (
        <Banner tone="accent" icon="cloud-download-outline" title={`JS update ${ota.check?.remoteVersion} available`}>
          {ota.phase === 'ready' ? 'Downloaded. It applies the next time the app starts.' : ota.check?.metadata?.releaseNotes ?? ''}
        </Banner>
      ) : null}
      {ota.error ? <Text style={{ color: t.textDim, fontSize: 13 }}>{ota.error}</Text> : null}
      {apkError && apkError !== ota.error ? <Text style={{ color: t.textDim, fontSize: 13 }}>{apkError}</Text> : null}
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Check" icon="refresh" kind="subtle" style={{ flex: 1 }} busy={ota.checking} onPress={() => checkOta(true)} />
        {otaReady && ota.phase !== 'ready' ? (
          <Button label={ota.phase === 'downloading' ? `${Math.round(ota.progress * 100)}%` : 'Update now'} icon="restart" style={{ flex: 1 }} busy={ota.phase === 'downloading'} onPress={() => applyOta(true)} />
        ) : null}
      </View>

      {newer ? (
        <Banner tone="accent" icon="download" title={`New app version: ${newer.versionName} (${newer.versionCode})`}>
          <Button label="Download APK" compact kind="primary" icon="download" style={{ alignSelf: 'flex-start', marginTop: 4 }} onPress={() => Linking.openURL(APK_URLS.apkUrl)} />
        </Banner>
      ) : null}
    </Card>
  );
}

function Server() {
  const t = useTheme();
  const [url, setUrl] = useState(serverUrl());
  const [info, setInfo] = useState<Info | null>(() => cacheGet<Info>('home:info'));
  const [who, setWho] = useState<{ login: string | null } | null>(() => cacheGet<{ login: string | null }>('settings:whoami'));
  const [err, setErr] = useState<string | null>(null);

  const test = useCallback(async () => {
    setErr(null);
    try {
      const [i, w] = await Promise.all([get<Info>('/pc/api/info'), get<{ login: string | null }>('/pc/api/whoami')]);
      cacheSet('home:info', i);
      cacheSet('settings:whoami', w);
      setInfo(i);
      setWho(w);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // automatic connection check on mount; Save runs it again
    // eslint-disable-next-line react-hooks/set-state-in-effect
    test();
  }, [test]);

  const save = () => {
    const clean = url.trim().replace(/\/+$/, '');
    if (!/^https:\/\/[^\s/]+$/.test(clean)) {
      Alert.alert('Use an https:// address', 'For example ' + DEFAULT_SERVER);
      return;
    }
    PcNative?.setServerUrl(clean);
    registerPush();
    reconnectRootStore();
    test();
  };

  return (
    <Card style={{ gap: space(3) }}>
      <Text style={{ color: t.textDim, fontSize: 13 }}>Laptop address (tailnet)</Text>
      <TextInput
        value={url}
        onChangeText={setUrl}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType="url"
        onSubmitEditing={save}
        placeholder={DEFAULT_SERVER}
        placeholderTextColor={t.textFaint}
        selectionColor={t.accent}
        style={[st.input, { color: t.text, backgroundColor: t.cardAlt, borderColor: t.border, fontFamily: t.mono }]}
      />
      <Button label="Save" kind="subtle" onPress={save} disabled={url.trim().replace(/\/+$/, '') === serverUrl()} />
      {err ? <Banner tone="bad" title="Can't reach the laptop">{err}</Banner> : null}
      {info ? (
        <View>
          <KV k="Host" v={info.host} />
          <KV k="Signed in as" v={who?.login ?? '–'} />
          <KV k="rootd" v={info.rootd.state === 'ok' ? `running · ${info.rootd.devices ?? 0} phone(s)` : info.rootd.state.replace('_', ' ')} />
        </View>
      ) : null}
    </Card>
  );
}

export default function SettingsScreen() {
  const t = useTheme();
  const version = Constants.expoConfig?.version ?? '?';
  const otaVersion = otaSnapshot().otaVersion;
  return (
    <Screen
      title="Settings"
      subtitle={
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
          <StatusDot tone="ok" />
          <Text style={{ color: t.textDim, fontSize: 12.5 }}>
            v{version} · OTA {otaVersion}
          </Text>
        </View>
      }
    >
      <Section label="Agent">
        <AgentRow />
      </Section>
      <Section label="Cameras">
        <CatCamCard />
      </Section>
      <Section label="Network">
        <TorrentVpn />
      </Section>
      <Section label="Root approvals">
        <Enrollment />
      </Section>
      <Section label="Notifications">
        <NotificationHealth />
        <NotificationPrefs />
      </Section>
      <Section label="Connection">
        <Server />
      </Section>
      <Section label="App updates">
        <Updates />
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  code: { borderRadius: radius.md, paddingVertical: space(4), alignItems: 'center' },
  masters: { flexDirection: 'row', borderRadius: radius.md, padding: space(2), gap: space(2), marginHorizontal: space(3), marginBottom: space(2) },
  master: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space(2), paddingLeft: space(1) },
  colHead: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space(4), paddingBottom: space(1), borderBottomWidth: StyleSheet.hairlineWidth },
  col: { width: 64, textAlign: 'center', fontSize: 11, fontWeight: '500' },
  colCell: { width: 64, alignItems: 'center' },
  catRow: { flexDirection: 'row', alignItems: 'center', gap: space(3), paddingVertical: space(3), paddingHorizontal: space(4) },
  input: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: space(3), height: 46, fontSize: 14 },
});
