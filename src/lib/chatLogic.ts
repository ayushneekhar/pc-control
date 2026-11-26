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

export type ChatReplyRef = {
  id: string;
  role: ChatRole;
  engine?: ChatEngine;
  text: string;
};

export type ChatProgressStep = {
  t: number;
  text: string;
};

export type ChatProgress = {
  engine?: ChatEngine;
  started: number;
  duration_s?: number;
  step_count?: number;
  steps: ChatProgressStep[];
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
  reply_to?: ChatReplyRef | string | null;
  kind?: string;
  attachments?: ChatAttachment[];
  progress?: ChatProgress | null;
  optimistic?: boolean;
  queued?: boolean;
  steered?: boolean;
};

export type ChatMessagesResponse = {
  messages: ChatMessage[];
  engine: ChatEngine;
  running: boolean;
  progress?: ChatProgress | null;
  queued_ids?: string[];
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

export type ChatSteerStatus =
  | 'steered'
  | 'dispatched'
  | 'steering'
  | 'already_running'
  | 'already_steered'
  | 'already_completed';

export type ChatSteerResponse = {
  ok: boolean;
  status: ChatSteerStatus;
  steered: boolean;
  user_message: ChatMessage;
  running: boolean;
};

export type ChatSseEvent =
  | {
      event: 'snapshot';
      data: {
        engine: ChatEngine;
        running: boolean;
        progress?: ChatProgress | null;
        queued_ids?: string[];
        ts: number;
      };
    }
  | {
      event: 'message';
      data: {
        message: ChatMessage;
        running: boolean;
        engine?: ChatEngine;
        progress?: ChatProgress | null;
        steered?: boolean;
        removed_message_id?: string;
        ts: number;
      };
    }
  | {
      event: 'progress';
      data: {
        running: boolean;
        progress: ChatProgress | null;
        ts: number;
      };
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
export const THINKING_INDICATOR_ID = '__chat_thinking__';

/**
 * Stable list key for a ChatMessage so an optimistic user bubble keeps the exact same key
 * once confirmed by the server (preventing container remount and scroll jumps mid-send).
 */
export function getChatMessageKey(msg: Pick<ChatMessage, 'id' | 'role' | 'client_id'>): string {
  if (msg.role === 'user' && msg.client_id) {
    return `u:${msg.client_id}`;
  }
  return msg.id;
}

/**
 * Builds the message array rendered by the chat list, appending a trailing pending assistant
 * item for the live thinking/progress indicator whenever a reply is in flight and no pending
 * message already exists in `messages`. Keeping the indicator as a real list item ensures it
 * participates in list virtualization, spacing, and scrollToEnd targeting cleanly.
 */
export function buildChatListItems(
  messages: readonly ChatMessage[],
  opts: {
    running: boolean;
    sending: boolean;
    engine: ChatEngine;
    activeProgress?: ChatProgress | null;
  },
): ChatMessage[] {
  const hasPendingMsg = messages.some((m) => m.status === 'pending');
  if ((!opts.running && !opts.sending) || hasPendingMsg) {
    return messages as ChatMessage[];
  }
  const thinkingItem: ChatMessage = {
    id: THINKING_INDICATOR_ID,
    role: 'assistant',
    engine: opts.activeProgress?.engine ?? opts.engine,
    text: '',
    created_at: opts.activeProgress?.started ?? 0,
    status: 'pending',
    progress: opts.activeProgress ?? null,
    optimistic: true,
  };
  return [...messages, thinkingItem];
}

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

export function buildChatAttachmentSource(
  url: string,
  defaultBaseUrl: string,
  endpoint: ChatEndpointConfig,
): { uri: string; headers?: Record<string, string> } {
  const target = resolveChatTarget(defaultBaseUrl, endpoint);
  const trimmed = url.trim();
  const isAbsolute = /^https?:\/\//i.test(trimmed);
  const uri = isAbsolute ? trimmed : `${target.baseUrl}${trimmed.startsWith('/') ? trimmed : `/${trimmed}`}`;
  const hasHeaders = Object.keys(target.headers).length > 0;
  if (!hasHeaders) return { uri };
  if (!isAbsolute) return { uri, headers: target.headers };
  const baseLower = target.baseUrl.toLowerCase();
  const uriLower = uri.toLowerCase();
  if (uriLower === baseLower || uriLower.startsWith(`${baseLower}/`)) {
    return { uri, headers: target.headers };
  }
  return { uri };
}

/**
 * React Native 0.86 Android Fabric's `<Image>` drops `source.headers` (both in
 * `Image.android.js` for single-object sources and in Fabric's `ImageProps` /
 * `ReactImageView.setSource`), so any attachment source that requires custom
 * request headers (e.g. `Authorization: Bearer ...`) must be fetched via JS
 * `fetch` and rendered as a `data:` URI.
 */
export function chatAttachmentRequiresFetch(source: {
  uri: string;
  headers?: Record<string, string>;
}): boolean {
  return Boolean(source.headers && Object.keys(source.headers).length > 0);
}

/** Stable cache key for an attachment image source, scoped to its URI and auth headers. */
export function attachmentImageCacheKey(source: {
  uri: string;
  headers?: Record<string, string>;
}): string {
  const headers = source.headers;
  if (!headers) return source.uri;
  const keys = Object.keys(headers).sort();
  if (keys.length === 0) return source.uri;
  const sig = keys.map((k) => `${k}:${headers[k]}`).join('\n');
  return `${source.uri}\n${sig}`;
}

/** Build or normalize a `data:<mime>;base64,...` URI from raw base64 or a FileReader data URL. */
export function buildAttachmentDataUri(base64OrDataUrl: string, mimeType?: string | null): string {
  const cleanMime = (mimeType || '').split(';')[0].trim().toLowerCase();
  const fallbackMime = cleanMime && cleanMime !== 'application/octet-stream' ? cleanMime : 'image/jpeg';
  const trimmed = base64OrDataUrl.trim();
  if (/^data:/i.test(trimmed)) {
    const comma = trimmed.indexOf(',');
    if (comma > 0) {
      const header = trimmed.slice(5, comma).toLowerCase();
      if (
        cleanMime &&
        cleanMime !== 'application/octet-stream' &&
        (header === ';base64' || header === 'application/octet-stream;base64')
      ) {
        return `data:${cleanMime};base64,${trimmed.slice(comma + 1)}`;
      }
    }
    return trimmed;
  }
  return `data:${fallbackMime};base64,${trimmed}`;
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
    if (typeof m.reply_to === 'string' && seenClientIds.has(m.reply_to)) continue;
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
  return messages.some((m) => m.status === 'pending' || m.queued === true);
}

/** Returns true when a user message should show the clickable "Send now" steering action. */
export function canSteerQueuedMessage(msg: ChatMessage, running: boolean): boolean {
  return Boolean(running && msg.role === 'user' && msg.queued === true && !msg.optimistic);
}

/** Apply a single incoming server message (new or updated) to the local chat list, removing any superseded placeholder. */
export function upsertChatMessage(
  current: ChatMessage[],
  incoming: ChatMessage,
  removedId?: string,
): ChatMessage[] {
  const normalized: ChatMessage = { ...incoming, optimistic: false };
  let replaced = false;
  const next: ChatMessage[] = [];
  for (const m of current) {
    if (removedId && m.id === removedId) continue;
    const sameId = m.id === normalized.id;
    const sameClientId =
      Boolean(normalized.client_id) &&
      m.role === 'user' &&
      normalized.role === 'user' &&
      m.client_id === normalized.client_id;
    if (sameId || sameClientId) {
      if (!replaced) {
        next.push(normalized);
        replaced = true;
      }
      continue;
    }
    next.push(m);
  }
  if (!replaced) {
    next.push(normalized);
  }
  next.sort((a, b) => a.created_at - b.created_at);
  return next;
}

/** Reconcile local queued flags with the authoritative server queued_ids list from an SSE snapshot. */
export function reconcileQueuedMessages(
  current: ChatMessage[],
  queuedIds: readonly string[],
  running: boolean,
): ChatMessage[] {
  const queuedSet = running ? new Set(queuedIds) : new Set<string>();
  let changed = false;
  const next = current.map((m) => {
    if (m.role !== 'user' || m.optimistic) return m;
    const shouldBeQueued = queuedSet.has(m.id);
    if (Boolean(m.queued) === shouldBeQueued) return m;
    changed = true;
    return { ...m, queued: shouldBeQueued };
  });
  return changed ? next : current;
}

/** Parse complete SSE event blocks from a raw text buffer for `/pc/api/chat/events`. */
export function parseChatSseBlocks(buf: string): { events: ChatSseEvent[]; rest: string } {
  const events: ChatSseEvent[] = [];
  let rem = buf.replace(/\r\n/g, '\n');
  let idx: number;
  while ((idx = rem.indexOf('\n\n')) >= 0) {
    const block = rem.slice(0, idx);
    rem = rem.slice(idx + 2);
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue;
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (dataLines.length === 0) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(dataLines.join('\n'));
    } catch {
      continue;
    }
    if (!parsed || typeof parsed !== 'object') continue;
    const obj = parsed as Record<string, unknown>;
    if (eventName === 'snapshot') {
      events.push({
        event: 'snapshot',
        data: {
          engine: obj.engine === 'gemini' ? 'gemini' : 'claude',
          running: Boolean(obj.running),
          progress: (obj.progress as ChatProgress | null | undefined) ?? null,
          queued_ids: Array.isArray(obj.queued_ids)
            ? obj.queued_ids.filter((x): x is string => typeof x === 'string')
            : [],
          ts: typeof obj.ts === 'number' ? obj.ts : 0,
        },
      });
    } else if (eventName === 'message' && obj.message && typeof obj.message === 'object') {
      events.push({
        event: 'message',
        data: {
          message: obj.message as ChatMessage,
          running: Boolean(obj.running),
          engine: obj.engine === 'gemini' ? 'gemini' : obj.engine === 'claude' ? 'claude' : undefined,
          progress: obj.progress !== undefined ? ((obj.progress as ChatProgress | null) ?? null) : undefined,
          steered: typeof obj.steered === 'boolean' ? obj.steered : undefined,
          removed_message_id: typeof obj.removed_message_id === 'string' ? obj.removed_message_id : undefined,
          ts: typeof obj.ts === 'number' ? obj.ts : 0,
        },
      });
    } else if (eventName === 'progress') {
      events.push({
        event: 'progress',
        data: {
          running: Boolean(obj.running),
          progress: (obj.progress as ChatProgress | null | undefined) ?? null,
          ts: typeof obj.ts === 'number' ? obj.ts : 0,
        },
      });
    }
  }
  return { events, rest: rem };
}

export type TableAlignment = 'left' | 'center' | 'right';

export type MessageSegment =
  | { kind: 'text'; text: string }
  | { kind: 'codeblock'; lang: string; code: string }
  | { kind: 'table'; headers: string[]; alignments: TableAlignment[]; rows: string[][] }
  | { kind: 'image'; alt: string; url: string; linkUrl?: string };

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

/** Convert `t3-thread://v2/<envId>/<threadId>` into the T3 Code Android App Link URL. */
export function resolveT3ThreadUrl(url: string): string | null {
  const m = /^t3-thread:\/\/v2\/([^/]+)\/(.+)$/i.exec(url.trim());
  if (!m) return null;
  const decode = (s: string) => {
    try {
      return decodeURIComponent(s);
    } catch {
      return s;
    }
  };
  const envId = decode(m[1]).trim();
  const threadId = decode(m[2]).trim();
  if (!envId || !threadId) return null;
  return `https://app.t3.codes/threads/${encodeURIComponent(envId)}/${encodeURIComponent(threadId)}`;
}

/** Resolve a tapped Markdown/chat link URL (http(s) or `t3-thread://v2/...`) into an openable http(s) URL. */
export function resolveChatLinkUrl(url: string): string | null {
  const trimmed = url.trim();
  if (!trimmed) return null;
  if (isHttpUrl(trimmed)) return trimmed;
  return resolveT3ThreadUrl(trimmed);
}

/**
 * Normalize a non-code Markdown text segment before handing it to EnrichedMarkdownText:
 * converts leading Unicode bullet markers (`• item`) into CommonMark `- item` so they get
 * proper native hanging indentation, while leaving mid-line bullets and all other syntax intact.
 */
export function normalizeMarkdownForDisplay(text: string): string {
  if (!text || !text.includes('•')) return text;
  return text.replace(/^([ \t]*)•[ \t]+/gm, '$1- ');
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
    if (seg.kind === 'text') {
      for (const tok of parseInlineTokens(seg.text)) {
        if (tok.kind === 'link' && tok.external) {
          return tok.url;
        }
      }
    } else if (seg.kind === 'table') {
      for (const row of [seg.headers, ...seg.rows]) {
        for (const cell of row) {
          for (const tok of parseInlineTokens(cell)) {
            if (tok.kind === 'link' && tok.external) {
              return tok.url;
            }
          }
        }
      }
    }
  }
  return null;
}

/** Split a GFM table row into trimmed cells, respecting inline code spans and escaped pipes (`\|`). */
function splitTableRow(line: string): string[] | null {
  const trimmed = line.trim();
  if (!trimmed) return null;

  // Find unescaped pipe positions outside backtick code spans.
  const pipeIndices: number[] = [];
  let inCode = false;
  for (let i = 0; i < trimmed.length; i += 1) {
    const ch = trimmed[i];
    if (ch === '\\' && !inCode && i + 1 < trimmed.length) {
      i += 1;
      continue;
    }
    if (ch === '`') {
      inCode = !inCode;
      continue;
    }
    if (ch === '|' && !inCode) {
      pipeIndices.push(i);
    }
  }
  if (pipeIndices.length === 0) return null;

  let start = 0;
  let end = trimmed.length;
  let firstPipe = 0;
  let lastPipe = pipeIndices.length - 1;

  if (pipeIndices[firstPipe] === 0) {
    start = 1;
    firstPipe += 1;
  }
  if (lastPipe >= firstPipe && pipeIndices[lastPipe] === trimmed.length - 1) {
    end = trimmed.length - 1;
    lastPipe -= 1;
  }

  const rawCells: string[] = [];
  let cursor = start;
  for (let p = firstPipe; p <= lastPipe; p += 1) {
    const idx = pipeIndices[p];
    rawCells.push(trimmed.slice(cursor, idx));
    cursor = idx + 1;
  }
  rawCells.push(trimmed.slice(cursor, end));

  if (rawCells.length === 0) return null;

  return rawCells.map((cell) => {
    // Unescape `\|` outside inline code spans.
    let out = '';
    let code = false;
    for (let i = 0; i < cell.length; i += 1) {
      const ch = cell[i];
      if (ch === '`') {
        code = !code;
        out += ch;
      } else if (ch === '\\' && !code && i + 1 < cell.length && cell[i + 1] === '|') {
        out += '|';
        i += 1;
      } else {
        out += ch;
      }
    }
    return out.trim();
  });
}

const TABLE_ALIGN_CELL_RE = /^:?-{1,}:?$/;

/** Parse a GFM delimiter row (e.g. `| --- | :---: | ---: |`) into column alignments, or null if invalid. */
function parseTableSeparator(line: string): TableAlignment[] | null {
  const cells = splitTableRow(line);
  if (!cells || cells.length === 0) return null;
  const alignments: TableAlignment[] = [];
  for (const cell of cells) {
    if (!TABLE_ALIGN_CELL_RE.test(cell)) return null;
    const left = cell.startsWith(':');
    const right = cell.endsWith(':');
    if (left && right) {
      alignments.push('center');
    } else if (right) {
      alignments.push('right');
    } else {
      alignments.push('left');
    }
  }
  return alignments;
}

export const INLINE_IMAGE_MAX_HEIGHT = 440;
export const INLINE_IMAGE_MIN_WIDTH = 64;
export const INLINE_IMAGE_MIN_HEIGHT = 48;
export const INLINE_IMAGE_DEFAULT_ASPECT = 4 / 3;

/** Returns true for image URLs safe and valid to render inline (http(s), relative `/...`, or `data:image/...`). */
export function isRenderableImageUrl(url: string): boolean {
  const trimmed = url.trim();
  if (!trimmed) return false;
  if (/^https?:\/\/[^/\s]+/i.test(trimmed)) return true;
  if (/^\/[^\s]*$/.test(trimmed) && !trimmed.startsWith('//')) return true;
  if (/^data:image\/[a-z0-9.+-]+;base64,/i.test(trimmed)) return true;
  return false;
}

/** Derive a clean display title for an inline image from its Markdown alt text or URL filename. */
export function deriveImageDisplayName(alt: string, url: string): string {
  const cleanAlt = alt.trim();
  if (cleanAlt) return cleanAlt;
  const trimmed = url.trim();
  if (!trimmed || /^data:/i.test(trimmed)) return 'Image';
  const withoutHash = trimmed.split('#')[0] ?? '';
  const withoutQuery = withoutHash.split('?')[0] ?? '';
  const parts = withoutQuery.split('/').filter(Boolean);
  const last = parts[parts.length - 1];
  if (!last) return 'Image';
  try {
    const decoded = decodeURIComponent(last).trim();
    return decoded || 'Image';
  } catch {
    return last;
  }
}

/**
 * Compute natural-aspect-ratio display dimensions for an inline Markdown image:
 * fits within `containerWidth` and clamps height to `maxHeight` (default 440dp)
 * so portrait images (e.g. 9:16 wallpapers) are never cropped.
 */
export function computeInlineImageSize(
  naturalWidth: number,
  naturalHeight: number,
  containerWidth: number,
  maxHeight = INLINE_IMAGE_MAX_HEIGHT,
): { width: number; height: number } {
  const safeContainerW = Number.isFinite(containerWidth) && containerWidth > 0 ? containerWidth : 280;
  const safeMaxH = Number.isFinite(maxHeight) && maxHeight > 0 ? maxHeight : INLINE_IMAGE_MAX_HEIGHT;

  if (
    !Number.isFinite(naturalWidth) ||
    !Number.isFinite(naturalHeight) ||
    naturalWidth <= 0 ||
    naturalHeight <= 0
  ) {
    const placeholderW = Math.min(safeContainerW, 280);
    const placeholderH = Math.min(safeMaxH, Math.round(placeholderW / INLINE_IMAGE_DEFAULT_ASPECT));
    return {
      width: Math.max(INLINE_IMAGE_MIN_WIDTH, Math.round(placeholderW)),
      height: Math.max(INLINE_IMAGE_MIN_HEIGHT, placeholderH),
    };
  }

  const aspect = naturalWidth / naturalHeight;
  let width = safeContainerW;
  let height = width / aspect;

  if (height > safeMaxH) {
    height = safeMaxH;
    width = height * aspect;
  }

  return {
    width: Math.min(safeContainerW, Math.max(INLINE_IMAGE_MIN_WIDTH, Math.round(width))),
    height: Math.min(safeMaxH, Math.max(INLINE_IMAGE_MIN_HEIGHT, Math.round(height))),
  };
}

/** Parse a Markdown parenthesized link/image destination `(url "optional title")` starting at `openParenIdx`. */
function parseMarkdownDestination(
  text: string,
  openParenIdx: number,
): { url: string; endIdx: number } | null {
  if (text[openParenIdx] !== '(') return null;
  let i = openParenIdx + 1;
  while (i < text.length && (text[i] === ' ' || text[i] === '\t')) i += 1;
  if (i >= text.length) return null;

  let url = '';
  if (text[i] === '<') {
    i += 1;
    const start = i;
    while (i < text.length && text[i] !== '>' && text[i] !== '\n') {
      if (text[i] === '\\' && i + 1 < text.length) i += 2;
      else i += 1;
    }
    if (i >= text.length || text[i] !== '>') return null;
    url = text.slice(start, i).trim();
    i += 1;
  } else {
    const start = i;
    let depth = 0;
    while (i < text.length) {
      const ch = text[i];
      if (ch === '\\' && i + 1 < text.length) {
        i += 2;
        continue;
      }
      if (ch === '(') {
        depth += 1;
        i += 1;
        continue;
      }
      if (ch === ')') {
        if (depth === 0) break;
        depth -= 1;
        i += 1;
        continue;
      }
      if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') break;
      i += 1;
    }
    url = text.slice(start, i).trim();
  }

  if (!url) return null;

  while (i < text.length && (text[i] === ' ' || text[i] === '\t')) i += 1;
  if (i < text.length && (text[i] === '"' || text[i] === "'" || text[i] === '(')) {
    const closeCh = text[i] === '(' ? ')' : text[i];
    i += 1;
    while (i < text.length && text[i] !== closeCh && text[i] !== '\n') {
      if (text[i] === '\\' && i + 1 < text.length) i += 2;
      else i += 1;
    }
    if (i >= text.length || text[i] !== closeCh) return null;
    i += 1;
    while (i < text.length && (text[i] === ' ' || text[i] === '\t')) i += 1;
  }

  if (i >= text.length || text[i] !== ')') return null;
  return { url, endIdx: i + 1 };
}

/** Parse `![alt](url)` starting at `bangIdx` (pointing at `!`). */
function parseMarkdownImageAt(
  text: string,
  bangIdx: number,
): { alt: string; url: string; endIdx: number } | null {
  if (text[bangIdx] !== '!' || text[bangIdx + 1] !== '[') return null;
  let i = bangIdx + 2;
  let alt = '';
  let depth = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '\\' && i + 1 < text.length) {
      alt += text[i + 1];
      i += 2;
      continue;
    }
    if (ch === '\n' && text[i + 1] === '\n') return null;
    if (ch === '[') {
      depth += 1;
      alt += ch;
      i += 1;
      continue;
    }
    if (ch === ']') {
      if (depth === 0) break;
      depth -= 1;
      alt += ch;
      i += 1;
      continue;
    }
    alt += ch;
    i += 1;
  }
  if (i >= text.length || text[i] !== ']' || text[i + 1] !== '(') return null;
  const dest = parseMarkdownDestination(text, i + 1);
  if (!dest || !isRenderableImageUrl(dest.url)) return null;
  return { alt: alt.trim(), url: dest.url, endIdx: dest.endIdx };
}

/** Split a non-code, non-table text chunk into interleaved `text` and Markdown `image` segments. */
function splitTextAndImages(text: string): MessageSegment[] {
  if (!text || !text.includes('![')) {
    return text.trim().length > 0 ? [{ kind: 'text', text }] : [];
  }

  const out: MessageSegment[] = [];
  let textStart = 0;

  const flushTextUntil = (endIdx: number) => {
    if (endIdx <= textStart) return;
    const chunk = text.slice(textStart, endIdx).replace(/^\n+|\n+$/g, '');
    if (chunk.trim().length > 0) {
      out.push({ kind: 'text', text: chunk });
    }
  };

  let i = 0;
  while (i < text.length) {
    const ch = text[i];

    // Skip escaped characters so `\![alt](url)` stays literal text.
    if (ch === '\\' && i + 1 < text.length) {
      i += 2;
      continue;
    }

    // Skip inline code spans so `` `![alt](url)` `` stays literal text.
    if (ch === '`') {
      let tickCount = 1;
      while (i + tickCount < text.length && text[i + tickCount] === '`') {
        tickCount += 1;
      }
      const closer = '`'.repeat(tickCount);
      const closeIdx = text.indexOf(closer, i + tickCount);
      if (closeIdx !== -1) {
        i = closeIdx + tickCount;
        continue;
      }
      i += tickCount;
      continue;
    }

    // Linked image: `[![alt](imgUrl)](linkUrl)`
    if (ch === '[' && text[i + 1] === '!' && text[i + 2] === '[') {
      const inner = parseMarkdownImageAt(text, i + 1);
      if (inner && text[inner.endIdx] === ']' && text[inner.endIdx + 1] === '(') {
        const outerDest = parseMarkdownDestination(text, inner.endIdx + 1);
        if (outerDest) {
          flushTextUntil(i);
          const resolvedLink = resolveChatLinkUrl(outerDest.url);
          out.push(
            resolvedLink
              ? { kind: 'image', alt: inner.alt, url: inner.url, linkUrl: resolvedLink }
              : { kind: 'image', alt: inner.alt, url: inner.url },
          );
          i = outerDest.endIdx;
          textStart = i;
          continue;
        }
      }
    }

    // Standalone or inline image: `![alt](imgUrl)`
    if (ch === '!' && text[i + 1] === '[') {
      const parsed = parseMarkdownImageAt(text, i);
      if (parsed) {
        flushTextUntil(i);
        out.push({ kind: 'image', alt: parsed.alt, url: parsed.url });
        i = parsed.endIdx;
        textStart = i;
        continue;
      }
    }

    i += 1;
  }

  flushTextUntil(text.length);
  return out;
}

/** Split a non-code text chunk into interleaved `text`, `image`, and GFM `table` segments. */
function splitTextAndTables(text: string): MessageSegment[] {
  const normalized = text.replace(/\r\n?/g, '\n');
  const lines = normalized.split('\n');
  const out: MessageSegment[] = [];
  const textBuf: string[] = [];

  const flushText = () => {
    const joined = textBuf.join('\n').replace(/^\n+|\n+$/g, '');
    textBuf.length = 0;
    if (joined.trim().length > 0) {
      out.push(...splitTextAndImages(joined));
    }
  };

  let i = 0;
  while (i < lines.length) {
    if (i + 1 < lines.length) {
      const headers = splitTableRow(lines[i]);
      const alignments = headers ? parseTableSeparator(lines[i + 1]) : null;
      if (headers && alignments && headers.length === alignments.length) {
        flushText();
        const colCount = headers.length;
        const rows: string[][] = [];
        let j = i + 2;
        while (j < lines.length) {
          const line = lines[j];
          if (!line.trim()) break;
          const cells = splitTableRow(line);
          if (!cells) break;
          const normalizedRow =
            cells.length === colCount
              ? cells
              : cells.length < colCount
                ? [...cells, ...Array<string>(colCount - cells.length).fill('')]
                : cells.slice(0, colCount);
          rows.push(normalizedRow);
          j += 1;
        }
        out.push({ kind: 'table', headers, alignments, rows });
        i = j;
        continue;
      }
    }
    textBuf.push(lines[i]);
    i += 1;
  }

  flushText();
  return out;
}

/** Split message text into plain text, GFM tables, and fenced code blocks (```lang ... ```). */
export function parseMessageSegments(text: string): MessageSegment[] {
  if (!text) return [];
  const out: MessageSegment[] = [];
  const fence = /```(?:([a-zA-Z0-9_+#.-]+)[ \t]*\r?\n|[ \t]*\r?\n)?([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = fence.exec(text)) !== null) {
    if (match.index > lastIndex) {
      const plain = text.slice(lastIndex, match.index);
      if (plain.trim().length > 0) {
        out.push(...splitTextAndTables(plain.replace(/^(?:\r?\n)+|(?:\r?\n)+$/g, '')));
      }
    }
    const lang = (match[1] || '').trim();
    const code = (match[2] || '').replace(/\r?\n$/, '');
    out.push({ kind: 'codeblock', lang, code });
    lastIndex = fence.lastIndex;
  }
  if (lastIndex < text.length) {
    const tail = text.slice(lastIndex);
    if (tail.trim().length > 0 || out.length === 0) {
      const trimmedTail = tail.replace(/^(?:\r?\n)+|(?:\r?\n)+$/g, '');
      const parsed = splitTextAndTables(trimmedTail);
      if (parsed.length > 0) {
        out.push(...parsed);
      } else if (out.length === 0) {
        out.push({ kind: 'text', text: trimmedTail });
      }
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

/** Human-readable engine label for assistant bubbles and controls. */
export function formatEngineLabel(engine?: string | null): string {
  return (engine || '').trim().toLowerCase() === 'gemini' ? 'Gemini' : 'Claude';
}

/**
 * Show the engine label on every assistant bubble consistently (done, pending, error, pushed, and history).
 */
export function shouldShowEngineTag(
  messages: readonly ChatMessage[],
  index: number,
  _activeEngine?: ChatEngine,
): boolean {
  const msg = messages[index];
  return Boolean(msg && msg.role === 'assistant');
}

export function formatProgressDuration(seconds: number): string {
  const total = Math.max(1, Math.round(Number.isFinite(seconds) ? seconds : 0));
  if (total < 60) return `${total}s`;
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return secs === 0 ? `${mins}m` : `${mins}m ${secs}s`;
}

export function formatProgressSummary(progress?: ChatProgress | null): string | null {
  if (!progress || !Array.isArray(progress.steps) || progress.steps.length === 0) {
    return null;
  }
  const count =
    typeof progress.step_count === 'number' && progress.step_count > 0
      ? progress.step_count
      : progress.steps.length;
  const noun = count === 1 ? 'step' : 'steps';
  if (typeof progress.duration_s === 'number' && Number.isFinite(progress.duration_s) && progress.duration_s > 0) {
    return `Worked for ${formatProgressDuration(progress.duration_s)} · ${count} ${noun}`;
  }
  return `${count} ${noun}`;
}

export function currentProgressStepText(progress?: ChatProgress | null): string {
  if (progress && Array.isArray(progress.steps) && progress.steps.length > 0) {
    const last = progress.steps[progress.steps.length - 1]?.text?.trim();
    if (last) return last;
  }
  return 'Thinking…';
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
  const replyTo = typeof target?.reply_to === 'string' ? target.reply_to.trim() : '';
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

/** Extract the structured quoted reply object from a message (ignoring legacy string reply_to IDs on assistant messages). */
export function getReplyRef(msg: ChatMessage): ChatReplyRef | null {
  const r = msg.reply_to;
  if (!r || typeof r !== 'object') return null;
  if (typeof r.id !== 'string' || !r.id) return null;
  return r;
}

/** Build a compact ChatReplyRef from a message for the composer bar and optimistic bubble. */
export function buildReplyRef(msg: ChatMessage, maxLen = 240): ChatReplyRef {
  let snippet = formatMessagePreview(msg);
  if (!snippet) {
    const atts = msg.attachments ?? [];
    if (atts.length === 1) {
      snippet = atts[0].kind === 'image' ? '[image]' : '[file]';
    } else if (atts.length > 1) {
      snippet = `[${atts.length} attachments]`;
    }
  }
  if (snippet.length > maxLen) {
    snippet = `${snippet.slice(0, maxLen - 1)}…`;
  }
  return {
    id: msg.id,
    role: msg.role,
    engine: msg.engine,
    text: snippet,
  };
}

/** Human-readable sender label for a quoted reply bar or bubble quote block. */
export function replySenderLabel(ref: Pick<ChatReplyRef, 'role' | 'engine'>): string {
  if (ref.role === 'user') return 'You';
  if (ref.role === 'system') return 'System';
  return ref.engine === 'gemini' ? 'Gemini' : 'Claude';
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

export const SWIPE_REPLY_ACTIVATION_DX = 10;
export const SWIPE_REPLY_DIRECTION_RATIO = 1.5;
export const SWIPE_REPLY_THRESHOLD = 60;
export const SWIPE_REPLY_RESET_THRESHOLD = 48;
export const SWIPE_REPLY_MAX_DRAG = 84;

/** Capture only deliberate horizontal rightward drags so vertical scroll, leftward drags, and taps stay untouched. */
export function shouldCaptureSwipeReply(dx: number, dy: number): boolean {
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return false;
  return dx > SWIPE_REPLY_ACTIVATION_DX && dx > Math.abs(dy) * SWIPE_REPLY_DIRECTION_RATIO;
}

/**
 * Maps raw horizontal rightward drag distance (dx) to damped visual bubble offset:
 * - 0 for leftward/zero drags
 * - 1:1 tracking up to SWIPE_REPLY_THRESHOLD
 * - Smooth exponential rubber-band resistance asymptotically approaching SWIPE_REPLY_MAX_DRAG
 */
export function computeSwipeReplyOffset(dx: number): number {
  if (!Number.isFinite(dx) || dx <= 0) return 0;
  if (dx <= SWIPE_REPLY_THRESHOLD) return dx;
  const excess = dx - SWIPE_REPLY_THRESHOLD;
  const headroom = SWIPE_REPLY_MAX_DRAG - SWIPE_REPLY_THRESHOLD;
  return SWIPE_REPLY_THRESHOLD + headroom * (1 - Math.exp(-excess / 48));
}

/** Returns true only for replyable user/assistant messages (excludes system banners, pending, and optimistic placeholders). */
export function isReplyableMessage(msg: ChatMessage): boolean {
  if (msg.role === 'system') return false;
  if (msg.status === 'pending' || msg.optimistic) return false;
  return (
    msg.role === 'user' ||
    msg.status === 'error' ||
    msg.text.trim().length > 0 ||
    (msg.attachments?.length ?? 0) > 0
  );
}

export type TextSelection = {
  start: number;
  end: number;
};

export type ChatListAutoFormatResult = {
  text: string;
  selection: TextSelection;
  changed: boolean;
};

const ORDERED_LIST_RE = /^([ \t]*)(\d+)([.)]) (.*)$/;
const BULLET_LIST_RE = /^([ \t]*)([-*•]) (.*)$/;

function findSingleNewlineInsertion(
  prevText: string,
  nextText: string,
  prevSelection?: TextSelection | null,
): { insertPos: number; afterPos: number } | null {
  if (prevSelection) {
    const start = Math.max(0, Math.min(prevSelection.start, prevSelection.end, prevText.length));
    const end = Math.max(0, Math.min(Math.max(prevSelection.start, prevSelection.end), prevText.length));
    if (
      nextText.length === prevText.length - (end - start) + 1 &&
      nextText === `${prevText.slice(0, start)}\n${prevText.slice(end)}`
    ) {
      return { insertPos: start, afterPos: end };
    }
  }

  // Fallback when prevSelection is absent or slightly stale (common on Android IME):
  // detect a pure single-character '\n' insertion by comparing prefix/suffix.
  if (nextText.length !== prevText.length + 1) return null;

  let prefixLen = 0;
  while (prefixLen < prevText.length && prevText[prefixLen] === nextText[prefixLen]) {
    prefixLen += 1;
  }

  let suffixLen = 0;
  const maxSuffix = prevText.length - prefixLen;
  while (
    suffixLen < maxSuffix &&
    prevText[prevText.length - 1 - suffixLen] === nextText[nextText.length - 1 - suffixLen]
  ) {
    suffixLen += 1;
  }

  if (prefixLen + suffixLen !== prevText.length) return null;
  if (nextText[prefixLen] !== '\n') return null;

  return { insertPos: prefixLen, afterPos: prefixLen };
}

/**
 * WhatsApp-style smart list continuation & double-Enter disengage for the chat composer.
 * Triggers ONLY when a single newline ('\n') is inserted (never on paste, delete, or arbitrary edits).
 */
export function applyChatListAutoFormat(
  prevText: string,
  nextText: string,
  prevSelection?: TextSelection | null,
): ChatListAutoFormatResult {
  const unchangedCursor = nextText.length;
  const insertion = findSingleNewlineInsertion(prevText, nextText, prevSelection);
  if (!insertion) {
    return {
      text: nextText,
      selection: { start: unchangedCursor, end: unchangedCursor },
      changed: false,
    };
  }

  const { insertPos, afterPos } = insertion;
  const lineStart = insertPos === 0 ? 0 : prevText.lastIndexOf('\n', insertPos - 1) + 1;
  const nextNewline = prevText.indexOf('\n', afterPos);
  const lineEnd = nextNewline === -1 ? prevText.length : nextNewline;

  const lineBeforeCursor = prevText.slice(lineStart, insertPos);
  const lineAfterCursor = prevText.slice(afterPos, lineEnd);

  let nextMarker: string | null = null;
  let contentBefore = '';

  const orderedMatch = ORDERED_LIST_RE.exec(lineBeforeCursor);
  if (orderedMatch) {
    const [, indent, numStr, delim, content] = orderedMatch;
    const num = Number.parseInt(numStr, 10);
    if (Number.isSafeInteger(num) && numStr.length <= 9) {
      nextMarker = `${indent}${num + 1}${delim} `;
      contentBefore = content;
    }
  } else {
    const bulletMatch = BULLET_LIST_RE.exec(lineBeforeCursor);
    if (bulletMatch) {
      const [, indent, bullet, content] = bulletMatch;
      nextMarker = `${indent}${bullet} `;
      contentBefore = content;
    }
  }

  if (!nextMarker) {
    const cursor = insertPos + 1;
    return {
      text: nextText,
      selection: { start: cursor, end: cursor },
      changed: false,
    };
  }

  // Double-Enter disengage: line only contains the list marker (and optional whitespace).
  if (contentBefore.trim().length === 0 && lineAfterCursor.trim().length === 0) {
    const beforeLine = prevText.slice(0, lineStart);
    const afterLine = prevText.slice(lineEnd);
    const formatted = beforeLine + afterLine;
    const cursor = beforeLine.length;
    return {
      text: formatted,
      selection: { start: cursor, end: cursor },
      changed: true,
    };
  }

  // Continue list on the next line.
  const isMidLineSplit = contentBefore.trim().length > 0 && lineAfterCursor.trim().length > 0;
  const rawPrefix = prevText.slice(0, insertPos);
  const prefix = isMidLineSplit ? rawPrefix.replace(/[ \t]+$/, '') : rawPrefix;
  const suffix = prevText.slice(afterPos).replace(/^[ \t]+/, '');
  const inserted = `\n${nextMarker}`;
  const formatted = prefix + inserted + suffix;
  const cursor = prefix.length + inserted.length;

  return {
    text: formatted,
    selection: { start: cursor, end: cursor },
    changed: true,
  };
}
