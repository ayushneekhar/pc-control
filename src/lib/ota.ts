// Over-the-air JS updates with react-native-nitro-ota, set up like Apex (lib/nitro-ota.ts there), but the snapshot
// lives on a GitHub Release (tag "ota-android", assets ota.version.json + bundle.zip) instead of a branch, and every
// bundle must carry the laptop's signature (checked natively, see modules/pc-native/.../OtaVerifier.kt).
//
// - A bundle targets one exact native build ("<version>+<versionCode>"); others are never offered.
// - Only bundles strictly newer than what runs (including the JS embedded in the APK) count as updates.
// - Native changes -> new APK (scripts/release-apk.sh); JS-only changes -> OTA (scripts/ota-publish.sh).
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { PcNative } from '../../modules/pc-native';

type NitroOtaModule = typeof import('react-native-nitro-ota');
type Manager = InstanceType<NitroOtaModule['OTAUpdateManager']>;
export type OtaCheck = NonNullable<Awaited<ReturnType<Manager['checkForUpdatesJS']>>>;

const extra = (Constants.expoConfig?.extra ?? {}) as { github?: { repo?: string; otaTag?: string } };
export const GITHUB_REPO = extra.github?.repo ?? 'ayushneekhar/pc-control';
const OTA_TAG = extra.github?.otaTag ?? 'ota-android';
const RELEASES = `https://github.com/${GITHUB_REPO}/releases`;
export const OTA_URLS = {
  versionUrl: `${RELEASES}/download/${OTA_TAG}/ota.version.json`,
  downloadUrl: `${RELEASES}/download/${OTA_TAG}/bundle.zip`,
};
/** The newest APK release: a small version.json next to a stable-named APK (scripts/release-apk.sh). */
export const APK_URLS = {
  versionUrl: `${RELEASES}/latest/download/version.json`,
  apkUrl: `${RELEASES}/latest/download/pc-control.apk`,
  page: `${RELEASES}/latest`,
};

let cached: { mod: NitroOtaModule; manager: Manager } | null | undefined;

function ota() {
  if (cached !== undefined) return cached;
  cached = null;
  if (Platform.OS !== 'android') return cached;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const mod = require('react-native-nitro-ota') as NitroOtaModule;
    cached = { mod, manager: new mod.OTAUpdateManager(OTA_URLS.downloadUrl, OTA_URLS.versionUrl) };
  } catch {
    cached = null;
  }
  return cached;
}

export function binaryVersion(): string {
  return Constants.expoConfig?.version ?? '0.0.0';
}

/** Exact native build, "<version>+<versionCode>". Must match scripts/ota/build-nitro-ota.mjs and OtaVerifier. */
export function binaryTarget(): string {
  try {
    if (PcNative) return PcNative.otaBinaryTarget();
  } catch {}
  const code = Constants.expoConfig?.android?.versionCode;
  return code ? `${binaryVersion()}+${code}` : binaryVersion();
}

/** OTA version the running JS was built as (inlined at bundle time; also set for the APK's embedded JS). */
function bundledOtaVersion(): string | null {
  return process.env.EXPO_PUBLIC_BUNDLED_OTA_VERSION?.trim() || null;
}

const num = (v: string | null | undefined) => (v && /^\d+$/.test(v.trim()) ? Number(v.trim()) : null);

export function otaSnapshot() {
  const o = ota();
  return {
    available: !!o,
    target: binaryTarget(),
    otaVersion: o?.manager.getVersion() ?? bundledOtaVersion(),
    fromDownload: !!o?.manager.getVersion(),
  };
}

export async function checkForOta(): Promise<OtaCheck | null> {
  const o = ota();
  if (!o) return null;
  const r = await o.manager.checkForUpdatesJS(binaryTarget());
  if (!r) return null;
  const current = o.manager.getVersion() ?? bundledOtaVersion();
  if (/404|not found/i.test(r.remoteVersion ?? '')) return { ...r, hasUpdate: false, isCompatible: false };
  // Nitro calls "no OTA applied yet" outdated and flags any different string; only strictly newer counts.
  const remote = num(r.remoteVersion);
  const cur = num(current);
  if (remote !== null && cur !== null) return { ...r, hasUpdate: remote > cur };
  return r;
}

/** Download + unzip, then check the signature before anything uses it. Throws (and drops it) if it's refused. */
export async function downloadOta(onProgress?: (received: number, total: number) => void): Promise<void> {
  const o = ota();
  if (!o) throw new Error('OTA is unavailable in this build');
  await o.manager.downloadUpdate(onProgress);
  const path = o.manager.getUnzippedPath();
  const problem = path && PcNative ? PcNative.otaCheck(path) : 'no bundle path';
  if (problem) {
    await o.manager.rollback().catch(() => false);
    throw new Error(`Update refused: ${problem}`);
  }
}

export function reloadApp() {
  ota()?.manager.reloadApp();
}

/** Call once the app has started fine on a downloaded bundle, so the crash guard won't roll it back. */
export function confirmOta() {
  const o = ota();
  if (o?.manager.getVersion()) o.manager.confirm();
}

export function onOtaRollback(cb: (r: unknown) => void): () => void {
  const o = ota();
  return o ? o.mod.onRollback(cb) : () => {};
}

export type OtaRecovery = { reason: string; message: string; otaVersion: string | null };

export function otaRecovery(): OtaRecovery | null {
  try {
    const raw = PcNative?.otaRecoveryStatus();
    if (!raw) return null;
    const j = JSON.parse(raw);
    return typeof j?.message === 'string' ? { reason: String(j.reason), message: j.message, otaVersion: j.otaVersion ?? null } : null;
  } catch {
    return null;
  }
}

export function clearOtaRecovery() {
  try {
    PcNative?.clearOtaRecoveryStatus();
  } catch {}
}

export type ApkRelease = { versionName: string; versionCode: number; sha256: string; size: number };

/** Newest APK on GitHub Releases, or null (offline, no release yet, or the repo is still private). */
export async function latestApk(): Promise<ApkRelease | null> {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 10000);
  try {
    const r = await fetch(APK_URLS.versionUrl, { signal: ctrl.signal, headers: { 'Cache-Control': 'no-cache' } });
    if (!r.ok) return null;
    const j = await r.json();
    return typeof j?.versionCode === 'number' ? (j as ApkRelease) : null;
  } catch {
    return null;
  } finally {
    clearTimeout(t);
  }
}
