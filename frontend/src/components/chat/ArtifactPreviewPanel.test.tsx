import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ArtifactInfo } from "@/api/artifacts";
import { PREVIEW_PANEL_DEFAULT } from "@/lib/chat/previewPanelWidth";
import { ArtifactPreviewPanel } from "./ArtifactPreviewPanel";

function artifact(id: number, fileName: string): ArtifactInfo {
  return {
    id,
    sessionId: 1,
    role: "ARTIFACT",
    storage: "DB",
    fileName,
    mime: "text/markdown",
    sizeBytes: 123,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: true,
    content: `# ${fileName}`,
    runId: null,
    createdAt: "2026-01-01T00:00:00Z",
  };
}

afterEach(cleanup);

describe("ArtifactPreviewPanel", () => {
  const tabs = [artifact(1, "a.md"), artifact(2, "b.md")];

  function setup(activeId: number | null = 1, width = PREVIEW_PANEL_DEFAULT) {
    const onSelect = vi.fn();
    const onCloseTab = vi.fn();
    const onClose = vi.fn();
    const onWidthChange = vi.fn();
    render(
      <ArtifactPreviewPanel
        tabs={tabs}
        activeId={activeId}
        onSelect={onSelect}
        onCloseTab={onCloseTab}
        onClose={onClose}
        width={width}
        onWidthChange={onWidthChange}
        browserTrigger={<button data-testid="open-artifact-browser-panel" />}
      />,
    );
    return { onSelect, onCloseTab, onClose, onWidthChange };
  }

  it("每个产物一个 tab，当前 tab 的内容在下面渲染", () => {
    setup(2);
    const rendered = screen.getAllByTestId("preview-tab").map((tab) => tab.textContent);
    expect(rendered).toEqual(["a.md", "b.md"]);
    // 只有选中的那个渲染进内容区（markdown 的 `# b.md` 会变成 <h1>b.md</h1>）
    expect(screen.getByTestId("artifact-preview-full").querySelector("h1")?.textContent).toBe(
      "b.md",
    );
  });

  it("点 tab 切内容；点 tab 上的叉关掉它；点右上角的叉关整个面板", () => {
    const { onSelect, onCloseTab, onClose } = setup();
    fireEvent.click(screen.getAllByTestId("preview-tab")[1].querySelector("button")!);
    expect(onSelect).toHaveBeenCalledWith(2);

    fireEvent.click(screen.getAllByTestId("preview-tab-close")[0]);
    expect(onCloseTab).toHaveBeenCalledWith(1);

    fireEvent.click(screen.getByTestId("preview-panel-close"));
    expect(onClose).toHaveBeenCalled();
  });

  it("左上角渲染传入的「来源与产物」触发按钮（弹窗自己锚在它下面）", () => {
    setup();
    const trigger = screen.getByTestId("open-artifact-browser-panel");
    const tabsContainer = screen.getAllByTestId("preview-tab")[0];
    expect(
      trigger.compareDocumentPosition(tabsContainer) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("宽度由 props 决定；左边界双击恢复到默认宽度", () => {
    const { onWidthChange } = setup(1, 600);
    expect(screen.getByTestId("preview-panel").style.width).toBe("600px");
    fireEvent.doubleClick(screen.getByTestId("preview-resize-handle"));
    expect(onWidthChange).toHaveBeenCalledWith(PREVIEW_PANEL_DEFAULT);
  });

  it("没有 tab 时不渲染面板（避免留一个空壳）", () => {
    render(
      <ArtifactPreviewPanel
        tabs={[]}
        activeId={null}
        onSelect={vi.fn()}
        onCloseTab={vi.fn()}
        onClose={vi.fn()}
        width={PREVIEW_PANEL_DEFAULT}
        onWidthChange={vi.fn()}
        browserTrigger={null}
      />,
    );
    expect(screen.queryByTestId("preview-panel")).toBeNull();
  });
});
