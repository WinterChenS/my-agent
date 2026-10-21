import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 记忆接口的 `?userId=` 拼装。
 *
 * <p>为什么值得单独测：这是“看谁的记忆”唯一的请求侧入口。漏拼了 `userId`，
 * 超管在页面上选了别的用户却仍然拿回自己的记忆 —— 界面上毫无提示（实测这种 bug 很隐蔽）。
 */
const state = vi.hoisted(() => ({ urls: [] as string[] }));

vi.mock("./client", () => ({
  apiFetch: (url: string) => {
    state.urls.push(url);
    return Promise.resolve([] as never);
  },
}));

const { deleteMemoryFile, getMemoryFile, listMemoryFiles, saveMemoryFile } = await import("./memory");

describe("记忆接口的 userId", () => {
  beforeEach(() => {
    state.urls.length = 0;
  });

  it("不传 userId 时不带 query（= 看自己的）", async () => {
    await listMemoryFiles();
    await getMemoryFile("MEMORY.md");
    await saveMemoryFile("MEMORY.md", "x");
    await deleteMemoryFile("2026-10-20.md");
    expect(state.urls).toEqual([
      "/api/memory",
      "/api/memory/MEMORY.md",
      "/api/memory/MEMORY.md",
      "/api/memory/2026-10-20.md",
    ]);
  });

  it("传 userId 时每个请求都带上（超管看别人的记忆）", async () => {
    await listMemoryFiles(42);
    await getMemoryFile("MEMORY.md", 42);
    await saveMemoryFile("MEMORY.md", "x", 42);
    await deleteMemoryFile("2026-10-20.md", 42);
    expect(state.urls).toEqual([
      "/api/memory?userId=42",
      "/api/memory/MEMORY.md?userId=42",
      "/api/memory/MEMORY.md?userId=42",
      "/api/memory/2026-10-20.md?userId=42",
    ]);
  });

  it("null / undefined 都按“自己”处理（切换下拉框时不用额外判断）", async () => {
    await listMemoryFiles(null);
    await listMemoryFiles(undefined);
    expect(state.urls).toEqual(["/api/memory", "/api/memory"]);
  });
});
