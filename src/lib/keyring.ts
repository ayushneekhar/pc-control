// Shared reactive store and biometric vault helpers for unlocking the laptop's GNOME login keyring.
// Plaintext passwords are never stored, cached, or logged; they live only as local variables for the
// duration of the API request or native encrypt call.
import { useSyncExternalStore } from 'react';
import { PcNative } from '../../modules/pc-native';
import { ApiError, getKeyring, KeyringStatus, unlockKeyringApi } from './api';

export type KeyringActionResult =
  | { ok: true; status: KeyringStatus; message: string }
  | { ok: false; cancelled?: boolean; invalidated?: boolean; message: string };

type StoreState = {
  status: KeyringStatus | null;
  hasSaved: boolean;
  busy: boolean;
  error: string | null;
  notice: string | null;
};

function readHasSaved(): boolean {
  try {
    return PcNative?.keyringHasSaved?.() ?? false;
  } catch {
    return false;
  }
}

let state: StoreState = {
  status: null,
  hasSaved: readHasSaved(),
  busy: false,
  error: null,
  notice: null,
};

const subs = new Set<() => void>();
function emit(patch: Partial<StoreState>) {
  state = { ...state, ...patch };
  subs.forEach((f) => f());
}

export function useKeyring(): StoreState {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
}

export function keyringVaultSupported(): boolean {
  return !!(PcNative?.keyringSave && PcNative?.keyringDecrypt && PcNative?.keyringHasSaved && PcNative?.keyringForget);
}

export function clearKeyringFeedback(): void {
  if (state.error || state.notice) emit({ error: null, notice: null });
}

export async function refreshKeyring(): Promise<KeyringStatus | null> {
  const hasSaved = readHasSaved();
  try {
    const next = await getKeyring();
    emit({ status: next, hasSaved });
    return next;
  } catch {
    if (hasSaved !== state.hasSaved) emit({ hasSaved });
    return null;
  }
}

function mapBiometricError(e: unknown): { cancelled?: boolean; invalidated?: boolean; message: string } {
  const err = e as { code?: string; message?: string } | null;
  const code = err?.code;
  if (code === 'E_CANCELLED') return { cancelled: true, message: 'Cancelled' };
  if (code === 'E_KEY_INVALIDATED' || code === 'E_NO_KEY') {
    return {
      invalidated: true,
      message: err?.message ?? 'Saved keyring password is no longer usable. Save it again in Settings.',
    };
  }
  if (code === 'E_LOCKOUT') {
    return { message: 'Too many biometric attempts. Unlock your phone with your PIN, then try again.' };
  }
  return { message: err?.message ?? 'Biometric authentication failed.' };
}

function mapApiError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return 'Wrong keyring password.';
    if (e.status === 429) return e.message || 'Too many failed unlock attempts. Try again later.';
    return e.message;
  }
  return (e as Error)?.message ?? 'Unlock failed.';
}

function checkBiometrics(): string | null {
  if (!keyringVaultSupported()) return 'This build does not support saving the keyring password.';
  const bio = PcNative?.biometricStatus?.();
  if (bio !== 'ok') {
    return bio === 'none_enrolled'
      ? 'Add a fingerprint in Android settings first (Security > Fingerprint).'
      : `Strong biometrics are not available on this phone (${bio ?? 'unavailable'}).`;
  }
  return null;
}

export async function saveKeyringPassword(password: string): Promise<{ ok: true } | { ok: false; cancelled?: boolean; message: string }> {
  let pw: string | null = password;
  try {
    if (!pw) return { ok: false, message: 'Enter your keyring password.' };
    const bioErr = checkBiometrics();
    if (bioErr) {
      emit({ error: bioErr, notice: null });
      return { ok: false, message: bioErr };
    }
    emit({ busy: true, error: null, notice: null });
    await PcNative!.keyringSave!(
      pw,
      'Save keyring password',
      'Verify your fingerprint to encrypt and store the password on this phone',
    );
    emit({ busy: false, hasSaved: readHasSaved(), error: null, notice: 'Keyring password saved on this phone.' });
    return { ok: true };
  } catch (e) {
    const mapped = mapBiometricError(e);
    emit({ busy: false, hasSaved: readHasSaved(), error: mapped.cancelled ? null : mapped.message, notice: null });
    return { ok: false, cancelled: mapped.cancelled, message: mapped.message };
  } finally {
    pw = null;
  }
}

export function forgetKeyringPassword(): void {
  try {
    PcNative?.keyringForget?.();
  } finally {
    emit({ hasSaved: readHasSaved(), error: null, notice: 'Saved keyring password removed.' });
  }
}

export async function unlockKeyringWithSaved(): Promise<KeyringActionResult> {
  if (state.busy) return { ok: false, message: 'Unlock already in progress.' };
  if (!keyringVaultSupported() || !readHasSaved()) {
    return { ok: false, invalidated: true, message: 'No keyring password saved on this phone.' };
  }
  emit({ busy: true, error: null, notice: null });
  let pw: string | null = null;
  try {
    pw = await PcNative!.keyringDecrypt!(
      'Unlock laptop keyring',
      'Verify your fingerprint to unlock the GNOME keyring and restart RDP',
    );
  } catch (e) {
    const mapped = mapBiometricError(e);
    emit({
      busy: false,
      hasSaved: readHasSaved(),
      error: mapped.cancelled ? null : mapped.message,
      notice: null,
    });
    return { ok: false, cancelled: mapped.cancelled, invalidated: mapped.invalidated, message: mapped.message };
  }

  try {
    const res = await unlockKeyringApi(pw);
    const next: KeyringStatus = { locked: res.locked, rdp: res.rdp };
    const msg = next.locked
      ? 'Keyring is still locked.'
      : `Keyring unlocked · RDP ${next.rdp}`;
    emit({ status: next, busy: false, error: null, notice: msg });
    return { ok: true, status: next, message: msg };
  } catch (e) {
    const msg = mapApiError(e);
    emit({ busy: false, error: msg, notice: null });
    return { ok: false, message: msg };
  } finally {
    pw = null;
  }
}

export async function unlockKeyringManual(password: string, saveAfter: boolean): Promise<KeyringActionResult> {
  if (state.busy) return { ok: false, message: 'Unlock already in progress.' };
  let pw: string | null = password;
  if (!pw) return { ok: false, message: 'Enter your keyring password.' };
  emit({ busy: true, error: null, notice: null });
  try {
    const res = await unlockKeyringApi(pw);
    const next: KeyringStatus = { locked: res.locked, rdp: res.rdp };
    let msg = next.locked ? 'Keyring is still locked.' : `Keyring unlocked · RDP ${next.rdp}`;
    if (saveAfter && !next.locked && keyringVaultSupported()) {
      const bioErr = checkBiometrics();
      if (bioErr) {
        msg = `${msg} (not saved: ${bioErr})`;
      } else {
        try {
          await PcNative!.keyringSave!(
            pw,
            'Save keyring password',
            'Verify your fingerprint to save this password for next time',
          );
          msg = `${msg} · saved on phone`;
        } catch (e) {
          const mapped = mapBiometricError(e);
          if (!mapped.cancelled) msg = `${msg} (not saved: ${mapped.message})`;
        }
      }
    }
    emit({ status: next, hasSaved: readHasSaved(), busy: false, error: null, notice: msg });
    return { ok: true, status: next, message: msg };
  } catch (e) {
    const msg = mapApiError(e);
    emit({ busy: false, error: msg, notice: null });
    return { ok: false, message: msg };
  } finally {
    pw = null;
  }
}
