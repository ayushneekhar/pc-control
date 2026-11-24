import type { DriveItem, SpaceCandidate, SpaceFreeItemRequest, TransferJob } from './api';

export type SpaceMode = 'watched' | 'all' | 'drive';
export type SpaceFilter = 'all' | 'movie' | 'season';
export type SpaceSort = 'size' | 'title';

export function filterAndSortCandidates(
  items: SpaceCandidate[],
  filter: SpaceFilter,
  sort: SpaceSort,
): SpaceCandidate[] {
  const filtered = filter === 'all' ? items : items.filter((it) => it.kind === filter);
  return [...filtered].sort((a, b) => {
    if (sort === 'title') {
      const cmp = a.title.localeCompare(b.title, undefined, { sensitivity: 'base' });
      if (cmp !== 0) return cmp;
      if (a.kind === 'season' && b.kind === 'season') {
        return (a.season_number ?? 0) - (b.season_number ?? 0);
      }
      return b.size_bytes - a.size_bytes;
    }
    if (b.size_bytes !== a.size_bytes) return b.size_bytes - a.size_bytes;
    return a.title.localeCompare(b.title, undefined, { sensitivity: 'base' });
  });
}

export function filterAndSortDriveItems(
  items: DriveItem[],
  filter: SpaceFilter,
  sort: SpaceSort,
): DriveItem[] {
  const filtered = filter === 'all' ? items : items.filter((it) => it.kind === filter);
  return [...filtered].sort((a, b) => {
    if (sort === 'title') {
      const cmp = a.title.localeCompare(b.title, undefined, { sensitivity: 'base' });
      if (cmp !== 0) return cmp;
      if (a.kind === 'season' && b.kind === 'season') {
        return (a.season_number ?? 0) - (b.season_number ?? 0);
      }
      return b.size_bytes - a.size_bytes;
    }
    if (b.size_bytes !== a.size_bytes) return b.size_bytes - a.size_bytes;
    return a.title.localeCompare(b.title, undefined, { sensitivity: 'base' });
  });
}

export function summarizeSelection(items: SpaceCandidate[], selectedIds: ReadonlySet<string>) {
  const selected = items.filter((it) => selectedIds.has(it.id));
  let totalBytes = 0;
  const unwatched: SpaceCandidate[] = [];
  for (const it of selected) {
    totalBytes += it.size_bytes;
    if (it.warn_unwatched && it.unwatched_users.length > 0) {
      unwatched.push(it);
    }
  }
  return {
    selected,
    count: selected.length,
    totalBytes,
    unwatched,
  };
}

export function summarizeDriveSelection(items: DriveItem[], selectedPaths: ReadonlySet<string>) {
  const selected = items.filter((it) => selectedPaths.has(it.rel_path));
  let totalBytes = 0;
  for (const it of selected) {
    totalBytes += it.size_bytes;
  }
  return {
    selected,
    count: selected.length,
    totalBytes,
  };
}

export function toFreeRequest(items: SpaceCandidate[]): SpaceFreeItemRequest[] {
  return items.map((it) => {
    if (it.kind === 'movie' && it.radarr_id != null) {
      return { kind: 'movie', radarr_id: it.radarr_id };
    }
    if (it.kind === 'season' && it.series_id != null && it.season_number != null) {
      return { kind: 'season', series_id: it.series_id, season_number: it.season_number };
    }
    return { id: it.id };
  });
}

const ACTIVE_JOB_STATUSES = new Set(['queued', 'copying', 'verifying', 'cleaning']);

export function selectionStateForVisible(visibleKeys: readonly string[], selectedKeys: ReadonlySet<string>) {
  const visibleCount = visibleKeys.length;
  let selectedVisibleCount = 0;
  for (const key of visibleKeys) {
    if (selectedKeys.has(key)) selectedVisibleCount += 1;
  }
  return {
    visibleCount,
    selectedVisibleCount,
    allSelected: visibleCount > 0 && selectedVisibleCount === visibleCount,
    someSelected: selectedVisibleCount > 0 && selectedVisibleCount < visibleCount,
  };
}

export function toggleSelectAllVisible(
  visibleKeys: readonly string[],
  selectedKeys: ReadonlySet<string>,
): Set<string> {
  const next = new Set(selectedKeys);
  if (visibleKeys.length === 0) return next;
  const allSelected = visibleKeys.every((k) => next.has(k));
  if (allSelected) {
    for (const k of visibleKeys) next.delete(k);
  } else {
    for (const k of visibleKeys) next.add(k);
  }
  return next;
}

export function mergeEnqueuedJobs(existing: TransferJob[], created: TransferJob[]): TransferJob[] {
  if (created.length === 0) return existing;
  const byId = new Map<string, TransferJob>();
  for (const j of existing) byId.set(j.id, j);
  for (const j of created) byId.set(j.id, j);
  return Array.from(byId.values());
}

export function activeTransferJobs(jobs: TransferJob[]): TransferJob[] {
  return jobs.filter((j) => ACTIVE_JOB_STATUSES.has(j.status));
}

const RECENT_TERMINAL_WINDOW_SEC = 1800; // 30 minutes
const MAX_RECENT_TERMINAL_JOBS = 6;

export function visibleDownloadDriveJobs(
  jobs: TransferJob[],
  nowSec = Math.floor(Date.now() / 1000),
): TransferJob[] {
  const active: TransferJob[] = [];
  const terminal: TransferJob[] = [];
  for (const j of jobs) {
    if (ACTIVE_JOB_STATUSES.has(j.status)) {
      active.push(j);
    } else if (
      j.status === 'failed' ||
      nowSec - (j.finished_at ?? j.created_at) <= RECENT_TERMINAL_WINDOW_SEC
    ) {
      terminal.push(j);
    }
  }
  terminal.sort((a, b) => (b.finished_at ?? b.created_at) - (a.finished_at ?? a.created_at));
  return [...active, ...terminal.slice(0, MAX_RECENT_TERMINAL_JOBS)];
}

export function transferStatusLabel(job: TransferJob): string {
  switch (job.status) {
    case 'queued':
      return 'Queued';
    case 'copying':
      return job.op === 'restore' ? 'Restoring' : 'Offloading';
    case 'verifying':
      return 'Verifying';
    case 'cleaning':
      return 'Finishing';
    case 'done':
      return 'Done';
    case 'failed':
      return 'Failed';
    case 'cancelled':
      return 'Cancelled';
    default:
      return job.status;
  }
}

export function parseSpaceJobsSseBlocks(buffer: string): {
  events: { event: string; jobs: TransferJob[] }[];
  rest: string;
} {
  const events: { event: string; jobs: TransferJob[] }[] = [];
  let buf = buffer;
  let idx: number;
  while ((idx = buf.indexOf('\n\n')) >= 0) {
    const block = buf.slice(0, idx);
    buf = buf.slice(idx + 2);
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue;
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    if (!dataLines.length) continue;
    try {
      const parsed = JSON.parse(dataLines.join('\n')) as { jobs?: TransferJob[] };
      if (Array.isArray(parsed?.jobs)) {
        events.push({ event, jobs: parsed.jobs });
      }
    } catch {
      // ignore malformed frames
    }
  }
  return { events, rest: buf };
}

const SOURCE_LABELS: Record<string, string> = {
  radarr: 'Radarr',
  sonarr: 'Sonarr',
  jellyfin: 'Jellyfin',
  qbt: 'qBittorrent',
  backend: 'Backend',
  quota: 'Drive quota',
  movies: 'Drive movies',
  shows: 'Drive shows',
};

export function formatUpstreamErrors(errors: Record<string, string> | undefined): string | null {
  if (!errors) return null;
  const names = Object.keys(errors)
    .filter((k) => k !== 'hdd')
    .map((k) => SOURCE_LABELS[k] ?? k);
  if (!names.length) return null;
  return `${names.join(', ')} unavailable`;
}
