import assert from 'node:assert/strict';
import test from 'node:test';
import { _setCacheBackendForTests, CacheBackend } from './cache';
import {
  ChatMessage,
  computeAndroidKeyboardOffset,
  extractPreviewUrl,
  findPrecedingUserMessage,
  formatAttachmentSize,
  formatChatTime,
  formatComposerChipLabel,
  formatMessagePreview,
  getChatEndpoint,
  mergeChatMessages,
  normalizeChatUrl,
  parseInlineTokens,
  parseMessageSegments,
  prependOlderMessages,
  resolveChatTarget,
  setChatEndpoint,
  shortEffortLabel,
  shortModelLabel,
  shouldPollChat,
  shouldShowEngineTag,
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

test('shouldShowEngineTag only shows tag on error or engine change', () => {
  const msgs: ChatMessage[] = [
    { id: 'u1', role: 'user', engine: 'gemini', text: 'hi', created_at: 1, status: 'done' },
    { id: 'a1', role: 'assistant', engine: 'gemini', text: 'hello', created_at: 2, status: 'done' },
    { id: 'a2', role: 'assistant', engine: 'gemini', text: 'still gemini', created_at: 3, status: 'done' },
    { id: 'a3', role: 'assistant', engine: 'claude', text: 'switched', created_at: 4, status: 'done' },
    { id: 'a4', role: 'assistant', engine: 'claude', text: 'still claude', created_at: 5, status: 'done' },
    { id: 'a5', role: 'assistant', engine: 'claude', text: '', error: 'boom', created_at: 6, status: 'error' },
  ];

  assert.equal(shouldShowEngineTag(msgs, 0, 'gemini'), false); // user
  assert.equal(shouldShowEngineTag(msgs, 1, 'gemini'), false); // matches active header engine
  assert.equal(shouldShowEngineTag(msgs, 1, 'claude'), true); // differs from active header engine
  assert.equal(shouldShowEngineTag(msgs, 2, 'gemini'), false); // same as previous assistant
  assert.equal(shouldShowEngineTag(msgs, 3, 'gemini'), true); // engine changed
  assert.equal(shouldShowEngineTag(msgs, 4, 'gemini'), false); // same as previous assistant
  assert.equal(shouldShowEngineTag(msgs, 5, 'gemini'), true); // error always shows
});

test('computeAndroidKeyboardOffset subtracts tab bar and OS resize cleanly', () => {
  assert.equal(computeAndroidKeyboardOffset(0, 92), 0);
  assert.equal(computeAndroidKeyboardOffset(320, 92, 0), 228);
  assert.equal(computeAndroidKeyboardOffset(320, 92, 320), 0);
  assert.equal(computeAndroidKeyboardOffset(80, 92, 0), 0);
});

test('shortModelLabel, shortEffortLabel, and formatComposerChipLabel format cleanly', () => {
  assert.equal(shortModelLabel('gemini-4-neon', 'Gemini 4 Neon'), 'Neon');
  assert.equal(shortModelLabel('gemini-3.8-flash', 'Gemini 3.8 Flash'), 'Flash');
  assert.equal(shortModelLabel('gemini-pro-orchestrator-neon', 'Orchestrator (Neon)'), 'Orch Neon');
  assert.equal(shortModelLabel('claude-opus-5-5', 'Opus 5.5'), 'Opus');
  assert.equal(shortModelLabel('claude-sonnet-5-5', 'Sonnet 5.5'), 'Sonnet');
  assert.equal(shortModelLabel('claude-haiku-5-5'), 'Haiku');

  assert.equal(shortEffortLabel('low'), 'Low');
  assert.equal(shortEffortLabel('medium'), 'Med');
  assert.equal(shortEffortLabel('high'), 'High');
  assert.equal(shortEffortLabel('xhigh'), 'XHigh');
  assert.equal(shortEffortLabel('max'), 'Max');

  assert.equal(formatComposerChipLabel('gemini', 'gemini-4-neon', 'Gemini 4 Neon', 'medium'), 'Neon · Med');
  assert.equal(formatComposerChipLabel('claude', 'claude-opus-5-5', 'Opus 5.5', 'high'), 'Opus · High');
  assert.equal(formatComposerChipLabel('gemini', null, null, null), 'Gemini');
  assert.equal(formatComposerChipLabel('claude', null, null, null), 'Claude');
});

test('formatAttachmentSize formats bytes, KB, and MB cleanly', () => {
  assert.equal(formatAttachmentSize(0), '0 B');
  assert.equal(formatAttachmentSize(512), '512 B');
  assert.equal(formatAttachmentSize(1536), '1.5 KB');
  assert.equal(formatAttachmentSize(24 * 1024), '24 KB');
  assert.equal(formatAttachmentSize(2.4 * 1024 * 1024), '2.4 MB');
  assert.equal(formatAttachmentSize(12 * 1024 * 1024), '12 MB');
});

test('findPrecedingUserMessage resolves reply_to, falls back to nearest user, and stops at system boundary', () => {
  const msgs: ChatMessage[] = [
    { id: 'u1', role: 'user', engine: 'claude', text: 'first prompt', created_at: 1, status: 'done', client_id: 'c1' },
    { id: 'a1', role: 'assistant', engine: 'claude', text: 'ok', created_at: 2, status: 'done', reply_to: 'u1' },
    { id: 's1', role: 'system', engine: 'claude', text: 'New conversation', created_at: 3, status: 'done' },
    { id: 'a_orphan', role: 'assistant', engine: 'claude', text: '', error: 'boom', created_at: 4, status: 'error' },
    { id: 'u2', role: 'user', engine: 'claude', text: 'second prompt', created_at: 5, status: 'done', client_id: 'c2' },
    { id: 'u3', role: 'user', engine: 'claude', text: 'third prompt', created_at: 6, status: 'done', client_id: 'c3' },
    { id: 'a2', role: 'assistant', engine: 'claude', text: '', error: 'timeout', created_at: 7, status: 'error', reply_to: 'u2' },
    { id: 'a3', role: 'assistant', engine: 'claude', text: '', error: 'failed', created_at: 8, status: 'error' },
  ];

  // System boundary blocks crossing into previous session
  assert.equal(findPrecedingUserMessage(msgs, 3), null);
  // reply_to matches exact user message even if another user message was sent after
  assert.equal(findPrecedingUserMessage(msgs, 6)?.id, 'u2');
  // Without reply_to, resolves nearest preceding user message
  assert.equal(findPrecedingUserMessage(msgs, 7)?.id, 'u3');
  // Out-of-bounds / first item returns null
  assert.equal(findPrecedingUserMessage(msgs, 0), null);
});

test('formatMessagePreview collapses whitespace and falls back to error or attachment summary', () => {
  assert.equal(
    formatMessagePreview({
      id: '1',
      role: 'user',
      engine: 'claude',
      text: '  hello\n   world  ',
      created_at: 1,
      status: 'done',
    }),
    'hello world',
  );
  assert.equal(
    formatMessagePreview({
      id: '2',
      role: 'assistant',
      engine: 'claude',
      text: '',
      error: 'Request\ntimed out',
      created_at: 2,
      status: 'error',
    }),
    'Request timed out',
  );
  assert.equal(
    formatMessagePreview({
      id: '3',
      role: 'user',
      engine: 'claude',
      text: '',
      created_at: 3,
      status: 'done',
      attachments: [
        { id: 'a1', name: 'screen.png', mime_type: 'image/png', size_bytes: 100, kind: 'image', url: '/x' },
      ],
    }),
    'screen.png',
  );
  assert.equal(
    formatMessagePreview({
      id: '4',
      role: 'user',
      engine: 'claude',
      text: '',
      created_at: 4,
      status: 'done',
      attachments: [
        { id: 'a1', name: 'a.png', mime_type: 'image/png', size_bytes: 100, kind: 'image', url: '/a' },
        { id: 'a2', name: 'b.png', mime_type: 'image/png', size_bytes: 100, kind: 'image', url: '/b' },
      ],
    }),
    '2 attachments',
  );
});

test('parseInlineTokens parses Markdown links, bare URLs, t3-thread refs, inline code, and bold', () => {
  assert.deepEqual(
    parseInlineTokens('See [GitHub](https://github.com/org/repo) and **important** `https://ignored.dev`!'),
    [
      { kind: 'text', text: 'See ' },
      { kind: 'link', label: 'GitHub', url: 'https://github.com/org/repo', external: true },
      { kind: 'text', text: ' and ' },
      { kind: 'bold', text: 'important' },
      { kind: 'text', text: ' ' },
      { kind: 'code', code: 'https://ignored.dev' },
      { kind: 'text', text: '!' },
    ],
  );

  // Bare URL trims trailing punctuation but preserves balanced parentheses
  assert.deepEqual(
    parseInlineTokens('Read (https://en.wikipedia.org/wiki/Rust_(programming_language)).'),
    [
      { kind: 'text', text: 'Read (' },
      {
        kind: 'link',
        label: 'https://en.wikipedia.org/wiki/Rust_(programming_language)',
        url: 'https://en.wikipedia.org/wiki/Rust_(programming_language)',
        external: true,
      },
      { kind: 'text', text: ').' },
    ],
  );

  // Internal/custom scheme like t3-thread:// is marked external: false with clean label
  assert.deepEqual(
    parseInlineTokens('Started [Fix chat links](t3-thread://v2/9a8fddd7-1c1a-4aab-a3ed-aed0696731e0) for you.'),
    [
      { kind: 'text', text: 'Started ' },
      {
        kind: 'link',
        label: 'Fix chat links',
        url: 't3-thread://v2/9a8fddd7-1c1a-4aab-a3ed-aed0696731e0',
        external: false,
      },
      { kind: 'text', text: ' for you.' },
    ],
  );
});

test('extractPreviewUrl finds first external http(s) URL and skips internal schemes and code', () => {
  assert.equal(
    extractPreviewUrl('Check [Thread](t3-thread://v2/abc) and [Docs](https://docs.example.com/guide).'),
    'https://docs.example.com/guide',
  );
  assert.equal(
    extractPreviewUrl('Bare link: https://example.com/page, and https://second.example.com'),
    'https://example.com/page',
  );
  assert.equal(
    extractPreviewUrl('```\nhttps://in-codeblock.example.com\n```\nInline `https://in-code.example.com`'),
    null,
  );
  assert.equal(extractPreviewUrl('Only [internal](t3-thread://v2/1234)'), null);
  assert.equal(extractPreviewUrl('Plain message with no links'), null);
});
