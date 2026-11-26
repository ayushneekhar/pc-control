// Shared reactive store for the Cat cam master switch, backed by persistent MMKV cache so
// Home, Services, and Settings reflect the switch immediately across restarts and tabs.
import { useSyncExternalStore } from 'react';
import { CatCamState, getCatCam, setCatCam } from './api';
import { cacheGet, cacheSet } from './cache';

const CACHE_KEY = 'catcam:state';

type StoreState = {
  data: CatCamState | null;
  loaded: boolean;
  busy: boolean;
  error: string | null;
};

let state: StoreState = {
  data: cacheGet<CatCamState>(CACHE_KEY),
  loaded: false,
  busy: false,
  error: null,
};

const subs = new Set<() => void>();
function emit(patch: Partial<StoreState>) {
  state = { ...state, ...patch };
  subs.forEach((f) => f());
}

export function useCatCam(): StoreState & { enabled: boolean } {
  const s = useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
  // Default to enabled when never fetched yet so first-time users see cams until server says otherwise.
  return { ...s, enabled: s.data?.enabled ?? true };
}

export async function refreshCatCam(): Promise<CatCamState | null> {
  if (state.busy) return state.data;
  try {
    const next = await getCatCam();
    if (state.busy) return state.data;
    cacheSet(CACHE_KEY, next);
    emit({ data: next, loaded: true, error: null });
    return next;
  } catch (e) {
    if (state.busy) return state.data;
    emit({ loaded: true, error: (e as Error).message });
    return null;
  }
}

export async function toggleCatCam(enabled: boolean): Promise<CatCamState | null> {
  if (state.busy) return state.data;
  const before = state.data;
  const optimistic: CatCamState = { enabled, units: before?.units ?? {} };
  emit({ data: optimistic, busy: true, error: null });
  try {
    const next = await setCatCam(enabled);
    cacheSet(CACHE_KEY, next);
    emit({ data: next, loaded: true, busy: false, error: null });
    return next;
  } catch (e) {
    emit({ data: before, busy: false, error: (e as Error).message });
    return null;
  }
}
