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
import { ApkRelease, parseApkVersion, parseOtaManifest } from './otaManifest';

export type { ApkRelease } from './otaManifest';

type NitroOtaModule = typeof import('react-native-nitro-ota');
type Manager = InstanceType<NitroOtaModule['OTAUpdateManager']>;
/** A validated manifest (src/lib/otaManifest.ts). Failures are thrown as one short message instead. */
export type OtaCheck = { hasUpdate: boolean; isCompatible: boolean; remoteVersion: string; metadata?: { releaseNotes?: string } };

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

export function otaSnapshot() {
  const o = ota();
  return {
    available: !!o,
    target: binaryTarget(),
    otaVersion: o?.manager.getVersion() ?? bundledOtaVersion(),
    fromDownload: !!o?.manager.getVersion(),
  };
}

type Fetched = { status: number; body: string };

/** Second attempt for the GitHub JSON files: RN's own networking module over XHR, which does not go through
 *  expo/fetch, its OkHttp interceptor stack or the WebView cookie jar. Resolves 4xx/5xx too, so the manifest
 *  parser can report the HTTP status. */
function getTextXhr(url: string, timeoutMs: number): Promise<Fetched> {
  return new Promise((resolve, reject) => {
    const fail = (name: string, message: string) => {
      const e = new Error(message);
      e.name = name;
      reject(e);
    };
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url);
    xhr.withCredentials = false;
    xhr.timeout = timeoutMs;
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.setRequestHeader('Cache-Control', 'no-cache');
    xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText ?? '' });
    xhr.onerror = () => fail('TypeError', 'Network request failed');
    xhr.ontimeout = () => fail('TimeoutError', `timed out after ${Math.round(timeoutMs / 1000)} s`);
    xhr.onabort = () => fail('AbortError', 'aborted');
    try {
      xhr.send();
    } catch (e) {
      reject(e);
    }
  });
}

/** GET a small JSON file from GitHub -> [status, body]. Tries expo/fetch first (no cookies, gzip only) and falls
 *  back once to XHR. Throws one readable message naming the host, the attempts' timings and the underlying errors. */
async function getText(url: string, what: string, timeoutMs = 10000, xhrTimeoutMs = 8000): Promise<[number, string]> {
  const host = url.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('/')[0];
  const attempts: string[] = [];
  let timedOut = false;

  const ctrl = new AbortController();
  const started = Date.now();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    // expo/fetch shares RN's cookie handler (the WebView CookieManager since react-native-webview arrived) and
    // otherwise negotiates zstd/br. Both are suspects for the 10 s stall on github.com: NO_COOKIES + gzip only.
    const r = await fetch(url, {
      signal: ctrl.signal,
      credentials: 'omit',
      headers: { 'Cache-Control': 'no-cache', Accept: 'application/json', 'Accept-Encoding': 'gzip' },
    });
    const body = await r.text();
    console.warn(`[ota] GET ${url} ok via fetch in ${Date.now() - started} ms (HTTP ${r.status})`);
    return [r.status, body];
  } catch (e) {
    const elapsed = Date.now() - started;
    const err = e as Error;
    // RN's abort does not always surface as name 'AbortError' (it can be 'TypeError: ... canceled'), so trust
    // the signal we aborted ourselves.
    timedOut = err?.name === 'AbortError' || ctrl.signal.aborted;
    const detail = `${err?.name || 'Error'}: ${err?.message || String(e)}`;
    attempts.push(`fetch ${timedOut ? 'timed out' : 'failed'} after ${elapsed} ms: ${detail}`);
    console.warn(`[ota] GET ${url} ${timedOut ? 'timed out' : 'failed'} via fetch after ${elapsed} ms (${detail}); retrying via XHR`);
  } finally {
    clearTimeout(t);
  }

  const startedXhr = Date.now();
  try {
    const r = await getTextXhr(url, xhrTimeoutMs);
    console.warn(`[ota] GET ${url} ok via XHR in ${Date.now() - startedXhr} ms (HTTP ${r.status})`);
    return [r.status, r.body];
  } catch (e) {
    const elapsed = Date.now() - startedXhr;
    const err = e as Error;
    const xhrTimedOut = err?.name === 'TimeoutError';
    timedOut = timedOut || xhrTimedOut;
    const detail = `${err?.name || 'Error'}: ${err?.message || String(e)}`;
    attempts.push(`XHR ${xhrTimedOut ? 'timed out' : 'failed'} after ${elapsed} ms: ${detail}`);
    console.warn(`[ota] GET ${url} ${xhrTimedOut ? 'timed out' : 'failed'} via XHR after ${elapsed} ms (${detail})`);
  }

  throw new Error(
    timedOut
      ? `Couldn't check for ${what} (timed out via ${host}: ${attempts.join('; ')})`
      : `Couldn't check for ${what} (network error via ${host}: ${attempts.join('; ')})`,
  );
}

/** Fetches and validates the OTA manifest ourselves (nitro's check takes an HTML error page as a version).
 *  -> null when OTA is unavailable in this build; throws "Couldn't check for updates (HTTP 404)" etc. */
export async function checkForOta(): Promise<OtaCheck | null> {
  const o = ota();
  if (!o) return null;
  const [status, body] = await getText(OTA_URLS.versionUrl, 'updates');
  const blacklist = await o.mod.getBlacklistedVersions().catch(() => [] as string[]);
  const r = parseOtaManifest(status, body, binaryTarget(), o.manager.getVersion() ?? bundledOtaVersion(), blacklist);
  if (!r.ok) throw new Error(r.error);
  return {
    hasUpdate: r.hasUpdate,
    isCompatible: r.isCompatible,
    remoteVersion: r.remoteVersion,
    ...(r.releaseNotes ? { metadata: { releaseNotes: r.releaseNotes } } : {}),
  };
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

/** Newest APK on GitHub Releases. Throws one clean message (offline, HTTP 404 while the repo is private, …). */
export async function latestApk(): Promise<ApkRelease> {
  const [status, body] = await getText(APK_URLS.versionUrl, 'app releases');
  const r = parseApkVersion(status, body);
  if (!r.ok) throw new Error(r.error);
  return r.release;
}
