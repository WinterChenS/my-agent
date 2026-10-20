import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ArtifactInfo } from "@/api/artifacts";
import { ArtifactBox } from "./ArtifactBox";

/**
 * 产物区：**每个产物一个小长方形卡片**（图标 + 文件名 + 大小，横排换行）。
 *
 * <p>重点断言“不再有内联正文/缩略图”—— 它们是之前把产物区撑高的原因。
 */
function artifact(overrides: Partial<ArtifactInfo> & { id: number }): ArtifactInfo {
  return {
    sessionId: 1,
    role: "ARTIFACT",
    storage: "DB",
    fileName: `file-${overrides.id}.md`,
    mime: "text/markdown",
    sizeBytes: 2048,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: true,
    content: "# 标题\n正文第一行\n正文第二行",
    runId: "run-1",
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

afterEach(cleanup);

describe("ArtifactBox", () => {
  it("每个产物一张卡片：没有内联正文、没有缩略图", () => {
    render(<ArtifactBox items={[artifact({ id: 1 })]} onOpen={vi.fn()} />);
    expect(screen.getAllByTestId("artifact-open")).toHaveLength(1);
    // 旧的“内联预览块”必须消失（文本前几行/图片缩略图都在那里）
    expect(screen.queryByTestId("artifact-preview-inline")).toBeNull();
    expect(document.querySelector("pre")).toBeNull();
    expect(document.querySelector("img")).toBeNull();
  });

  it("卡片布局：左图标 + 文件名/大小 + **右侧眼睛**", () => {
    render(<ArtifactBox items={[artifact({ id: 1 })]} onOpen={vi.fn()} />);
    const card = screen.getByTestId("artifact-open");
    expect(card.textContent).toContain("file-1.md");
    expect(card.textContent).toContain("2.0 KB");
    expect(card.querySelector("svg.lucide-file-text")).not.toBeNull();
    // 眼睛在最右侧（最后一个子元素）
    const eye = card.querySelector("svg.lucide-eye");
    expect(eye).not.toBeNull();
    expect(card.lastElementChild).toBe(eye);
    // 不能预览的类型也给眼睛（它只是“点开看看”的提示，统一视觉）
    cleanup();
    render(
      <ArtifactBox
        items={[artifact({ id: 2, fileName: "c.zip", mime: "application/zip", previewable: false })]}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId("artifact-open").querySelector("svg.lucide-eye")).not.toBeNull();
  });

  it("图片用图片图标（区别类型不用颜色）", () => {
    render(
      <ArtifactBox items={[artifact({ id: 2, fileName: "a.png", mime: "image/png" })]} onOpen={vi.fn()} />,
    );
    expect(screen.getByTestId("artifact-open").querySelector("svg.lucide-image")).not.toBeNull();
  });

  it("点整张卡片即在右侧预览面板打开（回调带上该产物）", () => {
    const onOpen = vi.fn();
    const item = artifact({ id: 7, fileName: "报告.md" });
    render(<ArtifactBox items={[item]} onOpen={onOpen} />);
    fireEvent.click(screen.getByTestId("artifact-open"));
    expect(onOpen).toHaveBeenCalledWith(item);
  });

  it("多个产物 → 多张卡片（横排，由父级 flex-wrap 负责换行）", () => {
    render(
      <ArtifactBox
        items={[artifact({ id: 1 }), artifact({ id: 2, fileName: "b.md" })]}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getAllByTestId("artifact-open")).toHaveLength(2);
    expect(screen.getByTestId("artifact-box").className).toContain("flex-wrap");
  });
});
