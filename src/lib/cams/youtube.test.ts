// yarn test:js
/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { youtubeLink } from './youtube';

test('finds the link in what the YouTube app shares', () => {
  assert.equal(youtubeLink('Calming Music for Cats https://youtu.be/AbCdEfGhI_0?si=abc'), 'https://youtu.be/AbCdEfGhI_0?si=abc');
  assert.equal(youtubeLink('https://www.youtube.com/watch?v=AbCdEfGhI_0&t=30'), 'https://www.youtube.com/watch?v=AbCdEfGhI_0&t=30');
  assert.equal(youtubeLink('https://m.youtube.com/watch?feature=share&v=AbCdEfGhI_0'), 'https://m.youtube.com/watch?feature=share&v=AbCdEfGhI_0');
  assert.equal(youtubeLink('live: https://youtube.com/live/AbCdEfGhI_0'), 'https://youtube.com/live/AbCdEfGhI_0');
  assert.equal(youtubeLink('https://music.youtube.com/watch?v=AbCdEfGhI_0'), 'https://music.youtube.com/watch?v=AbCdEfGhI_0');
});

test('ignores other text and look-alike hosts', () => {
  assert.equal(youtubeLink('look at this cat'), null);
  assert.equal(youtubeLink('https://example.com/watch?v=AbCdEfGhI_0'), null);
  assert.equal(youtubeLink('https://youtu.be/short'), null);
});
