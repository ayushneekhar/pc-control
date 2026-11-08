import { useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { get, RootdState, RootRequest } from './api';
import { EventStream, ServerEvent } from './events';

type State = {
  rootd: RootdState;
  live: boolean;
  requests: Record<string, RootRequest>; // pending + running (+ just finished, kept briefly for the UI)
  historyVersion: number; // bumps whenever something finishes, so history views refetch
  enrolledTick: number;
};

let state: State = { rootd: 'unknown', live: false, requests: {}, historyVersion: 0, enrolledTick: 0 };
const subs = new Set<() => void>();

function set(patch: Partial<State> | ((s: State) => Partial<State>)) {
  state = { ...state, ...(typeof patch === 'function' ? patch(state) : patch) };
  subs.forEach((f) => f());
}

function upsert(r: RootRequest) {
  set((s) => {
    const requests = { ...s.requests, [r.id]: { ...s.requests[r.id], ...r } };
    const finished = r.status !== 'pending' && r.status !== 'running';
    return { requests, historyVersion: finished ? s.historyVersion + 1 : s.historyVersion };
  });
}

function onEvent({ event, data }: ServerEvent) {
  switch (event) {
    case 'hello': {
      const reqs: Record<string, RootRequest> = {};
      for (const r of (data.pending || []) as RootRequest[]) reqs[r.id] = r;
      set((s) => ({ rootd: data.rootd, requests: reqs, historyVersion: s.historyVersion + 1 }));
      break;
    }
    case 'rootd':
      if (data.pending) {
        const reqs: Record<string, RootRequest> = {};
        for (const r of data.pending as RootRequest[]) reqs[r.id] = r;
        set({ rootd: data.state, requests: reqs });
      } else set({ rootd: data.state });
      break;
    case 'request':
    case 'update':
      upsert(data as RootRequest);
      break;
    case 'enrolled':
      set((s) => ({ enrolledTick: s.enrolledTick + 1 }));
      break;
  }
}

const stream = new EventStream(onEvent, (live) => set({ live }));
let started = false;
let poller: ReturnType<typeof setInterval> | null = null;

/** Fallback when the stream is down: poll pending every 8 s while the app is in the foreground. */
async function poll() {
  if (state.live) return;
  try {
    const res = await get<{ requests: RootRequest[] }>('/pc/api/root/pending', 6000);
    const reqs: Record<string, RootRequest> = {};
    for (const r of res.requests) reqs[r.id] = r;
    set({ rootd: 'ok', requests: reqs });
  } catch (e: any) {
    if (e?.status === 503) set({ rootd: e.body?.rootd ?? 'down', requests: {} });
  }
}

export function startRootStore() {
  if (started) return;
  started = true;
  const onFg = () => {
    stream.start();
    poll();
    if (!poller) poller = setInterval(poll, 8000);
  };
  const onBg = () => {
    stream.stop();
    if (poller) clearInterval(poller);
    poller = null;
  };
  AppState.addEventListener('change', (s) => (s === 'active' ? onFg() : onBg()));
  if (AppState.currentState === 'active' || AppState.currentState == null) onFg();
}

export function reconnectRootStore() {
  stream.reconnectNow();
  poll();
}

export function applyRequest(r: RootRequest) {
  upsert(r);
}

export function forgetRequest(id: string) {
  set((s) => {
    const requests = { ...s.requests };
    delete requests[id];
    return { requests };
  });
}

export function useRootStore(): State {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
}

export function pendingList(s: State): RootRequest[] {
  return Object.values(s.requests)
    .filter((r) => r.status === 'pending' || r.status === 'running')
    .sort((a, b) => b.created - a.created);
}
