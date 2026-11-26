import assert from 'node:assert/strict';
import test from 'node:test';
import {
  computeAge,
  formatCompactCount,
  formatDate,
  formatMoney,
  formatRuntime,
  isRequestableMovie,
  isRequestableSeason,
} from './seerrHelpers';

test('formatRuntime formats minutes and hours cleanly', () => {
  assert.equal(formatRuntime(null), null);
  assert.equal(formatRuntime(0), null);
  assert.equal(formatRuntime(47), '47m');
  assert.equal(formatRuntime(120), '2h');
  assert.equal(formatRuntime(148), '2h 28m');
});

test('formatDate formats ISO dates and passes through unparseable values', () => {
  assert.equal(formatDate(null), null);
  assert.equal(formatDate('2010-07-15'), 'Jul 15, 2010');
  assert.equal(formatDate('1974-11-11'), 'Nov 11, 1974');
  assert.equal(formatDate('TBA'), 'TBA');
});

test('computeAge calculates living and deceased ages accurately', () => {
  const ref = new Date(2026, 10, 26); // Nov 26, 2026
  assert.equal(computeAge('1974-11-11', null, ref), 52);
  assert.equal(computeAge('1974-12-01', null, ref), 51);
  assert.equal(computeAge('1950-05-10', '2020-05-09', ref), 69);
  assert.equal(computeAge('1950-05-10', '2020-05-10', ref), 70);
  assert.equal(computeAge(null, null, ref), null);
});

test('formatMoney and formatCompactCount format large numbers concisely', () => {
  assert.equal(formatMoney(160_000_000), '$160M');
  assert.equal(formatMoney(12_500_000), '$12.5M');
  assert.equal(formatMoney(1_450_000_000), '$1.45B');
  assert.equal(formatMoney(0), null);

  assert.equal(formatCompactCount(40829), '41k');
  assert.equal(formatCompactCount(2_891_347), '2.9M');
  assert.equal(formatCompactCount(1_250), '1.3k');
  assert.equal(formatCompactCount(420), '420');
});

test('isRequestableMovie and isRequestableSeason allow none and deleted only', () => {
  assert.equal(isRequestableMovie('none'), true);
  assert.equal(isRequestableMovie('deleted'), true);
  assert.equal(isRequestableMovie('available'), false);
  assert.equal(isRequestableMovie('requested'), false);
  assert.equal(isRequestableMovie('pending'), false);

  assert.equal(isRequestableSeason('none'), true);
  assert.equal(isRequestableSeason('deleted'), true);
  assert.equal(isRequestableSeason('partial'), false);
});
