// Music on the laptop speakers (cam-music service, /music/api; API in ~/remote-pc/music/music.py). Port of the
// /cams/ page's Music tab. The server owns all state: this only draws what it last said, polling every 2 s while
// the tab is visible (1 s while loading) and not at all otherwise. Responses can overlap: a poll draws only if
// nothing newer was drawn, and a command's answer takes its sequence number when it arrives.
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Banner, Button, Icon } from '../../components/ui';
import { serverUrl } from '../../lib/api';
import { radius, space, useTheme } from '../../theme';

export type MusicStatus = {
  state: 'idle' | 'loading' | 'playing' | 'paused' | 'error' | string;
  title: string | null;
  url: string | null;
  position: number | null;
  duration: number | null;
  live: boolean;
  loop: boolean;
  volume: number | null;
  muted: boolean;
  last_error: string | null;
};
type Recent = { id: string; url: string; title: string | null; live: boolean; ts: number };

const STATES: Record<string, string> = { idle: 'Idle', loading: 'Loading…', playing: 'Playing', paused: 'Paused', error: 'Error' };

export async function musicFetch<T = MusicStatus>(path: string, body?: object): Promise<T> {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), body === undefined ? 8000 : 70000); // play resolves the video first
  try {
    const r = await fetch(serverUrl() + '/music/api/' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? { 'Cache-Control': 'no-store' } : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    const j = await r.json().catch(() => null);
    if (!r.ok || !j) throw new Error(j?.error || "Couldn't reach the music service");
    return j as T;
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') throw new Error('The music service timed out');
    throw e;
  } finally {
    clearTimeout(t);
  }
}

const fmtTime = (t: number) => {
  const s = Math.floor(t);
  const h = Math.floor(s / 3600);
  const m = Math.floor(s / 60) % 60;
  const p = (n: number) => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(s % 60)}` : `${m}:${p(s % 60)}`;
};

/**
 * @param visible  poll only while true (tab shown, screen focused, app in the foreground)
 * @param shared   a YouTube link shared into the app ("Share → PC Control"): played once, then onSharedDone()
 */
export function MusicTab({
  visible,
  toast,
  shared,
  onSharedDone,
}: {
  visible: boolean;
  toast: (m: string, ms?: number) => void;
  shared: string | null;
  onSharedDone: () => void;
}) {
  const t = useTheme();
  const [s, setS] = useState<MusicStatus | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [recent, setRecent] = useState<Recent[]>([]);
  const [url, setUrl] = useState('');
  const [formErr, setFormErr] = useState('');
  const [playing, setPlaying] = useState(false); // a play request in flight
  const [vol, setVol] = useState<number | null>(null); // while dragging / sending
  const seq = useRef(0);
  const shown = useRef(0);
  const recentKey = useRef<string | null>(null);
  const volWant = useRef<number | null>(null);
  const volBusy = useRef(false);
  const volTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loadRecent = useCallback(async () => {
    try {
      const j = await musicFetch<{ recent: Recent[] }>('recent');
      setRecent(Array.isArray(j.recent) ? j.recent : []);
    } catch {
      /* keep the old list */
    }
  }, []);

  const apply = useCallback(
    (n: number, st: MusicStatus | null) => {
      if (n < shown.current) return;
      shown.current = n;
      setS(st);
      setLoaded(true);
      const key = st && st.state === 'playing' ? st.url : recentKey.current; // a video joins Recent once it plays
      if (key !== recentKey.current) {
        recentKey.current = key;
        loadRecent();
      }
    },
    [loadRecent],
  );
  const poll = useCallback(async () => {
    const n = ++seq.current;
    try {
      apply(n, await musicFetch('status'));
    } catch {
      apply(n, null);
    }
  }, [apply]);
  const cmd = async (path: string, body: object = {}) => {
    try {
      const st = await musicFetch(path, body);
      apply(++seq.current, st);
    } catch (e) {
      toast((e as Error).message);
      poll();
    }
  };
  const play = useCallback(
    async (link: string) => {
      setFormErr('');
      setPlaying(true);
      try {
        const st = await musicFetch('play', { url: link });
        apply(++seq.current, st);
        setUrl('');
      } catch (e) {
        setFormErr((e as Error).message);
      }
      setPlaying(false);
    },
    [apply],
  );

  // the one status loop: only while visible
  const state = s?.state;
  const stateRef = useRef(state);
  useEffect(() => {
    stateRef.current = state;
  }, [state]);
  useEffect(() => {
    if (!visible) return;
    let stop = false;
    let tid: ReturnType<typeof setTimeout> | null = null;
    const step = async () => {
      await poll();
      if (!stop) tid = setTimeout(step, stateRef.current === 'loading' ? 1000 : 2000);
    };
    recentKey.current = null;
    step();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadRecent();
    return () => {
      stop = true;
      if (tid) clearTimeout(tid);
    };
  }, [visible, poll, loadRecent]);

  // shared from YouTube: play it
  useEffect(() => {
    if (!shared || !visible) return;
    onSharedDone();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    play(shared);
  }, [shared, visible, play, onSharedDone]);

  // volume: the newest value 200 ms after the last change, one request at a time
  const sendVol = async () => {
    if (volBusy.current || volWant.current == null) return;
    const percent = volWant.current;
    volWant.current = null;
    volBusy.current = true;
    let st: MusicStatus | null = null;
    try {
      st = await musicFetch('volume', { percent });
    } catch (e) {
      toast((e as Error).message);
    }
    volBusy.current = false;
    if (volWant.current != null) return sendVol();
    setVol(null);
    if (st) apply(++seq.current, st);
    else poll();
  };
  const onVol = (v: number) => {
    const p = Math.round(v);
    volWant.current = p;
    setVol(p);
    if (volTimer.current) clearTimeout(volTimer.current);
    volTimer.current = setTimeout(sendVol, 200);
  };

  const up = !!s;
  const st = s?.state ?? 'idle';
  const active = ['loading', 'playing', 'paused'].includes(st);
  const pos =
    !active || !s
      ? ''
      : s.live
        ? st === 'loading'
          ? ''
          : 'LIVE'
        : s.position != null
          ? fmtTime(s.position) + (s.duration ? ' / ' + fmtTime(s.duration) : '')
          : '';
  const title = up && (s.title || (active ? 'Finding the video…' : ''));
  const note = st === 'loading' ? s?.last_error : null;
  const err = formErr || (st === 'error' ? s?.last_error || 'Playback failed' : '');
  const tone = !up
    ? t.bad
    : st === 'playing'
      ? t.ok
      : st === 'loading'
        ? t.warn
        : st === 'paused'
          ? t.accent
          : st === 'error'
            ? t.bad
            : t.textDim;
  const hasVol = up && s.volume != null;
  const shownVol = vol ?? (hasVol ? s.volume! : 0);
  const progress = active && s && !s.live && s.position != null && s.duration ? Math.min(1, s.position / s.duration) : null;

  return (
    <View style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <TextInput
          value={url}
          onChangeText={(v) => {
            setUrl(v);
            if (formErr) setFormErr('');
          }}
          placeholder="Paste a YouTube link"
          placeholderTextColor={t.textFaint}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          returnKeyType="go"
          onSubmitEditing={() => url.trim() && play(url.trim())}
          style={[st_.input, { color: t.text, backgroundColor: t.card, borderColor: t.border }]}
        />
        <Button label="Play" icon="play" busy={playing} disabled={!url.trim()} onPress={() => play(url.trim())} />
      </View>
      <Text style={{ color: t.textFaint, fontSize: 12, marginTop: -space(1) }}>Or in YouTube: Share → PC Control.</Text>
      {err ? <Banner tone="bad" title={err} /> : null}

      <View style={[st_.card, { backgroundColor: t.card, borderColor: t.border }]}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <View style={[st_.dot, { backgroundColor: tone }]} />
          <Text style={{ color: tone, fontWeight: '700', fontSize: 13 }}>
            {up ? STATES[st] || st : loaded ? 'Music service offline' : 'Connecting…'}
          </Text>
          {st === 'loading' ? <ActivityIndicator size="small" color={t.warn} /> : null}
          <Text style={{ marginLeft: 'auto', color: t.textDim, fontFamily: t.mono, fontSize: 13 }}>{pos}</Text>
        </View>
        <Text
          style={{ color: title ? t.text : t.textDim, fontSize: 17, fontWeight: title ? '600' : '500', lineHeight: 22 }}
          numberOfLines={3}
        >
          {title || 'Nothing playing'}
        </Text>
        {progress != null ? (
          <View style={{ height: 4, borderRadius: 2, backgroundColor: t.cardAlt, overflow: 'hidden' }}>
            <View style={{ width: `${progress * 100}%`, height: 4, backgroundColor: t.accent }} />
          </View>
        ) : null}
        {note ? <Text style={{ color: t.warn, fontSize: 13 }}>{note}</Text> : null}
        <View style={{ flexDirection: 'row', gap: space(2) }}>
          <Button
            label={st === 'paused' ? 'Resume' : 'Pause'}
            icon={st === 'paused' ? 'play' : 'pause'}
            kind="subtle"
            compact
            style={{ flex: 1 }}
            disabled={!active}
            onPress={() => cmd(st === 'paused' ? 'resume' : 'pause')}
          />
          <Button
            label="Stop"
            icon="stop"
            kind="subtle"
            compact
            style={{ flex: 1 }}
            disabled={!active && st !== 'error'}
            onPress={() => cmd('stop')}
          />
          <Button
            label="Loop"
            icon="repeat"
            kind={s?.loop ? 'primary' : 'subtle'}
            compact
            style={{ flex: 1 }}
            disabled={!up}
            onPress={() => cmd('loop', { loop: !s?.loop })}
          />
        </View>
      </View>

      <View style={[st_.card, { flexDirection: 'row', alignItems: 'center', backgroundColor: t.card, borderColor: t.border }]}>
        <Pressable
          disabled={!hasVol}
          onPress={() => cmd('mute', { muted: !s?.muted })}
          accessibilityLabel={s?.muted ? 'Unmute the laptop speakers' : 'Mute the laptop speakers'}
          style={[st_.mute, { backgroundColor: s?.muted ? t.accentSoft : t.cardAlt, opacity: hasVol ? 1 : 0.5 }]}
        >
          <Icon name={s?.muted ? 'volume-off' : 'volume-high'} size={20} color={s?.muted ? t.accent : t.text} />
        </Pressable>
        <VolumeSlider value={shownVol} disabled={!hasVol} onChange={onVol} />
        <Text style={{ width: 46, textAlign: 'right', color: t.text, fontFamily: t.mono, fontSize: 15 }}>
          {hasVol || vol != null ? `${shownVol}%` : '--'}
        </Text>
      </View>

      {recent.length ? (
        <View style={{ gap: 6 }}>
          <Text style={{ color: t.textFaint, fontSize: 12, fontWeight: '700', letterSpacing: 0.8, marginLeft: 2 }}>RECENT</Text>
          {recent.map((r) => {
            const cur = active && s?.url === r.url;
            return (
              <Pressable
                key={r.id || r.url}
                onPress={() => play(r.url)}
                android_ripple={{ color: t.accentSoft }}
                style={[st_.rec, { backgroundColor: t.card, borderColor: cur ? t.accent : t.border }]}
              >
                <Icon name={cur ? 'volume-high' : 'play'} size={16} color={cur ? t.accent : t.textDim} />
                <Text style={{ color: t.text, fontSize: 14, flex: 1 }} numberOfLines={1}>
                  {r.title || r.url}
                </Text>
                {r.live ? <Text style={{ color: t.textDim, fontSize: 11, fontWeight: '800' }}>LIVE</Text> : null}
              </Pressable>
            );
          })}
        </View>
      ) : null}
    </View>
  );
}

/** 0-100 slider (touch or drag anywhere on the track). */
function VolumeSlider({ value, disabled, onChange }: { value: number; disabled: boolean; onChange: (v: number) => void }) {
  const t = useTheme();
  const [w, setW] = useState(0);
  const at = (x: number) => onChange(Math.round(Math.min(1, Math.max(0, x / Math.max(1, w))) * 100));
  const pct = Math.min(100, Math.max(0, value));
  return (
    <View
      style={{ flex: 1, height: 44, justifyContent: 'center', marginHorizontal: space(3), opacity: disabled ? 0.45 : 1 }}
      onLayout={(e) => setW(e.nativeEvent.layout.width)}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel="Laptop speaker volume"
      accessibilityValue={{ min: 0, max: 100, now: pct }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={(e) => onChange(Math.min(100, Math.max(0, pct + (e.nativeEvent.actionName === 'increment' ? 5 : -5))))}
      onStartShouldSetResponder={() => !disabled}
      onMoveShouldSetResponder={() => !disabled}
      onResponderTerminationRequest={() => false}
      onResponderGrant={(e) => at(e.nativeEvent.locationX)}
      onResponderMove={(e) => at(e.nativeEvent.locationX)}
    >
      <View pointerEvents="none" style={{ height: 4, borderRadius: 2, backgroundColor: t.border }}>
        <View style={{ width: `${pct}%`, height: 4, borderRadius: 2, backgroundColor: t.accent }} />
      </View>
      <View
        pointerEvents="none"
        style={{
          position: 'absolute',
          left: `${pct}%`,
          marginLeft: -10,
          width: 20,
          height: 20,
          borderRadius: 10,
          backgroundColor: t.accent,
        }}
      />
    </View>
  );
}

const st_ = StyleSheet.create({
  input: { flex: 1, height: 46, paddingHorizontal: 14, borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, fontSize: 15 },
  card: { gap: 10, padding: space(4), borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth },
  dot: { width: 8, height: 8, borderRadius: 4 },
  mute: { width: 40, height: 40, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  rec: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minHeight: 48,
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
});
