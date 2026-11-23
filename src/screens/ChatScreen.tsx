import { useBottomTabBarHeight } from '@react-navigation/bottom-tabs';
import { useFocusEffect, useIsFocused } from '@react-navigation/native';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  FlatList,
  Image,
  Keyboard,
  KeyboardAvoidingView,
  LayoutChangeEvent,
  NativeScrollEvent,
  NativeSyntheticEvent,
  Platform,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { AgentModelPickerModal, AgentSelectionResult } from '../components/AgentModelPickerModal';
import { Banner, Button, Icon, Screen } from '../components/ui';
import { AgentSettings, getAgentSettings, setAgent, updateAgentSettings } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { fetchChatMessages, sendChatMessage, startNewChat } from '../lib/chatApi';
import {
  CHAT_STATE_CACHE_KEY,
  ChatCacheState,
  ChatEngine,
  ChatMessage,
  computeAndroidKeyboardOffset,
  formatChatTime,
  formatComposerChipLabel,
  makeClientId,
  mergeChatMessages,
  parseMessageSegments,
  prependOlderMessages,
  shouldPollChat,
  shouldShowEngineTag,
} from '../lib/chatLogic';
import { radius, screenPad, space, useTheme } from '../theme';

const ANTHROPIC_ICON = require('../../assets/brand-anthropic.png');
const GEMINI_ICON = require('../../assets/brand-gemini.png');
const CACHE_AGENT_SETTINGS = 'agent:settings';
const CACHE_AGENT_STATE = 'agent:state';
const POLL_INTERVAL_MS = 2000;
const KEYBOARD_BREATHING_ROOM = 12;

function engineLabel(engine: ChatEngine): string {
  return engine === 'gemini' ? 'Gemini' : 'Claude';
}

function InlineText({
  text,
  isUser,
  trailingTime,
}: {
  text: string;
  isUser: boolean;
  trailingTime?: string;
}) {
  const t = useTheme();
  const parts = useMemo(() => text.split(/(`[^`\n]+`)/g), [text]);
  const spacer = trailingTime ? `\u00A0\u00A0${trailingTime.replace(/ /g, '\u00A0')}` : '';
  return (
    <Text selectable style={[st.msgText, { color: t.text }]}>
      {parts.map((part, i) => {
        if (part.length >= 2 && part.startsWith('`') && part.endsWith('`')) {
          return (
            <Text
              key={i}
              style={[
                st.inlineCode,
                { fontFamily: t.mono, backgroundColor: isUser ? t.bg : t.cardAlt, color: t.text },
              ]}
            >
              {part.slice(1, -1)}
            </Text>
          );
        }
        return <React.Fragment key={i}>{part}</React.Fragment>;
      })}
      {spacer ? (
        <Text style={st.timeSpacer} accessibilityElementsHidden importantForAccessibility="no">
          {spacer}
        </Text>
      ) : null}
    </Text>
  );
}

function MessageBody({ text, isUser, timeStr }: { text: string; isUser: boolean; timeStr: string }) {
  const t = useTheme();
  const segments = useMemo(() => parseMessageSegments(text), [text]);

  if (segments.length === 0) return null;

  if (segments.length === 1 && segments[0].kind === 'text') {
    return (
      <View style={st.inlineTimeWrap}>
        <InlineText text={segments[0].text} isUser={isUser} trailingTime={timeStr || undefined} />
        {timeStr ? <Text style={[st.timeInline, { color: t.textFaint }]}>{timeStr}</Text> : null}
      </View>
    );
  }

  return (
    <>
      <View style={{ gap: space(2) }}>
        {segments.map((seg, idx) => {
          if (seg.kind === 'codeblock') {
            return (
              <View
                key={idx}
                style={[
                  st.codeBlock,
                  {
                    backgroundColor: t.bg,
                    borderColor: t.border,
                  },
                ]}
              >
                {seg.lang ? (
                  <Text style={[st.codeLang, { color: t.textFaint, fontFamily: t.mono }]}>{seg.lang}</Text>
                ) : null}
                <ScrollView horizontal showsHorizontalScrollIndicator={false}>
                  <Text selectable style={[st.codeText, { color: t.text, fontFamily: t.mono }]}>
                    {seg.code}
                  </Text>
                </ScrollView>
              </View>
            );
          }
          return <InlineText key={idx} text={seg.text} isUser={isUser} />;
        })}
      </View>
      {timeStr ? <Text style={[st.timeBlock, { color: t.textFaint }]}>{timeStr}</Text> : null}
    </>
  );
}

function ChatSkeleton() {
  const t = useTheme();
  return (
    <View style={st.skelWrap}>
      <View style={[st.skelBubbleRight, { backgroundColor: t.cardAlt }]}>
        <View style={[st.skelLine, { width: '75%', backgroundColor: t.border }]} />
        <View style={[st.skelLine, { width: '45%', backgroundColor: t.border }]} />
      </View>
      <View style={[st.skelBubbleLeft, { backgroundColor: t.card }]}>
        <View style={[st.skelLine, { width: '88%', backgroundColor: t.cardAlt }]} />
        <View style={[st.skelLine, { width: '70%', backgroundColor: t.cardAlt }]} />
        <View style={[st.skelLine, { width: '52%', backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skelBubbleRight, { backgroundColor: t.cardAlt }]}>
        <View style={[st.skelLine, { width: '60%', backgroundColor: t.border }]} />
      </View>
    </View>
  );
}

function TypingDots() {
  const t = useTheme();
  const [dots] = useState(() => [new Animated.Value(0), new Animated.Value(0), new Animated.Value(0)]);

  useEffect(() => {
    const loops: Animated.CompositeAnimation[] = [];
    const timers = dots.map((dot, idx) =>
      setTimeout(() => {
        const loop = Animated.loop(
          Animated.sequence([
            Animated.timing(dot, { toValue: 1, duration: 360, useNativeDriver: true }),
            Animated.timing(dot, { toValue: 0, duration: 360, useNativeDriver: true }),
            Animated.delay(260),
          ]),
        );
        loops.push(loop);
        loop.start();
      }, idx * 160),
    );
    return () => {
      timers.forEach((id) => clearTimeout(id));
      loops.forEach((loop) => loop.stop());
    };
  }, [dots]);

  return (
    <View style={st.typingRow} accessible accessibilityLabel="Assistant is typing">
      {dots.map((dot, idx) => (
        <Animated.View
          key={idx}
          style={[
            st.typingDot,
            {
              backgroundColor: t.accent,
              opacity: dot.interpolate({ inputRange: [0, 1], outputRange: [0.28, 0.9] }),
              transform: [
                {
                  translateY: dot.interpolate({ inputRange: [0, 1], outputRange: [0, -2.5] }),
                },
              ],
            },
          ]}
        />
      ))}
    </View>
  );
}

function MessageBubble({
  msg,
  showEngine,
  onCopy,
}: {
  msg: ChatMessage;
  showEngine: boolean;
  onCopy: (text: string) => void;
}) {
  const t = useTheme();
  const timeStr = formatChatTime(msg.created_at);

  if (msg.role === 'system') {
    return (
      <View style={st.systemRow}>
        <View style={[st.systemLine, { backgroundColor: t.border }]} />
        <Text style={[st.systemText, { color: t.textFaint }]}>
          {msg.text}
          {timeStr ? ` · ${timeStr}` : ''}
        </Text>
        <View style={[st.systemLine, { backgroundColor: t.border }]} />
      </View>
    );
  }

  const isUser = msg.role === 'user';
  const isPending = msg.status === 'pending';
  const isError = msg.status === 'error';
  const copyable = (msg.text || msg.error || '').trim();

  return (
    <View style={[st.bubbleRow, isUser ? st.bubbleRowUser : st.bubbleRowAssistant]}>
      <Pressable
        onLongPress={copyable ? () => onCopy(copyable) : undefined}
        delayLongPress={260}
        style={({ pressed }) => [
          st.bubble,
          isUser
            ? [st.bubbleUser, { backgroundColor: t.cardAlt }]
            : [st.bubbleAssistant, { backgroundColor: isError ? t.badSoft : t.card }],
          pressed && copyable && { opacity: 0.85 },
        ]}
      >
        {showEngine ? (
          <View style={st.engineRow}>
            {isError ? <Icon name="alert-circle-outline" size={13} color={t.bad} /> : null}
            <Text style={[st.engineTag, { color: isError ? t.bad : t.textDim }]}>{engineLabel(msg.engine)}</Text>
          </View>
        ) : null}

        {isPending ? (
          <TypingDots />
        ) : isError ? (
          <>
            <Text selectable style={[st.errorText, { color: t.bad }]}>
              {msg.error || 'Request failed'}
            </Text>
            {timeStr ? <Text style={[st.timeBlock, { color: t.textFaint }]}>{timeStr}</Text> : null}
          </>
        ) : (
          <MessageBody text={msg.text} isUser={isUser} timeStr={timeStr} />
        )}
      </Pressable>
    </View>
  );
}

export default function ChatScreen() {
  const t = useTheme();
  const isFocused = useIsFocused();
  const tabBarHeight = useBottomTabBarHeight();
  const cached = useMemo(() => cacheGet<ChatCacheState>(CHAT_STATE_CACHE_KEY), []);

  const [messages, setMessages] = useState<ChatMessage[]>(() => cached?.messages ?? []);
  const [engine, setEngine] = useState<ChatEngine>(() => cached?.engine ?? 'claude');
  const [hasMore, setHasMore] = useState<boolean>(() => cached?.has_more ?? false);
  const [running, setRunning] = useState(false);
  const [loadingInitial, setLoadingInitial] = useState(() => !cached);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [sending, setSending] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [draft, setDraft] = useState('');
  const [showScrollDown, setShowScrollDown] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [appActive, setAppActive] = useState(() => AppState.currentState === 'active');
  const [kbHeight, setKbHeight] = useState(0);
  const [outerHeight, setOuterHeight] = useState(0);
  const [baseHeight, setBaseHeight] = useState(0);
  const [androidPadAnim] = useState(() => new Animated.Value(0));

  const [agentSettings, setAgentSettings] = useState<AgentSettings | null>(() =>
    cacheGet<AgentSettings>(CACHE_AGENT_SETTINGS),
  );
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsErr, setSettingsErr] = useState<string | null>(null);
  const [pickerVisible, setPickerVisible] = useState(false);
  const [applyingModel, setApplyingModel] = useState(false);
  const [applyModelErr, setApplyModelErr] = useState<string | null>(null);

  const listRef = useRef<FlatList<ChatMessage>>(null);
  const mountedRef = useRef(true);
  const isFocusedRef = useRef(isFocused);
  const messagesCountRef = useRef(messages.length);
  const nearBottomRef = useRef(true);
  const loadingOlderRef = useRef(false);
  const settleScrollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    isFocusedRef.current = isFocused;
  }, [isFocused]);

  useEffect(() => {
    messagesCountRef.current = messages.length;
  }, [messages.length]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (settleScrollTimerRef.current) clearTimeout(settleScrollTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      setAppActive(state === 'active');
    });
    return () => sub.remove();
  }, []);

  const scrollToBottom = useCallback((animated: boolean) => {
    if (messagesCountRef.current > 0) {
      listRef.current?.scrollToEnd({ animated });
    }
  }, []);

  const scrollToBottomSettled = useCallback((animated: boolean) => {
    if (messagesCountRef.current <= 0) return;
    listRef.current?.scrollToEnd({ animated });
    if (settleScrollTimerRef.current) clearTimeout(settleScrollTimerRef.current);
    settleScrollTimerRef.current = setTimeout(() => {
      if (mountedRef.current && nearBottomRef.current && !loadingOlderRef.current && messagesCountRef.current > 0) {
        listRef.current?.scrollToEnd({ animated: true });
      }
    }, 80);
  }, []);

  const onListScroll = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const { contentOffset, contentSize, layoutMeasurement } = e.nativeEvent;
    const distanceFromBottom = contentSize.height - (contentOffset.y + layoutMeasurement.height);
    const isNear = distanceFromBottom <= 120;
    nearBottomRef.current = isNear;
    setShowScrollDown((prev) => (prev === !isNear ? prev : !isNear));
  }, []);

  const onPressScrollToBottom = useCallback(() => {
    nearBottomRef.current = true;
    setShowScrollDown(false);
    scrollToBottomSettled(true);
  }, [scrollToBottomSettled]);

  const lastMsg = messages.length > 0 ? messages[messages.length - 1] : null;
  const lastMsgSig = lastMsg
    ? `${lastMsg.id}:${lastMsg.status}:${lastMsg.text.length}:${lastMsg.error?.length ?? 0}`
    : '';
  const prevLastMsgSigRef = useRef(lastMsgSig);

  useEffect(() => {
    const prev = prevLastMsgSigRef.current;
    prevLastMsgSigRef.current = lastMsgSig;
    if (!lastMsgSig || lastMsgSig === prev) return;
    if (!nearBottomRef.current || loadingOlderRef.current) return;
    const raf = requestAnimationFrame(() => scrollToBottomSettled(true));
    return () => cancelAnimationFrame(raf);
  }, [lastMsgSig, scrollToBottomSettled]);

  // Dismiss keyboard and clear any Android keyboard offset when leaving the Chat tab.
  useEffect(() => {
    if (!isFocused) {
      Keyboard.dismiss();
      androidPadAnim.stopAnimation();
      androidPadAnim.setValue(0);
    }
  }, [isFocused, androidPadAnim]);

  useEffect(() => {
    let scrollTimer: ReturnType<typeof setTimeout> | null = null;
    const scheduleScroll = () => {
      if (scrollTimer) clearTimeout(scrollTimer);
      scrollTimer = setTimeout(() => scrollToBottom(true), 60);
    };

    const showEvent = Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow';
    const hideEvent = Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide';

    const showSub = Keyboard.addListener(showEvent, (e) => {
      if (!isFocusedRef.current) return;
      if (Platform.OS === 'android') {
        setKbHeight(e.endCoordinates?.height ?? 0);
      } else {
        scheduleScroll();
      }
    });
    const hideSub = Keyboard.addListener(hideEvent, () => {
      if (Platform.OS === 'android') {
        setKbHeight(0);
      }
    });

    return () => {
      if (scrollTimer) clearTimeout(scrollTimer);
      showSub.remove();
      hideSub.remove();
    };
  }, [scrollToBottom]);

  const effectiveKbHeight = isFocused ? kbHeight : 0;

  const onOuterLayout = useCallback(
    (e: LayoutChangeEvent) => {
      const h = e.nativeEvent.layout.height;
      setOuterHeight(h);
      if (effectiveKbHeight === 0 && h > 0) {
        setBaseHeight((prev) => (h > prev ? h : prev));
      }
    },
    [effectiveKbHeight],
  );

  const containerShrink =
    Platform.OS === 'android' && effectiveKbHeight > 0 && baseHeight > 0
      ? Math.max(0, baseHeight - outerHeight)
      : 0;

  const targetAndroidPad =
    Platform.OS === 'android'
      ? computeAndroidKeyboardOffset(effectiveKbHeight, tabBarHeight, containerShrink) +
        (effectiveKbHeight > 0 ? KEYBOARD_BREATHING_ROOM : 0)
      : 0;

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    if (!isFocused) {
      androidPadAnim.stopAnimation();
      androidPadAnim.setValue(0);
      return;
    }
    Animated.timing(androidPadAnim, {
      toValue: targetAndroidPad,
      duration: targetAndroidPad > 0 ? 150 : 110,
      useNativeDriver: false,
    }).start(({ finished }) => {
      if (finished && effectiveKbHeight > 0) {
        scrollToBottom(true);
      }
    });
  }, [targetAndroidPad, effectiveKbHeight, isFocused, androidPadAnim, scrollToBottom]);

  const persistCache = useCallback((nextMsgs: ChatMessage[], nextEngine: ChatEngine, nextHasMore: boolean) => {
    const clean = nextMsgs.filter((m) => !m.optimistic).slice(-80);
    cacheSet<ChatCacheState>(CHAT_STATE_CACHE_KEY, {
      messages: clean,
      engine: nextEngine,
      has_more: nextHasMore,
    });
  }, []);

  const loadMessages = useCallback(
    async (opts?: { silent?: boolean }) => {
      if (!opts?.silent) setErr(null);
      try {
        const res = await fetchChatMessages(undefined, 50);
        if (!mountedRef.current) return;
        setEngine(res.engine);
        setRunning(res.running);
        setHasMore(res.has_more);
        setMessages((prev) => {
          const next = mergeChatMessages(prev, res.messages);
          persistCache(next, res.engine, res.has_more);
          return next;
        });
        setErr(null);
      } catch (e) {
        if (!mountedRef.current) return;
        setErr((e as Error).message || 'Failed to load messages');
      } finally {
        if (mountedRef.current) {
          setLoadingInitial(false);
        }
      }
    },
    [persistCache],
  );

  const loadAgentSettings = useCallback(async () => {
    setSettingsLoading(true);
    setSettingsErr(null);
    try {
      const next = await getAgentSettings();
      if (!mountedRef.current) return;
      cacheSet(CACHE_AGENT_SETTINGS, next);
      setAgentSettings(next);
    } catch (e) {
      if (!mountedRef.current) return;
      setSettingsErr((e as Error).message || 'Failed to load agent settings');
    } finally {
      if (mountedRef.current) setSettingsLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadMessages({ silent: true });
      void loadAgentSettings();
    }, [loadMessages, loadAgentSettings]),
  );

  const polling = shouldPollChat(messages, running, isFocused, appActive);

  useEffect(() => {
    if (!polling) return;
    const id = setInterval(() => {
      void loadMessages({ silent: true });
    }, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [polling, loadMessages]);

  const onLoadOlder = useCallback(async () => {
    if (loadingOlder || !hasMore || messages.length === 0) return;
    const oldest = messages[0];
    if (!oldest || oldest.optimistic) return;
    loadingOlderRef.current = true;
    setLoadingOlder(true);
    try {
      const res = await fetchChatMessages(oldest.id, 50);
      if (!mountedRef.current) return;
      setHasMore(res.has_more);
      setMessages((prev) => prependOlderMessages(res.messages, prev));
    } catch (e) {
      if (!mountedRef.current) return;
      setErr((e as Error).message || 'Failed to load older messages');
    } finally {
      if (mountedRef.current) setLoadingOlder(false);
      setTimeout(() => {
        loadingOlderRef.current = false;
      }, 200);
    }
  }, [loadingOlder, hasMore, messages]);

  const onCopy = useCallback((text: string) => {
    void Share.share({ message: text }).catch(() => {});
  }, []);

  const onSend = useCallback(async () => {
    const trimmed = draft.trim();
    if (!trimmed || sending) return;
    const clientId = makeClientId();
    const nowSec = Date.now() / 1000;
    const optUser: ChatMessage = {
      id: `opt-u-${clientId}`,
      role: 'user',
      engine,
      text: trimmed,
      created_at: nowSec,
      status: 'done',
      client_id: clientId,
      optimistic: true,
    };
    const optAsst: ChatMessage = {
      id: `opt-a-${clientId}`,
      role: 'assistant',
      engine,
      text: '',
      created_at: nowSec + 0.001,
      status: 'pending',
      reply_to: clientId,
      optimistic: true,
    };

    setDraft('');
    setErr(null);
    setSending(true);
    setRunning(true);
    nearBottomRef.current = true;
    setShowScrollDown(false);
    setMessages((prev) => [...prev, optUser, optAsst]);
    requestAnimationFrame(() => scrollToBottomSettled(true));

    try {
      const res = await sendChatMessage(trimmed, clientId);
      if (!mountedRef.current) return;
      setEngine(res.engine);
      setRunning(res.running);
      setMessages((prev) => {
        const confirmed = [res.user_message, ...(res.assistant_message ? [res.assistant_message] : [])];
        const withoutOpt = prev.filter((m) => m.id !== optUser.id && m.id !== optAsst.id);
        const next = mergeChatMessages(withoutOpt, [...withoutOpt.filter((m) => !m.optimistic), ...confirmed]);
        persistCache(next, res.engine, hasMore);
        return next;
      });
    } catch (e) {
      if (!mountedRef.current) return;
      setMessages((prev) => prev.filter((m) => m.id !== optUser.id && m.id !== optAsst.id));
      setDraft(trimmed);
      setRunning(false);
      setErr((e as Error).message || 'Failed to send message');
    } finally {
      if (mountedRef.current) setSending(false);
    }
  }, [draft, sending, engine, hasMore, persistCache, scrollToBottomSettled]);

  const onNewChat = useCallback(() => {
    Alert.alert('New conversation', `Start a fresh ${engineLabel(engine)} session?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'New',
        onPress: () => {
          setResetting(true);
          setErr(null);
          void startNewChat()
            .then((res) => {
              if (!mountedRef.current) return;
              setEngine(res.engine);
              setRunning(res.running);
              nearBottomRef.current = true;
              setShowScrollDown(false);
              setMessages((prev) => {
                const next = mergeChatMessages(prev, [...prev.filter((m) => !m.optimistic), res.boundary_message]);
                persistCache(next, res.engine, hasMore);
                return next;
              });
              requestAnimationFrame(() => scrollToBottomSettled(true));
            })
            .catch((e) => {
              if (!mountedRef.current) return;
              setErr((e as Error).message || 'Failed to start new conversation');
            })
            .finally(() => {
              if (mountedRef.current) setResetting(false);
            });
        },
      },
    ]);
  }, [engine, hasMore, persistCache, scrollToBottomSettled]);

  const openModelPicker = useCallback(() => {
    Keyboard.dismiss();
    setApplyModelErr(null);
    setPickerVisible(true);
    if (!agentSettings) {
      void loadAgentSettings();
    }
  }, [agentSettings, loadAgentSettings]);

  const onApplyModel = useCallback(
    async ({ engine: nextEngine, patch }: AgentSelectionResult) => {
      setApplyingModel(true);
      setApplyModelErr(null);
      try {
        if (patch.claude || patch.gemini) {
          const updated = await updateAgentSettings(patch);
          if (!mountedRef.current) return;
          cacheSet(CACHE_AGENT_SETTINGS, updated);
          setAgentSettings(updated);
        }
        if (nextEngine !== engine) {
          const nextState = await setAgent(nextEngine);
          if (!mountedRef.current) return;
          cacheSet(CACHE_AGENT_STATE, nextState);
          setEngine(nextEngine);
          setMessages((prev) => {
            persistCache(prev, nextEngine, hasMore);
            return prev;
          });
        }
        if (mountedRef.current) {
          setPickerVisible(false);
        }
      } catch (e) {
        if (!mountedRef.current) return;
        setApplyModelErr((e as Error).message || 'Failed to update model settings');
      } finally {
        if (mountedRef.current) setApplyingModel(false);
      }
    },
    [engine, hasMore, persistCache],
  );

  const modelTriggerA11yLabel = useMemo(() => {
    const provider = engineLabel(engine);
    if (!agentSettings) return `Switch model and effort: ${provider}`;
    if (engine === 'claude') {
      const m = agentSettings.claude.models.find((x) => x.id === agentSettings.claude.model);
      const summary = formatComposerChipLabel('claude', agentSettings.claude.model, m?.label, agentSettings.claude.effort);
      return `Switch model and effort: ${provider}, ${summary}`;
    }
    const m = agentSettings.gemini.models.find((x) => x.id === agentSettings.gemini.model);
    const summary = formatComposerChipLabel('gemini', agentSettings.gemini.model, m?.label, agentSettings.gemini.thought_level);
    return `Switch model and effort: ${provider}, ${summary}`;
  }, [engine, agentSettings]);

  const canSend = draft.trim().length > 0 && !sending;

  const headerRight = (
    <View style={st.headerRight}>
      <Pressable
        onPress={onNewChat}
        disabled={resetting}
        accessibilityLabel="New conversation"
        android_ripple={{ color: t.ripple, foreground: true }}
        style={({ pressed }) => [
          st.newBtn,
          { backgroundColor: t.cardAlt, opacity: resetting ? 0.5 : pressed ? 0.8 : 1 },
        ]}
      >
        {resetting ? (
          <ActivityIndicator size={13} color={t.text} />
        ) : (
          <Icon name="square-edit-outline" size={15} color={t.text} />
        )}
        <Text style={[st.newBtnText, { color: t.text }]}>New</Text>
      </Pressable>
    </View>
  );

  const chatBody = (
    <>
      {err ? (
        <View style={{ paddingHorizontal: screenPad, paddingBottom: space(2) }}>
          <Banner tone="bad" title={err} />
        </View>
      ) : null}

      <View style={st.listWrap}>
        {loadingInitial && messages.length === 0 ? (
          <ChatSkeleton />
        ) : (
          <FlatList
            ref={listRef}
            data={messages}
            keyExtractor={(item) => item.id}
            renderItem={({ item, index }) => (
              <MessageBubble
                msg={item}
                showEngine={shouldShowEngineTag(messages, index, engine)}
                onCopy={onCopy}
              />
            )}
            contentContainerStyle={[st.listContent, messages.length === 0 && st.listContentEmpty]}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="interactive"
            onScroll={onListScroll}
            scrollEventThrottle={100}
            onContentSizeChange={() => {
              if (messages.length > 0 && nearBottomRef.current && !loadingOlderRef.current) {
                scrollToBottomSettled(false);
              }
            }}
            ListHeaderComponent={
              hasMore ? (
                <View style={st.olderWrap}>
                  <Button
                    label="Load earlier"
                    kind="subtle"
                    compact
                    busy={loadingOlder}
                    onPress={() => void onLoadOlder()}
                  />
                </View>
              ) : null
            }
            ListEmptyComponent={
              <View style={st.emptyWrap}>
                <View style={[st.emptyIcon, { backgroundColor: t.card }]}>
                  <Icon name="message-text-outline" size={22} color={t.textFaint} />
                </View>
                <Text style={[st.emptyLabel, { color: t.textFaint }]}>Start a conversation</Text>
              </View>
            }
          />
        )}
        {showScrollDown && messages.length > 0 ? (
          <Pressable
            onPress={onPressScrollToBottom}
            accessibilityRole="button"
            accessibilityLabel="Scroll to latest message"
            android_ripple={{ color: t.ripple, foreground: true }}
            style={({ pressed }) => [
              st.scrollDownBtn,
              {
                backgroundColor: t.card,
                borderColor: t.border,
                opacity: pressed ? 0.8 : 1,
              },
            ]}
          >
            <Icon name="chevron-down" size={20} color={t.text} />
          </Pressable>
        ) : null}
      </View>

      <View
        style={[
          st.composerPill,
          {
            backgroundColor: t.card,
            borderColor: t.border,
          },
        ]}
      >
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder={`Message ${engineLabel(engine)}`}
          placeholderTextColor={t.textFaint}
          multiline
          maxLength={32000}
          selectionColor={t.accent}
          style={[st.input, { color: t.text }]}
        />
        <View style={st.composerControls}>
          <Pressable
            onPress={openModelPicker}
            accessibilityRole="button"
            accessibilityLabel={modelTriggerA11yLabel}
            android_ripple={{ color: t.ripple, foreground: true }}
            style={({ pressed }) => [
              st.modelChip,
              { backgroundColor: t.cardAlt, opacity: pressed ? 0.8 : 1 },
            ]}
          >
            <Image
              source={engine === 'claude' ? ANTHROPIC_ICON : GEMINI_ICON}
              style={[st.modelChipIcon, { tintColor: t.accent }]}
              resizeMode="contain"
            />
            <Icon name="chevron-down" size={13} color={t.textFaint} />
          </Pressable>
          <Pressable
            onPress={() => void onSend()}
            disabled={!canSend}
            accessibilityLabel="Send message"
            style={({ pressed }) => [
              st.sendBtn,
              {
                backgroundColor: canSend ? t.accent : t.cardAlt,
                opacity: pressed && canSend ? 0.85 : 1,
              },
            ]}
          >
            {sending ? (
              <ActivityIndicator size="small" color={t.accent} />
            ) : (
              <Icon name="arrow-up" size={18} color={canSend ? t.onAccent : t.textFaint} />
            )}
          </Pressable>
        </View>
      </View>
    </>
  );

  return (
    <Screen title="Chat" right={headerRight} scroll={false}>
      <View style={{ flex: 1 }} onLayout={onOuterLayout}>
        {Platform.OS === 'ios' ? (
          <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
            {chatBody}
          </KeyboardAvoidingView>
        ) : (
          <Animated.View style={{ flex: 1, paddingBottom: androidPadAnim }}>{chatBody}</Animated.View>
        )}
      </View>
      <AgentModelPickerModal
        visible={pickerVisible}
        engine={engine}
        settings={agentSettings}
        loading={settingsLoading}
        loadError={settingsErr}
        applying={applyingModel}
        applyError={applyModelErr}
        onRetryLoad={() => void loadAgentSettings()}
        onApply={(res) => void onApplyModel(res)}
        onClose={() => setPickerVisible(false)}
      />
    </Screen>
  );
}

const st = StyleSheet.create({
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingBottom: 2 },
  newBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    height: 32,
    paddingHorizontal: 11,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  newBtnText: { fontSize: 13, fontWeight: '500' },
  listWrap: { flex: 1, position: 'relative' },
  listContent: {
    paddingHorizontal: screenPad,
    paddingTop: space(1),
    paddingBottom: space(4),
    gap: space(2.5),
    flexGrow: 1,
  },
  listContentEmpty: {
    justifyContent: 'center',
  },
  olderWrap: { alignItems: 'center', paddingBottom: space(2) },
  emptyWrap: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: space(2.5),
    paddingVertical: space(8),
  },
  emptyIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyLabel: { fontSize: 14, fontWeight: '400' },
  bubbleRow: { flexDirection: 'row', width: '100%' },
  bubbleRowUser: { justifyContent: 'flex-end' },
  bubbleRowAssistant: { justifyContent: 'flex-start' },
  bubble: {
    borderRadius: 18,
    paddingHorizontal: 13,
    paddingTop: 8,
    paddingBottom: 7,
    gap: 3,
  },
  bubbleUser: {
    maxWidth: '82%',
    borderBottomRightRadius: 5,
  },
  bubbleAssistant: {
    maxWidth: '88%',
    borderBottomLeftRadius: 5,
  },
  engineRow: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  engineTag: { fontSize: 11.5, fontWeight: '500' },
  inlineTimeWrap: { position: 'relative' },
  timeSpacer: { fontSize: 10.5, lineHeight: 14, fontVariant: ['tabular-nums'], color: 'transparent' },
  timeInline: {
    position: 'absolute',
    right: 0,
    bottom: 0,
    fontSize: 10.5,
    lineHeight: 14,
    fontVariant: ['tabular-nums'],
  },
  timeBlock: {
    fontSize: 10.5,
    lineHeight: 14,
    fontVariant: ['tabular-nums'],
    alignSelf: 'flex-end',
  },
  msgText: { fontSize: 15, lineHeight: 20 },
  inlineCode: { fontSize: 13, paddingHorizontal: 4, borderRadius: 4 },
  codeBlock: {
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space(2.5),
    gap: 4,
  },
  codeLang: { fontSize: 10.5 },
  codeText: { fontSize: 12.5, lineHeight: 18 },
  typingRow: { flexDirection: 'row', alignItems: 'center', gap: 5, paddingVertical: 5, paddingHorizontal: 2 },
  typingDot: { width: 7, height: 7, borderRadius: 3.5 },
  errorText: { fontSize: 14, lineHeight: 20 },
  systemRow: { flexDirection: 'row', alignItems: 'center', gap: space(2.5), paddingVertical: space(2) },
  systemLine: { flex: 1, height: StyleSheet.hairlineWidth },
  systemText: { fontSize: 11.5, fontVariant: ['tabular-nums'] },
  scrollDownBtn: {
    position: 'absolute',
    right: screenPad,
    bottom: space(2),
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    elevation: 3,
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 2 },
  },
  composerPill: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 6,
    minHeight: 48,
    marginHorizontal: screenPad,
    marginTop: space(1.5),
    marginBottom: space(2),
    borderRadius: 24,
    borderWidth: StyleSheet.hairlineWidth,
    paddingLeft: 14,
    paddingRight: 6,
    paddingVertical: 6,
  },
  input: {
    flex: 1,
    minHeight: 36,
    maxHeight: 120,
    paddingTop: 7,
    paddingBottom: 7,
    paddingRight: 4,
    fontSize: 15,
    lineHeight: 20,
    textAlignVertical: 'center',
    includeFontPadding: false,
  },
  composerControls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginBottom: 1,
  },
  modelChip: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
    height: 34,
    paddingLeft: 9,
    paddingRight: 6,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  modelChipIcon: {
    width: 16,
    height: 16,
  },
  sendBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  skelWrap: { flex: 1, paddingHorizontal: screenPad, paddingTop: space(3), gap: space(3) },
  skelBubbleRight: {
    alignSelf: 'flex-end',
    width: '68%',
    borderRadius: 18,
    borderBottomRightRadius: 5,
    padding: space(3.5),
    gap: space(2),
  },
  skelBubbleLeft: {
    alignSelf: 'flex-start',
    width: '80%',
    borderRadius: 18,
    borderBottomLeftRadius: 5,
    padding: space(3.5),
    gap: space(2),
  },
  skelLine: { height: 12, borderRadius: 4 },
});
