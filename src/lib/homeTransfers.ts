import type { TransferJob } from './api';
import { rate } from './format';
import type { DownloadsReply } from './live';
import { activeTransferJobs } from './spaceHelpers';

export type HomeTransfersSummary = {
  activeCount: number;
  subtitle?: string;
  trailing?: string;
  trailingTone?: 'warn' | 'accent' | 'dim';
};

function computeDriveProgress(activeDrive: TransferJob[]): number | null {
  const started = activeDrive.some((j) => j.status !== 'queued');
  if (!started) return null;

  let sumDone = 0;
  let sumTotal = 0;
  for (const j of activeDrive) {
    const total = j.bytes_total || j.size_bytes || 0;
    if (total > 0) {
      sumTotal += total;
      const done =
        j.status === 'verifying' || j.status === 'cleaning'
          ? total
          : Math.min(total, Math.max(0, j.bytes_done));
      sumDone += done;
    }
  }
  if (sumTotal > 0) {
    return Math.min(100, Math.max(0, Math.round((sumDone / sumTotal) * 100)));
  }

  const avg = activeDrive.reduce((s, j) => s + (Number.isFinite(j.pct) ? j.pct : 0), 0) / activeDrive.length;
  return Math.min(100, Math.max(0, Math.round(avg)));
}

function drivePhaseHint(jobs: TransferJob[]): string | null {
  if (jobs.some((j) => j.status === 'verifying')) return 'verifying';
  if (jobs.some((j) => j.status === 'cleaning')) return 'finishing';
  return null;
}

export function summarizeHomeTransfers(
  downloads: DownloadsReply | null,
  driveJobs: TransferJob[],
): HomeTransfersSummary {
  const torrentCount = downloads?.downloads?.length ?? 0;
  const torrentSpeed = Math.max(0, downloads?.total?.speed ?? 0);
  const torrentProgress =
    downloads?.total?.progress == null ? null : Math.round(downloads.total.progress * 100);

  const activeDrive = activeTransferJobs(driveJobs);
  const offloads = activeDrive.filter((j) => j.op === 'offload');
  const restores = activeDrive.filter((j) => j.op === 'restore');

  const uploadCount = offloads.length;
  const restoreCount = restores.length;
  const uploadSpeed = offloads.reduce(
    (s, j) => s + (j.status === 'copying' && j.speed_bps > 0 ? j.speed_bps : 0),
    0,
  );
  const restoreSpeed = restores.reduce(
    (s, j) => s + (j.status === 'copying' && j.speed_bps > 0 ? j.speed_bps : 0),
    0,
  );

  const downCount = torrentCount + restoreCount;
  const downSpeed = torrentSpeed + restoreSpeed;
  const activeCount = torrentCount + uploadCount + restoreCount;

  if (activeCount === 0) {
    return { activeCount: 0 };
  }

  const driveProgress = activeDrive.length > 0 ? computeDriveProgress(activeDrive) : null;

  // 1. Only torrent downloads active
  if (torrentCount > 0 && uploadCount === 0 && restoreCount === 0) {
    return {
      activeCount,
      subtitle: torrentSpeed > 0 ? `${torrentCount} active · ↓ ${rate(torrentSpeed)}` : `${torrentCount} active`,
      trailing: torrentProgress != null ? `${torrentProgress}%` : undefined,
      trailingTone: torrentProgress != null ? 'warn' : undefined,
    };
  }

  // 2. Only Drive uploads (offloads) active
  if (torrentCount === 0 && uploadCount > 0 && restoreCount === 0) {
    const phase = uploadSpeed > 0 ? null : drivePhaseHint(offloads);
    const subtitle =
      uploadSpeed > 0
        ? `${uploadCount} uploading · ↑ ${rate(uploadSpeed)}`
        : phase
          ? `${uploadCount} uploading · ${phase}`
          : `${uploadCount} uploading`;
    return {
      activeCount,
      subtitle,
      trailing: driveProgress != null ? `${driveProgress}%` : undefined,
      trailingTone: driveProgress != null ? 'accent' : undefined,
    };
  }

  // 3. Only Drive restores active
  if (torrentCount === 0 && uploadCount === 0 && restoreCount > 0) {
    const phase = restoreSpeed > 0 ? null : drivePhaseHint(restores);
    const subtitle =
      restoreSpeed > 0
        ? `${restoreCount} restoring · ↓ ${rate(restoreSpeed)}`
        : phase
          ? `${restoreCount} restoring · ${phase}`
          : `${restoreCount} restoring`;
    return {
      activeCount,
      subtitle,
      trailing: driveProgress != null ? `${driveProgress}%` : undefined,
      trailingTone: driveProgress != null ? 'accent' : undefined,
    };
  }

  // 4. Both downward (torrent and/or restore) and upward (Drive offload) active
  if (downCount > 0 && uploadCount > 0) {
    let subtitle: string;
    if (downSpeed > 0 && uploadSpeed > 0) {
      subtitle = `↓ ${rate(downSpeed)} · ↑ ${rate(uploadSpeed)}`;
    } else if (downSpeed > 0) {
      subtitle = `↓ ${rate(downSpeed)} · ${uploadCount} uploading`;
    } else if (uploadSpeed > 0) {
      subtitle = `${downCount} active · ↑ ${rate(uploadSpeed)}`;
    } else {
      subtitle = `${downCount} active · ${uploadCount} uploading`;
    }
    return {
      activeCount,
      subtitle,
      trailing: `${activeCount} active`,
      trailingTone: 'dim',
    };
  }

  // 5. Both torrents and Drive restores active (no uploads)
  const subtitle = downSpeed > 0 ? `${activeCount} active · ↓ ${rate(downSpeed)}` : `${activeCount} active`;
  const pct = torrentProgress ?? driveProgress;
  return {
    activeCount,
    subtitle,
    trailing: pct != null ? `${pct}%` : undefined,
    trailingTone: torrentProgress != null ? 'warn' : driveProgress != null ? 'accent' : undefined,
  };
}
