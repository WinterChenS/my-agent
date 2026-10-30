import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MessageBubble } from "./MessageBubble";
import { INLINE_IMAGE_MAX_BYTES, type ArtifactInfo } from "@/api/artifacts";

/*
 * 复制走 lib/clipboard（内网 http 下没有 navigator.clipboard）：换成可断言的空实现
 */
const { copyText } = vi.hoisted(() => ({ copyText: vi.fn(async () => true) }));
vi.mock("@/lib/clipboard", () => ({ copyText }));

/*
 * 把「取字节」换掉：组件测试不联网（真实请求要带 Bearer，jsdom 里也没法真解码图片）。
 * 其余导出（isImageArtifact / 常量）保持真实实现 —— 被测的正是它们与组件的配合。
 */
vi.mock("@/api/artifacts", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/artifacts")>();
  return { ...actual, fetchArtifactBlobUrl: vi.fn(async () => "blob:test-url") };
});

function artifact(partial: Partial<ArtifactInfo>): ArtifactInfo {
  return {
    id: 1,
    sessionId: 1,
    role: "ARTIFACT",
    storage: "MINIO",
    fileName: "shot.png",
    mime: "image/png",
    sizeBytes: 1024,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: false,
    content: null,
    runId: "run-1",
    createdAt: "2026-10-20T00:00:00Z",
    ...partial,
  };
}

describe("MessageBubble 气泡内图片", () => {
  /**
   * 这条分支之前只有实现、没有测试（e2e 造不出 8MB 以上的图片）：
   * 超过上限时**不得**内联渲染 <img>，而是给一个可点开的占位。
   */
  /**
   * 用户要求：气泡右下角有个"鼠标移上去才显示"的复制按钮，且复制的是 **Markdown 原文**，
   * 不是渲染后的纯文本（否则粘出去格式全丢）。
   */
  it("复制按钮复制 Markdown 原文（而不是渲染后的文本）", async () => {
    copyText.mockClear();
    const markdown = ["## 结论", "", "- 第一点", "- 第二点"].join("\n");
    render(<MessageBubble role="assistant" content={markdown} />);

    const button = screen.getByTestId("copy-message");
    // 默认是隐藏的（靠 group-hover 显示），但必须可点
    expect(button.className).toContain("opacity-0");
    fireEvent.click(button);

    await waitFor(() => expect(copyText).toHaveBeenCalledWith(markdown));
  });

  it("流式生成中不显示复制按钮（内容还在变，复制了没意义）", () => {
    render(<MessageBubble role="assistant" content="正在" streaming />);
    expect(screen.queryByTestId("copy-message")).toBeNull();
  });

  it("超过 INLINE_IMAGE_MAX_BYTES 的图片不内联，给可点开的占位", () => {
    const huge = artifact({ id: 7, fileName: "huge.png", sizeBytes: INLINE_IMAGE_MAX_BYTES + 1 });
    const { container } = render(
      <MessageBubble role="assistant" content="看图" artifacts={[huge]} onOpenArtifact={vi.fn()} />,
    );

    const placeholder = screen.getByTestId("bubble-image");
    expect(placeholder.tagName).toBe("BUTTON");
    expect(placeholder).toHaveTextContent("图片较大");
    expect(placeholder).toHaveTextContent("点击预览");
    // 关键：没有真的渲染图片
    expect(container.querySelector('[data-testid="bubble-images"] img')).toBeNull();
  });

  it("未超上限的图片会在气泡内渲染成 <img>（src 来自 blob）", async () => {
    const small = artifact({ id: 8, fileName: "small.png", sizeBytes: 1024 });
    const { container } = render(
      <MessageBubble role="assistant" content="看图" artifacts={[small]} onOpenArtifact={vi.fn()} />,
    );

    const img = await screen.findByAltText("small.png");
    expect(img).toHaveAttribute("src", "blob:test-url");
    expect(container.querySelector('[data-testid="bubble-images"]')).not.toBeNull();
  });

  it("非图片产物不进气泡内（仍由气泡下方的产物框展示）", () => {
    const markdown = artifact({ id: 9, fileName: "report.md", mime: "text/markdown" });
    const { container } = render(
      <MessageBubble role="assistant" content="写好了" artifacts={[markdown]} onOpenArtifact={vi.fn()} />,
    );

    expect(container.querySelector('[data-testid="bubble-images"]')).toBeNull();
    expect(screen.queryByTestId("bubble-image")).toBeNull();
  });

  it("正文里引用了不存在的图片文件名 → 给提示，不留破图", () => {
    render(<MessageBubble role="assistant" content="![图](nope.png)" artifacts={[]} />);

    expect(screen.getByTestId("markdown-image-missing")).toHaveTextContent("图片未找到");
    expect(screen.queryByTestId("markdown-image")).toBeNull();
  });

  it("正文里引用本会话已有图片 → 渲染成 <img>（按文件名匹配，与产物同一份数据）", async () => {
    const image = artifact({ id: 10, fileName: "chart.png" });
    render(
      <MessageBubble role="assistant" content="![图](chart.png)" artifacts={[image]} onOpenArtifact={vi.fn()} />,
    );

    const img = await screen.findByTestId("markdown-image");
    expect(img).toHaveAttribute("src", "blob:test-url");
  });
});
