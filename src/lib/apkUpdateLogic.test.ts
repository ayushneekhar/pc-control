import assert from 'node:assert/strict';
import test from 'node:test';
import { fetchLatestApk, resolveApkDownloadUrl } from './apkUpdateLogic';

const GITHUB_VERSION_URL = 'https://github.com/ayushneekhar/pc-control/releases/latest/download/version.json';
const GITHUB_APK_URL = 'https://github.com/ayushneekhar/pc-control/releases/latest/download/pc-control.apk';
const MIRROR_VERSION_URL = 'https://laptop.tailnet.ts.net/pc/api/ota/apk/version.json';
const MIRROR_APK_URL = 'https://laptop.tailnet.ts.net/pc/api/ota/apk/pc-control.apk';

const VALID_APK_BODY = JSON.stringify({
  versionName: '1.6.8',
  versionCode: 28,
  sha256: 'abc123',
  size: 42000000,
  otaVersion: 61,
});

test('resolveApkDownloadUrl defaults to GitHub Releases and only uses mirror on fallback', () => {
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, false), GITHUB_APK_URL);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, null, false), GITHUB_APK_URL);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, true), MIRROR_APK_URL);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, null, true), GITHUB_APK_URL);
});

test('fetchLatestApk tries GitHub first and does not touch the mirror when GitHub succeeds', async () => {
  const calls: string[] = [];
  const res = await fetchLatestApk({
    githubVersionUrl: GITHUB_VERSION_URL,
    mirrorVersionUrl: MIRROR_VERSION_URL,
    fetchGithub: async (url) => {
      calls.push(`github:${url}`);
      return [200, VALID_APK_BODY];
    },
    fetchMirror: async (url) => {
      calls.push(`mirror:${url}`);
      throw new Error('mirror should not be called');
    },
  });

  assert.deepEqual(calls, [`github:${GITHUB_VERSION_URL}`]);
  assert.equal(res.fromMirror, false);
  assert.equal(res.release.versionName, '1.6.8');
  assert.equal(res.release.versionCode, 28);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, res.fromMirror), GITHUB_APK_URL);
});

test('fetchLatestApk falls back to the Tailnet mirror when GitHub throws', async () => {
  const calls: string[] = [];
  const warnings: string[] = [];
  const res = await fetchLatestApk({
    githubVersionUrl: GITHUB_VERSION_URL,
    mirrorVersionUrl: MIRROR_VERSION_URL,
    fetchGithub: async (url) => {
      calls.push(`github:${url}`);
      throw new Error('network timeout');
    },
    fetchMirror: async (url) => {
      calls.push(`mirror:${url}`);
      return { status: 200, body: VALID_APK_BODY };
    },
    warn: (msg) => warnings.push(msg),
  });

  assert.deepEqual(calls, [`github:${GITHUB_VERSION_URL}`, `mirror:${MIRROR_VERSION_URL}`]);
  assert.equal(res.fromMirror, true);
  assert.equal(res.release.versionName, '1.6.8');
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, res.fromMirror), MIRROR_APK_URL);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /falling back to laptop mirror/i);
});

test('fetchLatestApk falls back to the Tailnet mirror when GitHub returns non-2xx or invalid JSON', async () => {
  const calls: string[] = [];
  const res = await fetchLatestApk({
    githubVersionUrl: GITHUB_VERSION_URL,
    mirrorVersionUrl: MIRROR_VERSION_URL,
    fetchGithub: async (url) => {
      calls.push(`github:${url}`);
      return [503, 'Service Unavailable'];
    },
    fetchMirror: async (url) => {
      calls.push(`mirror:${url}`);
      return { status: 200, body: VALID_APK_BODY };
    },
  });

  assert.deepEqual(calls, [`github:${GITHUB_VERSION_URL}`, `mirror:${MIRROR_VERSION_URL}`]);
  assert.equal(res.fromMirror, true);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, res.fromMirror), MIRROR_APK_URL);
});

test('fetchLatestApk switches back to GitHub when a subsequent check succeeds on GitHub', async () => {
  let githubDown = true;
  let lastApkFromMirror = false;

  const first = await fetchLatestApk({
    githubVersionUrl: GITHUB_VERSION_URL,
    mirrorVersionUrl: MIRROR_VERSION_URL,
    fetchGithub: async () => {
      if (githubDown) throw new Error('offline');
      return [200, VALID_APK_BODY];
    },
    fetchMirror: async () => ({ status: 200, body: VALID_APK_BODY }),
  });
  lastApkFromMirror = first.fromMirror;
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, lastApkFromMirror), MIRROR_APK_URL);

  githubDown = false;
  const second = await fetchLatestApk({
    githubVersionUrl: GITHUB_VERSION_URL,
    mirrorVersionUrl: MIRROR_VERSION_URL,
    fetchGithub: async () => [200, VALID_APK_BODY],
    fetchMirror: async () => {
      throw new Error('mirror should not be called');
    },
  });
  lastApkFromMirror = second.fromMirror;
  assert.equal(second.fromMirror, false);
  assert.equal(resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, lastApkFromMirror), GITHUB_APK_URL);
});

test('fetchLatestApk throws combined error when both GitHub and mirror fail', async () => {
  await assert.rejects(
    () =>
      fetchLatestApk({
        githubVersionUrl: GITHUB_VERSION_URL,
        mirrorVersionUrl: MIRROR_VERSION_URL,
        fetchGithub: async () => {
          throw new Error('GitHub unreachable');
        },
        fetchMirror: async () => {
          throw new Error('Tailnet offline');
        },
      }),
    /GitHub unreachable \(laptop mirror: Tailnet offline\)/,
  );
});

test('fetchLatestApk throws GitHub error alone when no mirror is configured', async () => {
  await assert.rejects(
    () =>
      fetchLatestApk({
        githubVersionUrl: GITHUB_VERSION_URL,
        mirrorVersionUrl: null,
        fetchGithub: async () => {
          throw new Error('GitHub unreachable');
        },
        fetchMirror: async () => {
          throw new Error('should not be called');
        },
      }),
    { message: /^GitHub unreachable$/ },
  );
});
