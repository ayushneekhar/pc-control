// Smart native-change detector for scripts/ota-publish.sh.
// Compares the working tree against a release tag (e.g. v1.6.5) and exits 1 with reasons on stderr
// only if native Android code, native Expo config, or a dependency with native Android code changed.
// Pure-JS dependency additions/updates (e.g. @legendapp/list) and devDependencies changes are allowed.
//
// Usage: node scripts/ota/check-native-changes.mjs --base v1.6.5
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const ALWAYS_NATIVE = [
  'android',
  'modules/pc-native/android',
  'modules/pc-native/expo-module.config.json',
  'plugins',
  'app.config.js',
];

const NATIVE_MARKERS = [
  { name: 'android', dir: true },
  { name: 'expo-module.config.json', dir: false },
  { name: 'react-native.config.js', dir: false },
  { name: 'nitro.json', dir: false },
];

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const k = process.argv[i];
  if (k.startsWith('--') && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')) {
    args[k.slice(2)] = process.argv[++i];
  }
}

const base = args.base;
if (!base) {
  console.error('Usage: node scripts/ota/check-native-changes.mjs --base <ref>');
  process.exit(2);
}

const root = process.cwd();

function git(gitArgs) {
  return execFileSync('git', gitArgs, { cwd: root, encoding: 'utf8' });
}

function gitShow(refPath) {
  try {
    return git(['show', refPath]);
  } catch {
    return null;
  }
}

function listChanged(paths) {
  const tracked = git(['diff', '--name-only', base, '--', ...paths]).trim().split('\n').filter(Boolean);
  const untracked = git(['ls-files', '--others', '--exclude-standard', '--', ...paths]).trim().split('\n').filter(Boolean);
  return [...new Set([...tracked, ...untracked])].sort();
}

function stripNonNativeAppJson(raw) {
  const parsed = JSON.parse(raw);
  if (parsed && typeof parsed === 'object' && parsed.expo && typeof parsed.expo === 'object') {
    const expo = { ...parsed.expo };
    delete expo.extra;
    return JSON.stringify({ ...parsed, expo });
  }
  return JSON.stringify(parsed);
}

/** Extracts `pkg@spec -> resolvedVersion` map from yarn.lock v1 format. */
function parseYarnLockVersions(lockText) {
  const versions = new Map();
  if (!lockText) return versions;
  let keys = [];
  for (const line of lockText.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    if (!line.startsWith(' ')) {
      keys = line
        .replace(/:$/, '')
        .split(',')
        .map((s) => s.trim().replace(/^"|"$/g, ''))
        .filter(Boolean);
    } else if (keys.length > 0) {
      const m = line.match(/^\s+version\s+"([^"]+)"/);
      if (m) {
        for (const k of keys) versions.set(k, m[1]);
        keys = [];
      }
    }
  }
  return versions;
}

function findNativeMarker(pkgName) {
  const pkgDir = resolve(root, 'node_modules', pkgName);
  if (!existsSync(pkgDir)) return null;
  for (const marker of NATIVE_MARKERS) {
    const p = join(pkgDir, marker.name);
    if (!existsSync(p)) continue;
    const isDir = statSync(p).isDirectory();
    if (marker.dir ? isDir : !isDir) return marker.dir ? `${marker.name}/` : marker.name;
  }
  return false;
}

const reasons = [];

// 1. Always-native files & directories
for (const f of listChanged(ALWAYS_NATIVE)) {
  reasons.push(f);
}

// 2. Native fields in app.json
if (listChanged(['app.json']).length > 0) {
  const oldRaw = gitShow(`${base}:app.json`);
  const newRaw = readFileSync(resolve(root, 'app.json'), 'utf8');
  if (oldRaw === null || stripNonNativeAppJson(oldRaw) !== stripNonNativeAppJson(newRaw)) {
    reasons.push('app.json (native fields changed)');
  }
}

// 3. Dependencies in package.json / yarn.lock
if (listChanged(['package.json', 'yarn.lock']).length > 0) {
  const oldPkgRaw = gitShow(`${base}:package.json`);
  const newPkgRaw = readFileSync(resolve(root, 'package.json'), 'utf8');
  const oldDeps = oldPkgRaw ? (JSON.parse(oldPkgRaw).dependencies ?? {}) : {};
  const newDeps = JSON.parse(newPkgRaw).dependencies ?? {};

  const oldLock = parseYarnLockVersions(gitShow(`${base}:yarn.lock`));
  const newLock = parseYarnLockVersions(
    existsSync(resolve(root, 'yarn.lock')) ? readFileSync(resolve(root, 'yarn.lock'), 'utf8') : '',
  );

  const allDeps = new Set([...Object.keys(oldDeps), ...Object.keys(newDeps)]);
  for (const name of [...allDeps].sort()) {
    const oldRange = oldDeps[name];
    const newRange = newDeps[name];
    const oldResolved = oldRange ? oldLock.get(`${name}@${oldRange}`) : undefined;
    const newResolved = newRange ? newLock.get(`${name}@${newRange}`) : undefined;

    if (oldRange === newRange && oldResolved === newResolved) continue;

    if (newRange === undefined) {
      const marker = findNativeMarker(name);
      if (marker === false) continue;
      reasons.push(
        marker
          ? `package.json: removed native dependency ${name} (${marker})`
          : `package.json: removed dependency ${name} (not in node_modules; cannot verify native code)`,
      );
      continue;
    }

    const marker = findNativeMarker(name);
    if (marker) {
      const kind = oldRange === undefined ? 'added' : 'updated';
      reasons.push(`package.json: ${kind} native dependency ${name} (${marker})`);
    } else if (marker === null) {
      reasons.push(`package.json: ${name} not installed in node_modules (run yarn install)`);
    }
  }
}

if (reasons.length > 0) {
  console.error(`Native files changed since ${base}, so this needs a new APK (scripts/release-apk.sh --bump):`);
  for (const r of reasons) console.error(`  ${r}`);
  process.exit(1);
}
