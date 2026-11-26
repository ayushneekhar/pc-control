import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Banner, Button, Card, Empty, Icon, List, ListRow, Pill, Screen, Segmented, StatusDot, Tone } from '../components/ui';
import {
  actionLabel,
  AgentRule,
  AgentState,
  AgentUrl,
  AgentViewMasterState,
  deleteAgentRule,
  getAgentMasterState,
  getAgentState,
  getAgentUrls,
  relativeTime,
  reorderAgentRules,
  splitUrl,
  toggleAgentMaster,
  updateAgentRule,
} from '../lib/agentview';
import { useNow } from '../lib/useNow';
import { radius, space, useTheme } from '../theme';

type Tab = 'urls' | 'rules';

const ACTION_ICONS: Record<AgentRule['action'], string> = {
  replace_page: 'file-document-edit',
  replace_text: 'find-replace',
  hide: 'eye-off',
  block: 'cancel',
};

export default function AgentViewScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const now = useNow(10000);
  const [tab, setTab] = useState<Tab>('urls');
  const [master, setMaster] = useState<AgentViewMasterState | null>(null);
  const [state, setState] = useState<AgentState | null>(null);
  const [urls, setUrls] = useState<AgentUrl[] | null>(null);
  const [q, setQ] = useState('');
  const [err, setErr] = useState<{ title: string; message: string } | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [switching, setSwitching] = useState<'on' | 'off' | null>(null);
  const [busy, setBusy] = useState(false);
  const qRef = useRef(q);
  const masterRef = useRef<AgentViewMasterState | null>(master);
  const switchingRef = useRef(false);
  const didMountQ = useRef(false);
  const seq = useRef(0);

  useEffect(() => {
    qRef.current = q;
  }, [q]);

  useEffect(() => {
    masterRef.current = master;
  }, [master]);

  useEffect(() => {
    switchingRef.current = switching !== null;
  }, [switching]);

  const loadWebData = useCallback(async (n: number) => {
    try {
      const [nextState, nextUrls] = await Promise.all([getAgentState(), getAgentUrls(qRef.current)]);
      if (n !== seq.current) return;
      setState(nextState);
      setUrls(nextUrls);
      setErr(null);
    } catch (e) {
      if (n !== seq.current) return;
      setErr({ title: 'Agent view web UI is unreachable', message: (e as Error).message });
    }
  }, []);

  const load = useCallback(async () => {
    if (switchingRef.current) return;
    const n = ++seq.current;
    let nextMaster: AgentViewMasterState;
    try {
      nextMaster = await getAgentMasterState();
    } catch (e) {
      if (n !== seq.current) return;
      setErr({ title: 'Agent view status unavailable', message: (e as Error).message });
      return;
    }
    if (n !== seq.current) return;
    setMaster(nextMaster);

    if (!nextMaster.enabled) {
      setState(null);
      setUrls(null);
      setErr(null);
      return;
    }

    if (!nextMaster.web_reachable) {
      setErr(null);
      return;
    }

    await loadWebData(n);
  }, [loadWebData]);

  useFocusEffect(
    useCallback(() => {
      void load();
      const iv = setInterval(() => {
        if (AppState.currentState === 'active' && !switchingRef.current) void load();
      }, 10000);
      const sub = AppState.addEventListener('change', (s) => {
        if (s === 'active' && !switchingRef.current) void load();
      });
      return () => {
        clearInterval(iv);
        sub.remove();
      };
    }, [load]),
  );

  useEffect(() => {
    if (!didMountQ.current) {
      didMountQ.current = true;
      return;
    }
    if (!masterRef.current?.enabled || !masterRef.current.web_reachable) return;
    const timer = setTimeout(async () => {
      const n = ++seq.current;
      try {
        const nextUrls = await getAgentUrls(qRef.current);
        if (n !== seq.current) return;
        setUrls(nextUrls);
        setErr(null);
      } catch (e) {
        if (n !== seq.current) return;
        setErr({ title: 'Agent view web UI is unreachable', message: (e as Error).message });
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [q]);

  const toggleMaster = async (enabled: boolean) => {
    if (switchingRef.current) return;
    const n = ++seq.current;
    switchingRef.current = true;
    setSwitching(enabled ? 'on' : 'off');
    setErr(null);
    try {
      const nextMaster = await toggleAgentMaster(enabled);
      if (n !== seq.current) return;
      setMaster(nextMaster);
      if (!nextMaster.enabled) {
        setState(null);
        setUrls(null);
        setErr(null);
        return;
      }
      if (nextMaster.web_reachable) {
        await loadWebData(n);
      }
    } catch (e) {
      if (n !== seq.current) return;
      setErr({
        title: enabled ? 'Could not turn Agent view on' : 'Could not turn Agent view off',
        message: (e as Error).message,
      });
    } finally {
      switchingRef.current = false;
      setSwitching(null);
    }
  };

  const toggleRule = async (rule: AgentRule, enabled: boolean) => {
    try {
      const next = await updateAgentRule(rule.id, { enabled });
      setState((s) => (s ? { ...s, rules: s.rules.map((r) => (r.id === next.id ? next : r)) } : s));
      setErr(null);
    } catch (e) {
      setErr({ title: 'Could not update rule', message: (e as Error).message });
    }
  };

  const moveRule = async (index: number, dir: -1 | 1) => {
    if (!state) return;
    const target = index + dir;
    if (target < 0 || target >= state.rules.length) return;
    const rules = state.rules.slice();
    const [moved] = rules.splice(index, 1);
    rules.splice(target, 0, moved);
    setBusy(true);
    try {
      setState(await reorderAgentRules(rules.map((r) => r.id)));
      setErr(null);
    } catch (e) {
      setErr({ title: 'Could not reorder rules', message: (e as Error).message });
    } finally {
      setBusy(false);
    }
  };

  const removeRule = (rule: AgentRule) =>
    Alert.alert('Delete rule?', rule.name || rule.match, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          try {
            await deleteAgentRule(rule.id);
            setState((s) => (s ? { ...s, rules: s.rules.filter((r) => r.id !== rule.id) } : s));
            setErr(null);
          } catch (e) {
            setErr({ title: 'Could not delete rule', message: (e as Error).message });
          }
        },
      },
    ]);

  const editUrl = (u: AgentUrl) => nav.navigate('AgentRule', { url: u.url, ruleId: u.rule_id ?? undefined });

  const displayEnabled = switching ? switching === 'on' : !!master?.enabled;
  const isOff = master !== null && !master.enabled && switching !== 'on';
  const isActive = !!master?.enabled && master.web_reachable && switching !== 'off';
  const isStartingOrOffline = !!master?.enabled && !master.web_reachable && switching === null;

  let statusDot: Tone = 'faint';
  let statusText = 'Checking status…';
  let cardStatusText = 'Checking status…';

  if (switching === 'on') {
    statusDot = 'warn';
    statusText = 'Starting stack…';
    cardStatusText = 'Starting proxy and web UI…';
  } else if (switching === 'off') {
    statusDot = 'warn';
    statusText = 'Stopping stack…';
    cardStatusText = 'Stopping proxy and web UI…';
  } else if (master === null) {
    statusDot = err ? 'bad' : 'faint';
    statusText = err ? 'Status unavailable' : 'Checking status…';
    cardStatusText = err ? 'Status unavailable' : 'Checking status…';
  } else if (!master.enabled) {
    statusDot = 'faint';
    statusText = 'Off · proxy and web UI stopped';
    cardStatusText = 'Off';
  } else if (!master.web_reachable) {
    statusDot = 'warn';
    statusText = 'On · waiting for web UI';
    cardStatusText = 'Starting · web UI offline';
  } else {
    statusDot = 'ok';
    const ruleCount = state?.rules.length;
    statusText =
      ruleCount != null ? `Active · ${ruleCount} ${ruleCount === 1 ? 'rule' : 'rules'}` : 'Active';
    cardStatusText = 'On · proxy and web UI running';
  }

  return (
    <Screen
      title="Agent view"
      subtitle={
        <View style={st.subtitleRow}>
          <StatusDot tone={statusDot} />
          <Text style={[st.subtitleText, { color: t.textDim }]}>{statusText}</Text>
        </View>
      }
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err ? (
        <Banner tone="bad" title={err.title}>
          {err.message}
        </Banner>
      ) : null}

      <Card style={{ gap: space(3) }}>
        <View style={st.masterRow}>
          <Icon
            name={displayEnabled ? 'robot' : 'robot-off'}
            size={22}
            color={isActive ? t.accent : t.textDim}
          />
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={[st.masterTitle, { color: t.text }]}>Agent view</Text>
            <View style={st.statusInline}>
              <StatusDot tone={statusDot} />
              <Text style={[st.masterStatus, { color: t.textDim }]}>{cardStatusText}</Text>
            </View>
          </View>
          {switching !== null ? (
            <ActivityIndicator size="small" color={t.textDim} />
          ) : null}
          <Switch
            value={displayEnabled}
            onValueChange={(v) => void toggleMaster(v)}
            disabled={!master || switching !== null}
            accessibilityLabel="Agent view master switch"
            trackColor={{ true: t.accent, false: t.border }}
            thumbColor={t.onMedia}
          />
        </View>

        {isOff ? (
          <View style={[st.offNote, { borderTopColor: t.border }]}>
            <Text style={[st.offBody, { color: t.textDim }]}>
              Capture proxy, MITM filter, and web UI are stopped. Turn on to inspect captured URLs and manage rewrite rules.
            </Text>
          </View>
        ) : null}
      </Card>

      {isStartingOrOffline && !err ? (
        <Banner tone="warn" title="Web UI isn't responding yet">
          <View style={{ gap: space(2.5) }}>
            <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>
              Agent view is turned on, but the local web service on port 1991 has not answered yet.
            </Text>
            <View style={{ flexDirection: 'row' }}>
              <Button label="Retry" icon="refresh" kind="subtle" compact onPress={() => void load()} />
            </View>
          </View>
        </Banner>
      ) : null}

      {isActive ? (
        <>
          <Segmented
            value={tab}
            onChange={setTab}
            options={[
              { id: 'urls', label: 'URLs', icon: 'link-variant' },
              { id: 'rules', label: 'Rules', icon: 'filter-variant' },
            ]}
          />

          {tab === 'urls' ? (
            <>
              <View style={[st.search, { backgroundColor: t.card }]}>
                <Icon name="magnify" size={18} color={t.textFaint} />
                <TextInput
                  value={q}
                  onChangeText={setQ}
                  placeholder="Search URLs"
                  placeholderTextColor={t.textFaint}
                  selectionColor={t.accent}
                  style={{ flex: 1, color: t.text, fontSize: 15, paddingVertical: 0 }}
                  autoCapitalize="none"
                  autoCorrect={false}
                />
                {q ? (
                  <Pressable onPress={() => setQ('')} hitSlop={8} accessibilityLabel="Clear search">
                    <Icon name="close-circle" size={18} color={t.textFaint} />
                  </Pressable>
                ) : null}
              </View>

              {urls === null && !err ? (
                <Card>
                  <Text style={{ color: t.textDim }}>Loading…</Text>
                </Card>
              ) : null}

              {urls && urls.length === 0 ? (
                <Empty icon={q ? 'magnify-close' : 'link-off'} title={q ? 'No matches' : 'No URLs yet'} />
              ) : null}

              {urls && urls.length ? (
                <List>
                  {urls.map((u, i) => {
                    const { host, path } = splitUrl(u.url);
                    const rule = state?.rules.find((r) => r.id === u.rule_id);
                    return (
                      <ListRow
                        key={u.url}
                        icon="link-variant"
                        title={host || u.host}
                        subtitle={
                          <View style={{ gap: 2 }}>
                            <Text style={{ color: t.textFaint, fontSize: 12.5, fontFamily: t.mono }} numberOfLines={1}>
                              {path}
                            </Text>
                            <Text style={{ color: t.textFaint, fontSize: 12, fontVariant: ['tabular-nums'] }}>
                              {relativeTime(u.last_seen, now)} · {u.count}×
                            </Text>
                          </View>
                        }
                        trailing={
                          rule ? (
                            <Pill
                              label={rule.name ? (rule.name.length > 18 ? `${rule.name.slice(0, 17)}…` : rule.name) : 'Rule'}
                              tone="dim"
                              icon="filter-variant"
                            />
                          ) : undefined
                        }
                        chevron
                        last={i === urls.length - 1}
                        onPress={() => editUrl(u)}
                      />
                    );
                  })}
                </List>
              ) : null}
            </>
          ) : (
            <>
              {state === null && !err ? (
                <Card>
                  <Text style={{ color: t.textDim }}>Loading…</Text>
                </Card>
              ) : null}

              {state && state.rules.length === 0 ? (
                <Empty icon="filter-off" title="No rules yet" />
              ) : null}

              {state && state.rules.length ? (
                <List>
                  {state.rules.map((rule, i) => (
                    <View
                      key={rule.id}
                      style={i > 0 ? { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border } : undefined}
                    >
                      <View
                        style={{
                          flexDirection: 'row',
                          alignItems: 'center',
                          gap: space(3),
                          paddingHorizontal: space(4),
                          paddingTop: space(3),
                        }}
                      >
                        <Pressable
                          onPress={() => nav.navigate('AgentRule', { ruleId: rule.id })}
                          android_ripple={{ color: t.ripple }}
                          style={({ pressed }) => [st.ruleMain, pressed && { opacity: 0.85 }]}
                        >
                          <Icon name={ACTION_ICONS[rule.action]} size={20} color={t.textDim} />
                          <View style={{ flex: 1, gap: 2 }}>
                            <Text style={{ color: t.text, fontSize: 15, fontWeight: '500' }} numberOfLines={1}>
                              {rule.name || rule.match}
                            </Text>
                            <Text style={{ color: t.textFaint, fontSize: 12.5, fontFamily: t.mono }} numberOfLines={1}>
                              {rule.match}
                            </Text>
                            <Text style={{ color: t.textDim, fontSize: 12 }}>{actionLabel(rule.action)}</Text>
                          </View>
                        </Pressable>
                        <Switch
                          value={rule.enabled}
                          onValueChange={(v) => toggleRule(rule, v)}
                          trackColor={{ true: t.accent, false: t.border }}
                          thumbColor={t.onMedia}
                        />
                      </View>
                      <View style={st.ruleActions}>
                        <Pressable
                          onPress={() => moveRule(i, -1)}
                          disabled={i === 0 || busy}
                          hitSlop={8}
                          accessibilityLabel="Move rule up"
                          style={[st.iconBtn, { opacity: i === 0 ? 0.35 : 1 }]}
                        >
                          <Icon name="arrow-up" size={20} color={t.textDim} />
                        </Pressable>
                        <Pressable
                          onPress={() => moveRule(i, 1)}
                          disabled={i === state.rules.length - 1 || busy}
                          hitSlop={8}
                          accessibilityLabel="Move rule down"
                          style={[st.iconBtn, { opacity: i === state.rules.length - 1 ? 0.35 : 1 }]}
                        >
                          <Icon name="arrow-down" size={20} color={t.textDim} />
                        </Pressable>
                        <View style={{ flex: 1 }} />
                        <Pressable
                          onPress={() => removeRule(rule)}
                          hitSlop={8}
                          accessibilityLabel="Delete rule"
                          style={st.iconBtn}
                        >
                          <Icon name="trash-can" size={20} color={t.bad} />
                        </Pressable>
                      </View>
                    </View>
                  ))}
                </List>
              ) : null}
            </>
          )}
        </>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  subtitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  subtitleText: { fontSize: 12.5, fontVariant: ['tabular-nums'] },
  masterRow: { flexDirection: 'row', alignItems: 'center', gap: space(3) },
  masterTitle: { fontSize: 16, fontWeight: '600' },
  statusInline: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  masterStatus: { fontSize: 13 },
  offNote: { borderTopWidth: StyleSheet.hairlineWidth, paddingTop: space(3) },
  offBody: { fontSize: 13.5, lineHeight: 19 },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    borderRadius: radius.md,
    paddingHorizontal: space(3),
    height: 44,
  },
  ruleMain: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space(3) },
  ruleActions: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: space(2),
    paddingBottom: 2,
  },
  iconBtn: { width: 42, height: 38, alignItems: 'center', justifyContent: 'center' },
});
