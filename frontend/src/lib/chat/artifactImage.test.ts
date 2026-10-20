import { describe, expect, it } from "vitest";
import { resolveMarkdownImage } from "./artifactImage";
import type { ArtifactInfo } from "@/api/artifacts";

function artifact(partial: Partial<ArtifactInfo>): ArtifactInfo {
  return {
    id: 1,
    sessionId: 1,
    role: "ARTIFACT",
    storage: "MINIO",
    fileName: "a.png",
    mime: "image/png",
    sizeBytes: 1,
    sha256: "x",
    status: "READY",
    errorMessage: null,
    previewable: false,
    content: null,
    runId: null,
    createdAt: "2026-10-19T00:00:00Z",
    ...partial,
  };
}

describe("resolveMarkdownImage", () => {
  it("data:/blob:/http(s): 原样渲染（外部图不由平台代理）", () => {
    expect(resolveMarkdownImage("data:image/png;base64,AAA", [])).toEqual({
      kind: "direct",
      url: "data:image/png;base64,AAA",
    });
    expect(resolveMarkdownImage("https://x.test/a.png", [])).toEqual({
      kind: "direct",
      url: "https://x.test/a.png",
    });
    expect(resolveMarkdownImage("blob:http://localhost/abc", [])).toEqual({
      kind: "direct",
      url: "blob:http://localhost/abc",
    });
  });

  it("否则按 basename（大小写不敏感）匹配本会话图片产物", () => {
    const hit = artifact({ id: 9, fileName: "Shot.PNG" });
    expect(resolveMarkdownImage("shot.png", [hit])).toEqual({ kind: "artifact", item: hit });
    expect(resolveMarkdownImage("out/shots/shot.png", [hit])).toEqual({
      kind: "artifact",
      item: hit,
    });
    expect(resolveMarkdownImage("out\\shots\\SHOT.png", [hit])).toEqual({
      kind: "artifact",
      item: hit,
    });
  });

  it("匹配不到、或同名但不是图片 → null（界面给提示，不静默留破图）", () => {
    expect(resolveMarkdownImage("missing.png", [])).toBeNull();
    expect(
      resolveMarkdownImage("note.md", [artifact({ fileName: "note.md", mime: "text/markdown" })]),
    ).toBeNull();
    expect(resolveMarkdownImage("", [])).toBeNull();
  });
});
