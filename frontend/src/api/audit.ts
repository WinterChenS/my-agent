import { apiFetch } from "./client";

export interface TokenTotalsRow {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  model_id?: number | null;
  day?: string;
  /** 可读名称（后端已 join 出来，避免界面只显示 id） */
  model_name?: string | null;
  model_key?: string | null;
  provider_type?: string | null;
  agent_id?: number | null;
  agent_name?: string | null;
  /** 该维度的调用次数 */
  records?: number;
  /** Phase 15：按模型价格（每百万 token）算出的成本，货币见 currency */
  cost_total?: number | null;
  currency?: string | null;
  price_input?: number | null;
  price_output?: number | null;
  /** 缓存命中价（为空 = 不区分，命中按输入价计） */
  price_cache?: number | null;
  cache_tokens?: number | null;
  cost_input?: number | null;
  cost_cache?: number | null;
  cost_output?: number | null;
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
  /** 维度补充：按 Agent、按供应商（供应商类型来自 token_usage.provider_type） */
  byAgent?: TokenTotalsRow[];
  byProvider?: TokenTotalsRow[];
  /** KPI 计数：调用次数 / 会话数 / 用到的 Agent 数 / 模型数 */
  records?: number;
  sessions?: number;
  /** 缓存命中 token 数（厂商返回才有） */
  cacheTokens?: number;
  /** 分类成本：未命中输入 / 缓存命中 / 输出（三者之和 = costTotal） */
  costInput?: number | null;
  costCache?: number | null;
  costOutput?: number | null;
  agentCount?: number;
  modelCount?: number;
}

export interface TokenRecord {
  id: number;
  userId: number;
  /** 名称（后端 join，界面不必再显示裸 id） */
  userName?: string | null;
  agentName?: string | null;
  modelName?: string | null;
  sessionId: number | null;
  agentId: number | null;
  modelId: number | null;
  providerType: string | null;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** 缓存命中 token 数 */
  cacheTokens?: number | null;
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
