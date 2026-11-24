import { PcNative } from '../../modules/pc-native';
import { ApiError, serverUrl } from './api';
import { cacheGet, cacheSet } from './cache';
import {
  ChatAttachment,
  ChatMessagesResponse,
  ChatNewResponse,
  ChatSendResponse,
  getChatEndpoint,
  LinkPreview,
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
  const target = resolveChatTarget(serverUrl(), getChatEndpoint());
  const uri = /^https?:\/\//i.test(url) ? url : `${target.baseUrl}${url.startsWith('/') ? url : `/${url}`}`;
  const hasHeaders = Object.keys(target.headers).length > 0;
  return hasHeaders ? { uri, headers: target.headers } : { uri };
}

export function startNewChat(): Promise<ChatNewResponse> {
  return chatRequest<ChatNewResponse>('POST', '/pc/api/chat/new', {});
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
