import { LegendList, LegendListRef, LegendListRenderItemProps } from '@legendapp/list/react-native';
import { useBottomTabBarHeight } from '@react-navigation/bottom-tabs';
import { useFocusEffect, useIsFocused } from '@react-navigation/native';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  Clipboard,
  Dimensions,
  Easing,
  findNodeHandle,
  GestureResponderEvent,
  Image,
  ImageLoadEventData,
  Keyboard,
  KeyboardAvoidingView,
  LayoutChangeEvent,
  Linking,
  Modal,
  NativeScrollEvent,
  NativeSyntheticEvent,
  PanResponder,
  Platform,
  Pressable,
  ScrollView,
  Share,
  StyleProp,
  StyleSheet,
  Text,
  Vibration,
  View,
  ViewStyle,
} from 'react-native';
import {
  EnrichedMarkdownText,
  EnrichedMarkdownTextInput,
  EnrichedMarkdownTextInputInstance,
  LinkPressEvent,
  LinkRecognition,
  MarkdownShortcutsConfig,
  MarkdownStyle,
  MarkdownTextInputStyle,
  Md4cFlags,
} from 'react-native-enriched-markdown';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PcNative, PickedAttachment } from '../../modules/pc-native';
import { AgentModelPickerModal, AgentSelectionResult } from '../components/AgentModelPickerModal';
import { PinchZoom, PinchZoomHandle } from '../components/PinchZoom';
import { Banner, Button, Icon, Screen } from '../components/ui';
import { AgentSettings } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import {
  fetchChatMessages,
  fetchLinkPreview,
  getCachedChatAttachmentImage,
  getCachedLinkPreview,
  getChatAgentSettings,
  loadChatAttachmentImage,
  primeChatAttachmentImageCache,
  resolveChatAttachmentSource,
  sendChatMessage,
  startNewChat,
  steerChatMessage,
  subscribeChatEvents,
  switchChatAgent,
  syncNativeChatConfig,
  updateChatAgentSettings,
  uploadChatAttachment,
} from '../lib/chatApi';
import {
  attachmentImageCacheKey,
  buildChatListItems,
  buildReplyRef,
  canSteerQueuedMessage,
  CHAT_STATE_CACHE_KEY,
  ChatAttachment,
  chatAttachmentRequiresFetch,
  ChatCacheState,
  ChatEngine,
  ChatMessage,
  ChatProgress,
  ChatProgressStep,
  ChatReplyRef,
  computeAndroidKeyboardOffset,
  computeInlineImageSize,
  computeSwipeReplyOffset,
  currentProgressStepText,
  deriveImageDisplayName,
  extractPreviewUrl,
  findPrecedingUserMessage,
  formatAttachmentSize,
  formatChatTime,
  formatComposerChipLabel,
  formatEngineLabel,
  formatMessagePreview,
  formatProgressSummary,
  getChatMessageKey,
  getReplyRef,
  INLINE_IMAGE_MAX_HEIGHT,
  isHttpUrl,
  isReplyableMessage,
  LinkPreview,
  makeClientId,
  mergeChatMessages,
  normalizeMarkdownForDisplay,
  parseInlineTokens,
  parseMessageSegments,
  prependOlderMessages,
  reconcileQueuedMessages,
  replySenderLabel,
  resolveChatLinkUrl,
  shouldCaptureSwipeReply,
  shouldPollChat,
  SWIPE_REPLY_RESET_THRESHOLD,
  SWIPE_REPLY_THRESHOLD,
  TableAlignment,
  upsertChatMessage,
} from '../lib/chatLogic';
import { radius, screenPad, space, Theme, useTheme } from '../theme';
import { setHasActiveDraft } from '../lib/otaTriggerLogic';

const ANTHROPIC_ICON = require('../../assets/brand-anthropic.png');
const GEMINI_ICON = require('../../assets/brand-gemini.png');
const CACHE_AGENT_SETTINGS = 'agent:settings';
const CACHE_AGENT_STATE = 'agent:state';
const POLL_INTERVAL_MS = 1000;
const SSE_FALLBACK_POLL_INTERVAL_MS = 3000;
const STEER_HIT_SLOP = { top: 10, bottom: 10, left: 10, right: 8 };
const KEYBOARD_BREATHING_ROOM = 12;
const MAX_ATTACHMENTS = 4;
const COPY_TOAST_DURATION_MS = 1500;

function engineLabel(engine?: ChatEngine | string | null): string {
  return formatEngineLabel(engine);
}

function openExternalUrl(url: string): void {
  if (!isHttpUrl(url)) return;
  Linking.openURL(url).catch(() => {});
}

function triggerHaptic(kind: 'click' | 'heavy' | 'tick' = 'click'): void {
  try {
    if (typeof PcNative?.haptic === 'function') {
      PcNative.haptic(kind);
      return;
    }
  } catch {
    // Fall through to legacy Vibration on older APKs without PcNative.haptic.
  }
  try {
    Vibration.vibrate(kind === 'tick' ? 8 : 10);
  } catch {
    // Ignore if vibration is unavailable.
  }
}

function triggerReplyHaptic(): void {
  triggerHaptic('click');
}

function createSwipeReplyResponder({
  enabled,
  msg,
  dragX,
  iconPulse,
  onThresholdChange,
  onSpringBack,
  onSwipeReply,
}: {
  enabled: boolean;
  msg: ChatMessage;
  dragX: Animated.Value;
  iconPulse: Animated.Value;
  onThresholdChange: (msgId: string | null) => void;
  onSpringBack: () => void;
  onSwipeReply: (msg: ChatMessage) => void;
}) {
  let thresholdCrossed = false;
  return PanResponder.create({
    onStartShouldSetPanResponder: () => false,
    onStartShouldSetPanResponderCapture: () => false,
    onMoveShouldSetPanResponder: (_, gs) => enabled && shouldCaptureSwipeReply(gs.dx, gs.dy),
    onMoveShouldSetPanResponderCapture: (_, gs) => enabled && shouldCaptureSwipeReply(gs.dx, gs.dy),
    onPanResponderTerminationRequest: () => false,
    onShouldBlockNativeResponder: () => true,
    onPanResponderGrant: () => {
      dragX.stopAnimation();
      thresholdCrossed = false;
      onThresholdChange(null);
    },
    onPanResponderMove: (_, gs) => {
      const offset = computeSwipeReplyOffset(gs.dx);
      dragX.setValue(offset);
      if (!thresholdCrossed && offset >= SWIPE_REPLY_THRESHOLD) {
        thresholdCrossed = true;
        onThresholdChange(msg.id);
        triggerReplyHaptic();
        iconPulse.stopAnimation();
        iconPulse.setValue(1);
        Animated.sequence([
          Animated.timing(iconPulse, { toValue: 1.22, duration: 85, useNativeDriver: true }),
          Animated.spring(iconPulse, { toValue: 1, friction: 6, tension: 220, useNativeDriver: true }),
        ]).start();
      } else if (thresholdCrossed && offset < SWIPE_REPLY_RESET_THRESHOLD) {
        thresholdCrossed = false;
        onThresholdChange(null);
      }
    },
    onPanResponderRelease: () => {
      const triggered = thresholdCrossed;
      thresholdCrossed = false;
      onSpringBack();
      if (triggered) {
        onSwipeReply(msg);
      }
    },
    onPanResponderTerminate: () => {
      thresholdCrossed = false;
      onSpringBack();
    },
  });
}

function SwipeableMessageRow({
  msg,
  enabled,
  onSwipeReply,
  children,
}: {
  msg: ChatMessage;
  enabled: boolean;
  onSwipeReply: (msg: ChatMessage) => void;
  children: React.ReactNode;
}) {
  const t = useTheme();
  const [dragX] = useState(() => new Animated.Value(0));
  const [iconPulse] = useState(() => new Animated.Value(1));
  const [thresholdMsgId, setThresholdMsgId] = useState<string | null>(null);
  const thresholdReached = thresholdMsgId === msg.id;

  useEffect(() => {
    dragX.stopAnimation();
    dragX.setValue(0);
    iconPulse.stopAnimation();
    iconPulse.setValue(1);
  }, [msg.id, dragX, iconPulse]);

  const springBack = useCallback(() => {
    setThresholdMsgId(null);
    iconPulse.stopAnimation();
    iconPulse.setValue(1);
    Animated.spring(dragX, {
      toValue: 0,
      useNativeDriver: true,
      tension: 180,
      friction: 18,
    }).start();
  }, [dragX, iconPulse]);

  const panResponder = useMemo(
    () =>
      createSwipeReplyResponder({
        enabled,
        msg,
        dragX,
        iconPulse,
        onThresholdChange: setThresholdMsgId,
        onSpringBack: springBack,
        onSwipeReply,
      }),
    [dragX, enabled, iconPulse, msg, onSwipeReply, springBack],
  );

  const iconOpacity = useMemo(
    () =>
      dragX.interpolate({
        inputRange: [0, 12, SWIPE_REPLY_THRESHOLD],
        outputRange: [0, 0, 1],
        extrapolate: 'clamp',
      }),
    [dragX],
  );
  const iconTranslateX = useMemo(
    () =>
      dragX.interpolate({
        inputRange: [0, SWIPE_REPLY_THRESHOLD],
        outputRange: [-18, 6],
        extrapolate: 'clamp',
      }),
    [dragX],
  );
  const iconScale = useMemo(
    () =>
      dragX.interpolate({
        inputRange: [0, 12, SWIPE_REPLY_THRESHOLD],
        outputRange: [0.55, 0.55, 1],
        extrapolate: 'clamp',
      }),
    [dragX],
  );

  return (
    <View style={st.swipeRowWrap} {...(enabled ? panResponder.panHandlers : undefined)}>
      {enabled ? (
        <Animated.View
          pointerEvents="none"
          style={[
            st.swipeReplyIndicatorWrap,
            {
              opacity: iconOpacity,
              transform: [{ translateX: iconTranslateX }, { scale: iconScale }],
            },
          ]}
        >
          <Animated.View
            style={[
              st.swipeReplyIconCircle,
              {
                backgroundColor: thresholdReached ? t.accentSoft : t.cardAlt,
                transform: [{ scale: iconPulse }],
              },
            ]}
          >
            <Icon name="reply" size={17} color={thresholdReached ? t.accent : t.textDim} />
          </Animated.View>
        </Animated.View>
      ) : null}
      <Animated.View style={[st.swipeBubbleTrack, { transform: [{ translateX: dragX }] }]}>
        {children}
      </Animated.View>
    </View>
  );
}

const BUBBLE_MD4C_FLAGS: Md4cFlags = {
  hardSoftBreaks: true,
  strikethrough: true,
  latexMath: false,
  admonitions: false,
};

const BUBBLE_LINK_RECOGNITION: LinkRecognition = {
  text: /t3-thread:\/\/v2\/[^\s<>)\]]+/,
};

const COMPOSER_MARKDOWN_SHORTCUTS: MarkdownShortcutsConfig = {
  unorderedList: true,
  orderedList: true,
  heading: false,
};

function buildBubbleMarkdownStyle(t: Theme, isUser: boolean): MarkdownStyle {
  const inlineCodeBg = isUser ? t.bg : t.cardAlt;
  const blockSurfaceBg = isUser ? t.bg : t.card;
  const lineHeight = isUser ? 21 : 22;
  const blockMarginBottom = isUser ? 6 : 8;
  return {
    paragraph: {
      fontSize: 15,
      lineHeight,
      color: t.text,
      marginTop: 0,
      marginBottom: blockMarginBottom,
    },
    list: {
      fontSize: 15,
      lineHeight,
      color: t.text,
      bulletColor: t.accent,
      bulletSize: 5,
      markerColor: t.accent,
      markerFontWeight: '500',
      markerMinWidth: 16,
      gapWidth: 8,
      marginLeft: 18,
      itemSpacing: 4,
      marginTop: 4,
      marginBottom: blockMarginBottom,
    },
    strong: {
      fontWeight: 'bold',
      color: t.text,
    },
    em: {
      fontStyle: 'italic',
      color: t.text,
    },
    link: {
      color: t.accent,
      underline: true,
    },
    code: {
      fontFamily: t.mono,
      fontSize: 13,
      color: t.text,
      backgroundColor: inlineCodeBg,
      borderColor: t.border,
    },
    codeBlock: {
      fontFamily: t.mono,
      fontSize: 12.5,
      lineHeight: 18,
      color: t.text,
      backgroundColor: blockSurfaceBg,
      borderColor: t.border,
      borderWidth: StyleSheet.hairlineWidth,
      borderRadius: radius.sm,
      padding: 10,
      marginTop: 4,
      marginBottom: blockMarginBottom,
    },
    blockquote: {
      fontSize: 14.5,
      lineHeight: 20,
      color: t.textDim,
      borderColor: t.accent,
      borderWidth: 2,
      gapWidth: 10,
      backgroundColor: blockSurfaceBg,
      borderRadius: 8,
      padding: 8,
      marginTop: 4,
      marginBottom: blockMarginBottom,
    },
    h1: { fontSize: 18, lineHeight: 24, fontWeight: '600', color: t.text, marginTop: isUser ? 6 : 10, marginBottom: 4 },
    h2: { fontSize: 17, lineHeight: 23, fontWeight: '600', color: t.text, marginTop: isUser ? 6 : 10, marginBottom: 4 },
    h3: { fontSize: 16, lineHeight: 22, fontWeight: '600', color: t.text, marginTop: isUser ? 6 : 8, marginBottom: 4 },
    h4: { fontSize: 15, lineHeight: 21, fontWeight: '600', color: t.text, marginTop: 6, marginBottom: 3 },
    h5: { fontSize: 15, lineHeight: 21, fontWeight: '500', color: t.text, marginTop: 4, marginBottom: 3 },
    h6: { fontSize: 14, lineHeight: 20, fontWeight: '500', color: t.textDim, marginTop: 4, marginBottom: 3 },
    thematicBreak: {
      color: t.border,
      height: StyleSheet.hairlineWidth,
      marginTop: 8,
      marginBottom: 8,
    },
  };
}

function buildComposerMarkdownStyle(t: Theme): MarkdownTextInputStyle {
  return {
    strong: { color: t.text },
    em: { color: t.text },
    link: { color: t.accent, underline: true },
    list: { itemSpacing: 3 },
  };
}

function MarkdownSegment({
  text,
  isUser,
  onCopyCode,
  onLongPress,
}: {
  text: string;
  isUser: boolean;
  onCopyCode: (code: string) => void;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  const normalized = useMemo(() => normalizeMarkdownForDisplay(text), [text]);
  const markdownStyle = useMemo(() => buildBubbleMarkdownStyle(t, isUser), [t, isUser]);

  const handleLinkPress = useCallback((event: LinkPressEvent) => {
    const targetUrl = resolveChatLinkUrl(event.url);
    if (targetUrl) openExternalUrl(targetUrl);
  }, []);

  const handleLinkLongPress = useCallback(() => {
    onLongPress?.();
  }, [onLongPress]);

  const handleCodeBlockPress = useCallback(
    (event: { code: string }) => {
      onCopyCode(event.code);
    },
    [onCopyCode],
  );

  return (
    <EnrichedMarkdownText
      markdown={normalized}
      markdownStyle={markdownStyle}
      md4cFlags={BUBBLE_MD4C_FLAGS}
      linkRecognition={BUBBLE_LINK_RECOGNITION}
      selectable={false}
      allowTrailingMargin={false}
      onLinkPress={handleLinkPress}
      onLinkLongPress={onLongPress ? handleLinkLongPress : undefined}
      onCodeBlockPress={handleCodeBlockPress}
    />
  );
}

const LinkPreviewCard = React.memo(function LinkPreviewCard({
  url,
  isUser,
}: {
  url: string;
  isUser: boolean;
}) {
  const t = useTheme();
  const cached = getCachedLinkPreview(url);
  const [fetched, setFetched] = useState<{ url: string; preview: LinkPreview | null } | null>(null);
  const [failedImg, setFailedImg] = useState<string | null>(null);

  useEffect(() => {
    if (cached !== undefined) return;
    let cancelled = false;
    fetchLinkPreview(url).then((res) => {
      if (!cancelled) setFetched({ url, preview: res });
    });
    return () => {
      cancelled = true;
    };
  }, [url, cached]);

  const preview = cached !== undefined ? cached : fetched?.url === url ? fetched.preview : null;
  const imgUrl = preview?.image ?? null;
  const showImage = Boolean(imgUrl && failedImg !== imgUrl);
  if (!preview || (!preview.title && !showImage)) {
    return null;
  }

  const targetUrl = isHttpUrl(preview.url) ? preview.url : url;
  const hostLabel = preview.site_name || preview.domain;

  return (
    <Pressable
      onPress={() => openExternalUrl(targetUrl)}
      accessibilityRole="link"
      accessibilityLabel={preview.title || hostLabel || targetUrl}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [
        st.linkCard,
        {
          backgroundColor: isUser ? t.bg : t.card,
          borderColor: t.border,
          opacity: pressed ? 0.86 : 1,
        },
      ]}
    >
      <View style={[st.linkCardBar, { backgroundColor: t.accent }]} />
      <View style={st.linkCardBody}>
        <View style={st.linkCardText}>
          {hostLabel ? (
            <Text numberOfLines={1} style={[st.linkCardDomain, { color: t.textFaint }]}>
              {hostLabel}
            </Text>
          ) : null}
          {preview.title ? (
            <Text numberOfLines={2} style={[st.linkCardTitle, { color: t.text }]}>
              {preview.title}
            </Text>
          ) : null}
          {preview.description ? (
            <Text numberOfLines={2} style={[st.linkCardDesc, { color: t.textDim }]}>
              {preview.description}
            </Text>
          ) : null}
        </View>
        {showImage && imgUrl ? (
          <Image
            source={{ uri: imgUrl }}
            style={[st.linkCardThumb, { backgroundColor: t.cardAlt, borderColor: t.border }]}
            resizeMode="cover"
            onError={() => setFailedImg(imgUrl)}
          />
        ) : null}
      </View>
    </Pressable>
  );
});

function CodeBlockCard({
  lang,
  code,
  isUser,
  onCopyCode,
  onLongPress,
}: {
  lang: string;
  code: string;
  isUser: boolean;
  onCopyCode: (code: string) => void;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  const [justCopied, setJustCopied] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handleCopy = useCallback(() => {
    onCopyCode(code);
    setJustCopied(true);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setJustCopied(false);
    }, COPY_TOAST_DURATION_MS);
  }, [code, onCopyCode]);

  return (
    <View
      style={[
        st.codeBlock,
        {
          backgroundColor: isUser ? t.bg : t.card,
          borderColor: t.border,
        },
      ]}
    >
      <View style={[st.codeHeader, { borderBottomColor: t.border }]}>
        <Text numberOfLines={1} style={[st.codeLang, { color: t.textFaint, fontFamily: t.mono }]}>
          {lang || 'code'}
        </Text>
        <Pressable
          onPress={handleCopy}
          onLongPress={onLongPress}
          hitSlop={6}
          accessibilityRole="button"
          accessibilityLabel={justCopied ? 'Copied code block' : 'Copy code block'}
          android_ripple={{ color: t.ripple, foreground: true }}
          style={({ pressed }) => [
            st.codeCopyChip,
            {
              backgroundColor: t.cardAlt,
              opacity: pressed ? 0.75 : 1,
            },
          ]}
        >
          <Icon
            name={justCopied ? 'check' : 'content-copy'}
            size={12}
            color={justCopied ? t.accent : t.textDim}
          />
          <Text style={[st.codeCopyChipText, { color: justCopied ? t.accent : t.textDim }]}>
            {justCopied ? 'Copied' : 'Copy'}
          </Text>
        </Pressable>
      </View>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={st.codeBodyScroll}
      >
        <Pressable
          onPress={handleCopy}
          onLongPress={onLongPress}
          accessibilityRole="button"
          accessibilityLabel="Copy code block"
          style={({ pressed }) => [st.codeBodyPress, pressed && { opacity: 0.78 }]}
        >
          <Text style={[st.codeText, { color: t.text, fontFamily: t.mono }]}>{code}</Text>
        </Pressable>
      </ScrollView>
    </View>
  );
}

function cellDisplayLength(raw: string): number {
  let total = 0;
  for (const tok of parseInlineTokens(raw)) {
    if (tok.kind === 'code') total += tok.code.length;
    else if (tok.kind === 'link') total += tok.label.length;
    else total += tok.text.length;
  }
  return total;
}

function TableCellText({
  text,
  align,
  isHeader,
  isUser,
}: {
  text: string;
  align: TableAlignment;
  isHeader: boolean;
  isUser: boolean;
}) {
  const t = useTheme();
  const tokens = useMemo(() => parseInlineTokens(text), [text]);
  const codeBg = isUser ? t.card : t.cardAlt;

  return (
    <Text
      style={[
        isHeader ? st.tableHeaderText : st.tableCellText,
        {
          color: t.text,
          textAlign: align,
        },
      ]}
    >
      {tokens.length === 0
        ? ' '
        : tokens.map((tok, idx) => {
            if (tok.kind === 'bold') {
              return (
                <Text key={idx} style={st.tableBoldText}>
                  {tok.text}
                </Text>
              );
            }
            if (tok.kind === 'code') {
              return (
                <Text
                  key={idx}
                  style={[
                    st.tableInlineCode,
                    { fontFamily: t.mono, backgroundColor: codeBg, color: t.text },
                  ]}
                >
                  {` ${tok.code} `}
                </Text>
              );
            }
            if (tok.kind === 'link') {
              const targetUrl = resolveChatLinkUrl(tok.url);
              return (
                <Text
                  key={idx}
                  onPress={targetUrl ? () => openExternalUrl(targetUrl) : undefined}
                  style={[st.tableLinkText, { color: t.accent }]}
                >
                  {tok.label}
                </Text>
              );
            }
            return <React.Fragment key={idx}>{tok.text}</React.Fragment>;
          })}
    </Text>
  );
}

function MarkdownTableCard({
  headers,
  alignments,
  rows,
  isUser,
  onLongPress,
}: {
  headers: string[];
  alignments: TableAlignment[];
  rows: string[][];
  isUser: boolean;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  const colWidths = useMemo(
    () =>
      headers.map((header, colIdx) => {
        let maxLen = cellDisplayLength(header);
        for (const row of rows) {
          const len = cellDisplayLength(row[colIdx] ?? '');
          if (len > maxLen) maxLen = len;
        }
        return Math.min(220, Math.max(68, Math.ceil(maxLen * 7.8) + 24));
      }),
    [headers, rows],
  );

  const surfaceBg = isUser ? t.bg : t.card;
  const headerBg = isUser ? t.card : t.cardAlt;

  return (
    <View style={[st.tableCard, { backgroundColor: surfaceBg, borderColor: t.border }]}>
      <ScrollView
        horizontal
        nestedScrollEnabled
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={st.tableScrollContent}
      >
        <Pressable onLongPress={onLongPress} style={st.tableInner}>
          <View
            style={[
              st.tableRow,
              {
                backgroundColor: headerBg,
                borderBottomColor: t.border,
                borderBottomWidth: rows.length > 0 ? StyleSheet.hairlineWidth : 0,
              },
            ]}
          >
            {headers.map((cell, colIdx) => {
              const align = alignments[colIdx] ?? 'left';
              return (
                <View
                  key={colIdx}
                  style={[
                    st.tableCell,
                    {
                      width: colWidths[colIdx],
                      flexGrow: colWidths[colIdx],
                      borderRightColor: t.border,
                      borderRightWidth: colIdx < headers.length - 1 ? StyleSheet.hairlineWidth : 0,
                    },
                  ]}
                >
                  <TableCellText text={cell} align={align} isHeader isUser={isUser} />
                </View>
              );
            })}
          </View>
          {rows.map((row, rowIdx) => (
            <View
              key={rowIdx}
              style={[
                st.tableRow,
                {
                  borderBottomColor: t.border,
                  borderBottomWidth: rowIdx < rows.length - 1 ? StyleSheet.hairlineWidth : 0,
                },
              ]}
            >
              {headers.map((_, colIdx) => {
                const align = alignments[colIdx] ?? 'left';
                return (
                  <View
                    key={colIdx}
                    style={[
                      st.tableCell,
                      {
                        width: colWidths[colIdx],
                        flexGrow: colWidths[colIdx],
                        borderRightColor: t.border,
                        borderRightWidth: colIdx < headers.length - 1 ? StyleSheet.hairlineWidth : 0,
                      },
                    ]}
                  >
                    <TableCellText text={row[colIdx] ?? ''} align={align} isHeader={false} isUser={isUser} />
                  </View>
                );
              })}
            </View>
          ))}
        </Pressable>
      </ScrollView>
    </View>
  );
}

type PreviewImageItem = {
  url: string;
  name: string;
  mimeType?: string;
  linkUrl?: string;
};

const INLINE_IMAGE_SIZE_CACHE_MAX = 120;
const inlineImageSizeCache = new Map<string, { width: number; height: number }>();

function cacheInlineImageSize(key: string, width: number, height: number): void {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return;
  if (inlineImageSizeCache.has(key)) {
    inlineImageSizeCache.delete(key);
  }
  inlineImageSizeCache.set(key, { width, height });
  if (inlineImageSizeCache.size > INLINE_IMAGE_SIZE_CACHE_MAX) {
    const oldest = inlineImageSizeCache.keys().next().value;
    if (oldest !== undefined) inlineImageSizeCache.delete(oldest);
  }
}

function MarkdownImageCard({
  url,
  alt,
  linkUrl,
  isUser,
  onPressImage,
  onLongPress,
}: {
  url: string;
  alt: string;
  linkUrl?: string;
  isUser: boolean;
  onPressImage: (img: PreviewImageItem) => void;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  const source = resolveChatAttachmentSource(url);
  const needsFetch = chatAttachmentRequiresFetch(source);
  const cacheKey = attachmentImageCacheKey(source);
  const [retryNonce, setRetryNonce] = useState(0);
  const requestKey = `${cacheKey}:${retryNonce}`;
  const syncUri = needsFetch ? getCachedChatAttachmentImage(url) : source.uri;

  const [fetchState, setFetchState] = useState<{
    key: string;
    uri: string | null;
    failed: boolean;
  } | null>(null);
  const [renderFailedKey, setRenderFailedKey] = useState<string | null>(null);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [naturalSizeState, setNaturalSizeState] = useState<{
    key: string;
    width: number;
    height: number;
  } | null>(() => {
    const cached = inlineImageSizeCache.get(cacheKey);
    return cached ? { key: cacheKey, width: cached.width, height: cached.height } : null;
  });
  const [containerWidth, setContainerWidth] = useState<number>(() => {
    const winW = Dimensions.get('window').width;
    return isUser ? Math.max(160, Math.round(winW * 0.72)) : Math.max(200, Math.round(winW - screenPad * 2));
  });

  useEffect(() => {
    if (!needsFetch || syncUri) return;
    let cancelled = false;
    void loadChatAttachmentImage(url)
      .then((dataUri) => {
        if (cancelled) return;
        setFetchState({ key: requestKey, uri: dataUri, failed: false });
      })
      .catch((err) => {
        if (cancelled) return;
        console.warn('[MarkdownImageCard] fetch failed', url, err);
        setFetchState({ key: requestKey, uri: null, failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [url, needsFetch, syncUri, requestKey]);

  const activeFetch = fetchState?.key === requestKey ? fetchState : null;
  const resolvedUri = syncUri ?? activeFetch?.uri ?? null;
  const failed = renderFailedKey === requestKey || Boolean(activeFetch?.failed);
  const loaded = loadedKey === requestKey;

  const cachedSize = inlineImageSizeCache.get(cacheKey);
  const naturalSize =
    naturalSizeState?.key === cacheKey
      ? naturalSizeState
      : cachedSize
        ? { key: cacheKey, width: cachedSize.width, height: cachedSize.height }
        : null;
  const hasNaturalSize = naturalSize !== null;

  useEffect(() => {
    if (!resolvedUri || failed || hasNaturalSize) return;
    let cancelled = false;
    Image.getSize(
      resolvedUri,
      (width, height) => {
        if (cancelled || width <= 0 || height <= 0) return;
        cacheInlineImageSize(cacheKey, width, height);
        setNaturalSizeState({ key: cacheKey, width, height });
      },
      () => {
        // onLoad will still report dimensions when the image decodes.
      },
    );
    return () => {
      cancelled = true;
    };
  }, [resolvedUri, failed, hasNaturalSize, cacheKey]);

  const onWrapLayout = useCallback((e: LayoutChangeEvent) => {
    const w = Math.floor(e.nativeEvent.layout.width);
    if (w > 0) {
      setContainerWidth((prev) => (Math.abs(prev - w) > 1 ? w : prev));
    }
  }, []);

  const handleLoad = useCallback(
    (e: NativeSyntheticEvent<ImageLoadEventData>) => {
      setLoadedKey(requestKey);
      const src = e.nativeEvent?.source;
      if (src && src.width > 0 && src.height > 0) {
        cacheInlineImageSize(cacheKey, src.width, src.height);
        setNaturalSizeState({ key: cacheKey, width: src.width, height: src.height });
      }
    },
    [cacheKey, requestKey],
  );

  const handleRetry = useCallback(() => {
    setRenderFailedKey(null);
    setRetryNonce((n) => n + 1);
  }, []);

  const displayName = useMemo(() => deriveImageDisplayName(alt, url), [alt, url]);
  const boxSize = useMemo(
    () =>
      computeInlineImageSize(
        naturalSize?.width ?? 0,
        naturalSize?.height ?? 0,
        containerWidth,
        INLINE_IMAGE_MAX_HEIGHT,
      ),
    [naturalSize?.width, naturalSize?.height, containerWidth],
  );

  const surfaceBg = isUser ? t.bg : t.card;

  const handlePress = useCallback(() => {
    onPressImage({
      url,
      name: displayName,
      linkUrl,
    });
  }, [displayName, linkUrl, onPressImage, url]);

  return (
    <View style={st.mdImageWrap} onLayout={onWrapLayout}>
      {failed ? (
        <Pressable
          onPress={handleRetry}
          onLongPress={onLongPress}
          delayLongPress={260}
          accessibilityRole="button"
          accessibilityLabel={`Retry loading ${displayName}`}
          style={({ pressed }) => [
            st.mdImageFrame,
            {
              width: boxSize.width,
              height: Math.min(boxSize.height, 148),
              backgroundColor: surfaceBg,
              borderColor: t.border,
              opacity: pressed ? 0.8 : 1,
            },
          ]}
        >
          <View style={st.attImgFallback}>
            <Icon name="image-broken-variant" size={22} color={t.textFaint} />
            <Text numberOfLines={1} style={[st.attImgRetryText, { color: t.textFaint }]}>
              Tap to retry
            </Text>
          </View>
        </Pressable>
      ) : (
        <Pressable
          onPress={handlePress}
          onLongPress={onLongPress}
          delayLongPress={260}
          accessibilityRole="button"
          accessibilityLabel={`Open ${displayName} full screen`}
          style={({ pressed }) => [
            st.mdImageFrame,
            {
              width: boxSize.width,
              height: boxSize.height,
              backgroundColor: surfaceBg,
              borderColor: t.border,
              opacity: pressed ? 0.88 : 1,
            },
          ]}
        >
          {resolvedUri ? (
            <Image
              key={requestKey}
              source={{ uri: resolvedUri }}
              style={StyleSheet.absoluteFill}
              resizeMode="contain"
              accessibilityLabel={displayName}
              onLoad={handleLoad}
              onError={(e) => {
                console.warn('[MarkdownImageCard] render error', url, e.nativeEvent?.error);
                setRenderFailedKey(requestKey);
              }}
            />
          ) : null}
          {!loaded ? (
            <View pointerEvents="none" style={st.attImgFallback}>
              <ActivityIndicator size="small" color={t.textFaint} />
            </View>
          ) : null}
        </Pressable>
      )}
    </View>
  );
}

function MessageBody({
  text,
  isUser,
  onCopyCode,
  onPressImage,
  onLongPress,
}: {
  text: string;
  isUser: boolean;
  onCopyCode: (code: string) => void;
  onPressImage: (img: PreviewImageItem) => void;
  onLongPress?: () => void;
}) {
  const segments = useMemo(() => parseMessageSegments(text), [text]);

  if (segments.length === 0) return null;

  return (
    <View style={segments.length > 1 ? st.messageBodyStack : undefined}>
      {segments.map((seg, idx) => {
        if (seg.kind === 'codeblock') {
          return (
            <CodeBlockCard
              key={idx}
              lang={seg.lang}
              code={seg.code}
              isUser={isUser}
              onCopyCode={onCopyCode}
              onLongPress={onLongPress}
            />
          );
        }
        if (seg.kind === 'table') {
          return (
            <MarkdownTableCard
              key={idx}
              headers={seg.headers}
              alignments={seg.alignments}
              rows={seg.rows}
              isUser={isUser}
              onLongPress={onLongPress}
            />
          );
        }
        if (seg.kind === 'image') {
          return (
            <MarkdownImageCard
              key={`${idx}:${seg.url}`}
              url={seg.url}
              alt={seg.alt}
              linkUrl={seg.linkUrl}
              isUser={isUser}
              onPressImage={onPressImage}
              onLongPress={onLongPress}
            />
          );
        }
        return (
          <MarkdownSegment
            key={idx}
            text={seg.text}
            isUser={isUser}
            onCopyCode={onCopyCode}
            onLongPress={onLongPress}
          />
        );
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
      <View style={st.skelAssistantPlain}>
        <View style={[st.skelLine, { width: '92%', backgroundColor: t.cardAlt }]} />
        <View style={[st.skelLine, { width: '78%', backgroundColor: t.cardAlt }]} />
        <View style={[st.skelLine, { width: '58%', backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skelBubbleRight, { backgroundColor: t.cardAlt }]}>
        <View style={[st.skelLine, { width: '60%', backgroundColor: t.border }]} />
      </View>
    </View>
  );
}

const SHIMMER_OUTER_W = 68;
const SHIMMER_INNER_W = 34;

function ShimmerText({ text }: { text: string }) {
  const t = useTheme();
  const [width, setWidth] = useState(180);
  const [phase] = useState(() => new Animated.Value(0));

  useEffect(() => {
    phase.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(phase, {
          toValue: 1,
          duration: 1400,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.delay(260),
      ]),
    );
    loop.start();
    return () => {
      loop.stop();
    };
  }, [phase]);

  const span = Math.max(width, 64);
  const outerStart = -SHIMMER_OUTER_W;
  const outerEnd = span + 12;
  const innerOffset = (SHIMMER_OUTER_W - SHIMMER_INNER_W) / 2;
  const innerStart = outerStart + innerOffset;
  const innerEnd = outerEnd + innerOffset;

  const outerX = useMemo(
    () => phase.interpolate({ inputRange: [0, 1], outputRange: [outerStart, outerEnd] }),
    [phase, outerStart, outerEnd],
  );
  const outerCounterX = useMemo(
    () => phase.interpolate({ inputRange: [0, 1], outputRange: [-outerStart, -outerEnd] }),
    [phase, outerStart, outerEnd],
  );
  const innerX = useMemo(
    () => phase.interpolate({ inputRange: [0, 1], outputRange: [innerStart, innerEnd] }),
    [phase, innerStart, innerEnd],
  );
  const innerCounterX = useMemo(
    () => phase.interpolate({ inputRange: [0, 1], outputRange: [-innerStart, -innerEnd] }),
    [phase, innerStart, innerEnd],
  );

  const onTextLayout = useCallback((e: LayoutChangeEvent) => {
    const measured = Math.ceil(e.nativeEvent.layout.width);
    if (measured > 0) {
      setWidth((prev) => (Math.abs(prev - measured) > 1 ? measured : prev));
    }
  }, []);

  return (
    <View style={st.shimmerWrap} onLayout={onTextLayout}>
      <Text numberOfLines={1} style={[st.shimmerText, { color: t.textDim }]}>
        {text}
      </Text>
      <Animated.View
        pointerEvents="none"
        style={[
          st.shimmerBand,
          { width: SHIMMER_OUTER_W, transform: [{ translateX: outerX }] },
        ]}
      >
        <Animated.View style={{ width: span, transform: [{ translateX: outerCounterX }] }}>
          <Text
            numberOfLines={1}
            accessibilityElementsHidden
            importantForAccessibility="no"
            style={[st.shimmerText, { color: t.text, opacity: 0.45 }]}
          >
            {text}
          </Text>
        </Animated.View>
      </Animated.View>
      <Animated.View
        pointerEvents="none"
        style={[
          st.shimmerBand,
          { width: SHIMMER_INNER_W, transform: [{ translateX: innerX }] },
        ]}
      >
        <Animated.View style={{ width: span, transform: [{ translateX: innerCounterX }] }}>
          <Text
            numberOfLines={1}
            accessibilityElementsHidden
            importantForAccessibility="no"
            style={[st.shimmerText, { color: t.text }]}
          >
            {text}
          </Text>
        </Animated.View>
      </Animated.View>
    </View>
  );
}

function ProgressStepList({ steps }: { steps: ChatProgressStep[] }) {
  const t = useTheme();
  if (steps.length === 0) return null;
  return (
    <View style={[st.progressList, { borderLeftColor: t.border }]}>
      {steps.map((step, idx) => (
        <View key={`${step.t}-${idx}`} style={st.progressStepRow}>
          <View style={[st.progressStepDot, { backgroundColor: t.textFaint }]} />
          <Text style={[st.progressStepText, { color: t.textDim }]}>{step.text}</Text>
        </View>
      ))}
    </View>
  );
}

function ThinkingProgressContent({ progress }: { progress?: ChatProgress | null }) {
  const t = useTheme();
  const [expanded, setExpanded] = useState(false);
  const steps = progress?.steps ?? [];
  const hasSteps = steps.length > 0;
  const currentStep = currentProgressStepText(progress);

  if (!hasSteps) {
    return (
      <View style={st.thinkingRow} accessible accessibilityLabel={currentStep}>
        <ShimmerText text={currentStep} />
      </View>
    );
  }

  return (
    <View style={st.thinkingWrap}>
      <Pressable
        onPress={() => setExpanded((v) => !v)}
        hitSlop={{ top: 4, bottom: 4, left: 4, right: 8 }}
        accessibilityRole="button"
        accessibilityLabel={`${currentStep}. ${expanded ? 'Hide' : 'Show'} ${steps.length} steps`}
        style={({ pressed }) => [st.thinkingRow, pressed && { opacity: 0.78 }]}
      >
        <View style={st.thinkingTextFlex}>
          <ShimmerText text={currentStep} />
        </View>
        <Icon
          name={expanded ? 'chevron-up' : 'chevron-down'}
          size={15}
          color={t.textFaint}
        />
      </Pressable>
      {expanded ? <ProgressStepList steps={steps} /> : null}
    </View>
  );
}

function CompletedProgressBlock({ progress }: { progress: ChatProgress }) {
  const t = useTheme();
  const [expanded, setExpanded] = useState(false);
  const summary = formatProgressSummary(progress);
  if (!summary) return null;

  return (
    <View style={st.completedProgressWrap}>
      <Pressable
        onPress={() => setExpanded((v) => !v)}
        hitSlop={{ top: 4, bottom: 4, left: 4, right: 8 }}
        accessibilityRole="button"
        accessibilityLabel={`${summary}. ${expanded ? 'Hide' : 'Show'} steps`}
        style={({ pressed }) => [st.completedProgressRow, pressed && { opacity: 0.75 }]}
      >
        <Text style={[st.completedProgressText, { color: t.textDim }]}>{summary}</Text>
        <Icon
          name={expanded ? 'chevron-down' : 'chevron-right'}
          size={14}
          color={t.textFaint}
        />
      </Pressable>
      {expanded ? <ProgressStepList steps={progress.steps} /> : null}
    </View>
  );
}

type ChatAttachmentImageProps = {
  url: string;
  mimeType?: string;
  name: string;
  style: StyleProp<ViewStyle>;
  resizeMode?: 'cover' | 'contain';
  compact?: boolean;
  onPress?: () => void;
  onLongPress?: () => void;
};

function ChatAttachmentImage({
  url,
  mimeType,
  name,
  style,
  resizeMode = 'cover',
  compact = false,
  onPress,
  onLongPress,
}: ChatAttachmentImageProps) {
  const t = useTheme();
  const source = resolveChatAttachmentSource(url);
  const needsFetch = chatAttachmentRequiresFetch(source);
  const cacheKey = attachmentImageCacheKey(source);
  const [retryNonce, setRetryNonce] = useState(0);
  const requestKey = `${cacheKey}:${retryNonce}`;
  const syncUri = needsFetch ? getCachedChatAttachmentImage(url) : source.uri;

  const [fetchState, setFetchState] = useState<{
    key: string;
    uri: string | null;
    failed: boolean;
  } | null>(null);
  const [renderFailedKey, setRenderFailedKey] = useState<string | null>(null);

  useEffect(() => {
    if (!needsFetch || syncUri) return;
    let cancelled = false;
    void loadChatAttachmentImage(url, mimeType)
      .then((dataUri) => {
        if (cancelled) return;
        setFetchState({ key: requestKey, uri: dataUri, failed: false });
      })
      .catch((err) => {
        if (cancelled) return;
        console.warn('[ChatAttachmentImage] fetch failed', url, err);
        setFetchState({ key: requestKey, uri: null, failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [url, mimeType, needsFetch, syncUri, requestKey]);

  const activeFetch = fetchState?.key === requestKey ? fetchState : null;
  const resolvedUri = syncUri ?? activeFetch?.uri ?? null;
  const failed = renderFailedKey === requestKey || Boolean(activeFetch?.failed);
  const loading = needsFetch && !resolvedUri && !failed;

  const handleRetry = useCallback(() => {
    setRetryNonce((n) => n + 1);
  }, []);

  const body =
    resolvedUri && !failed ? (
      <Image
        key={requestKey}
        source={{ uri: resolvedUri }}
        style={StyleSheet.absoluteFill}
        resizeMode={resizeMode}
        accessibilityLabel={name}
        onError={(e) => {
          console.warn('[ChatAttachmentImage] render error', url, e.nativeEvent?.error);
          setRenderFailedKey(requestKey);
        }}
      />
    ) : loading ? (
      <View style={st.attImgFallback}>
        <ActivityIndicator size="small" color={t.textFaint} />
      </View>
    ) : (
      <View style={st.attImgFallback}>
        <Icon name="image-broken-variant" size={compact ? 18 : 22} color={t.textFaint} />
        {!compact ? (
          <Text numberOfLines={1} style={[st.attImgRetryText, { color: t.textFaint }]}>
            Tap to retry
          </Text>
        ) : null}
      </View>
    );

  if (failed) {
    return (
      <Pressable
        onPress={handleRetry}
        onLongPress={onLongPress}
        delayLongPress={260}
        accessibilityRole="button"
        accessibilityLabel={`Retry loading ${name}`}
        style={({ pressed }) => [st.attImgWrap, style, pressed && { opacity: 0.8 }]}
      >
        {body}
      </Pressable>
    );
  }

  if (onPress || onLongPress) {
    return (
      <Pressable
        onPress={onPress}
        onLongPress={onLongPress}
        delayLongPress={260}
        accessibilityRole={onPress ? 'button' : undefined}
        accessibilityLabel={onPress ? `View ${name}` : name}
        style={({ pressed }) => [st.attImgWrap, style, pressed && onPress && { opacity: 0.88 }]}
      >
        {body}
      </Pressable>
    );
  }

  return <View style={[st.attImgWrap, style]}>{body}</View>;
}

function BubbleAttachments({
  attachments,
  isUser,
  onPressImage,
  onLongPress,
}: {
  attachments: ChatAttachment[];
  isUser: boolean;
  onPressImage?: (img: PreviewImageItem) => void;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  if (attachments.length === 0) return null;
  const images = attachments.filter((a) => a.kind === 'image');
  const files = attachments.filter((a) => a.kind !== 'image');
  const surfaceBg = isUser ? t.bg : t.card;

  return (
    <View style={st.bubbleAttWrap}>
      {images.length > 0 ? (
        <View style={st.bubbleImgGrid}>
          {images.map((img) => (
            <ChatAttachmentImage
              key={img.id}
              url={img.url}
              mimeType={img.mime_type}
              name={img.name}
              style={[
                images.length === 1 ? st.bubbleImgSingle : st.bubbleImgMulti,
                { backgroundColor: surfaceBg, borderColor: t.border },
              ]}
              resizeMode="cover"
              onPress={
                onPressImage
                  ? () => onPressImage({ url: img.url, name: img.name, mimeType: img.mime_type })
                  : undefined
              }
              onLongPress={onLongPress}
            />
          ))}
        </View>
      ) : null}
      {files.map((file) => (
        <View
          key={file.id}
          style={[st.bubbleFileChip, { backgroundColor: surfaceBg, borderColor: t.border }]}
        >
          <Icon name="file-document" size={16} color={t.accent} />
          <View style={st.bubbleFileMeta}>
            <Text numberOfLines={1} style={[st.bubbleFileName, { color: t.text }]}>
              {file.name}
            </Text>
            <Text style={[st.bubbleFileSize, { color: t.textFaint }]}>
              {formatAttachmentSize(file.size_bytes)}
            </Text>
          </View>
        </View>
      ))}
    </View>
  );
}

const FOOTER_ICON_HIT_SLOP = { top: 10, bottom: 10, left: 6, right: 6 };

function CopyIconButton({
  msgId,
  text,
  onCopy,
}: {
  msgId: string;
  text: string;
  onCopy: (text: string) => void;
}) {
  const t = useTheme();
  const [copiedMsgId, setCopiedMsgId] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const justCopied = copiedMsgId === msgId;

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handlePress = useCallback(() => {
    onCopy(text);
    setCopiedMsgId(msgId);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setCopiedMsgId((prev) => (prev === msgId ? null : prev));
    }, COPY_TOAST_DURATION_MS);
  }, [msgId, onCopy, text]);

  return (
    <Pressable
      onPress={handlePress}
      hitSlop={FOOTER_ICON_HIT_SLOP}
      accessibilityRole="button"
      accessibilityLabel={justCopied ? 'Copied message' : 'Copy message'}
      style={({ pressed }) => [st.footerIconBtn, pressed && { opacity: 0.65 }]}
    >
      <Icon
        name={justCopied ? 'check' : 'content-copy'}
        size={15}
        color={justCopied ? t.accent : t.textFaint}
      />
    </Pressable>
  );
}

type MessageBubbleProps = {
  msg: ChatMessage;
  canRetry: boolean;
  isQueued: boolean;
  canSteer: boolean;
  isSteering: boolean;
  onLongPress: (msg: ChatMessage) => void;
  onReply: (msg: ChatMessage) => void;
  onCopy: (text: string) => void;
  onRetry: (msg: ChatMessage) => void;
  onSteer: (msg: ChatMessage) => void;
  onPressReplyQuote: (replyId: string) => void;
  onCopyCode: (code: string) => void;
  onPressImage: (img: PreviewImageItem) => void;
};

const MessageBubble = React.memo(function MessageBubble({
  msg,
  canRetry,
  isQueued,
  canSteer,
  isSteering,
  onLongPress,
  onReply,
  onCopy,
  onRetry,
  onSteer,
  onPressReplyQuote,
  onCopyCode,
  onPressImage,
}: MessageBubbleProps) {
  const t = useTheme();
  const previewUrl = useMemo(
    () => (msg.role !== 'system' && msg.status !== 'pending' && msg.status !== 'error' ? extractPreviewUrl(msg.text) : null),
    [msg.role, msg.status, msg.text],
  );
  const replyRef = getReplyRef(msg);

  if (msg.role === 'system') {
    return (
      <View style={st.systemRow}>
        <View style={[st.systemLine, { backgroundColor: t.border }]} />
        <Text style={[st.systemText, { color: t.textFaint }]}>{msg.text}</Text>
        <View style={[st.systemLine, { backgroundColor: t.border }]} />
      </View>
    );
  }

  const isUser = msg.role === 'user';
  const isPending = msg.status === 'pending';
  const isError = msg.status === 'error';
  const isSettled = !isPending && !msg.optimistic;
  const attachments = msg.attachments ?? [];
  const hasText = msg.text.trim().length > 0;
  const canOpenActions = !isPending && (isUser || isError || hasText || attachments.length > 0);
  const canSwipeReply = isReplyableMessage(msg);
  const canShowCopy = !isUser && isSettled && !isError && hasText;
  const handleLongPress = canOpenActions ? () => onLongPress(msg) : undefined;

  if (!isUser && isPending) {
    return (
      <View style={st.assistantGroup}>
        <View style={st.assistantMeta}>
          <ThinkingProgressContent progress={msg.progress} />
        </View>
      </View>
    );
  }

  const hasCompletedProgress = !isUser && formatProgressSummary(msg.progress) !== null;

  const messageContent = (
    <>
      {replyRef ? (
        <Pressable
          onPress={() => onPressReplyQuote(replyRef.id)}
          accessibilityRole="button"
          accessibilityLabel={`Quoted message from ${replySenderLabel(replyRef)}`}
          android_ripple={{ color: t.ripple, foreground: true }}
          style={({ pressed }) => [
            st.replyQuote,
            !isUser && [st.replyQuoteAssistant, { borderColor: t.border }],
            {
              backgroundColor: isUser ? t.bg : t.card,
              borderLeftColor: t.accent,
              opacity: pressed ? 0.8 : 1,
            },
          ]}
        >
          <Text numberOfLines={1} style={[st.replyQuoteSender, { color: t.accent }]}>
            {replySenderLabel(replyRef)}
          </Text>
          {replyRef.text ? (
            <Text numberOfLines={2} style={[st.replyQuoteText, { color: t.textDim }]}>
              {replyRef.text}
            </Text>
          ) : null}
        </Pressable>
      ) : null}

      {attachments.length > 0 ? (
        <BubbleAttachments
          attachments={attachments}
          isUser={isUser}
          onPressImage={onPressImage}
          onLongPress={handleLongPress}
        />
      ) : null}

      {isError ? (
        <>
          <Text selectable style={[st.errorText, { color: t.bad }]}>
            {msg.error || 'Request failed'}
          </Text>
          {canRetry ? (
            <View style={st.errorFooter}>
              <Pressable
                onPress={() => onRetry(msg)}
                accessibilityRole="button"
                accessibilityLabel="Retry message"
                android_ripple={{ color: t.ripple, foreground: true }}
                style={({ pressed }) => [
                  st.retryBtn,
                  {
                    backgroundColor: t.card,
                    borderColor: t.border,
                    opacity: pressed ? 0.8 : 1,
                  },
                ]}
              >
                <Icon name="refresh" size={13} color={t.bad} />
                <Text style={[st.retryBtnText, { color: t.bad }]}>Retry</Text>
              </Pressable>
            </View>
          ) : null}
        </>
      ) : hasText ? (
        <>
          <MessageBody
            text={msg.text}
            isUser={isUser}
            onCopyCode={onCopyCode}
            onPressImage={onPressImage}
            onLongPress={handleLongPress}
          />
          {previewUrl ? <LinkPreviewCard url={previewUrl} isUser={isUser} /> : null}
        </>
      ) : null}
    </>
  );

  if (isUser) {
    return (
      <SwipeableMessageRow msg={msg} enabled={canSwipeReply} onSwipeReply={onReply}>
        <View style={st.userGroup}>
          <View style={[st.bubbleRow, st.bubbleRowUser]}>
            <Pressable
              onLongPress={handleLongPress}
              delayLongPress={260}
              style={({ pressed }) => [
                st.bubble,
                st.bubbleUser,
                { backgroundColor: t.cardAlt },
                pressed && canOpenActions && { opacity: 0.84 },
              ]}
            >
              {messageContent}
            </Pressable>
          </View>
          {isQueued ? (
            <View style={st.userQueuedRow}>
              <Text style={[st.queuedLabel, { color: t.textFaint }]}>Queued</Text>
              {canSteer ? (
                <>
                  <Text style={[st.queuedSep, { color: t.textFaint }]}>·</Text>
                  <Pressable
                    onPress={() => onSteer(msg)}
                    disabled={isSteering}
                    hitSlop={STEER_HIT_SLOP}
                    accessibilityRole="button"
                    accessibilityLabel={isSteering ? 'Sending queued message now' : 'Send queued message now'}
                    style={({ pressed }) => [
                      st.sendNowBtn,
                      { opacity: isSteering ? 0.55 : pressed ? 0.65 : 1 },
                    ]}
                  >
                    {isSteering ? <ActivityIndicator size={11} color={t.accent} /> : null}
                    <Text style={[st.sendNowText, { color: t.accent }]}>
                      {isSteering ? 'Sending…' : 'Send now'}
                    </Text>
                  </Pressable>
                </>
              ) : null}
            </View>
          ) : null}
        </View>
      </SwipeableMessageRow>
    );
  }

  return (
    <View style={st.assistantGroup}>
      {hasCompletedProgress && msg.progress ? (
        <View style={st.assistantMeta}>
          <CompletedProgressBlock progress={msg.progress} />
        </View>
      ) : null}
      <SwipeableMessageRow msg={msg} enabled={canSwipeReply} onSwipeReply={onReply}>
        <View style={[st.bubbleRow, st.bubbleRowAssistant]}>
          {isError ? (
            <Pressable
              onLongPress={handleLongPress}
              delayLongPress={260}
              style={({ pressed }) => [
                st.assistantErrorCard,
                {
                  backgroundColor: t.badSoft,
                  borderColor: t.border,
                  borderLeftColor: t.bad,
                },
                pressed && canOpenActions && { opacity: 0.84 },
              ]}
            >
              {messageContent}
            </Pressable>
          ) : (
            <View style={st.assistantPlain}>
              <Pressable
                onLongPress={handleLongPress}
                delayLongPress={260}
                style={({ pressed }) => [
                  st.assistantBodyPress,
                  pressed && canOpenActions && { opacity: 0.84 },
                ]}
              >
                {messageContent}
              </Pressable>
              {canShowCopy ? (
                <View style={st.assistantFooterRow}>
                  <CopyIconButton msgId={msg.id} text={msg.text} onCopy={onCopy} />
                </View>
              ) : null}
            </View>
          )}
        </View>
      </SwipeableMessageRow>
    </View>
  );
});

type EditingMessageState = {
  messageId: string;
  previewText: string;
  previousDraft: string;
  previousAttachments: ChatAttachment[];
};

export default function ChatScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const isFocused = useIsFocused();
  const tabBarHeight = useBottomTabBarHeight();
  const cached = useMemo(() => cacheGet<ChatCacheState>(CHAT_STATE_CACHE_KEY), []);

  const [messages, setMessages] = useState<ChatMessage[]>(() => cached?.messages ?? []);
  const [engine, setEngine] = useState<ChatEngine>(() => cached?.engine ?? 'claude');
  const [hasMore, setHasMore] = useState<boolean>(() => cached?.has_more ?? false);
  const [running, setRunning] = useState(false);
  const [activeProgress, setActiveProgress] = useState<ChatProgress | null>(null);
  const [steeringIds, setSteeringIds] = useState<Set<string>>(() => new Set());
  const [sseConnected, setSseConnected] = useState(false);
  const [loadingInitial, setLoadingInitial] = useState(() => !cached);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [sending, setSending] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [draft, setDraft] = useState('');
  const [pendingAttachments, setPendingAttachments] = useState<ChatAttachment[]>([]);
  const [uploadingCount, setUploadingCount] = useState(0);
  const [attachSheetVisible, setAttachSheetVisible] = useState(false);
  const [previewImage, setPreviewImage] = useState<PreviewImageItem | null>(null);
  const imageZoomRef = useRef<PinchZoomHandle | null>(null);
  const lastImageTapRef = useRef(0);
  const [actionSheetMsg, setActionSheetMsg] = useState<ChatMessage | null>(null);
  const [editingState, setEditingState] = useState<EditingMessageState | null>(null);
  const [replyTo, setReplyTo] = useState<ChatReplyRef | null>(null);
  const [showScrollDown, setShowScrollDown] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [appActive, setAppActive] = useState(() => AppState.currentState === 'active');
  const [kbHeight, setKbHeight] = useState(0);
  const [outerHeight, setOuterHeight] = useState(0);
  const [baseHeight, setBaseHeight] = useState(0);
  const [scrollToBottomReq, setScrollToBottomReq] = useState<{ nonce: number; animated: boolean }>({
    nonce: 0,
    animated: true,
  });
  const [androidPadAnim] = useState(() => new Animated.Value(0));
  const [copyToastVisible, setCopyToastVisible] = useState(false);
  const [copyToastAnim] = useState(() => new Animated.Value(0));

  const [agentSettings, setAgentSettings] = useState<AgentSettings | null>(() =>
    cacheGet<AgentSettings>(CACHE_AGENT_SETTINGS),
  );
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsErr, setSettingsErr] = useState<string | null>(null);
  const [pickerVisible, setPickerVisible] = useState(false);
  const [applyingModel, setApplyingModel] = useState(false);
  const [applyModelErr, setApplyModelErr] = useState<string | null>(null);

  const listRef = useRef<LegendListRef>(null);
  const inputRef = useRef<EnrichedMarkdownTextInputInstance | null>(null);
  const inputWrapRef = useRef<View>(null);
  const composerFocusedRef = useRef(false);
  const draftRef = useRef(draft);
  const pasteViewTagRef = useRef<number | null>(null);
  const pendingAttachmentsRef = useRef<ChatAttachment[]>(pendingAttachments);
  const pendingCountRef = useRef(0);
  const uploadingCountRef = useRef(0);
  const mountedRef = useRef(true);
  const isFocusedRef = useRef(isFocused);
  const isForeground = isFocused && appActive;
  const listData = useMemo(
    () => buildChatListItems(messages, { running, sending, engine, activeProgress }),
    [messages, running, sending, engine, activeProgress],
  );

  const messagesRef = useRef<ChatMessage[]>(messages);
  const listDataCountRef = useRef(listData.length);
  const sendingRef = useRef(sending);
  const runningRef = useRef(running);
  const engineRef = useRef<ChatEngine>(engine);
  const hasMoreRef = useRef(hasMore);
  const steeringIdsRef = useRef<Set<string>>(steeringIds);
  const nearBottomRef = useRef(true);
  const atBottomRef = useRef(true);
  const userDraggingRef = useRef(false);
  const programmaticScrollRef = useRef(false);
  const scrollToBottomSeqRef = useRef(0);
  const kbHeightRef = useRef(0);
  const focusTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const copyToastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loadMessagesRef = useRef<(opts?: { silent?: boolean }) => Promise<void>>(async () => {});

  useEffect(() => {
    isFocusedRef.current = isFocused;
  }, [isFocused]);

  useEffect(() => {
    try {
      PcNative?.setChatVisible?.(isForeground);
    } catch {
      // Ignore on older APKs without setChatVisible.
    }
  }, [isForeground]);

  useEffect(() => {
    return () => {
      try {
        PcNative?.setChatVisible?.(false);
      } catch {
        // Ignore on older APKs without setChatVisible.
      }
    };
  }, []);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    listDataCountRef.current = listData.length;
  }, [listData.length]);

  useEffect(() => {
    sendingRef.current = sending;
  }, [sending]);

  useEffect(() => {
    runningRef.current = running;
  }, [running]);

  useEffect(() => {
    engineRef.current = engine;
  }, [engine]);

  useEffect(() => {
    hasMoreRef.current = hasMore;
  }, [hasMore]);

  useEffect(() => {
    steeringIdsRef.current = steeringIds;
  }, [steeringIds]);

  useEffect(() => {
    pendingAttachmentsRef.current = pendingAttachments;
    pendingCountRef.current = pendingAttachments.length;
  }, [pendingAttachments]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (focusTimerRef.current) clearTimeout(focusTimerRef.current);
      if (copyToastTimerRef.current) clearTimeout(copyToastTimerRef.current);
    };
  }, []);

  const clearChatNotifs = useCallback(() => {
    try {
      PcNative?.clearChatNotifications?.();
    } catch {
      // Ignore on older APKs without clearChatNotifications.
    }
  }, []);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      const active = state === 'active';
      setAppActive(active);
      if (active && isFocusedRef.current) {
        clearChatNotifs();
        void loadMessagesRef.current({ silent: true });
      }
    });
    return () => sub.remove();
  }, [clearChatNotifs]);

  const scrollToBottom = useCallback((animated: boolean) => {
    if (listDataCountRef.current <= 0) return;
    const list = listRef.current;
    if (!list) return;
    const seq = ++scrollToBottomSeqRef.current;
    programmaticScrollRef.current = true;
    nearBottomRef.current = true;
    setShowScrollDown(false);
    void list
      .scrollToEnd({ animated })
      .then(() => {
        if (scrollToBottomSeqRef.current !== seq || !mountedRef.current) return;
        if (animated && !atBottomRef.current && !userDraggingRef.current) {
          return listRef.current?.scrollToEnd({ animated: false });
        }
        return undefined;
      })
      .finally(() => {
        if (scrollToBottomSeqRef.current === seq) {
          programmaticScrollRef.current = false;
        }
      });
  }, []);

  useEffect(() => {
    if (scrollToBottomReq.nonce === 0) return;
    scrollToBottom(scrollToBottomReq.animated);
  }, [scrollToBottomReq, scrollToBottom]);

  const onListScrollBeginDrag = useCallback(() => {
    userDraggingRef.current = true;
    scrollToBottomSeqRef.current += 1;
    programmaticScrollRef.current = false;
  }, []);

  const onListScrollEndDrag = useCallback(() => {
    userDraggingRef.current = false;
  }, []);

  const onListScroll = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const { contentOffset, contentSize, layoutMeasurement } = e.nativeEvent;
    if (contentSize.height <= layoutMeasurement.height + 24) {
      atBottomRef.current = true;
      nearBottomRef.current = true;
      setShowScrollDown((prev) => (prev ? false : prev));
      return;
    }
    const distanceFromBottom = contentSize.height - (contentOffset.y + layoutMeasurement.height);
    atBottomRef.current = distanceFromBottom <= 24;
    if (programmaticScrollRef.current) {
      if (distanceFromBottom <= 80) {
        nearBottomRef.current = true;
        setShowScrollDown((prev) => (prev ? false : prev));
      }
      return;
    }
    const isNear = nearBottomRef.current ? distanceFromBottom <= 160 : distanceFromBottom <= 80;
    nearBottomRef.current = isNear;
    setShowScrollDown((prev) => (prev === !isNear ? prev : !isNear));
  }, []);

  const onPressScrollToBottom = useCallback(() => {
    scrollToBottom(true);
  }, [scrollToBottom]);

  // Dismiss keyboard and clear any Android keyboard offset when leaving the Chat tab.
  useEffect(() => {
    if (!isFocused) {
      Keyboard.dismiss();
      androidPadAnim.stopAnimation();
      androidPadAnim.setValue(0);
    }
  }, [isFocused, androidPadAnim]);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    const showSub = Keyboard.addListener('keyboardDidShow', (e) => {
      if (!isFocusedRef.current) return;
      const h = e.endCoordinates?.height ?? 0;
      kbHeightRef.current = h;
      setKbHeight(h);
    });
    const hideSub = Keyboard.addListener('keyboardDidHide', () => {
      kbHeightRef.current = 0;
      setKbHeight(0);
    });
    return () => {
      showSub.remove();
      hideSub.remove();
    };
  }, []);

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
    }).start();
  }, [targetAndroidPad, isFocused, androidPadAnim]);

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
        setActiveProgress(res.running ? (res.progress ?? null) : null);
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
      const next = await getChatAgentSettings();
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
      clearChatNotifs();
      syncNativeChatConfig();
      void loadMessages({ silent: true });
      void loadAgentSettings();
    }, [clearChatNotifs, loadMessages, loadAgentSettings]),
  );

  // Track whether the composer currently holds an unsent draft/attachment so auto-apply OTA never wipes it.
  useEffect(() => {
    setHasActiveDraft(draft.trim().length > 0 || pendingAttachments.length > 0 || uploadingCount > 0);
  }, [draft, pendingAttachments.length, uploadingCount]);

  useEffect(() => () => setHasActiveDraft(false), []);

  // Immediately refresh when an incoming chat FCM arrives while the app is running.
  useEffect(() => {
    let sub: { remove(): void } | undefined;
    try {
      sub = PcNative?.addListener?.('onChatPush', (ev) => {
        if (typeof ev?.id === 'string' && ev.id.startsWith('ota-trigger-')) return;
        void loadMessages({ silent: true });
      });
    } catch {
      // Ignore on older APKs without onChatPush.
    }
    return () => {
      try {
        sub?.remove?.();
      } catch {
        // Ignore cleanup errors.
      }
    };
  }, [loadMessages]);

  useEffect(() => {
    loadMessagesRef.current = loadMessages;
  }, [loadMessages]);

  // Live SSE updates (/pc/api/chat/events) while the Chat tab is in the foreground.
  useEffect(() => {
    if (!isForeground) return;
    let hasConnectedOnce = false;
    const unsubscribe = subscribeChatEvents(
      (ev) => {
        if (!mountedRef.current) return;
        if (ev.event === 'snapshot') {
          setEngine(ev.data.engine);
          setRunning(ev.data.running);
          setActiveProgress(ev.data.running ? (ev.data.progress ?? null) : null);
          const qids = ev.data.queued_ids ?? [];
          setMessages((prev) => {
            const next = reconcileQueuedMessages(prev, qids, ev.data.running);
            if (next !== prev) {
              persistCache(next, ev.data.engine, hasMoreRef.current);
            }
            return next;
          });
          return;
        }
        if (ev.event === 'message') {
          const nextEngine = ev.data.engine ?? engineRef.current;
          if (ev.data.engine) setEngine(ev.data.engine);
          setRunning(ev.data.running);
          if (!ev.data.running) {
            setActiveProgress(null);
          } else if (ev.data.progress !== undefined) {
            setActiveProgress(ev.data.progress);
          }
          setMessages((prev) => {
            const next = upsertChatMessage(prev, ev.data.message, ev.data.removed_message_id);
            persistCache(next, nextEngine, hasMoreRef.current);
            return next;
          });
          return;
        }
        if (ev.event === 'progress') {
          setRunning(ev.data.running);
          setActiveProgress(ev.data.running ? (ev.data.progress ?? null) : null);
        }
      },
      (connected) => {
        if (!mountedRef.current) return;
        setSseConnected(connected);
        if (connected) {
          if (hasConnectedOnce) {
            void loadMessagesRef.current({ silent: true });
          }
          hasConnectedOnce = true;
        }
      },
    );
    return unsubscribe;
  }, [isForeground, persistCache]);

  // Safety-net polling while a reply is actively in flight (relaxed when SSE is connected).
  const polling = shouldPollChat(messages, running, isFocused, appActive);

  useEffect(() => {
    if (!polling) return;
    const intervalMs = sseConnected ? SSE_FALLBACK_POLL_INTERVAL_MS : POLL_INTERVAL_MS;
    const id = setInterval(() => {
      void loadMessages({ silent: true });
    }, intervalMs);
    return () => clearInterval(id);
  }, [polling, sseConnected, loadMessages]);

  const onLoadOlder = useCallback(async () => {
    const current = messagesRef.current;
    if (loadingOlder || !hasMore || current.length === 0) return;
    const oldest = current[0];
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
  }, [loadingOlder, hasMore]);

  const showCopyToast = useCallback(() => {
    if (copyToastTimerRef.current) {
      clearTimeout(copyToastTimerRef.current);
    }
    setCopyToastVisible(true);
    copyToastAnim.stopAnimation();
    Animated.timing(copyToastAnim, {
      toValue: 1,
      duration: 120,
      useNativeDriver: true,
    }).start();
    copyToastTimerRef.current = setTimeout(() => {
      copyToastTimerRef.current = null;
      Animated.timing(copyToastAnim, {
        toValue: 0,
        duration: 180,
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished && mountedRef.current) {
          setCopyToastVisible(false);
        }
      });
    }, COPY_TOAST_DURATION_MS);
  }, [copyToastAnim]);

  const onCopyCode = useCallback(
    (code: string) => {
      if (!code) return;
      try {
        Clipboard.setString(code);
      } catch {
        void Share.share({ message: code }).catch(() => {});
        return;
      }
      triggerHaptic('tick');
      showCopyToast();
    },
    [showCopyToast],
  );

  const onCopy = useCallback(
    (text: string) => {
      const clean = text.trim();
      if (!clean) return;
      try {
        Clipboard.setString(clean);
      } catch {
        void Share.share({ message: clean }).catch(() => {});
        return;
      }
      triggerHaptic('tick');
      showCopyToast();
    },
    [showCopyToast],
  );

  const onRemoveAttachment = useCallback((id: string) => {
    setPendingAttachments((prev) => prev.filter((a) => a.id !== id));
  }, []);

  const onOpenAttachSheet = useCallback(() => {
    if (sending) return;
    if (pendingAttachments.length >= MAX_ATTACHMENTS) {
      setErr(`Up to ${MAX_ATTACHMENTS} attachments per message.`);
      return;
    }
    Keyboard.dismiss();
    setAttachSheetVisible(true);
  }, [sending, pendingAttachments.length]);

  const uploadPickedItems = useCallback(async (picked: PickedAttachment[], initialError?: string) => {
    if (!mountedRef.current) return;
    if (picked.length === 0) {
      if (initialError) setErr(initialError);
      return;
    }
    const slots = MAX_ATTACHMENTS - (pendingCountRef.current + uploadingCountRef.current);
    if (slots <= 0) {
      setErr(`Up to ${MAX_ATTACHMENTS} attachments per message.`);
      return;
    }
    const batch = picked.slice(0, slots);
    if (initialError) {
      setErr(initialError);
    } else if (picked.length > slots) {
      setErr(`Up to ${MAX_ATTACHMENTS} attachments per message.`);
    } else {
      setErr(null);
    }
    uploadingCountRef.current += batch.length;
    setUploadingCount((c) => c + batch.length);
    for (const item of batch) {
      try {
        const uploaded = await uploadChatAttachment({
          name: item.name,
          mimeType: item.mimeType,
          base64: item.base64,
        });
        if (uploaded.kind === 'image' && item.base64) {
          primeChatAttachmentImageCache(uploaded.url, item.base64, uploaded.mime_type || item.mimeType);
        }
        if (!mountedRef.current) return;
        setPendingAttachments((prev) => {
          if (prev.length >= MAX_ATTACHMENTS) return prev;
          const next = [...prev, uploaded];
          pendingCountRef.current = next.length;
          return next;
        });
      } catch (e) {
        if (mountedRef.current) {
          setErr((e as Error).message || `Failed to upload ${item.name}`);
        }
      } finally {
        uploadingCountRef.current = Math.max(0, uploadingCountRef.current - 1);
        if (mountedRef.current) {
          setUploadingCount((c) => Math.max(0, c - 1));
        }
      }
    }
  }, []);

  const ensureImagePasteAttached = useCallback(() => {
    if (Platform.OS !== 'android' || !PcNative?.attachImagePaste) return;
    const tag = findNodeHandle(inputWrapRef.current);
    if (typeof tag !== 'number' || tag <= 0) return;
    if (pasteViewTagRef.current === tag) return;
    pasteViewTagRef.current = tag;
    try {
      void PcNative.attachImagePaste(tag)
        .then((ok) => {
          if (!ok && pasteViewTagRef.current === tag) {
            pasteViewTagRef.current = null;
          }
        })
        .catch(() => {
          if (pasteViewTagRef.current === tag) {
            pasteViewTagRef.current = null;
          }
        });
    } catch {
      if (pasteViewTagRef.current === tag) {
        pasteViewTagRef.current = null;
      }
    }
  }, []);

  useEffect(() => {
    ensureImagePasteAttached();
    return () => {
      const tag = pasteViewTagRef.current;
      pasteViewTagRef.current = null;
      if (tag !== null) {
        try {
          void PcNative?.detachImagePaste?.(tag)?.catch?.(() => {});
        } catch {
          // Ignore on older APKs without detachImagePaste.
        }
      }
    };
  }, [ensureImagePasteAttached]);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    let sub: { remove(): void } | undefined;
    try {
      sub = PcNative?.addListener?.('onImagePaste', (event) => {
        if (!mountedRef.current) return;
        const items = Array.isArray(event?.items) ? event.items : [];
        void uploadPickedItems(items, event?.error);
      });
    } catch {
      // Ignore on older APKs without onImagePaste.
    }
    return () => {
      try {
        sub?.remove?.();
      } catch {
        // Ignore cleanup errors.
      }
    };
  }, [uploadPickedItems]);

  const onPickAttachments = useCallback(
    async (kind: 'image' | 'file') => {
      setAttachSheetVisible(false);
      if (!PcNative?.pickAttachments) {
        setErr('Update the app APK to attach files.');
        return;
      }
      const slots = MAX_ATTACHMENTS - (pendingCountRef.current + uploadingCountRef.current);
      if (slots <= 0) {
        setErr(`Up to ${MAX_ATTACHMENTS} attachments per message.`);
        return;
      }
      setErr(null);
      try {
        const picked = await PcNative.pickAttachments(kind);
        if (!mountedRef.current || !picked || picked.length === 0) return;
        await uploadPickedItems(picked);
      } catch (e) {
        if (mountedRef.current) {
          setErr((e as Error).message || 'Could not pick attachment');
        }
      }
    },
    [uploadPickedItems],
  );

  const composerMarkdownStyle = useMemo(() => buildComposerMarkdownStyle(t), [t]);

  const setDraftValue = useCallback((next: string) => {
    draftRef.current = next;
    setDraft(next);
    inputRef.current?.setValue(next);
  }, []);

  const onChangeDraftMarkdown = useCallback((markdown: string) => {
    draftRef.current = markdown;
    setDraft(markdown);
  }, []);

  const onComposerFocus = useCallback(() => {
    composerFocusedRef.current = true;
    ensureImagePasteAttached();
  }, [ensureImagePasteAttached]);

  const onComposerBlur = useCallback(() => {
    composerFocusedRef.current = false;
  }, []);

  const sendMessagePayload = useCallback(
    async (
      text: string,
      attachments: ChatAttachment[],
      opts?: {
        clearComposer?: boolean;
        restoreEditOnError?: EditingMessageState | null;
        replyTo?: ChatReplyRef | null;
        restoreReplyOnError?: ChatReplyRef | null;
      },
    ) => {
      const trimmed = text.trim();
      if ((!trimmed && attachments.length === 0) || sendingRef.current) return;
      const clientId = makeClientId();
      const nowSec = Date.now() / 1000;
      const wasRunning = runningRef.current;
      const optUser: ChatMessage = {
        id: `opt-u-${clientId}`,
        role: 'user',
        engine,
        text: trimmed,
        created_at: nowSec,
        status: 'done',
        client_id: clientId,
        reply_to: opts?.replyTo ?? undefined,
        attachments: attachments.length > 0 ? attachments : undefined,
        optimistic: true,
        queued: wasRunning,
      };

      if (opts?.clearComposer) {
        setDraftValue('');
        setPendingAttachments([]);
        setEditingState(null);
        setReplyTo(null);
      }
      setErr(null);
      sendingRef.current = true;
      setSending(true);
      setRunning(true);
      if (!wasRunning) {
        setActiveProgress({ engine, started: nowSec, steps: [] });
      }
      nearBottomRef.current = true;
      setShowScrollDown(false);
      setMessages((prev) => [...prev, optUser]);
      setScrollToBottomReq((prev) => ({ nonce: prev.nonce + 1, animated: true }));

      try {
        const res = await sendChatMessage(trimmed, clientId, attachments, opts?.replyTo?.id);
        if (!mountedRef.current) return;
        setEngine(res.engine);
        setRunning(res.running);
        setMessages((prev) => {
          const confirmed = [res.user_message, ...(res.assistant_message ? [res.assistant_message] : [])];
          const withoutOpt = prev.filter((m) => m.id !== optUser.id);
          const next = mergeChatMessages(withoutOpt, [...withoutOpt.filter((m) => !m.optimistic), ...confirmed]);
          persistCache(next, res.engine, hasMore);
          return next;
        });
      } catch (e) {
        if (!mountedRef.current) return;
        setMessages((prev) => prev.filter((m) => m.id !== optUser.id));
        if (opts?.clearComposer) {
          setDraftValue(trimmed);
          setPendingAttachments(attachments);
          if (opts.restoreEditOnError) {
            setEditingState(opts.restoreEditOnError);
          }
          if (opts.restoreReplyOnError) {
            setReplyTo(opts.restoreReplyOnError);
          }
        }
        if (!wasRunning) {
          setRunning(false);
          setActiveProgress(null);
        }
        setErr((e as Error).message || 'Failed to send message');
      } finally {
        sendingRef.current = false;
        if (mountedRef.current) setSending(false);
      }
    },
    [engine, hasMore, persistCache, setDraftValue],
  );

  const onSteerMessage = useCallback(
    async (msg: ChatMessage) => {
      if (!msg.id || msg.optimistic || steeringIdsRef.current.has(msg.id)) return;
      triggerHaptic('tick');
      setErr(null);
      setSteeringIds((prev) => {
        const next = new Set(prev);
        next.add(msg.id);
        return next;
      });
      try {
        const res = await steerChatMessage(msg.id);
        if (!mountedRef.current) return;
        setRunning(res.running);
        if (!res.running) {
          setActiveProgress(null);
        }
        setMessages((prev) => {
          const next = upsertChatMessage(prev, res.user_message);
          persistCache(next, engineRef.current, hasMoreRef.current);
          return next;
        });
      } catch (e) {
        if (!mountedRef.current) return;
        setErr((e as Error).message || 'Failed to send message now');
        void loadMessages({ silent: true });
      } finally {
        if (mountedRef.current) {
          setSteeringIds((prev) => {
            if (!prev.has(msg.id)) return prev;
            const next = new Set(prev);
            next.delete(msg.id);
            return next;
          });
        }
      }
    },
    [loadMessages, persistCache],
  );

  const onSend = useCallback(() => {
    if (uploadingCount > 0) return;
    void sendMessagePayload(draft, pendingAttachments, {
      clearComposer: true,
      restoreEditOnError: editingState,
      replyTo,
      restoreReplyOnError: replyTo,
    });
  }, [draft, pendingAttachments, uploadingCount, editingState, replyTo, sendMessagePayload]);

  const onOpenMessageActions = useCallback((msg: ChatMessage) => {
    Keyboard.dismiss();
    setActionSheetMsg(msg);
  }, []);

  const focusComposer = useCallback((delayMs = 0) => {
    if (focusTimerRef.current) {
      clearTimeout(focusTimerRef.current);
      focusTimerRef.current = null;
    }
    const runFocus = () => {
      if (!mountedRef.current) return;
      const input = inputRef.current;
      if (!input) return;
      // On Android, hardware-back dismissal can leave the input focused while the
      // IME is hidden; blur + next-frame focus forces showSoftInput reliably.
      if (Platform.OS === 'android' && kbHeightRef.current === 0 && composerFocusedRef.current) {
        input.blur();
        requestAnimationFrame(() => {
          if (mountedRef.current) {
            inputRef.current?.focus();
          }
        });
        return;
      }
      input.focus();
    };
    if (delayMs > 0) {
      focusTimerRef.current = setTimeout(() => {
        focusTimerRef.current = null;
        requestAnimationFrame(runFocus);
      }, delayMs);
    } else {
      requestAnimationFrame(runFocus);
    }
  }, []);

  const onStartReplyMessage = useCallback(
    (msg: ChatMessage, opts?: { fromSheet?: boolean }) => {
      setActionSheetMsg(null);
      setEditingState((prev) => {
        if (prev) {
          setDraftValue(prev.previousDraft);
          setPendingAttachments(prev.previousAttachments);
        }
        return null;
      });
      setReplyTo(buildReplyRef(msg));
      focusComposer(opts?.fromSheet ? 220 : 60);
    },
    [focusComposer, setDraftValue],
  );

  const onCancelReply = useCallback(() => {
    setReplyTo(null);
  }, []);

  const onPressReplyQuote = useCallback((replyId: string) => {
    const idx = messagesRef.current.findIndex((m) => m.id === replyId);
    if (idx >= 0) {
      void listRef.current?.scrollToIndex({ index: idx, animated: true, viewPosition: 0.35 });
    }
  }, []);

  const onStartEditMessage = useCallback(
    (msg: ChatMessage, opts?: { fromSheet?: boolean }) => {
      setActionSheetMsg(null);
      setReplyTo(null);
      setEditingState((prev) => ({
        messageId: msg.id,
        previewText: formatMessagePreview(msg),
        previousDraft: prev ? prev.previousDraft : draftRef.current,
        previousAttachments: prev ? prev.previousAttachments : pendingAttachmentsRef.current,
      }));
      setDraftValue(msg.text);
      setPendingAttachments(msg.attachments ? [...msg.attachments] : []);
      focusComposer(opts?.fromSheet ? 220 : 60);
    },
    [focusComposer, setDraftValue],
  );

  const onCancelEdit = useCallback(() => {
    setEditingState((prev) => {
      if (prev) {
        setDraftValue(prev.previousDraft);
        setPendingAttachments(prev.previousAttachments);
      }
      return null;
    });
  }, [setDraftValue]);

  const onResendMessage = useCallback(
    (msg: ChatMessage) => {
      setActionSheetMsg(null);
      void sendMessagePayload(msg.text, msg.attachments ?? []);
    },
    [sendMessagePayload],
  );

  const onRetryAssistantError = useCallback(
    (msg: ChatMessage) => {
      setActionSheetMsg(null);
      const list = messagesRef.current;
      const idx = list.findIndex((m) => m.id === msg.id);
      if (idx < 0) return;
      const userMsg = findPrecedingUserMessage(list, idx);
      if (!userMsg) return;
      void sendMessagePayload(userMsg.text, userMsg.attachments ?? []);
    },
    [sendMessagePayload],
  );

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
              setScrollToBottomReq((prev) => ({ nonce: prev.nonce + 1, animated: true }));
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
          const updated = await updateChatAgentSettings(patch);
          if (!mountedRef.current) return;
          cacheSet(CACHE_AGENT_SETTINGS, updated);
          setAgentSettings(updated);
        }
        if (nextEngine !== engine) {
          const nextState = await switchChatAgent(nextEngine);
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

  const canSend = (draft.trim().length > 0 || pendingAttachments.length > 0) && !sending && uploadingCount === 0;

  const keyExtractor = useCallback((item: ChatMessage) => getChatMessageKey(item), []);

  const onPressImage = useCallback((img: PreviewImageItem) => {
    Keyboard.dismiss();
    lastImageTapRef.current = 0;
    setPreviewImage(img);
  }, []);

  const previewActionUrl = useMemo(() => {
    if (!previewImage) return null;
    if (previewImage.linkUrl && isHttpUrl(previewImage.linkUrl)) {
      return previewImage.linkUrl;
    }
    const resolved = resolveChatAttachmentSource(previewImage.url).uri;
    return isHttpUrl(resolved) ? resolved : null;
  }, [previewImage]);

  const onSharePreviewImage = useCallback(() => {
    if (!previewImage || !previewActionUrl) return;
    void Share.share({
      message: previewActionUrl,
      url: previewActionUrl,
      title: previewImage.name,
    }).catch(() => {});
  }, [previewActionUrl, previewImage]);

  const onOpenPreviewImage = useCallback(() => {
    if (!previewActionUrl) return;
    openExternalUrl(previewActionUrl);
  }, [previewActionUrl]);

  const onPreviewImageTap = useCallback((x: number, y: number, now: number) => {
    if (now - lastImageTapRef.current < 300) {
      lastImageTapRef.current = 0;
      imageZoomRef.current?.toggleAt(x, y);
      return;
    }
    lastImageTapRef.current = now;
  }, []);

  const onPreviewImagePress = useCallback(
    (e: GestureResponderEvent) => {
      onPreviewImageTap(e.nativeEvent.locationX, e.nativeEvent.locationY, e.nativeEvent.timestamp);
    },
    [onPreviewImageTap],
  );

  const renderItem = useCallback(
    ({ item, index, data }: LegendListRenderItemProps<ChatMessage>) => (
      <MessageBubble
        msg={item}
        canRetry={
          item.role === 'assistant' &&
          item.status === 'error' &&
          findPrecedingUserMessage(data, index) !== null
        }
        isQueued={Boolean(running && item.role === 'user' && item.queued)}
        canSteer={canSteerQueuedMessage(item, running)}
        isSteering={steeringIds.has(item.id)}
        onLongPress={onOpenMessageActions}
        onReply={onStartReplyMessage}
        onCopy={onCopy}
        onRetry={onRetryAssistantError}
        onSteer={onSteerMessage}
        onPressReplyQuote={onPressReplyQuote}
        onCopyCode={onCopyCode}
        onPressImage={onPressImage}
      />
    ),
    [
      running,
      steeringIds,
      onOpenMessageActions,
      onStartReplyMessage,
      onCopy,
      onRetryAssistantError,
      onSteerMessage,
      onPressReplyQuote,
      onCopyCode,
      onPressImage,
    ],
  );

  const isEmpty = listData.length === 0;
  const listContentStyle = useMemo(
    () => [st.listContent, isEmpty && st.listContentEmpty],
    [isEmpty],
  );

  const listHeader = useMemo(
    () =>
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
      ) : null,
    [hasMore, loadingOlder, onLoadOlder],
  );

  const listEmpty = useMemo(
    () => (
      <View style={st.emptyWrap}>
        <View style={[st.emptyIcon, { backgroundColor: t.card }]}>
          <Icon name="message-text" size={22} color={t.textFaint} />
        </View>
        <Text style={[st.emptyLabel, { color: t.textFaint }]}>Start a conversation</Text>
      </View>
    ),
    [t.card, t.textFaint],
  );

  const actionSheetView = useMemo(() => {
    if (!actionSheetMsg) return null;
    const isUserMsg = actionSheetMsg.role === 'user';
    const isErrMsg = actionSheetMsg.status === 'error';
    const copyText = (actionSheetMsg.text || actionSheetMsg.error || '').trim();
    const preview = formatMessagePreview(actionSheetMsg);
    const timeStr = formatChatTime(actionSheetMsg.created_at);
    const metaLabel = isUserMsg
      ? 'You'
      : isErrMsg
        ? `${engineLabel(actionSheetMsg.engine)} · Error`
        : engineLabel(actionSheetMsg.engine);
    const idx = isErrMsg && !isUserMsg ? messages.findIndex((m) => m.id === actionSheetMsg.id) : -1;
    const canRetry = idx >= 0 && findPrecedingUserMessage(messages, idx) !== null;
    return { msg: actionSheetMsg, isUserMsg, isErrMsg, copyText, preview, timeStr, metaLabel, canRetry };
  }, [actionSheetMsg, messages]);

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
          <Icon name="pencil-box" size={15} color={t.text} />
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
        {loadingInitial && listData.length === 0 ? (
          <ChatSkeleton />
        ) : (
          <LegendList
            ref={listRef}
            data={listData}
            keyExtractor={keyExtractor}
            renderItem={renderItem}
            initialScrollAtEnd
            alignItemsAtEnd
            maintainScrollAtEnd
            maintainScrollAtEndThreshold={0.15}
            maintainVisibleContentPosition
            estimatedItemSize={72}
            contentContainerStyle={listContentStyle}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="interactive"
            onScroll={onListScroll}
            onScrollBeginDrag={onListScrollBeginDrag}
            onScrollEndDrag={onListScrollEndDrag}
            scrollEventThrottle={100}
            ListHeaderComponent={listHeader}
            ListEmptyComponent={listEmpty}
          />
        )}
        {showScrollDown && listData.length > 0 ? (
          <Pressable
            onPress={onPressScrollToBottom}
            accessibilityRole="button"
            accessibilityLabel="Scroll to latest message"
            android_ripple={{ color: t.ripple, foreground: true }}
            style={({ pressed }) => [
              st.scrollDownBtn,
              {
                bottom: space(2),
                backgroundColor: t.card,
                borderColor: t.border,
                opacity: pressed ? 0.8 : 1,
              },
            ]}
          >
            <Icon name="chevron-down" size={20} color={t.text} />
          </Pressable>
        ) : null}

        {copyToastVisible ? (
          <Animated.View
            pointerEvents="none"
            accessibilityLiveRegion="polite"
            style={[
              st.copyToastWrap,
              {
                bottom: space(2.5),
                opacity: copyToastAnim,
                transform: [
                  {
                    translateY: copyToastAnim.interpolate({
                      inputRange: [0, 1],
                      outputRange: [6, 0],
                    }),
                  },
                ],
              },
            ]}
          >
            <View
              style={[
                st.copyToastPill,
                { backgroundColor: t.cardAlt, borderColor: t.border },
              ]}
            >
              <Icon name="check" size={14} color={t.accent} />
              <Text style={[st.copyToastText, { color: t.text }]}>Copied</Text>
            </View>
          </Animated.View>
        ) : null}
      </View>

      <View style={st.composerDock}>
          {pendingAttachments.length > 0 || uploadingCount > 0 ? (
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={st.previewStrip}
              style={st.previewStripWrap}
              keyboardShouldPersistTaps="handled"
            >
              {pendingAttachments.map((att) =>
                att.kind === 'image' ? (
                  <View key={att.id} style={st.previewImgWrap}>
                    <ChatAttachmentImage
                      url={att.url}
                      mimeType={att.mime_type}
                      name={att.name}
                      style={[st.previewImg, { backgroundColor: t.cardAlt, borderColor: t.border }]}
                      resizeMode="cover"
                      compact
                      onPress={() =>
                        onPressImage({ url: att.url, name: att.name, mimeType: att.mime_type })
                      }
                    />
                    <Pressable
                      onPress={() => onRemoveAttachment(att.id)}
                      accessibilityLabel={`Remove ${att.name}`}
                      hitSlop={6}
                      style={[st.previewRemoveBadge, { backgroundColor: t.bg, borderColor: t.border }]}
                    >
                      <Icon name="close" size={12} color={t.text} />
                    </Pressable>
                  </View>
                ) : (
                  <View
                    key={att.id}
                    style={[st.previewFileChip, { backgroundColor: t.card, borderColor: t.border }]}
                  >
                    <Icon name="file-document" size={16} color={t.accent} />
                    <View style={st.previewFileMeta}>
                      <Text numberOfLines={1} style={[st.previewFileName, { color: t.text }]}>
                        {att.name}
                      </Text>
                      <Text style={[st.previewFileSize, { color: t.textFaint }]}>
                        {formatAttachmentSize(att.size_bytes)}
                      </Text>
                    </View>
                    <Pressable
                      onPress={() => onRemoveAttachment(att.id)}
                      accessibilityLabel={`Remove ${att.name}`}
                      hitSlop={6}
                      style={st.previewFileClose}
                    >
                      <Icon name="close" size={14} color={t.textDim} />
                    </Pressable>
                  </View>
                ),
              )}
              {uploadingCount > 0 ? (
                <View style={[st.uploadingChip, { backgroundColor: t.card, borderColor: t.border }]}>
                  <ActivityIndicator size="small" color={t.accent} />
                  <Text style={[st.uploadingText, { color: t.textDim }]}>
                    {uploadingCount > 1 ? `Uploading ${uploadingCount}…` : 'Uploading…'}
                  </Text>
                </View>
              ) : null}
            </ScrollView>
          ) : null}

          {replyTo ? (
            <View
              style={[
                st.replyBar,
                {
                  backgroundColor: t.card,
                  borderColor: t.border,
                  borderLeftColor: t.accent,
                },
              ]}
            >
              <View style={st.replyBarTextWrap}>
                <Text numberOfLines={1} style={[st.replyBarLabel, { color: t.accent }]}>
                  Replying to {replySenderLabel(replyTo)}
                </Text>
                {replyTo.text ? (
                  <Text numberOfLines={1} style={[st.replyBarPreview, { color: t.textDim }]}>
                    {replyTo.text}
                  </Text>
                ) : null}
              </View>
              <Pressable
                onPress={onCancelReply}
                accessibilityRole="button"
                accessibilityLabel="Cancel reply"
                hitSlop={8}
                style={({ pressed }) => [st.editingBarClose, pressed && { opacity: 0.7 }]}
              >
                <Icon name="close" size={16} color={t.textDim} />
              </Pressable>
            </View>
          ) : null}

          {editingState ? (
            <View
              style={[
                st.editingBar,
                { backgroundColor: t.card, borderColor: t.border },
              ]}
            >
              <View style={st.editingBarLeft}>
                <Icon name="pencil" size={14} color={t.accent} />
                <Text style={[st.editingBarLabel, { color: t.accent }]}>Editing message</Text>
                {editingState.previewText ? (
                  <Text numberOfLines={1} style={[st.editingBarPreview, { color: t.textFaint }]}>
                    · {editingState.previewText}
                  </Text>
                ) : null}
              </View>
              <Pressable
                onPress={onCancelEdit}
                accessibilityRole="button"
                accessibilityLabel="Cancel editing message"
                hitSlop={8}
                style={({ pressed }) => [st.editingBarClose, pressed && { opacity: 0.7 }]}
              >
                <Icon name="close" size={16} color={t.textDim} />
              </Pressable>
            </View>
          ) : null}

          <View
            style={[
              st.composerPill,
              {
                backgroundColor: t.card,
                borderColor: editingState ? t.accent : t.border,
              },
            ]}
          >
            <Pressable
              onPress={onOpenAttachSheet}
              disabled={sending || pendingAttachments.length >= MAX_ATTACHMENTS}
              accessibilityRole="button"
              accessibilityLabel="Add photo or file"
              android_ripple={{ color: t.ripple, foreground: true }}
              style={({ pressed }) => [
                st.attachBtn,
                {
                  backgroundColor: t.cardAlt,
                  opacity:
                    sending || pendingAttachments.length >= MAX_ATTACHMENTS ? 0.4 : pressed ? 0.8 : 1,
                },
              ]}
            >
              <Icon name="plus" size={20} color={t.text} />
            </Pressable>
            <View ref={inputWrapRef} collapsable={false} style={st.inputWrap} onLayout={ensureImagePasteAttached}>
              <EnrichedMarkdownTextInput
                ref={inputRef}
                onLayout={ensureImagePasteAttached}
                onChangeMarkdown={onChangeDraftMarkdown}
                onFocus={onComposerFocus}
                onBlur={onComposerBlur}
                markdownShortcuts={COMPOSER_MARKDOWN_SHORTCUTS}
                markdownStyle={composerMarkdownStyle}
                placeholder={`Message ${engineLabel(engine)}`}
                placeholderTextColor={t.textFaint}
                multiline
                scrollEnabled
                cursorColor={t.accent}
                selectionColor={t.accentSoft}
                style={[st.input, { color: t.text }]}
              />
            </View>
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
      <Modal
        visible={attachSheetVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setAttachSheetVisible(false)}
      >
        <Pressable style={st.sheetBackdrop} onPress={() => setAttachSheetVisible(false)}>
          <Pressable
            onPress={() => {}}
            style={[st.sheetCard, { backgroundColor: t.card, borderColor: t.border }]}
          >
            <Text style={[st.sheetTitle, { color: t.textDim }]}>Attach</Text>
            <Pressable
              onPress={() => void onPickAttachments('image')}
              android_ripple={{ color: t.ripple, foreground: true }}
              style={({ pressed }) => [
                st.sheetRow,
                { backgroundColor: pressed ? t.cardAlt : 'transparent' },
              ]}
            >
              <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                <Icon name="image" size={20} color={t.accent} />
              </View>
              <View style={st.sheetRowText}>
                <Text style={[st.sheetRowTitle, { color: t.text }]}>Photo</Text>
                <Text style={[st.sheetRowSub, { color: t.textFaint }]}>Choose images from your device</Text>
              </View>
            </Pressable>
            <Pressable
              onPress={() => void onPickAttachments('file')}
              android_ripple={{ color: t.ripple, foreground: true }}
              style={({ pressed }) => [
                st.sheetRow,
                { backgroundColor: pressed ? t.cardAlt : 'transparent' },
              ]}
            >
              <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                <Icon name="file-document" size={20} color={t.accent} />
              </View>
              <View style={st.sheetRowText}>
                <Text style={[st.sheetRowTitle, { color: t.text }]}>File</Text>
                <Text style={[st.sheetRowSub, { color: t.textFaint }]}>Documents, logs, or any file up to 15 MB</Text>
              </View>
            </Pressable>
          </Pressable>
        </Pressable>
      </Modal>
      <Modal
        visible={actionSheetView !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setActionSheetMsg(null)}
      >
        <Pressable style={st.sheetBackdrop} onPress={() => setActionSheetMsg(null)}>
          {actionSheetView ? (
            <Pressable
              onPress={() => {}}
              style={[st.sheetCard, { backgroundColor: t.card, borderColor: t.border }]}
            >
              <View style={[st.sheetPreview, { backgroundColor: t.cardAlt }]}>
                <Text style={[st.sheetPreviewMeta, { color: actionSheetView.isErrMsg ? t.bad : t.textDim }]}>
                  {actionSheetView.metaLabel}
                  {actionSheetView.timeStr ? ` · ${actionSheetView.timeStr}` : ''}
                </Text>
                {actionSheetView.preview ? (
                  <Text numberOfLines={2} style={[st.sheetPreviewText, { color: t.text }]}>
                    {actionSheetView.preview}
                  </Text>
                ) : null}
              </View>

              <Pressable
                onPress={() => onStartReplyMessage(actionSheetView.msg, { fromSheet: true })}
                android_ripple={{ color: t.ripple, foreground: true }}
                style={({ pressed }) => [
                  st.sheetRow,
                  { backgroundColor: pressed ? t.cardAlt : 'transparent' },
                ]}
              >
                <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                  <Icon name="reply" size={20} color={t.accent} />
                </View>
                <View style={st.sheetRowText}>
                  <Text style={[st.sheetRowTitle, { color: t.text }]}>Reply</Text>
                  <Text style={[st.sheetRowSub, { color: t.textFaint }]}>
                    Quote this message in your reply
                  </Text>
                </View>
              </Pressable>

              {actionSheetView.isUserMsg ? (
                <>
                  <Pressable
                    onPress={() => onStartEditMessage(actionSheetView.msg, { fromSheet: true })}
                    android_ripple={{ color: t.ripple, foreground: true }}
                    style={({ pressed }) => [
                      st.sheetRow,
                      { backgroundColor: pressed ? t.cardAlt : 'transparent' },
                    ]}
                  >
                    <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                      <Icon name="pencil" size={20} color={t.accent} />
                    </View>
                    <View style={st.sheetRowText}>
                      <Text style={[st.sheetRowTitle, { color: t.text }]}>Edit & resend</Text>
                      <Text style={[st.sheetRowSub, { color: t.textFaint }]}>
                        Modify in composer before sending
                      </Text>
                    </View>
                  </Pressable>
                  <Pressable
                    onPress={() => onResendMessage(actionSheetView.msg)}
                    disabled={sending}
                    android_ripple={{ color: t.ripple, foreground: true }}
                    style={({ pressed }) => [
                      st.sheetRow,
                      {
                        backgroundColor: pressed ? t.cardAlt : 'transparent',
                        opacity: sending ? 0.45 : 1,
                      },
                    ]}
                  >
                    <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                      <Icon name="refresh" size={20} color={t.textDim} />
                    </View>
                    <View style={st.sheetRowText}>
                      <Text style={[st.sheetRowTitle, { color: t.text }]}>Resend</Text>
                      <Text style={[st.sheetRowSub, { color: t.textFaint }]}>
                        Send again immediately
                      </Text>
                    </View>
                  </Pressable>
                </>
              ) : null}

              {!actionSheetView.isUserMsg && actionSheetView.isErrMsg && actionSheetView.canRetry ? (
                <Pressable
                  onPress={() => onRetryAssistantError(actionSheetView.msg)}
                  disabled={sending}
                  android_ripple={{ color: t.ripple, foreground: true }}
                  style={({ pressed }) => [
                    st.sheetRow,
                    {
                      backgroundColor: pressed ? t.cardAlt : 'transparent',
                      opacity: sending ? 0.45 : 1,
                    },
                  ]}
                >
                  <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                    <Icon name="refresh" size={20} color={t.accent} />
                  </View>
                  <View style={st.sheetRowText}>
                    <Text style={[st.sheetRowTitle, { color: t.text }]}>Retry</Text>
                    <Text style={[st.sheetRowSub, { color: t.textFaint }]}>
                      Resend your previous message
                    </Text>
                  </View>
                </Pressable>
              ) : null}

              {actionSheetView.copyText ? (
                <Pressable
                  onPress={() => {
                    setActionSheetMsg(null);
                    onCopy(actionSheetView.copyText);
                  }}
                  android_ripple={{ color: t.ripple, foreground: true }}
                  style={({ pressed }) => [
                    st.sheetRow,
                    { backgroundColor: pressed ? t.cardAlt : 'transparent' },
                  ]}
                >
                  <View style={[st.sheetIconWrap, { backgroundColor: t.cardAlt }]}>
                    <Icon name="content-copy" size={20} color={t.textDim} />
                  </View>
                  <View style={st.sheetRowText}>
                    <Text style={[st.sheetRowTitle, { color: t.text }]}>Copy</Text>
                    <Text style={[st.sheetRowSub, { color: t.textFaint }]}>
                      {actionSheetView.isErrMsg ? 'Copy error details' : 'Copy message text'}
                    </Text>
                  </View>
                </Pressable>
              ) : null}
            </Pressable>
          ) : null}
        </Pressable>
      </Modal>

      <Modal
        visible={previewImage !== null}
        transparent
        statusBarTranslucent
        animationType="fade"
        onRequestClose={() => setPreviewImage(null)}
      >
        <View
          style={[
            st.imageModalBackdrop,
            {
              paddingTop: Math.max(insets.top, space(3)) + space(1.5),
              paddingBottom: Math.max(insets.bottom, space(3)) + space(1.5),
            },
          ]}
        >
          <View style={st.imageModalHeader}>
            <Text numberOfLines={1} style={st.imageModalTitle}>
              {previewImage?.name ?? ''}
            </Text>
            <Pressable
              onPress={() => setPreviewImage(null)}
              accessibilityRole="button"
              accessibilityLabel="Close image preview"
              hitSlop={10}
              style={({ pressed }) => [st.imageModalClose, pressed && { opacity: 0.72 }]}
            >
              <Icon name="close" size={20} color="#F5F5F5" />
            </Pressable>
          </View>
          <View style={st.imageModalBody}>
            {previewImage ? (
              <PinchZoom
                ref={imageZoomRef}
                resetKey={previewImage.url}
                style={st.imageModalZoom}
                onTap={onPreviewImageTap}
              >
                <Pressable style={st.imageModalZoom} onPress={onPreviewImagePress}>
                  <ChatAttachmentImage
                    url={previewImage.url}
                    mimeType={previewImage.mimeType}
                    name={previewImage.name}
                    style={st.imageModalImg}
                    resizeMode="contain"
                  />
                </Pressable>
              </PinchZoom>
            ) : null}
          </View>
          {previewActionUrl ? (
            <View style={st.imageModalActions}>
              <Pressable
                onPress={onSharePreviewImage}
                accessibilityRole="button"
                accessibilityLabel="Share image"
                android_ripple={{ color: 'rgba(255,255,255,0.12)', foreground: true }}
                style={({ pressed }) => [st.imageModalActionBtn, pressed && { opacity: 0.78 }]}
              >
                <Icon name="share-variant" size={18} color="#F5F5F5" />
                <Text style={st.imageModalActionText}>Share</Text>
              </Pressable>
              <Pressable
                onPress={onOpenPreviewImage}
                accessibilityRole="button"
                accessibilityLabel="Open image in browser"
                android_ripple={{ color: 'rgba(255,255,255,0.12)', foreground: true }}
                style={({ pressed }) => [st.imageModalActionBtn, pressed && { opacity: 0.78 }]}
              >
                <Icon name="open-in-new" size={18} color="#F5F5F5" />
                <Text style={st.imageModalActionText}>Open in browser</Text>
              </Pressable>
            </View>
          ) : null}
        </View>
      </Modal>
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
  listWrap: { flex: 1, position: 'relative', overflow: 'hidden' },
  listContent: {
    paddingHorizontal: screenPad,
    paddingTop: space(1),
    paddingBottom: space(3),
    gap: space(3.5),
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
  swipeRowWrap: {
    position: 'relative',
    width: '100%',
    justifyContent: 'center',
  },
  swipeReplyIndicatorWrap: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
  },
  swipeReplyIconCircle: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swipeBubbleTrack: {
    width: '100%',
  },
  assistantGroup: {
    width: '100%',
    alignItems: 'flex-start',
    gap: 6,
    paddingVertical: 2,
  },
  assistantMeta: {
    width: '100%',
    alignSelf: 'flex-start',
    gap: 4,
  },
  userGroup: {
    width: '100%',
    alignItems: 'flex-end',
  },
  userQueuedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 6,
    marginTop: 4,
    paddingRight: 4,
  },
  queuedLabel: {
    fontSize: 12,
    fontWeight: '400',
  },
  queuedSep: {
    fontSize: 12,
    fontWeight: '400',
  },
  sendNowBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingVertical: 1,
  },
  sendNowText: {
    fontSize: 12,
    fontWeight: '500',
  },
  bubbleRow: { flexDirection: 'row', width: '100%' },
  bubbleRowUser: { justifyContent: 'flex-end' },
  bubbleRowAssistant: { justifyContent: 'flex-start' },
  bubble: {
    borderRadius: 18,
    paddingHorizontal: 13,
    paddingTop: 8,
    paddingBottom: 8,
    gap: 3,
  },
  bubbleUser: {
    maxWidth: '82%',
    borderBottomRightRadius: 5,
  },
  assistantPlain: {
    width: '100%',
    gap: 4,
  },
  assistantBodyPress: {
    width: '100%',
    gap: 6,
  },
  assistantErrorCard: {
    width: '100%',
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderLeftWidth: 3,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 6,
    overflow: 'hidden',
  },
  assistantFooterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-start',
    width: '100%',
    marginTop: 2,
  },
  footerIconBtn: {
    padding: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  replyQuote: {
    borderLeftWidth: 2,
    borderRadius: 10,
    paddingHorizontal: 9,
    paddingVertical: 6,
    marginBottom: 4,
    gap: 2,
    overflow: 'hidden',
  },
  replyQuoteAssistant: {
    borderWidth: StyleSheet.hairlineWidth,
    borderLeftWidth: 2,
  },
  replyQuoteSender: {
    fontSize: 11.5,
    fontWeight: '600',
  },
  replyQuoteText: {
    fontSize: 12.5,
    lineHeight: 16.5,
  },
  messageBodyStack: { gap: space(2.5) },
  linkCard: {
    flexDirection: 'row',
    borderWidth: 1,
    borderRadius: radius.sm,
    overflow: 'hidden',
    marginTop: 6,
  },
  linkCardBar: {
    width: 3,
  },
  linkCardBody: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  linkCardText: {
    flex: 1,
    minWidth: 0,
    gap: 2,
  },
  linkCardDomain: {
    fontSize: 11,
    fontWeight: '500',
  },
  linkCardTitle: {
    fontSize: 13.5,
    fontWeight: '600',
    lineHeight: 18,
  },
  linkCardDesc: {
    fontSize: 12,
    lineHeight: 16,
  },
  linkCardThumb: {
    width: 64,
    height: 64,
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
  },
  codeBlock: {
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  codeHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
    paddingLeft: space(2.5),
    paddingRight: space(1.5),
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  codeLang: { fontSize: 11, flexShrink: 1 },
  codeCopyChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  codeCopyChipText: { fontSize: 11, fontWeight: '500' },
  codeBodyScroll: { flexGrow: 1 },
  codeBodyPress: {
    flexGrow: 1,
    paddingHorizontal: space(2.5),
    paddingVertical: space(2),
  },
  codeText: { fontSize: 12.5, lineHeight: 18 },
  tableCard: {
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  tableScrollContent: {
    flexGrow: 1,
  },
  tableInner: {
    flexGrow: 1,
  },
  tableRow: {
    flexDirection: 'row',
    alignItems: 'stretch',
  },
  tableCell: {
    paddingHorizontal: space(2.5),
    paddingVertical: space(2),
    justifyContent: 'center',
  },
  tableHeaderText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '600',
    fontVariant: ['tabular-nums'],
  },
  tableCellText: {
    fontSize: 13.5,
    lineHeight: 19,
    fontWeight: '400',
    fontVariant: ['tabular-nums'],
  },
  tableBoldText: {
    fontWeight: '600',
  },
  tableInlineCode: {
    fontSize: 12,
    borderRadius: 4,
  },
  tableLinkText: {
    textDecorationLine: 'underline',
  },
  copyToastWrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  copyToastPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 13,
    paddingVertical: 7,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    elevation: 3,
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 2 },
  },
  copyToastText: {
    fontSize: 12.5,
    fontWeight: '500',
  },
  shimmerWrap: {
    position: 'relative',
    overflow: 'hidden',
    alignSelf: 'flex-start',
    maxWidth: '100%',
  },
  shimmerBand: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    overflow: 'hidden',
  },
  shimmerText: {
    fontSize: 13.5,
    lineHeight: 19,
    fontWeight: '400',
  },
  thinkingWrap: {
    width: '100%',
    gap: 6,
  },
  thinkingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    maxWidth: '100%',
    gap: 6,
    paddingVertical: 2,
  },
  thinkingTextFlex: {
    flexShrink: 1,
    minWidth: 0,
  },
  completedProgressWrap: {
    width: '100%',
    gap: 5,
  },
  completedProgressRow: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    maxWidth: '100%',
    gap: 3,
    paddingVertical: 2,
  },
  completedProgressText: {
    fontSize: 12.5,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  progressList: {
    width: '100%',
    borderLeftWidth: 1.5,
    paddingLeft: 10,
    marginLeft: 3,
    gap: 4,
    paddingVertical: 2,
  },
  progressStepRow: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 6,
  },
  progressStepDot: {
    width: 4,
    height: 4,
    borderRadius: 2,
    marginTop: 6.5,
    flexShrink: 0,
  },
  progressStepText: {
    flex: 1,
    flexShrink: 1,
    minWidth: 0,
    fontSize: 12.5,
    lineHeight: 17,
  },
  errorText: { fontSize: 14, lineHeight: 20 },
  errorFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-start',
    gap: space(2),
    marginTop: 4,
  },
  retryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  retryBtnText: { fontSize: 12.5, fontWeight: '500' },
  systemRow: { flexDirection: 'row', alignItems: 'center', gap: space(2.5), paddingVertical: space(2) },
  systemLine: { flex: 1, height: StyleSheet.hairlineWidth },
  systemText: { fontSize: 11.5, fontVariant: ['tabular-nums'] },
  scrollDownBtn: {
    position: 'absolute',
    right: screenPad,
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
  composerDock: {
    flexShrink: 0,
  },
  replyBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    marginHorizontal: screenPad,
    marginTop: space(1),
    paddingLeft: 10,
    paddingRight: 8,
    paddingVertical: 7,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderLeftWidth: 2,
  },
  replyBarTextWrap: {
    flex: 1,
    minWidth: 0,
    gap: 1,
  },
  replyBarLabel: {
    fontSize: 12,
    fontWeight: '600',
  },
  replyBarPreview: {
    fontSize: 12.5,
  },
  editingBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    marginHorizontal: screenPad,
    marginTop: space(1),
    paddingLeft: 12,
    paddingRight: 8,
    paddingVertical: 7,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
  },
  editingBarLeft: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    minWidth: 0,
  },
  editingBarLabel: {
    fontSize: 12.5,
    fontWeight: '500',
    flexShrink: 0,
  },
  editingBarPreview: {
    fontSize: 12,
    flexShrink: 1,
  },
  editingBarClose: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
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
    paddingLeft: 6,
    paddingRight: 6,
    paddingVertical: 6,
    elevation: 4,
    shadowColor: '#000',
    shadowOpacity: 0.22,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
  },
  attachBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 1,
    overflow: 'hidden',
  },
  previewStripWrap: {
    flexGrow: 0,
    marginTop: space(1),
  },
  previewStrip: {
    paddingHorizontal: screenPad,
    paddingVertical: 4,
    gap: 8,
    alignItems: 'center',
  },
  previewImgWrap: {
    position: 'relative',
  },
  previewImg: {
    width: 56,
    height: 56,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
  previewRemoveBadge: {
    position: 'absolute',
    top: -4,
    right: -4,
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  previewFileChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    height: 46,
    maxWidth: 190,
    paddingHorizontal: 10,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
  previewFileMeta: {
    flexShrink: 1,
  },
  previewFileName: {
    fontSize: 12.5,
    fontWeight: '500',
  },
  previewFileSize: {
    fontSize: 10.5,
    fontVariant: ['tabular-nums'],
  },
  previewFileClose: {
    padding: 2,
  },
  uploadingChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    height: 46,
    paddingHorizontal: 12,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
  uploadingText: {
    fontSize: 12.5,
  },
  bubbleAttWrap: {
    gap: 6,
    marginBottom: 4,
  },
  attImgWrap: {
    overflow: 'hidden',
    position: 'relative',
  },
  attImgFallback: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    paddingHorizontal: 8,
  },
  attImgRetryText: {
    fontSize: 11,
    fontWeight: '500',
  },
  mdImageWrap: {
    width: '100%',
    alignItems: 'flex-start',
  },
  mdImageFrame: {
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
    position: 'relative',
  },
  imageModalBackdrop: {
    flex: 1,
    backgroundColor: '#000000',
  },
  imageModalHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: screenPad,
    paddingBottom: space(2),
    gap: 12,
  },
  imageModalTitle: {
    flex: 1,
    color: '#F5F5F5',
    fontSize: 14,
    fontWeight: '500',
  },
  imageModalClose: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#1F1F1F',
    alignItems: 'center',
    justifyContent: 'center',
  },
  imageModalBody: {
    flex: 1,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  imageModalZoom: {
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  imageModalImg: {
    width: '100%',
    height: '100%',
  },
  imageModalActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2.5),
    paddingHorizontal: screenPad,
    paddingTop: space(2.5),
  },
  imageModalActionBtn: {
    flex: 1,
    height: 44,
    borderRadius: radius.sm,
    backgroundColor: '#1F1F1F',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    overflow: 'hidden',
  },
  imageModalActionText: {
    color: '#F5F5F5',
    fontSize: 14,
    fontWeight: '500',
  },
  bubbleImgGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  bubbleImgSingle: {
    width: 196,
    height: 148,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
  },
  bubbleImgMulti: {
    width: 112,
    height: 112,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
  },
  bubbleFileChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    maxWidth: 230,
  },
  bubbleFileMeta: {
    flexShrink: 1,
  },
  bubbleFileName: {
    fontSize: 12.5,
    fontWeight: '500',
  },
  bubbleFileSize: {
    fontSize: 10.5,
    fontVariant: ['tabular-nums'],
  },
  sheetBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'flex-end',
    padding: screenPad,
    paddingBottom: space(6),
  },
  sheetCard: {
    borderRadius: 20,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space(3),
    gap: 4,
  },
  sheetTitle: {
    fontSize: 12,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    paddingHorizontal: 8,
    paddingBottom: 6,
  },
  sheetPreview: {
    borderRadius: radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 9,
    marginBottom: 4,
    gap: 3,
  },
  sheetPreviewMeta: {
    fontSize: 11.5,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  sheetPreviewText: {
    fontSize: 13.5,
    lineHeight: 18,
  },
  sheetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 10,
    paddingVertical: 11,
    borderRadius: 12,
    overflow: 'hidden',
  },
  sheetIconWrap: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sheetRowText: {
    flex: 1,
    gap: 1,
  },
  sheetRowTitle: {
    fontSize: 15,
    fontWeight: '500',
  },
  sheetRowSub: {
    fontSize: 12,
  },
  inputWrap: {
    flex: 1,
    minWidth: 0,
    justifyContent: 'center',
  },
  input: {
    width: '100%',
    minHeight: 36,
    maxHeight: 120,
    paddingTop: 7,
    paddingBottom: 7,
    paddingRight: 4,
    fontSize: 15,
    lineHeight: 20,
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
  skelAssistantPlain: {
    alignSelf: 'stretch',
    paddingVertical: space(1),
    gap: space(2),
  },
  skelLine: { height: 12, borderRadius: 4 },
});
