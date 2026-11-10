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

export type LiveStatus = {
  sdk: number;
  /** Android 16+: Live Updates (status bar chip, Samsung Now Bar). Older: a normal progress notification. */
  supported: boolean;
  /** NotificationManager.canPostPromotedNotifications(): the per-app "Live notifications" switch. */
  canPromote: boolean;
  channelEnabled: boolean;
  running: boolean;
};

export type BiometricStatus =
  | 'ok'
  | 'none_enrolled'
  | 'no_hardware'
  | 'unavailable'
  | 'security_update_required'
  | 'unsupported';

export type HwDecoder = { name: string; maxWidth: number; maxHeight: number; hd: boolean; lowLatency: boolean } | null;

export type CamsSettings = { safeMode: boolean; decoder: boolean; lowLatency: boolean; mediaAudio: boolean };
export type CamsStatus = {
  prepared: boolean;
  applied: Partial<Record<'decoder' | 'lowLatency' | 'mediaAudio', boolean>>;
  error: string | null;
  settings: CamsSettings;
  /** ms: the previous run crashed while Cat cams was opening (safe mode was turned on), else 0. */
  crashedWhileOpening: number;
};
export type CrashReport = {
  id: string;
  kind: string;
  ts: number;
  reason: string;
  where: string;
  text: string;
  trace_b64?: string | null;
};

/** Error codes thrown by sign()/createKey(): E_CANCELLED, E_KEY_INVALIDATED, E_NO_KEY, E_LOCKOUT, E_AUTH, ... */
export type PcNativeModule = {
  /** System bar + display cutout insets in dp, or null before the activity is attached. */
  windowInsets(): { top: number; bottom: number; left: number; right: number } | null;
  /** Hardware H.264/H.265 decoders; configured = H.265 over WebRTC is on in this process (else error says why). */
  camsHardware(): {
    h264: HwDecoder;
    h265: HwDecoder;
    configured: boolean;
    safeMode: boolean;
    error: string | null;
    fieldTrials: string;
  };
  /** Right before react-native-webrtc loads (App.tsx): sets the crash marker and this process's WebRTC options. */
  camsPrepare(): CamsStatus;
  camsStatus(): CamsStatus;
  /** The Cams screen clears the marker once it has run 15 s, or when it is left. */
  camsMarkOpening(on: boolean): void;
  camsSetSetting(key: keyof CamsSettings, on: boolean): void;
  /** The "crashed last time" notice was shown. */
  camsCrashSeen(): void;
  /** Crash reports not uploaded yet (own Java crash files + Android's exit records). */
  crashPending(): Promise<CrashReport[]>;
  crashAck(ids: string[]): void;
  /** Landscape + hidden system bars, or back to portrait. */
  camsSetFullscreen(on: boolean): Promise<void>;
  camsKeepAwake(on: boolean): Promise<void>;
  /** Saves go2rtc's current frame of a stream to Pictures/PC Control; resolves to its content:// uri. */
  camsSaveSnapshot(stream: string, label: string): Promise<string>;
  camsShare(uri: string): Promise<void>;
  /** Text shared to the app ("Share → PC Control"), once; null if none. */
  takeSharedText(): string | null;
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
  liveStatus(): LiveStatus;
  /** Start live download progress for these active downloads (pc-api ids). false = not started (notifications
   *  off, the user already stopped it for all of them, or Android refused the foreground service). */
  liveStart(activeIds: string[]): boolean;
  liveStop(): void;
  /** Android 16's "Live notifications" setting for this app (or its notification settings). */
  openLiveSettings(): boolean;
  biometricStatus(): BiometricStatus;
  keyInfo(): KeyInfo | null;
  createKey(): Promise<KeyInfo>;
  deleteKey(): void;
  sign(payload: string, title: string, subtitle: string, description: string): Promise<string>;
  /** null if the OTA bundle at this path is signed by the laptop's key for this exact build, else why not. */
  otaCheck(bundlePath: string): string | null;
  /** "<versionName>+<versionCode>" of the installed APK. */
  otaBinaryTarget(): string;
  /** JSON written at startup when an OTA bundle was refused or missing (null if none). */
  otaRecoveryStatus(): string | null;
  clearOtaRecoveryStatus(): boolean;
};

export const PcNative = requireOptionalNativeModule<PcNativeModule>('PcNative');
