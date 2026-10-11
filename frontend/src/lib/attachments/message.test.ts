import { describe, expect, it } from "vitest";
import {
  ATTACHMENT_HEADER,
  attachmentBaseName,
  buildAttachmentBlock,
  parseAttachmentBlock,
} from "./message";

describe("附件块：生成（让模型知道这一轮带了什么）", () => {
  it("把工作区相对路径写进块里，并明确告诉模型去哪读", () => {
    const block = buildAttachmentBlock(["uploads/a.md", "uploads/b.png"]);
    expect(block).toContain(ATTACHMENT_HEADER);
    expect(block).toContain("uploads/ 目录");
    expect(block).toContain("- uploads/a.md");
    expect(block).toContain("- uploads/b.png");
  });

  it("没有附件时不产生任何内容（保持消息原样）", () => {
    expect(buildAttachmentBlock([])).toBe("");
  });
});

describe("附件块：解析（气泡渲染与刷新回放）", () => {
  it("往返一致：生成后能解回正文与附件列表", () => {
    const body = "帮我看下这两个文件";
    const content = body + buildAttachmentBlock(["uploads/a.md", "uploads/b.png"]);
    const parsed = parseAttachmentBlock(content);
    expect(parsed.text).toBe(body);
    expect(parsed.attachments).toEqual(["uploads/a.md", "uploads/b.png"]);
  });

  it("没有附件块时原样返回，不误伤普通正文", () => {
    const plain = "- 这是一个普通列表\n- 不是附件";
    expect(parseAttachmentBlock(plain)).toEqual({ text: plain, attachments: [] });
  });

  it("只把工作区内 uploads/ 路径当附件（正文里别的列表不算）", () => {
    const content = "看看这个" + buildAttachmentBlock(["uploads/a.md"]);
    const parsed = parseAttachmentBlock(content + "\n- 正文里的普通条目");
    expect(parsed.attachments).toEqual(["uploads/a.md"]);
  });

  it("只有附件没有正文时也能解析（正文为空串）", () => {
    const parsed = parseAttachmentBlock(buildAttachmentBlock(["uploads/a.md"]).trimStart());
    expect(parsed.attachments).toEqual(["uploads/a.md"]);
    expect(parsed.text).toBe("");
  });

  it("文件名用于气泡展示", () => {
    expect(attachmentBaseName("uploads/钉钉登录与用户同步接入手册.md")).toBe(
      "钉钉登录与用户同步接入手册.md",
    );
  });
});
