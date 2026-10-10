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
