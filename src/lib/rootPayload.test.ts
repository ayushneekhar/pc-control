import assert from 'node:assert/strict';
import test from 'node:test';
import {
  argvHash,
  buildPayloadV1,
  buildPayloadV2,
  commandLine,
  hasUserWritablePath,
  isLongCommand,
  LONG_COMMAND_CHARS,
  sha256Hex,
  showArg,
  utf8,
} from './rootPayload';

test('buildPayloadV2 includes timeout, reason sha256, snap_sha256 and argv hash', () => {
  const req = {
    id: '0123456789abcdef',
    nonce: 'a'.repeat(32),
    expires: 1794128000,
    timeout: 120,
    reason: 'restart nginx',
    snap_sha256: 'b'.repeat(64),
    argv: ['/usr/bin/systemctl', 'restart', 'nginx'],
  };
  const p2 = buildPayloadV2('approve', req);
  assert.equal(
    p2,
    [
      'rootd-v2',
      'approve',
      req.id,
      req.nonce,
      String(req.expires),
      '120',
      sha256Hex(utf8('restart nginx')),
      'b'.repeat(64),
      argvHash(req.argv),
    ].join('\n'),
  );
  const p1 = buildPayloadV1('approve', req);
  assert.equal(p1, `rootd-v1\napprove\n${req.id}\n${req.nonce}\n${req.expires}\n${argvHash(req.argv)}`);
});

test('showArg collapses long runs of whitespace into visible markers', () => {
  assert.equal(showArg('a   b'), 'a   b');
  assert.equal(showArg('a' + ' '.repeat(12) + 'b'), 'a[12 spaces]b');
  assert.equal(showArg('x' + '\t'.repeat(5) + 'y'), 'x[5 tabs]y');
  assert.equal(showArg('x' + '\n'.repeat(4) + 'y'), 'x[4 newlines]y');
  assert.equal(showArg('line1\nline2\tend'), 'line1\\nline2\\tend');
});

test('isLongCommand flags commands over 600 chars', () => {
  assert.equal(isLongCommand(['/usr/bin/echo', 'short']), false);
  assert.equal(isLongCommand(['/usr/bin/echo', 'a'.repeat(LONG_COMMAND_CHARS + 10)]), true);
  // 600 spaces collapse to "[600 spaces]", making the hidden tail visible right away
  const padded = commandLine(['/bin/sh', '-c', 'systemctl restart nginx' + ' '.repeat(600) + '; id']);
  assert.ok(padded.includes('[600 spaces]; id'));
});

test('hasUserWritablePath detects /home and /tmp paths', () => {
  assert.equal(hasUserWritablePath(['/usr/bin/systemctl', 'restart', 'nginx']), false);
  assert.equal(hasUserWritablePath(['/home/ayushlive/fix.sh']), true);
  assert.equal(hasUserWritablePath(['/bin/bash', '/tmp/run.sh']), true);
  assert.equal(hasUserWritablePath(['/usr/bin/python3', '--script=/home/ayushlive/x.py']), true);
  assert.equal(hasUserWritablePath(['/usr/bin/ls', '/tmp']), true);
  assert.equal(hasUserWritablePath(['/usr/bin/echo', 'not-a-home-path']), false);
});
