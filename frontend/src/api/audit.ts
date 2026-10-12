import { apiFetch } from "./client";

export interface TokenTotalsRow {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  model_id?: number | null;
  day?: string;
  /** Phase 15：按模型价格（每百万 token）算出的成本，货币见 currency */
  cost_total?: number | null;
  currency?: string | null;
  price_input?: number | null;
  price_output?: number | null;
}

export interface TokenSummary {
  userId: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** Phase 15：按模型价格汇总的成本；不同货币混合时为 MIXED */
  costTotal?: number | null;
  costCurrency?: string | null;
  byModel: TokenTotalsRow[];
  byDay: TokenTotalsRow[];
}

export interface TokenRecord {
  id: number;
  userId: number;
  sessionId: number | null;
  agentId: number | null;
  modelId: number | null;
  providerType: string | null;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  requestId: string | null;
  createdAt: string;
}

export interface TokenUser {
  user_id: number;
  username: string;
  display_name: string | null;
  total_tokens: number;
}

export function fetchTokenSummary(params: { userId?: number; from?: string; to?: string }) {
  const query = new URLSearchParams();
  if (params.userId != null) query.set("userId", String(params.userId));
  if (params.from) query.set("from", params.from);
  if (params.to) query.set("to", params.to);
  return apiFetch<TokenSummary>(`/api/audit/token/summary?${query.toString()}`);
}

export function fetchTokenRecords(params: {
  userId?: number;
  from?: string;
  to?: string;
  current?: number;
  size?: number;
}) {
  const query = new URLSearchParams();
  if (params.userId != null) query.set("userId", String(params.userId));
  if (params.from) query.set("from", params.from);
  if (params.to) query.set("to", params.to);
  query.set("current", String(params.current ?? 1));
  query.set("size", String(params.size ?? 20));
  return apiFetch<{ records: TokenRecord[]; total: number; current: number; size: number }>(
    `/api/audit/token/records?${query.toString()}`,
  );
}

export function fetchTokenUsers() {
  return apiFetch<TokenUser[]>("/api/audit/token/users");
}
