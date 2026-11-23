import { useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AgentEngine, AgentSettings, AgentSettingsPatch } from '../lib/api';
import { shortEffortLabel } from '../lib/chatLogic';
import { radius, screenPad, space, useTheme } from '../theme';
import { Banner, Button, Icon, Pill } from './ui';

const ANTHROPIC_ICON = require('../../assets/brand-anthropic.png');
const GEMINI_ICON = require('../../assets/brand-gemini.png');

export type AgentSelectionResult = {
  engine: AgentEngine;
  patch: AgentSettingsPatch;
};

type Props = {
  visible: boolean;
  engine: AgentEngine;
  settings: AgentSettings | null;
  loading: boolean;
  loadError: string | null;
  applying: boolean;
  applyError: string | null;
  onRetryLoad: () => void;
  onApply: (result: AgentSelectionResult) => void;
  onClose: () => void;
};

function ProviderBrandIcon({ brand, size = 17, color }: { brand: AgentEngine; size?: number; color: string }) {
  return (
    <Image
      source={brand === 'claude' ? ANTHROPIC_ICON : GEMINI_ICON}
      style={{ width: size, height: size, tintColor: color }}
      resizeMode="contain"
    />
  );
}

function RadioDot({ selected, disabled }: { selected: boolean; disabled?: boolean }) {
  const t = useTheme();
  return (
    <View
      style={[
        st.radioOuter,
        {
          borderColor: selected ? t.accent : t.border,
          opacity: disabled ? 0.4 : 1,
        },
      ]}
    >
      {selected ? <View style={[st.radioInner, { backgroundColor: t.accent }]} /> : null}
    </View>
  );
}

function PickerBody({
  engine,
  settings,
  applying,
  applyError,
  onApply,
}: {
  engine: AgentEngine;
  settings: AgentSettings;
  applying: boolean;
  applyError: string | null;
  onApply: (result: AgentSelectionResult) => void;
}) {
  const t = useTheme();
  const [draftEngine, setDraftEngine] = useState<AgentEngine>(engine);
  const [draftClaudeModel, setDraftClaudeModel] = useState(settings.claude.model);
  const [draftClaudeEffort, setDraftClaudeEffort] = useState(settings.claude.effort);
  const [draftGeminiInstance, setDraftGeminiInstance] = useState(settings.gemini.instance_id);
  const [draftGeminiModel, setDraftGeminiModel] = useState(settings.gemini.model);
  const [draftGeminiEffort, setDraftGeminiEffort] = useState(settings.gemini.thought_level);
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    const activeKey = engine === 'claude' ? 'claude' : `gemini:${settings.gemini.instance_id}`;
    return { [activeKey]: true };
  });

  const toggleSection = (key: string) => {
    setExpanded((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const efforts = draftEngine === 'claude' ? settings.claude.efforts : settings.gemini.efforts;
  const activeEffort = draftEngine === 'claude' ? draftClaudeEffort : draftGeminiEffort;

  const hasChanges = Boolean(
    draftEngine !== engine ||
      (draftEngine === 'claude' &&
        (draftClaudeModel !== settings.claude.model || draftClaudeEffort !== settings.claude.effort)) ||
      (draftEngine === 'gemini' &&
        (draftGeminiInstance !== settings.gemini.instance_id ||
          draftGeminiModel !== settings.gemini.model ||
          draftGeminiEffort !== settings.gemini.thought_level)),
  );

  const handleApply = () => {
    if (applying) return;
    const patch: AgentSettingsPatch = {};
    if (draftEngine === 'claude') {
      const c: { model?: string; effort?: string } = {};
      if (draftClaudeModel && draftClaudeModel !== settings.claude.model) c.model = draftClaudeModel;
      if (draftClaudeEffort && draftClaudeEffort !== settings.claude.effort) c.effort = draftClaudeEffort;
      if (c.model || c.effort) patch.claude = c;
    } else {
      const g: { instance_id?: string; model?: string; thought_level?: string } = {};
      if (draftGeminiInstance && draftGeminiInstance !== settings.gemini.instance_id) {
        g.instance_id = draftGeminiInstance;
      }
      if (draftGeminiModel && draftGeminiModel !== settings.gemini.model) g.model = draftGeminiModel;
      if (draftGeminiEffort && draftGeminiEffort !== settings.gemini.thought_level) {
        g.thought_level = draftGeminiEffort;
      }
      if (g.instance_id || g.model || g.thought_level) patch.gemini = g;
    }
    onApply({ engine: draftEngine, patch });
  };

  const claudeOpen = Boolean(expanded.claude);
  const claudeActive = draftEngine === 'claude';

  return (
    <>
      <ScrollView
        style={st.scroll}
        contentContainerStyle={st.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* Claude section */}
        <View style={[st.providerCard, { backgroundColor: t.card }]}>
          <Pressable
            onPress={() => toggleSection('claude')}
            android_ripple={{ color: t.ripple }}
            style={st.providerHeader}
          >
            <ProviderBrandIcon
              brand="claude"
              size={17}
              color={claudeActive ? t.accent : t.textDim}
            />
            <View style={{ flex: 1, gap: 1 }}>
              <Text style={[st.providerTitle, { color: t.text }]} numberOfLines={1}>
                Claude
              </Text>
              {settings.claude.account ? (
                <Text style={[st.providerSub, { color: t.textFaint }]} numberOfLines={1}>
                  {settings.claude.account}
                </Text>
              ) : null}
            </View>
            {claudeActive ? <Pill label="Active" tone="accent" /> : null}
            <Icon
              name={claudeOpen ? 'chevron-up' : 'chevron-down'}
              size={20}
              color={t.textFaint}
            />
          </Pressable>

          {claudeOpen ? (
            <View style={[st.modelList, { borderTopColor: t.border }]}>
              {settings.claude.models.map((m, idx) => {
                const selected = draftEngine === 'claude' && draftClaudeModel === m.id;
                return (
                  <Pressable
                    key={m.id}
                    onPress={() => {
                      setDraftEngine('claude');
                      setDraftClaudeModel(m.id);
                    }}
                    android_ripple={{ color: t.ripple }}
                    style={[
                      st.modelRow,
                      idx > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border },
                    ]}
                  >
                    <RadioDot selected={selected} />
                    <Text
                      style={[
                        st.modelLabel,
                        { color: selected ? t.text : t.textDim, fontWeight: selected ? '600' : '400' },
                      ]}
                      numberOfLines={1}
                    >
                      {m.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          ) : null}
        </View>

        {/* Dynamic Antigravity / Gemini instances */}
        {settings.gemini.instances.map((inst) => {
          const key = `gemini:${inst.id}`;
          const isOpen = Boolean(expanded[key]);
          const isSelectedProvider = draftEngine === 'gemini' && draftGeminiInstance === inst.id;
          const disabled = !inst.signed_in;
          return (
            <View
              key={inst.id}
              style={[st.providerCard, { backgroundColor: t.card, opacity: disabled ? 0.6 : 1 }]}
            >
              <Pressable
                onPress={() => toggleSection(key)}
                android_ripple={{ color: t.ripple }}
                style={st.providerHeader}
              >
                <ProviderBrandIcon
                  brand="gemini"
                  size={17}
                  color={isSelectedProvider ? t.accent : t.textDim}
                />
                <View style={{ flex: 1, gap: 1 }}>
                  <Text style={[st.providerTitle, { color: t.text }]} numberOfLines={1}>
                    {inst.label}
                  </Text>
                  {inst.email || disabled ? (
                    <Text style={[st.providerSub, { color: t.textFaint }]} numberOfLines={1}>
                      {inst.email || 'Not signed in'}
                    </Text>
                  ) : null}
                </View>
                {disabled ? (
                  <Pill label="Signed out" tone="dim" />
                ) : isSelectedProvider ? (
                  <Pill label="Active" tone="accent" />
                ) : null}
                <Icon
                  name={isOpen ? 'chevron-up' : 'chevron-down'}
                  size={20}
                  color={t.textFaint}
                />
              </Pressable>

              {isOpen ? (
                <View style={[st.modelList, { borderTopColor: t.border }]}>
                  {settings.gemini.models.map((m, idx) => {
                    const selected =
                      draftEngine === 'gemini' &&
                      draftGeminiInstance === inst.id &&
                      draftGeminiModel === m.id;
                    return (
                      <Pressable
                        key={m.id}
                        disabled={disabled}
                        onPress={() => {
                          setDraftEngine('gemini');
                          setDraftGeminiInstance(inst.id);
                          setDraftGeminiModel(m.id);
                        }}
                        android_ripple={disabled ? undefined : { color: t.ripple }}
                        style={[
                          st.modelRow,
                          idx > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border },
                        ]}
                      >
                        <RadioDot selected={selected} disabled={disabled} />
                        <Text
                          style={[
                            st.modelLabel,
                            { color: selected ? t.text : t.textDim, fontWeight: selected ? '600' : '400' },
                          ]}
                          numberOfLines={1}
                        >
                          {m.label}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : null}
            </View>
          );
        })}
      </ScrollView>

      {/* Effort segmented selector */}
      {efforts.length > 0 ? (
        <View style={st.effortSection}>
          <Text style={[st.sectionLabel, { color: t.textDim }]}>Effort</Text>
          <View style={[st.segTrack, { backgroundColor: t.card }]} accessibilityRole="tablist">
            {efforts.map((eff) => {
              const on = eff === activeEffort;
              return (
                <Pressable
                  key={eff}
                  onPress={() => {
                    if (draftEngine === 'claude') setDraftClaudeEffort(eff);
                    else setDraftGeminiEffort(eff);
                  }}
                  accessibilityRole="tab"
                  accessibilityState={{ selected: on }}
                  android_ripple={{ color: t.ripple }}
                  style={[st.segBtn, on && { backgroundColor: t.accent }]}
                >
                  <Text
                    style={[
                      st.segText,
                      { color: on ? t.onAccent : t.textDim, fontWeight: on ? '600' : '500' },
                    ]}
                    numberOfLines={1}
                    adjustsFontSizeToFit
                    minimumFontScale={0.8}
                  >
                    {shortEffortLabel(eff)}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>
      ) : null}

      {applyError ? (
        <View style={{ paddingHorizontal: screenPad, paddingTop: space(2) }}>
          <Banner tone="bad" title={applyError} />
        </View>
      ) : null}

      <View style={st.footer}>
        <Button
          label="Apply"
          kind="primary"
          busy={applying}
          disabled={!hasChanges || applying}
          onPress={handleApply}
        />
      </View>
    </>
  );
}

export function AgentModelPickerModal({
  visible,
  engine,
  settings,
  loading,
  loadError,
  applying,
  applyError,
  onRetryLoad,
  onApply,
  onClose,
}: Props) {
  const t = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Modal
      visible={visible}
      animationType="slide"
      onRequestClose={onClose}
      transparent
      statusBarTranslucent
      navigationBarTranslucent
    >
      <View style={[st.backdrop, { backgroundColor: t.overlay }]}>
        <Pressable style={{ flex: 1 }} onPress={onClose} accessibilityLabel="Close model picker" />
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />

          <View style={st.header}>
            <Text style={[st.title, { color: t.text }]}>Model & effort</Text>
            <Pressable
              onPress={onClose}
              hitSlop={10}
              accessibilityLabel="Close"
              style={({ pressed }) => [
                st.closeBtn,
                { backgroundColor: t.card, opacity: pressed ? 0.7 : 1 },
              ]}
            >
              <Icon name="close" size={18} color={t.textDim} />
            </Pressable>
          </View>

          {!settings && loading ? (
            <View style={st.centerState}>
              <ActivityIndicator size="small" color={t.accent} />
              <Text style={{ color: t.textDim, fontSize: 13.5 }}>Loading providers…</Text>
            </View>
          ) : !settings && loadError ? (
            <View style={{ paddingHorizontal: screenPad, paddingVertical: space(4), gap: space(3) }}>
              <Banner tone="bad" title="Could not load models">
                {loadError}
              </Banner>
              <Button label="Retry" kind="subtle" onPress={onRetryLoad} />
            </View>
          ) : visible && settings ? (
            <PickerBody
              engine={engine}
              settings={settings}
              applying={applying}
              applyError={applyError}
              onApply={onApply}
            />
          ) : null}
        </View>
      </View>
    </Modal>
  );
}

const st = StyleSheet.create({
  backdrop: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  sheet: {
    maxHeight: '84%',
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    paddingTop: space(2),
  },
  grabber: {
    width: 38,
    height: 4,
    borderRadius: 2,
    alignSelf: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: screenPad,
    paddingTop: space(3),
    paddingBottom: space(2.5),
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
  },
  closeBtn: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  centerState: {
    paddingVertical: space(8),
    alignItems: 'center',
    justifyContent: 'center',
    gap: space(2),
  },
  scroll: {
    flexShrink: 1,
  },
  scrollContent: {
    paddingHorizontal: screenPad,
    paddingBottom: space(2),
    gap: space(2.5),
  },
  providerCard: {
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  providerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2.5),
    paddingHorizontal: space(3.5),
    paddingVertical: space(3),
    minHeight: 54,
  },
  providerTitle: {
    fontSize: 15,
    fontWeight: '500',
  },
  providerSub: {
    fontSize: 12,
  },
  modelList: {
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  modelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(3),
    paddingHorizontal: space(4),
    paddingVertical: 13,
  },
  modelLabel: {
    fontSize: 14.5,
    flex: 1,
  },
  radioOuter: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioInner: {
    width: 9,
    height: 9,
    borderRadius: 4.5,
  },
  effortSection: {
    paddingHorizontal: screenPad,
    paddingTop: space(3),
    gap: space(1.5),
  },
  sectionLabel: {
    fontSize: 12.5,
    fontWeight: '500',
  },
  segTrack: {
    flexDirection: 'row',
    padding: 3,
    borderRadius: radius.sm,
    gap: 3,
  },
  segBtn: {
    flex: 1,
    minHeight: 36,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 6,
  },
  segText: {
    fontSize: 13,
  },
  footer: {
    paddingHorizontal: screenPad,
    paddingTop: space(3),
  },
});
