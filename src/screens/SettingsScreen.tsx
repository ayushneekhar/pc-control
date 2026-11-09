import Constants from 'expo-constants';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Linking, StyleSheet, Text, TextInput, View } from 'react-native';
import { KeyInfo, PcNative } from '../../modules/pc-native';
import { Banner, Button, Card, Icon, KV, Mono, Pill, Screen, Section } from '../components/ui';
import { ApiError, DEFAULT_SERVER, get, Info, post, serverUrl } from '../lib/api';
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
      <Button label="Send test notification" icon="send-outline" kind="subtle" busy={test.kind === 'busy'} disabled={!registered} onPress={sendTest} />
      {test.kind === 'done' ? <Text style={{ color: test.ok ? t.ok : t.bad, fontSize: 13 }}>{test.text}</Text> : null}
      <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18 }}>
        Delivered by Firebase Cloud Messaging. Only the notification text goes through Google, e.g. “Root approval needed” and the reason, never the command. Approving still happens here, with your fingerprint, over the tailnet.
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

  const myCode = Number(Constants.expoConfig?.android?.versionCode ?? 0);
  const update = info?.apk && info.apk.versionCode > myCode ? info.apk : null;

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
          <KV k="App" v={`${Constants.expoConfig?.version ?? '?'} (${myCode})`} />
        </View>
      ) : null}
      {update ? (
        <Banner tone="accent" icon="download" title={`Update available: ${update.versionName} (${update.versionCode})`}>
          <Button
            label="Download"
            compact
            kind="primary"
            icon="download"
            style={{ alignSelf: 'flex-start', marginTop: 8 }}
            onPress={() => Linking.openURL(serverUrl() + '/pc/app.apk')}
          />
        </Banner>
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
        <Push />
      </Section>
      <Section label="Connection">
        <Server />
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  iconWrap: { width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center' },
  code: { borderRadius: radius.md, borderWidth: 1, paddingVertical: space(4), alignItems: 'center' },
  input: { borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: space(3), height: 46, fontSize: 14 },
});
