import { useFocusEffect, useIsFocused } from '@react-navigation/native';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  FlatList,
  KeyboardAvoidingView,
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
import { Banner, Button, Icon, Pill, Screen, StatusDot } from '../components/ui';
import { cacheGet, cacheSet } from '../lib/cache';
import { fetchChatMessages, sendChatMessage, startNewChat } from '../lib/chatApi';
import {
  CHAT_STATE_CACHE_KEY,
  ChatCacheState,
  ChatEngine,
  ChatMessage,
  formatChatTime,
  makeClientId,
  mergeChatMessages,
  parseMessageSegments,
  prependOlderMessages,
  shouldPollChat,
} from '../lib/chatLogic';
import { radius, screenPad, space, useTheme } from '../theme';

const POLL_INTERVAL_MS = 2000;

function engineLabel(engine: ChatEngine): string {
  return engine === 'gemini' ? 'Gemini' : 'Claude';
}

function InlineText({ text, color }: { text: string; color: string }) {
  const t = useTheme();
  const parts = useMemo(() => text.split(/(`[^`\n]+`)/g), [text]);
  return (
    <Text selectable style={[st.msgText, { color }]}>
      {parts.map((part, i) => {
        if (part.length >= 2 && part.startsWith('`') && part.endsWith('`')) {
          return (
            <Text key={i} style={[st.inlineCode, { fontFamily: t.mono, backgroundColor: t.cardAlt, color: t.accent }]}>
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
  const textColor = t.text;

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
                  backgroundColor: isUser ? t.bg : t.cardAlt,
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
        return <InlineText key={idx} text={seg.text} color={textColor} />;
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

function MessageBubble({ msg, onCopy }: { msg: ChatMessage; onCopy: (text: string) => void }) {
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
        style={[
          st.bubble,
          isUser
            ? { backgroundColor: t.accentSoft, borderColor: t.accent, borderWidth: StyleSheet.hairlineWidth }
            : { backgroundColor: t.card, borderColor: isError ? t.bad : t.border, borderWidth: StyleSheet.hairlineWidth },
        ]}
      >
        {!isUser ? (
          <View style={st.bubbleMetaTop}>
            <Text style={[st.engineTag, { color: isError ? t.bad : t.accent }]}>{engineLabel(msg.engine)}</Text>
            {copyable && !isPending ? (
              <Pressable
                onPress={() => onCopy(copyable)}
                hitSlop={10}
                accessibilityLabel="Copy message"
                style={({ pressed }) => [st.copyBtn, pressed && { opacity: 0.6 }]}
              >
                <Icon name="content-copy" size={14} color={t.textFaint} />
              </Pressable>
            ) : null}
          </View>
        ) : null}

        {isPending ? (
          <View style={st.pendingRow}>
            <ActivityIndicator size="small" color={t.accent} />
            <Text style={[st.pendingText, { color: t.textDim }]}>Working…</Text>
          </View>
        ) : isError ? (
          <Text selectable style={[st.errorText, { color: t.bad }]}>
            {msg.error || 'Request failed'}
          </Text>
        ) : (
          <MessageBody text={msg.text} isUser={isUser} />
        )}

        <View style={st.bubbleMetaBottom}>
          <Text style={[st.timeText, { color: t.textFaint }]}>{timeStr}</Text>
          {isUser && copyable ? (
            <Pressable
              onPress={() => onCopy(copyable)}
              hitSlop={10}
              accessibilityLabel="Copy message"
              style={({ pressed }) => [st.copyBtn, pressed && { opacity: 0.6 }]}
            >
              <Icon name="content-copy" size={13} color={t.textFaint} />
            </Pressable>
          ) : null}
        </View>
      </Pressable>
    </View>
  );
}

export default function ChatScreen() {
  const t = useTheme();
  const isFocused = useIsFocused();
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

  const listRef = useRef<FlatList<ChatMessage>>(null);
  const mountedRef = useRef(true);

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

  const headerRight = (
    <View style={st.headerRight}>
      <View style={[st.enginePill, { backgroundColor: t.accentSoft }]}>
        {running ? <ActivityIndicator size={12} color={t.accent} /> : <StatusDot tone="accent" size={6} />}
        <Text style={[st.enginePillText, { color: t.accent }]}>{engineLabel(engine)}</Text>
      </View>
      <Button label="New" kind="subtle" compact busy={resetting} onPress={onNewChat} />
    </View>
  );

  return (
    <Screen title="Chat" right={headerRight} scroll={false}>
      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
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
            renderItem={({ item }) => <MessageBubble msg={item} onCopy={onCopy} />}
            contentContainerStyle={st.listContent}
            keyboardShouldPersistTaps="handled"
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
                <Pill label={engineLabel(engine)} tone="accent" dot />
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
            disabled={!draft.trim() || sending}
            accessibilityLabel="Send message"
            style={({ pressed }) => [
              st.sendBtn,
              {
                backgroundColor: t.accent,
                opacity: !draft.trim() || sending ? 0.35 : pressed ? 0.8 : 1,
              },
            ]}
          >
            {sending ? (
              <ActivityIndicator size="small" color={t.onAccent} />
            ) : (
              <Icon name="arrow-up" size={20} color={t.onAccent} />
            )}
          </Pressable>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const st = StyleSheet.create({
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingBottom: 4 },
  enginePill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 36,
    paddingHorizontal: 11,
    borderRadius: radius.pill,
  },
  enginePillText: { fontSize: 12.5, fontWeight: '600' },
  listContent: { paddingHorizontal: screenPad, paddingTop: space(1), paddingBottom: space(4), gap: space(2.5), flexGrow: 1 },
  olderWrap: { alignItems: 'center', paddingBottom: space(2) },
  emptyWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: space(12) },
  bubbleRow: { flexDirection: 'row', width: '100%' },
  bubbleRowUser: { justifyContent: 'flex-end' },
  bubbleRowAssistant: { justifyContent: 'flex-start' },
  bubble: {
    maxWidth: '88%',
    borderRadius: radius.md,
    paddingHorizontal: space(3.5),
    paddingVertical: space(2.5),
    gap: space(1.5),
  },
  bubbleMetaTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) },
  engineTag: { fontSize: 11.5, fontWeight: '600' },
  bubbleMetaBottom: { flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: space(1.5) },
  timeText: { fontSize: 11, fontVariant: ['tabular-nums'] },
  copyBtn: { padding: 2 },
  msgText: { fontSize: 14.5, lineHeight: 20.5 },
  inlineCode: { fontSize: 13, paddingHorizontal: 4, borderRadius: 4 },
  codeBlock: {
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space(2.5),
    gap: 4,
  },
  codeLang: { fontSize: 10.5 },
  codeText: { fontSize: 12.5, lineHeight: 18 },
  pendingRow: { flexDirection: 'row', alignItems: 'center', gap: space(2), paddingVertical: 2 },
  pendingText: { fontSize: 13.5 },
  errorText: { fontSize: 13.5, lineHeight: 19 },
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
    minHeight: 42,
    maxHeight: 120,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space(3.5),
    paddingVertical: space(2.5),
    fontSize: 15,
    lineHeight: 20,
  },
  sendBtn: {
    width: 42,
    height: 42,
    borderRadius: 21,
    alignItems: 'center',
    justifyContent: 'center',
  },
  skelWrap: { flex: 1, paddingHorizontal: screenPad, paddingTop: space(3), gap: space(3) },
  skelBubbleRight: { alignSelf: 'flex-end', width: '68%', borderRadius: radius.md, padding: space(3.5), gap: space(2) },
  skelBubbleLeft: { alignSelf: 'flex-start', width: '80%', borderRadius: radius.md, padding: space(3.5), gap: space(2) },
  skelLine: { height: 12, borderRadius: 4 },
});
