import { apiFetch } from "./client";
import type { PageResult } from "./types";

export interface SkillInfo {
  id: number;
  name: string;
  description: string | null;
  content: string | null;
  version: number;
  status: "ENABLED" | "DISABLED";
  /** 导入来源（仓库地址）；手工创建为 null。 */
  sourceUrl?: string | null;
  /** 导入来源的分支/标签。 */
  sourceRef?: string | null;
  /** 资源文件数量（标准 Skill 的 scripts/ references/ assets/ …）。 */
  resourceCount?: number;
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

/** 仓库里的一个待安装技能（预览结果）。 */
export interface SkillImportCandidate {
  root: string;
  /** 所属技能包（仓库 .claude-plugin/marketplace.json 里的插件名）；可能为空。 */
  pack?: string | null;
  name: string;
  description: string;
  content: string;
  resourceCount: number;
  resourceBytes: number;
  resourcePaths: string[];
  /** 安全扫描结论：SAFE / CAUTION / DANGEROUS。 */
  verdict: string;
  findings: string[];
  /** 平台里已有同名 Skill 时的主键（提示「将更新」）。 */
  existingSkillId?: number | null;
  existingVersion?: number | null;
}

export interface SkillImportResult {
  downloadUrl: string;
  ref: string;
  preview: boolean;
  candidates: SkillImportCandidate[];
  installed: {
    id: number;
    name: string;
    root: string;
    version: number;
    action: "created" | "updated" | "unchanged";
    resourceCount: number;
  }[];
  skipped: { name: string; root: string; reason: string }[];
  /** 仓库层面的提示（如「多份同名技能，只取其中一份」）。 */
  notes?: string[];
}

export interface SkillImportPayload {
  url: string;
  ref?: string | null;
  /** 默认 true：只预览不落库。 */
  preview?: boolean;
  /** 要安装的技能目录名 / 技能名 / 包名；空 = 全部。 */
  names?: string[];
  /** 同名 Skill 已存在时是否覆盖（默认 true）。 */
  overwrite?: boolean;
  /** 装完是否启用（默认 false：外部内容先停用，人工确认后再启用）。 */
  enable?: boolean;
  /** 是否允许安装安全扫描为 DANGEROUS 的技能（默认 false；需人工看过命中项）。 */
  force?: boolean;
}

/**
 * 从仓库链接导入标准 Skill（SKILL.md + 资源）。
 *
 * 两段式：先 `preview: true` 看仓库里有哪些技能与安全结论，再带 `names` 安装选中的。
 */
export function importSkills(payload: SkillImportPayload) {
  return apiFetch<SkillImportResult>("/api/skills/import", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}
