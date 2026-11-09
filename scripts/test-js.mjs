// Runs the pure-TS unit tests (src/**/*.test.ts) with node --test: transpiles each test and the local modules it
// imports with the repo's typescript into a temp dir (this node has no built-in TypeScript support).
import { spawnSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const ts = createRequire(path.join(root, 'node_modules/'))('typescript');
const out = fs.mkdtempSync(path.join(os.tmpdir(), 'pcc-test-'));
const tests = [];
const done = new Set();
const emit = (file) => {
  if (done.has(file)) return;
  done.add(file);
  let js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, verbatimModuleSyntax: false },
  }).outputText;
  js = js.replace(/from '(\.\.?\/[^']+)'/g, (_, rel) => {
    const dep = path.resolve(path.dirname(file), rel + '.ts');
    if (fs.existsSync(dep)) emit(dep);
    return `from '${rel}.mjs'`;
  });
  const dest = path.join(out, path.relative(root, file)).replace(/\.ts$/, '.mjs');
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, js);
  if (file.endsWith('.test.ts')) tests.push(dest);
};
const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
  const p = path.join(d, e.name);
  if (e.isDirectory()) walk(p);
  else if (e.name.endsWith('.test.ts')) emit(p);
});
walk(path.join(root, 'src'));
const r = spawnSync(process.execPath, ['--test', ...tests], { stdio: 'inherit' });
fs.rmSync(out, { recursive: true, force: true });
process.exit(r.status ?? 1);
