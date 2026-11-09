import { requireOptionalNativeModule } from 'expo';

export type KeyInfo = {
  publicKeyDer: string;
  publicKeyPem: string;
  securityLevel: 'strongbox' | 'tee' | 'software' | 'unknown';
  strongBox?: boolean;
};

export type NativeSettings = {
  serverUrl: string;
  deviceFingerprint: string | null;
  defaultServerUrl: string;
};

export type BiometricStatus =
  | 'ok'
  | 'none_enrolled'
  | 'no_hardware'
  | 'unavailable'
  | 'security_update_required'
  | 'unsupported';

/** Error codes thrown by sign()/createKey(): E_CANCELLED, E_KEY_INVALIDATED, E_NO_KEY, E_LOCKOUT, E_AUTH, ... */
export type PcNativeModule = {
  /** System bar + display cutout insets in dp, or null before the activity is attached. */
  windowInsets(): { top: number; bottom: number; left: number; right: number } | null;
  getSettings(): NativeSettings;
  setServerUrl(url: string): void;
  setDeviceFingerprint(fp: string | null): void;
  deviceName(): string;
  /** Firebase configured in this build, the FCM token pc-api last accepted (or null), the last error. */
  pushStatus(): { available: boolean; token: string | null; error: string | null };
  /** Fetch the FCM token and register it with pc-api. Rejects with E_NO_FIREBASE / E_FCM_TOKEN. */
  pushRegister(): Promise<{ token: string; registered: boolean; error: string | null }>;
  notificationsEnabled(): boolean;
  openNotificationSettings(): void;
  biometricStatus(): BiometricStatus;
  keyInfo(): KeyInfo | null;
  createKey(): Promise<KeyInfo>;
  deleteKey(): void;
  sign(payload: string, title: string, subtitle: string, description: string): Promise<string>;
};

export const PcNative = requireOptionalNativeModule<PcNativeModule>('PcNative');
