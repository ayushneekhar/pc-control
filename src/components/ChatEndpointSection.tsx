import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button, Card, Icon, Pill, Section } from './ui';
import { syncNativeChatConfig } from '../lib/chatApi';
import { getChatEndpoint, setChatEndpoint } from '../lib/chatLogic';
import { radius, space, useTheme } from '../theme';

export function ChatEndpointSection() {
  const t = useTheme();
  const initial = getChatEndpoint();
  const [url, setUrl] = useState(initial.url);
  const [token, setToken] = useState(initial.token);
  const [savedUrl, setSavedUrl] = useState(initial.url);
  const [savedToken, setSavedToken] = useState(initial.token);
  const [showToken, setShowToken] = useState(false);

  const dirty = url.trim() !== savedUrl || token.trim() !== savedToken;
  const active = Boolean(savedUrl);

  const onSave = () => {
    const clean = setChatEndpoint({ url, token });
    syncNativeChatConfig();
    setUrl(clean.url);
    setToken(clean.token);
    setSavedUrl(clean.url);
    setSavedToken(clean.token);
  };

  const onClear = () => {
    const clean = setChatEndpoint({ url: '', token: '' });
    syncNativeChatConfig();
    setUrl(clean.url);
    setToken(clean.token);
    setSavedUrl(clean.url);
    setSavedToken(clean.token);
  };

  return (
    <Section
      label="Chat endpoint"
      right={<Pill label={active ? 'Custom' : 'Tailnet'} tone={active ? 'accent' : 'dim'} dot={active} />}
    >
      <Card style={{ gap: space(3) }}>
        <View style={{ gap: space(1.5) }}>
          <Text style={[st.label, { color: t.textDim }]}>Chat URL</Text>
          <TextInput
            value={url}
            onChangeText={setUrl}
            placeholder="https://agent.neekhar.dev"
            placeholderTextColor={t.textFaint}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            style={[st.input, { backgroundColor: t.cardAlt, borderColor: t.border, color: t.text }]}
          />
        </View>

        <View style={{ gap: space(1.5) }}>
          <Text style={[st.label, { color: t.textDim }]}>Chat token</Text>
          <View style={st.tokenRow}>
            <TextInput
              value={token}
              onChangeText={setToken}
              placeholder="Bearer token"
              placeholderTextColor={t.textFaint}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry={!showToken}
              style={[
                st.input,
                { flex: 1, backgroundColor: t.cardAlt, borderColor: t.border, color: t.text, fontFamily: t.mono },
              ]}
            />
            <Pressable
              onPress={() => setShowToken((v) => !v)}
              accessibilityLabel={showToken ? 'Hide token' : 'Show token'}
              style={[st.eyeBtn, { backgroundColor: t.cardAlt, borderColor: t.border }]}
            >
              <Icon name={showToken ? 'eye-off-outline' : 'eye-outline'} size={18} color={t.textDim} />
            </Pressable>
          </View>
        </View>

        <View style={st.actions}>
          <Button label="Save" kind="primary" compact disabled={!dirty} style={{ flex: 1 }} onPress={onSave} />
          {active || url.trim() || token.trim() ? (
            <Button label="Clear" kind="subtle" compact onPress={onClear} />
          ) : null}
        </View>
      </Card>
    </Section>
  );
}

const st = StyleSheet.create({
  label: { fontSize: 12.5, fontWeight: '500' },
  input: {
    height: 42,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space(3),
    fontSize: 14,
  },
  tokenRow: { flexDirection: 'row', alignItems: 'center', gap: space(2) },
  eyeBtn: {
    width: 42,
    height: 42,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actions: { flexDirection: 'row', gap: space(2) },
});
