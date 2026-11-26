import { PcNative } from '../../modules/pc-native';
import {
  AgentEngine,
  AgentSettings,
  AgentSettingsPatch,
  AgentState,
  ApiError,
  serverUrl,
} from './api';
import { cacheGet, cacheSet } from './cache';
import {
  attachmentImageCacheKey,
  buildAttachmentDataUri,
  buildChatAttachmentSource,
  ChatAttachment,
  chatAttachmentRequiresFetch,
  ChatMessagesResponse,
  ChatNewResponse,
  ChatSendResponse,
  ChatSseEvent,
  ChatSteerResponse,
  getChatEndpoint,
  LinkPreview,
  parseChatSseBlocks,
  resolveChatTarget,
} from './chatLogic';

export function syncNativeChatConfig(): void {
  try {
    const endpoint = getChatEndpoint();
    PcNative?.setChatConfig?.(endpoint.url, endpoint.url ? endpoint.token : '');
  } catch {
    // Ignore on older APKs without setChatConfig.
  }
}

async function chatRequest<T>(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  timeoutMs = 15000,
): Promise<T> {
  const target = resolveChatTarget(serverUrl(), getChatEndpoint());
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(target.baseUrl + path, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...target.headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: ctrl.signal,
    });
    const text = await res.text();
    let data: unknown = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    if (!res.ok) {
      const msg = (data as { error?: string } | null)?.error || `HTTP ${res.status}`;
      throw new ApiError(res.status, msg, data);
    }
    return data as T;
  } catch (e) {
    if (e instanceof ApiError) throw e;
    if ((e as Error)?.name === 'AbortError') {
      throw new ApiError(0, target.custom ? 'Chat endpoint timed out.' : 'Timed out. Is Tailscale connected?');
    }
    throw new ApiError(
      0,
      target.custom
        ? `Can't reach chat endpoint (${(e as Error)?.message ?? e})`
        : `Can't reach the laptop. Is Tailscale connected? (${(e as Error)?.message ?? e})`,
    );
  } finally {
    clearTimeout(timer);
  }
}

export function fetchChatMessages(before?: string, limit = 50): Promise<ChatMessagesResponse> {
  const params = new URLSearchParams();
  params.set('limit', String(limit));
  if (before) params.set('before', before);
  return chatRequest<ChatMessagesResponse>('GET', `/pc/api/chat/messages?${params.toString()}`);
}

export function sendChatMessage(
  text: string,
  clientId: string,
  attachments?: ChatAttachment[],
  replyToId?: string,
): Promise<ChatSendResponse> {
  const payload: Record<string, unknown> = { text, client_id: clientId };
  if (attachments && attachments.length > 0) {
    payload.attachments = attachments;
  }
  if (replyToId) {
    payload.reply_to_id = replyToId;
  }
  return chatRequest<ChatSendResponse>('POST', '/pc/api/chat/send', payload);
}

export function steerChatMessage(messageId: string): Promise<ChatSteerResponse> {
  return chatRequest<ChatSteerResponse>('POST', '/pc/api/chat/steer', { message_id: messageId });
}

/**
 * Subscribe to live chat SSE updates (`/pc/api/chat/events`), supporting both Tailscale
 * and custom chat-token endpoints. Uses XHR `onprogress` streaming (matching `events.ts`).
 */
export function subscribeChatEvents(
  onEvent: (ev: ChatSseEvent) => void,
  onState?: (connected: boolean) => void,
): () => void {
  let stopped = false;
  let xhr: XMLHttpRequest | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let watchdog: ReturnType<typeof setInterval> | null = null;
  let retryDelay = 1500;
  let seen = 0;
  let buf = '';
  let lastData = 0;
  let connected = false;

  const setConnected = (next: boolean) => {
    if (next !== connected) {
      connected = next;
      onState?.(next);
    }
  };

  const closeXhr = () => {
    const cur = xhr;
    xhr = null;
    if (cur) {
      cur.onprogress = cur.onerror = cur.onload = cur.onreadystatechange = null;
      try {
        cur.abort();
      } catch {}
    }
  };

  const scheduleReconnect = (delay: number) => {
    closeXhr();
    setConnected(false);
    if (stopped) return;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(connect, delay);
  };

  const connect = () => {
    if (stopped) return;
    retryTimer = null;
    seen = 0;
    buf = '';
    lastData = Date.now();
    const target = resolveChatTarget(serverUrl(), getChatEndpoint());
    const x = new XMLHttpRequest();
    xhr = x;
    x.open('GET', target.baseUrl + '/pc/api/chat/events');
    x.setRequestHeader('Accept', 'text/event-stream');
    x.setRequestHeader('Cache-Control', 'no-cache');
    for (const [k, v] of Object.entries(target.headers)) {
      x.setRequestHeader(k, v);
    }

    const consume = () => {
      if (xhr !== x) return;
      const text = x.responseText || '';
      if (text.length <= seen) return;
      lastData = Date.now();
      buf += text.slice(seen);
      seen = text.length;
      const { events, rest } = parseChatSseBlocks(buf);
      buf = rest;
      for (const ev of events) {
        retryDelay = 1500;
        setConnected(true);
        onEvent(ev);
      }
    };

    const fail = () => {
      if (xhr !== x) return;
      const d = retryDelay;
      retryDelay = Math.min(retryDelay * 2, 15000);
      scheduleReconnect(d);
    };

    x.onprogress = consume;
    x.onreadystatechange = () => {
      if (x.readyState === 3 || x.readyState === 4) consume();
    };
    x.onerror = fail;
    x.onload = fail;
    x.send();
  };

  connect();
  watchdog = setInterval(() => {
    if (xhr && (Date.now() - lastData > 65000 || seen > 2_000_000)) {
      scheduleReconnect(0);
    }
  }, 10000);

  return () => {
    stopped = true;
    if (retryTimer) clearTimeout(retryTimer);
    if (watchdog) clearInterval(watchdog);
    retryTimer = null;
    watchdog = null;
    closeXhr();
    setConnected(false);
  };
}

export function uploadChatAttachment(file: {
  name: string;
  mimeType: string;
  base64: string;
}): Promise<ChatAttachment> {
  return chatRequest<ChatAttachment>(
    'POST',
    '/pc/api/chat/upload',
    {
      name: file.name,
      mime_type: file.mimeType,
      data_base64: file.base64,
    },
    60000,
  );
}

export function resolveChatAttachmentSource(url: string): { uri: string; headers?: Record<string, string> } {
  return buildChatAttachmentSource(url, serverUrl(), getChatEndpoint());
}

const ATTACHMENT_IMAGE_CACHE_MAX_ENTRIES = 40;
const ATTACHMENT_IMAGE_CACHE_MAX_BYTES = 24 * 1024 * 1024;
const ATTACHMENT_IMAGE_TIMEOUT_MS = 20000;

const attachmentImageCache = new Map<string, string>();
let attachmentImageCacheBytes = 0;
const attachmentImageInFlight = new Map<string, Promise<string>>();

function putAttachmentImageCache(key: string, dataUri: string): void {
  const existing = attachmentImageCache.get(key);
  if (existing !== undefined) {
    attachmentImageCacheBytes -= existing.length;
    attachmentImageCache.delete(key);
  }
  attachmentImageCache.set(key, dataUri);
  attachmentImageCacheBytes += dataUri.length;
  while (
    attachmentImageCache.size > 1 &&
    (attachmentImageCache.size > ATTACHMENT_IMAGE_CACHE_MAX_ENTRIES ||
      attachmentImageCacheBytes > ATTACHMENT_IMAGE_CACHE_MAX_BYTES)
  ) {
    const oldest = attachmentImageCache.entries().next().value;
    if (!oldest) break;
    attachmentImageCache.delete(oldest[0]);
    attachmentImageCacheBytes -= oldest[1].length;
  }
}

function readBlobAsDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string' && reader.result.length > 0) {
        resolve(reader.result);
      } else {
        reject(new Error('Empty image data'));
      }
    };
    reader.onerror = () => {
      reject(reader.error ?? new Error('Failed to decode image blob'));
    };
    reader.readAsDataURL(blob);
  });
}

/** Synchronous lookup in the in-memory authenticated image cache (returns `data:` URI or null). */
export function getCachedChatAttachmentImage(url: string): string | null {
  const source = resolveChatAttachmentSource(url);
  const key = attachmentImageCacheKey(source);
  const hit = attachmentImageCache.get(key);
  if (hit === undefined) return null;
  // Refresh LRU recency.
  attachmentImageCache.delete(key);
  attachmentImageCache.set(key, hit);
  return hit;
}

/** Seed the image cache immediately after uploading a local image so preview renders with no extra fetch. */
export function primeChatAttachmentImageCache(url: string, base64: string, mimeType?: string): void {
  if (!url || !base64) return;
  const source = resolveChatAttachmentSource(url);
  const key = attachmentImageCacheKey(source);
  putAttachmentImageCache(key, buildAttachmentDataUri(base64, mimeType));
}

/**
 * Load a chat image attachment for `<Image>`.
 * When the endpoint requires auth headers (off-Tailscale Bearer token), fetches the bytes via JS
 * `fetch` and converts them to a `data:` URI, working around RN 0.86 Android Fabric dropping
 * `source.headers` on `<Image>`.
 */
export function loadChatAttachmentImage(url: string, mimeHint?: string): Promise<string> {
  const source = resolveChatAttachmentSource(url);
  if (!chatAttachmentRequiresFetch(source)) {
    return Promise.resolve(source.uri);
  }
  const key = attachmentImageCacheKey(source);
  const cached = attachmentImageCache.get(key);
  if (cached !== undefined) {
    attachmentImageCache.delete(key);
    attachmentImageCache.set(key, cached);
    return Promise.resolve(cached);
  }
  const existing = attachmentImageInFlight.get(key);
  if (existing) return existing;

  const task = (async () => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), ATTACHMENT_IMAGE_TIMEOUT_MS);
    try {
      const res = await fetch(source.uri, {
        method: 'GET',
        headers: {
          Accept: 'image/*,*/*;q=0.8',
          ...source.headers,
        },
        signal: ctrl.signal,
      });
      if (!res.ok) {
        throw new ApiError(res.status, `HTTP ${res.status}`);
      }
      const contentType = res.headers.get('content-type') || mimeHint || undefined;
      const blob = await res.blob();
      try {
        const rawDataUrl = await readBlobAsDataUrl(blob);
        const dataUri = buildAttachmentDataUri(rawDataUrl, contentType);
        putAttachmentImageCache(key, dataUri);
        return dataUri;
      } finally {
        (blob as unknown as { close?: () => void }).close?.();
      }
    } finally {
      clearTimeout(timer);
    }
  })();

  attachmentImageInFlight.set(key, task);
  void task.finally(() => {
    if (attachmentImageInFlight.get(key) === task) {
      attachmentImageInFlight.delete(key);
    }
  });
  return task;
}

export function startNewChat(): Promise<ChatNewResponse> {
  return chatRequest<ChatNewResponse>('POST', '/pc/api/chat/new', {});
}

export function getChatAgentSettings(): Promise<AgentSettings> {
  return chatRequest<AgentSettings>('GET', '/pc/api/chat/agent');
}

export function updateChatAgentSettings(patch: AgentSettingsPatch): Promise<AgentSettings> {
  return chatRequest<AgentSettings>('POST', '/pc/api/chat/agent', patch);
}

export function switchChatAgent(engine: AgentEngine): Promise<AgentState> {
  return chatRequest<AgentState>('POST', '/pc/api/chat/agent/switch', { engine });
}

type LinkPreviewCacheEntry = {
  preview: LinkPreview | null;
  expiresAt: number;
};

type LinkPreviewResponse = {
  ok: boolean;
  preview: LinkPreview | null;
};

const LINK_PREVIEW_CACHE_KEY = 'chat:link-previews';
const LINK_PREVIEW_MAX_ENTRIES = 128;
const LINK_PREVIEW_HIT_TTL_MS = 6 * 60 * 60 * 1000;
const LINK_PREVIEW_MISS_TTL_MS = 10 * 60 * 1000;

let previewMemCache: Map<string, LinkPreviewCacheEntry> | null = null;
const previewInFlight = new Map<string, Promise<LinkPreview | null>>();

function getPreviewStore(): Map<string, LinkPreviewCacheEntry> {
  if (previewMemCache) return previewMemCache;
  previewMemCache = new Map<string, LinkPreviewCacheEntry>();
  const stored = cacheGet<Record<string, LinkPreviewCacheEntry>>(LINK_PREVIEW_CACHE_KEY);
  if (stored && typeof stored === 'object') {
    const now = Date.now();
    for (const [k, v] of Object.entries(stored)) {
      if (v && typeof v === 'object' && typeof v.expiresAt === 'number' && v.expiresAt > now) {
        previewMemCache.set(k, v);
      }
    }
  }
  return previewMemCache;
}

function persistPreviewStore(store: Map<string, LinkPreviewCacheEntry>): void {
  while (store.size > LINK_PREVIEW_MAX_ENTRIES) {
    const oldest = store.keys().next().value;
    if (oldest === undefined) break;
    store.delete(oldest);
  }
  const obj: Record<string, LinkPreviewCacheEntry> = {};
  for (const [k, v] of store.entries()) {
    obj[k] = v;
  }
  cacheSet(LINK_PREVIEW_CACHE_KEY, obj);
}

/**
 * Synchronous lookup from the in-memory + MMKV link preview cache.
 * Returns `undefined` on cache miss, `null` on cached negative result, or the `LinkPreview`.
 */
export function getCachedLinkPreview(url: string): LinkPreview | null | undefined {
  const key = url.trim();
  if (!key) return null;
  const store = getPreviewStore();
  const entry = store.get(key);
  if (!entry) return undefined;
  if (Date.now() >= entry.expiresAt) {
    store.delete(key);
    return undefined;
  }
  return entry.preview;
}

export async function fetchLinkPreview(url: string): Promise<LinkPreview | null> {
  const key = url.trim();
  if (!key) return null;

  const cached = getCachedLinkPreview(key);
  if (cached !== undefined) return cached;

  const existing = previewInFlight.get(key);
  if (existing) return existing;

  const promise = (async (): Promise<LinkPreview | null> => {
    try {
      const params = new URLSearchParams();
      params.set('url', key);
      const res = await chatRequest<LinkPreviewResponse>(
        'GET',
        `/pc/api/chat/link-preview?${params.toString()}`,
        undefined,
        8000,
      );
      const preview = res?.preview ?? null;
      const store = getPreviewStore();
      store.set(key, {
        preview,
        expiresAt: Date.now() + (preview ? LINK_PREVIEW_HIT_TTL_MS : LINK_PREVIEW_MISS_TTL_MS),
      });
      persistPreviewStore(store);
      return preview;
    } catch {
      return null;
    } finally {
      previewInFlight.delete(key);
    }
  })();

  previewInFlight.set(key, promise);
  return promise;
}
