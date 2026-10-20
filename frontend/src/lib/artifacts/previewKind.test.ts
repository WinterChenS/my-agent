import { describe, expect, it } from "vitest";
import { canPreviewArtifact, previewKind } from "./previewKind";

const item = (mime: string | null, fileName = "a.bin", previewable = false) => ({
  mime,
  fileName,
  previewable,
});

describe("previewKind", () => {
  it("图片 / PDF / HTML / Markdown 各有专用渲染", () => {
    expect(previewKind(item("image/png", "a.png"))).toBe("image");
    expect(previewKind(item("application/pdf", "a.pdf"))).toBe("pdf");
    expect(previewKind(item("text/html", "a.html"))).toBe("html");
    expect(previewKind(item("text/markdown", "a.md"))).toBe("markdown");
  });

  it("Excel / Word 按 MIME 与扩展名都能认出来", () => {
    expect(
      previewKind(
        item("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "报表.xlsx"),
      ),
    ).toBe("sheet");
    expect(previewKind(item("application/vnd.ms-excel", "老表.xls"))).toBe("sheet");
    expect(
      previewKind(
        item("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "文档.docx"),
      ),
    ).toBe("docx");
    // 后端 MIME 缺失时按扩展名兜底（历史数据里存在 octet-stream）
    expect(previewKind(item("application/octet-stream", "报表.xlsx"))).toBe("sheet");
    expect(previewKind(item("application/octet-stream", "文档.docx"))).toBe("docx");
  });

  it("其它文本类走纯文本；二进制走 none", () => {
    expect(previewKind(item("text/plain", "a.txt"))).toBe("text");
    expect(previewKind(item("text/csv", "a.csv"))).toBe("text");
    expect(previewKind(item("application/json", "a.json"))).toBe("text");
    expect(previewKind(item(null, "a.log", true))).toBe("text");
    expect(previewKind(item("application/zip", "a.zip"))).toBe("none");
    expect(previewKind(null)).toBe("none");
  });

  it("canPreviewArtifact 与 kind 一致", () => {
    expect(canPreviewArtifact(item("image/png", "a.png"))).toBe(true);
    expect(canPreviewArtifact(item("application/pdf", "a.pdf"))).toBe(true);
    expect(canPreviewArtifact(item("application/zip", "a.zip"))).toBe(false);
    expect(canPreviewArtifact(undefined)).toBe(false);
  });
});
