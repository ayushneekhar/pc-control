import assert from 'node:assert/strict';
import test from 'node:test';
import {
  _resetDraftStateForTests,
  hasActiveDraft,
  isOtaVersionNewer,
  nextReloadPhase,
  parseOtaTrigger,
  setHasActiveDraft,
  shouldAutoApplyTrigger,
  subscribeDraftState,
} from './otaTriggerLogic';

test('parseOtaTrigger parses valid payloads and rejects null/malformed ones', () => {
  assert.deepEqual(parseOtaTrigger({ version: '64', notes: '  Fresh build  ', triggered_at: 1700000000 }), {
    version: '64',
    notes: 'Fresh build',
    triggeredAt: 1700000000,
  });
  assert.deepEqual(parseOtaTrigger({ version: 65 }), { version: '65' });
  assert.equal(parseOtaTrigger({ version: null }), null);
  assert.equal(parseOtaTrigger({ version: '' }), null);
  assert.equal(parseOtaTrigger({ version: 'v64' }), null);
  assert.equal(parseOtaTrigger(null), null);
  assert.equal(parseOtaTrigger(['64']), null);
});

test('isOtaVersionNewer compares numeric OTA versions safely', () => {
  assert.equal(isOtaVersionNewer('64', '63'), true);
  assert.equal(isOtaVersionNewer('64', '64'), false);
  assert.equal(isOtaVersionNewer('63', '64'), false);
  assert.equal(isOtaVersionNewer('64', null), true);
  assert.equal(isOtaVersionNewer('100', '99'), true);
  assert.equal(isOtaVersionNewer('abc', '63'), false);
});

test('shouldAutoApplyTrigger enforces version comparison, blacklist, and MMKV loop guard', () => {
  const trigger = { version: '64', notes: 'fix' };

  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: true, currentVersion: '63', lastAttempt: null }),
    { shouldApply: true, reason: 'ok' },
  );

  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: false, currentVersion: '63', lastAttempt: null }),
    { shouldApply: false, reason: 'ota_unavailable' },
  );

  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: true, currentVersion: '64', lastAttempt: null }),
    { shouldApply: false, reason: 'not_newer' },
  );

  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: true, currentVersion: '63', lastAttempt: null, blacklist: ['64'] }),
    { shouldApply: false, reason: 'blacklisted' },
  );

  // Loop guard: failed attempt for v64 blocks retrying v64 automatically
  assert.deepEqual(
    shouldAutoApplyTrigger({
      trigger,
      otaAvailable: true,
      currentVersion: '63',
      lastAttempt: { version: '64', status: 'failed', error: 'bad signature', at: 1 },
    }),
    { shouldApply: false, reason: 'already_failed' },
  );

  // Loop guard: already applied v64 blocks re-applying v64
  assert.deepEqual(
    shouldAutoApplyTrigger({
      trigger,
      otaAvailable: true,
      currentVersion: '63',
      lastAttempt: { version: '64', status: 'applied', at: 1 },
    }),
    { shouldApply: false, reason: 'already_applied' },
  );

  // Newer triggered version v65 IS allowed even if v64 previously failed
  assert.deepEqual(
    shouldAutoApplyTrigger({
      trigger: { version: '65' },
      otaAvailable: true,
      currentVersion: '63',
      lastAttempt: { version: '64', status: 'failed', error: 'network', at: 1 },
    }),
    { shouldApply: true, reason: 'ok' },
  );

  // In-flight or busy auto-apply blocks duplicate runs
  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: true, currentVersion: '63', lastAttempt: null, inFlightVersion: '64' }),
    { shouldApply: false, reason: 'in_flight' },
  );
  assert.deepEqual(
    shouldAutoApplyTrigger({ trigger, otaAvailable: true, currentVersion: '63', lastAttempt: null, autoStatus: 'held_for_draft' }),
    { shouldApply: false, reason: 'busy_held_for_draft' },
  );
});

test('draft-hold state tracks active drafts and notifies subscribers on change', () => {
  _resetDraftStateForTests();
  assert.equal(hasActiveDraft(), false);
  assert.equal(nextReloadPhase(hasActiveDraft()), 'reloading');

  const events: boolean[] = [];
  const unsub = subscribeDraftState((active) => events.push(active));

  setHasActiveDraft(true);
  setHasActiveDraft(true); // duplicate ignored
  assert.equal(hasActiveDraft(), true);
  assert.equal(nextReloadPhase(hasActiveDraft()), 'held_for_draft');

  setHasActiveDraft(false);
  assert.equal(hasActiveDraft(), false);
  assert.equal(nextReloadPhase(hasActiveDraft()), 'reloading');
  assert.deepEqual(events, [true, false]);

  unsub();
  setHasActiveDraft(true);
  assert.deepEqual(events, [true, false]);
  _resetDraftStateForTests();
});
