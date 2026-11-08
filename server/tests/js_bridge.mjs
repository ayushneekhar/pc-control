// Runs the app's own payload code (src/lib/rootPayload.ts) for the Python tests.
// stdin: {"argvs": [[...]], "ders": ["base64"], "payloads": [{"decision":..,"req":{..}}], "safe": [[...]]}
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ts = createRequire(join(root, 'package.json'))('typescript');
const src = readFileSync(join(root, 'src', 'lib', 'rootPayload.ts'), 'utf8');
const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const dir = mkdtempSync(join(tmpdir(), 'rootpayload-'));
writeFileSync(join(dir, 'rootPayload.mjs'), js);
const { canonicalArgv, argvHash, buildPayload, keyFingerprint, argvLooksSafe } = await import(pathToFileURL(join(dir, 'rootPayload.mjs')).href);
rmSync(dir, { recursive: true });

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  const q = JSON.parse(input);
  const out = {
    argvs: (q.argvs || []).map((a) => ({ canon: canonicalArgv(a), hash: argvHash(a) })),
    ders: (q.ders || []).map((d) => keyFingerprint(d)),
    payloads: (q.payloads || []).map((p) => {
      try { return buildPayload(p.decision, p.req); } catch (e) { return { error: String(e.message) }; }
    }),
    safe: (q.safe || []).map((a) => argvLooksSafe(a)),
  };
  process.stdout.write(JSON.stringify(out));
});
