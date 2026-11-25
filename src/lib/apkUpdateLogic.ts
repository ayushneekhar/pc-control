// Pure logic for APK release checks and download URL selection (GitHub Releases primary, laptop Tailnet mirror fallback).
// No React Native imports so Node unit tests (`npm run test:js`) can exercise it directly.
import { ApkRelease, parseApkVersion } from './otaManifest';

export type FetchedResponse = { status: number; body: string };

export type FetchLatestApkDeps = {
  githubVersionUrl: string;
  mirrorVersionUrl: string | null;
  fetchGithub: (url: string) => Promise<[number, string]>;
  fetchMirror: (url: string) => Promise<FetchedResponse>;
  warn?: (msg: string) => void;
};

export type FetchLatestApkResult = {
  release: ApkRelease;
  fromMirror: boolean;
};

/** Select the APK download URL: GitHub Releases by default, falling back to the Tailnet mirror only when
 *  the last `latestApk()` check had to fall back to the mirror. */
export function resolveApkDownloadUrl(
  githubApkUrl: string,
  mirrorApkUrl: string | null,
  lastApkFromMirror: boolean,
): string {
  return lastApkFromMirror && mirrorApkUrl ? mirrorApkUrl : githubApkUrl;
}

/** Fetch the newest APK release metadata: try GitHub Releases first, and fall back to the laptop mirror
 *  only if GitHub is unreachable or returns an unusable response. */
export async function fetchLatestApk(deps: FetchLatestApkDeps): Promise<FetchLatestApkResult> {
  const { githubVersionUrl, mirrorVersionUrl, fetchGithub, fetchMirror, warn } = deps;

  // 1. Try GitHub Releases first.
  let ghErr: string;
  try {
    const [status, body] = await fetchGithub(githubVersionUrl);
    const parsed = parseApkVersion(status, body);
    if (parsed.ok) {
      return { release: parsed.release, fromMirror: false };
    }
    ghErr = parsed.error;
    if (mirrorVersionUrl) {
      warn?.(`[ota] GitHub APK version unusable (${ghErr}); falling back to laptop mirror`);
    }
  } catch (e) {
    ghErr = (e as Error).message;
    if (mirrorVersionUrl) {
      warn?.(`[ota] GitHub APK check failed (${ghErr}); falling back to laptop mirror`);
    }
  }

  // 2. Fallback to the laptop Tailnet mirror if configured.
  let mirrorErr: string | null = null;
  if (mirrorVersionUrl) {
    try {
      const r = await fetchMirror(mirrorVersionUrl);
      const parsed = parseApkVersion(r.status, r.body);
      if (parsed.ok) {
        return { release: parsed.release, fromMirror: true };
      }
      mirrorErr = parsed.error;
    } catch (e) {
      mirrorErr = (e as Error).message;
    }
  }

  throw new Error(mirrorErr ? `${ghErr} (laptop mirror: ${mirrorErr})` : ghErr);
}
