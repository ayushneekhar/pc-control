/**
 * Rewrites legacy deep-link paths (`vitals`, `health`, `services`) to the unified `system/:tab` route
 * while preserving query strings and hashes.
 */
export function normalizeDeepLinkPath(path: string): string {
  const match = path.match(/^(\/?)([^/?#]+)(.*)$/);
  if (!match) return path;
  const [, leadingSlash, segment, rest] = match;
  const lower = segment.toLowerCase();
  if (lower === 'vitals') return `${leadingSlash}system/vitals${rest}`;
  if (lower === 'health' || lower === 'services') return `${leadingSlash}system/services${rest}`;
  return path;
}
