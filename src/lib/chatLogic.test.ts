import assert from 'node:assert/strict';
import test from 'node:test';
import { _setCacheBackendForTests, CacheBackend } from './cache';
import {
  ChatMessage,
  formatChatTime,
  getChatEndpoint,
  mergeChatMessages,
  normalizeChatUrl,
  parseMessageSegments,
  prependOlderMessages,
  resolveChatTarget,
  setChatEndpoint,
  shouldPollChat,
} from './chatLogic';

function memBackend(): CacheBackend {
  const m = new Map<string, string>();
  return {
    getString: (k) => m.get(k),
    set: (k, v) => m.set(k, v),
    remove: (k) => m.delete(k),
    clearAll: () => m.clear(),
  };
}

test('chat endpoint config round-trips and normalizes URLs', () => {
  _setCacheBackendForTests(memBackend());
  assert.deepEqual(getChatEndpoint(), { url: '', token: '' });

  assert.equal(normalizeChatUrl('https://agent.neekhar.dev/pc/api/chat/messages'), 'https://agent.neekhar.dev');
  assert.equal(normalizeChatUrl('https://agent.neekhar.dev///'), 'https://agent.neekhar.dev');

  setChatEndpoint({ url: ' https://agent.neekhar.dev/ ', token: ' secret-tok ' });
  assert.deepEqual(getChatEndpoint(), { url: 'https://agent.neekhar.dev', token: 'secret-tok' });

  const customTarget = resolveChatTarget('https://tailnet.ts.net', getChatEndpoint());
  assert.equal(customTarget.custom, true);
  assert.equal(customTarget.baseUrl, 'https://agent.neekhar.dev');
  assert.deepEqual(customTarget.headers, { Authorization: 'Bearer secret-tok' });

  setChatEndpoint({ url: '', token: '' });
  const defaultTarget = resolveChatTarget('https://tailnet.ts.net/', getChatEndpoint());
  assert.equal(defaultTarget.custom, false);
  assert.equal(defaultTarget.baseUrl, 'https://tailnet.ts.net');
  assert.deepEqual(defaultTarget.headers, {});
});

test('mergeChatMessages deduplicates optimistic messages by client_id', () => {
  const optimisticUser: ChatMessage = {
    id: 'opt-u-1',
    role: 'user',
    engine: 'gemini',
    text: 'hello',
    created_at: 100,
    status: 'done',
    client_id: 'cid-1',
    optimistic: true,
  };
  const optimisticAsst: ChatMessage = {
    id: 'opt-a-1',
    role: 'assistant',
    engine: 'gemini',
    text: '',
    created_at: 100.001,
    status: 'pending',
    reply_to: 'cid-1',
    optimistic: true,
  };

  // Before server confirms cid-1, optimistic messages stay visible.
  const before = mergeChatMessages([optimisticUser, optimisticAsst], []);
  assert.equal(before.length, 2);

  // Once server returns cid-1, optimistic placeholders are replaced cleanly.
  const serverMsgs: ChatMessage[] = [
    {
      id: 'srv-u-1',
      role: 'user',
      engine: 'gemini',
      text: 'hello',
      created_at: 100.2,
      status: 'done',
      client_id: 'cid-1',
    },
    {
      id: 'srv-a-1',
      role: 'assistant',
      engine: 'gemini',
      text: 'world',
      created_at: 100.5,
      status: 'done',
      reply_to: 'srv-u-1',
    },
  ];
  const after = mergeChatMessages([optimisticUser, optimisticAsst], serverMsgs);
  assert.deepEqual(
    after.map((m) => m.id),
    ['srv-u-1', 'srv-a-1'],
  );
});

test('prependOlderMessages avoids duplicate IDs', () => {
  const cur: ChatMessage[] = [
    { id: 'm2', role: 'user', engine: 'claude', text: 'two', created_at: 2, status: 'done' },
  ];
  const older: ChatMessage[] = [
    { id: 'm1', role: 'user', engine: 'claude', text: 'one', created_at: 1, status: 'done' },
    { id: 'm2', role: 'user', engine: 'claude', text: 'two', created_at: 2, status: 'done' },
  ];
  const combined = prependOlderMessages(older, cur);
  assert.deepEqual(
    combined.map((m) => m.id),
    ['m1', 'm2'],
  );
});

test('shouldPollChat polls only when in-flight, focused, and active', () => {
  const doneMsg: ChatMessage = { id: '1', role: 'assistant', engine: 'gemini', text: 'ok', created_at: 1, status: 'done' };
  const pendMsg: ChatMessage = { id: '2', role: 'assistant', engine: 'gemini', text: '', created_at: 2, status: 'pending' };

  assert.equal(shouldPollChat([doneMsg], false, true, true), false);
  assert.equal(shouldPollChat([doneMsg], true, true, true), true);
  assert.equal(shouldPollChat([pendMsg], false, true, true), true);
  assert.equal(shouldPollChat([pendMsg], true, false, true), false);
  assert.equal(shouldPollChat([pendMsg], true, true, false), false);
});

test('parseMessageSegments extracts fenced code blocks and surrounding text', () => {
  const segs = parseMessageSegments('Before:\n```ts\nconst x = 1;\n```\nAfter');
  assert.deepEqual(segs, [
    { kind: 'text', text: 'Before:' },
    { kind: 'codeblock', lang: 'ts', code: 'const x = 1;' },
    { kind: 'text', text: 'After' },
  ]);

  assert.deepEqual(parseMessageSegments('Plain only'), [{ kind: 'text', text: 'Plain only' }]);
});

test('formatChatTime formats same-day and older timestamps', () => {
  const base = new Date(2026, 10, 23, 14, 5, 0).getTime();
  assert.equal(formatChatTime(base / 1000, base + 60_000), '2:05 pm');
  const prevDay = new Date(2026, 10, 22, 9, 30, 0).getTime();
  assert.equal(formatChatTime(prevDay / 1000, base), 'Nov 22, 9:30 am');
});
