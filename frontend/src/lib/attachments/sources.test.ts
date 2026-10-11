import { describe, expect, it } from "vitest";
import {
  extensionOf,
  filesFromClipboard,
  filesFromDataTransfer,
  normalizeDroppedFile,
  normalizeIncomingFiles,
} from "./sources";

function file(name: string, size = 3, type = "image/png"): File {
  return new File([new Uint8Array(size)], name, { type });
}

/** 造一个最小的 DataTransfer 替身（jsdom 没有真的 DataTransfer）。 */
function fakeDataTransfer(options: { files?: File[]; items?: { kind: string; file: File | null }[] }) {
  return {
    files: options.files ?? [],
    items: (options.items ?? []).map((item) => ({
      kind: item.kind,
      getAsFile: () => item.file,
    })),
  } as unknown as DataTransfer;
}

describe("拖拽来源（filesFromDataTransfer）", () => {
  it("优先用 dataTransfer.files（从系统拖文件）", () => {
    const files = filesFromDataTransfer(fakeDataTransfer({ files: [file("a.md"), file("b.png")] }));
    expect(files.map((f) => f.name)).toEqual(["a.md", "b.png"]);
  });

  it("files 为空时回退到 items（只取 kind=file）", () => {
    const files = filesFromDataTransfer(
      fakeDataTransfer({
        items: [
          { kind: "string", file: null },
          { kind: "file", file: file("c.txt") },
        ],
      }),
    );
    expect(files.map((f) => f.name)).toEqual(["c.txt"]);
  });

  it("空输入返回空数组（不抛错）", () => {
    expect(filesFromDataTransfer(null)).toEqual([]);
    expect(filesFromDataTransfer(undefined)).toEqual([]);
    expect(filesFromDataTransfer(fakeDataTransfer({}))).toEqual([]);
  });
});

describe("粘贴来源（filesFromClipboard）", () => {
  it("粘贴截图时拿到文件", () => {
    const files = filesFromClipboard(fakeDataTransfer({ files: [file("image.png", 10)] }));
    expect(files).toHaveLength(1);
  });

  it("只粘贴纯文本时没有文件（交给输入框本身）", () => {
    expect(filesFromClipboard(fakeDataTransfer({ items: [{ kind: "string", file: null }] }))).toEqual(
      [],
    );
  });
});

describe("文件名规范化（截图重名问题）", () => {
  it("通用名/空名会补成带时间戳的可读名字", () => {
    const normalized = normalizeDroppedFile(file("image.png", 5, "image/png"), 0, 1791700000000);
    expect(normalized.name).toBe("粘贴文件-1791700000000-1.png");

    const unnamed = normalizeDroppedFile(file("", 5, "image/jpeg"), 1, 1791700000000);
    expect(unnamed.name).toBe("粘贴文件-1791700000000-2.jpg");
  });

  it("有真实名字的文件保持原名（长文件名不被改）", () => {
    const named = file("钉钉登录与用户同步接入手册.md", 5, "text/markdown");
    expect(normalizeDroppedFile(named, 0, 1791700000000).name).toBe("钉钉登录与用户同步接入手册.md");
  });

  it("一次拖入多个截图会得到互不相同的名字（暂存区去重不会互相吞掉）", () => {
    const list = normalizeIncomingFiles([file("image.png", 5), file("image.png", 9)], 1791700000000);
    expect(list.map((f) => f.name)).toEqual([
      "粘贴文件-1791700000000-1.png",
      "粘贴文件-1791700000000-2.png",
    ]);
  });

  it("扩展名推断：优先原名，其次 MIME", () => {
    expect(extensionOf("image/png", "shot.PNG")).toBe(".png");
    expect(extensionOf("image/webp", "blob")).toBe(".webp");
    expect(extensionOf("application/octet-stream", "x")).toBe("");
  });
});
