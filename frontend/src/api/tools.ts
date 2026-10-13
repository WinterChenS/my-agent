import { apiFetch } from "./client";

/** 一条可插入的工具（MCP 或本地）。 */
export interface ToolEntry {
  /** 模型实际要调用的名字（MCP 工具带 `server__tool` 命名空间）。 */
  name: string;
  /** 来源分组：`MCP · <server>` 或 `本地（框架内置）`。 */
  group: string;
  hint?: string | null;
}

/**
 * 可用工具清单：MCP 工具（来自已同步的 tool_cache）+ 本地工具（来自 AgentScope 框架自身注册）。
 *
 * 用途：Skill 编辑器里输入 `/` 时插入工具名——手打容易拼错，尤其 MCP 工具带命名空间。
 */
export function listTools() {
  return apiFetch<ToolEntry[]>("/api/tools");
}
