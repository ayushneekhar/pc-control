// yarn test:js
/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildExactMatch,
  buildRulePayload,
  buildSiteMatch,
  defaultRuleName,
  matchModeFor,
  relativeTime,
  splitUrl,
  urlFromMatch,
} from './agentviewRules';

test('splitUrl separates host from path and query', () => {
  assert.deepEqual(splitUrl('https://example.com/a/b?x=1#top'), { host: 'example.com', path: '/a/b?x=1' });
  assert.deepEqual(splitUrl('https://example.com'), { host: 'example.com', path: '/' });
  assert.deepEqual(splitUrl('https://example.com/'), { host: 'example.com', path: '/' });
  assert.deepEqual(splitUrl('example.com/x'), { host: 'example.com', path: '/x' });
  assert.deepEqual(splitUrl('http://localhost:8080/a'), { host: 'localhost:8080', path: '/a' });
  assert.deepEqual(splitUrl('https://mail.google.com?q=1'), { host: 'mail.google.com', path: '/?q=1' });
  assert.deepEqual(splitUrl(''), { host: '', path: '/' });
});

test('buildExactMatch escapes the URL and anchors it as a regex', () => {
  const m = buildExactMatch('https://example.com/a?b=1.2');
  assert.equal(m.matchType, 'regex');
  assert.equal(m.match, '^https://example\\.com/a\\?b=1\\.2$');
  assert.ok(new RegExp(m.match).test('https://example.com/a?b=1.2'));
  assert.ok(!new RegExp(m.match).test('https://example.com/a?b=1x2'));
});

test('buildSiteMatch turns the host into a whole-site glob', () => {
  assert.deepEqual(buildSiteMatch('https://example.com/a/b'), { match: '*://example.com/*', matchType: 'glob' });
  assert.deepEqual(buildSiteMatch('https://localhost:8080/x'), { match: '*://localhost:8080/*', matchType: 'glob' });
});

test('matchModeFor recognises exact and whole-site rules', () => {
  const exact = buildExactMatch('https://example.com/a');
  const site = buildSiteMatch('https://example.com/a');
  assert.equal(matchModeFor(exact, 'https://example.com/a'), 'exact');
  assert.equal(matchModeFor(site, 'https://example.com/a'), 'site');
  assert.equal(matchModeFor({ match: '^https://other\\.com/$' }, 'https://example.com/a'), 'custom');
  assert.equal(matchModeFor(exact, null), 'custom');
});

test('urlFromMatch recovers a usable URL for both match types', () => {
  assert.equal(urlFromMatch('^https://example\\.com/a\\?b=1$', 'regex'), 'https://example.com/a?b=1');
  assert.equal(urlFromMatch('*://example.com/*', 'glob'), 'https://example.com/');
  assert.equal(urlFromMatch('*://*.bank.example/*', 'glob'), null);
  assert.equal(urlFromMatch('re:foo', 'regex'), null);
});

test('defaultRuleName mirrors rules/from-url naming', () => {
  assert.equal(defaultRuleName('https://example.com/'), 'example.com/');
  assert.equal(defaultRuleName('https://example.com/a/b?x=1'), 'example.com/a/b?x=1');
  assert.equal(defaultRuleName(''), 'New rule');
});

test('buildRulePayload fills the action-specific fields', () => {
  const base = {
    name: 'My page',
    matchMode: 'exact' as const,
    url: 'https://example.com/a',
    enabled: false,
  };
  assert.deepEqual(buildRulePayload({ ...base, action: 'replace_page', html: '<p>x</p>' }), {
    name: 'My page',
    match: '^https://example\\.com/a$',
    matchType: 'regex',
    action: 'replace_page',
    enabled: false,
    html: '<p>x</p>',
  });
  assert.deepEqual(
    buildRulePayload({
      ...base,
      action: 'replace_text',
      replacements: [
        { find: 'SECRET', replace: 'REDACTED', regex: false },
        { find: '', replace: 'ignored', regex: true },
      ],
    }),
    {
      name: 'My page',
      match: '^https://example\\.com/a$',
      matchType: 'regex',
      action: 'replace_text',
      enabled: false,
      replacements: [{ find: 'SECRET', replace: 'REDACTED', regex: false }],
    },
  );
  assert.deepEqual(
    buildRulePayload({ ...base, action: 'hide', selectors: [' .ad ', '', '#sponsored'] }),
    {
      name: 'My page',
      match: '^https://example\\.com/a$',
      matchType: 'regex',
      action: 'hide',
      enabled: false,
      selectors: ['.ad', '#sponsored'],
    },
  );
  assert.deepEqual(buildRulePayload({ ...base, action: 'block', blockMode: 'reset' }), {
    name: 'My page',
    match: '^https://example\\.com/a$',
    matchType: 'regex',
    action: 'block',
    enabled: false,
    blockMode: 'reset',
  });
  assert.equal(buildRulePayload({ ...base, action: 'block', blockMode: 'nonsense' as never }).blockMode, '404');
});

test('buildRulePayload supports site and custom matches and falls back to a name', () => {
  assert.deepEqual(buildRulePayload({ action: 'hide', matchMode: 'site', url: 'https://example.com/a', selectors: [] }), {
    name: 'example.com/a',
    match: '*://example.com/*',
    matchType: 'glob',
    action: 'hide',
    enabled: true,
    selectors: [],
  });
  assert.deepEqual(
    buildRulePayload({
      action: 'block',
      matchMode: 'custom',
      customMatch: '^https://x\\.test/.*$',
      customMatchType: 'regex',
    }),
    {
      name: 'New rule',
      match: '^https://x\\.test/.*$',
      matchType: 'regex',
      action: 'block',
      enabled: true,
      blockMode: '404',
    },
  );
});

test('relativeTime is compact and future-safe', () => {
  const now = Date.parse('2026-11-11T12:00:00Z');
  assert.equal(relativeTime('2026-11-11T12:00:00Z', now), 'just now');
  assert.equal(relativeTime('2026-11-11T11:59:30Z', now), '30s ago');
  assert.equal(relativeTime('2026-11-11T11:55:00Z', now), '5m ago');
  assert.equal(relativeTime('2026-11-11T09:00:00Z', now), '3h ago');
  assert.equal(relativeTime('2026-11-09T12:00:00Z', now), '2d ago');
  assert.equal(relativeTime('2026-09-11T12:00:00Z', now), '2mo ago');
  assert.equal(relativeTime('2026-11-11T12:00:05Z', now), 'just now');
  assert.equal(relativeTime('not a date', now), '');
});
