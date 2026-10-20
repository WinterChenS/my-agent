import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import type { ArtifactInfo } from "@/api/artifacts";
import { ArtifactBrowserList } from "./ArtifactBrowserPopover";

/**
 * 清单内容：**分栏只在有内容时出现**（用户要求：没有那种类型的产物就不占位置）。
 *
 * <p>只测与数据/分栏有关的逻辑；「贴着按钮下方弹出、竖长条」属于浮层定位，交给 e2e
 * （`verify-artifact-preview.spec.ts` 里断言面板左上角按钮能打开它）。
 */
function artifact(
  overrides: Partial<ArtifactInfo> & { id: number; role: "SOURCE" | "ARTIFACT" },
): ArtifactInfo {
  return {
    sessionId: 1,
    storage: "DB",
    fileName: `file-${overrides.id}.txt`,
    mime: "text/plain",
    sizeBytes: 10,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: true,
    content: "x",
    runId: null,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

afterEach(cleanup);

function setup(items: ArtifactInfo[], onPreview = vi.fn()) {
  render(
    <ArtifactBrowserList
      items={items}
      onRemove={async () => {}}
      onError={vi.fn()}
      onPreview={onPreview}
    />,
  );
  return { onPreview };
}

describe("ArtifactBrowserList", () => {
  it("只有来源文件 → 只出「来源文件」一栏，产物栏不占位置", () => {
    setup([artifact({ id: 1, role: "SOURCE", storage: "MINIO" })]);
    expect(screen.getByTestId("panel-sources")).toBeTruthy();
    expect(screen.getByTestId("panel-sources-item")).toBeTruthy();
    expect(screen.queryByTestId("panel-artifacts")).toBeNull();
    // 存储位置也照旧标出来（MINIO → 对象存储）
    expect(screen.getByTestId("panel-sources-item").textContent).toContain("对象存储");
  });

  it("只有产物 → 只出「产物」一栏", () => {
    setup([artifact({ id: 2, role: "ARTIFACT" })]);
    expect(screen.getByTestId("panel-artifacts")).toBeTruthy();
    expect(screen.queryByTestId("panel-sources")).toBeNull();
  });

  it("两类都有 → 两栏都出，DOM 顺序是来源在前", () => {
    setup([artifact({ id: 1, role: "SOURCE" }), artifact({ id: 2, role: "ARTIFACT" })]);
    const sources = screen.getByTestId("panel-sources");
    const artifacts = screen.getByTestId("panel-artifacts");
    expect(sources.textContent).toContain("来源文件");
    expect(artifacts.textContent).toContain("产物");
    expect(
      sources.compareDocumentPosition(artifacts) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("都没有 → 给一句空提示，不出现空分栏", () => {
    setup([]);
    expect(screen.getByTestId("artifact-browser-empty")).toBeTruthy();
    expect(screen.queryByTestId("panel-sources")).toBeNull();
    expect(screen.queryByTestId("panel-artifacts")).toBeNull();
  });

  it("预览按钮只在「能预览的类型」上出现：pdf / 靠扩展名兜底的 xlsx 有，zip 没有", async () => {
    const onPreview = vi.fn();
    const pdf = artifact({
      id: 3,
      role: "SOURCE",
      fileName: "a.pdf",
      mime: "application/pdf",
      previewable: false,
    });
    const xlsx = artifact({
      id: 4,
      role: "ARTIFACT",
      fileName: "b.xlsx",
      mime: "application/octet-stream",
      previewable: false,
    });
    const zip = artifact({
      id: 5,
      role: "SOURCE",
      fileName: "c.zip",
      mime: "application/zip",
      previewable: false,
    });
    setup([pdf, xlsx, zip], onPreview);

    await waitFor(() => expect(screen.getByTestId("panel-artifacts")).toBeTruthy());
    const rows = [
      ...screen.getAllByTestId("panel-sources-item"),
      ...screen.getAllByTestId("panel-artifacts-item"),
    ];
    const previewButton = (row: HTMLElement) =>
      row.querySelector('[data-testid="artifact-preview-button"]');

    expect(rows).toHaveLength(3);
    expect(previewButton(rows.find((row) => row.textContent?.includes("a.pdf"))!)).not.toBeNull();
    const xlsxRow = rows.find((row) => row.textContent?.includes("b.xlsx"))!;
    expect(previewButton(xlsxRow)).not.toBeNull();
    expect(previewButton(rows.find((row) => row.textContent?.includes("c.zip"))!)).toBeNull();

    xlsxRow.querySelector<HTMLButtonElement>('[data-testid="artifact-preview-button"]')!.click();
    expect(onPreview).toHaveBeenCalledWith(xlsx);
  });
});
