// Over-the-air JS updates with react-native-nitro-ota, set up like Apex (lib/nitro-ota.ts there), but the snapshot
// lives on a GitHub Release (tag "ota-android", assets ota.version.json + bundle.zip) AND is mirrored by pc-api on
// the laptop (/pc/api/ota/*). Every bundle must carry the laptop's signature (checked natively by OtaVerifier.kt);
// the mirror is strictly transport.
//
// Why mirror-first + direct downloadZipFromUrl + hard timeouts:
// 1. On Android with Tailscale VPN active (IPv6 tunnel address), OkHttp (used by expo/fetch, RN XHR, and nitro-ota's
//    DownloadManager) lacks Happy Eyeballs and stalls connecting to GitHub / release-assets.githubusercontent.com.
//    Fetching from pc-api over the tailnet first succeeds immediately when Tailscale is on; GitHub is the fallback
//    when off the tailnet.
// 2. nitro-ota's OTAUpdateManager.downloadUpdate() calls findPatchUrl() -> raw fetch(versionCheckUrl) with NO timeout,
//    and DownloadManager's OkHttpClient() has callTimeout=0. Calling downloadZipFromUrl() directly under a hard
//    timeout bypasses the unbounded patch fetch and guarantees an error is surfaced instead of hanging.
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { PcNative } from '../../modules/pc-native';
import { serverUrl } from './api';
import { ApkRelease, parseApkVersion, parseOtaManifest } from './otaManifest';

export type { ApkRelease } from './otaManifest';

type NitroOtaModule = typeof import('react-native-nitro-ota');
type Manager = InstanceType<NitroOtaModule['OTAUpdateManager']>;
/** A validated manifest (src/lib/otaManifest.ts). Failures are thrown as one short message instead. */
export type OtaCheck = { hasUpdate: boolean; isCompatible: boolean; remoteVersion: string; metadata?: { releaseNotes?: string } };

const extra = (Constants.expoConfig?.extra ?? {}) as {
  github?: { repo?: string; otaTag?: string };
  bundledOtaVersion?: string | number;
};
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

export function mirrorOtaUrls() {
  const base = serverUrl();
  return {
    versionUrl: `${base}/pc/api/ota/ota.version.json`,
    downloadUrl: `${base}/pc/api/ota/bundle.zip`,
  };
}

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

/** OTA version the running JS was built as (baked into app.config by withBuildOptimizations, or inlined at bundle time). */
function bundledOtaVersion(): string | null {
  const fromExtra = extra.bundledOtaVersion;
  if (fromExtra != null && String(fromExtra).trim()) return String(fromExtra).trim();
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

/** Hard deadline wrapper so a stalled native/HTTP call can never hang the UI indefinitely. */
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      const e = new Error(`${label} timed out after ${Math.round(ms / 1000)} s`);
      e.name = 'TimeoutError';
      reject(e);
    }, ms);
    promise.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
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

/** Single fetch attempt with AbortController + hard deadline. */
async function fetchTextOnce(url: string, timeoutMs: number): Promise<Fetched> {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await withTimeout(
      fetch(url, {
        signal: ctrl.signal,
        credentials: 'omit',
        headers: { 'Cache-Control': 'no-cache', Accept: 'application/json', 'Accept-Encoding': 'gzip' },
      }),
      timeoutMs + 500,
      'fetch',
    );
    const body = await withTimeout(r.text(), timeoutMs, 'read body');
    return { status: r.status, body };
  } catch (e) {
    if (ctrl.signal.aborted && (e as Error)?.name !== 'TimeoutError') {
      const te = new Error(`timed out after ${Math.round(timeoutMs / 1000)} s`);
      te.name = 'TimeoutError';
      throw te;
    }
    throw e;
  } finally {
    clearTimeout(t);
  }
}

/** GET a small JSON file from GitHub -> [status, body]. Tries expo/fetch first (no cookies, gzip only) and falls
 *  back once to XHR, both under hard timeouts. Throws one readable message naming the host and errors. */
async function getText(url: string, what: string, timeoutMs = 8000, xhrTimeoutMs = 6000): Promise<[number, string]> {
  const host = url.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('/')[0];
  const attempts: string[] = [];
  let timedOut = false;

  const started = Date.now();
  try {
    const r = await fetchTextOnce(url, timeoutMs);
    console.warn(`[ota] GET ${url} ok via fetch in ${Date.now() - started} ms (HTTP ${r.status})`);
    return [r.status, r.body];
  } catch (e) {
    const elapsed = Date.now() - started;
    const err = e as Error;
    timedOut = err?.name === 'TimeoutError' || err?.name === 'AbortError';
    const detail = `${err?.name || 'Error'}: ${err?.message || String(e)}`;
    attempts.push(`fetch ${timedOut ? 'timed out' : 'failed'} after ${elapsed} ms: ${detail}`);
    console.warn(`[ota] GET ${url} ${timedOut ? 'timed out' : 'failed'} via fetch after ${elapsed} ms (${detail}); retrying via XHR`);
  }

  const startedXhr = Date.now();
  try {
    const r = await withTimeout(getTextXhr(url, xhrTimeoutMs), xhrTimeoutMs + 500, 'XHR');
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

let lastCheckedPatches: Record<string, string> | null = null;

/** Fetches and validates the OTA manifest. Tries the laptop mirror over Tailscale first (fast when Tailscale is on),
 *  then falls back to GitHub Releases. Every step has a hard timeout so it can never hang. */
export async function checkForOta(): Promise<OtaCheck | null> {
  const o = ota();
  if (!o) return null;
  const blacklist = await o.mod.getBlacklistedVersions().catch(() => [] as string[]);
  const current = o.manager.getVersion() ?? bundledOtaVersion();
  const target = binaryTarget();

  // 1. Try laptop mirror first (over tailnet).
  const mirror = mirrorOtaUrls();
  let mirrorErr: string | null = null;
  try {
    const r = await fetchTextOnce(mirror.versionUrl, 5000);
    const parsed = parseOtaManifest(r.status, r.body, target, current, blacklist);
    if (parsed.ok) {
      lastCheckedPatches = parsed.patches ?? null;
      return {
        hasUpdate: parsed.hasUpdate,
        isCompatible: parsed.isCompatible,
        remoteVersion: parsed.remoteVersion,
        ...(parsed.releaseNotes ? { metadata: { releaseNotes: parsed.releaseNotes } } : {}),
      };
    }
    mirrorErr = parsed.error;
    console.warn(`[ota] laptop mirror manifest unusable (${parsed.error}); falling back to GitHub`);
  } catch (e) {
    mirrorErr = (e as Error).message;
    console.warn(`[ota] laptop mirror check failed (${mirrorErr}); falling back to GitHub`);
  }

  // 2. Fallback to GitHub Releases.
  try {
    const [status, body] = await getText(OTA_URLS.versionUrl, 'updates');
    const r = parseOtaManifest(status, body, target, current, blacklist);
    if (!r.ok) throw new Error(r.error);
    lastCheckedPatches = r.patches ?? null;
    return {
      hasUpdate: r.hasUpdate,
      isCompatible: r.isCompatible,
      remoteVersion: r.remoteVersion,
      ...(r.releaseNotes ? { metadata: { releaseNotes: r.releaseNotes } } : {}),
    };
  } catch (e) {
    const ghErr = (e as Error).message;
    throw new Error(mirrorErr ? `${ghErr} (laptop mirror: ${mirrorErr})` : ghErr);
  }
}

const DOWNLOAD_TIMEOUT_MS = 45_000;
const PATCH_FILENAME_RE = /^patch-\d+-to-\d+\.zip$/;

function resolvePatchUrls(patchRef: string): { mirrorUrl: string | null; githubUrl: string } {
  if (/^https:\/\//.test(patchRef)) {
    const baseName = patchRef.split('/').pop() ?? '';
    return {
      mirrorUrl: PATCH_FILENAME_RE.test(baseName) ? `${serverUrl()}/pc/api/ota/${baseName}` : null,
      githubUrl: patchRef,
    };
  }
  return {
    mirrorUrl: PATCH_FILENAME_RE.test(patchRef) ? `${serverUrl()}/pc/api/ota/${patchRef}` : null,
    githubUrl: `${RELEASES}/download/${OTA_TAG}/${patchRef}`,
  };
}

/** Download + unzip (patch first when an installed OTA bundle matches the manifest's patches map,
 *  mirror-first with GitHub fallback, seamlessly falling back to full bundle.zip on any patch error),
 *  then verify the laptop's ECDSA-P256 signature before anything uses it. */
export async function downloadOta(onProgress?: (received: number, total: number) => void): Promise<void> {
  const o = ota();
  if (!o) throw new Error('OTA is unavailable in this build');

  // 1. Differential bsdiff patch path: only when a previous OTA bundle is already installed on disk
  //    (never on the first OTA after a fresh APK) and the manifest advertises a patch for it.
  const installedVersion = o.manager.getVersion();
  const installedPath = o.manager.getUnzippedPath();
  const patchRef = installedVersion && installedPath && o.mod.isPatchSupported() ? lastCheckedPatches?.[installedVersion] : undefined;
  if (patchRef) {
    const { mirrorUrl, githubUrl } = resolvePatchUrls(patchRef);
    let patched = false;
    if (mirrorUrl) {
      try {
        await withTimeout(o.mod.downloadZipFromUrl(mirrorUrl, onProgress), DOWNLOAD_TIMEOUT_MS, 'Laptop OTA patch download');
        patched = true;
      } catch (e) {
        console.warn(`[ota] laptop mirror patch failed (${(e as Error).message}); trying GitHub patch`);
      }
    }
    if (!patched) {
      try {
        await withTimeout(o.mod.downloadZipFromUrl(githubUrl, onProgress), DOWNLOAD_TIMEOUT_MS, 'GitHub OTA patch download');
        patched = true;
      } catch (e) {
        console.warn(`[ota] GitHub patch failed (${(e as Error).message}); falling back to full bundle`);
      }
    }
    if (patched) {
      const patchedPath = o.manager.getUnzippedPath();
      const problem = patchedPath && PcNative ? PcNative.otaCheck(patchedPath) : 'no bundle path';
      if (!problem) return;
      console.warn(`[ota] patched bundle refused (${problem}); falling back to full bundle`);
    }
  }

  // 2. Full bundle download: mirror-first + GitHub fallback, each under a hard timeout.
  //    Bypass OTAUpdateManager.downloadUpdate() because its internal findPatchUrl() has no timeout.
  const mirror = mirrorOtaUrls();
  let mirrorErr: string | null = null;
  try {
    await withTimeout(o.mod.downloadZipFromUrl(mirror.downloadUrl, onProgress), DOWNLOAD_TIMEOUT_MS, 'Laptop OTA download');
  } catch (e) {
    mirrorErr = (e as Error).message;
    console.warn(`[ota] laptop mirror download failed (${mirrorErr}); falling back to GitHub`);
    try {
      await withTimeout(o.mod.downloadZipFromUrl(OTA_URLS.downloadUrl, onProgress), DOWNLOAD_TIMEOUT_MS, 'GitHub OTA download');
    } catch (ghErr) {
      throw new Error(`OTA download failed: ${(ghErr as Error).message} (laptop mirror: ${mirrorErr})`);
    }
  }

  // Signature verification stays strictly identical regardless of transport (mirror vs GitHub).
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
