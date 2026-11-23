// Persistent on-phone JSON cache backed by react-native-mmkv (synchronous, Nitro-based),
// with an in-memory layer so repeated reads avoid re-parsing and a safe fallback when native
// storage is unavailable (e.g. Node unit tests or storage errors). All operations are wrapped
// in try/catch and versioned so schema changes cleanly invalidate stale entries.

export const CACHE_VERSION = 1;

export type CacheBackend = {
  getString(key: string): string | undefined;
  set(key: string, value: string): void;
  remove(key: string): void;
  clearAll(): void;
};

type Envelope<T> = {
  v: number;
  ts: number;
  data: T;
};

const mem = new Map<string, unknown>();
let backend: CacheBackend | null | undefined;
let testBackend: CacheBackend | null | undefined;

function getBackend(): CacheBackend | null {
  if (testBackend !== undefined) return testBackend;
  if (backend !== undefined) return backend;
  backend = null;
  try {
    if (typeof require === 'function') {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const mmkv = require('react-native-mmkv') as {
        createMMKV?: (cfg?: { id?: string }) => CacheBackend;
      };
      if (typeof mmkv?.createMMKV === 'function') {
        backend = mmkv.createMMKV({ id: 'pc-control-cache' });
      }
    }
  } catch {
    backend = null;
  }
  return backend;
}

function storageKey(key: string): string {
  return `v${CACHE_VERSION}:${key}`;
}

export function cacheGet<T>(key: string): T | null {
  try {
    if (mem.has(key)) return mem.get(key) as T;
    const store = getBackend();
    if (!store) return null;
    const raw = store.getString(storageKey(key));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Envelope<T>> | null;
    if (!parsed || typeof parsed !== 'object' || parsed.v !== CACHE_VERSION || !('data' in parsed)) {
      try {
        store.remove(storageKey(key));
      } catch {
        // ignore cleanup failure
      }
      return null;
    }
    mem.set(key, parsed.data as T);
    return parsed.data as T;
  } catch {
    return null;
  }
}

export function cacheSet<T>(key: string, value: T): void {
  try {
    mem.set(key, value);
    const store = getBackend();
    if (!store) return;
    const env: Envelope<T> = { v: CACHE_VERSION, ts: Date.now(), data: value };
    store.set(storageKey(key), JSON.stringify(env));
  } catch {
    // ignore storage errors
  }
}

export function cacheRemove(key: string): void {
  try {
    mem.delete(key);
    getBackend()?.remove(storageKey(key));
  } catch {
    // ignore storage errors
  }
}

export function cacheClear(): void {
  try {
    mem.clear();
    getBackend()?.clearAll();
  } catch {
    // ignore storage errors
  }
}

/** Test-only hook to inject a mock storage backend and reset the in-memory layer. */
export function _setCacheBackendForTests(b: CacheBackend | null | undefined): void {
  mem.clear();
  testBackend = b;
}

/** Test-only hook to clear only the in-memory layer so the next read hits the persistent backend. */
export function _clearMemCacheForTests(): void {
  mem.clear();
}
