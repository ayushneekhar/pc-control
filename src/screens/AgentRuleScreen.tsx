import { useNavigation, useRoute } from '@react-navigation/native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Modal, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, Icon, Mono, Screen, Section } from '../components/ui';
import {
  AgentAction,
  AgentRule,
  buildExactMatch,
  buildRulePayload,
  buildSiteMatch,
  createRuleFromUrl,
  deleteAgentRule,
  getAgentRules,
  getOriginalHtml,
  MatchMode,
  MatchType,
  OriginalMeta,
  Replacement,
  splitUrl,
  updateAgentRule,
  urlFromMatch,
} from '../lib/agentview';
import { radius, space, useTheme } from '../theme';

type Params = { url?: string; ruleId?: string };

type Draft = {
  name: string;
  matchMode: MatchMode;
  customMatch: string;
  customMatchType: MatchType;
  action: AgentAction;
  html: string;
  replacements: Replacement[];
  selectors: string[];
  blockMode: '404' | 'reset';
  enabled: boolean;
};

const ACTIONS: { id: AgentAction; icon: string; title: string; body: string }[] = [
  { id: 'replace_page', icon: 'file-document-edit-outline', title: 'Show my page', body: 'Serve your own HTML instead of the real page.' },
  { id: 'replace_text', icon: 'find-replace', title: 'Replace text', body: 'Find and replace text in HTML, text, or JSON.' },
  { id: 'hide', icon: 'eye-off-outline', title: 'Hide elements', body: 'Remove matching elements with CSS selectors.' },
  { id: 'block', icon: 'cancel', title: 'Block', body: 'Fail the request with a 404 or a connection reset.' },
];

const ACTION_NAMES: Record<AgentAction, string> = {
  replace_page: 'Show my page',
  replace_text: 'Replace text',
  hide: 'Hide elements',
  block: 'Block',
};

type PreviewState = { html: string; label: string };

const PREVIEW_LOCKDOWN = `
(function () {
  try {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition = function (success, failure) {
        failure && failure({ code: 1, message: 'Geolocation is disabled.' });
      };
      navigator.geolocation.watchPosition = function (success, failure) {
        failure && failure({ code: 1, message: 'Geolocation is disabled.' });
        return 0;
      };
    }
  } catch (e) {}
  try {
    if (navigator.mediaDevices) {
      navigator.mediaDevices.getUserMedia = function () { return Promise.reject(new Error('Media capture is disabled.')); };
      navigator.mediaDevices.getDisplayMedia = function () { return Promise.reject(new Error('Media capture is disabled.')); };
      navigator.mediaDevices.enumerateDevices = function () { return Promise.resolve([]); };
    }
    navigator.getUserMedia = function (constraints, success, failure) { failure && failure(new Error('Media capture is disabled.')); };
    navigator.webkitGetUserMedia = navigator.getUserMedia;
  } catch (e) {}
  try {
    if (navigator.permissions && navigator.permissions.query) {
      var query = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (desc) {
        if (desc && (desc.name === 'geolocation' || desc.name === 'camera' || desc.name === 'microphone')) {
          return Promise.resolve({ state: 'denied', onchange: null });
        }
        return query(desc);
      };
    }
  } catch (e) {}
})();
true;
`;

function urlOrigin(raw: string): string | undefined {
  const m = /^([a-z][a-z0-9+.-]*:)\/\/([^/?#]+)/i.exec((raw ?? '').trim());
  return m ? `${m[1]}//${m[2]}` : undefined;
}

function draftFrom(rule: AgentRule, url: string | null): Draft {
  const exact = url ? buildExactMatch(url) : null;
  const site = url ? buildSiteMatch(url) : null;
  const mode: MatchMode = exact && rule.match === exact.match ? 'exact' : site && rule.match === site.match ? 'site' : 'custom';
  return {
    name: rule.name ?? '',
    matchMode: mode,
    customMatch: rule.match,
    customMatchType: rule.matchType,
    action: rule.action,
    html: rule.html ?? '',
    replacements: rule.replacements ?? [],
    selectors: rule.selectors ?? [],
    blockMode: rule.blockMode === 'reset' ? 'reset' : '404',
    enabled: rule.enabled,
  };
}

export default function AgentRuleScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const nav = useNavigation<any>();
  const params = (useRoute().params ?? {}) as Params;
  const [loading, setLoading] = useState(true);
  const [rule, setRule] = useState<AgentRule | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [original, setOriginal] = useState<OriginalMeta | null>(null);
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [previewBlocked, setPreviewBlocked] = useState(false);
  const previewLoaded = useRef(false);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const ruleUrl = params.url ?? (rule ? urlFromMatch(rule.match, rule.matchType) : null) ?? '';
  const previewBaseUrl = useMemo(() => urlOrigin(ruleUrl), [ruleUrl]);
  const previewSource = useMemo(
    () => (preview ? { html: preview.html, baseUrl: previewBaseUrl } : null),
    [preview, previewBaseUrl]
  );

  useEffect(() => {
    let off = false;
    (async () => {
      setLoading(true);
      try {
        if (!params.ruleId) {
          if (!params.url) throw new Error('No URL or rule to edit.');
          if (!off) {
            setRule(null);
            setDraft(null);
            setErr(null);
          }
          return;
        }
        const state = await getAgentRules();
        const found = state.rules.find((r) => r.id === params.ruleId);
        if (!found) throw new Error('That rule no longer exists.');
        const knownUrl = params.url ?? urlFromMatch(found.match, found.matchType);
        if (off) return;
        setRule(found);
        setDraft(draftFrom(found, knownUrl));
        setErr(null);
      } catch (e) {
        if (!off) setErr((e as Error).message);
      } finally {
        if (!off) setLoading(false);
      }
    })();
    return () => {
      off = true;
    };
  }, [params.ruleId, params.url]);

  const chooseAction = async (action: AgentAction) => {
    if (!params.url || busy) return;
    setBusy(action);
    try {
      const res = await createRuleFromUrl(params.url, action);
      setRule(res.rule);
      setDraft(draftFrom(res.rule, params.url));
      setOriginal(res.original ?? { available: false });
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const patch = (p: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...p } : d));

  const save = async () => {
    if (!rule || !draft) return;
    setBusy('save');
    try {
      await updateAgentRule(rule.id, buildRulePayload({ ...draft, url: ruleUrl || null }));
      setErr(null);
      nav.goBack();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const remove = () => {
    if (!rule) return;
    Alert.alert('Delete rule?', rule.name || rule.match, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          try {
            await deleteAgentRule(rule.id);
            nav.goBack();
          } catch (e) {
            setErr((e as Error).message);
          }
        },
      },
    ]);
  };

  const resetOriginal = async () => {
    if (!ruleUrl) {
      setErr('This rule has no URL to reset from.');
      return;
    }
    setBusy('original');
    try {
      const o = await getOriginalHtml(ruleUrl);
      patch({ html: o.html });
      setOriginal({ available: true, captured_at: o.captured_at, size: o.size, truncated: o.truncated });
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const openPreview = (html: string, label: string) => {
    if (!html.trim()) return;
    previewLoaded.current = false;
    setPreviewBlocked(false);
    setPreview({ html, label });
  };

  const previewOriginal = async () => {
    if (!ruleUrl) {
      setErr('This rule has no URL to preview.');
      return;
    }
    setBusy('original-preview');
    try {
      const o = await getOriginalHtml(ruleUrl);
      setOriginal({ available: true, captured_at: o.captured_at, size: o.size, truncated: o.truncated });
      setErr(null);
      openPreview(o.html, 'Original HTML');
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const closePreview = () => {
    setPreview(null);
    setPreviewBlocked(false);
    previewLoaded.current = false;
  };

  const typography = { color: t.text, backgroundColor: t.cardAlt, borderColor: t.border, borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.md, paddingHorizontal: space(3), paddingVertical: space(2.5), fontSize: 14 };
  const { host, path } = splitUrl(ruleUrl);
  const title = rule ? 'Edit rule' : 'New rule';
  const shownMatch = !draft
    ? ''
    : draft.matchMode === 'exact' && ruleUrl
      ? buildExactMatch(ruleUrl).match
      : draft.matchMode === 'site' && ruleUrl
        ? buildSiteMatch(ruleUrl).match
        : draft.customMatch;
  const shownMatchType: MatchType =
    draft?.matchMode === 'exact' ? 'regex' : draft?.matchMode === 'site' ? 'glob' : draft?.customMatchType ?? 'glob';

  return (
    <>
      <Screen
        title={title}
        subtitle={ruleUrl ? `${host}${path}` : 'Agent view'}
        onBack={() => nav.goBack()}
        scroll
      >
      {ruleUrl ? (
        <Card style={{ gap: 2 }}>
          <Text style={{ color: t.textFaint, fontSize: 12, fontWeight: '700', letterSpacing: 0.6 }}>URL</Text>
          <Mono style={{ fontSize: 12 }}>{ruleUrl}</Mono>
        </Card>
      ) : null}

      {err ? (
        <Banner tone="bad" title="Agent view is unreachable">
          {err}
        </Banner>
      ) : null}

      {loading ? (
        <Card>
          <Text style={{ color: t.textDim }}>Loading…</Text>
        </Card>
      ) : null}

      {!loading && !rule && params.url ? (
        <>
          <Section label="Action">
            {ACTIONS.map((a) => (
              <Card key={a.id} onPress={() => chooseAction(a.id)} style={{ gap: space(2) }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
                  <View style={[st.actionIcon, { backgroundColor: t.cardAlt }]}>
                    <Icon name={a.icon} size={22} color={t.textDim} />
                  </View>
                  <View style={{ flex: 1, gap: 2 }}>
                    <Text style={{ color: t.text, fontSize: 15.5, fontWeight: '700' }}>{a.title}</Text>
                    <Text style={{ color: t.textDim, fontSize: 13, lineHeight: 18 }}>{a.body}</Text>
                  </View>
                  {busy === a.id ? <Icon name="progress-clock" size={20} color={t.textFaint} /> : <Icon name="chevron-right" size={22} color={t.textFaint} />}
                </View>
              </Card>
            ))}
          </Section>
          <Text style={{ color: t.textFaint, fontSize: 12.5, lineHeight: 18, paddingHorizontal: space(1) }}>
            The rule is created immediately with agentview&apos;s from-url match and inserted above the others.
          </Text>
        </>
      ) : null}

      {!loading && rule && draft ? (
        <>
          <Section label="Rule">
            <Card style={{ gap: space(3) }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
                <View style={{ flex: 1 }}>
                  <Text style={{ color: t.text, fontSize: 15, fontWeight: '700' }}>{ACTION_NAMES[draft.action]}</Text>
                  <Text style={{ color: t.textDim, fontSize: 12.5 }}>{ruleUrl ? host : rule.match}</Text>
                </View>
                <Switch
                  value={draft.enabled}
                  onValueChange={(v) => patch({ enabled: v })}
                  trackColor={{ true: t.accent, false: t.border }}
                  thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'}
                />
              </View>
              <View style={{ gap: 6 }}>
                <Text style={{ color: t.textDim, fontSize: 12.5 }}>Name</Text>
                <TextInput
                  value={draft.name}
                  onChangeText={(v) => patch({ name: v })}
                  placeholder="Rule name"
                  placeholderTextColor={t.textFaint}
                  style={typography}
                  autoCapitalize="none"
                  autoCorrect={false}
                />
              </View>
            </Card>
          </Section>

          <Section label="Match">
            <Card style={{ gap: space(3) }}>
              <View style={{ flexDirection: 'row', gap: space(2) }}>
                {(['exact', 'site', 'custom'] as MatchMode[]).map((m) => {
                  const on = draft.matchMode === m;
                  const label = m === 'exact' ? 'Exact URL' : m === 'site' ? 'Whole site' : 'Custom';
                  return (
                    <Pressable
                      key={m}
                      onPress={() => patch({ matchMode: m, customMatchType: m === 'exact' ? 'regex' : m === 'site' ? 'glob' : draft.customMatchType })}
                      style={[st.choice, { backgroundColor: on ? t.cardAlt : 'transparent', borderColor: on ? t.border : t.border }]}
                    >
                      <Text style={{ color: on ? t.text : t.textDim, fontSize: 12.5, fontWeight: '700' }}>{label}</Text>
                    </Pressable>
                  );
                })}
              </View>
              <TextInput
                value={shownMatch}
                onChangeText={(v) => patch({ matchMode: 'custom', customMatch: v, customMatchType: shownMatchType })}
                placeholder="Match pattern"
                placeholderTextColor={t.textFaint}
                style={[typography, { fontFamily: t.mono, fontSize: 12 }]}
                autoCapitalize="none"
                autoCorrect={false}
              />
              {draft.matchMode === 'custom' ? (
                <View style={{ flexDirection: 'row', gap: space(2) }}>
                  {(['glob', 'regex'] as MatchType[]).map((m) => {
                    const on = draft.customMatchType === m;
                    return (
                      <Pressable
                        key={m}
                        onPress={() => patch({ customMatchType: m })}
                        style={[st.choice, { backgroundColor: on ? t.cardAlt : 'transparent', borderColor: t.border }]}
                      >
                        <Text style={{ color: on ? t.text : t.textDim, fontSize: 12.5, fontWeight: '700' }}>{m}</Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : (
                <Text style={{ color: t.textFaint, fontSize: 12, lineHeight: 17 }}>
                  {draft.matchMode === 'exact'
                    ? 'Matches this exact URL only.'
                    : `Matches every page on ${host || 'this host'} (${shownMatch}).`}
                </Text>
              )}
            </Card>
          </Section>

          {draft.action === 'replace_page' ? (
            <Section label="HTML">
              <Card style={{ gap: space(3) }}>
                {original && original.truncated ? (
                  <Banner tone="warn" title="Original was truncated">
                    The captured HTML is capped at 2 MiB, so the editor may not include the whole page.
                  </Banner>
                ) : null}
                {original && original.available === false ? (
                  <Banner tone="warn" title="No original captured">
                    No HTML was captured for this URL yet. Paste your own HTML or let an agent open the page through the proxy first.
                  </Banner>
                ) : null}
                <TextInput
                  value={draft.html}
                  onChangeText={(v) => patch({ html: v })}
                  placeholder="<!doctype html>…"
                  placeholderTextColor={t.textFaint}
                  style={[typography, { fontFamily: t.mono, fontSize: 12, lineHeight: 17, height: 360, textAlignVertical: 'top' }]}
                  multiline
                  scrollEnabled
                  autoCapitalize="none"
                  autoCorrect={false}
                  spellCheck={false}
                />
                <View style={{ flexDirection: 'row', gap: space(2) }}>
                  <Button
                    label="Preview"
                    icon="eye-outline"
                    kind="subtle"
                    compact
                    disabled={!draft.html.trim()}
                    style={{ flex: 1 }}
                    onPress={() => openPreview(draft.html, 'Edited HTML')}
                  />
                </View>
                <View style={{ flexDirection: 'row', gap: space(2) }}>
                  <Button
                    label="Preview original"
                    icon="eye-outline"
                    kind="subtle"
                    compact
                    busy={busy === 'original-preview'}
                    style={{ flex: 1 }}
                    onPress={previewOriginal}
                  />
                  <Button
                    label="Reset to original"
                    icon="history"
                    kind="subtle"
                    compact
                    busy={busy === 'original'}
                    style={{ flex: 1 }}
                    onPress={resetOriginal}
                  />
                </View>
              </Card>
            </Section>
          ) : null}

          {draft.action === 'replace_text' ? (
            <Section label="Replacements">
              {draft.replacements.length === 0 ? (
                <Text style={{ color: t.textDim, fontSize: 13 }}>No pairs yet.</Text>
              ) : null}
              {draft.replacements.map((pair, i) => (
                <Card key={i} style={{ gap: space(3) }}>
                  <View style={{ gap: 6 }}>
                    <Text style={{ color: t.textDim, fontSize: 12.5 }}>Find</Text>
                    <TextInput
                      value={pair.find}
                      onChangeText={(v) =>
                        patch({ replacements: draft.replacements.map((p, j) => (j === i ? { ...p, find: v } : p)) })
                      }
                      placeholder="Text or regex"
                      placeholderTextColor={t.textFaint}
                      style={[typography, { fontFamily: t.mono, fontSize: 12.5 }]}
                      autoCapitalize="none"
                      autoCorrect={false}
                    />
                  </View>
                  <View style={{ gap: 6 }}>
                    <Text style={{ color: t.textDim, fontSize: 12.5 }}>Replace with</Text>
                    <TextInput
                      value={pair.replace}
                      onChangeText={(v) =>
                        patch({ replacements: draft.replacements.map((p, j) => (j === i ? { ...p, replace: v } : p)) })
                      }
                      placeholder="Replacement"
                      placeholderTextColor={t.textFaint}
                      style={[typography, { fontFamily: t.mono, fontSize: 12.5 }]}
                      autoCapitalize="none"
                      autoCorrect={false}
                    />
                  </View>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                    <Text style={{ color: t.textDim, fontSize: 13, flex: 1 }}>Regular expression</Text>
                    <Switch
                      value={pair.regex}
                      onValueChange={(v) =>
                        patch({ replacements: draft.replacements.map((p, j) => (j === i ? { ...p, regex: v } : p)) })
                      }
                      trackColor={{ true: t.accent, false: t.border }}
                      thumbColor={t.dark ? '#ECEFF5' : '#FFFFFF'}
                    />
                    <Pressable
                      onPress={() => patch({ replacements: draft.replacements.filter((_, j) => j !== i) })}
                      hitSlop={8}
                      accessibilityLabel="Remove pair"
                    >
                      <Icon name="trash-can-outline" size={20} color={t.bad} />
                    </Pressable>
                  </View>
                </Card>
              ))}
              <Button
                label="Add pair"
                icon="plus"
                kind="subtle"
                onPress={() => patch({ replacements: [...draft.replacements, { find: '', replace: '', regex: false }] })}
              />
            </Section>
          ) : null}

          {draft.action === 'hide' ? (
            <Section label="Selectors">
              {draft.selectors.length === 0 ? (
                <Text style={{ color: t.textDim, fontSize: 13 }}>No selectors yet.</Text>
              ) : null}
              {draft.selectors.map((sel, i) => (
                <View key={i} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                  <TextInput
                    value={sel}
                    onChangeText={(v) => patch({ selectors: draft.selectors.map((s, j) => (j === i ? v : s)) })}
                    placeholder=".ad-banner"
                    placeholderTextColor={t.textFaint}
                    style={[typography, { flex: 1, fontFamily: t.mono, fontSize: 12.5 }]}
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                  <Pressable
                    onPress={() => patch({ selectors: draft.selectors.filter((_, j) => j !== i) })}
                    hitSlop={8}
                    accessibilityLabel="Remove selector"
                  >
                    <Icon name="trash-can-outline" size={20} color={t.bad} />
                  </Pressable>
                </View>
              ))}
              <Button label="Add selector" icon="plus" kind="subtle" onPress={() => patch({ selectors: [...draft.selectors, ''] })} />
            </Section>
          ) : null}

          {draft.action === 'block' ? (
            <Section label="Block mode">
              <Card style={{ gap: space(2) }}>
                {(
                  [
                    { id: '404', title: '404 Not Found', body: 'A plain generic page; the site looks missing.' },
                    { id: 'reset', title: 'Connection reset', body: 'The connection is dropped before the site is fetched.' },
                  ] as const
                ).map((m) => {
                  const on = draft.blockMode === m.id;
                  return (
                    <Pressable
                      key={m.id}
                      onPress={() => patch({ blockMode: m.id })}
                      style={[st.radioRow, { backgroundColor: on ? t.cardAlt : 'transparent', borderColor: t.border }]}
                    >
                      <Icon name={on ? 'radiobox-marked' : 'radiobox-blank'} size={20} color={on ? t.text : t.textFaint} />
                      <View style={{ flex: 1, gap: 1 }}>
                        <Text style={{ color: t.text, fontSize: 14, fontWeight: '700' }}>{m.title}</Text>
                        <Text style={{ color: t.textDim, fontSize: 12.5 }}>{m.body}</Text>
                      </View>
                    </Pressable>
                  );
                })}
              </Card>
            </Section>
          ) : null}

          <View style={{ flexDirection: 'row', gap: space(2), marginTop: space(2) }}>
            <Button label="Delete" icon="trash-can-outline" kind="danger" style={{ flex: 1 }} onPress={remove} />
            <Button label="Save" icon="check" style={{ flex: 2 }} busy={busy === 'save'} onPress={save} />
          </View>
        </>
      ) : null}

      {!loading && !rule && !params.url ? (
        <Banner tone="bad" title="Nothing to edit">
          Open a URL or a rule from Agent view.
        </Banner>
      ) : null}
      </Screen>

      {preview && previewSource ? (
        <Modal visible animationType="slide" onRequestClose={closePreview} statusBarTranslucent navigationBarTranslucent>
          <View style={{ flex: 1, backgroundColor: t.bg, paddingTop: insets.top }}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                gap: space(2),
                paddingHorizontal: space(4),
                paddingVertical: space(3),
                borderBottomWidth: StyleSheet.hairlineWidth,
                borderBottomColor: t.border,
              }}
            >
              <Pressable onPress={closePreview} hitSlop={8} accessibilityLabel="Close preview" style={st.previewClose}>
                <Icon name="close" size={24} color={t.text} />
              </Pressable>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.text, fontSize: 17, fontWeight: '800' }}>Preview</Text>
                <Text style={{ color: t.textDim, fontSize: 12 }} numberOfLines={1}>
                  {preview.label} · {host}
                </Text>
              </View>
            </View>
            <WebView
              source={previewSource}
              originWhitelist={['*']}
              javaScriptEnabled
              domStorageEnabled
              incognito
              thirdPartyCookiesEnabled={false}
              sharedCookiesEnabled={false}
              allowFileAccess={false}
              allowFileAccessFromFileURLs={false}
              allowUniversalAccessFromFileURLs={false}
              geolocationEnabled={false}
              mediaCapturePermissionGrantType="deny"
              javaScriptCanOpenWindowsAutomatically={false}
              setSupportMultipleWindows={false}
              setBuiltInZoomControls={false}
              setDisplayZoomControls={false}
              saveFormDataDisabled
              allowsLinkPreview={false}
              injectedJavaScriptBeforeContentLoaded={PREVIEW_LOCKDOWN}
              injectedJavaScriptBeforeContentLoadedForMainFrameOnly={false}
              onLoadEnd={() => {
                previewLoaded.current = true;
              }}
              onShouldStartLoadWithRequest={(req) => {
                if (req.url === 'about:blank') return true;
                if (!previewLoaded.current) {
                  const sameOrigin = previewBaseUrl ? urlOrigin(req.url) === previewBaseUrl : req.url.startsWith('data:');
                  if (sameOrigin) return true;
                }
                setPreviewBlocked(true);
                return false;
              }}
              style={{ flex: 1, backgroundColor: t.bg }}
            />
            <View
              style={{
                gap: space(2),
                paddingHorizontal: space(4),
                paddingTop: space(3),
                paddingBottom: insets.bottom + space(3),
                borderTopWidth: StyleSheet.hairlineWidth,
                borderTopColor: t.border,
              }}
            >
              {previewBlocked ? (
                <Text style={{ color: t.warn, fontSize: 12.5, textAlign: 'center' }}>
                  Navigation is disabled in the preview.
                </Text>
              ) : null}
              <Button label="Close preview" kind="subtle" compact onPress={closePreview} />
            </View>
          </View>
        </Modal>
      ) : null}
    </>
  );
}

const st = StyleSheet.create({
  actionIcon: { width: 42, height: 42, borderRadius: 13, alignItems: 'center', justifyContent: 'center' },
  choice: { flex: 1, alignItems: 'center', justifyContent: 'center', height: 38, borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth },
  previewClose: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center', marginLeft: -space(2) },
  radioRow: { flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(3), borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth },
});
