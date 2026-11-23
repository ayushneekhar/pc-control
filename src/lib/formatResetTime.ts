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
