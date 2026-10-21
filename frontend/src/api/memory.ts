import { apiFetch } from "./client";

/** 一份长期记忆文档（与后端 `MemoryDocument` 对应）。 */
export interface MemoryFileInfo {
  /** 文件名：`MEMORY.md`（长期记忆）或 `YYYY-MM-DD.md`（当日台账） */
  name: string;
  kind: "CURATED" | "DAILY";
  /** UTF-8 字节数 */
  sizeBytes: number;
  /** 最后修改时间（ISO-8601；拿不到时为 null） */
  updatedAt: string | null;
  /** 正文；列表接口不含（null），详情接口才有 */
  content?: string | null;
}

/**
 * 查谁的记忆。
 *
 * <p>`userId` 只有超管才需要传（页面上的「用户」下拉框）；不传/传自己就是本人。
 * 普通用户传别人的 id 会被后端 403（不会静默改成自己）。
 */
type Target = number | null | undefined;

function query(userId?: Target) {
  return userId ? `?userId=${userId}` : "";
}

/** 记忆文件清单（`MEMORY.md` 在前，台账按日期倒序；不带正文）。 */
export function listMemoryFiles(userId?: Target) {
  return apiFetch<MemoryFileInfo[]>(`/api/memory${query(userId)}`);
}

/** 读一份记忆的正文。 */
export function getMemoryFile(name: string, userId?: Target) {
  return apiFetch<MemoryFileInfo>(`/api/memory/${encodeURIComponent(name)}${query(userId)}`);
}

/** 整份覆盖保存（保存后**下一轮对话立即生效**）。 */
export function saveMemoryFile(name: string, content: string, userId?: Target) {
  return apiFetch<MemoryFileInfo>(`/api/memory/${encodeURIComponent(name)}${query(userId)}`, {
    method: "PUT",
    body: JSON.stringify({ content }),
  });
}

/** 删除一份**台账**（`MEMORY.md` 不允许删除，只能清空正文）。 */
export function deleteMemoryFile(name: string, userId?: Target) {
  return apiFetch<void>(`/api/memory/${encodeURIComponent(name)}${query(userId)}`, {
    method: "DELETE",
  });
}
