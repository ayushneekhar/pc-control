// OTA update state shared by the launch prompt (App.tsx) and Settings. Checks on launch and whenever the app
// comes back to the foreground (at most every 10 minutes), like Apex's initial check + prompt.
import { useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { checkForOta, confirmOta, downloadOta, onOtaRollback, OtaCheck, reloadApp } from './ota';

type State = {
  check: OtaCheck | null;
  checking: boolean;
  checkedAt: number;
  dismissed: string | null; // remote version the user said "Later" to
  phase: 'idle' | 'downloading' | 'ready' | 'error';
  progress: number;
  error: string | null;
};

let state: State = { check: null, checking: false, checkedAt: 0, dismissed: null, phase: 'idle', progress: 0, error: null };
const subs = new Set<() => void>();
const set = (p: Partial<State>) => {
  state = { ...state, ...p };
  subs.forEach((f) => f());
};

export function useOta() {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
}

export const updateOffered = (s: State) =>
  !!s.check?.hasUpdate && !!s.check.isCompatible && s.dismissed !== s.check.remoteVersion && s.phase !== 'ready';

export async function checkOta(force = false) {
  if (state.checking || state.phase === 'downloading') return;
  if (!force && Date.now() - state.checkedAt < 10 * 60_000) return;
  set({ checking: true, error: force ? null : state.error });
  try {
    set({ check: await checkForOta(), checkedAt: Date.now() });
  } catch (e) {
    set({ error: (e as Error).message, checkedAt: Date.now() });
  }
  set({ checking: false });
}

export function dismissOta() {
  set({ dismissed: state.check?.remoteVersion ?? null });
}

/** Download + verify. With restart=true it reloads right away; otherwise the update applies on next start. */
export async function applyOta(restart: boolean) {
  if (state.phase === 'downloading') return;
  set({ phase: 'downloading', progress: 0, error: null });
  try {
    const fresh = await checkForOta();
    if (!fresh?.hasUpdate || !fresh.isCompatible) {
      set({ phase: 'idle', check: fresh });
      return;
    }
    await downloadOta((r, t) => t > 0 && set({ progress: r / t }));
    set({ phase: 'ready' });
    if (restart) reloadApp();
  } catch (e) {
    set({ phase: 'error', error: (e as Error).message });
  }
}

let started = false;
/** Call once at startup: confirms a bundle that booted fine, then checks now and on every foreground. */
export function startOta() {
  if (started) return () => {};
  started = true;
  confirmOta();
  const unsub = onOtaRollback((r) => console.warn('OTA rollback', r));
  checkOta(true);
  const s = AppState.addEventListener('change', (st) => st === 'active' && checkOta());
  return () => {
    started = false;
    unsub();
    s.remove();
  };
}
