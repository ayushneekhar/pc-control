import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Banner, Card, Empty, Icon, Pill, Screen } from '../components/ui';
import {
  actionLabel,
  AgentRule,
  AgentState,
  AgentUrl,
  deleteAgentRule,
  getAgentState,
  getAgentUrls,
  relativeTime,
  reorderAgentRules,
  setAgentEnabled,
  splitUrl,
  updateAgentRule,
} from '../lib/agentview';
import { useNow } from '../lib/useNow';
import { radius, space, useTheme } from '../theme';

type Tab = 'urls' | 'rules';

const ACTION_ICONS: Record<AgentRule['action'], string> = {
  replace_page: 'file-document-edit-outline',
  replace_text: 'find-replace',
  hide: 'eye-off-outline',
  block: 'cancel',
};

function Segmented({ value, onChange }: { value: Tab; onChange: (t: Tab) => void }) {
  const t = useTheme();
  const items: { id: Tab; label: string; icon: string }[] = [
    { id: 'urls', label: 'URLs', icon: 'link-variant' },
    { id: 'rules', label: 'Rules', icon: 'filter-variant' },
  ];
  return (
    <View style={[st.seg, { backgroundColor: t.cardAlt }]} accessibilityRole="tablist">
      {items.map((x) => {
        const on = value === x.id;
        return (
          <Pressable
            key={x.id}
            onPress={() => onChange(x.id)}
            accessibilityRole="tab"
            accessibilityState={{ selected: on }}
            style={[st.segBtn, on && { backgroundColor: t.card }]}
          >
            <Icon name={x.icon} size={15} color={on ? t.text : t.textDim} />
            <Text style={{ color: on ? t.text : t.textDim, fontWeight: '700', fontSize: 13 }}>{x.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export default function AgentViewScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const now = useNow(10000);
  const [tab, setTab] = useState<Tab>('urls');
  const [state, setState] = useState<AgentState | null>(null);
  const [urls, setUrls] = useState<AgentUrl[] | null>(null);
  const [q, setQ] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [busy, setBusy] = useState(false);
  const qRef = useRef(q);
  const seq = useRef(0);

  useEffect(() => {
    qRef.current = q;
  }, [q]);

  const load = useCallback(async () => {
    const n = ++seq.current;
    try {
      const [nextState, nextUrls] = await Promise.all([getAgentState(), getAgentUrls(qRef.current)]);
      if (n !== seq.current) return;
      setState(nextState);
      setUrls(nextUrls);
      setErr(null);
    } catch (e) {
      if (n !== seq.current) return;
      setErr((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
      const iv = setInterval(() => {
        if (AppState.currentState === 'active') void load();
      }, 10000);
      const sub = AppState.addEventListener('change', (s) => s === 'active' && void load());
      return () => {
        clearInterval(iv);
        sub.remove();
      };
    }, [load]),
  );

  useEffect(() => {
    const timer = setTimeout(() => void load(), 250);
    return () => clearTimeout(timer);
  }, [q, load]);

  const toggleFilter = async (enabled: boolean) => {
    setBusy(true);
    try {
      setState(await setAgentEnabled(enabled));
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const toggleRule = async (rule: AgentRule, enabled: boolean) => {
    try {
      const next = await updateAgentRule(rule.id, { enabled });
      setState((s) => (s ? { ...s, rules: s.rules.map((r) => (r.id === next.id ? next : r)) } : s));
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
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
      setErr((e as Error).message);
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
            setErr((e as Error).message);
          }
        },
      },
    ]);

  const editUrl = (u: AgentUrl) => nav.navigate('AgentRule', { url: u.url, ruleId: u.rule_id ?? undefined });

  return (
    <Screen
      title="Agent view"
      subtitle="What agents see through the proxy"
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err ? (
        <Banner tone="bad" title="Agent view is unreachable">
          {err}
        </Banner>
      ) : null}

      <Card style={{ gap: space(3) }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={{ color: t.text, fontSize: 16, fontWeight: '700' }}>
              {state?.enabled ? 'Filtering on' : 'Filtering off'}
            </Text>
            <Text style={{ color: t.textDim, fontSize: 13 }}>
              {state?.enabled ? 'Enabled rules change what agents receive.' : 'Agents get the real sites; rules are kept.'}
            </Text>
          </View>
          <Switch
            value={!!state?.enabled}
            onValueChange={toggleFilter}
            disabled={!state || busy}
            trackColor={{ true: t.accent, false: t.border }}
            thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'}
          />
        </View>
      </Card>

      <Segmented value={tab} onChange={setTab} />

      {tab === 'urls' ? (
        <>
          <View style={[st.search, { backgroundColor: t.card, borderColor: t.border }]}>
            <Icon name="magnify" size={18} color={t.textFaint} />
            <TextInput
              value={q}
              onChangeText={setQ}
              placeholder="Search URLs"
              placeholderTextColor={t.textFaint}
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
            <Empty
              icon={q ? 'magnify-close' : 'link-off'}
              title={q ? 'No matches' : 'No URLs yet'}
              body={q ? 'Try a different host or path.' : 'URLs appear here once an agent opens a site through the proxy.'}
            />
          ) : null}

          {(urls ?? []).map((u) => {
            const { host, path } = splitUrl(u.url);
            const rule = state?.rules.find((r) => r.id === u.rule_id);
            return (
              <Card key={u.url} onPress={() => editUrl(u)} style={{ gap: space(2) }}>
                <View style={{ flexDirection: 'row', gap: space(2), alignItems: 'flex-start' }}>
                  <View style={{ flex: 1, gap: 2 }}>
                    <Text style={{ color: t.text, fontSize: 15.5, fontWeight: '700' }} numberOfLines={1}>
                      {host || u.host}
                    </Text>
                    <Text style={{ color: t.textDim, fontSize: 13, fontFamily: t.mono }} numberOfLines={2}>
                      {path}
                    </Text>
                  </View>
                  {rule ? (
                    <Pill
                      label={rule.name ? (rule.name.length > 18 ? `${rule.name.slice(0, 17)}…` : rule.name) : 'Rule'}
                      tone="dim"
                      icon="filter-variant"
                    />
                  ) : null}
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <Text style={{ color: t.textFaint, fontSize: 12 }}>{relativeTime(u.last_seen, now)}</Text>
                  <Text style={{ color: t.textFaint, fontSize: 12 }}>·</Text>
                  <Text style={{ color: t.textFaint, fontSize: 12 }}>{u.count}×</Text>
                </View>
              </Card>
            );
          })}
        </>
      ) : (
        <>
          {state && state.rules.length === 0 ? (
            <Empty icon="filter-off-outline" title="No rules yet" body="Open a URL from the URLs tab to add a rule." />
          ) : null}

          {(state?.rules ?? []).map((rule, i) => (
            <Card key={rule.id} style={{ padding: 0 }}>
              <View style={st.ruleTop}>
                <Pressable
                  onPress={() => nav.navigate('AgentRule', { ruleId: rule.id })}
                  android_ripple={{ color: t.accentSoft }}
                  style={({ pressed }) => [st.ruleMain, pressed && { opacity: 0.85 }]}
                >
                  <View style={[st.ruleIcon, { backgroundColor: t.cardAlt }]}>
                    <Icon name={ACTION_ICONS[rule.action]} size={19} color={t.textDim} />
                  </View>
                  <View style={{ flex: 1, gap: 2 }}>
                    <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '700' }} numberOfLines={1}>
                      {rule.name || rule.match}
                    </Text>
                    <Text style={{ color: t.textFaint, fontSize: 12, fontFamily: t.mono }} numberOfLines={1}>
                      {rule.match}
                    </Text>
                    <Text style={{ color: t.textDim, fontSize: 12 }}>{actionLabel(rule.action)}</Text>
                  </View>
                </Pressable>
                <Switch
                  value={rule.enabled}
                  onValueChange={(v) => toggleRule(rule, v)}
                  trackColor={{ true: t.accent, false: t.border }}
                  thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'}
                />
              </View>
              <View style={[st.ruleActions, { borderTopColor: t.border }]}>
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
                  disabled={i === state!.rules.length - 1 || busy}
                  hitSlop={8}
                  accessibilityLabel="Move rule down"
                  style={[st.iconBtn, { opacity: i === state!.rules.length - 1 ? 0.35 : 1 }]}
                >
                  <Icon name="arrow-down" size={20} color={t.textDim} />
                </Pressable>
                <View style={{ flex: 1 }} />
                <Pressable onPress={() => removeRule(rule)} hitSlop={8} accessibilityLabel="Delete rule" style={st.iconBtn}>
                  <Icon name="trash-can-outline" size={20} color={t.bad} />
                </Pressable>
              </View>
            </Card>
          ))}
        </>
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
  seg: { flexDirection: 'row', padding: 3, borderRadius: 99, gap: 2 },
  segBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    height: 36,
    borderRadius: 99,
  },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space(3),
    height: 44,
  },
  ruleTop: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingRight: space(3) },
  ruleMain: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(4) },
  ruleIcon: { width: 38, height: 38, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  ruleActions: {
    flexDirection: 'row',
    alignItems: 'center',
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space(2),
    paddingVertical: 2,
  },
  iconBtn: { width: 42, height: 38, alignItems: 'center', justifyContent: 'center' },
});
