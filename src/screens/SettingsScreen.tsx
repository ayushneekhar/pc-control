import Constants from 'expo-constants';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Linking, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { KeyInfo, PcNative } from '../../modules/pc-native';
import { Banner, Button, Card, Icon, KV, Mono, Pill, Screen, Section } from '../components/ui';
import { ApiError, DEFAULT_SERVER, get, Info, post, serverUrl } from '../lib/api';
import { ApkRelease, APK_URLS, binaryTarget, clearOtaRecovery, latestApk, otaRecovery, otaSnapshot } from '../lib/ota';
import { applyOta, checkOta, useOta } from '../lib/otaStore';
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
        <View style={[st.iconWrap, { backgroundColor: enrolledHere && serverEnrolled !== false ? t.okSoft : t.cardAlt }]}>
          <Icon name="fingerprint" size={24} color={enrolledHere && serverEnrolled !== false ? t.ok : t.textDim} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '700', fontSize: 16 }}>Root approvals</Text>
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
          <Text style={{ color: t.textDim, lineHeight: 20 }}>
            On the laptop, run this in a terminal (or the installer asks for it), and type the code below when asked:
          </Text>
          <Mono style={{ backgroundColor: t.cardAlt, padding: space(3), borderRadius: radius.md }}>sudo rootd-admin enroll</Mono>
          <View style={[st.code, { backgroundColor: t.accentSoft, borderColor: t.accent }]}>
            <Text selectable style={{ color: t.accent, fontFamily: t.mono, fontSize: 26, fontWeight: '700', letterSpacing: 1.5 }}>
              {phase.code}
            </Text>
          </View>
          <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18 }}>
            This code is computed on the phone from its own key. Only someone with sudo on the laptop can confirm it, so
            nothing on the laptop can enroll a key by itself. Waiting for confirmation…
          </Text>
        </View>
      ) : null}

      {store.rootd !== 'ok' && !enrolledHere ? (
        <Text style={{ color: t.textFaint, fontSize: 13 }}>Install rootd on the laptop first (see the Root tab).</Text>
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

type TestState = { kind: 'idle' } | { kind: 'busy' } | { kind: 'done'; ok: boolean; text: string };

function Push() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const [notifs, setNotifs] = useState(() => PcNative?.notificationsEnabled() ?? false);
  const [status, setStatus] = useState(() => PcNative?.pushStatus() ?? { available: false, token: null, error: null });
  const [server, setServer] = useState<{ configured: boolean; registered?: boolean } | null>(null);
  const [registering, setRegistering] = useState(false);
  const [test, setTest] = useState<TestState>({ kind: 'idle' });
  const [tgTest, setTgTest] = useState<TestState>({ kind: 'idle' });

  const sendTgTest = async () => {
    setTgTest({ kind: 'busy' });
    try {
      await post('/pc/api/notify/test', { channel: 'telegram' });
      setTgTest({ kind: 'done', ok: true, text: 'Sent to the Qwen bot chat.' });
    } catch (e) {
      setTgTest({ kind: 'done', ok: false, text: (e as Error).message });
    }
  };

  const refresh = useCallback(async () => {
    if (!PcNative) return;
    setRegistering(true);
    setNotifs(PcNative.notificationsEnabled());
    await registerPush();
    const s = PcNative.pushStatus();
    setStatus(s);
    try {
      setServer(await get<{ configured: boolean; registered?: boolean }>(`/pc/api/push/status?token=${encodeURIComponent(s.token ?? '')}`));
    } catch {
      setServer(null);
    }
    setRegistering(false);
  }, []);

  useEffect(() => {
    // re-check (and re-register) whenever the screen is shown or the app comes back to the foreground
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh, tick]);

  const allow = async () => {
    if (await askNotificationPermission()) setNotifs(true);
    else
      Alert.alert('Notifications are off', 'pc-control needs notifications to tell you about root requests.', [
        { text: 'Not now', style: 'cancel' },
        { text: 'Open settings', onPress: () => PcNative?.openNotificationSettings() },
      ]);
  };

  const sendTest = async () => {
    setTest({ kind: 'busy' });
    try {
      const r = await post<{ sent: number; results: { ok: boolean; error: string | null }[] }>('/pc/api/push/test', {
        token: status.token,
      });
      const err = r.results.find((x) => !x.ok)?.error;
      setTest(r.sent ? { kind: 'done', ok: true, text: 'Sent. It should arrive in a few seconds.' } : { kind: 'done', ok: false, text: `FCM refused it: ${err ?? 'no phones registered'}` });
    } catch (e) {
      setTest({ kind: 'done', ok: false, text: (e as Error).message });
    }
  };

  const registered = !!status.token && server?.registered === true;
  const subtitle = !status.available
    ? 'This build has no Firebase config'
    : registering && !registered
      ? 'Registering with the laptop…'
      : registered
        ? 'Registered with the laptop'
        : 'Not registered';
  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
        <View style={[st.iconWrap, { backgroundColor: registered && notifs ? t.accentSoft : t.cardAlt }]}>
          <Icon name="bell-badge-outline" size={22} color={registered && notifs ? t.accent : t.textDim} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '700', fontSize: 16 }}>Push notifications</Text>
          <Text style={{ color: t.textDim, fontSize: 13 }}>{subtitle}</Text>
        </View>
        {registered && notifs ? <Pill tone="ok" icon="check" label="On" /> : null}
      </View>
      <Row ok={notifs} label="Notifications allowed" fix={notifs ? undefined : { label: 'Allow', onPress: allow }} />
      <Row ok={registered} label="FCM token registered" fix={registered || registering ? undefined : { label: 'Retry', onPress: refresh }} />
      <Row ok={server?.configured === true} label="FCM set up on the laptop" />
      {!registered && status.error ? <Text style={{ color: t.textFaint, fontSize: 12.5 }}>{status.error}</Text> : null}
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Test app" icon="cellphone-message" kind="subtle" style={{ flex: 1 }} busy={test.kind === 'busy'} disabled={!registered} onPress={sendTest} />
        <Button label="Test Telegram" icon="send-outline" kind="subtle" style={{ flex: 1 }} busy={tgTest.kind === 'busy'} onPress={sendTgTest} />
      </View>
      {tgTest.kind === 'done' ? <Text style={{ color: tgTest.ok ? t.ok : t.bad, fontSize: 13 }}>{tgTest.text}</Text> : null}
      {test.kind === 'done' ? <Text style={{ color: test.ok ? t.ok : t.bad, fontSize: 13 }}>{test.text}</Text> : null}
      <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18 }}>
        App notifications are delivered by Firebase Cloud Messaging. Only the notification text goes through Google, e.g. “Root approval needed” and the reason, never the command; pictures are fetched from the laptop over the tailnet. Approving still happens here, with your fingerprint.
      </Text>
    </Card>
  );
}

type Channel = 'telegram' | 'app';
type NotifCategory = { id: string; label: string; description: string; icon: string; telegram: boolean; app: boolean };
type NotifSettings = {
  masters: Record<Channel, boolean>;
  categories: NotifCategory[];
  channels: { telegram: { configured: boolean }; app: { configured: boolean; devices: number } };
};

/** Per-category delivery: Telegram (Qwen bot) and/or this app. Stored on the laptop (pc-api). */
function NotificationPrefs() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const [s, setS] = useState<NotifSettings | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    get<NotifSettings>('/pc/api/notify/settings')
      .then((r) => {
        setS(r);
        setErr(null);
      })
      .catch((e) => setErr((e as Error).message));
  }, [tick]);

  const save = async (patch: { masters?: Partial<Record<Channel, boolean>>; categories?: Record<string, Partial<Record<Channel, boolean>>> }, optimistic: NotifSettings) => {
    const before = s;
    setS(optimistic);
    try {
      setS(await post<NotifSettings>('/pc/api/notify/settings', patch));
      setErr(null);
    } catch (e) {
      setS(before);
      setErr(`Not saved: ${(e as Error).message}`);
    }
  };
  const setMaster = (ch: Channel, v: boolean) => s && save({ masters: { [ch]: v } }, { ...s, masters: { ...s.masters, [ch]: v } });
  const setCat = (id: string, ch: Channel, v: boolean) =>
    s && save({ categories: { [id]: { [ch]: v } } }, { ...s, categories: s.categories.map((c) => (c.id === id ? { ...c, [ch]: v } : c)) });

  if (!s)
    return (
      <Card>
        {err ? <Banner tone="bad" title="Can't load notification settings">{err}</Banner> : <Text style={{ color: t.textDim }}>Loading…</Text>}
      </Card>
    );

  const sw = (on: boolean, dim: boolean, onChange: (v: boolean) => void, label: string) => (
    <Switch
      value={on}
      onValueChange={onChange}
      accessibilityLabel={label}
      trackColor={{ true: dim ? t.border : t.accent, false: t.cardAlt }}
      thumbColor={on ? (dim ? t.textFaint : '#fff') : t.textFaint}
    />
  );
  return (
    <Card style={{ gap: space(1), paddingHorizontal: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3), paddingHorizontal: space(1), marginBottom: space(2) }}>
        <View style={[st.iconWrap, { backgroundColor: t.accentSoft }]}>
          <Icon name="bell-cog-outline" size={22} color={t.accent} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.text, fontWeight: '700', fontSize: 16 }}>Where notifications go</Text>
          <Text style={{ color: t.textDim, fontSize: 13 }}>Per category: the Qwen bot on Telegram, this app, or both.</Text>
        </View>
      </View>
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

      <View style={[st.colHead, { borderBottomColor: t.border }]}>
        <Text style={{ flex: 1, color: t.textFaint, fontSize: 11.5, fontWeight: '700', letterSpacing: 0.6 }}>CATEGORY</Text>
        <Text style={[st.col, { color: t.textFaint }]}>TELEGRAM</Text>
        <Text style={[st.col, { color: t.textFaint }]}>APP</Text>
      </View>
      {s.categories.map((c, i) => (
        <View key={c.id} style={[st.catRow, i < s.categories.length - 1 && { borderBottomColor: t.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
          <Icon name={c.icon} size={20} color={c.telegram || c.app ? t.text : t.textFaint} />
          <View style={{ flex: 1, paddingRight: space(1) }}>
            <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }}>{c.label}</Text>
            <Text style={{ color: t.textFaint, fontSize: 12, lineHeight: 16 }}>{c.description}</Text>
          </View>
          <View style={st.colCell}>{sw(c.telegram, !s.masters.telegram, (v) => setCat(c.id, 'telegram', v), `${c.label} on Telegram`)}</View>
          <View style={st.colCell}>{sw(c.app, !s.masters.app, (v) => setCat(c.id, 'app', v), `${c.label} in the app`)}</View>
        </View>
      ))}
      {!s.masters.telegram || !s.masters.app ? (
        <Text style={{ color: t.textFaint, fontSize: 12.5, paddingHorizontal: space(1), paddingTop: space(2) }}>
          A channel that is all off ignores its per-category switches (shown greyed).
        </Text>
      ) : null}
    </Card>
  );
}

/** APK releases (GitHub) + over-the-air JS updates (signed, for this exact build). */
function Updates() {
  const t = useTheme();
  const tick = useAppActiveTick();
  const ota = useOta();
  const [apk, setApk] = useState<ApkRelease | null | undefined>(undefined);
  const [recovery, setRecovery] = useState(() => otaRecovery());
  const snap = otaSnapshot();
  const myCode = Number(Constants.expoConfig?.android?.versionCode ?? 0);

  useEffect(() => {
    latestApk().then(setApk);
    checkOta();
  }, [tick]);

  const newer = apk && apk.versionCode > myCode ? apk : null;
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
      {ota.error ? <Text style={{ color: t.bad, fontSize: 13 }}>{ota.error}</Text> : null}
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Check" icon="refresh" kind="subtle" style={{ flex: 1 }} busy={ota.checking} onPress={() => checkOta(true)} />
        {otaReady && ota.phase !== 'ready' ? (
          <Button label={ota.phase === 'downloading' ? `${Math.round(ota.progress * 100)}%` : 'Update now'} icon="restart" style={{ flex: 1 }} busy={ota.phase === 'downloading'} onPress={() => applyOta(true)} />
        ) : null}
      </View>

      {newer ? (
        <Banner tone="accent" icon="download" title={`New app version: ${newer.versionName} (${newer.versionCode})`}>
          This one changes native code, so it’s a full APK from GitHub Releases.
          <Button label="Download APK" compact kind="primary" icon="download" style={{ alignSelf: 'flex-start', marginTop: 8 }} onPress={() => Linking.openURL(APK_URLS.apkUrl)} />
        </Banner>
      ) : null}
      <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18 }}>
        {apk === null ? 'Could not read the latest release from GitHub. ' : ''}JS-only changes arrive over the air, signed by the laptop and only for this exact build. Native changes need a new APK.
      </Text>
    </Card>
  );
}

function Row({ ok, label, fix }: { ok: boolean; label: string; fix?: { label: string; onPress: () => void } }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
      <Icon name={ok ? 'check-circle' : 'alert-circle-outline'} size={18} color={ok ? t.ok : t.warn} />
      <Text style={{ color: t.text, flex: 1, fontSize: 14 }}>{label}</Text>
      {fix ? <Button label={fix.label} compact kind="subtle" onPress={fix.onPress} /> : null}
    </View>
  );
}

function Server() {
  const t = useTheme();
  const [url, setUrl] = useState(serverUrl());
  const [info, setInfo] = useState<Info | null>(null);
  const [who, setWho] = useState<{ login: string | null } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const test = useCallback(async () => {
    setBusy(true);
    setErr(null);
    try {
      const [i, w] = await Promise.all([get<Info>('/pc/api/info'), get<{ login: string | null }>('/pc/api/whoami')]);
      setInfo(i);
      setWho(w);
    } catch (e) {
      setInfo(null);
      setErr((e as Error).message);
    }
    setBusy(false);
  }, []);

  useEffect(() => {
    // one connection test on mount (shows a spinner, then the result)
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
        style={[st.input, { color: t.text, backgroundColor: t.cardAlt, borderColor: t.border, fontFamily: t.mono }]}
      />
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Save" kind="subtle" style={{ flex: 1 }} onPress={save} disabled={url.trim().replace(/\/+$/, '') === serverUrl()} />
        <Button label="Test" kind="subtle" icon="lan-connect" style={{ flex: 1 }} busy={busy} onPress={test} />
      </View>
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
  return (
    <Screen title="Settings">
      <Section label="Root approvals">
        <Enrollment />
      </Section>
      <Section label="Notifications">
        <NotificationPrefs />
        <Push />
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
  iconWrap: { width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center' },
  code: { borderRadius: radius.md, borderWidth: 1, paddingVertical: space(4), alignItems: 'center' },
  masters: { flexDirection: 'row', borderRadius: radius.md, padding: space(2), gap: space(2), marginBottom: space(2) },
  master: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space(2), paddingLeft: space(1) },
  colHead: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space(1), paddingBottom: space(1), borderBottomWidth: StyleSheet.hairlineWidth },
  col: { width: 64, textAlign: 'center', fontSize: 10.5, fontWeight: '700', letterSpacing: 0.4 },
  colCell: { width: 64, alignItems: 'center' },
  catRow: { flexDirection: 'row', alignItems: 'center', gap: space(3), paddingVertical: space(3), paddingHorizontal: space(1) },
  input: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: space(3), height: 46, fontSize: 14 },
});
