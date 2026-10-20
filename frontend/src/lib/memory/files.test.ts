import { describe, expect, it } from "vitest";
import type { MemoryFileInfo } from "@/api/memory";
import { fileLabel, isDirty, sortMemoryFiles, summarizeMemory } from "./files";

function file(overrides: Partial<MemoryFileInfo> & { name: string }): MemoryFileInfo {
  return {
    kind: overrides.name === "MEMORY.md" ? "CURATED" : "DAILY",
    sizeBytes: 100,
    updatedAt: null,
    ...overrides,
  };
}

describe("sortMemoryFiles", () => {
  it("MEMORY.md 永远在最前，台账按日期倒序", () => {
    const sorted = sortMemoryFiles([
      file({ name: "2026-10-18.md" }),
      file({ name: "MEMORY.md" }),
      file({ name: "2026-10-20.md" }),
      file({ name: "2026-10-19.md" }),
    ]);
    expect(sorted.map((f) => f.name)).toEqual([
      "MEMORY.md",
      "2026-10-20.md",
      "2026-10-19.md",
      "2026-10-18.md",
    ]);
  });

  it("不修改入参（返回新数组）", () => {
    const input = [file({ name: "2026-10-18.md" }), file({ name: "MEMORY.md" })];
    const snapshot = input.map((f) => f.name);
    sortMemoryFiles(input);
    expect(input.map((f) => f.name)).toEqual(snapshot);
  });

  it("没有 MEMORY.md 时只按日期倒序（不塌）", () => {
    expect(
      sortMemoryFiles([file({ name: "2026-10-17.md" }), file({ name: "2026-10-20.md" })]).map(
        (f) => f.name,
      ),
    ).toEqual(["2026-10-20.md", "2026-10-17.md"]);
  });
});

describe("summarizeMemory", () => {
  it("汇总文件数与总字节数", () => {
    expect(
      summarizeMemory([
        file({ name: "MEMORY.md", sizeBytes: 1000 }),
        file({ name: "2026-10-20.md", sizeBytes: 24 }),
      ]),
    ).toEqual({ count: 2, bytes: 1024 });
  });

  it("空清单是 0/0（不是 NaN）", () => {
    expect(summarizeMemory([])).toEqual({ count: 0, bytes: 0 });
  });
});

describe("isDirty", () => {
  it("正文不同才算有改动（避免误报“未保存”）", () => {
    expect(isDirty("a\nb", "a\nb")).toBe(false);
    expect(isDirty("a\nb", "a\nb ")).toBe(true);
  });
});

describe("fileLabel", () => {
  it("MEMORY.md → 长期记忆；台账 → 日期", () => {
    expect(fileLabel({ name: "MEMORY.md", kind: "CURATED" })).toBe("长期记忆");
    expect(fileLabel({ name: "2026-10-20.md", kind: "DAILY" })).toBe("2026-10-20");
  });
});
