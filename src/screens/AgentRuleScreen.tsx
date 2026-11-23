import { useNavigation, useRoute } from '@react-navigation/native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Modal, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, Icon, List, ListRow, Mono, Screen, Section, Segmented } from '../components/ui';
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
import { radius, screenPad, space, useTheme } from '../theme';

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

const ACTIONS: { id: AgentAction; icon: string; title: string }[] = [
  { id: 'replace_page', icon: 'file-document-edit-outline', title: 'Show my page' },
  { id: 'replace_text', icon: 'find-replace', title: 'Replace text' },
  { id: 'hide', icon: 'eye-off-outline', title: 'Hide elements' },
  { id: 'block', icon: 'cancel', title: 'Block' },
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

  const typography = { color: t.text, backgroundColor: t.cardAlt, borderRadius: radius.sm, paddingHorizontal: space(3), paddingVertical: space(2.5), fontSize: 14 };
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
          <Text style={{ color: t.textDim, fontSize: 12, fontWeight: '500' }}>Url</Text>
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
        <Section label="Action">
          <List>
            {ACTIONS.map((a, i) => (
              <ListRow
                key={a.id}
                icon={a.icon}
                title={a.title}
                trailing={
                  busy === a.id ? (
                    <Icon name="progress-clock" size={20} color={t.textFaint} />
                  ) : undefined
                }
                chevron={busy !== a.id}
                last={i === ACTIONS.length - 1}
                onPress={() => chooseAction(a.id)}
              />
            ))}
          </List>
        </Section>
      ) : null}

      {!loading && rule && draft ? (
        <>
          <Section label="Rule">
            <Card style={{ gap: space(3) }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
                <View style={{ flex: 1 }}>
                  <Text style={{ color: t.text, fontSize: 15, fontWeight: '500' }}>{ACTION_NAMES[draft.action]}</Text>
                  <Text style={{ color: t.textDim, fontSize: 12.5 }}>{ruleUrl ? host : rule.match}</Text>
                </View>
                <Switch
                  value={draft.enabled}
                  onValueChange={(v) => patch({ enabled: v })}
                  trackColor={{ true: t.accent, false: t.border }}
                  thumbColor={t.onMedia}
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
              <Segmented
                value={draft.matchMode}
                onChange={(m) =>
                  patch({
                    matchMode: m,
                    customMatchType: m === 'exact' ? 'regex' : m === 'site' ? 'glob' : draft.customMatchType,
                  })
                }
                options={[
                  { id: 'exact', label: 'Exact URL' },
                  { id: 'site', label: 'Whole site' },
                  { id: 'custom', label: 'Custom' },
                ]}
              />
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
                <Segmented
                  value={draft.customMatchType}
                  onChange={(m) => patch({ customMatchType: m })}
                  options={[
                    { id: 'glob', label: 'Glob' },
                    { id: 'regex', label: 'Regex' },
                  ]}
                />
              ) : null}
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
                    busy={busy === 'original-preview'}
                    style={{ flex: 1 }}
                    onPress={previewOriginal}
                  />
                  <Button
                    label="Reset to original"
                    icon="history"
                    kind="subtle"
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
              {draft.replacements.length ? (
                <List>
                  {draft.replacements.map((pair, i) => (
                    <View
                      key={i}
                      style={[st.formRow, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border }]}
                    >
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
                          thumbColor={t.onMedia}
                        />
                        <Pressable
                          onPress={() => patch({ replacements: draft.replacements.filter((_, j) => j !== i) })}
                          hitSlop={8}
                          accessibilityLabel="Remove pair"
                        >
                          <Icon name="trash-can-outline" size={20} color={t.bad} />
                        </Pressable>
                      </View>
                    </View>
                  ))}
                </List>
              ) : null}
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
              {draft.selectors.length ? (
                <List>
                  {draft.selectors.map((sel, i) => (
                    <View
                      key={i}
                      style={[st.selectorRow, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border }]}
                    >
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
                </List>
              ) : null}
              <Button label="Add selector" icon="plus" kind="subtle" onPress={() => patch({ selectors: [...draft.selectors, ''] })} />
            </Section>
          ) : null}

          {draft.action === 'block' ? (
            <Section label="Block mode">
              <Card style={{ gap: space(2) }}>
                {(
                  [
                    { id: '404', title: '404 Not Found' },
                    { id: 'reset', title: 'Connection reset' },
                  ] as const
                ).map((m) => {
                  const on = draft.blockMode === m.id;
                  return (
                    <Pressable
                      key={m.id}
                      onPress={() => patch({ blockMode: m.id })}
                      style={[st.radioRow, { backgroundColor: on ? t.cardAlt : 'transparent' }]}
                    >
                      <Icon name={on ? 'radiobox-marked' : 'radiobox-blank'} size={20} color={on ? t.text : t.textFaint} />
                      <Text style={{ color: t.text, fontSize: 14, fontWeight: '500', flex: 1 }}>{m.title}</Text>
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
                paddingHorizontal: screenPad,
                paddingVertical: space(3),
                borderBottomWidth: StyleSheet.hairlineWidth,
                borderBottomColor: t.border,
              }}
            >
              <Pressable onPress={closePreview} hitSlop={8} accessibilityLabel="Close preview" style={st.previewClose}>
                <Icon name="close" size={24} color={t.text} />
              </Pressable>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.text, fontSize: 17, fontWeight: '600' }}>Preview</Text>
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
                paddingHorizontal: screenPad,
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
              <Button label="Close preview" kind="subtle" onPress={closePreview} />
            </View>
          </View>
        </Modal>
      ) : null}
    </>
  );
}

const st = StyleSheet.create({
  previewClose: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  radioRow: { flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(3), borderRadius: radius.md },
  formRow: { gap: space(3), padding: space(4) },
  selectorRow: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingHorizontal: space(4), paddingVertical: space(3) },
});
