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
  snap_sha256?: string;
  output_expired?: boolean;
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

export type Proc = {
  pid: number;
  name: string;
  user: string;
  cpu: number;
  rss: number;
  cmd: string;
  thr?: number;
  status?: string;
  gpu?: number | null;
};

export type Vitals = {
  t: number;
  cpu: number;
  percpu: number[];
  freq: number | null;
  load: number[];
  mem: { used: number; total: number; pct: number };
  swap: { used: number; total: number; pct: number };
  temps: Record<string, number | null>;
  gpu: { name: string; util: number; mem_used: number; mem_total: number; temp: number; power: number } | null;
  nics: Record<string, { rx: number; tx: number }>;
  disk_io: { r: number; w: number };
  disks: { mount: string; used: number; total: number; pct: number }[];
  bat: { pct: number; plugged: boolean } | null;
  uptime: number;
  procs: Proc[] | null;
  hist: { t: number; cpu: number; mem: number; temp: number | null; gpu: number | null; rx: number; tx: number }[];
};

export type HealthProblem = {
  kind: 'service' | 'source' | 'downloads' | 'stalled' | 'arr' | 'disk' | string;
  severity: 'bad' | 'warn';
  title: string;
  detail?: string;
  unit?: string;
};

export type HealthDownload = {
  id: string;
  name: string;
  torrent: string;
  category: string;
  state: 'downloading' | 'stalled' | 'metadata' | 'queued' | 'checking' | 'moving';
  progress: number;
  speed: number;
  upspeed?: number;
  eta: number | null;
  size: number;
  done: number;
};

export type ArrQueue = {
  available: boolean;
  error?: string;
  queue?: number;
  needs_action?: number;
  items?: { title: string; state: string; reason: string }[];
};

export type JellyfinStream = {
  user: string;
  title: string;
  subtitle?: string;
  method: 'direct' | 'direct_stream' | 'transcode' | string;
  paused?: boolean;
  device?: string;
  progress?: number | null;
};

export type HealthDisk = { mount: string; kind: string; used: number; total: number; pct: number };

export type Health = {
  ts: number;
  problems: HealthProblem[];
  services: {
    available: boolean;
    error?: string;
    units?: Service[];
    counts?: { running: number; failed: number; inactive: number; other: number };
  };
  downloads: {
    available: boolean;
    error?: string;
    qbt?: 'ok' | 'down' | 'auth';
    total?: { count: number; downloading: number; progress: number | null; speed: number; upspeed: number; eta: number | null; size: number; done: number };
    items?: HealthDownload[];
    stalled?: number;
    stalled_items?: string[];
  };
  radarr: ArrQueue;
  sonarr: ArrQueue;
  jellyfin: { available: boolean; error?: string; streams?: JellyfinStream[] };
  disks: { available: boolean; error?: string; disks?: HealthDisk[] };
  vpn?: VpnState & { available?: boolean; error?: string };
  agent?: AgentState & { error?: string };
};

export type VpnState = {
  enabled: boolean;
  tunnel_up: boolean;
  forwarded_port: number | null;
  qbt_interface: string;
  public_ip: string | null;
  last_error: string | null;
};

export type AgentEngine = 'claude' | 'gemini';

export type AgentOption = {
  id: AgentEngine;
  label: string;
  model: string;
};

export type AgentState = {
  engine: AgentEngine;
  claude_active: boolean;
  gemini_active: boolean;
  available: boolean;
  switching: boolean;
  options: AgentOption[];
};

export const getAgent = () => get<AgentState>('/pc/api/agent');
export const setAgent = (engine: AgentEngine) => post<AgentState>('/pc/api/agent', { engine });

export type AgentUsageWindow = {
  remaining_pct: number | null;
  used_pct: number | null;
  refreshes_in?: string | null;
  resets_at?: string | null;
  status?: string | null;
};

export type AgentUsageDeepSeek = {
  is_available?: boolean;
  currency?: string;
  total_balance?: number;
  formatted?: string;
  error?: string;
};

export type AgentUsageZenModel = {
  model: string;
  messages: number;
  cost?: string | null;
};

export type AgentUsageZen = {
  status: string;
  days?: number;
  sessions?: number;
  messages?: number;
  total_cost?: string;
  zen_messages?: number;
  models?: AgentUsageZenModel[];
  error?: string;
};

export type AgentUsageAccount = {
  id: string;
  harness: AgentEngine | 'opencode';
  label: string;
  email: string | null;
  signed_in: boolean;
  status: string | null;
  error: string | null;
  five_hour: AgentUsageWindow | null;
  weekly: AgentUsageWindow | null;
  deepseek?: AgentUsageDeepSeek | null;
  zen?: AgentUsageZen | null;
};

export type AgentUsageResponse = {
  ts: number;
  cached: boolean;
  stale?: boolean;
  accounts: AgentUsageAccount[];
};

export type AgentModelOption = {
  id: string;
  label: string;
};

export type GeminiInstanceOption = {
  id: string;
  label: string;
  signed_in: boolean;
  email: string | null;
};

export type UsageAlertsConfig = {
  enabled: boolean;
  five_hour_pct: number;
  weekly_pct: number;
};

export type AgentSettings = {
  claude: {
    account: string | null;
    model: string;
    effort: string;
    models: AgentModelOption[];
    efforts: string[];
  };
  gemini: {
    instance_id: string;
    model: string;
    thought_level: string;
    auto_rotate: boolean;
    instances: GeminiInstanceOption[];
    models: AgentModelOption[];
    efforts: string[];
  };
  usage_alerts?: UsageAlertsConfig;
};

export type AgentSettingsPatch = {
  claude?: { model?: string; effort?: string };
  gemini?: { instance_id?: string; model?: string; thought_level?: string; auto_rotate?: boolean };
  usage_alerts?: Partial<UsageAlertsConfig>;
};

export const getAgentUsage = (refresh = false) =>
  get<AgentUsageResponse>(refresh ? '/pc/api/agent/usage?refresh=1' : '/pc/api/agent/usage', 50000);
export const getAgentSettings = () => get<AgentSettings>('/pc/api/agent/settings');
export const updateAgentSettings = (patch: AgentSettingsPatch) =>
  post<AgentSettings>('/pc/api/agent/settings', patch);
export const resetAgent = () => post<{ ok: boolean; message: string }>('/pc/api/agent/reset', {});

export type CatCamState = {
  enabled: boolean;
  units: Record<string, string>;
};

export const getCatCam = () => get<CatCamState>('/pc/api/catcam');
export const setCatCam = (enabled: boolean) => post<CatCamState>('/pc/api/catcam', { enabled }, 30000);

export type KeyringStatus = {
  locked: boolean;
  rdp: string;
};

export type KeyringUnlockResult = {
  ok: boolean;
  locked: boolean;
  rdp: string;
  error?: string;
};

export const getKeyring = () => get<KeyringStatus>('/pc/api/keyring');
export const unlockKeyringApi = (password: string) =>
  post<KeyringUnlockResult>('/pc/api/keyring/unlock', { password }, 35000);
