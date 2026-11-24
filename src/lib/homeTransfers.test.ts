import assert from 'node:assert/strict';
import test from 'node:test';
import type { TransferJob } from './api';
import { summarizeHomeTransfers } from './homeTransfers';
import type { DownloadsReply } from './live';

const MB = 1024 * 1024;

function makeDownloads(count: number, speed: number, progress: number | null = 0.42): DownloadsReply {
  return {
    enabled: true,
    qbt: 'ok',
    downloads: Array.from({ length: count }, (_, i) => ({
      id: `t${i}`,
      name: `Torrent ${i}`,
      torrent: `t${i}`,
      category: 'movies',
      state: speed > 0 ? 'downloading' : 'stalled',
      progress: progress ?? 0,
      speed: count > 0 ? speed / count : 0,
      eta: 60,
      size: 1000 * MB,
      done: 420 * MB,
    })),
    total: {
      count,
      downloading: speed > 0 ? count : 0,
      progress,
      speed,
      eta: 60,
    },
  };
}

function makeJob(overrides: Partial<TransferJob> = {}): TransferJob {
  return {
    id: 'j1',
    op: 'offload',
    item_id: 'movie:1',
    kind: 'movie',
    title: 'Inception',
    subtitle: null,
    rel_path: 'Movies/Inception (2010)',
    size_bytes: 1000 * MB,
    status: 'copying',
    bytes_done: 500 * MB,
    bytes_total: 1000 * MB,
    pct: 50,
    speed_bps: 11.2 * MB,
    eta_s: 45,
    error: null,
    warnings: [],
    created_at: 100,
    started_at: 101,
    finished_at: null,
    ...overrides,
  };
}

test('summarizeHomeTransfers returns empty summary when nothing is active', () => {
  assert.deepEqual(summarizeHomeTransfers(null, []), { activeCount: 0 });
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(0, 0, null), [makeJob({ status: 'done' }), makeJob({ id: 'j2', status: 'failed' })]),
    { activeCount: 0 },
  );
});

test('summarizeHomeTransfers formats torrent-only downloads', () => {
  assert.deepEqual(summarizeHomeTransfers(makeDownloads(2, 12.4 * MB, 0.65), []), {
    activeCount: 2,
    subtitle: '2 active · ↓ 12.4 MB/s',
    trailing: '65%',
    trailingTone: 'warn',
  });

  assert.deepEqual(summarizeHomeTransfers(makeDownloads(1, 0, null), []), {
    activeCount: 1,
    subtitle: '1 active',
    trailing: undefined,
    trailingTone: undefined,
  });
});

test('summarizeHomeTransfers formats Drive upload-only transfers', () => {
  assert.deepEqual(summarizeHomeTransfers(null, [makeJob({ speed_bps: 11.2 * MB, bytes_done: 350 * MB, bytes_total: 1000 * MB })]), {
    activeCount: 1,
    subtitle: '1 uploading · ↑ 11.2 MB/s',
    trailing: '35%',
    trailingTone: 'accent',
  });

  // Multiple uploads: one copying, one queued
  assert.deepEqual(
    summarizeHomeTransfers(null, [
      makeJob({ id: 'j1', status: 'copying', speed_bps: 10 * MB, bytes_done: 500 * MB, bytes_total: 1000 * MB }),
      makeJob({ id: 'j2', status: 'queued', speed_bps: 0, bytes_done: 0, bytes_total: 1000 * MB }),
    ]),
    {
      activeCount: 2,
      subtitle: '2 uploading · ↑ 10.0 MB/s',
      trailing: '25%',
      trailingTone: 'accent',
    },
  );

  // Verifying phase with 0 speed
  assert.deepEqual(
    summarizeHomeTransfers(null, [makeJob({ status: 'verifying', speed_bps: 0 })]),
    {
      activeCount: 1,
      subtitle: '1 uploading · verifying',
      trailing: '100%',
      trailingTone: 'accent',
    },
  );

  // Queued only: no progress percentage yet
  assert.deepEqual(
    summarizeHomeTransfers(null, [makeJob({ status: 'queued', speed_bps: 0, bytes_done: 0 })]),
    {
      activeCount: 1,
      subtitle: '1 uploading',
      trailing: undefined,
      trailingTone: undefined,
    },
  );
});

test('summarizeHomeTransfers formats Drive restore-only transfers', () => {
  assert.deepEqual(
    summarizeHomeTransfers(null, [makeJob({ op: 'restore', speed_bps: 8.5 * MB, bytes_done: 400 * MB, bytes_total: 1000 * MB })]),
    {
      activeCount: 1,
      subtitle: '1 restoring · ↓ 8.5 MB/s',
      trailing: '40%',
      trailingTone: 'accent',
    },
  );
});

test('summarizeHomeTransfers combines torrent downloads and Drive uploads cleanly', () => {
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(2, 4.2 * MB, 0.5), [makeJob({ speed_bps: 10.8 * MB })]),
    {
      activeCount: 3,
      subtitle: '↓ 4.2 MB/s · ↑ 10.8 MB/s',
      trailing: '3 active',
      trailingTone: 'dim',
    },
  );

  // Torrent downloading + Drive upload queued (0 speed)
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(1, 4.2 * MB, 0.5), [makeJob({ status: 'queued', speed_bps: 0 })]),
    {
      activeCount: 2,
      subtitle: '↓ 4.2 MB/s · 1 uploading',
      trailing: '2 active',
      trailingTone: 'dim',
    },
  );

  // Torrent stalled (0 speed) + Drive uploading
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(1, 0, 0.2), [makeJob({ speed_bps: 10.8 * MB })]),
    {
      activeCount: 2,
      subtitle: '1 active · ↑ 10.8 MB/s',
      trailing: '2 active',
      trailingTone: 'dim',
    },
  );

  // Both torrent and Drive restore + Drive upload -> downward speeds summed
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(1, 4.0 * MB, 0.5), [
      makeJob({ id: 'j1', op: 'restore', speed_bps: 2.5 * MB }),
      makeJob({ id: 'j2', op: 'offload', speed_bps: 9.0 * MB }),
    ]),
    {
      activeCount: 3,
      subtitle: '↓ 6.5 MB/s · ↑ 9.0 MB/s',
      trailing: '3 active',
      trailingTone: 'dim',
    },
  );
});

test('summarizeHomeTransfers combines torrent downloads and Drive restores', () => {
  assert.deepEqual(
    summarizeHomeTransfers(makeDownloads(1, 3.0 * MB, 0.6), [makeJob({ op: 'restore', speed_bps: 5.0 * MB })]),
    {
      activeCount: 2,
      subtitle: '2 active · ↓ 8.0 MB/s',
      trailing: '60%',
      trailingTone: 'warn',
    },
  );
});
