import { cacheGet, cacheRemove, cacheSet } from './cache';

export type ChatEngine = 'claude' | 'gemini';
export type ChatRole = 'user' | 'assistant' | 'system';
export type ChatStatus = 'pending' | 'done' | 'error';

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
