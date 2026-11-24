const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;

function ordinal(day: number): string {
  const mod100 = day % 100;
  if (mod100 >= 11 && mod100 <= 13) return `${day}th`;
  switch (day % 10) {
    case 1:
      return `${day}st`;
    case 2:
      return `${day}nd`;
    case 3:
      return `${day}rd`;
    default:
      return `${day}th`;
  }
}

function formatClock12h(d: Date): string {
  const h24 = d.getHours();
  const mins = String(d.getMinutes()).padStart(2, '0');
  const ampm = h24 >= 12 ? 'pm' : 'am';
  const h12 = h24 % 12 || 12;
  return `${h12}:${mins} ${ampm}`;
}

function startOfLocalDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/**
 * Format an ISO 8601 UTC reset timestamp in the device's local timezone:
 * - Under 1 hour: "Resets in 42 min"
 * - Today: "Resets today, 12:20 pm"
 * - Tomorrow: "Resets tomorrow, 9:05 am"
 * - Later: "Resets Sun 29th Nov, 6:30 am"
 */
export function formatResetTime(resetsAt: string | null | undefined, now: Date = new Date()): string | null {
  if (!resetsAt) return null;
  const target = new Date(resetsAt);
  if (Number.isNaN(target.getTime())) return null;

  const diffMs = target.getTime() - now.getTime();
  if (diffMs <= 0) return 'Resets now';
  if (diffMs < 60 * 60 * 1000) {
    const mins = Math.max(1, Math.ceil(diffMs / 60_000));
    return `Resets in ${mins} min`;
  }

  const timeStr = formatClock12h(target);
  const dayDiff = Math.round((startOfLocalDay(target) - startOfLocalDay(now)) / 86_400_000);
  if (dayDiff === 0) return `Resets today, ${timeStr}`;
  if (dayDiff === 1) return `Resets tomorrow, ${timeStr}`;

  const wd = WEEKDAYS[target.getDay()];
  const dayOrd = ordinal(target.getDate());
  const mon = MONTHS[target.getMonth()];
  return `Resets ${wd} ${dayOrd} ${mon}, ${timeStr}`;
}

const DURATION_PART_RE = /(\d+)\s*([dhms])/gi;

/**
 * Parse a compact duration string such as "155h 9m", "4d 10h", "2h 38m", or "45m" into milliseconds.
 */
export function parseDurationMs(dur: string | null | undefined): number | null {
  if (!dur) return null;
  let totalSec = 0;
  let matched = false;
  DURATION_PART_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = DURATION_PART_RE.exec(dur)) !== null) {
    matched = true;
    const n = Number(m[1]);
    const unit = m[2].toLowerCase();
    if (unit === 'd') totalSec += n * 86_400;
    else if (unit === 'h') totalSec += n * 3_600;
    else if (unit === 'm') totalSec += n * 60;
    else if (unit === 's') totalSec += n;
  }
  if (!matched || totalSec <= 0) return null;
  return totalSec * 1000;
}

/**
 * Format a positive duration in milliseconds into a compact string:
 * - >= 24h: "6d 11h" (or "2d" when exact days)
 * - 1h..24h: "2h 38m" (or "4h" when exact hours)
 * - < 1h: "42m" (minimum "1m")
 */
export function formatCompactDuration(ms: number): string | null {
  if (!Number.isFinite(ms) || ms <= 0) return null;
  const totalMins = Math.max(1, Math.floor(ms / 60_000));
  const days = Math.floor(totalMins / (24 * 60));
  const hours = Math.floor((totalMins % (24 * 60)) / 60);
  const mins = totalMins % 60;
  if (days > 0) {
    return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  }
  if (hours > 0) {
    return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
  }
  return `${mins}m`;
}

/**
 * Format the remaining time until an ISO 8601 UTC reset timestamp as a compact duration ("6d 11h", "2h 44m", "18m").
 * Returns null when the timestamp is missing, invalid, or not in the future.
 */
export function formatCompactResetDuration(
  resetsAt: string | null | undefined,
  now: Date = new Date(),
): string | null {
  if (!resetsAt) return null;
  const target = new Date(resetsAt);
  if (Number.isNaN(target.getTime())) return null;
  return formatCompactDuration(target.getTime() - now.getTime());
}
