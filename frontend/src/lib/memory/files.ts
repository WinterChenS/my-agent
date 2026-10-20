import type { MemoryFileInfo } from "@/api/memory";

/**
 * 记忆清单的排序与汇总（纯函数，便于单测）。
 *
 * <p>顺序固定为：`MEMORY.md`（长期记忆，最重要）在最前；其余按文件名（即日期）**倒序**，
 * 最新的台账在上。不要依赖后端返回顺序 —— 换存储/换查询后容易悄悄变。
 */
export function sortMemoryFiles(files: MemoryFileInfo[]): MemoryFileInfo[] {
  return [...files].sort((a, b) => {
    if (a.kind === "CURATED" && b.kind !== "CURATED") return -1;
    if (b.kind === "CURATED" && a.kind !== "CURATED") return 1;
    return b.name.localeCompare(a.name);
  });
}

/** 汇总：文件数与总字节数（页头展示）。 */
export function summarizeMemory(files: MemoryFileInfo[]): { count: number; bytes: number } {
  return {
    count: files.length,
    bytes: files.reduce((sum, file) => sum + (file.sizeBytes || 0), 0),
  };
}

/** 两份正文是否等价（用于“有没有未保存的改动”）。 */
export function isDirty(original: string, draft: string): boolean {
  return original !== draft;
}

/** 台账名（`YYYY-MM-DD.md`）→ 日期文案；`MEMORY.md` 返回「长期记忆」。 */
export function fileLabel(file: Pick<MemoryFileInfo, "name" | "kind">): string {
  if (file.kind === "CURATED") {
    return "长期记忆";
  }
  return file.name.replace(/\.md$/i, "");
}
