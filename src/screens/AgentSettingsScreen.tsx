import { useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, Icon, List, ListRow, Pill, Screen, Section, StatusDot, Tone } from '../components/ui';
import { ChatEndpointSection } from '../components/ChatEndpointSection';
import {
  AgentEngine,
  AgentOption,
  AgentSettings,
  AgentSettingsPatch,
  AgentState,
  AgentUsageAccount,
  AgentUsageResponse,
  AgentUsageWindow,
  CodingThread,
  getAgent,
  getAgentSettings,
  getAgentUsage,
  getCodingThreads,
  interruptCodingThread,
  post,
  resetAgent,
  setAgent,
  updateAgentSettings,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { ago } from '../lib/format';
import { formatResetTime } from '../lib/formatResetTime';
import { radius, screenPad, space, useTheme } from '../theme';

const ANTHROPIC_ICON = require('../../assets/brand-anthropic.png');
const GEMINI_ICON = require('../../assets/brand-gemini.png');

const CACHE_AGENT_STATE = 'agent:state';
const CACHE_AGENT_SETTINGS = 'agent:settings';
const CACHE_AGENT_USAGE = 'agent:usage';
const CACHE_AGENT_THREADS = 'agent:threads';
const FIVE_HOUR_THRESHOLDS = [10, 15, 20, 25, 30, 40, 50];
const WEEKLY_THRESHOLDS = [5, 10, 15, 20, 25, 30];
let lastAccountCount = 5;

const DEFAULT_AGENT_OPTIONS: AgentOption[] = [
  { id: 'claude', label: 'Claude', model: 'Opus 5.5' },
  { id: 'gemini', label: 'Gemini', model: 'Gemini 4 Neon' },
];
const AGENT_POLL_MS = 2000;
const AGENT_POLL_MAX_ATTEMPTS = 15; // 30 s
const STALE_REPOLL_MS = 2500;
const STALE_REPOLL_MAX = 4;

type Brand = 'claude' | 'gemini' | 'opencode';

function BrandIcon({ brand, size = 18, color }: { brand: Brand; size?: number; color: string }) {
  if (brand === 'opencode') {
    return <Icon name="code-tags" size={size} color={color} />;
  }
  return (
    <Image
      source={brand === 'claude' ? ANTHROPIC_ICON : GEMINI_ICON}
      style={{ width: size, height: size, tintColor: color }}
      resizeMode="contain"
    />
  );
}

function useAppActiveTick() {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const s = AppState.addEventListener('change', (st) => st === 'active' && setTick((n) => n + 1));
    return () => s.remove();
  }, []);
  return tick;
}

type PickerOption = {
  id: string;
  label: string;
  subtitle?: string;
  disabled?: boolean;
};

type PickerState = {
  title: string;
  selected: string;
  options: PickerOption[];
  onSelect: (id: string) => void;
};

function PickerSheet({ state, onClose }: { state: PickerState | null; onClose: () => void }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal
      visible={!!state}
      animationType="slide"
      onRequestClose={onClose}
      transparent
      statusBarTranslucent
      navigationBarTranslucent
    >
      <View style={[st.sheetWrap, { backgroundColor: t.overlay }]}>
        <Pressable style={{ flex: 1 }} onPress={onClose} />
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />
          <View style={{ paddingHorizontal: screenPad, paddingTop: space(3), paddingBottom: space(2) }}>
            <Text style={{ color: t.text, fontSize: 18, fontWeight: '600' }}>{state?.title ?? ''}</Text>
          </View>
          <ScrollView contentContainerStyle={{ paddingHorizontal: screenPad, paddingBottom: space(3) }}>
            <List>
              {(state?.options ?? []).map((opt, idx, arr) => {
                const selected = opt.id === state?.selected;
                return (
                  <ListRow
                    key={opt.id}
                    title={opt.label}
                    subtitle={opt.subtitle}
                    last={idx === arr.length - 1}
                    onPress={
                      opt.disabled
                        ? undefined
                        : () => {
                            state?.onSelect(opt.id);
                            onClose();
                          }
                    }
                    chevron={false}
                    trailing={
                      selected ? (
                        <Icon name="check" size={20} color={t.accent} />
                      ) : opt.disabled ? (
                        <Pill label="Not signed in" tone="dim" />
                      ) : null
                    }
                  />
                );
              })}
            </List>
          </ScrollView>
          <View style={{ paddingHorizontal: screenPad }}>
            <Button label="Cancel" kind="subtle" onPress={onClose} />
          </View>
        </View>
      </View>
    </Modal>
  );
}

function fmtPct(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—';
  const rounded = Math.round(v);
  return `${rounded}%`;
}

function UsageBar({ label, win }: { label: string; win: AgentUsageWindow | null }) {
  const t = useTheme();
  const rem = win?.remaining_pct;
  const pct = rem == null ? 0 : Math.max(0, Math.min(100, rem));
  const barColor = rem == null ? t.textFaint : rem < 15 ? t.bad : rem < 40 ? t.warn : t.ok;
  const isFullAvailable = rem != null && rem >= 100 && win?.status?.toLowerCase() === 'quota available';
  const formattedReset = isFullAvailable ? null : formatResetTime(win?.resets_at);
  const refreshText = isFullAvailable
    ? 'Quota available'
    : formattedReset
      ? formattedReset
      : win?.refreshes_in
        ? `Refreshes in ${win.refreshes_in}`
        : win?.status && win.status.toLowerCase() !== 'quota available'
          ? win.status
          : rem != null
            ? 'Quota available'
            : '—';

  return (
    <View style={{ gap: 5 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
        <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '500' }}>{label}</Text>
        <Text style={{ color: t.text, fontSize: 12.5, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
          {fmtPct(rem)} left
        </Text>
      </View>
      <View style={[st.barTrack, { backgroundColor: t.cardAlt }]}>
        <View style={[st.barFill, { width: `${pct}%`, backgroundColor: barColor }]} />
      </View>
      <Text style={{ color: t.textFaint, fontSize: 11.5 }}>{refreshText}</Text>
    </View>
  );
}

function UsageSkeletonCard() {
  const t = useTheme();
  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2.5) }}>
        <View style={[st.skelCircle, { backgroundColor: t.cardAlt }]} />
        <View style={{ flex: 1, gap: 6 }}>
          <View style={[st.skelLine, { width: '58%', height: 14, backgroundColor: t.cardAlt }]} />
          <View style={[st.skelLine, { width: '32%', height: 11, backgroundColor: t.cardAlt }]} />
        </View>
        <View style={[st.skelPill, { backgroundColor: t.cardAlt }]} />
      </View>
      <View style={{ gap: space(2.5) }}>
        {[0, 1].map((i) => (
          <View key={i} style={{ gap: 5 }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
              <View style={[st.skelLine, { width: 76, height: 12, backgroundColor: t.cardAlt }]} />
              <View style={[st.skelLine, { width: 48, height: 12, backgroundColor: t.cardAlt }]} />
            </View>
            <View style={[st.barTrack, { backgroundColor: t.cardAlt }]} />
            <View style={[st.skelLine, { width: 116, height: 11, backgroundColor: t.cardAlt }]} />
          </View>
        ))}
      </View>
    </Card>
  );
}

function shortModelName(model: string): string {
  const idx = model.indexOf('/');
  return idx >= 0 ? model.slice(idx + 1) : model;
}

function OpenCodeUsageCard({ acc }: { acc: AgentUsageAccount }) {
  const t = useTheme();
  const ds = acc.deepseek;
  const zen = acc.zen;
  const active = acc.signed_in && !acc.error;

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2.5) }}>
        <BrandIcon brand="opencode" size={20} color={active ? t.accent : t.textFaint} />
        <View style={{ flex: 1, gap: 1 }}>
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={1}>
            {acc.label}
          </Text>
        </View>
        <Pill label="OpenCode" tone="dim" />
      </View>

      {acc.error ? (
        <Text style={{ color: t.bad, fontSize: 12.5, lineHeight: 18 }}>{acc.error}</Text>
      ) : (
        <View style={{ gap: space(2.5) }}>
          <View style={st.ocRow}>
            <Text style={{ color: t.textDim, fontSize: 13 }}>DeepSeek balance</Text>
            {ds?.error ? (
              <Text style={{ color: t.bad, fontSize: 12.5, flexShrink: 1, textAlign: 'right' }} numberOfLines={1}>
                {ds.error}
              </Text>
            ) : ds?.formatted ? (
              <Text style={{ color: t.text, fontSize: 14, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
                {ds.formatted} left
              </Text>
            ) : (
              <Text style={{ color: t.textFaint, fontSize: 13 }}>—</Text>
            )}
          </View>

          {zen?.status === 'ok' ? (
            <View style={[st.ocZenBox, { backgroundColor: t.cardAlt }]}>
              <View style={st.ocRow}>
                <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '500' }}>
                  Zen ({zen.days ?? 7}d)
                </Text>
                <Text style={{ color: t.text, fontSize: 12.5, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
                  {(zen.messages ?? 0).toLocaleString()} msgs · {zen.total_cost ?? '$0.00'}
                </Text>
              </View>
              {zen.models?.length ? (
                <View style={{ gap: 4, marginTop: 2 }}>
                  {zen.models.map((m) => (
                    <View key={m.model} style={st.ocRow}>
                      <Text style={{ color: t.textFaint, fontSize: 12, flex: 1 }} numberOfLines={1}>
                        {shortModelName(m.model)}
                      </Text>
                      <Text style={{ color: t.textDim, fontSize: 12, fontVariant: ['tabular-nums'] }}>
                        {m.messages.toLocaleString()} msgs
                      </Text>
                    </View>
                  ))}
                </View>
              ) : null}
            </View>
          ) : zen?.error ? (
            <Text style={{ color: t.textFaint, fontSize: 12 }}>Zen: {zen.error}</Text>
          ) : null}
        </View>
      )}
    </Card>
  );
}

function UsageAccountCard({ acc }: { acc: AgentUsageAccount }) {
  const t = useTheme();
  if (acc.harness === 'opencode') {
    return <OpenCodeUsageCard acc={acc} />;
  }
  const harnessLabel = acc.harness === 'claude' ? 'Claude' : 'Gemini';

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2.5) }}>
        <BrandIcon
          brand={acc.harness}
          size={19}
          color={acc.signed_in && !acc.error ? t.accent : t.textFaint}
        />
        <View style={{ flex: 1, gap: 1 }}>
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={1}>
            {acc.email || acc.label}
          </Text>
          {acc.email ? (
            <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
              {acc.label}
            </Text>
          ) : null}
        </View>
        <Pill label={harnessLabel} tone={acc.harness === 'claude' ? 'accent' : 'dim'} />
      </View>

      {!acc.signed_in ? (
        <Text style={{ color: t.textFaint, fontSize: 13 }}>{acc.status || 'Not signed in'}</Text>
      ) : acc.error ? (
        <Text style={{ color: t.bad, fontSize: 12.5, lineHeight: 18 }}>{acc.error}</Text>
      ) : (
        <View style={{ gap: space(2.5) }}>
          <UsageBar label="5-hour limit" win={acc.five_hour} />
          <UsageBar label="Weekly limit" win={acc.weekly} />
        </View>
      )}
    </Card>
  );
}

function HarnessSection({
  state,
  pending,
  err,
  onSelect,
}: {
  state: AgentState | null;
  pending: AgentEngine | null;
  err: string | null;
  onSelect: (engine: AgentEngine) => void;
}) {
  const t = useTheme();
  const options = state?.options?.length ? state.options : DEFAULT_AGENT_OPTIONS;
  const selected: AgentEngine = pending ?? state?.engine ?? 'claude';
  const switching = pending !== null || Boolean(state?.switching);
  const unavailable = state != null && !state.available;
  const disabled = !state || unavailable || switching;

  return (
    <Card style={{ gap: space(3) }}>
      <View style={[st.agentSeg, { backgroundColor: t.cardAlt, opacity: disabled ? 0.55 : 1 }]} accessibilityRole="tablist">
        {options.map((opt) => {
          const on = opt.id === selected;
          const accentOn = on && !unavailable && state != null;
          const fg = accentOn ? t.onAccent : t.textDim;
          return (
            <Pressable
              key={opt.id}
              onPress={() => onSelect(opt.id)}
              disabled={disabled}
              accessibilityRole="tab"
              accessibilityState={{ selected: on, disabled }}
              android_ripple={{ color: t.ripple, borderless: false }}
              style={[
                st.agentSegBtn,
                accentOn ? { backgroundColor: t.accent } : on ? { backgroundColor: t.card } : null,
              ]}
            >
              <BrandIcon brand={opt.id} size={15} color={fg} />
              <Text
                style={[st.agentSegText, { color: fg }]}
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.78}
              >
                {opt.label}
              </Text>
            </Pressable>
          );
        })}
      </View>

      {unavailable ? (
        <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18 }}>Agent switch not installed</Text>
      ) : null}
      {err && !unavailable ? (
        <Text style={{ color: t.bad, fontSize: 12.5, lineHeight: 18 }}>{err}</Text>
      ) : null}
    </Card>
  );
}

function formatEffort(v: string): string {
  if (v === 'xhigh') return 'XHigh';
  return v ? v[0].toUpperCase() + v.slice(1) : '—';
}

function threadStatusTone(status: string): Tone {
  if (status === 'running' || status === 'starting' || status === 'preparing') return 'accent';
  if (status === 'waiting' || status === 'queued') return 'warn';
  if (status === 'completed' || status === 'idle') return 'ok';
  if (status === 'failed' || status === 'interrupted' || status === 'cancelled') return 'bad';
  return 'faint';
}

function threadAge(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  return ago(ms / 1000);
}

function ThreadSkeletonCard() {
  const t = useTheme();
  return (
    <Card style={{ gap: space(2) }}>
      {[0, 1, 2].map((i) => (
        <View key={i} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2.5), paddingVertical: 4 }}>
          <View style={[st.skelLine, { width: 8, height: 8, borderRadius: 4, backgroundColor: t.cardAlt }]} />
          <View style={{ flex: 1, gap: 6 }}>
            <View style={[st.skelLine, { width: '65%', height: 14, backgroundColor: t.cardAlt }]} />
            <View style={[st.skelLine, { width: '45%', height: 11, backgroundColor: t.cardAlt }]} />
          </View>
        </View>
      ))}
    </Card>
  );
}

function CodingThreadsSection({
  threads,
  loading,
  err,
  interruptingId,
  onRefresh,
  onInterrupt,
}: {
  threads: CodingThread[] | null;
  loading: boolean;
  err: string | null;
  interruptingId: string | null;
  onRefresh: () => void;
  onInterrupt: (th: CodingThread) => void;
}) {
  const t = useTheme();
  const isInterruptible = (status: string) =>
    status === 'running' || status === 'starting' || status === 'preparing' || status === 'waiting' || status === 'queued';

  return (
    <Section
      label="Coding threads"
      right={
        loading ? (
          <ActivityIndicator size="small" color={t.accent} />
        ) : (
          <Pressable onPress={onRefresh} hitSlop={8}>
            <Text style={{ color: t.accent, fontSize: 12.5, fontWeight: '500' }}>Refresh</Text>
          </Pressable>
        )
      }
    >
      {err ? <Banner tone="bad" title="Coding threads">{err}</Banner> : null}
      {!threads && loading ? (
        <ThreadSkeletonCard />
      ) : threads?.length ? (
        <List>
          {threads.map((th, idx) => {
            const active = isInterruptible(th.status);
            const meta = [th.model, th.instance, th.status, threadAge(th.updated_at || th.created_at)]
              .filter(Boolean)
              .join(' · ');
            return (
              <ListRow
                key={th.id}
                leading={<StatusDot tone={threadStatusTone(th.status)} />}
                title={th.title || 'Untitled'}
                subtitle={meta}
                chevron={false}
                last={idx === threads.length - 1}
                trailing={
                  active ? (
                    <Button
                      label="Interrupt"
                      kind="danger"
                      compact
                      busy={interruptingId === th.id}
                      disabled={interruptingId !== null && interruptingId !== th.id}
                      onPress={() => onInterrupt(th)}
                    />
                  ) : null
                }
              />
            );
          })}
        </List>
      ) : !err ? (
        <Card>
          <Text style={{ color: t.textFaint, fontSize: 13 }}>No recent threads.</Text>
        </Card>
      ) : null}
    </Section>
  );
}

export default function AgentSettingsScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const tick = useAppActiveTick();

  // Harness state (hydrated from persistent MMKV cache for instant render on cold start & re-entry)
  const [agentState, setAgentState] = useState<AgentState | null>(() => cacheGet<AgentState>(CACHE_AGENT_STATE));
  const [pendingEngine, setPendingEngine] = useState<AgentEngine | null>(null);
  const [agentErr, setAgentErr] = useState<string | null>(null);
  const mounted = useRef(true);
  const switchSeq = useRef(0);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const staleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const staleAttempts = useRef(0);
  const pendingRef = useRef<AgentEngine | null>(null);

  // Usage state
  const [usage, setUsage] = useState<AgentUsageResponse | null>(() => cacheGet<AgentUsageResponse>(CACHE_AGENT_USAGE));
  const [usageLoading, setUsageLoading] = useState(() => cacheGet<AgentUsageResponse>(CACHE_AGENT_USAGE) === null);
  const [usageErr, setUsageErr] = useState<string | null>(null);

  // Settings state
  const [settings, setSettings] = useState<AgentSettings | null>(() => cacheGet<AgentSettings>(CACHE_AGENT_SETTINGS));
  const [settingsErr, setSettingsErr] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Coding threads state
  const [threads, setThreads] = useState<CodingThread[] | null>(() => cacheGet<CodingThread[]>(CACHE_AGENT_THREADS));
  const [threadsLoading, setThreadsLoading] = useState(() => cacheGet<CodingThread[]>(CACHE_AGENT_THREADS) === null);
  const [threadsErr, setThreadsErr] = useState<string | null>(null);
  const [interruptingId, setInterruptingId] = useState<string | null>(null);

  // Actions state
  const [restartingClaude, setRestartingClaude] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [picker, setPicker] = useState<PickerState | null>(null);

  const clearPoll = useCallback(() => {
    if (pollTimer.current) {
      clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const clearStaleTimer = useCallback(() => {
    if (staleTimer.current) {
      clearTimeout(staleTimer.current);
      staleTimer.current = null;
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      clearPoll();
      clearStaleTimer();
    };
  }, [clearPoll, clearStaleTimer]);

  const loadAgent = useCallback(async () => {
    if (pendingRef.current !== null) return;
    try {
      const next = await getAgent();
      if (!mounted.current || pendingRef.current !== null) return;
      cacheSet(CACHE_AGENT_STATE, next);
      setAgentState(next);
      setAgentErr(null);
    } catch (e) {
      if (!mounted.current || pendingRef.current !== null) return;
      setAgentErr((e as Error).message);
    }
  }, []);

  const loadSettings = useCallback(async () => {
    try {
      const next = await getAgentSettings();
      if (!mounted.current) return;
      cacheSet(CACHE_AGENT_SETTINGS, next);
      setSettings(next);
      setSettingsErr(null);
    } catch (e) {
      if (!mounted.current) return;
      setSettingsErr((e as Error).message);
    }
  }, []);

  const loadUsage = useCallback(async (force = false, silent = false) => {
    const run = async (doForce: boolean, isSilent: boolean) => {
      if (!isSilent) setUsageLoading(true);
      clearStaleTimer();
      if (doForce) staleAttempts.current = 0;
      try {
        const next = await getAgentUsage(doForce);
        if (!mounted.current) return;
        cacheSet(CACHE_AGENT_USAGE, next);
        if (next.accounts.length > 0) {
          lastAccountCount = next.accounts.length;
        }
        setUsage(next);
        setUsageErr(null);
        // Reload settings once usage completes so newly discovered emails appear in the Gemini instance picker.
        void loadSettings();
        if (next.stale && staleAttempts.current < STALE_REPOLL_MAX) {
          staleAttempts.current += 1;
          staleTimer.current = setTimeout(() => {
            if (mounted.current) void run(false, true);
          }, STALE_REPOLL_MS);
        } else if (!next.stale) {
          staleAttempts.current = 0;
        }
      } catch (e) {
        if (!mounted.current) return;
        setUsageErr((e as Error).message);
      } finally {
        if (mounted.current && !isSilent) setUsageLoading(false);
      }
    };
    await run(force, silent);
  }, [loadSettings, clearStaleTimer]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadAgent();
    const iv = setInterval(loadAgent, agentState?.switching ? AGENT_POLL_MS : 8000);
    return () => clearInterval(iv);
  }, [loadAgent, tick, agentState?.switching]);

  const loadThreads = useCallback(async (silent = false) => {
    if (!silent) setThreadsLoading(true);
    try {
      const res = await getCodingThreads();
      if (!mounted.current) return;
      cacheSet(CACHE_AGENT_THREADS, res.threads);
      setThreads(res.threads);
      setThreadsErr(null);
    } catch (e) {
      if (!mounted.current) return;
      setThreadsErr((e as Error).message);
    } finally {
      if (mounted.current && !silent) setThreadsLoading(false);
    }
  }, []);

  const interruptThread = (th: CodingThread) =>
    Alert.alert('Interrupt thread?', th.title || 'Untitled', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Interrupt',
        style: 'destructive',
        onPress: async () => {
          setInterruptingId(th.id);
          try {
            await interruptCodingThread(th.id);
            await loadThreads(true);
          } catch (e) {
            Alert.alert('Interrupt failed', (e as Error).message);
          } finally {
            if (mounted.current) setInterruptingId(null);
          }
        },
      },
    ]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadSettings();
    void loadUsage(false);
    void loadThreads(false);
  }, [loadSettings, loadUsage, loadThreads]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.all([loadAgent(), loadSettings(), loadUsage(true), loadThreads(true)]);
    } finally {
      if (mounted.current) setRefreshing(false);
    }
  }, [loadAgent, loadSettings, loadUsage, loadThreads]);

  const selectEngine = async (target: AgentEngine) => {
    if (!agentState || !agentState.available || pendingRef.current !== null || agentState.switching) return;
    const targetActive = target === 'claude' ? agentState.claude_active : agentState.gemini_active;
    if (target === agentState.engine && targetActive) return;

    const seq = ++switchSeq.current;
    clearPoll();
    pendingRef.current = target;
    setPendingEngine(target);
    setAgentErr(null);

    try {
      await setAgent(target);
    } catch (e) {
      if (!mounted.current || switchSeq.current !== seq) return;
      pendingRef.current = null;
      setPendingEngine(null);
      setAgentErr((e as Error).message || 'Failed to switch agent');
      return;
    }

    if (!mounted.current || switchSeq.current !== seq) return;
    let attempts = 0;

    const poll = async () => {
      if (!mounted.current || switchSeq.current !== seq) return;
      attempts += 1;
      try {
        const cur = await getAgent();
        if (!mounted.current || switchSeq.current !== seq) return;
        if (!cur.available) {
          pendingRef.current = null;
          setAgentState(cur);
          setPendingEngine(null);
          setAgentErr('Agent switch not installed');
          return;
        }
        const active = target === 'claude' ? cur.claude_active : cur.gemini_active;
        if (active) {
          pendingRef.current = null;
          cacheSet(CACHE_AGENT_STATE, cur);
          setAgentState(cur);
          setPendingEngine(null);
          setAgentErr(null);
          return;
        }
      } catch {
        // transient network error while switching; keep polling
      }
      if (attempts >= AGENT_POLL_MAX_ATTEMPTS) {
        pendingRef.current = null;
        setPendingEngine(null);
        setAgentErr(`Switch to ${target === 'claude' ? 'Claude' : 'Gemini'} timed out`);
        return;
      }
      pollTimer.current = setTimeout(poll, AGENT_POLL_MS);
    };

    pollTimer.current = setTimeout(poll, AGENT_POLL_MS);
  };

  const savePatch = async (patch: AgentSettingsPatch) => {
    setSaving(true);
    setSettingsErr(null);
    try {
      const updated = await updateAgentSettings(patch);
      if (mounted.current) {
        cacheSet(CACHE_AGENT_SETTINGS, updated);
        setSettings(updated);
      }
    } catch (e) {
      if (mounted.current) setSettingsErr((e as Error).message);
    } finally {
      if (mounted.current) setSaving(false);
    }
  };

  const restartClaude = () =>
    Alert.alert('Restart Claude agent?', 'Applies your model and effort changes immediately.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Restart',
        onPress: async () => {
          setRestartingClaude(true);
          try {
            await post('/pc/api/services/pc-agent.service/restart', {});
            setTimeout(() => void loadAgent(), 1800);
          } catch (e) {
            Alert.alert('Restart failed', (e as Error).message);
          } finally {
            if (mounted.current) setRestartingClaude(false);
          }
        },
      },
    ]);

  const startFresh = () =>
    Alert.alert('Start a fresh conversation?', 'Clears the current conversation context for the active agent.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Start fresh',
        onPress: async () => {
          setResetting(true);
          try {
            const res = await resetAgent();
            Alert.alert('Fresh conversation', res.message || 'Next message will start fresh.');
          } catch (e) {
            Alert.alert('Reset failed', (e as Error).message);
          } finally {
            if (mounted.current) setResetting(false);
          }
        },
      },
    ]);

  const claude = settings?.claude;
  const gemini = settings?.gemini;
  const claudeModelLabel =
    claude?.models.find((m) => m.id === claude.model)?.label ?? claude?.model ?? '—';
  const geminiModelLabel =
    gemini?.models.find((m) => m.id === gemini.model)?.label ?? gemini?.model ?? '—';
  const geminiInstObj = gemini?.instances.find((i) => i.id === gemini.instance_id);

  const selectedEngine: AgentEngine = pendingEngine ?? agentState?.engine ?? 'claude';
  const switching = pendingEngine !== null || Boolean(agentState?.switching);
  const unavailable = agentState != null && !agentState.available;
  const selectedActive = agentState
    ? selectedEngine === 'claude'
      ? agentState.claude_active
      : agentState.gemini_active
    : false;
  const statusText = switching
    ? 'Switching…'
    : !agentState || unavailable
      ? 'Unavailable'
      : selectedActive
        ? 'Running'
        : 'Stopped';
  const statusTone: Tone = switching ? 'warn' : !agentState || unavailable ? 'faint' : selectedActive ? 'ok' : 'warn';

  const isStale = Boolean(usage?.stale);

  return (
    <Screen
      title="Agent settings"
      onBack={() => nav.goBack()}
      onRefresh={onRefresh}
      refreshing={refreshing}
      right={
        <View style={st.headerStatus} accessibilityLabel={`Agent status: ${statusText}`}>
          <StatusDot tone={statusTone} />
          <Text style={{ color: t.textDim, fontSize: 13, fontWeight: '500' }}>{statusText}</Text>
        </View>
      }
    >
      <Section label="Harness">
        <HarnessSection state={agentState} pending={pendingEngine} err={agentErr} onSelect={selectEngine} />
      </Section>

      <Section
        label="Usage"
        right={
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
            {isStale ? (
              <Text style={{ color: t.textFaint, fontSize: 11.5 }}>updating…</Text>
            ) : null}
            {usageLoading ? (
              <ActivityIndicator size="small" color={t.accent} />
            ) : (
              <Pressable onPress={() => void loadUsage(true)} hitSlop={8}>
                <Text style={{ color: t.accent, fontSize: 12.5, fontWeight: '500' }}>Refresh</Text>
              </Pressable>
            )}
          </View>
        }
      >
        {usageErr ? <Banner tone="bad" title="Usage unavailable">{usageErr}</Banner> : null}
        {!usage && usageLoading ? (
          <View style={{ gap: space(2.5) }}>
            {Array.from({ length: lastAccountCount }, (_, i) => (
              <UsageSkeletonCard key={i} />
            ))}
          </View>
        ) : usage?.accounts?.length ? (
          <View style={{ gap: space(2.5) }}>
            {usage.accounts.map((acc) => (
              <UsageAccountCard key={acc.id} acc={acc} />
            ))}
          </View>
        ) : !usageErr ? (
          <Card>
            <Text style={{ color: t.textFaint, fontSize: 13 }}>No accounts reported.</Text>
          </Card>
        ) : null}
      </Section>

      {settingsErr ? <Banner tone="bad" title="Agent settings">{settingsErr}</Banner> : null}

      <Section label="Claude" icon={<BrandIcon brand="claude" size={14} color={t.textDim} />}>
        <Card style={{ gap: space(3), padding: 0, overflow: 'hidden' }}>
          <View>
            <ListRow
              leading={<BrandIcon brand="claude" size={18} color={t.textDim} />}
              title="Account"
              trailing={claude?.account || '—'}
              chevron={false}
            />
            <ListRow
              icon="tune"
              title="Model"
              trailing={claudeModelLabel}
              chevron
              onPress={
                claude && !saving
                  ? () =>
                      setPicker({
                        title: 'Claude model',
                        selected: claude.model,
                        options: claude.models.map((m) => ({ id: m.id, label: m.label, subtitle: m.id })),
                        onSelect: (id) => void savePatch({ claude: { model: id } }),
                      })
                  : undefined
              }
            />
            <ListRow
              icon="gauge"
              title="Effort"
              trailing={claude ? formatEffort(claude.effort) : '—'}
              chevron
              last
              onPress={
                claude && !saving
                  ? () =>
                      setPicker({
                        title: 'Claude effort',
                        selected: claude.effort,
                        options: claude.efforts.map((e) => ({ id: e, label: formatEffort(e) })),
                        onSelect: (id) => void savePatch({ claude: { effort: id } }),
                      })
                  : undefined
              }
            />
          </View>
          {agentState?.engine === 'claude' ? (
            <View style={{ paddingHorizontal: space(4), paddingBottom: space(4) }}>
              <Button
                label="Restart agent now"
                kind="subtle"
                icon="restart"
                busy={restartingClaude}
                onPress={restartClaude}
              />
            </View>
          ) : null}
        </Card>
      </Section>

      <Section label="Gemini" icon={<BrandIcon brand="gemini" size={14} color={t.textDim} />}>
        <Card style={{ gap: space(3), padding: 0, overflow: 'hidden' }}>
          <View>
            <ListRow
              leading={<BrandIcon brand="gemini" size={18} color={t.textDim} />}
              title="Account"
              subtitle={geminiInstObj?.email || (geminiInstObj && !geminiInstObj.signed_in ? 'Not signed in' : undefined)}
              trailing={geminiInstObj?.label ?? gemini?.instance_id ?? '—'}
              chevron
              onPress={
                gemini && !saving
                  ? () =>
                      setPicker({
                        title: 'Gemini account',
                        selected: gemini.instance_id,
                        options: gemini.instances.map((inst) => ({
                          id: inst.id,
                          label: inst.label,
                          subtitle: inst.email || (inst.signed_in ? inst.id : 'Not signed in'),
                          disabled: !inst.signed_in,
                        })),
                        onSelect: (id) => void savePatch({ gemini: { instance_id: id } }),
                      })
                  : undefined
              }
            />
            <ListRow
              icon="autorenew"
              title="Auto-rotate on usage limit"
              chevron={false}
              trailing={
                <Switch
                  value={Boolean(gemini?.auto_rotate)}
                  disabled={!gemini || saving}
                  onValueChange={(v) => void savePatch({ gemini: { auto_rotate: v } })}
                  accessibilityLabel="Auto-rotate on usage limit"
                  trackColor={{ true: t.accent, false: t.cardAlt }}
                  thumbColor={gemini?.auto_rotate ? t.onMedia : t.textFaint}
                />
              }
            />
            <ListRow
              icon="tune"
              title="Model"
              trailing={geminiModelLabel}
              chevron
              onPress={
                gemini && !saving
                  ? () =>
                      setPicker({
                        title: 'Gemini model',
                        selected: gemini.model,
                        options: gemini.models.map((m) => ({ id: m.id, label: m.label, subtitle: m.id })),
                        onSelect: (id) => void savePatch({ gemini: { model: id } }),
                      })
                  : undefined
              }
            />
            <ListRow
              icon="gauge"
              title="Effort"
              trailing={gemini ? formatEffort(gemini.thought_level) : '—'}
              chevron
              last
              onPress={
                gemini && !saving
                  ? () =>
                      setPicker({
                        title: 'Gemini effort',
                        selected: gemini.thought_level,
                        options: gemini.efforts.map((e) => ({ id: e, label: formatEffort(e) })),
                        onSelect: (id) => void savePatch({ gemini: { thought_level: id } }),
                      })
                  : undefined
              }
            />
          </View>
        </Card>
      </Section>

      <Section label="Usage alerts">
        <List>
          <ListRow
            icon="bell-alert"
            title="Usage alerts"
            chevron={false}
            trailing={
              <Switch
                value={settings?.usage_alerts?.enabled ?? true}
                disabled={!settings || saving}
                onValueChange={(v) => void savePatch({ usage_alerts: { enabled: v } })}
                accessibilityLabel="Usage alerts"
                trackColor={{ true: t.accent, false: t.cardAlt }}
                thumbColor={(settings?.usage_alerts?.enabled ?? true) ? t.onMedia : t.textFaint}
              />
            }
          />
          <ListRow
            icon="timer-sand"
            title="5-hour threshold"
            trailing={settings?.usage_alerts ? `${settings.usage_alerts.five_hour_pct}% left` : '—'}
            chevron
            onPress={
              settings?.usage_alerts && !saving
                ? () =>
                    setPicker({
                      title: '5-hour alert threshold',
                      selected: String(settings.usage_alerts!.five_hour_pct),
                      options: FIVE_HOUR_THRESHOLDS.map((pct) => ({
                        id: String(pct),
                        label: `${pct}% left`,
                        subtitle: `Alert at ${100 - pct}% used`,
                      })),
                      onSelect: (id) => void savePatch({ usage_alerts: { five_hour_pct: Number(id) } }),
                    })
                : undefined
            }
          />
          <ListRow
            icon="calendar-clock"
            title="Weekly threshold"
            trailing={settings?.usage_alerts ? `${settings.usage_alerts.weekly_pct}% left` : '—'}
            chevron
            last
            onPress={
              settings?.usage_alerts && !saving
                ? () =>
                    setPicker({
                      title: 'Weekly alert threshold',
                      selected: String(settings.usage_alerts!.weekly_pct),
                      options: WEEKLY_THRESHOLDS.map((pct) => ({
                        id: String(pct),
                        label: `${pct}% left`,
                        subtitle: `Alert at ${100 - pct}% used`,
                      })),
                      onSelect: (id) => void savePatch({ usage_alerts: { weekly_pct: Number(id) } }),
                    })
                : undefined
            }
          />
        </List>
      </Section>

      <CodingThreadsSection
        threads={threads}
        loading={threadsLoading}
        err={threadsErr}
        interruptingId={interruptingId}
        onRefresh={() => void loadThreads(false)}
        onInterrupt={interruptThread}
      />

      <ChatEndpointSection />

      <Section label="Conversation">
        <Card>
          <Button
            label="Start fresh"
            kind="subtle"
            icon="refresh"
            busy={resetting}
            onPress={startFresh}
          />
        </Card>
      </Section>

      <PickerSheet state={picker} onClose={() => setPicker(null)} />
    </Screen>
  );
}

const st = StyleSheet.create({
  headerStatus: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingBottom: 6 },
  agentSeg: { flexDirection: 'row', padding: 3, borderRadius: radius.sm, gap: 3 },
  agentSegBtn: {
    flex: 1,
    flexDirection: 'row',
    gap: 6,
    minHeight: 38,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
    paddingHorizontal: 8,
    paddingVertical: 6,
    overflow: 'hidden',
  },
  agentSegText: { fontSize: 13.5, fontWeight: '600', textAlign: 'center' },
  barTrack: { height: 6, borderRadius: 3, overflow: 'hidden' },
  barFill: { height: '100%', borderRadius: 3 },
  skelCircle: { width: 20, height: 20, borderRadius: 10 },
  skelLine: { borderRadius: 4 },
  skelPill: { width: 54, height: 22, borderRadius: radius.pill },
  ocRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) },
  ocZenBox: { borderRadius: radius.sm, padding: space(3), gap: 6 },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '78%', borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, paddingTop: space(2) },
  grabber: { width: 38, height: 4, borderRadius: 2, alignSelf: 'center' },
});
