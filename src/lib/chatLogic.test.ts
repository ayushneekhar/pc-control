import assert from 'node:assert/strict';
import test from 'node:test';
import { _setCacheBackendForTests, CacheBackend } from './cache';
import {
  applyChatListAutoFormat,
  attachmentImageCacheKey,
  buildAttachmentDataUri,
  buildChatAttachmentSource,
  buildChatListItems,
  buildReplyRef,
  canSteerQueuedMessage,
  chatAttachmentRequiresFetch,
  ChatMessage,
  computeAndroidKeyboardOffset,
  computeInlineImageSize,
  computeSwipeReplyOffset,
  currentProgressStepText,
  deriveImageDisplayName,
  extractPreviewUrl,
  findPrecedingUserMessage,
  formatAttachmentSize,
  formatChatTime,
  formatComposerChipLabel,
  formatEngineLabel,
  formatMessagePreview,
  formatProgressDuration,
  formatProgressSummary,
  getChatEndpoint,
  getChatMessageKey,
  getReplyRef,
  INLINE_IMAGE_MAX_HEIGHT,
  isRenderableImageUrl,
  isReplyableMessage,
  mergeChatMessages,
  normalizeChatUrl,
  normalizeMarkdownForDisplay,
  parseChatSseBlocks,
  parseInlineTokens,
  parseMessageSegments,
  prependOlderMessages,
  reconcileQueuedMessages,
  replySenderLabel,
  resolveChatLinkUrl,
  resolveChatTarget,
  resolveT3ThreadUrl,
  setChatEndpoint,
  shortEffortLabel,
  shortModelLabel,
  shouldCaptureSwipeReply,
  shouldPollChat,
  shouldShowEngineTag,
  SWIPE_REPLY_MAX_DRAG,
  SWIPE_REPLY_THRESHOLD,
  THINKING_INDICATOR_ID,
  upsertChatMessage,
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

test('buildChatAttachmentSource resolves relative and absolute URLs with Bearer token only for chat origin', () => {
  const customEndpoint = { url: 'https://agent.neekhar.dev/', token: 'tok-123' };
  const defaultBase = 'https://tailnet.ts.net/';

  // Relative path uses custom chat endpoint + Authorization header
  assert.deepEqual(
    buildChatAttachmentSource('/pc/api/chat/file/0123456789abcdef', defaultBase, customEndpoint),
    {
      uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
      headers: { Authorization: 'Bearer tok-123' },
    },
  );

  // Relative path without leading slash is normalized
  assert.deepEqual(
    buildChatAttachmentSource('pc/api/chat/file/0123456789abcdef', defaultBase, customEndpoint),
    {
      uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
      headers: { Authorization: 'Bearer tok-123' },
    },
  );

  // Absolute URL on the same custom chat origin keeps Authorization header
  assert.deepEqual(
    buildChatAttachmentSource('https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef', defaultBase, customEndpoint),
    {
      uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
      headers: { Authorization: 'Bearer tok-123' },
    },
  );

  // Absolute URL on an unrelated external origin MUST NOT receive the chat Bearer token
  assert.deepEqual(
    buildChatAttachmentSource('https://cdn.example.com/photo.png', defaultBase, customEndpoint),
    {
      uri: 'https://cdn.example.com/photo.png',
    },
  );

  // Without a custom chat endpoint, falls back to Tailnet base URL with no Authorization header
  assert.deepEqual(
    buildChatAttachmentSource('/pc/api/chat/file/0123456789abcdef', defaultBase, { url: '', token: 'tok-123' }),
    {
      uri: 'https://tailnet.ts.net/pc/api/chat/file/0123456789abcdef',
    },
  );

  // Custom URL without token omits headers
  assert.deepEqual(
    buildChatAttachmentSource('/pc/api/chat/file/0123456789abcdef', defaultBase, { url: 'https://agent.neekhar.dev', token: '' }),
    {
      uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
    },
  );
});

test('chatAttachmentRequiresFetch, attachmentImageCacheKey, and buildAttachmentDataUri handle authenticated image sources', () => {
  const authed = {
    uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
    headers: { Authorization: 'Bearer tok-123' },
  };
  const authedOther = {
    uri: 'https://agent.neekhar.dev/pc/api/chat/file/0123456789abcdef',
    headers: { Authorization: 'Bearer tok-999' },
  };
  const plain = {
    uri: 'https://tailnet.ts.net/pc/api/chat/file/0123456789abcdef',
  };

  assert.equal(chatAttachmentRequiresFetch(authed), true);
  assert.equal(chatAttachmentRequiresFetch(plain), false);
  assert.equal(chatAttachmentRequiresFetch({ uri: plain.uri, headers: {} }), false);

  assert.equal(attachmentImageCacheKey(plain), plain.uri);
  assert.notEqual(attachmentImageCacheKey(authed), attachmentImageCacheKey(authedOther));
  assert.equal(
    attachmentImageCacheKey({ uri: 'https://a/x', headers: { B: '2', A: '1' } }),
    attachmentImageCacheKey({ uri: 'https://a/x', headers: { A: '1', B: '2' } }),
  );

  // Raw base64 -> data URI with provided or default MIME
  assert.equal(buildAttachmentDataUri('AQID', 'image/png'), 'data:image/png;base64,AQID');
  assert.equal(buildAttachmentDataUri('AQID', 'image/jpeg; charset=binary'), 'data:image/jpeg;base64,AQID');
  assert.equal(buildAttachmentDataUri('AQID'), 'data:image/jpeg;base64,AQID');
  assert.equal(buildAttachmentDataUri('AQID', 'application/octet-stream'), 'data:image/jpeg;base64,AQID');

  // Existing data URL with generic MIME gets upgraded when specific MIME is known
  assert.equal(
    buildAttachmentDataUri('data:application/octet-stream;base64,AQID', 'image/webp'),
    'data:image/webp;base64,AQID',
  );
  assert.equal(
    buildAttachmentDataUri('data:;base64,AQID', 'image/png'),
    'data:image/png;base64,AQID',
  );
  // Existing data URL with specific MIME stays intact
  assert.equal(
    buildAttachmentDataUri('data:image/png;base64,AQID', 'image/jpeg'),
    'data:image/png;base64,AQID',
  );
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

test('parseMessageSegments extracts fenced code blocks with and without language tags', () => {
  const segs = parseMessageSegments('Before:\n```ts\nconst x = 1;\n```\nAfter');
  assert.deepEqual(segs, [
    { kind: 'text', text: 'Before:' },
    { kind: 'codeblock', lang: 'ts', code: 'const x = 1;' },
    { kind: 'text', text: 'After' },
  ]);

  // Fenced code block without language tag, preserving internal indentation
  assert.deepEqual(
    parseMessageSegments('Run:\n```\n  echo "hi"\n  echo "bye"\n```'),
    [
      { kind: 'text', text: 'Run:' },
      { kind: 'codeblock', lang: '', code: '  echo "hi"\n  echo "bye"' },
    ],
  );

  // CRLF line endings, trailing spaces after language tag, and multiple fenced blocks
  assert.deepEqual(
    parseMessageSegments('```bash  \r\nnpm run lint\r\n```\r\nThen:\r\n```\r\nnpm test\r\n```'),
    [
      { kind: 'codeblock', lang: 'bash', code: 'npm run lint' },
      { kind: 'text', text: 'Then:' },
      { kind: 'codeblock', lang: '', code: 'npm test' },
    ],
  );

  // Single-line triple-backtick without newline does not swallow first word as language tag
  assert.deepEqual(parseMessageSegments('```const a = 1```'), [
    { kind: 'codeblock', lang: '', code: 'const a = 1' },
  ]);

  assert.deepEqual(parseMessageSegments('Plain only'), [{ kind: 'text', text: 'Plain only' }]);
});

test('formatChatTime formats same-day and older timestamps', () => {
  const base = new Date(2026, 10, 23, 14, 5, 0).getTime();
  assert.equal(formatChatTime(base / 1000, base + 60_000), '2:05 pm');
  const prevDay = new Date(2026, 10, 22, 9, 30, 0).getTime();
  assert.equal(formatChatTime(prevDay / 1000, base), 'Nov 22, 9:30 am');
});

test('shouldShowEngineTag and formatEngineLabel label every assistant bubble consistently', () => {
  const msgs: ChatMessage[] = [
    { id: 'u1', role: 'user', engine: 'gemini', text: 'hi', created_at: 1, status: 'done' },
    { id: 'a1', role: 'assistant', engine: 'gemini', text: 'hello', created_at: 2, status: 'done' },
    { id: 'a2', role: 'assistant', engine: 'gemini', text: 'still gemini', created_at: 3, status: 'done' },
    { id: 'a3', role: 'assistant', engine: 'claude', text: 'switched', created_at: 4, status: 'done' },
    { id: 'a4', role: 'assistant', engine: 'claude', text: 'still claude', created_at: 5, status: 'done' },
    { id: 'a5', role: 'assistant', engine: 'claude', text: '', error: 'boom', created_at: 6, status: 'error' },
    { id: 's1', role: 'system', engine: 'claude', text: 'New conversation', created_at: 7, status: 'done' },
  ];

  assert.equal(shouldShowEngineTag(msgs, 0, 'gemini'), false); // user
  assert.equal(shouldShowEngineTag(msgs, 1, 'gemini'), true); // gemini assistant shows label
  assert.equal(shouldShowEngineTag(msgs, 1, 'claude'), true);
  assert.equal(shouldShowEngineTag(msgs, 2, 'gemini'), true); // consecutive gemini still shows label
  assert.equal(shouldShowEngineTag(msgs, 3, 'gemini'), true); // claude shows label
  assert.equal(shouldShowEngineTag(msgs, 4, 'claude'), true); // consecutive claude shows label
  assert.equal(shouldShowEngineTag(msgs, 5, 'gemini'), true); // error shows label
  assert.equal(shouldShowEngineTag(msgs, 6, 'claude'), false); // system never shows label

  assert.equal(formatEngineLabel('gemini'), 'Gemini');
  assert.equal(formatEngineLabel('claude'), 'Claude');
  assert.equal(formatEngineLabel(undefined), 'Claude');
});

test('progress helpers format current step, duration, and collapsed summary', () => {
  assert.equal(currentProgressStepText(null), 'Thinking…');
  assert.equal(currentProgressStepText({ started: 1, steps: [] }), 'Thinking…');
  assert.equal(
    currentProgressStepText({
      started: 1,
      steps: [
        { t: 2, text: 'reading chat.py' },
        { t: 3, text: 'running `git status`' },
      ],
    }),
    'running `git status`',
  );

  assert.equal(formatProgressDuration(0), '1s');
  assert.equal(formatProgressDuration(42), '42s');
  assert.equal(formatProgressDuration(60), '1m');
  assert.equal(formatProgressDuration(125), '2m 5s');

  assert.equal(formatProgressSummary(null), null);
  assert.equal(formatProgressSummary({ started: 1, duration_s: 5, step_count: 0, steps: [] }), null);
  assert.equal(
    formatProgressSummary({
      started: 1,
      duration_s: 42,
      step_count: 7,
      steps: [{ t: 2, text: 'reading chat.py' }],
    }),
    'Worked for 42s · 7 steps',
  );
  assert.equal(
    formatProgressSummary({
      started: 1,
      duration_s: 3,
      step_count: 1,
      steps: [{ t: 2, text: 'reading chat.py' }],
    }),
    'Worked for 3s · 1 step',
  );
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

test('parseInlineTokens handles multiple inline code spans, mixed links, and unmatched backticks', () => {
  // Single inline code span
  assert.deepEqual(parseInlineTokens('Run `git status` now'), [
    { kind: 'text', text: 'Run ' },
    { kind: 'code', code: 'git status' },
    { kind: 'text', text: ' now' },
  ]);

  // Multiple inline code spans in one message
  assert.deepEqual(parseInlineTokens('Use `npm run lint` and `npx tsc --noEmit` before `git commit`.'), [
    { kind: 'text', text: 'Use ' },
    { kind: 'code', code: 'npm run lint' },
    { kind: 'text', text: ' and ' },
    { kind: 'code', code: 'npx tsc --noEmit' },
    { kind: 'text', text: ' before ' },
    { kind: 'code', code: 'git commit' },
    { kind: 'text', text: '.' },
  ]);

  // Inline code mixed with bare URLs, Markdown links, and T3 thread links
  assert.deepEqual(
    parseInlineTokens(
      'Open [Thread](t3-thread://v2/env-1/thr-2), run `curl https://api.example.com`, or check [Docs](https://docs.example.com) and https://example.com/status.',
    ),
    [
      { kind: 'text', text: 'Open ' },
      { kind: 'link', label: 'Thread', url: 't3-thread://v2/env-1/thr-2', external: false },
      { kind: 'text', text: ', run ' },
      { kind: 'code', code: 'curl https://api.example.com' },
      { kind: 'text', text: ', or check ' },
      { kind: 'link', label: 'Docs', url: 'https://docs.example.com', external: true },
      { kind: 'text', text: ' and ' },
      { kind: 'link', label: 'https://example.com/status', url: 'https://example.com/status', external: true },
      { kind: 'text', text: '.' },
    ],
  );

  // Unmatched single backticks stay plain text
  assert.deepEqual(parseInlineTokens('A stray ` backtick stays plain'), [
    { kind: 'text', text: 'A stray ` backtick stays plain' },
  ]);

  // Valid inline code followed by an unmatched backtick
  assert.deepEqual(parseInlineTokens('Valid `foo` and stray `bar'), [
    { kind: 'text', text: 'Valid ' },
    { kind: 'code', code: 'foo' },
    { kind: 'text', text: ' and stray `bar' },
  ]);

  // Empty backticks and backticks across newlines stay plain text
  assert.deepEqual(parseInlineTokens('Empty `` stays plain'), [
    { kind: 'text', text: 'Empty `` stays plain' },
  ]);
  assert.deepEqual(parseInlineTokens('Backtick `across\nlines` stays plain'), [
    { kind: 'text', text: 'Backtick `across\nlines` stays plain' },
  ]);
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
  assert.equal(extractPreviewUrl('Only [T3](t3-thread://v2/env-1/thr-2)'), null);
  assert.equal(extractPreviewUrl('Plain message with no links'), null);
});

test('resolveT3ThreadUrl converts t3-thread://v2/<envId>/<threadId> to app.t3.codes App Links', () => {
  assert.equal(
    resolveT3ThreadUrl('t3-thread://v2/env-123/thread-456'),
    'https://app.t3.codes/threads/env-123/thread-456',
  );
  // Already-encoded segments are decoded once and cleanly re-encoded (no double encoding)
  assert.equal(
    resolveT3ThreadUrl('t3-thread://v2/env%3A1/mcp%3A9a8fddd7-1c1a-4aab-a3ed-aed0696731e0'),
    'https://app.t3.codes/threads/env%3A1/mcp%3A9a8fddd7-1c1a-4aab-a3ed-aed0696731e0',
  );
  // Non-matching or incomplete URLs return null
  assert.equal(resolveT3ThreadUrl('t3-thread://v2/only-one-segment'), null);
  assert.equal(resolveT3ThreadUrl('https://app.t3.codes/threads/env/thr'), null);
  assert.equal(resolveT3ThreadUrl('pccontrol://chat'), null);
});

test('reply helpers extract structured reply_to, build snippets, and format sender labels', () => {
  const legacyAsst: ChatMessage = {
    id: 'a1',
    role: 'assistant',
    engine: 'gemini',
    text: 'done',
    created_at: 2,
    status: 'done',
    reply_to: 'u1',
  };
  assert.equal(getReplyRef(legacyAsst), null);

  const longUser: ChatMessage = {
    id: 'u1',
    role: 'user',
    engine: 'claude',
    text: 'x'.repeat(300),
    created_at: 1,
    status: 'done',
  };
  const built = buildReplyRef(longUser);
  assert.equal(built.id, 'u1');
  assert.equal(built.role, 'user');
  assert.equal(built.text.length, 240);
  assert.ok(built.text.endsWith('…'));

  const withReply: ChatMessage = {
    id: 'u2',
    role: 'user',
    engine: 'gemini',
    text: 'following up',
    created_at: 3,
    status: 'done',
    reply_to: { id: 'a1', role: 'assistant', engine: 'gemini', text: 'done' },
  };
  assert.deepEqual(getReplyRef(withReply), { id: 'a1', role: 'assistant', engine: 'gemini', text: 'done' });

  assert.equal(replySenderLabel({ role: 'user', engine: 'gemini' }), 'You');
  assert.equal(replySenderLabel({ role: 'assistant', engine: 'gemini' }), 'Gemini');
  assert.equal(replySenderLabel({ role: 'assistant', engine: 'claude' }), 'Claude');
  assert.equal(replySenderLabel({ role: 'system' }), 'System');
});

test('shouldCaptureSwipeReply captures only deliberate rightward horizontal drags', () => {
  assert.equal(shouldCaptureSwipeReply(0, 0), false);
  assert.equal(shouldCaptureSwipeReply(10, 0), false);
  assert.equal(shouldCaptureSwipeReply(11, 0), true);
  assert.equal(shouldCaptureSwipeReply(-30, 0), false);
  assert.equal(shouldCaptureSwipeReply(20, 15), false); // dy too large (20 <= 22.5)
  assert.equal(shouldCaptureSwipeReply(20, -15), false);
  assert.equal(shouldCaptureSwipeReply(25, 15), true); // 25 > 22.5
  assert.equal(shouldCaptureSwipeReply(Number.NaN, 0), false);
});

test('computeSwipeReplyOffset tracks 1:1 up to threshold and rubber-bands cleanly below max', () => {
  assert.equal(computeSwipeReplyOffset(-25), 0);
  assert.equal(computeSwipeReplyOffset(0), 0);
  assert.equal(computeSwipeReplyOffset(32), 32);
  assert.equal(computeSwipeReplyOffset(SWIPE_REPLY_THRESHOLD), SWIPE_REPLY_THRESHOLD);

  const past = computeSwipeReplyOffset(SWIPE_REPLY_THRESHOLD + 40);
  assert.ok(past > SWIPE_REPLY_THRESHOLD && past < SWIPE_REPLY_MAX_DRAG);

  const huge = computeSwipeReplyOffset(2000);
  assert.ok(huge <= SWIPE_REPLY_MAX_DRAG);
  assert.ok(huge > SWIPE_REPLY_MAX_DRAG - 0.1);
});

test('isReplyableMessage excludes system, pending, and optimistic messages', () => {
  assert.equal(
    isReplyableMessage({ id: 'u1', role: 'user', engine: 'claude', text: 'hi', created_at: 1, status: 'done' }),
    true,
  );
  assert.equal(
    isReplyableMessage({ id: 'a1', role: 'assistant', engine: 'gemini', text: 'hey', created_at: 2, status: 'done' }),
    true,
  );
  assert.equal(
    isReplyableMessage({ id: 'a2', role: 'assistant', engine: 'gemini', text: '', error: 'err', created_at: 3, status: 'error' }),
    true,
  );
  assert.equal(
    isReplyableMessage({ id: 's1', role: 'system', engine: 'claude', text: 'New chat', created_at: 4, status: 'done' }),
    false,
  );
  assert.equal(
    isReplyableMessage({ id: 'p1', role: 'assistant', engine: 'claude', text: '', created_at: 5, status: 'pending' }),
    false,
  );
  assert.equal(
    isReplyableMessage({
      id: 'opt-1',
      role: 'user',
      engine: 'claude',
      text: 'sending',
      created_at: 6,
      status: 'done',
      optimistic: true,
    }),
    false,
  );
});

test('applyChatListAutoFormat continues ordered lists and preserves delimiter and indent', () => {
  const r1 = applyChatListAutoFormat('1. first', '1. first\n', { start: 8, end: 8 });
  assert.deepEqual(r1, {
    text: '1. first\n2. ',
    selection: { start: 12, end: 12 },
    changed: true,
  });

  // Multi-digit rollover (9 -> 10)
  const r2 = applyChatListAutoFormat('9. x', '9. x\n', { start: 4, end: 4 });
  assert.deepEqual(r2, {
    text: '9. x\n10. ',
    selection: { start: 9, end: 9 },
    changed: true,
  });

  // Parenthesis delimiter
  const r3 = applyChatListAutoFormat('1) a', '1) a\n', { start: 4, end: 4 });
  assert.deepEqual(r3, {
    text: '1) a\n2) ',
    selection: { start: 8, end: 8 },
    changed: true,
  });

  // Preserving indentation
  const r4 = applyChatListAutoFormat('  1. a', '  1. a\n', { start: 6, end: 6 });
  assert.deepEqual(r4, {
    text: '  1. a\n  2. ',
    selection: { start: 12, end: 12 },
    changed: true,
  });

  const r5 = applyChatListAutoFormat('  2) item', '  2) item\n', { start: 9, end: 9 });
  assert.deepEqual(r5, {
    text: '  2) item\n  3) ',
    selection: { start: 15, end: 15 },
    changed: true,
  });
});

test('applyChatListAutoFormat continues bulleted lists (-, *, •) and preserves indent', () => {
  const dash = applyChatListAutoFormat('- a', '- a\n', { start: 3, end: 3 });
  assert.deepEqual(dash, {
    text: '- a\n- ',
    selection: { start: 6, end: 6 },
    changed: true,
  });

  const star = applyChatListAutoFormat('* a', '* a\n', { start: 3, end: 3 });
  assert.deepEqual(star, {
    text: '* a\n* ',
    selection: { start: 6, end: 6 },
    changed: true,
  });

  const bullet = applyChatListAutoFormat('• a', '• a\n', { start: 3, end: 3 });
  assert.deepEqual(bullet, {
    text: '• a\n• ',
    selection: { start: 6, end: 6 },
    changed: true,
  });

  const indented = applyChatListAutoFormat('  • item', '  • item\n', { start: 8, end: 8 });
  assert.deepEqual(indented, {
    text: '  • item\n  • ',
    selection: { start: 13, end: 13 },
    changed: true,
  });
});

test('applyChatListAutoFormat disengages cleanly on double Enter for empty ordered and unordered items', () => {
  // Ordered item disengage after previous item
  const r1 = applyChatListAutoFormat('1. a\n2. ', '1. a\n2. \n', { start: 8, end: 8 });
  assert.deepEqual(r1, {
    text: '1. a\n',
    selection: { start: 5, end: 5 },
    changed: true,
  });

  // Unordered item disengage after previous item
  const r2 = applyChatListAutoFormat('- a\n- ', '- a\n- \n', { start: 6, end: 6 });
  assert.deepEqual(r2, {
    text: '- a\n',
    selection: { start: 4, end: 4 },
    changed: true,
  });

  // Indented empty marker with extra trailing spaces on first line
  const r3 = applyChatListAutoFormat('  2)   ', '  2)   \n', { start: 7, end: 7 });
  assert.deepEqual(r3, {
    text: '',
    selection: { start: 0, end: 0 },
    changed: true,
  });

  // Mid-text disengage leaves a clean blank unindented line between items
  const r4 = applyChatListAutoFormat('1. a\n2. \n3. c', '1. a\n2. \n\n3. c', { start: 8, end: 8 });
  assert.deepEqual(r4, {
    text: '1. a\n\n3. c',
    selection: { start: 5, end: 5 },
    changed: true,
  });
});

test('applyChatListAutoFormat splits a list item mid-line with accurate cursor placement', () => {
  // Cursor right after "hello" before the space
  const r1 = applyChatListAutoFormat('1. hello world', '1. hello\n world', { start: 8, end: 8 });
  assert.deepEqual(r1, {
    text: '1. hello\n2. world',
    selection: { start: 12, end: 12 },
    changed: true,
  });

  // Cursor right after the space before "world"
  const r2 = applyChatListAutoFormat('1. hello world', '1. hello \nworld', { start: 9, end: 9 });
  assert.deepEqual(r2, {
    text: '1. hello\n2. world',
    selection: { start: 12, end: 12 },
    changed: true,
  });

  // Inserting a new item at the end of an earlier list line
  const r3 = applyChatListAutoFormat('1. a\n2. b', '1. a\n\n2. b', { start: 4, end: 4 });
  assert.deepEqual(r3, {
    text: '1. a\n2. \n2. b',
    selection: { start: 8, end: 8 },
    changed: true,
  });

  // Works even when prevSelection is omitted (Android IME fallback)
  const r4 = applyChatListAutoFormat('1. hello world', '1. hello\n world');
  assert.deepEqual(r4, {
    text: '1. hello\n2. world',
    selection: { start: 12, end: 12 },
    changed: true,
  });
});

test('applyChatListAutoFormat ignores multi-line paste, deletions, and plain text', () => {
  // Multi-line paste does NOT trigger auto-formatting
  const paste = applyChatListAutoFormat('1. a', '1. a\nfoo\nbar', { start: 4, end: 4 });
  assert.equal(paste.changed, false);
  assert.equal(paste.text, '1. a\nfoo\nbar');

  // Backspace / deletion does NOT trigger or re-insert markers
  const delSpace = applyChatListAutoFormat('1. a\n2. ', '1. a\n2.', { start: 8, end: 8 });
  assert.equal(delSpace.changed, false);
  assert.equal(delSpace.text, '1. a\n2.');

  const delBullet = applyChatListAutoFormat('- ', '-', { start: 2, end: 2 });
  assert.equal(delBullet.changed, false);
  assert.equal(delBullet.text, '-');

  // Plain text / non-list lines unaffected
  const plain = applyChatListAutoFormat('hello world', 'hello world\n', { start: 11, end: 11 });
  assert.equal(plain.changed, false);
  assert.equal(plain.text, 'hello world\n');

  // Non-list punctuation (no space after marker, horizontal rule, markdown bold)
  const noSpace = applyChatListAutoFormat('1.item', '1.item\n', { start: 6, end: 6 });
  assert.equal(noSpace.changed, false);
  assert.equal(noSpace.text, '1.item\n');

  const hr = applyChatListAutoFormat('---', '---\n', { start: 3, end: 3 });
  assert.equal(hr.changed, false);
  assert.equal(hr.text, '---\n');

  const bold = applyChatListAutoFormat('*bold*', '*bold*\n', { start: 6, end: 6 });
  assert.equal(bold.changed, false);
  assert.equal(bold.text, '*bold*\n');

  // Regular typing (non-newline char)
  const typing = applyChatListAutoFormat('1. a', '1. ab', { start: 4, end: 4 });
  assert.equal(typing.changed, false);
  assert.equal(typing.text, '1. ab');
});

test('normalizeMarkdownForDisplay converts leading unicode bullets to CommonMark dashes', () => {
  assert.equal(normalizeMarkdownForDisplay('• first\n• second'), '- first\n- second');
  assert.equal(normalizeMarkdownForDisplay('  • nested\n\t• deeper'), '  - nested\n\t- deeper');
  assert.equal(normalizeMarkdownForDisplay('foo • bar'), 'foo • bar');
  assert.equal(normalizeMarkdownForDisplay('- already dash\n1. numbered'), '- already dash\n1. numbered');
  assert.equal(normalizeMarkdownForDisplay(''), '');
});

test('resolveChatLinkUrl resolves http(s) and t3-thread URLs and rejects others', () => {
  assert.equal(resolveChatLinkUrl('https://app.t3.codes/threads/e1/t1'), 'https://app.t3.codes/threads/e1/t1');
  assert.equal(resolveChatLinkUrl('t3-thread://v2/env-1/thr-2'), 'https://app.t3.codes/threads/env-1/thr-2');
  assert.equal(resolveChatLinkUrl('javascript:alert(1)'), null);
  assert.equal(resolveChatLinkUrl(''), null);
});

test('parseMessageSegments parses standard GFM tables with alignment markers and surrounding text', () => {
  const md = [
    'Summary:',
    '',
    '| Service | Status | CPU |',
    '| :--- | :---: | ---: |',
    '| pc-api | up | 1.2% |',
    '| caddy | up | 0.4% |',
    '',
    'All healthy.',
  ].join('\n');

  assert.deepEqual(parseMessageSegments(md), [
    { kind: 'text', text: 'Summary:' },
    {
      kind: 'table',
      headers: ['Service', 'Status', 'CPU'],
      alignments: ['left', 'center', 'right'],
      rows: [
        ['pc-api', 'up', '1.2%'],
        ['caddy', 'up', '0.4%'],
      ],
    },
    { kind: 'text', text: 'All healthy.' },
  ]);

  // Tables without outer pipes and with plain `---` separators default to left alignment
  const compact = ['Name | Count', '--- | ---:', 'Alpha | 10', 'Beta | 20'].join('\n');
  assert.deepEqual(parseMessageSegments(compact), [
    {
      kind: 'table',
      headers: ['Name', 'Count'],
      alignments: ['left', 'right'],
      rows: [
        ['Alpha', '10'],
        ['Beta', '20'],
      ],
    },
  ]);

  // Uneven rows pad missing cells with empty strings and truncate extra cells; header-only tables parse cleanly
  const uneven = [
    '| A | B | C |',
    '| --- | --- | --- |',
    '| 1 |',
    '| 1 | 2 | 3 | 4 |',
  ].join('\n');
  assert.deepEqual(parseMessageSegments(uneven), [
    {
      kind: 'table',
      headers: ['A', 'B', 'C'],
      alignments: ['left', 'left', 'left'],
      rows: [
        ['1', '', ''],
        ['1', '2', '3'],
      ],
    },
  ]);

  assert.deepEqual(parseMessageSegments('| Only | Header |\n| --- | :---: |'), [
    {
      kind: 'table',
      headers: ['Only', 'Header'],
      alignments: ['left', 'center'],
      rows: [],
    },
  ]);
});

test('parseMessageSegments preserves inline code, escaped pipes, bold, and links inside table cells', () => {
  const md = [
    '| Feature | Command | Docs |',
    '| --- | --- | --- |',
    '| **Pipe** | `echo a | b` | [Guide](https://example.com/guide) |',
    '| Escaped | a \\| b | [Thread](t3-thread://v2/env-1/thr-2) |',
  ].join('\n');

  assert.deepEqual(parseMessageSegments(md), [
    {
      kind: 'table',
      headers: ['Feature', 'Command', 'Docs'],
      alignments: ['left', 'left', 'left'],
      rows: [
        ['**Pipe**', '`echo a | b`', '[Guide](https://example.com/guide)'],
        ['Escaped', 'a | b', '[Thread](t3-thread://v2/env-1/thr-2)'],
      ],
    },
  ]);

  // Inline tokens inside table cells remain parseable and external URLs work for link previews
  assert.equal(extractPreviewUrl(md), 'https://example.com/guide');
});

test('parseMessageSegments does NOT parse tables inside fenced code blocks', () => {
  const md = [
    'Example markdown:',
    '```markdown',
    '| Col A | Col B |',
    '| --- | ---: |',
    '| 1 | 2 |',
    '```',
  ].join('\n');

  assert.deepEqual(parseMessageSegments(md), [
    { kind: 'text', text: 'Example markdown:' },
    {
      kind: 'codeblock',
      lang: 'markdown',
      code: '| Col A | Col B |\n| --- | ---: |\n| 1 | 2 |',
    },
  ]);
});

test('parseMessageSegments ignores malformed and non-table pipe lines', () => {
  // Mismatched header vs separator column count
  const mismatched = '| A | B |\n| --- | --- | --- |\n| 1 | 2 |';
  assert.deepEqual(parseMessageSegments(mismatched), [{ kind: 'text', text: mismatched }]);

  // Missing separator row
  const noSep = '| A | B |\n| 1 | 2 |';
  assert.deepEqual(parseMessageSegments(noSep), [{ kind: 'text', text: noSep }]);

  // Invalid separator characters
  const badSep = '| A | B |\n| --- | abc |\n| 1 | 2 |';
  assert.deepEqual(parseMessageSegments(badSep), [{ kind: 'text', text: badSep }]);

  // Standalone thematic break and prose with pipes
  const prose = 'Use foo | bar\n---\nNext paragraph';
  assert.deepEqual(parseMessageSegments(prose), [{ kind: 'text', text: prose }]);
});

test('getChatMessageKey stays stable across optimistic and server-confirmed user messages', () => {
  const optimistic: ChatMessage = {
    id: 'opt-u-c-123',
    role: 'user',
    engine: 'claude',
    text: 'hi',
    created_at: 10,
    status: 'done',
    client_id: 'c-123',
    optimistic: true,
  };
  const confirmed: ChatMessage = {
    id: 'msg-server-99',
    role: 'user',
    engine: 'claude',
    text: 'hi',
    created_at: 10.2,
    status: 'done',
    client_id: 'c-123',
  };
  const assistant: ChatMessage = {
    id: 'msg-asst-100',
    role: 'assistant',
    engine: 'claude',
    text: 'hello',
    created_at: 11,
    status: 'done',
    client_id: 'c-123',
  };
  const userWithoutClientId: ChatMessage = {
    id: 'msg-user-legacy',
    role: 'user',
    engine: 'claude',
    text: 'legacy',
    created_at: 1,
    status: 'done',
  };

  assert.equal(getChatMessageKey(optimistic), 'u:c-123');
  assert.equal(getChatMessageKey(confirmed), 'u:c-123');
  assert.equal(getChatMessageKey(assistant), 'msg-asst-100');
  assert.equal(getChatMessageKey(userWithoutClientId), 'msg-user-legacy');
});

test('buildChatListItems appends trailing pending thinking item only when in flight without a pending message', () => {
  const base: ChatMessage[] = [
    { id: 'u1', role: 'user', engine: 'claude', text: 'check status', created_at: 10, status: 'done', client_id: 'c1' },
  ];
  const progress = {
    engine: 'gemini' as const,
    started: 11,
    steps: [{ t: 12, text: 'Inspecting files' }],
  };

  // Idle: returns the exact original array without appending
  const idle = buildChatListItems(base, { running: false, sending: false, engine: 'claude', activeProgress: null });
  assert.equal(idle, base);

  // Sending or running with no pending message: appends synthetic thinking item at the end
  const inFlight = buildChatListItems(base, { running: true, sending: false, engine: 'claude', activeProgress: progress });
  assert.equal(inFlight.length, 2);
  assert.equal(inFlight[0], base[0]);
  assert.deepEqual(inFlight[1], {
    id: THINKING_INDICATOR_ID,
    role: 'assistant',
    engine: 'gemini',
    text: '',
    created_at: 11,
    status: 'pending',
    progress,
    optimistic: true,
  });
  assert.equal(isReplyableMessage(inFlight[1]), false);

  // Sending only (before server confirms running): still appends thinking item
  const sendingOnly = buildChatListItems(base, { running: false, sending: true, engine: 'claude', activeProgress: null });
  assert.equal(sendingOnly.length, 2);
  assert.equal(sendingOnly[1].id, THINKING_INDICATOR_ID);
  assert.equal(sendingOnly[1].engine, 'claude');

  // If a real pending assistant message already exists, never duplicate the thinking item
  const withPending: ChatMessage[] = [
    ...base,
    { id: 'a-pend', role: 'assistant', engine: 'claude', text: '', created_at: 12, status: 'pending' },
  ];
  const deduped = buildChatListItems(withPending, {
    running: true,
    sending: true,
    engine: 'claude',
    activeProgress: progress,
  });
  assert.equal(deduped, withPending);
});

test('parseMessageSegments extracts inline and linked Markdown images while preserving links and code', () => {
  // Standalone portrait wallpaper image with surrounding text
  assert.deepEqual(
    parseMessageSegments(
      'Here is your wallpaper:\n\n![AMOLED Minimal](https://cdn.example.com/wall_9x16.png)\n\nTap to view full screen.',
    ),
    [
      { kind: 'text', text: 'Here is your wallpaper:' },
      { kind: 'image', alt: 'AMOLED Minimal', url: 'https://cdn.example.com/wall_9x16.png' },
      { kind: 'text', text: 'Tap to view full screen.' },
    ],
  );

  // Linked image, title attribute, balanced parens, relative server path, and angle-bracket URL
  assert.deepEqual(
    parseMessageSegments(
      [
        '[![Wallpaper](https://cdn.example.com/thumb.jpg)](https://cdn.example.com/full.jpg)',
        '![Wiki](https://en.wikipedia.org/wiki/Image_(1).png "Title")',
        '![Server](/pc/api/chat/attachments/abc123)',
        '![](<https://cdn.example.com/my wall.png>)',
      ].join('\n'),
    ),
    [
      {
        kind: 'image',
        alt: 'Wallpaper',
        url: 'https://cdn.example.com/thumb.jpg',
        linkUrl: 'https://cdn.example.com/full.jpg',
      },
      { kind: 'image', alt: 'Wiki', url: 'https://en.wikipedia.org/wiki/Image_(1).png' },
      { kind: 'image', alt: 'Server', url: '/pc/api/chat/attachments/abc123' },
      { kind: 'image', alt: '', url: 'https://cdn.example.com/my wall.png' },
    ],
  );

  // Regular links, inline code, fenced code blocks, escaped syntax, and invalid URLs stay untouched
  assert.deepEqual(
    parseMessageSegments(
      'See [Docs](https://example.com/docs), inline `![code](https://example.com/a.png)`, escaped \\![lit](https://example.com/b.png), and ![bad](javascript:alert(1)).',
    ),
    [
      {
        kind: 'text',
        text: 'See [Docs](https://example.com/docs), inline `![code](https://example.com/a.png)`, escaped \\![lit](https://example.com/b.png), and ![bad](javascript:alert(1)).',
      },
    ],
  );

  assert.deepEqual(parseMessageSegments('```md\n![inside](https://example.com/x.png)\n```'), [
    { kind: 'codeblock', lang: 'md', code: '![inside](https://example.com/x.png)' },
  ]);

  // extractPreviewUrl skips standalone images but still finds real links alongside images
  assert.equal(extractPreviewUrl('![Wallpaper](https://cdn.example.com/wall.png)'), null);
  assert.equal(
    extractPreviewUrl(
      '![Wallpaper](https://cdn.example.com/wall.png)\nSource: [Unsplash](https://unsplash.com/photos/abc)',
    ),
    'https://unsplash.com/photos/abc',
  );
});

test('computeInlineImageSize preserves natural aspect ratio and clamps tall portraits cleanly', () => {
  // 9:16 portrait wallpaper in 360dp container -> clamped to max height 440dp with exact 9:16 width
  const portrait = computeInlineImageSize(1440, 2560, 360, INLINE_IMAGE_MAX_HEIGHT);
  assert.equal(portrait.height, 440);
  assert.equal(portrait.width, Math.round(440 * (9 / 16)));

  // 9:19.5 tall phone wallpaper -> clamped to 440dp height, proportional narrower width
  const tallPhone = computeInlineImageSize(1440, 3120, 360, INLINE_IMAGE_MAX_HEIGHT);
  assert.equal(tallPhone.height, 440);
  assert.equal(tallPhone.width, Math.round(440 * (1440 / 3120)));

  // 16:9 landscape -> fills container width at exact 16:9 height
  const landscape = computeInlineImageSize(1920, 1080, 360, INLINE_IMAGE_MAX_HEIGHT);
  assert.equal(landscape.width, 360);
  assert.equal(landscape.height, Math.round(360 / (16 / 9)));

  // 1:1 square -> fills container width
  const square = computeInlineImageSize(1024, 1024, 340, INLINE_IMAGE_MAX_HEIGHT);
  assert.deepEqual(square, { width: 340, height: 340 });

  // Unloaded / invalid dimensions -> clean bounded placeholder
  const placeholder = computeInlineImageSize(0, 0, 360, INLINE_IMAGE_MAX_HEIGHT);
  assert.ok(placeholder.width > 0 && placeholder.width <= 360);
  assert.ok(placeholder.height > 0 && placeholder.height <= INLINE_IMAGE_MAX_HEIGHT);
});

test('isRenderableImageUrl and deriveImageDisplayName handle URLs and alt text cleanly', () => {
  assert.equal(isRenderableImageUrl('https://example.com/a.png'), true);
  assert.equal(isRenderableImageUrl('http://100.64.0.1:1990/img.jpg'), true);
  assert.equal(isRenderableImageUrl('/pc/api/chat/attachments/1'), true);
  assert.equal(isRenderableImageUrl('data:image/png;base64,AQID'), true);
  assert.equal(isRenderableImageUrl('//protocol-relative.example.com/a.png'), false);
  assert.equal(isRenderableImageUrl('javascript:alert(1)'), false);
  assert.equal(isRenderableImageUrl(''), false);

  assert.equal(deriveImageDisplayName('Dark Wallpaper', 'https://example.com/x.png'), 'Dark Wallpaper');
  assert.equal(deriveImageDisplayName('', 'https://example.com/walls/amoled%20night.png?w=1080'), 'amoled night.png');
  assert.equal(deriveImageDisplayName('', 'data:image/png;base64,AQID'), 'Image');
});

test('canSteerQueuedMessage, upsertChatMessage, reconcileQueuedMessages, and parseChatSseBlocks handle steering lifecycle', () => {
  const runningUser: ChatMessage = {
    id: 'u1',
    role: 'user',
    engine: 'gemini',
    text: 'first',
    created_at: 10,
    status: 'done',
    queued: false,
  };
  const queuedUser: ChatMessage = {
    id: 'u2',
    role: 'user',
    engine: 'gemini',
    text: 'second',
    created_at: 11,
    status: 'done',
    client_id: 'c2',
    queued: true,
  };
  const optQueued: ChatMessage = { ...queuedUser, id: 'opt-u-c2', optimistic: true };

  assert.equal(canSteerQueuedMessage(queuedUser, true), true);
  assert.equal(canSteerQueuedMessage(queuedUser, false), false);
  assert.equal(canSteerQueuedMessage(optQueued, true), false);
  assert.equal(canSteerQueuedMessage(runningUser, true), false);

  // shouldPollChat stays true if any message is queued
  assert.equal(shouldPollChat([runningUser, queuedUser], false, true, true), true);

  // upsertChatMessage confirms optimistic bubble by client_id and clears paired placeholder
  const pendingPlaceholder: ChatMessage = {
    id: 'a-pend',
    role: 'assistant',
    engine: 'gemini',
    text: '',
    created_at: 12,
    status: 'pending',
    reply_to: 'u2',
  };
  const confirmed = upsertChatMessage([runningUser, optQueued, pendingPlaceholder], queuedUser);
  assert.deepEqual(
    confirmed.map((m) => [m.id, m.queued, m.optimistic]),
    [
      ['u1', false, undefined],
      ['u2', true, false],
      ['a-pend', undefined, undefined],
    ],
  );

  const steered = upsertChatMessage(confirmed, { ...queuedUser, queued: false, steered: true }, 'a-pend');
  assert.deepEqual(
    steered.map((m) => [m.id, m.queued, m.steered]),
    [
      ['u1', false, undefined],
      ['u2', false, true],
    ],
  );

  // reconcileQueuedMessages clears stale queued flags based on server snapshot
  const reconciled = reconcileQueuedMessages([runningUser, queuedUser], [], true);
  assert.equal(reconciled[1].queued, false);
  const kept = reconcileQueuedMessages([runningUser, queuedUser], ['u2'], true);
  assert.equal(kept, kept);
  assert.equal(kept[1].queued, true);
  const idleCleared = reconcileQueuedMessages([runningUser, queuedUser], ['u2'], false);
  assert.equal(idleCleared[1].queued, false);

  // parseChatSseBlocks parses snapshot, message, progress, ignores comments and preserves incomplete tail
  const raw =
    ': ping\n\n' +
    'event: snapshot\ndata: {"engine":"gemini","running":true,"queued_ids":["u2"],"ts":100}\n\n' +
    'event: message\ndata: {"message":{"id":"u2","role":"user","engine":"gemini","text":"second","created_at":11,"status":"done","queued":false,"steered":true},"running":true,"steered":true,"ts":101}\n\n' +
    'event: progress\ndata: {"running":true,"progress":{"engine":"gemini","started":10,"steps":[{"t":11,"text":"reading file"}]},"ts":102}\n\n' +
    'event: message\ndata: {"partial":true';
  const parsed = parseChatSseBlocks(raw);
  assert.equal(parsed.events.length, 3);
  assert.equal(parsed.events[0].event, 'snapshot');
  if (parsed.events[0].event === 'snapshot') {
    assert.deepEqual(parsed.events[0].data.queued_ids, ['u2']);
    assert.equal(parsed.events[0].data.running, true);
  }
  assert.equal(parsed.events[1].event, 'message');
  if (parsed.events[1].event === 'message') {
    assert.equal(parsed.events[1].data.steered, true);
    assert.equal(parsed.events[1].data.message.queued, false);
  }
  assert.equal(parsed.events[2].event, 'progress');
  assert.equal(parsed.rest, 'event: message\ndata: {"partial":true');
});
