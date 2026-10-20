import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import * as XLSX from "xlsx";
import type { ArtifactInfo } from "@/api/artifacts";
import { ArtifactPreviewBody } from "./ArtifactPreviewBody";

/**
 * 渲染体单测：按类型分派 + 两条「自己解」的路径（Excel / Word 失败兜底）。
 *
 * <p>字节从接口取，所以这里把 `fetchArtifactBytes` 换掉（其余导出保持真实实现，
 * 被测的正是它们 —— 与 MessageBubble.test 的做法一致）。
 */
const bytesByArtifact = new Map<number, Uint8Array>();

vi.mock("@/api/artifacts", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/artifacts")>();
  return {
    ...actual,
    fetchArtifactBytes: async (id: number) => {
      const bytes = bytesByArtifact.get(id);
      if (!bytes) {
        throw new Error(`没有给 ${id} 准备字节`);
      }
      return bytes;
    },
  };
});

function artifact(overrides: Partial<ArtifactInfo> & { id: number }): ArtifactInfo {
  return {
    sessionId: 1,
    role: "ARTIFACT",
    storage: "DB",
    fileName: `file-${overrides.id}.txt`,
    mime: "text/plain",
    sizeBytes: 10,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: true,
    content: "",
    runId: null,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

/** 造一个两页的 xlsx（只在内存里，不落 fixture）。 */
function workbookBytes(): Uint8Array {
  const book = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(
    book,
    XLSX.utils.aoa_to_sheet([
      ["名称", "数量"],
      ["苹果", "3"],
    ]),
    "清单",
  );
  XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([["第二页内容"]]), "第二页");
  return new Uint8Array(XLSX.write(book, { type: "array", bookType: "xlsx" }) as ArrayBuffer);
}

afterEach(() => {
  cleanup();
  bytesByArtifact.clear();
});

describe("ArtifactPreviewBody", () => {
  it("markdown 走渲染（不是纯文本），同一 testid 便于统一断言", () => {
    const item = artifact({
      id: 1,
      fileName: "备注.md",
      mime: "text/markdown",
      content: "## 小标题\n\n| 列 | 值 |\n|---|---|\n| a | 1 |",
    });
    render(<ArtifactPreviewBody item={item} />);
    const box = screen.getByTestId("artifact-preview-full");
    expect(box.querySelector("h2")?.textContent).toBe("小标题");
    expect(box.querySelector("table")).not.toBeNull();
  });

  it("html 走沙箱 iframe：允许脚本但不允许同源", () => {
    const item = artifact({
      id: 2,
      fileName: "图.html",
      mime: "text/html",
      content: "<h1>hi</h1><script>1</script>",
    });
    render(<ArtifactPreviewBody item={item} />);
    const frame = screen.getByTestId("preview-html");
    expect(frame.getAttribute("sandbox")).toBe("allow-scripts");
    expect(frame.getAttribute("sandbox")).not.toContain("allow-same-origin");
    expect(frame.getAttribute("srcdoc")).toContain("<h1>hi</h1>");
  });

  it("Excel：动态读 xlsx，多 sheet 出 tab，单元格以文本渲染", async () => {
    const item = artifact({
      id: 3,
      fileName: "表.xlsx",
      mime: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      previewable: false,
      content: null,
    });
    bytesByArtifact.set(3, workbookBytes());
    render(<ArtifactPreviewBody item={item} />);

    await waitFor(() => expect(screen.getByTestId("preview-sheet-table")).toBeTruthy());
    expect(screen.getByTestId("preview-sheet-table").textContent).toContain("苹果");
    const tabs = screen.getAllByTestId("preview-sheet-tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual(["清单", "第二页"]);
  });

  it("Word：字节不是 zip 时给「无法解析，请下载」，不留空白", async () => {
    const item = artifact({
      id: 4,
      fileName: "合同.docx",
      mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      previewable: false,
      content: null,
    });
    bytesByArtifact.set(4, new Uint8Array(32).fill(7));
    render(<ArtifactPreviewBody item={item} />);
    await waitFor(() => expect(screen.getByTestId("preview-docx-failed")).toBeTruthy());
  });

  it("不支持的类型（如 zip）明确说明只能下载", () => {
    const item = artifact({ id: 5, fileName: "包.zip", mime: "application/zip", previewable: false, content: null });
    render(<ArtifactPreviewBody item={item} />);
    expect(screen.getByText(/不支持在线预览/)).toBeTruthy();
  });
});
