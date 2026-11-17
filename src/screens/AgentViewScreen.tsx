import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Banner, Card, Empty, Icon, List, ListRow, Pill, Screen, Segmented } from '../components/ui';
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
            <Text style={{ color: t.text, fontSize: 16, fontWeight: '600' }}>
              {state?.enabled ? 'Filtering on' : 'Filtering off'}
            </Text>
            <Text style={{ color: t.textDim, fontSize: 12.5 }}>
              {state?.enabled ? 'Enabled rules change what agents receive.' : 'Agents get the real sites; rules are kept.'}
            </Text>
          </View>
          <Switch
            value={!!state?.enabled}
            onValueChange={toggleFilter}
            disabled={!state || busy}
            trackColor={{ true: t.accent, false: t.border }}
            thumbColor={t.onMedia}
          />
        </View>
      </Card>

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
            <Empty
              icon={q ? 'magnify-close' : 'link-off'}
              title={q ? 'No matches' : 'No URLs yet'}
              body={q ? 'Try a different host or path.' : 'URLs appear here once an agent opens a site through the proxy.'}
            />
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
                        <Text style={{ color: t.textFaint, fontSize: 12 }}>
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
          {state && state.rules.length === 0 ? (
            <Empty icon="filter-off-outline" title="No rules yet" body="Open a URL from the URLs tab to add a rule." />
          ) : null}

          {state && state.rules.length ? (
            <List>
              {state.rules.map((rule, i) => (
                <View key={rule.id} style={i > 0 ? { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border } : undefined}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3), paddingHorizontal: space(4), paddingTop: space(3) }}>
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
                    <Pressable onPress={() => removeRule(rule)} hitSlop={8} accessibilityLabel="Delete rule" style={st.iconBtn}>
                      <Icon name="trash-can-outline" size={20} color={t.bad} />
                    </Pressable>
                  </View>
                </View>
              ))}
            </List>
          ) : null}
        </>
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
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
