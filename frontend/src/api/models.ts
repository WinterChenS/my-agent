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
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
  updatedAt?: string;
}

export interface ModelPayload {
  providerId: number;
  modelId: string;
  displayName?: string | null;
  params?: Record<string, unknown> | null;
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
