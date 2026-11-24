// Pure logic for agent-triggered auto-apply OTA (version comparison, loop guard, draft-hold state).
// No React Native imports so Node unit tests (`yarn test:js`) can exercise it directly.

export const OTA_TRIGGER_ATTEMPT_CACHE_KEY = 'ota:lastTriggeredAttempt';
export const AUTO_RELOAD_DELAY_MS = 1500;

export type OtaTrigger = {
  version: string;
  notes?: string;
  triggeredAt?: number;
};

export type OtaTriggerAttempt = {
  version: string;
  status: 'failed' | 'applied';
  error?: string;
  at: number;
};

export type AutoApplyStatus = 'idle' | 'downloading' | 'reloading' | 'held_for_draft';

const VERSION_RE = /^\d{1,9}$/;

export function parseOtaTrigger(raw: unknown): OtaTrigger | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;
  const rawVer = obj.version;
  const version =
    typeof rawVer === 'number' && Number.isInteger(rawVer) && rawVer >= 0
      ? String(rawVer)
      : typeof rawVer === 'string'
        ? rawVer.trim()
        : '';
  if (!VERSION_RE.test(version)) return null;
  const notes = typeof obj.notes === 'string' && obj.notes.trim() ? obj.notes.trim().slice(0, 300) : undefined;
  const rawTs = obj.triggered_at ?? obj.triggeredAt;
  const triggeredAt = typeof rawTs === 'number' && Number.isFinite(rawTs) ? rawTs : undefined;
  return {
    version,
    ...(notes ? { notes } : {}),
    ...(triggeredAt !== undefined ? { triggeredAt } : {}),
  };
}

export function isOtaVersionNewer(remoteVersion: string, currentVersion: string | null): boolean {
  const rv = remoteVersion.trim();
  if (!VERSION_RE.test(rv)) return false;
  if (!currentVersion || !VERSION_RE.test(currentVersion.trim())) return true;
  return Number(rv) > Number(currentVersion.trim());
}

export function shouldAutoApplyTrigger(params: {
  trigger: OtaTrigger | null;
  otaAvailable: boolean;
  currentVersion: string | null;
  lastAttempt: OtaTriggerAttempt | null;
  blacklist?: string[];
  inFlightVersion?: string | null;
  autoStatus?: AutoApplyStatus;
}): { shouldApply: boolean; reason: string } {
  const { trigger, otaAvailable, currentVersion, lastAttempt, blacklist = [], inFlightVersion, autoStatus = 'idle' } = params;
  if (!trigger) return { shouldApply: false, reason: 'no_trigger' };
  if (!otaAvailable) return { shouldApply: false, reason: 'ota_unavailable' };
  if (!VERSION_RE.test(trigger.version)) return { shouldApply: false, reason: 'invalid_version' };
  if (!isOtaVersionNewer(trigger.version, currentVersion)) return { shouldApply: false, reason: 'not_newer' };
  if (blacklist.includes(trigger.version)) return { shouldApply: false, reason: 'blacklisted' };
  if (lastAttempt && lastAttempt.version === trigger.version) {
    return { shouldApply: false, reason: `already_${lastAttempt.status}` };
  }
  if (inFlightVersion === trigger.version) return { shouldApply: false, reason: 'in_flight' };
  if (autoStatus === 'downloading' || autoStatus === 'reloading' || autoStatus === 'held_for_draft') {
    return { shouldApply: false, reason: `busy_${autoStatus}` };
  }
  return { shouldApply: true, reason: 'ok' };
}

export function nextReloadPhase(draftActive: boolean): 'reloading' | 'held_for_draft' {
  return draftActive ? 'held_for_draft' : 'reloading';
}

// ---- Shared active-draft tracker (ChatScreen -> otaStore) ----
// Keeps auto-apply OTA from ever wiping an unsent chat message or pending attachment.

let activeDraft = false;
const draftListeners = new Set<(active: boolean) => void>();

export function hasActiveDraft(): boolean {
  return activeDraft;
}

export function setHasActiveDraft(active: boolean): void {
  const next = Boolean(active);
  if (next === activeDraft) return;
  activeDraft = next;
  draftListeners.forEach((fn) => {
    try {
      fn(activeDraft);
    } catch {
      // ignore listener errors
    }
  });
}

export function subscribeDraftState(listener: (active: boolean) => void): () => void {
  draftListeners.add(listener);
  return () => {
    draftListeners.delete(listener);
  };
}

export function _resetDraftStateForTests(): void {
  activeDraft = false;
  draftListeners.clear();
}
