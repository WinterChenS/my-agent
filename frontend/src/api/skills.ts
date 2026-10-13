import { apiFetch } from "./client";
import type { PageResult } from "./types";

export interface SkillInfo {
  id: number;
  name: string;
  description: string | null;
  content: string | null;
  version: number;
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
  updatedAt?: string;
}

export interface SkillPayload {
  name: string;
  description?: string | null;
  content?: string | null;
  status?: "ENABLED" | "DISABLED";
}

export function listSkills(current = 1, size = 100, status?: string) {
  const query = status ? `&status=${status}` : "";
  return apiFetch<PageResult<SkillInfo>>(`/api/skills?current=${current}&size=${size}${query}`);
}

export function createSkill(payload: SkillPayload) {
  return apiFetch<SkillInfo>("/api/skills", { method: "POST", body: JSON.stringify(payload) });
}

export function updateSkill(id: number, payload: SkillPayload) {
  return apiFetch<SkillInfo>(`/api/skills/${id}`, { method: "PUT", body: JSON.stringify(payload) });
}

export function deleteSkill(id: number) {
  return apiFetch<void>(`/api/skills/${id}`, { method: "DELETE" });
}

/** AI 生成的技能草稿。 */
export interface SkillDraft {
  name: string;
  description: string;
  content: string;
  /** 生成时的提示（例如说明缺失已填占位文案）。 */
  notice?: string | null;
}

/**
 * 用自然语言让 AI 生成技能草稿（不落库，只返回内容）。
 *
 * 后端会把**可用的工具清单**（本地内置 + MCP）与**技能格式要求**一起交给模型，
 * 所以生成出来的正文里引用的工具名是真实存在的。
 */
export function generateSkill(requirement: string, modelId?: number | null) {
  return apiFetch<SkillDraft>("/api/skills/generate", {
    method: "POST",
    body: JSON.stringify({ requirement, modelId: modelId ?? null }),
  });
}
