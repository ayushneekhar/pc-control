import { cacheGet, cacheRemove, cacheSet } from './cache';

export type ChatEngine = 'claude' | 'gemini';
export type ChatRole = 'user' | 'assistant' | 'system';
export type ChatStatus = 'pending' | 'done' | 'error';

export type ChatAttachment = {
  id: string;
  name: string;
  mime_type: string;
  size_bytes: number;
  kind: 'image' | 'file';
  url: string;
};

export type ChatMessage = {
  id: string;
  role: ChatRole;
  engine: ChatEngine;
  text: string;
  created_at: number;
  status: ChatStatus;
  error?: string | null;
  client_id?: string | null;
  reply_to?: string | null;
  kind?: string;
  attachments?: ChatAttachment[];
  optimistic?: boolean;
};

export type ChatMessagesResponse = {
  messages: ChatMessage[];
  engine: ChatEngine;
  running: boolean;
  has_more: boolean;
  ts: number;
};

export type ChatSendResponse = {
  ok: boolean;
  deduped: boolean;
  user_message: ChatMessage;
  assistant_message: ChatMessage | null;
  engine: ChatEngine;
  running: boolean;
};

export type ChatNewResponse = {
  ok: boolean;
  engine: ChatEngine;
  boundary_message: ChatMessage;
  running: boolean;
};

export type ChatEndpointConfig = {
  url: string;
  token: string;
};

export type ChatCacheState = {
  messages: ChatMessage[];
  engine: ChatEngine;
  has_more: boolean;
};

export const CHAT_ENDPOINT_CACHE_KEY = 'chat:endpoint';
export const CHAT_STATE_CACHE_KEY = 'chat:state';

export function normalizeChatUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  // Strip a trailing /pc/api/chat... if the user pasted a full path instead of the host origin.
  return trimmed.replace(/\/pc\/api\/chat.*$/i, '').replace(/\/+$/, '');
}

export function getChatEndpoint(): ChatEndpointConfig {
  const stored = cacheGet<Partial<ChatEndpointConfig>>(CHAT_ENDPOINT_CACHE_KEY);
  return {
    url: typeof stored?.url === 'string' ? normalizeChatUrl(stored.url) : '',
    token: typeof stored?.token === 'string' ? stored.token.trim() : '',
  };
}

export function setChatEndpoint(cfg: ChatEndpointConfig): ChatEndpointConfig {
  const clean: ChatEndpointConfig = {
    url: normalizeChatUrl(cfg.url),
    token: cfg.token.trim(),
  };
  if (!clean.url && !clean.token) {
    cacheRemove(CHAT_ENDPOINT_CACHE_KEY);
  } else {
    cacheSet(CHAT_ENDPOINT_CACHE_KEY, clean);
  }
  return clean;
}

export function resolveChatTarget(
  defaultBaseUrl: string,
  endpoint: ChatEndpointConfig,
): { baseUrl: string; headers: Record<string, string>; custom: boolean } {
  const customUrl = normalizeChatUrl(endpoint.url);
  const tok = endpoint.token.trim();
  if (customUrl) {
    return {
      baseUrl: customUrl,
      headers: tok ? { Authorization: `Bearer ${tok}` } : {},
      custom: true,
    };
  }
  return {
    baseUrl: defaultBaseUrl.replace(/\/+$/, ''),
    headers: {},
    custom: false,
  };
}

export function makeClientId(): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `c-${Date.now()}-${rand}`;
}

/** Merge server messages with any still-in-flight optimistic messages, deduplicating by id and client_id. */
export function mergeChatMessages(current: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const seenIds = new Set<string>();
  const seenClientIds = new Set<string>();
  const merged: ChatMessage[] = [];

  for (const m of incoming) {
    if (seenIds.has(m.id)) continue;
    seenIds.add(m.id);
    if (m.client_id) seenClientIds.add(m.client_id);
    merged.push({ ...m, optimistic: false });
  }

  // Keep only optimistic messages whose client_id has not yet been confirmed by the server.
  for (const m of current) {
    if (!m.optimistic) continue;
    if (seenIds.has(m.id)) continue;
    if (m.client_id && seenClientIds.has(m.client_id)) continue;
    if (m.reply_to && seenClientIds.has(m.reply_to)) continue;
    merged.push(m);
  }

  merged.sort((a, b) => a.created_at - b.created_at);
  return merged;
}

/** Prepend older paginated messages without duplicating existing IDs. */
export function prependOlderMessages(older: ChatMessage[], current: ChatMessage[]): ChatMessage[] {
  const existingIds = new Set(current.map((m) => m.id));
  const prefix = older.filter((m) => !existingIds.has(m.id));
  return [...prefix, ...current];
}

export function shouldPollChat(
  messages: ChatMessage[],
  running: boolean,
  isFocused: boolean,
  appActive: boolean,
): boolean {
  if (!isFocused || !appActive) return false;
  if (running) return true;
  return messages.some((m) => m.status === 'pending');
}

export type MessageSegment =
  | { kind: 'text'; text: string }
  | { kind: 'codeblock'; lang: string; code: string };

export type InlineToken =
  | { kind: 'text'; text: string }
  | { kind: 'code'; code: string }
  | { kind: 'bold'; text: string }
  | { kind: 'link'; label: string; url: string; external: boolean };

export type LinkPreview = {
  url: string;
  domain: string;
  title: string | null;
  description: string | null;
  image: string | null;
  site_name: string | null;
};

export function isHttpUrl(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

function trimBareUrl(raw: string): { url: string; trailing: string } {
  let end = raw.length;
  while (end > 0) {
    const ch = raw[end - 1];
    if (ch === ')') {
      const slice = raw.slice(0, end);
      const opens = (slice.match(/\(/g) ?? []).length;
      const closes = (slice.match(/\)/g) ?? []).length;
      if (closes > opens) {
        end -= 1;
        continue;
      }
      break;
    }
    if (/[.,;:!?'"\]}]/.test(ch)) {
      end -= 1;
      continue;
    }
    break;
  }
  return { url: raw.slice(0, end), trailing: raw.slice(end) };
}

/** Tokenize inline code, Markdown `[label](url)` links, bare http(s) URLs, and `**bold**` spans. */
export function parseInlineTokens(text: string): InlineToken[] {
  if (!text) return [];
  const tokens: InlineToken[] = [];
  const pushText = (chunk: string) => {
    if (!chunk) return;
    const prev = tokens[tokens.length - 1];
    if (prev && prev.kind === 'text') {
      prev.text += chunk;
    } else {
      tokens.push({ kind: 'text', text: chunk });
    }
  };

  const pattern =
    /`([^`\n]+)`|\[([^\]\n]+)\]\(([a-zA-Z][a-zA-Z0-9+.-]*:\/\/(?:[^()\s]|\([^()\s]*\))+)\)|(https?:\/\/[^\s<>]+)|\*\*([^*\n]+)\*\*/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      pushText(text.slice(lastIndex, match.index));
    }
    if (match[1] !== undefined) {
      tokens.push({ kind: 'code', code: match[1] });
    } else if (match[2] !== undefined && match[3] !== undefined) {
      const url = match[3];
      tokens.push({ kind: 'link', label: match[2], url, external: isHttpUrl(url) });
    } else if (match[4] !== undefined) {
      const { url, trailing } = trimBareUrl(match[4]);
      if (url && /^https?:\/\/[^/\s]+/i.test(url)) {
        tokens.push({ kind: 'link', label: url, url, external: true });
        pushText(trailing);
      } else {
        pushText(match[4]);
      }
    } else if (match[5] !== undefined) {
      tokens.push({ kind: 'bold', text: match[5] });
    }
    lastIndex = pattern.lastIndex;
  }
  if (lastIndex < text.length) {
    pushText(text.slice(lastIndex));
  }
  return tokens;
}

/** Return the first external http(s) URL in a message (ignoring code blocks and inline code). */
export function extractPreviewUrl(text: string): string | null {
  if (!text) return null;
  for (const seg of parseMessageSegments(text)) {
    if (seg.kind !== 'text') continue;
    for (const tok of parseInlineTokens(seg.text)) {
      if (tok.kind === 'link' && tok.external) {
        return tok.url;
      }
    }
  }
  return null;
}

/** Split message text into plain text and fenced code blocks (```lang ... ```). */
export function parseMessageSegments(text: string): MessageSegment[] {
  if (!text) return [];
  const out: MessageSegment[] = [];
  const fence = /```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = fence.exec(text)) !== null) {
    if (match.index > lastIndex) {
      const plain = text.slice(lastIndex, match.index);
      if (plain.trim().length > 0) {
        out.push({ kind: 'text', text: plain.replace(/^\n+|\n+$/g, '') });
      }
    }
    const lang = (match[1] || '').trim();
    const code = (match[2] || '').replace(/\n$/, '');
    out.push({ kind: 'codeblock', lang, code });
    lastIndex = fence.lastIndex;
  }
  if (lastIndex < text.length) {
    const tail = text.slice(lastIndex);
    if (tail.trim().length > 0 || out.length === 0) {
      out.push({ kind: 'text', text: tail.replace(/^\n+|\n+$/g, '') });
    }
  }
  return out;
}

export function formatChatTime(epochSec: number, nowMs = Date.now()): string {
  if (!Number.isFinite(epochSec) || epochSec <= 0) return '';
  const d = new Date(epochSec * 1000);
  const now = new Date(nowMs);
  const hh = d.getHours();
  const mm = String(d.getMinutes()).padStart(2, '0');
  const h12 = hh % 12 || 12;
  const ampm = hh < 12 ? 'am' : 'pm';
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) return `${h12}:${mm} ${ampm}`;
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${months[d.getMonth()]} ${d.getDate()}, ${h12}:${mm} ${ampm}`;
}

/**
 * Show the engine tag on an assistant bubble only when it's an error, when the engine changed
 * compared to the previous assistant message, or when the first assistant message differs from
 * the currently active header engine.
 */
export function shouldShowEngineTag(
  messages: readonly ChatMessage[],
  index: number,
  activeEngine: ChatEngine,
): boolean {
  const msg = messages[index];
  if (!msg || msg.role !== 'assistant') return false;
  if (msg.status === 'error') return true;
  for (let i = index - 1; i >= 0; i -= 1) {
    if (messages[i].role === 'assistant') {
      return messages[i].engine !== msg.engine;
    }
  }
  return msg.engine !== activeEngine;
}

function hasSendableContent(msg: ChatMessage): boolean {
  return msg.text.trim().length > 0 || (msg.attachments?.length ?? 0) > 0;
}

/**
 * Finds the preceding user message that corresponds to the message at `index`
 * (typically an assistant error bubble) so it can be retried with one tap.
 * Stops at system conversation boundaries.
 */
export function findPrecedingUserMessage(messages: readonly ChatMessage[], index: number): ChatMessage | null {
  if (index <= 0 || index >= messages.length) return null;
  const target = messages[index];
  const replyTo = target?.reply_to?.trim();
  if (replyTo) {
    for (let i = index - 1; i >= 0; i -= 1) {
      const m = messages[i];
      if (m.role === 'user' && (m.id === replyTo || m.client_id === replyTo) && hasSendableContent(m)) {
        return m;
      }
    }
  }
  for (let i = index - 1; i >= 0; i -= 1) {
    const m = messages[i];
    if (m.role === 'system') return null;
    if (m.role === 'user' && hasSendableContent(m)) {
      return m;
    }
  }
  return null;
}

/** Compact single-line preview of a message for the action sheet or editing bar. */
export function formatMessagePreview(msg: ChatMessage): string {
  const body = msg.text.trim();
  if (body) return body.replace(/\s+/g, ' ');
  if (msg.status === 'error' && msg.error?.trim()) {
    return msg.error.trim().replace(/\s+/g, ' ');
  }
  const atts = msg.attachments ?? [];
  if (atts.length === 1) {
    return atts[0].name || (atts[0].kind === 'image' ? 'Photo' : 'Attachment');
  }
  if (atts.length > 1) {
    return `${atts.length} attachments`;
  }
  return '';
}

/**
 * Computes the extra bottom padding needed on Android when the soft keyboard is visible.
 * Subtracts the bottom tab bar height (which sits behind the keyboard on edge-to-edge Android)
 * and any height already shrunk by the OS window resize so there is never a dead gap or double-lift.
 */
export function computeAndroidKeyboardOffset(
  keyboardHeight: number,
  tabBarHeight: number,
  containerShrink = 0,
): number {
  if (!Number.isFinite(keyboardHeight) || keyboardHeight <= 0) return 0;
  const tab = Number.isFinite(tabBarHeight) && tabBarHeight > 0 ? tabBarHeight : 0;
  const shrink = Number.isFinite(containerShrink) && containerShrink > 0 ? containerShrink : 0;
  return Math.max(0, keyboardHeight - tab - shrink);
}

/** Compact model label for the in-composer pill (e.g. "Neon", "Opus", "Sonnet", "Flash"). */
export function shortModelLabel(modelId?: string | null, label?: string | null): string {
  const rawLabel = (label || '').trim();
  const rawId = (modelId || '').trim().toLowerCase();

  if (rawLabel) {
    // "Gemini 4 Neon" -> "Neon", "Gemini 3.8 Flash" -> "Flash"
    const geminiMatch = rawLabel.match(/^Gemini\s+[\d.]+\s+(.+)$/i);
    if (geminiMatch) return geminiMatch[1].trim();
    // "Orchestrator (Neon)" -> "Orch"
    if (/^Orchestrator\b/i.test(rawLabel)) {
      const paren = rawLabel.match(/\(([^)]+)\)/);
      return paren ? `Orch ${paren[1].trim()}` : 'Orch';
    }
    // "Opus 5.5" -> "Opus", "Sonnet 5.5" -> "Sonnet"
    const claudeMatch = rawLabel.match(/^([A-Za-z]+)\s+[\d.]+$/);
    if (claudeMatch) return claudeMatch[1];
    return rawLabel;
  }

  if (rawId) {
    const parts = rawId.split('-').filter(Boolean);
    if (parts[0] === 'claude' && parts[1]) {
      return parts[1][0].toUpperCase() + parts[1].slice(1);
    }
    if (parts[0] === 'gemini' && parts.length >= 2) {
      const tail = parts[parts.length - 1];
      return tail[0].toUpperCase() + tail.slice(1);
    }
    return rawId;
  }

  return '';
}

/** Compact effort label for the composer pill and segmented selector. */
export function shortEffortLabel(effort?: string | null): string {
  const v = (effort || '').trim().toLowerCase();
  if (!v) return '';
  if (v === 'medium') return 'Med';
  if (v === 'xhigh') return 'XHigh';
  return v[0].toUpperCase() + v.slice(1);
}

/** Builds the concise composer pill label such as "Neon · Med" or "Opus · High". */
export function formatComposerChipLabel(
  engine: ChatEngine,
  modelId?: string | null,
  modelLabel?: string | null,
  effort?: string | null,
): string {
  const m = shortModelLabel(modelId, modelLabel) || (engine === 'gemini' ? 'Gemini' : 'Claude');
  const e = shortEffortLabel(effort);
  return e ? `${m} · ${e}` : m;
}

/** Human-readable byte size for chat file attachment chips. */
export function formatAttachmentSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`;
  const mb = kb / 1024;
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}
