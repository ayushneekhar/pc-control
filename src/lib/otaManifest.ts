// Pure parsing/validation of the update manifests (no React Native imports, so server/tests can run it in Node).
// Anything that isn't a 2xx JSON body with the expected fields is an error with one short message; it is never
// treated as a version. (react-native-nitro-ota's own check takes a non-JSON body, e.g. GitHub's
// "504 Gateway Time-out" HTML page, as the version string, so we don't use it for checking.)

export type OtaManifestResult =
  | { ok: true; remoteVersion: string; isCompatible: boolean; hasUpdate: boolean; releaseNotes?: string }
  | { ok: false; error: string };

export type ApkRelease = { versionName: string; versionCode: number; sha256: string; size: number; otaVersion?: number };
export type ApkResult = { ok: true; release: ApkRelease } | { ok: false; error: string };

export function httpProblem(what: string, status: number): string {
  return `Couldn't check for ${what} (HTTP ${status})`;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/**
 * @param status   HTTP status of the manifest fetch
 * @param text     response body
 * @param target   this build, "<version>+<versionCode>"
 * @param current  OTA version running now (downloaded bundle or the APK's embedded JS), or null
 * @param blacklist versions nitro rolled back from; never offered again
 */
export function parseOtaManifest(status: number, text: string, target: string, current: string | null, blacklist: string[] = []): OtaManifestResult {
  if (status < 200 || status >= 300) return { ok: false, error: httpProblem('updates', status) };
  const j = parseJson(text);
  if (!isObj(j)) return { ok: false, error: "Couldn't check for updates (not a valid update manifest)" };
  const v = typeof j.version === 'number' ? String(j.version) : typeof j.version === 'string' ? j.version.trim() : '';
  if (!/^\d{1,9}$/.test(v)) return { ok: false, error: "Couldn't check for updates (manifest has no valid version)" };
  const tv = isObj(j.targetVersions) ? j.targetVersions.android : undefined;
  if (!Array.isArray(tv) || !tv.every((x) => typeof x === 'string'))
    return { ok: false, error: "Couldn't check for updates (manifest has no target builds)" };
  const isCompatible = tv.includes(target);
  const cur = current && /^\d+$/.test(current.trim()) ? Number(current.trim()) : null;
  const hasUpdate = isCompatible && !blacklist.includes(v) && (cur === null || Number(v) > cur);
  const notes = typeof j.releaseNotes === 'string' && j.releaseNotes.trim() ? j.releaseNotes.trim().slice(0, 300) : undefined;
  return { ok: true, remoteVersion: v, isCompatible, hasUpdate, ...(notes ? { releaseNotes: notes } : {}) };
}

export function parseApkVersion(status: number, text: string): ApkResult {
  if (status < 200 || status >= 300) return { ok: false, error: httpProblem('app releases', status) };
  const j = parseJson(text);
  if (!isObj(j)) return { ok: false, error: "Couldn't check for app releases (not a valid version file)" };
  const { versionName, versionCode, sha256, size } = j;
  if (typeof versionName !== 'string' || !/^\d+\.\d+\.\d+$/.test(versionName) || !Number.isInteger(versionCode) || (versionCode as number) < 1)
    return { ok: false, error: "Couldn't check for app releases (version file has no valid version)" };
  return {
    ok: true,
    release: {
      versionName,
      versionCode: versionCode as number,
      sha256: typeof sha256 === 'string' ? sha256 : '',
      size: typeof size === 'number' ? size : 0,
      ...(typeof j.otaVersion === 'number' ? { otaVersion: j.otaVersion } : {}),
    },
  };
}
