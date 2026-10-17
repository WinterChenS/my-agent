import { apiFetch } from "./client";
import type { PageResult } from "./types";

export type ProviderType =
  | "OPENAI"
  | "OPENAI_COMPATIBLE"
  | "DASHSCOPE"
  | "ANTHROPIC"
  | "GEMINI"
  | "OLLAMA";

export interface Provider {
  id: number;
  name: string;
  providerType: ProviderType;
  baseUrl: string | null;
  apiKey: string | null;
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
  updatedAt?: string;
}

export interface ProviderPayload {
  name: string;
  providerType: ProviderType;
  baseUrl?: string | null;
  apiKey?: string | null;
  status?: "ENABLED" | "DISABLED";
}

export interface ModelInfo {
  id: number;
  providerId: number;
  modelId: string;
  displayName: string | null;
  params: Record<string, unknown> | null;
  /** Phase 15：采样温度 0–2 */
  temperature: number | null;
  /** Top-P 核采样（Top-N）0–1 */
  topP: number | null;
  /** 上下文窗口长度（token） */
  contextWindow: number | null;
  /** 单次输出长度上限（token） */
  maxTokens: number | null;
  /** 输入价格：每百万 token */
  priceInput: number | null;
  /** 输出价格：每百万 token */
  priceOutput: number | null;
  /** 缓存命中价（每百万 token）；null = 不区分，命中部分按输入价计 */
  priceCache: number | null;
  /** 价格货币：CNY / USD */
  priceCurrency: string | null;
  /** 是否支持图片输入（多模态） */
  multimodal: boolean | null;
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
  updatedAt?: string;
}

export interface ModelPayload {
  providerId: number;
  modelId: string;
  displayName?: string | null;
  params?: Record<string, unknown> | null;
  /** Phase 15：这五项都会同步下发给模型（见 AgentFactor/ModelFactory） */
  temperature?: number | null;
  topP?: number | null;
  contextWindow?: number | null;
  maxTokens?: number | null;
  priceInput?: number | null;
  priceOutput?: number | null;
  priceCache?: number | null;
  priceCurrency?: string | null;
  status?: "ENABLED" | "DISABLED";
}

export interface ProbeResult {
  ok: boolean;
  latencyMs: number;
  error: string | null;
}

export const PROVIDER_TYPES: ProviderType[] = [
  "OPENAI_COMPATIBLE",
  "OPENAI",
  "DASHSCOPE",
  "ANTHROPIC",
  "GEMINI",
  "OLLAMA",
];

export function listProviders(current = 1, size = 100) {
  return apiFetch<PageResult<Provider>>(`/api/model-providers?current=${current}&size=${size}`);
}

export function createProvider(payload: ProviderPayload) {
  return apiFetch<Provider>("/api/model-providers", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function updateProvider(id: number, payload: ProviderPayload) {
  return apiFetch<Provider>(`/api/model-providers/${id}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function deleteProvider(id: number) {
  return apiFetch<void>(`/api/model-providers/${id}`, { method: "DELETE" });
}

/** 连通性测试：omitted modelId 时用该供应商下第一个模型。 */
export function testProvider(id: number, modelId?: number) {
  const query = modelId ? `?modelId=${modelId}` : "";
  return apiFetch<ProbeResult>(`/api/model-providers/${id}/test${query}`, { method: "POST" });
}

export function listModels(current = 1, size = 200, providerId?: number) {
  const query = providerId ? `&providerId=${providerId}` : "";
  return apiFetch<PageResult<ModelInfo>>(`/api/models?current=${current}&size=${size}${query}`);
}

export function createModel(payload: ModelPayload) {
  return apiFetch<ModelInfo>("/api/models", { method: "POST", body: JSON.stringify(payload) });
}

export function updateModel(id: number, payload: ModelPayload) {
  return apiFetch<ModelInfo>(`/api/models/${id}`, { method: "PUT", body: JSON.stringify(payload) });
}

export function deleteModel(id: number) {
  return apiFetch<void>(`/api/models/${id}`, { method: "DELETE" });
}
