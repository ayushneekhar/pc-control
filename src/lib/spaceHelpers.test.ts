import assert from 'node:assert/strict';
import test from 'node:test';
import type { DriveItem, SpaceCandidate, TransferJob } from './api';
import {
  activeTransferJobs,
  filterAndSortCandidates,
  filterAndSortDriveItems,
  formatUpstreamErrors,
  mergeEnqueuedJobs,
  parseSpaceJobsSseBlocks,
  selectionStateForVisible,
  summarizeDriveSelection,
  summarizeSelection,
  toFreeRequest,
  toggleSelectAllVisible,
  transferStatusLabel,
  visibleDownloadDriveJobs,
} from './spaceHelpers';

const SAMPLE: SpaceCandidate[] = [
  {
    id: 'movie:12',
    kind: 'movie',
    radarr_id: 12,
    title: 'Inception',
    subtitle: null,
    year: 2010,
    size_bytes: 10_000_000_000,
    watched_count: 1,
    total_users: 2,
    watched_users: ['Ayush'],
    unwatched_users: ['Amrita'],
    warn_unwatched: true,
    monitored: true,
  },
  {
    id: 'movie:15',
    kind: 'movie',
    radarr_id: 15,
    title: 'Arrival',
    subtitle: null,
    year: 2016,
    size_bytes: 18_000_000_000,
    watched_count: 2,
    total_users: 2,
    watched_users: ['Amrita', 'Ayush'],
    unwatched_users: [],
    warn_unwatched: false,
    monitored: false,
  },
  {
    id: 'season:5:2',
    kind: 'season',
    series_id: 5,
    season_number: 2,
    title: 'Severance',
    subtitle: 'Season 2',
    year: 2022,
    episode_count: 10,
    size_bytes: 14_000_000_000,
    watched_count: 1,
    total_users: 2,
    watched_users: ['Ayush'],
    unwatched_users: ['Amrita'],
    warn_unwatched: true,
    monitored: true,
  },
  {
    id: 'season:5:1',
    kind: 'season',
    series_id: 5,
    season_number: 1,
    title: 'Severance',
    subtitle: 'Season 1',
    year: 2022,
    episode_count: 9,
    size_bytes: 12_000_000_000,
    watched_count: 2,
    total_users: 2,
    watched_users: ['Amrita', 'Ayush'],
    unwatched_users: [],
    warn_unwatched: false,
    monitored: true,
  },
];

test('filterAndSortCandidates filters by kind and sorts by size or title', () => {
  assert.deepEqual(
    filterAndSortCandidates(SAMPLE, 'all', 'size').map((x) => x.id),
    ['movie:15', 'season:5:2', 'season:5:1', 'movie:12'],
  );
  assert.deepEqual(
    filterAndSortCandidates(SAMPLE, 'all', 'title').map((x) => x.id),
    ['movie:15', 'movie:12', 'season:5:1', 'season:5:2'],
  );
  assert.deepEqual(
    filterAndSortCandidates(SAMPLE, 'movie', 'size').map((x) => x.id),
    ['movie:15', 'movie:12'],
  );
  assert.deepEqual(
    filterAndSortCandidates(SAMPLE, 'season', 'title').map((x) => x.id),
    ['season:5:1', 'season:5:2'],
  );
});

test('summarizeSelection computes totals and unwatched items', () => {
  const s = summarizeSelection(SAMPLE, new Set(['movie:12', 'movie:15', 'season:5:2']));
  assert.equal(s.count, 3);
  assert.equal(s.totalBytes, 42_000_000_000);
  assert.deepEqual(
    s.unwatched.map((x) => x.id),
    ['movie:12', 'season:5:2'],
  );
});

test('toFreeRequest maps movies and seasons to structured requests', () => {
  assert.deepEqual(toFreeRequest([SAMPLE[0], SAMPLE[2]]), [
    { kind: 'movie', radarr_id: 12 },
    { kind: 'season', series_id: 5, season_number: 2 },
  ]);
});

test('formatUpstreamErrors ignores hdd and formats service names', () => {
  assert.equal(formatUpstreamErrors({}), null);
  assert.equal(formatUpstreamErrors({ hdd: 'HDD not mounted' }), null);
  assert.equal(formatUpstreamErrors({ radarr: 'down', qbt: 'timeout' }), 'Radarr, qBittorrent unavailable');
  assert.equal(formatUpstreamErrors({ quota: 'err' }), 'Drive quota unavailable');
});

const DRIVE_SAMPLE: DriveItem[] = [
  {
    id: 'drive:Movies/Inception (2010)',
    kind: 'movie',
    rel_path: 'Movies/Inception (2010)',
    title: 'Inception',
    subtitle: null,
    year: 2010,
    episode_count: null,
    size_bytes: 10_000_000_000,
  },
  {
    id: 'drive:Shows/Severance/Season 02',
    kind: 'season',
    rel_path: 'Shows/Severance/Season 02',
    title: 'Severance',
    subtitle: 'Season 2',
    year: 2022,
    episode_count: 10,
    season_number: 2,
    size_bytes: 16_000_000_000,
  },
  {
    id: 'drive:Shows/Severance/Season 01',
    kind: 'season',
    rel_path: 'Shows/Severance/Season 01',
    title: 'Severance',
    subtitle: 'Season 1',
    year: 2022,
    episode_count: 9,
    season_number: 1,
    size_bytes: 12_000_000_000,
  },
];

test('filterAndSortDriveItems and summarizeDriveSelection', () => {
  assert.deepEqual(
    filterAndSortDriveItems(DRIVE_SAMPLE, 'all', 'size').map((x) => x.rel_path),
    ['Shows/Severance/Season 02', 'Shows/Severance/Season 01', 'Movies/Inception (2010)'],
  );
  assert.deepEqual(
    filterAndSortDriveItems(DRIVE_SAMPLE, 'season', 'title').map((x) => x.rel_path),
    ['Shows/Severance/Season 01', 'Shows/Severance/Season 02'],
  );
  const sel = summarizeDriveSelection(
    DRIVE_SAMPLE,
    new Set(['Movies/Inception (2010)', 'Shows/Severance/Season 01']),
  );
  assert.equal(sel.count, 2);
  assert.equal(sel.totalBytes, 22_000_000_000);
});

test('activeTransferJobs, transferStatusLabel, and parseSpaceJobsSseBlocks', () => {
  const baseJob: TransferJob = {
    id: 'j1',
    op: 'offload',
    item_id: 'movie:12',
    kind: 'movie',
    title: 'Inception',
    subtitle: null,
    rel_path: 'Movies/Inception (2010)',
    size_bytes: 1000,
    status: 'copying',
    bytes_done: 500,
    bytes_total: 1000,
    pct: 50,
    speed_bps: 100,
    eta_s: 5,
    error: null,
    warnings: [],
    created_at: 1,
    started_at: 2,
    finished_at: null,
  };
  const doneJob: TransferJob = { ...baseJob, id: 'j2', status: 'done', pct: 100 };
  const restoreJob: TransferJob = { ...baseJob, id: 'j3', op: 'restore', status: 'verifying' };

  assert.deepEqual(activeTransferJobs([baseJob, doneJob, restoreJob]).map((j) => j.id), ['j1', 'j3']);
  assert.equal(transferStatusLabel(baseJob), 'Offloading');
  assert.equal(transferStatusLabel({ ...baseJob, op: 'restore' }), 'Restoring');
  assert.equal(transferStatusLabel(restoreJob), 'Verifying');
  assert.equal(transferStatusLabel(doneJob), 'Done');

  const raw = `: ping\n\nevent: snapshot\ndata: ${JSON.stringify({ jobs: [baseJob] })}\n\nevent: jobs\ndata: {"jobs":`;
  const parsed = parseSpaceJobsSseBlocks(raw);
  assert.equal(parsed.events.length, 1);
  assert.equal(parsed.events[0].event, 'snapshot');
  assert.equal(parsed.events[0].jobs[0].id, 'j1');
  assert.equal(parsed.rest, 'event: jobs\ndata: {"jobs":');
});

test('selectionStateForVisible and toggleSelectAllVisible handle select all / deselect all', () => {
  const visible = ['movie:12', 'movie:15'];
  const empty = new Set<string>();
  assert.deepEqual(selectionStateForVisible(visible, empty), {
    visibleCount: 2,
    selectedVisibleCount: 0,
    allSelected: false,
    someSelected: false,
  });

  const all = toggleSelectAllVisible(visible, new Set(['season:5:1']));
  assert.deepEqual(Array.from(all).sort(), ['movie:12', 'movie:15', 'season:5:1']);
  assert.deepEqual(selectionStateForVisible(visible, all), {
    visibleCount: 2,
    selectedVisibleCount: 2,
    allSelected: true,
    someSelected: false,
  });

  const deselected = toggleSelectAllVisible(visible, all);
  assert.deepEqual(Array.from(deselected), ['season:5:1']);

  const partial = new Set(['movie:12']);
  assert.deepEqual(selectionStateForVisible(visible, partial), {
    visibleCount: 2,
    selectedVisibleCount: 1,
    allSelected: false,
    someSelected: true,
  });
  assert.deepEqual(Array.from(toggleSelectAllVisible(visible, partial)).sort(), ['movie:12', 'movie:15']);
});

test('mergeEnqueuedJobs and visibleDownloadDriveJobs prioritize active, failed, and recent jobs', () => {
  const makeJob = (id: string, status: TransferJob['status'], finished_at: number | null): TransferJob => ({
    id,
    op: 'offload',
    item_id: `movie:${id}`,
    kind: 'movie',
    title: `Movie ${id}`,
    subtitle: null,
    rel_path: `Movies/Movie ${id}`,
    size_bytes: 1000,
    status,
    bytes_done: 0,
    bytes_total: 1000,
    pct: 0,
    speed_bps: 0,
    eta_s: null,
    error: status === 'failed' ? 'boom' : null,
    warnings: [],
    created_at: 1000,
    started_at: 1001,
    finished_at,
  });

  const j1 = makeJob('1', 'copying', null);
  const j2 = makeJob('2', 'queued', null);
  const merged = mergeEnqueuedJobs([j1], [{ ...j1, pct: 25 }, j2]);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].pct, 25);
  assert.equal(merged[1].id, '2');

  const now = 5000;
  const recentDone = makeJob('3', 'done', now - 120);
  const oldDone = makeJob('4', 'done', now - 7200);
  const oldFailed = makeJob('5', 'failed', now - 7200);
  const visible = visibleDownloadDriveJobs([oldDone, recentDone, j1, oldFailed, j2], now);
  assert.deepEqual(
    visible.map((j) => j.id),
    ['1', '2', '3', '5'],
  );
});
