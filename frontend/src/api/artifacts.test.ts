import { describe, expect, it } from "vitest";
import { canPreview, INLINE_IMAGE_MAX_BYTES, isImageArtifact, type ArtifactInfo } from "./artifacts";

function artifact(partial: Partial<ArtifactInfo>): ArtifactInfo {
  return {
    id: 1,
    sessionId: 1,
    role: "ARTIFACT",
    storage: "MINIO",
    fileName: "a.bin",
    mime: null,
    sizeBytes: 10,
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

describe("isImageArtifact", () => {
  it("只看 mime 前缀（不认扩展名清单）", () => {
    expect(isImageArtifact(artifact({ mime: "image/png" }))).toBe(true);
    expect(isImageArtifact(artifact({ mime: "image/svg+xml" }))).toBe(true);
    expect(isImageArtifact(artifact({ mime: "IMAGE/PNG" }))).toBe(false); // mime 由后端规范化，不做大小写兜底
    expect(isImageArtifact(artifact({ fileName: "a.png", mime: null }))).toBe(false);
    expect(isImageArtifact(artifact({ mime: "application/octet-stream" }))).toBe(false);
    expect(isImageArtifact(null)).toBe(false);
    expect(isImageArtifact(undefined)).toBe(false);
  });
});

describe("canPreview", () => {
  it("文本类可预览，图片类也可预览", () => {
    expect(canPreview(artifact({ previewable: true }))).toBe(true);
    expect(canPreview(artifact({ mime: "image/png", previewable: false }))).toBe(true);
    expect(canPreview(artifact({ mime: "application/zip", previewable: false }))).toBe(false);
  });
});

describe("INLINE_IMAGE_MAX_BYTES", () => {
  it("是一个正数上限（气泡内内联图片的门槛）", () => {
    expect(INLINE_IMAGE_MAX_BYTES).toBeGreaterThan(0);
  });
});
