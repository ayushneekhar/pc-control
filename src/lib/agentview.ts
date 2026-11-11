// agentview: local HTTPS proxy that rewrites what AI agents see on websites.
// The laptop serves the JSON API behind tailscale at /agentview/api (see the agentview README).
import { get, request } from './api';
import type { AgentAction, AgentRule, AgentState, AgentUrl, FromUrl, Original } from './agentviewRules';

const BASE = '/agentview/api';
const WRITE_HEADERS = { 'X-Requested-With': 'pc-control' };

export function getAgentState() {
  return get<AgentState>(`${BASE}/state`);
}

export function setAgentEnabled(enabled: boolean) {
  return request<AgentState>('POST', `${BASE}/state`, { enabled }, 12000, WRITE_HEADERS);
}

export function getAgentUrls(q = '', limit = 200) {
  const query = q.trim();
  return get<AgentUrl[]>(`${BASE}/urls?${query ? `q=${encodeURIComponent(query)}&` : ''}limit=${limit}`);
}

export function getAgentRules() {
  return get<AgentState>(`${BASE}/rules`);
}

export function createRuleFromUrl(url: string, action: AgentAction) {
  return request<FromUrl>('POST', `${BASE}/rules/from-url`, { url, action }, 30000, WRITE_HEADERS);
}

export function updateAgentRule(id: string, body: Partial<AgentRule>) {
  return request<AgentRule>('PUT', `${BASE}/rules/${encodeURIComponent(id)}`, body, 30000, WRITE_HEADERS);
}

export function deleteAgentRule(id: string) {
  return request<{ ok: boolean }>('DELETE', `${BASE}/rules/${encodeURIComponent(id)}`, undefined, 12000, WRITE_HEADERS);
}

export function reorderAgentRules(ids: string[]) {
  return request<AgentState>('POST', `${BASE}/rules/order`, { ids }, 12000, WRITE_HEADERS);
}

export function getOriginalHtml(url: string) {
  return get<Original>(`${BASE}/original?url=${encodeURIComponent(url)}`, 30000);
}

export function createAgentRule(rule: Partial<AgentRule>) {
  return request<AgentRule>('POST', `${BASE}/rules`, rule, 12000, WRITE_HEADERS);
}

export * from './agentviewRules';
