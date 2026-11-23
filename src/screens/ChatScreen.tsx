import { useBottomTabBarHeight } from '@react-navigation/bottom-tabs';
import { useFocusEffect, useIsFocused } from '@react-navigation/native';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  FlatList,
  Keyboard,
  KeyboardAvoidingView,
  LayoutChangeEvent,
  Platform,
  Pressable,
  RefreshControl,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Banner, Button, Icon, Screen, StatusDot } from '../components/ui';
import { cacheGet, cacheSet } from '../lib/cache';
import { fetchChatMessages, sendChatMessage, startNewChat } from '../lib/chatApi';
import {
  CHAT_STATE_CACHE_KEY,
  ChatCacheState,
  ChatEngine,
  ChatMessage,
  computeAndroidKeyboardOffset,
  formatChatTime,
  makeClientId,
  mergeChatMessages,
  parseMessageSegments,
  prependOlderMessages,
  shouldPollChat,
  shouldShowEngineTag,
} from '../lib/chatLogic';
import { radius, screenPad, space, useTheme } from '../theme';

const POLL_INTERVAL_MS = 2000;

function engineLabel(engine: ChatEngine): string {
  return engine === 'gemini' ? 'Gemini' : 'Claude';
}

function InlineText({ text, isUser }: { text: string; isUser: boolean }) {
  const t = useTheme();
  const parts = useMemo(() => text.split(/(`[^`\n]+`)/g), [text]);
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
    </Text>
  );
}

function MessageBody({ text, isUser }: { text: string; isUser: boolean }) {
  const t = useTheme();
  const segments = useMemo(() => parseMessageSegments(text), [text]);

  if (segments.length === 0) return null;

  return (
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
          <View style={st.pendingRow}>
            <ActivityIndicator size="small" color={t.accent} />
            <Text style={[st.pendingText, { color: t.textDim }]}>Thinking…</Text>
          </View>
        ) : isError ? (
          <Text selectable style={[st.errorText, { color: t.bad }]}>
            {msg.error || 'Request failed'}
          </Text>
        ) : (
          <MessageBody text={msg.text} isUser={isUser} />
        )}

        {!isPending && timeStr ? (
          <Text style={[st.timeText, { color: t.textFaint }]}>{timeStr}</Text>
        ) : null}
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
  const [refreshing, setRefreshing] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [sending, setSending] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [draft, setDraft] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [appActive, setAppActive] = useState(() => AppState.currentState === 'active');
  const [kbHeight, setKbHeight] = useState(0);
  const [outerHeight, setOuterHeight] = useState(0);
  const [baseHeight, setBaseHeight] = useState(0);
  const [androidPadAnim] = useState(() => new Animated.Value(0));

  const listRef = useRef<FlatList<ChatMessage>>(null);
  const mountedRef = useRef(true);
  const isFocusedRef = useRef(isFocused);
  const messagesCountRef = useRef(messages.length);

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
      ? computeAndroidKeyboardOffset(effectiveKbHeight, tabBarHeight, containerShrink)
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
          setRefreshing(false);
        }
      }
    },
    [persistCache],
  );

  useFocusEffect(
    useCallback(() => {
      void loadMessages({ silent: true });
    }, [loadMessages]),
  );

  const polling = shouldPollChat(messages, running, isFocused, appActive);

  useEffect(() => {
    if (!polling) return;
    const id = setInterval(() => {
      void loadMessages({ silent: true });
    }, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [polling, loadMessages]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    void loadMessages();
  }, [loadMessages]);

  const onLoadOlder = useCallback(async () => {
    if (loadingOlder || !hasMore || messages.length === 0) return;
    const oldest = messages[0];
    if (!oldest || oldest.optimistic) return;
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
    setMessages((prev) => [...prev, optUser, optAsst]);
    requestAnimationFrame(() => listRef.current?.scrollToEnd({ animated: true }));

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
  }, [draft, sending, engine, hasMore, persistCache]);

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
              setMessages((prev) => {
                const next = mergeChatMessages(prev, [...prev.filter((m) => !m.optimistic), res.boundary_message]);
                persistCache(next, res.engine, hasMore);
                return next;
              });
              requestAnimationFrame(() => listRef.current?.scrollToEnd({ animated: true }));
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
  }, [engine, hasMore, persistCache]);

  const canSend = draft.trim().length > 0 && !sending;

  const headerRight = (
    <View style={st.headerRight}>
      <View style={[st.engineChip, { backgroundColor: t.card }]}>
        {running ? <ActivityIndicator size={11} color={t.accent} /> : <StatusDot tone="ok" size={6} />}
        <Text style={[st.engineChipText, { color: t.textDim }]}>{engineLabel(engine)}</Text>
      </View>
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
          onContentSizeChange={() => {
            if (messages.length > 0) listRef.current?.scrollToEnd({ animated: false });
          }}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={onRefresh}
              tintColor={t.accent}
              colors={[t.accent]}
              progressBackgroundColor={t.card}
            />
          }
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

      <View style={[st.composerWrap, { borderTopColor: t.border, backgroundColor: t.bg }]}>
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder={`Message ${engineLabel(engine)}`}
          placeholderTextColor={t.textFaint}
          multiline
          maxLength={32000}
          selectionColor={t.accent}
          style={[
            st.input,
            {
              backgroundColor: t.card,
              borderColor: t.border,
              color: t.text,
            },
          ]}
        />
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
            <Icon name="arrow-up" size={20} color={canSend ? t.onAccent : t.textFaint} />
          )}
        </Pressable>
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
    </Screen>
  );
}

const st = StyleSheet.create({
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingBottom: 2 },
  engineChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 32,
    paddingHorizontal: 10,
    borderRadius: radius.pill,
  },
  engineChipText: { fontSize: 12.5, fontWeight: '500' },
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
    paddingHorizontal: 14,
    paddingVertical: 10,
    gap: space(1.5),
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
  timeText: { fontSize: 11, fontVariant: ['tabular-nums'], alignSelf: 'flex-end' },
  msgText: { fontSize: 15, lineHeight: 21 },
  inlineCode: { fontSize: 13, paddingHorizontal: 4, borderRadius: 4 },
  codeBlock: {
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space(2.5),
    gap: 4,
  },
  codeLang: { fontSize: 10.5 },
  codeText: { fontSize: 12.5, lineHeight: 18 },
  pendingRow: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingVertical: 2 },
  pendingText: { fontSize: 14 },
  errorText: { fontSize: 14, lineHeight: 20 },
  systemRow: { flexDirection: 'row', alignItems: 'center', gap: space(2.5), paddingVertical: space(2) },
  systemLine: { flex: 1, height: StyleSheet.hairlineWidth },
  systemText: { fontSize: 11.5, fontVariant: ['tabular-nums'] },
  composerWrap: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: space(2),
    paddingHorizontal: screenPad,
    paddingVertical: space(2.5),
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  input: {
    flex: 1,
    minHeight: 44,
    maxHeight: 120,
    borderRadius: 22,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 16,
    paddingTop: 11,
    paddingBottom: 11,
    fontSize: 15,
    lineHeight: 20,
    textAlignVertical: 'center',
    includeFontPadding: false,
  },
  sendBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    marginBottom: 2,
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
