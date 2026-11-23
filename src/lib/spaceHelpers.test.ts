import assert from 'node:assert/strict';
import test from 'node:test';
import type { SpaceCandidate } from './api';
import {
  filterAndSortCandidates,
  formatUpstreamErrors,
  summarizeSelection,
  toFreeRequest,
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
});
