import assert from 'node:assert/strict';
import test from 'node:test';
import {
  _clearMemCacheForTests,
  _setCacheBackendForTests,
  CACHE_VERSION,
  CacheBackend,
  cacheClear,
  cacheGet,
  cacheRemove,
  cacheSet,
} from './cache';

function makeFakeBackend(): CacheBackend & { map: Map<string, string> } {
  const map = new Map<string, string>();
  return {
    map,
    getString: (k) => map.get(k),
    set: (k, v) => {
      map.set(k, v);
    },
    remove: (k) => {
      map.delete(k);
    },
    clearAll: () => {
      map.clear();
    },
  };
}

test('cache: round-trips JSON through persistent backend across cold read', () => {
  const b = makeFakeBackend();
  _setCacheBackendForTests(b);
  cacheSet('home:routes', [{ id: '1', name: 'Jellyfin' }]);
  assert.equal(b.map.size, 1);

  _clearMemCacheForTests();
  const got = cacheGet<{ id: string; name: string }[]>('home:routes');
  assert.deepEqual(got, [{ id: '1', name: 'Jellyfin' }]);

  cacheRemove('home:routes');
  _clearMemCacheForTests();
  assert.equal(cacheGet('home:routes'), null);
  _setCacheBackendForTests(undefined);
});

test('cache: rejects entries with mismatched version or corrupt JSON', () => {
  const b = makeFakeBackend();
  _setCacheBackendForTests(b);
  b.map.set(`v${CACHE_VERSION}:old`, JSON.stringify({ v: CACHE_VERSION + 1, ts: 1, data: { ok: true } }));
  b.map.set(`v${CACHE_VERSION}:bad`, '{not valid json');

  assert.equal(cacheGet('old'), null);
  assert.equal(b.map.has(`v${CACHE_VERSION}:old`), false);
  assert.equal(cacheGet('bad'), null);
  _setCacheBackendForTests(undefined);
});

test('cache: swallows backend exceptions without throwing', () => {
  const throwing: CacheBackend = {
    getString() {
      throw new Error('disk fail');
    },
    set() {
      throw new Error('disk full');
    },
    remove() {
      throw new Error('rm fail');
    },
    clearAll() {
      throw new Error('clear fail');
    },
  };
  _setCacheBackendForTests(throwing);
  assert.doesNotThrow(() => cacheSet('k', { a: 1 }));
  _clearMemCacheForTests();
  assert.equal(cacheGet('k'), null);
  assert.doesNotThrow(() => cacheRemove('k'));
  assert.doesNotThrow(() => cacheClear());
  _setCacheBackendForTests(undefined);
});
