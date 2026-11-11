import { PcNative } from '../../modules/pc-native';

export const DEFAULT_SERVER = 'https://ayushlive-hp-pavilion.tail65e4a4.ts.net';

export function serverUrl(): string {
  try {
    return (PcNative?.getSettings().serverUrl || DEFAULT_SERVER).replace(/\/+$/, '');
  } catch {
    return DEFAULT_SERVER;
  }
}

export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
  timeoutMs = 12000,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(serverUrl() + path, {
      method,
      headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...extraHeaders },
      body: body ? JSON.stringify(body) : undefined,
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
    if ((e as Error)?.name === 'AbortError') throw new ApiError(0, 'Timed out. Is Tailscale connected?');
    throw new ApiError(0, `Can't reach the laptop. Is Tailscale connected? (${(e as Error)?.message ?? e})`);
  } finally {
    clearTimeout(timer);
  }
}

export const get = <T>(path: string, timeoutMs?: number) => request<T>('GET', path, undefined, timeoutMs);
export const post = <T>(path: string, body: unknown, timeoutMs?: number) => request<T>('POST', path, body, timeoutMs);
export const put = <T>(path: string, body: unknown, timeoutMs?: number) => request<T>('PUT', path, body, timeoutMs);
export const del = <T>(path: string, timeoutMs?: number) => request<T>('DELETE', path, undefined, timeoutMs);

// ---------------------------------------------------------------- types

export type RootdState = 'ok' | 'down' | 'not_installed' | 'unknown';

export type RootRequest = {
  id: string;
  argv: string[];
  reason: string;
  nonce: string;
  created: number;
  expires: number;
  status: 'pending' | 'running' | 'done' | 'denied' | 'expired' | 'cancelled' | 'lost';
  timeout: number;
  uid: number;
  argv_sha256: string;
  exit?: number;
  device?: string;
  decided?: number;
  started?: number;
  finished?: number;
  timed_out?: boolean;
  stdout?: string;
  stderr?: string;
  out_bytes?: number;
  err_bytes?: number;
};

export type Info = {
  host: string;
  version: number;
  rootd: { state: RootdState; version?: number; devices?: number };
  apk: { versionCode: number; versionName: string; sha256: string; size: number; built: number } | null;
  push?: { configured: boolean; devices: number };
};

export type Route = {
  id: string;
  name: string;
  icon: string;
  subtitle: string;
  url: string;
  path: string;
  port: number;
  target: string;
  kind?: 'page' | 'api' | 'app';
  hidden?: boolean;
  /** Android package to launch instead of opening url (url is the fallback when it isn't installed). */
  app?: string;
};

export type Service = {
  unit: string;
  description: string;
  active: string;
  sub: string;
  enabled: string;
  since: string;
  pid: number;
  memory: number | null;
  restarts: number | null;
  type: string;
  result: string;
  timer: boolean;
};

export type Proc = { pid: number; name: string; user: string; cpu: number; rss: number; cmd: string; gpu?: number | null };

export type Vitals = {
  t: number;
  cpu: number;
  percpu: number[];
  freq: number;
  load: number[];
  mem: { used: number; total: number; pct: number };
  swap: { used: number; total: number; pct: number };
  temps: Record<string, number>;
  gpu: { name: string; util: number; mem_used: number; mem_total: number; temp: number; power: number } | null;
  nics: Record<string, { rx: number; tx: number }>;
  disk_io: { r: number; w: number };
  disks: { mount: string; used: number; total: number; pct: number }[];
  bat: { pct: number; plugged: boolean } | null;
  uptime: number;
  procs: Proc[] | null;
  hist: { t: number; cpu: number; mem: number; temp: number; gpu: number; rx: number; tx: number }[];
};
