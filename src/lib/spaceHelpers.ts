import type { SpaceCandidate, SpaceFreeItemRequest } from './api';

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

const SOURCE_LABELS: Record<string, string> = {
  radarr: 'Radarr',
  sonarr: 'Sonarr',
  jellyfin: 'Jellyfin',
  qbt: 'qBittorrent',
  backend: 'Backend',
};

export function formatUpstreamErrors(errors: Record<string, string> | undefined): string | null {
  if (!errors) return null;
  const names = Object.keys(errors)
    .filter((k) => k !== 'hdd')
    .map((k) => SOURCE_LABELS[k] ?? k);
  if (!names.length) return null;
  return `${names.join(', ')} unavailable`;
}
