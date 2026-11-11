// agentview rule/API types.

export type AgentAction = 'replace_page' | 'replace_text' | 'hide' | 'block';
export type MatchType = 'glob' | 'regex';
export type BlockMode = '404' | 'reset';

export type Replacement = { find: string; replace: string; regex: boolean };

export type AgentRule = {
  id: string;
  enabled: boolean;
  name: string;
  match: string;
  matchType: MatchType;
  action: AgentAction;
  html?: string;
  replacements?: Replacement[];
  selectors?: string[];
  blockMode?: BlockMode;
};

export type AgentState = { enabled: boolean; rules: AgentRule[] };

export type AgentUrl = {
  url: string;
  host: string;
  last_seen: string;
  count: number;
  rule_id: string | null;
};

export type OriginalMeta = {
  available?: boolean;
  captured_at?: string;
  truncated?: boolean;
  size?: number;
};

export type Original = {
  url: string;
  html: string;
  captured_at?: string;
  size?: number;
  truncated?: boolean;
};

export type FromUrl = { rule: AgentRule; original: OriginalMeta | null };

// Pure helpers for the Agent view (kept separate from the API client so tests need no native modules).

/** host (with port, if any) and path+query for display. Fragments are dropped. */
export function splitUrl(raw: string): { host: string; path: string } {
  const s = (raw ?? '').trim();
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)([^#]*)/i.exec(s);
  if (withScheme) return { host: withScheme[1], path: withPath(withScheme[2]) };
  const bare = /^([^/?#]+)([^#]*)/.exec(s);
  if (!bare) return { host: '', path: '/' };
  return { host: bare[1], path: withPath(bare[2]) };
}

function withPath(p: string): string {
  if (!p) return '/';
  return p.startsWith('/') ? p : `/${p}`;
}

export function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** The exact-URL match agentview creates for rules/from-url: an anchored, escaped regex. */
export function buildExactMatch(url: string): { match: string; matchType: MatchType } {
  return { match: `^${escapeRegex(url.trim())}$`, matchType: 'regex' };
}

/** A whole-site match for the URL's host, e.g. *://example.com/*. */
export function buildSiteMatch(url: string): { match: string; matchType: MatchType } {
  const { host } = splitUrl(url);
  return { match: host ? `*://${host}/*` : '*://*/*', matchType: 'glob' };
}

export type MatchMode = 'exact' | 'site' | 'custom';

export function matchModeFor(rule: Pick<AgentRule, 'match'>, url: string | null): MatchMode {
  if (!url) return 'custom';
  if (rule.match === buildExactMatch(url).match) return 'exact';
  if (rule.match === buildSiteMatch(url).match) return 'site';
  return 'custom';
}

/** Best-effort URL for a rule's match, used when editing a rule without a known URL. */
export function urlFromMatch(match: string, matchType: MatchType): string | null {
  if (matchType === 'glob') {
    const m = /^\*:\/\/([^/*]+)\/\*$/.exec(match);
    return m ? `https://${m[1]}/` : null;
  }
  if (match.startsWith('^') && match.endsWith('$')) {
    const raw = match.slice(1, -1).replace(/\\(.)/g, '$1');
    return /^https?:\/\//i.test(raw) ? raw : null;
  }
  return null;
}

export function defaultRuleName(url: string): string {
  const { host, path } = splitUrl(url);
  return host ? `${host}${path}` : 'New rule';
}

export type RulePayload = Partial<AgentRule>;

export type RuleDraft = {
  action: AgentAction;
  name?: string;
  matchMode: MatchMode;
  url?: string | null;
  customMatch?: string;
  customMatchType?: MatchType;
  html?: string;
  replacements?: Replacement[];
  selectors?: string[];
  blockMode?: BlockMode;
  enabled?: boolean;
};

/** Rule fields for POST /rules and PUT /rules/{id}, including only the action's own fields. */
export function buildRulePayload(draft: RuleDraft): RulePayload {
  const url = draft.url ?? null;
  const customMatch = (draft.customMatch ?? '').trim();
  const built =
    draft.matchMode === 'exact' && url
      ? buildExactMatch(url)
      : draft.matchMode === 'site' && url
        ? buildSiteMatch(url)
        : { match: customMatch, matchType: draft.customMatchType ?? ('glob' as MatchType) };
  const body: RulePayload = {
    name: (draft.name ?? '').trim() || defaultRuleName(url ?? ''),
    match: built.match,
    matchType: built.matchType,
    action: draft.action,
    enabled: draft.enabled ?? true,
  };
  if (draft.action === 'replace_page') body.html = draft.html ?? '';
  if (draft.action === 'replace_text')
    body.replacements = (draft.replacements ?? []).filter((r) => r.find !== '');
  if (draft.action === 'hide') body.selectors = (draft.selectors ?? []).map((s) => s.trim()).filter(Boolean);
  if (draft.action === 'block') body.blockMode = draft.blockMode === 'reset' ? 'reset' : '404';
  return body;
}

const plural = (n: number, one: string) => `${n}${one}`;

/** "last seen" for the URLs list; tolerant of ISO timestamps. */
export function relativeTime(iso: string, now = Date.now()): string {
  const ts = Date.parse(iso);
  if (isNaN(ts)) return '';
  const secs = Math.max(0, Math.floor((now - ts) / 1000));
  if (secs < 10) return 'just now';
  if (secs < 60) return `${plural(secs, 's')} ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${plural(mins, 'm')} ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${plural(hours, 'h')} ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${plural(days, 'd')} ago`;
  return `${Math.floor(days / 30)}mo ago`;
}

export const ACTION_LABELS: Record<AgentAction, string> = {
  replace_page: 'Show my page',
  replace_text: 'Replace text',
  hide: 'Hide elements',
  block: 'Block',
};

export function actionLabel(action: AgentAction): string {
  return ACTION_LABELS[action] ?? action;
}
