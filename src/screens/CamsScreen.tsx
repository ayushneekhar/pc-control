// Native Cat cams: the three cameras over WebRTC (react-native-webrtc, hardware decode), opened from the
// "Cat cams" route. Replay, Music etc. stay on the /cams/ web page (menu, top right).
//
// - One video-only peer connection per camera (no audio on it: lip sync delays video, see link.ts).
// - CP Plus: HD = cat3hd, H.265 2304x1296 straight from the cam, when the phone has a hardware HEVC decoder and
//   libwebrtc offers H.265; no HD frame within HD_WAIT_MS drops to SD (cat3, H.264 720p). Badge: "20 fps · HD·WebRTC".
// - Everything starts muted: audio connections exist only while Listen / the cam mic / Talk are on.
// - Leaving the screen or backgrounding the app closes every connection (and Talk/Listen); coming back
//   reconnects at once.
import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  AppState,
  BackHandler,
  Linking,
  Modal,
  NativeModules,
  PermissionsAndroid,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  useWindowDimensions,
  Vibration,
  View,
} from 'react-native';
import { RTCRtpReceiver, RTCView } from 'react-native-webrtc';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PcNative } from '../../modules/pc-native';
import { Banner, Button, Icon } from '../components/ui';
import { serverUrl } from '../lib/api';
import { CamLink, LinkKind, LinkState } from '../lib/cams/link';
import { CAM_MIC_STREAM, CamId, CAMS, HD_STREAM, HD_WAIT_MS, LAPTOP_MIC_STREAM, NAMES, TALK_STREAM, WEB_PATH } from '../lib/cams/config';
import { radius, space, useTheme } from '../theme';
import { MusicTab } from './cams/MusicTab';
import { ReplayTab } from './cams/ReplayTab';

export type CamsTab = 'live' | 'replay' | 'music';
export type CamsParams = { tab?: CamsTab; share?: string; shareId?: number } | undefined;
const TABS: { id: CamsTab; label: string; icon: string }[] = [
  { id: 'live', label: 'Live', icon: 'access-point' },
  { id: 'replay', label: 'Replay', icon: 'history' },
  { id: 'music', label: 'Music', icon: 'music' },
];

type Stream = { toURL(): string };

// ---------------------------------------------------------------- capability check (once)

function hdSupport(): { ok: boolean; why: string; decoder: string | null } {
  let decoder: string | null = null;
  try {
    const hw = PcNative?.camsHardware();
    if (hw && !hw.configured) return { ok: false, why: hw.error ?? 'WebRTC options not set', decoder };
    if (!hw?.h265) return { ok: false, why: 'This phone has no hardware H.265 decoder', decoder };
    decoder = hw.h265.name;
    if (!hw.h265.hd) return { ok: false, why: `${hw.h265.name} can't decode 2304×1296`, decoder };
  } catch {
    return { ok: false, why: 'No native cams support in this build', decoder };
  }
  try {
    const caps = RTCRtpReceiver.getCapabilities('video');
    if (!caps?.codecs?.some((c: { mimeType: string }) => /^video\/h265$/i.test(c.mimeType)))
      return { ok: false, why: "This build's WebRTC doesn't offer H.265", decoder };
  } catch {
    return { ok: false, why: "Couldn't read WebRTC's codecs", decoder };
  }
  return { ok: true, why: '', decoder };
}

// ---------------------------------------------------------------- hooks

const IDLE: LinkState = {
  phase: 'stopped',
  fps: 0,
  codec: null,
  decoder: null,
  size: null,
  bufferMs: null,
  rttMs: null,
  everPlayed: false,
  error: null,
  retryIn: null,
  attempt: 0,
};

/** A CamLink that runs while `src` is set. Returns its state and (video) stream. */
function useLink(
  src: string | null,
  kind: LinkKind,
  opts?: { firstFrameMs?: number; maxAttempts?: number; onState?: (s: LinkState) => void },
) {
  const [state, setState] = useState<LinkState>(IDLE);
  const [stream, setStream] = useState<Stream | null>(null);
  const cb = useRef(opts?.onState);
  useEffect(() => {
    cb.current = opts?.onState;
  });
  const firstFrameMs = opts?.firstFrameMs;
  const maxAttempts = opts?.maxAttempts;
  useEffect(() => {
    if (!src) return;
    const l = new CamLink({
      base: serverUrl(),
      src,
      kind,
      onState: (s) => {
        setState(s);
        cb.current?.(s);
      },
      onStream: (s) => setStream(s as Stream | null),
      firstFrameMs,
      maxAttempts,
    });
    l.start();
    return () => {
      l.stop();
      setStream(null);
    };
  }, [src, kind, firstFrameMs, maxAttempts]);
  return src ? { state, stream } : { state: IDLE, stream: null };
}

/** True while this screen is focused and the app is in the foreground. */
function useActive(onInactive: () => void) {
  const [focused, setFocused] = useState(false);
  const [fg, setFg] = useState(AppState.currentState === 'active');
  const cb = useRef(onInactive);
  useEffect(() => {
    cb.current = onInactive;
  });
  useFocusEffect(
    useCallback(() => {
      setFocused(true);
      return () => {
        setFocused(false);
        cb.current();
      };
    }, []),
  );
  useEffect(() => {
    const s = AppState.addEventListener('change', (st) => {
      setFg(st === 'active');
      if (st !== 'active') cb.current();
    });
    return () => s.remove();
  }, []);
  return focused && fg;
}

function useToast() {
  const [msg, setMsg] = useState<{ text: string; action?: { label: string; run: () => void } } | null>(null);
  const [op] = useState(() => new Animated.Value(0));
  const tid = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback(
    (text: string, ms = 2600, action?: { label: string; run: () => void }) => {
      setMsg({ text, action });
      Animated.timing(op, { toValue: 1, duration: 160, useNativeDriver: true }).start();
      if (tid.current) clearTimeout(tid.current);
      tid.current = setTimeout(
        () => Animated.timing(op, { toValue: 0, duration: 200, useNativeDriver: true }).start(() => setMsg(null)),
        ms,
      );
    },
    [op],
  );
  useEffect(() => () => void (tid.current && clearTimeout(tid.current)), []);
  return { msg, op, show };
}

async function hit(path: string, method: 'GET' | 'POST' = 'POST', timeoutMs = 6000): Promise<Response> {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(serverUrl() + path, { method, signal: ctrl.signal, headers: { 'Cache-Control': 'no-store' } });
  } finally {
    clearTimeout(t);
  }
}

// ---------------------------------------------------------------- cat alerts

type DetState = {
  enabled: boolean;
  cams?: Record<string, { name?: string; last_seen_ts?: number }>;
  last_alert?: { cam: string; name?: string; ts: number };
};

function ago(ts: number) {
  const m = Math.floor((Date.now() / 1000 - ts) / 60);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.floor(h / 24);
  return d === 1 ? 'yesterday' : `${d} days ago`;
}

function useDetector(active: boolean, toast: (m: string, ms?: number) => void) {
  const [det, setDet] = useState<DetState | null>(null);
  const [loaded, setLoaded] = useState(false);
  const busy = useRef(false);
  const [busyUi, setBusyUi] = useState(false);
  const poll = useCallback(async () => {
    if (busy.current) return;
    try {
      const r = await hit('/detect/state', 'GET');
      const j = r.ok ? await r.json() : null;
      if (!busy.current) setDet(j && typeof j.enabled === 'boolean' ? j : null);
    } catch {
      if (!busy.current) setDet(null);
    }
    setLoaded(true);
  }, []);
  useEffect(() => {
    if (!active) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    poll();
    const id = setInterval(poll, 15000);
    return () => clearInterval(id);
  }, [active, poll]);
  const toggle = async (on: boolean) => {
    if (!det || busy.current) return;
    const prev = det;
    busy.current = true;
    setBusyUi(true);
    setDet({ ...prev, enabled: on }); // optimistic, reverts on error
    try {
      const r = await hit(`/detect/toggle?on=${on ? 1 : 0}`);
      const j = r.ok ? await r.json() : null;
      if (!j || typeof j.enabled !== 'boolean') throw new Error('bad state');
      setDet(j);
      toast(on ? 'Cat alerts on' : 'Cat alerts off: detector paused', 1800);
    } catch {
      setDet(prev);
      toast("Couldn't reach the cat detector. Nothing changed.");
    }
    busy.current = false;
    setBusyUi(false);
  };
  let line = loaded ? 'Detector offline' : 'Connecting to detector…';
  if (det) {
    let best: { ts: number; name: string } | null = null;
    for (const [k, c] of Object.entries(det.cams ?? {}))
      if (c.last_seen_ts && (!best || c.last_seen_ts > best.ts)) best = { ts: c.last_seen_ts, name: NAMES[k] || c.name || k };
    if (!best && det.last_alert)
      best = { ts: det.last_alert.ts, name: NAMES[det.last_alert.cam] || det.last_alert.name || det.last_alert.cam };
    line = best ? `Cat seen ${ago(best.ts)} · ${best.name}` : 'No cats yet';
  }
  return { det, line, toggle, busy: busyUi };
}

// ---------------------------------------------------------------- PTZ

function usePtz() {
  const moving = useRef(false);
  const send = (p: string) => hit('/ptz/' + p).catch(() => undefined);
  const start = (dir: string) => {
    moving.current = true;
    Vibration.vibrate(12);
    send('move?dir=' + dir);
  };
  const stop = useCallback(() => {
    if (!moving.current) return;
    moving.current = false;
    send('stop');
  }, []);
  useEffect(() => stop, [stop]); // leaving the screen mid-move (the server also stops after 3 s)
  return { start, stop };
}

// ---------------------------------------------------------------- UI pieces

const DIRS: { dir: string; icon: string; area: object }[] = [
  { dir: 'up', icon: 'chevron-up', area: { top: 0, left: '33.3%' } },
  { dir: 'left', icon: 'chevron-left', area: { top: '33.3%', left: 0 } },
  { dir: 'right', icon: 'chevron-right', area: { top: '33.3%', right: 0 } },
  { dir: 'down', icon: 'chevron-down', area: { bottom: 0, left: '33.3%' } },
];

function PtzPad({ size, onClose, ptz }: { size: number; onClose: () => void; ptz: ReturnType<typeof usePtz> }) {
  const [on, setOn] = useState<string | null>(null);
  const cell = size / 3;
  return (
    <View style={[st.pad, { width: size, height: size, borderRadius: size / 2 }]}>
      {DIRS.map((d) => (
        <Pressable
          key={d.dir}
          accessibilityLabel={`Move ${d.dir}`}
          onPressIn={() => {
            setOn(d.dir);
            ptz.start(d.dir);
          }}
          onPressOut={() => {
            setOn(null);
            ptz.stop();
          }}
          style={[st.padBtn, { width: cell, height: cell, borderRadius: cell / 2 }, d.area, on === d.dir && st.padOn]}
        >
          <Icon name={d.icon} size={cell * 0.62} color={on === d.dir ? '#0B1530' : '#fff'} />
        </Pressable>
      ))}
      <Pressable
        onPress={onClose}
        accessibilityLabel="Close pan / tilt"
        style={[st.padBtn, st.padClose, { width: cell * 0.8, height: cell * 0.8, borderRadius: cell, top: cell * 1.1, left: cell * 1.1 }]}
      >
        <Icon name="close" size={cell * 0.38} color="#fff" />
      </Pressable>
    </View>
  );
}

function Chip({ icon, on, onPress, label, text }: { icon: string; on?: boolean; onPress: () => void; label: string; text?: string }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={label}
      accessibilityState={{ selected: !!on }}
      hitSlop={4}
      style={({ pressed }) => [
        st.chip,
        text ? { width: undefined, paddingHorizontal: 12 } : null,
        on && st.chipOn,
        pressed && { opacity: 0.8 },
      ]}
    >
      <Icon name={icon} size={20} color={on ? '#0B1530' : '#fff'} />
      {text ? <Text style={{ color: on ? '#0B1530' : '#fff', fontWeight: '700', fontSize: 13 }}>{text}</Text> : null}
    </Pressable>
  );
}

function IconBtn({ icon, onPress, label, busy }: { icon: string; onPress: () => void; label: string; busy?: boolean }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={label}
      hitSlop={2}
      style={({ pressed }) => [st.ib, pressed && { backgroundColor: 'rgba(255,255,255,0.18)' }]}
    >
      {busy ? <ActivityIndicator size="small" color="#fff" /> : <Icon name={icon} size={22} color="#fff" />}
    </Pressable>
  );
}

function badgeOf(s: LinkState, mode?: string) {
  const tone = s.fps >= 4 ? 'ok' : s.fps > 0 ? 'slow' : 'bad';
  const text =
    s.fps > 0
      ? `${s.fps} fps${mode ? ` · ${mode}` : ''}`
      : s.phase === 'retrying'
        ? `retry in ${s.retryIn}s`
        : s.phase === 'error'
          ? 'offline'
          : s.phase === 'stalled'
            ? 'stalled'
            : 'connecting';
  return { tone, text };
}

function Badge({ s, mode }: { s: LinkState; mode?: string }) {
  const t = useTheme();
  const b = badgeOf(s, mode);
  const dot = b.tone === 'ok' ? t.ok : b.tone === 'slow' ? t.warn : t.bad;
  return (
    <View style={[st.badge, b.tone === 'bad' && { backgroundColor: 'rgba(127,29,29,0.65)' }]}>
      <View style={[st.badgeDot, { backgroundColor: dot }]} />
      <Text style={[st.badgeText, b.tone === 'bad' && { color: '#FECACA' }]} numberOfLines={1}>
        {b.text}
      </Text>
    </View>
  );
}

type CardProps = {
  id: CamId;
  name: string;
  link: { state: LinkState; stream: Stream | null };
  mode?: string;
  compact?: boolean;
  fullscreen?: boolean;
  onPress?: () => void;
  children?: ReactNode; // overlays (buttons, pad)
  style?: object;
};

function CamCard({ name, link, mode, compact, fullscreen, onPress, children, style }: CardProps) {
  const { state, stream } = link;
  const showMsg = !stream || state.fps === 0;
  return (
    <Pressable onPress={onPress} style={[fullscreen ? st.fsCard : st.card, compact && st.cardCompact, style]}>
      {stream ? <RTCView streamURL={stream.toURL()} objectFit="contain" zOrder={0} style={StyleSheet.absoluteFill} /> : null}
      {showMsg && !compact ? (
        <View style={st.msg} pointerEvents="none">
          {state.phase === 'error' ? (
            <Icon name="video-off-outline" size={28} color="rgba(255,255,255,0.6)" />
          ) : (
            <ActivityIndicator color="rgba(255,255,255,0.7)" />
          )}
          <Text style={st.msgText}>
            {state.phase === 'retrying'
              ? `${state.error ?? 'Disconnected'} · retrying in ${state.retryIn}s`
              : state.phase === 'error'
                ? (state.error ?? 'Offline')
                : state.phase === 'stalled'
                  ? 'Stalled: waiting for frames…'
                  : state.attempt > 1
                    ? 'Reconnecting…'
                    : 'Connecting…'}
          </Text>
        </View>
      ) : null}
      <View style={[st.tl, compact && { top: 5, left: 7, right: 7 }]} pointerEvents="none">
        <Text style={[st.name, compact && { fontSize: 12 }]} numberOfLines={1}>
          {name}
        </Text>
        {!compact ? <Badge s={state} mode={mode} /> : null}
      </View>
      {!compact ? children : null}
    </Pressable>
  );
}

function ActButton({
  state,
  icon,
  t1,
  t2,
  onPress,
  onPressIn,
  onPressOut,
}: {
  state: 'off' | 'on' | 'busy' | 'talking' | 'blocked';
  icon: string;
  t1: string;
  t2: string;
  onPress?: () => void;
  onPressIn?: () => void;
  onPressOut?: () => void;
}) {
  const t = useTheme();
  const bg =
    state === 'talking'
      ? '#B91C1C'
      : state === 'on'
        ? t.dark
          ? 'rgba(6,78,59,0.8)'
          : '#D1FAE5'
        : state === 'blocked'
          ? t.warnSoft
          : t.card;
  const icBg = state === 'talking' ? '#fff' : state === 'on' ? t.ok : t.cardAlt;
  const icFg = state === 'talking' ? '#B91C1C' : state === 'on' ? '#052E22' : t.textDim;
  const fg = state === 'talking' ? '#fff' : t.text;
  const fg2 = state === 'talking' ? '#FECACA' : state === 'on' ? (t.dark ? '#A7F3D0' : '#065F46') : t.textDim;
  return (
    <Pressable
      onPress={onPress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      android_ripple={{ color: 'rgba(127,127,127,0.2)', foreground: true }}
      style={[st.act, { backgroundColor: bg, borderColor: state === 'off' ? t.border : 'transparent' }]}
    >
      <View style={[st.actIc, { backgroundColor: icBg }]}>
        {state === 'busy' ? <ActivityIndicator size="small" color={icFg} /> : <Icon name={icon} size={22} color={icFg} />}
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ color: fg, fontWeight: '700', fontSize: 15 }} numberOfLines={1}>
          {t1}
        </Text>
        <Text style={{ color: fg2, fontSize: 12 }} numberOfLines={1}>
          {t2}
        </Text>
      </View>
    </Pressable>
  );
}

// ---------------------------------------------------------------- screen

export default function CamsScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const params = useRoute().params as CamsParams;
  const insets = useSafeAreaInsets();
  const win = useWindowDimensions();
  const toast = useToast();
  const showToast = toast.show;
  const [hd] = useState(hdSupport);
  const webrtcOk = !!NativeModules.WebRTCModule;
  const [tab, setTab] = useState<CamsTab>(params?.tab ?? 'live');
  // a YouTube link shared into the app (App.tsx navigates here with it): the Music tab plays it once
  const [shared, setShared] = useState<string | null>(params?.share ?? null);
  const shareId = params?.shareId;
  useEffect(() => {
    if (!shareId || !params?.share) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTab('music');
    setShared(params.share);
  }, [shareId, params?.share]);
  const clearShared = useCallback(() => setShared(null), []);
  // Replay decodes cat3hd's H.265 itself when the phone has a hardware HEVC decoder for 2304x1296, else the laptop
  // transcodes it to H.264 (the page's &v=avc)
  const [recHevc] = useState(() => {
    try {
      return !!PcNative?.camsHardware().h265?.hd;
    } catch {
      return false;
    }
  });

  // CP Plus mode: HD by default when the phone can (nothing saved: every open starts on HD, like the page)
  const [cat3Hd, setCat3Hd] = useState(hd.ok);
  const [focus, setFocus] = useState<CamId | null>(null);
  const [full, setFull] = useState<CamId | null>(null);
  const [padOpen, setPadOpen] = useState(false);
  const [menu, setMenu] = useState(false);
  const [camMic, setCamMic] = useState(false);
  const [listen, setListen] = useState(false);
  const [talk, setTalk] = useState<'off' | 'held' | 'latched'>('off');
  const [snapBusy, setSnapBusy] = useState<CamId | null>(null);
  const talkPress = useRef<{ at: number; started: boolean; wasOn: boolean; released: boolean }>({
    at: 0,
    started: false,
    wasOn: false,
    released: false,
  });
  const [now, setNow] = useState(() => Date.now());
  const [talkSince, setTalkSince] = useState(0);
  const ptz = usePtz();
  const stopPtz = ptz.stop;

  // background / leaving: everything closes, and audio is off when you come back (everything starts muted)
  const active = useActive(() => {
    setCamMic(false);
    setListen(false);
    setTalk('off');
    setPadOpen(false);
    setFull(null);
    stopPtz();
  });

  // live video only on the Live tab (frees the decoders and the link for Replay); Listen/Talk keep running
  const run = active && webrtcOk && tab === 'live';
  const audioRun = active && webrtcOk;
  const links = {
    cat: useLink(run ? 'cat' : null, 'video'),
    cat2: useLink(run ? 'cat2' : null, 'video'),
    // HD gets one try with a deadline, then SD; SD retries forever
    cat3: useLink(run ? (cat3Hd ? HD_STREAM : 'cat3') : null, 'video', {
      firstFrameMs: cat3Hd ? HD_WAIT_MS : undefined,
      maxAttempts: cat3Hd ? 2 : undefined,
      onState: (s) => {
        // HD that shows nothing: SD
        if (!cat3Hd || s.phase !== 'error') return;
        setCat3Hd(false);
        showToast("HD didn't start, so this is the standard stream.", 4000);
      },
    }),
  };
  useLink(audioRun && camMic ? CAM_MIC_STREAM : null, 'audio', {
    onState: (s) => {
      if (s.phase !== 'retrying' || s.attempt <= 2) return;
      setCamMic(false);
      showToast(`Cam's mic didn't connect (${s.error ?? 'error'}).`, 3500);
    },
  });
  const ear = useLink(audioRun && listen ? LAPTOP_MIC_STREAM : null, 'audio');
  const mic = useLink(audioRun && talk !== 'off' ? TALK_STREAM : null, 'talk', {
    maxAttempts: 2,
    onState: (s) => {
      if (s.phase !== 'error') return;
      setTalk('off');
      showToast(`Talk dropped: ${s.error ?? 'connection failed'}. Try again.`, 3500);
    },
  });
  const det = useDetector(active, toast.show);

  // crash guard (CamsWebrtc): a marker while live video starts, cleared after 15 s of it or when it stops. If the
  // app dies meanwhile, the next start turns safe mode on (react-native-webrtc's defaults).
  useEffect(() => {
    if (!run) return;
    try {
      PcNative?.camsMarkOpening(true);
    } catch {
      return;
    }
    const id = setTimeout(() => PcNative?.camsMarkOpening(false), 15000);
    return () => {
      clearTimeout(id);
      PcNative?.camsMarkOpening(false);
    };
  }, [run]);
  const [safeMode, setSafeMode] = useState(() => {
    try {
      return !!PcNative?.camsStatus().settings.safeMode;
    } catch {
      return false;
    }
  });
  useEffect(() => {
    try {
      if (!PcNative?.camsStatus().crashedWhileOpening) return;
      PcNative.camsCrashSeen();
    } catch {
      return;
    }
    showToast('Cat cams crashed last time, so it now uses safe mode (default WebRTC settings). Menu → Leave safe mode.', 7000);
  }, [showToast]);
  const toggleSafe = () => {
    setMenu(false);
    try {
      PcNative?.camsSetSetting('safeMode', !safeMode);
    } catch {
      return;
    }
    setSafeMode(!safeMode);
    showToast(safeMode ? 'Safe mode off from the next app start.' : 'Safe mode on from the next app start.', 4000);
  };

  // keep the screen on while watching
  useEffect(() => {
    if (!active) return;
    PcNative?.camsKeepAwake(true).catch(() => undefined);
    return () => void PcNative?.camsKeepAwake(false).catch(() => undefined);
  }, [active]);

  // fullscreen: landscape + hidden bars; back leaves it
  useEffect(() => {
    if (!full) return;
    PcNative?.camsSetFullscreen(true).catch(() => undefined);
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setFull(null);
      return true;
    });
    return () => {
      sub.remove();
      PcNative?.camsSetFullscreen(false).catch(() => undefined);
    };
  }, [full]);

  useEffect(() => {
    if (talk === 'off') return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [talk]);

  const cat3Mode = `${cat3Hd ? 'HD' : 'SD'}·WebRTC`;
  const liveCount = CAMS.filter((c) => links[c.id].state.fps > 0).length;

  const snapshot = async (id: CamId) => {
    if (!PcNative || snapBusy) return;
    setSnapBusy(id);
    try {
      // CP Plus: always cat3 (go2rtc's frame.jpeg fails on cat3hd)
      const uri = await PcNative.camsSaveSnapshot(id, NAMES[id] ?? id);
      toast.show('Saved to Pictures/PC Control', 4500, { label: 'Share', run: () => PcNative?.camsShare(uri).catch(() => undefined) });
    } catch (e) {
      toast.show(`Snapshot failed: ${(e as Error)?.message ?? e}`, 4000);
    }
    setSnapBusy(null);
  };

  const toggleHd = () => {
    if (!cat3Hd && !hd.ok) return toast.show(`HD needs hardware H.265 over WebRTC. ${hd.why}.`, 5000);
    setCat3Hd(!cat3Hd);
    toast.show(cat3Hd ? 'Back to the standard stream' : 'Switching to HD (3MP)', 1800);
  };

  // Talk: hold to talk (released = off), or a quick tap to latch it on until the next tap.
  const micAllowed = async () => {
    const p = PermissionsAndroid.PERMISSIONS.RECORD_AUDIO;
    if (await PermissionsAndroid.check(p)) return true;
    const r = await PermissionsAndroid.request(p);
    if (r === PermissionsAndroid.RESULTS.GRANTED) toast.show('Microphone allowed. Hold Talk to speak.', 3000);
    else toast.show('Talk needs the microphone permission (Android settings → Apps → PC Control).', 4500);
    return false; // the dialog took the press: start on the next one
  };
  const talkIn = async () => {
    const wasOn = talk !== 'off';
    const press: { at: number; started: boolean; wasOn: boolean; released: boolean } = {
      at: Date.now(),
      started: false,
      wasOn,
      released: false,
    };
    talkPress.current = press;
    if (wasOn) return;
    if (!(await micAllowed())) return;
    press.started = true;
    Vibration.vibrate(20);
    setTalkSince(Date.now());
    setNow(Date.now());
    setTalk(press.released ? 'latched' : 'held'); // released before the permission check answered: a tap
  };
  const talkOut = () => {
    const p = talkPress.current;
    p.released = true;
    if (p.wasOn) return setTalk('off');
    if (!p.started) return;
    if (Date.now() - p.at < 400) setTalk('latched');
    else setTalk('off');
  };

  const openWeb = (hash = '') => {
    setMenu(false);
    Linking.openURL(serverUrl() + WEB_PATH + hash);
  };

  const cardButtons = (id: CamId, isFull: boolean) => (
    <>
      <View style={st.tr}>
        <IconBtn icon="camera-outline" label="Save snapshot" busy={snapBusy === id} onPress={() => snapshot(id)} />
        <IconBtn
          icon={isFull ? 'fullscreen-exit' : 'fullscreen'}
          label={isFull ? 'Exit fullscreen' : 'Fullscreen'}
          onPress={() => setFull(isFull ? null : id)}
        />
        {!isFull ? (
          <IconBtn
            icon={focus === id ? 'arrow-collapse' : 'arrow-expand'}
            label={focus === id ? 'Back to all cameras' : 'Focus this camera'}
            onPress={() => setFocus(focus === id ? null : id)}
          />
        ) : null}
      </View>
      {id === 'cat3' ? (
        <>
          <View style={[st.bl, isFull && { left: 16 + insets.left, bottom: 16 }]}>
            <Chip
              icon={camMic ? 'volume-high' : 'volume-off'}
              on={camMic}
              label={camMic ? "Cam's mic: on" : "Cam's mic: off"}
              onPress={() => setCamMic(!camMic)}
            />
            <Chip icon="high-definition" on={cat3Hd} label={cat3Hd ? 'HD on' : 'HD off'} onPress={toggleHd} />
            <Chip icon="arrow-all" on={padOpen} label="Pan / tilt" onPress={() => setPadOpen(!padOpen)} />
          </View>
          {padOpen ? (
            <View style={[st.padWrap, isFull && { right: 24 + insets.right, bottom: 24 }]}>
              <PtzPad size={isFull ? 180 : 150} ptz={ptz} onClose={() => setPadOpen(false)} />
            </View>
          ) : null}
        </>
      ) : null}
    </>
  );

  // ---- fullscreen: one camera, nothing else
  if (full) {
    const c = CAMS.find((x) => x.id === full)!;
    return (
      <View style={{ flex: 1, backgroundColor: '#000' }}>
        <CamCard id={c.id} name={c.name} link={links[c.id]} mode={c.id === 'cat3' ? cat3Mode : undefined} fullscreen>
          {cardButtons(c.id, true)}
        </CamCard>
        <Toast toast={toast} bottom={24} />
      </View>
    );
  }

  const ordered = focus ? [CAMS.find((c) => c.id === focus)!, ...CAMS.filter((c) => c.id !== focus)] : CAMS;
  const talkSecs = talkSince ? Math.max(0, Math.floor((now - talkSince) / 1000)) : 0;
  const talkLive = mic.state.phase === 'live';
  const big = focus ? links[focus].state : null;
  const thumbW = (win.width - space(4) * 2 - space(2.5)) / 2;
  const showBar = tab === 'live' || listen || talk !== 'off';

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      {/* header */}
      <View style={[st.header, { paddingTop: insets.top + space(2), borderBottomColor: t.border }]}>
        <Pressable onPress={() => nav.goBack()} hitSlop={8} accessibilityLabel="Back" style={st.hBtn}>
          <Icon name="arrow-left" size={24} color={t.text} />
        </Pressable>
        <View style={[st.seg, { backgroundColor: t.cardAlt }]} accessibilityRole="tablist">
          {TABS.map((x) => {
            const on = tab === x.id;
            return (
              <Pressable
                key={x.id}
                onPress={() => setTab(x.id)}
                accessibilityRole="tab"
                accessibilityState={{ selected: on }}
                style={[st.segBtn, on && { backgroundColor: t.card }]}
              >
                {x.id === 'live' && on ? (
                  <View style={[st.liveDot, { backgroundColor: liveCount ? '#EF4444' : t.textFaint }]} />
                ) : (
                  <Icon name={x.icon} size={15} color={on ? t.text : t.textDim} />
                )}
                <Text style={{ color: on ? t.text : t.textDim, fontWeight: '700', fontSize: 13 }} numberOfLines={1}>
                  {x.id === 'live' && on ? `${x.label} ${liveCount}/${CAMS.length}` : x.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
        <Pressable onPress={() => setMenu(true)} hitSlop={8} accessibilityLabel="More" style={st.hBtn}>
          <Icon name="dots-vertical" size={24} color={t.text} />
        </Pressable>
      </View>

      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ padding: space(4), paddingBottom: (showBar ? 110 : space(6)) + insets.bottom, gap: space(3) }}
      >
        {tab === 'replay' ? <ReplayTab visible={active} hevcOk={recHevc} toast={toast.show} /> : null}
        {tab === 'music' ? <MusicTab visible={active} toast={toast.show} shared={shared} onSharedDone={clearShared} /> : null}
        {tab !== 'live' ? null : (
          <>
            {!webrtcOk ? (
              <Banner tone="bad" title="Native player unavailable">
                This build has no WebRTC module. Use the web page from the menu.
              </Banner>
            ) : null}
            {/* cat alerts */}
            <View style={st.detRow}>
              <Pressable
                onPress={() => det.det && det.toggle(!det.det.enabled)}
                disabled={!det.det || det.busy}
                style={[
                  st.detTog,
                  {
                    backgroundColor: det.det?.enabled ? t.okSoft : t.card,
                    borderColor: det.det?.enabled ? t.ok + '66' : t.border,
                    opacity: det.det ? 1 : 0.5,
                  },
                ]}
              >
                <Icon name="cctv" size={18} color={det.det?.enabled ? t.ok : t.textDim} />
                <Text style={{ color: det.det?.enabled ? t.text : t.textDim, fontWeight: '700', fontSize: 14 }}>Cat alerts</Text>
                <Switch
                  value={!!det.det?.enabled}
                  onValueChange={(v) => det.toggle(v)}
                  disabled={!det.det || det.busy}
                  trackColor={{ true: t.ok, false: t.border }}
                  thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'}
                  style={{ marginVertical: -8 }}
                />
              </Pressable>
              <Text style={{ color: t.textDim, fontSize: 13, flex: 1 }} numberOfLines={2}>
                {det.line}
              </Text>
            </View>

            {/* cameras */}
            {focus ? (
              <>
                <CamCard
                  id={focus}
                  name={NAMES[focus]}
                  link={links[focus]}
                  mode={focus === 'cat3' ? cat3Mode : undefined}
                  onPress={() => setFocus(null)}
                >
                  {cardButtons(focus, false)}
                </CamCard>
                {big ? <StreamInfo s={big} hdDecoder={focus === 'cat3' && cat3Hd ? hd.decoder : null} /> : null}
                <View style={{ flexDirection: 'row', gap: space(2.5) }}>
                  {ordered.slice(1).map((c) => (
                    <CamCard
                      key={c.id}
                      id={c.id}
                      name={c.name}
                      link={links[c.id]}
                      compact
                      style={{ width: thumbW }}
                      onPress={() => setFocus(c.id)}
                    />
                  ))}
                </View>
              </>
            ) : (
              CAMS.map((c) => (
                <CamCard
                  key={c.id}
                  id={c.id}
                  name={c.name}
                  link={links[c.id]}
                  mode={c.id === 'cat3' ? cat3Mode : undefined}
                  onPress={() => setFocus(c.id)}
                >
                  {cardButtons(c.id, false)}
                </CamCard>
              ))
            )}
            {!hd.ok && webrtcOk ? (
              <Text style={{ color: t.textFaint, fontSize: 12, textAlign: 'center' }}>Food bowl HD unavailable: {hd.why}.</Text>
            ) : null}
          </>
        )}
      </ScrollView>

      {/* Listen / Talk (on Replay / Music only while one of them is on, like the page) */}
      {showBar ? (
        <View style={[st.bar, { paddingBottom: insets.bottom + space(3), backgroundColor: t.bg + 'F2', borderTopColor: t.border }]}>
          <ActButton
            state={!listen ? 'off' : ear.state.phase === 'live' ? 'on' : ear.state.phase === 'error' ? 'blocked' : 'busy'}
            icon={listen ? 'volume-high' : 'volume-off'}
            t1={listen ? 'Listening' : 'Listen'}
            t2={
              !listen
                ? 'Laptop mic is off'
                : ear.state.phase === 'live'
                  ? 'Tap to stop'
                  : ear.state.phase === 'error'
                    ? (ear.state.error ?? 'Failed')
                    : 'Connecting…'
            }
            onPress={() => setListen(!listen)}
          />
          <ActButton
            state={talk === 'off' ? 'off' : talkLive ? 'talking' : 'busy'}
            icon={talk === 'off' ? 'microphone-off' : 'microphone'}
            t1={talk === 'off' ? 'Talk' : talkLive ? 'Talking' : 'Connecting…'}
            t2={
              talk === 'off'
                ? 'Hold to talk · tap to latch'
                : `${Math.floor(talkSecs / 60)}:${String(talkSecs % 60).padStart(2, '0')} · ${talk === 'held' ? 'release to stop' : 'tap to stop'}`
            }
            onPressIn={talkIn}
            onPressOut={talkOut}
          />
        </View>
      ) : null}

      <Toast toast={toast} bottom={insets.bottom + (showBar ? 96 : 24)} />

      <Modal visible={menu} transparent animationType="fade" onRequestClose={() => setMenu(false)}>
        <Pressable style={st.menuBg} onPress={() => setMenu(false)}>
          <View style={[st.menu, { top: insets.top + 52, backgroundColor: t.card, borderColor: t.border }]}>
            {[
              { icon: 'web', label: 'Open the web page', run: () => openWeb(tab === 'live' ? '' : '#' + tab) },
              { icon: 'shield-check-outline', label: safeMode ? 'Leave safe mode' : 'Safe mode (WebRTC defaults)', run: toggleSafe },
            ].map((m) => (
              <Pressable key={m.label} onPress={m.run} android_ripple={{ color: t.accentSoft }} style={st.menuItem}>
                <Icon name={m.icon} size={20} color={t.textDim} />
                <Text style={{ color: t.text, fontSize: 15 }}>{m.label}</Text>
              </Pressable>
            ))}
          </View>
        </Pressable>
      </Modal>
    </View>
  );
}

function StreamInfo({ s, hdDecoder }: { s: LinkState; hdDecoder: string | null }) {
  const t = useTheme();
  const bits = [
    s.codec,
    s.size,
    s.decoder ?? hdDecoder,
    s.bufferMs != null ? `buffer ${s.bufferMs} ms` : null,
    s.rttMs != null ? `rtt ${s.rttMs} ms` : null,
  ].filter(Boolean);
  if (!bits.length) return null;
  return (
    <Text style={{ color: t.textFaint, fontSize: 11.5, fontFamily: t.mono, marginTop: -space(1) }} numberOfLines={2}>
      {bits.join(' · ')}
    </Text>
  );
}

function Toast({ toast, bottom }: { toast: ReturnType<typeof useToast>; bottom: number }) {
  const t = useTheme();
  if (!toast.msg) return null;
  const a = toast.msg.action;
  return (
    <Animated.View
      style={[st.toast, { bottom, opacity: toast.op, backgroundColor: t.dark ? '#262B33' : '#1F2430' }]}
      pointerEvents="box-none"
    >
      <Icon name="information-outline" size={18} color="#8AB4FF" />
      <Text style={{ color: '#ECEEF1', fontSize: 14, flexShrink: 1 }}>{toast.msg.text}</Text>
      {a ? <Button label={a.label} kind="ghost" compact onPress={a.run} /> : null}
    </Animated.View>
  );
}

const st = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: space(2),
    paddingBottom: space(2),
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  hBtn: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 22 },
  seg: { flex: 1, flexDirection: 'row', padding: 3, borderRadius: 99, gap: 2 },
  segBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    height: 34,
    borderRadius: 99,
    paddingHorizontal: 6,
  },
  liveDot: { width: 8, height: 8, borderRadius: 4 },
  detRow: { flexDirection: 'row', alignItems: 'center', gap: space(3) },
  detTog: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    height: 42,
    paddingLeft: 12,
    paddingRight: 4,
    borderRadius: 99,
    borderWidth: StyleSheet.hairlineWidth,
  },
  card: { aspectRatio: 16 / 9, borderRadius: radius.lg, overflow: 'hidden', backgroundColor: '#000' },
  cardCompact: { borderRadius: radius.md },
  fsCard: { flex: 1, backgroundColor: '#000' },
  msg: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', gap: 8, padding: 16 },
  msgText: { color: 'rgba(255,255,255,0.7)', fontSize: 13, textAlign: 'center' },
  tl: { position: 'absolute', top: 10, left: 12, right: 150, flexDirection: 'row', alignItems: 'center', gap: 8 },
  name: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
    textShadowColor: 'rgba(0,0,0,0.7)',
    textShadowRadius: 3,
    textShadowOffset: { width: 0, height: 1 },
    flexShrink: 1,
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 22,
    paddingHorizontal: 8,
    borderRadius: 99,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  badgeDot: { width: 7, height: 7, borderRadius: 4 },
  badgeText: { color: 'rgba(255,255,255,0.8)', fontSize: 11, fontFamily: 'monospace' },
  tr: { position: 'absolute', top: 4, right: 4, flexDirection: 'row', gap: 2 },
  ib: { width: 44, height: 44, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  bl: { position: 'absolute', bottom: 8, left: 8, flexDirection: 'row', gap: 6 },
  chip: {
    width: 44,
    height: 44,
    borderRadius: 22,
    flexDirection: 'row',
    gap: 6,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(20,23,28,0.62)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.18)',
  },
  chipOn: { backgroundColor: '#8AB4FF', borderColor: 'transparent' },
  padWrap: { position: 'absolute', right: 10, bottom: 10 },
  pad: { backgroundColor: 'rgba(15,17,21,0.6)', borderWidth: StyleSheet.hairlineWidth, borderColor: 'rgba(255,255,255,0.2)' },
  padBtn: { position: 'absolute', alignItems: 'center', justifyContent: 'center' },
  padOn: { backgroundColor: '#8AB4FF' },
  padClose: { backgroundColor: 'rgba(255,255,255,0.12)' },
  bar: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    flexDirection: 'row',
    gap: space(2.5),
    paddingHorizontal: space(4),
    paddingTop: space(3),
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  act: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    height: 60,
    paddingHorizontal: 12,
    borderRadius: 18,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  actIc: { width: 40, height: 40, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  toast: {
    position: 'absolute',
    alignSelf: 'center',
    maxWidth: '92%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 14,
    elevation: 6,
  },
  menuBg: { flex: 1 },
  menu: {
    position: 'absolute',
    right: space(3),
    minWidth: 230,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    paddingVertical: 6,
    elevation: 10,
    overflow: 'hidden',
  },
  menuItem: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, height: 48 },
});
