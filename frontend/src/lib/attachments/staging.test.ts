import { describe, expect, it } from "vitest";
import {
  attachmentKey,
  formatAttachmentSize,
  hasStaged,
  removeAttachment,
  stageFiles,
  toStaged,
  type StagedAttachment,
} from "./staging";

/** 造一个 File（jsdom 提供 File 实现）。 */
function makeFile(name: string, size: number, type = "text/plain"): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe("附件暂存：多选与追加（D24）", () => {
  it("一次选多个文件全部进入暂存", () => {
    const staged = stageFiles([], [makeFile("a.md", 10), makeFile("b.png", 20)]);
    expect(staged.map((item) => item.file.name)).toEqual(["a.md", "b.png"]);
  });

  it("分多次选择是追加，不互相覆盖", () => {
    const first = stageFiles([], [makeFile("a.md", 10)]);
    const second = stageFiles(first, [makeFile("b.md", 10)]);
    expect(second.map((item) => item.file.name)).toEqual(["a.md", "b.md"]);
    // 原数组不被就地修改
    expect(first).toHaveLength(1);
  });

  it("同名同大小去重（重复选中不产生两条）", () => {
    const staged = stageFiles([], [makeFile("a.md", 10), makeFile("a.md", 10)]);
    expect(staged).toHaveLength(1);
    const again = stageFiles(staged, [makeFile("a.md", 10)]);
    expect(again).toHaveLength(1);
  });

  it("同名但大小不同视为不同附件", () => {
    const staged = stageFiles([], [makeFile("a.md", 10), makeFile("a.md", 11)]);
    expect(staged).toHaveLength(2);
  });

  it("空输入不改变现有暂存", () => {
    const staged: StagedAttachment[] = [toStaged(makeFile("a.md", 10))];
    expect(stageFiles(staged, [])).toBe(staged);
  });
});

describe("附件暂存：移除", () => {
  it("按 key 移除，只影响那一条", () => {
    const staged = stageFiles([], [makeFile("a.md", 10), makeFile("b.md", 20)]);
    const kept = removeAttachment(staged, attachmentKey("a.md", 10));
    expect(kept.map((item) => item.file.name)).toEqual(["b.md"]);
  });

  it("移除不存在的 key 时列表不变", () => {
    const staged = stageFiles([], [makeFile("a.md", 10)]);
    expect(removeAttachment(staged, "nothing::1")).toHaveLength(1);
  });

  it("hasStaged 反映是否还有附件", () => {
    expect(hasStaged([])).toBe(false);
    expect(hasStaged(stageFiles([], [makeFile("a.md", 1)]))).toBe(true);
  });
});

describe("附件大小展示", () => {
  it("按 B / KB / MB 展示", () => {
    expect(formatAttachmentSize(512)).toBe("512 B");
    expect(formatAttachmentSize(2048)).toBe("2.0 KB");
    expect(formatAttachmentSize(3 * 1024 * 1024)).toBe("3.0 MB");
  });
});
