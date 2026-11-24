// Builds a signed Nitro OTA snapshot for the exact native build in app.json. Adapted from Apex's
// scripts/ota/build-nitro-ota.mjs, plus a signature (react-native-nitro-ota has none of its own):
//   ota.manifest = "pc-control-ota-v1\nversion <ota>\ntarget <version>+<versionCode>\n<sha256> <path>\n..." (every file)
//   ota.sig      = base64 DER ECDSA-P256-SHA256 over ota.manifest, with ~/.config/pc-control/ota-signing-key.pem
// The app verifies both natively (modules/pc-native/.../OtaVerifier.kt) before it ever loads the bundle.
//
// Usage:
//   node scripts/ota/build-nitro-ota.mjs --ota-version <n> [--release-notes <text>] [--output-dir <dir>]
//     [--previous-zip <bundle.zip> | --previous-bundle <index.android.bundle>] [--previous-version <n>]
//     [--reset-cache]
// Output:
//   <dir>/snapshot/ (the files), <dir>/bundle.zip, <dir>/ota.version.json,
//   and optionally <dir>/patch-<from>-to-<to>.zip (NITROBSD bsdiff patch)
import { Buffer } from 'node:buffer';
import { spawnSync } from 'node:child_process';
import { createHash, sign } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const k = process.argv[i];
  if (!k.startsWith('--')) continue;
  const v = process.argv[i + 1];
  if (!v || v.startsWith('--')) args[k.slice(2)] = 'true';
  else {
    args[k.slice(2)] = v;
    i++;
  }
}
const otaVersion = String(args['ota-version'] ?? '').trim();
if (!/^\d+$/.test(otaVersion)) {
  console.error(
    'Usage: node scripts/ota/build-nitro-ota.mjs --ota-version <n> [--release-notes <text>] [--output-dir <dir>] [--previous-zip <zip> --previous-version <n>] [--reset-cache]',
  );
  process.exit(1);
}
const root = process.cwd();
const app = JSON.parse(readFileSync(resolve(root, 'app.json'), 'utf8')).expo;
// Exact native build. Must match OtaVerifier.binaryTarget() and src/lib/ota.ts binaryTarget().
const target = `${app.version}+${app.android.versionCode}`;
const keyPath = process.env.PCC_OTA_KEY || join(homedir(), '.config', 'pc-control', 'ota-signing-key.pem');
if (!existsSync(keyPath)) {
  console.error(`Missing OTA signing key ${keyPath} (see README: OTA updates)`);
  process.exit(1);
}
const outDir = resolve(root, args['output-dir'] || '.artifacts/nitro-ota/android');
const snap = join(outDir, 'snapshot');
rmSync(outDir, { recursive: true, force: true });
mkdirSync(snap, { recursive: true });

console.log(`Building OTA ${otaVersion} for ${target}...`);
const embedArgs = [
  require.resolve('expo/bin/cli'),
  'export:embed',
  '--platform',
  'android',
  '--dev',
  'false',
  ...(args['reset-cache'] === 'true' ? ['--reset-cache'] : []),
  '--bundle-output',
  join(snap, 'index.android.bundle'),
  '--assets-dest',
  snap,
];
const r = spawnSync(process.execPath, embedArgs, {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'production', EXPO_PUBLIC_BUNDLED_OTA_VERSION: otaVersion },
});
if (r.status !== 0) process.exit(r.status ?? 1);
if (!existsSync(join(snap, 'index.android.bundle'))) {
  console.error('export:embed produced no bundle');
  process.exit(1);
}

// Fonts prebundled natively in the APK (plugins/withBuildOptimizations.js -> assets/fonts/material-community.ttf)
// do not need to be re-shipped inside every OTA bundle.zip.
const PREBUNDLED_FONT_PATTERNS = [/materialcommunityicons\.ttf$/i];
const prunePrebundledFonts = (dir) => {
  let removed = 0;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      removed += prunePrebundledFonts(p);
      if (readdirSync(p).length === 0) rmdirSync(p);
    } else if (PREBUNDLED_FONT_PATTERNS.some((re) => re.test(name))) {
      rmSync(p);
      removed += 1;
    }
  }
  return removed;
};
const prunedFonts = prunePrebundledFonts(snap);
if (prunedFonts > 0) {
  console.log(`Excluded ${prunedFonts} APK-prebundled font asset(s) from OTA snapshot`);
}

// Optional bsdiff differential patch against the previous OTA bundle for the same native build.
const prevVersion = String(args['previous-version'] ?? '').trim();
const prevZip = typeof args['previous-zip'] === 'string' && args['previous-zip'] !== 'true' ? resolve(root, args['previous-zip']) : null;
const prevBundleArg =
  typeof args['previous-bundle'] === 'string' && args['previous-bundle'] !== 'true' ? resolve(root, args['previous-bundle']) : null;

let patchInfo = null;
if (prevVersion && /^\d+$/.test(prevVersion) && prevVersion !== otaVersion && (prevZip || prevBundleArg)) {
  let oldBundleBytes = null;
  if (prevBundleArg && existsSync(prevBundleArg)) {
    oldBundleBytes = new Uint8Array(readFileSync(prevBundleArg));
  } else if (prevZip && existsSync(prevZip)) {
    const uz = spawnSync('unzip', ['-p', prevZip, 'index.android.bundle'], { maxBuffer: 256 * 1024 * 1024 });
    if (uz.status === 0 && uz.stdout && uz.stdout.length > 0) {
      oldBundleBytes = new Uint8Array(uz.stdout);
    } else {
      console.warn(`Warning: could not extract index.android.bundle from ${prevZip}; skipping bsdiff patch`);
    }
  }
  if (oldBundleBytes) {
    const bsdiffPath = resolve(root, 'node_modules/react-native-nitro-ota/lib/module/bsdiff.js');
    const { bsdiff, bspatch } = await import(pathToFileURL(bsdiffPath).href);
    const newBundleBytes = new Uint8Array(readFileSync(join(snap, 'index.android.bundle')));
    const patchBytes = bsdiff(oldBundleBytes, newBundleBytes);
    const rebuilt = bspatch(oldBundleBytes, patchBytes);
    if (Buffer.compare(rebuilt, newBundleBytes) !== 0) {
      throw new Error(`bsdiff self-verification failed for patch ${prevVersion} -> ${otaVersion}`);
    }
    const patchName = `patch-${prevVersion}-to-${otaVersion}.zip`;
    patchInfo = { fromVersion: prevVersion, patchName, patchBytes };
    console.log(
      `Generated & verified bsdiff patch ${prevVersion} -> ${otaVersion} (${patchBytes.length} raw bytes vs ${newBundleBytes.length} bundle bytes)`,
    );
  }
}

const notes = typeof args['release-notes'] === 'string' && args['release-notes'] !== 'true' ? args['release-notes'].trim() : '';
const manifestJson = {
  version: otaVersion,
  isSemver: false,
  targetVersions: { android: [target] },
  ...(notes ? { releaseNotes: notes } : {}),
  ...(patchInfo ? { patches: { [patchInfo.fromVersion]: patchInfo.patchName } } : {}),
};
writeFileSync(join(snap, 'ota.version'), otaVersion + '\n');
writeFileSync(join(snap, 'ota.version.json'), JSON.stringify(manifestJson, null, 2) + '\n');
writeFileSync(
  join(snap, 'ota.meta.json'),
  JSON.stringify(
    { platform: 'android', appVersion: target, otaVersion, bundleName: 'index.android.bundle', generatedAt: new Date().toISOString() },
    null,
    2,
  ) + '\n',
);

const files = [];
const walk = (d) => {
  for (const n of readdirSync(d)) {
    const p = join(d, n);
    if (statSync(p).isDirectory()) walk(p);
    else files.push(relative(snap, p).split(sep).join('/'));
  }
};
walk(snap);
files.sort();
for (const f of files) {
  if (!/^[\w.\-/@+]+$/.test(f)) {
    console.error(`Unexpected characters in asset path: ${f}`);
    process.exit(1);
  }
}
const lines = [
  'pc-control-ota-v1',
  `version ${otaVersion}`,
  `target ${target}`,
  ...files.map((f) => `${createHash('sha256').update(readFileSync(join(snap, f))).digest('hex')} ${f}`),
];
const manifest = Buffer.from(lines.join('\n') + '\n');
writeFileSync(join(snap, 'ota.manifest'), manifest);
writeFileSync(join(snap, 'ota.sig'), sign('sha256', manifest, readFileSync(keyPath)).toString('base64') + '\n');

const bundleZipPath = join(outDir, 'bundle.zip');
const z = spawnSync('zip', ['-9', '-q', '-r', '-X', bundleZipPath, '.'], { cwd: snap, stdio: 'inherit' });
if (z.status !== 0) process.exit(z.status ?? 1);

if (patchInfo) {
  const patchSnap = join(outDir, 'patch-snapshot');
  rmSync(patchSnap, { recursive: true, force: true });
  mkdirSync(patchSnap, { recursive: true });
  const allSnapFiles = [...files, 'ota.manifest', 'ota.sig'];
  for (const rel of allSnapFiles) {
    if (rel === 'index.android.bundle') continue;
    const dst = join(patchSnap, rel);
    mkdirSync(dirname(dst), { recursive: true });
    copyFileSync(join(snap, rel), dst);
  }
  writeFileSync(join(patchSnap, 'index.android.bundle.patch'), patchInfo.patchBytes);
  const patchZipPath = join(outDir, patchInfo.patchName);
  const zp = spawnSync('zip', ['-9', '-q', '-r', '-X', patchZipPath, '.'], { cwd: patchSnap, stdio: 'inherit' });
  rmSync(patchSnap, { recursive: true, force: true });
  if (zp.status !== 0) process.exit(zp.status ?? 1);
  console.log(`Patch zip: ${patchZipPath} (${statSync(patchZipPath).size} bytes)`);
}

writeFileSync(join(outDir, 'ota.version.json'), JSON.stringify(manifestJson, null, 2) + '\n');
console.log(`OTA ${otaVersion} for ${target}: ${files.length} files signed -> ${bundleZipPath} (${statSync(bundleZipPath).size} bytes)`);
