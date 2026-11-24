import { PcNative } from '../../modules/pc-native';
import { ApiError, serverUrl } from './api';
import {
  ChatAttachment,
  ChatMessagesResponse,
  ChatNewResponse,
  ChatSendResponse,
  getChatEndpoint,
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
): Promise<ChatSendResponse> {
  const payload: Record<string, unknown> = { text, client_id: clientId };
  if (attachments && attachments.length > 0) {
    payload.attachments = attachments;
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
