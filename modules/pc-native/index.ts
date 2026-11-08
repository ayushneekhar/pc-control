import { requireOptionalNativeModule } from 'expo';

export type KeyInfo = {
  publicKeyDer: string;
  publicKeyPem: string;
  securityLevel: 'strongbox' | 'tee' | 'software' | 'unknown';
  strongBox?: boolean;
};

export type NativeSettings = {
  serverUrl: string;
  listenerEnabled: boolean;
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
  getSettings(): NativeSettings;
  setServerUrl(url: string): void;
  setDeviceFingerprint(fp: string | null): void;
  deviceName(): string;
  setListenerEnabled(on: boolean): void;
  restartListener(): void;
  listenerStatus(): { running: boolean; connected: boolean };
  notificationsEnabled(): boolean;
  openNotificationSettings(): void;
  isIgnoringBatteryOptimizations(): boolean;
  requestIgnoreBatteryOptimizations(): void;
  biometricStatus(): BiometricStatus;
  keyInfo(): KeyInfo | null;
  createKey(): Promise<KeyInfo>;
  deleteKey(): void;
  sign(payload: string, title: string, subtitle: string, description: string): Promise<string>;
};

export const PcNative = requireOptionalNativeModule<PcNativeModule>('PcNative');
