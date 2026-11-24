// OTA update state shared by the launch prompt (App.tsx) and Settings. Checks on launch and whenever the app
// comes back to the foreground (at most every 10 minutes), plus supports agent-triggered background auto-apply
// over SSE (/pc/api/events) and GET /pc/api/ota/trigger with MMKV loop protection and draft-safe reload.
import { useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { PcNative } from '../../modules/pc-native';
import { cacheGet, cacheSet } from './cache';
import {
  checkForOta,
  confirmOta,
  downloadOta,
  fetchOtaTrigger,
  onOtaRollback,
  OtaCheck,
  otaSnapshot,
  reloadApp,
} from './ota';
import {
  AUTO_RELOAD_DELAY_MS,
  hasActiveDraft,
  nextReloadPhase,
  OTA_TRIGGER_ATTEMPT_CACHE_KEY,
  OtaTriggerAttempt,
  parseOtaTrigger,
  shouldAutoApplyTrigger,
  subscribeDraftState,
} from './otaTriggerLogic';

export type AutoApplyState =
  | { status: 'idle' }
  | { status: 'downloading'; version: string; notes?: string }
  | { status: 'reloading'; version: string; notes?: string }
  | { status: 'held_for_draft'; version: string; notes?: string };

type State = {
  check: OtaCheck | null;
  checking: boolean;
  checkedAt: number;
  dismissed: string | null; // remote version the user said "Later" to
  phase: 'idle' | 'downloading' | 'ready' | 'error';
  progress: number;
  error: string | null;
  autoApply: AutoApplyState;
};

let state: State = {
  check: null,
  checking: false,
  checkedAt: 0,
  dismissed: null,
  phase: 'idle',
  progress: 0,
  error: null,
  autoApply: { status: 'idle' },
};
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
  s.autoApply.status === 'idle' &&
  !!s.check?.hasUpdate &&
  !!s.check.isCompatible &&
  s.dismissed !== s.check.remoteVersion &&
  s.phase !== 'ready';

export async function checkOta(force = false) {
  if (state.checking || state.phase === 'downloading') return;
  if (!force && Date.now() - state.checkedAt < 10 * 60_000) return;
  set({ checking: true });
  try {
    set({ check: await checkForOta(), error: null, checkedAt: Date.now() });
  } catch (e) {
    set({ check: null, error: (e as Error).message, checkedAt: Date.now() });
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
    let fresh: OtaCheck | null;
    try {
      fresh = await checkForOta();
    } catch (e) {
      set({ phase: 'idle', check: null, error: (e as Error).message }); // re-check failed: no update to offer
      return;
    }
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

// ---- Agent-triggered background auto-apply OTA ----

let inFlightTriggerVersion: string | null = null;
let reloadTimer: ReturnType<typeof setTimeout> | null = null;
let draftUnsub: (() => void) | null = null;

function clearAutoReloadHandles() {
  if (reloadTimer) {
    clearTimeout(reloadTimer);
    reloadTimer = null;
  }
  if (draftUnsub) {
    draftUnsub();
    draftUnsub = null;
  }
}

function recordTriggerAttempt(version: string, status: 'failed' | 'applied', error?: string) {
  cacheSet<OtaTriggerAttempt>(OTA_TRIGGER_ATTEMPT_CACHE_KEY, {
    version,
    status,
    ...(error ? { error: error.slice(0, 240) } : {}),
    at: Date.now(),
  });
}

function scheduleAutoReload(version: string, notes?: string) {
  clearAutoReloadHandles();
  const phase = nextReloadPhase(hasActiveDraft());
  if (phase === 'held_for_draft') {
    set({ autoApply: { status: 'held_for_draft', version, ...(notes ? { notes } : {}) } });
    draftUnsub = subscribeDraftState((active) => {
      if (!active && state.autoApply.status === 'held_for_draft') {
        scheduleAutoReload(version, notes);
      }
    });
    return;
  }

  set({ autoApply: { status: 'reloading', version, ...(notes ? { notes } : {}) } });
  draftUnsub = subscribeDraftState((active) => {
    // If the user starts typing a draft during the brief countdown, hold the reload immediately.
    if (active && state.autoApply.status === 'reloading') {
      scheduleAutoReload(version, notes);
    }
  });
  reloadTimer = setTimeout(() => {
    reloadTimer = null;
    if (hasActiveDraft()) {
      scheduleAutoReload(version, notes);
      return;
    }
    clearAutoReloadHandles();
    reloadApp();
  }, AUTO_RELOAD_DELAY_MS);
}

/** Immediately reload into a downloaded auto-applied OTA (e.g. from the 1-tap "OTA <v> ready · Reload" pill). */
export function reloadAutoOtaNow() {
  clearAutoReloadHandles();
  reloadApp();
}

/** Handle a trigger payload from SSE (/pc/api/events) or GET /pc/api/ota/trigger. Silent on failure, loop-guarded in MMKV. */
export async function handleOtaTrigger(raw: unknown): Promise<void> {
  const trigger = parseOtaTrigger(raw);
  if (!trigger) return;
  const snap = otaSnapshot();
  const lastAttempt = cacheGet<OtaTriggerAttempt>(OTA_TRIGGER_ATTEMPT_CACHE_KEY);
  const decision = shouldAutoApplyTrigger({
    trigger,
    otaAvailable: snap.available,
    currentVersion: snap.otaVersion,
    lastAttempt,
    inFlightVersion: inFlightTriggerVersion,
    autoStatus: state.autoApply.status,
  });
  if (!decision.shouldApply) return;
  if (state.phase === 'downloading') return;

  inFlightTriggerVersion = trigger.version;
  set({
    phase: 'downloading',
    progress: 0,
    error: null,
    autoApply: { status: 'downloading', version: trigger.version, ...(trigger.notes ? { notes: trigger.notes } : {}) },
  });

  try {
    const fresh = await checkForOta(true);
    if (!fresh || !fresh.isCompatible) {
      recordTriggerAttempt(trigger.version, 'failed', 'Update is unavailable or incompatible with this build');
      set({ phase: 'idle', check: fresh, autoApply: { status: 'idle' } });
      return;
    }
    if (!fresh.hasUpdate) {
      set({ phase: 'idle', check: fresh, autoApply: { status: 'idle' } });
      return;
    }
    await downloadOta((r, t) => t > 0 && set({ progress: r / t }));
    const appliedVersion = fresh.remoteVersion;
    recordTriggerAttempt(appliedVersion, 'applied');
    if (appliedVersion !== trigger.version) {
      recordTriggerAttempt(trigger.version, 'applied');
    }
    set({ phase: 'ready', check: fresh, error: null });
    scheduleAutoReload(appliedVersion, trigger.notes ?? fresh.metadata?.releaseNotes);
  } catch (e) {
    const msg = (e as Error)?.message || String(e);
    console.warn(`[ota] auto-apply OTA ${trigger.version} failed: ${msg}`);
    recordTriggerAttempt(trigger.version, 'failed', msg);
    set({ phase: 'idle', error: null, autoApply: { status: 'idle' } });
  } finally {
    if (inFlightTriggerVersion === trigger.version) {
      inFlightTriggerVersion = null;
    }
  }
}

/** Poll GET /pc/api/ota/trigger and auto-apply if a newer triggered version is waiting. */
export async function checkOtaTrigger(): Promise<void> {
  const trigger = await fetchOtaTrigger();
  if (trigger) {
    await handleOtaTrigger(trigger);
  }
}

let started = false;
/** Call once at startup: confirms a bundle that booted fine, then checks now and on every foreground. */
export function startOta() {
  if (started) return () => {};
  started = true;
  confirmOta();
  const unsub = onOtaRollback((r) => console.warn('OTA rollback', r));
  void checkOta(true);
  void checkOtaTrigger();
  const s = AppState.addEventListener('change', (st) => {
    if (st === 'active') {
      void checkOta();
      void checkOtaTrigger();
    }
  });
  let pushSub: { remove(): void } | undefined;
  try {
    pushSub = PcNative?.addListener?.('onChatPush', (ev) => {
      if (typeof ev?.id === 'string' && ev.id.startsWith('ota-trigger-')) {
        void checkOtaTrigger();
      }
    });
  } catch {
    // Ignore on older APKs without onChatPush.
  }
  return () => {
    started = false;
    clearAutoReloadHandles();
    unsub();
    s.remove();
    try {
      pushSub?.remove?.();
    } catch {}
  };
}
