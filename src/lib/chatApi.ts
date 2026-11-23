import { ApiError, serverUrl } from './api';
import {
  ChatMessagesResponse,
  ChatNewResponse,
  ChatSendResponse,
  getChatEndpoint,
  resolveChatTarget,
} from './chatLogic';

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

export function sendChatMessage(text: string, clientId: string): Promise<ChatSendResponse> {
  return chatRequest<ChatSendResponse>('POST', '/pc/api/chat/send', { text, client_id: clientId });
}

export function startNewChat(): Promise<ChatNewResponse> {
  return chatRequest<ChatNewResponse>('POST', '/pc/api/chat/new', {});
}
