// Runs src/lib/otaManifest.ts (the app's update-manifest validation) through Node with assertions.
// Called by test_ota_manifest.py; exit code 0 = all cases pass.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ts = createRequire(join(root, 'package.json'))('typescript');
const src = readFileSync(join(root, 'src', 'lib', 'otaManifest.ts'), 'utf8');
const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const dir = mkdtempSync(join(tmpdir(), 'otamanifest-'));
writeFileSync(join(dir, 'm.mjs'), js);
const { parseOtaManifest: ota, parseApkVersion: apk } = await import(pathToFileURL(join(dir, 'm.mjs')).href);
rmSync(dir, { recursive: true });

const T = '1.2.0+4';
const good = (v, targets = [T], extra = {}) => JSON.stringify({ version: v, isSemver: false, targetVersions: { android: targets }, ...extra });
const HTML504 = "<html><body><h1>504 Gateway Time-out</h1>The server didn't respond in time.</body></html>";

// GitHub error pages / non-2xx are never versions, whatever the body
assert.deepEqual(ota(504, HTML504, T, '1'), { ok: false, error: "Couldn't check for updates (HTTP 504)" });
assert.deepEqual(ota(404, 'Not Found', T, '1'), { ok: false, error: "Couldn't check for updates (HTTP 404)" });
assert.deepEqual(ota(500, good('9'), T, '1'), { ok: false, error: "Couldn't check for updates (HTTP 500)" });
// 200 but not a manifest
for (const body of [HTML504, '', 'null', '[]', '"3"', '{}', good('<html>'), good('1.2'), good(''), good('9', 'x'),
  JSON.stringify({ version: '9' }), good('9', [4])]) {
  const r = ota(200, body, T, '1');
  assert.equal(r.ok, false, body);
  assert.match(r.error, /^Couldn't check for updates \(/, body);
}
// valid
assert.deepEqual(ota(200, good('2', [T], { releaseNotes: ' fix ' }), T, '1'),
  { ok: true, remoteVersion: '2', isCompatible: true, hasUpdate: true, releaseNotes: 'fix' });
assert.equal(ota(200, good(3), T, '1').hasUpdate, true);            // numeric version is fine
assert.equal(ota(200, good('2'), T, '2').hasUpdate, false);         // same version
assert.equal(ota(200, good('2'), T, '5').hasUpdate, false);         // older
assert.equal(ota(200, good('2'), T, null).hasUpdate, true);         // nothing known about the running JS
const other = ota(200, good('9', ['1.2.0+5']), T, '1');             // built for another native build
assert.equal(other.isCompatible, false);
assert.equal(other.hasUpdate, false);
assert.equal(ota(200, good('9'), T, '1', ['9']).hasUpdate, false);  // rolled back before

// APK version.json
assert.deepEqual(apk(404, 'Not Found'), { ok: false, error: "Couldn't check for app releases (HTTP 404)" });
assert.equal(apk(200, HTML504).ok, false);
assert.equal(apk(200, '{"versionName":"1.2","versionCode":5}').ok, false);
assert.equal(apk(200, '{"versionName":"1.2.1","versionCode":"5"}').ok, false);
assert.deepEqual(apk(200, '{"versionName":"1.2.1","versionCode":5,"sha256":"ab","size":9,"otaVersion":3}'),
  { ok: true, release: { versionName: '1.2.1', versionCode: 5, sha256: 'ab', size: 9, otaVersion: 3 } });
console.log('ota manifest checks ok');
