import assert from 'node:assert/strict';
import test from 'node:test';
import { parseSharedText } from './share';

test('parseSharedText detects magnet links and extracts display name', () => {
  const res = parseSharedText('magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu+24.04+ISO');
  assert.deepEqual(res, {
    kind: 'magnet',
    uri: 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu+24.04+ISO',
    name: 'Ubuntu 24.04 ISO',
  });
});

test('parseSharedText detects YouTube links', () => {
  const res = parseSharedText('Check this out https://youtu.be/dQw4w9WgXcQ');
  assert.equal(res?.kind, 'youtube');
});

test('parseSharedText detects IMDb, TMDB, and Letterboxd links', () => {
  assert.deepEqual(parseSharedText('https://www.imdb.com/title/tt1375666/'), {
    kind: 'media',
    url: 'https://www.imdb.com/title/tt1375666/',
    source: 'imdb',
  });
  assert.deepEqual(parseSharedText('Watch https://www.themoviedb.org/tv/1396-breaking-bad.'), {
    kind: 'media',
    url: 'https://www.themoviedb.org/tv/1396-breaking-bad',
    source: 'tmdb',
  });
  assert.deepEqual(parseSharedText('https://letterboxd.com/film/inception/'), {
    kind: 'media',
    url: 'https://letterboxd.com/film/inception/',
    source: 'letterboxd',
  });
  assert.deepEqual(parseSharedText('https://boxd.it/1c2d'), {
    kind: 'media',
    url: 'https://boxd.it/1c2d',
    source: 'letterboxd',
  });
});

test('parseSharedText ignores unsupported links and plain text', () => {
  assert.equal(parseSharedText('hello world'), null);
  assert.equal(parseSharedText('https://example.com/film/test'), null);
  assert.equal(parseSharedText('https://notimdb.com/title/tt1234567'), null);
});
