// Builds a signed Nitro OTA snapshot for the exact native build in app.json. Adapted from Apex's
// scripts/ota/build-nitro-ota.mjs, plus a signature (react-native-nitro-ota has none of its own):
//   ota.manifest = "pc-control-ota-v1\nversion <ota>\ntarget <version>+<versionCode>\n<sha256> <path>\n..." (every file)
//   ota.sig      = base64 DER ECDSA-P256-SHA256 over ota.manifest, with ~/.config/pc-control/ota-signing-key.pem
// The app verifies both natively (modules/pc-native/.../OtaVerifier.kt) before it ever loads the bundle.
//
// Usage: node scripts/ota/build-nitro-ota.mjs --ota-version <n> [--release-notes <text>] [--output-dir <dir>]
// Output: <dir>/snapshot/ (the files), <dir>/bundle.zip, <dir>/ota.version.json (manifest uploaded next to the zip)
import { spawnSync } from 'node:child_process';
import { createHash, sign } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { join, relative, resolve, sep } from 'node:path';

const require = createRequire(import.meta.url);
const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const k = process.argv[i];
  if (!k.startsWith('--')) continue;
  const v = process.argv[i + 1];
  if (!v || v.startsWith('--')) args[k.slice(2)] = 'true';
  else (args[k.slice(2)] = v), i++;
}
const otaVersion = String(args['ota-version'] ?? '').trim();
if (!/^\d+$/.test(otaVersion)) {
  console.error('Usage: node scripts/ota/build-nitro-ota.mjs --ota-version <n> [--release-notes <text>] [--output-dir <dir>]');
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
const r = spawnSync(process.execPath, [require.resolve('expo/bin/cli'), 'export:embed', '--platform', 'android', '--dev', 'false',
  '--reset-cache', '--bundle-output', join(snap, 'index.android.bundle'), '--assets-dest', snap], {
  cwd: root, stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'production', EXPO_PUBLIC_BUNDLED_OTA_VERSION: otaVersion },
});
if (r.status !== 0) process.exit(r.status ?? 1);
if (!existsSync(join(snap, 'index.android.bundle'))) {
  console.error('export:embed produced no bundle');
  process.exit(1);
}

const notes = typeof args['release-notes'] === 'string' && args['release-notes'] !== 'true' ? args['release-notes'].trim() : '';
const manifestJson = { version: otaVersion, isSemver: false, targetVersions: { android: [target] }, ...(notes ? { releaseNotes: notes } : {}) };
writeFileSync(join(snap, 'ota.version'), otaVersion + '\n');
writeFileSync(join(snap, 'ota.version.json'), JSON.stringify(manifestJson, null, 2) + '\n');
writeFileSync(join(snap, 'ota.meta.json'), JSON.stringify({ platform: 'android', appVersion: target, otaVersion,
  bundleName: 'index.android.bundle', generatedAt: new Date().toISOString() }, null, 2) + '\n');

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
const lines = ['pc-control-ota-v1', `version ${otaVersion}`, `target ${target}`,
  ...files.map((f) => `${createHash('sha256').update(readFileSync(join(snap, f))).digest('hex')} ${f}`)];
const manifest = Buffer.from(lines.join('\n') + '\n');
writeFileSync(join(snap, 'ota.manifest'), manifest);
writeFileSync(join(snap, 'ota.sig'), sign('sha256', manifest, readFileSync(keyPath)).toString('base64') + '\n');

const z = spawnSync('zip', ['-q', '-r', '-X', join(outDir, 'bundle.zip'), '.'], { cwd: snap, stdio: 'inherit' });
if (z.status !== 0) process.exit(z.status ?? 1);
writeFileSync(join(outDir, 'ota.version.json'), JSON.stringify(manifestJson, null, 2) + '\n');
console.log(`OTA ${otaVersion} for ${target}: ${files.length} files signed -> ${join(outDir, 'bundle.zip')}`);
