import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeDeepLinkPath } from './linking';

test('normalizeDeepLinkPath maps legacy vitals and health paths to system tabs', () => {
  assert.equal(normalizeDeepLinkPath('vitals'), 'system/vitals');
  assert.equal(normalizeDeepLinkPath('/vitals'), '/system/vitals');
  assert.equal(normalizeDeepLinkPath('vitals?foo=1'), 'system/vitals?foo=1');
  assert.equal(normalizeDeepLinkPath('health'), 'system/services');
  assert.equal(normalizeDeepLinkPath('/health'), '/system/services');
  assert.equal(normalizeDeepLinkPath('health?from=notif'), 'system/services?from=notif');
  assert.equal(normalizeDeepLinkPath('services'), 'system/services');
});

test('normalizeDeepLinkPath leaves other paths untouched', () => {
  assert.equal(normalizeDeepLinkPath('routes'), 'routes');
  assert.equal(normalizeDeepLinkPath('chat'), 'chat');
  assert.equal(normalizeDeepLinkPath('system/vitals'), 'system/vitals');
  assert.equal(normalizeDeepLinkPath('system/services'), 'system/services');
  assert.equal(normalizeDeepLinkPath('root/abc-123'), 'root/abc-123');
  assert.equal(normalizeDeepLinkPath('cams?tab=replay'), 'cams?tab=replay');
  assert.equal(normalizeDeepLinkPath(''), '');
});
