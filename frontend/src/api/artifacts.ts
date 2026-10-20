import { apiFetch } from "./client";
import { useAuthStore } from "@/stores/auth";

/** 产物/来源记录（与后端 ArtifactResponse 对应，Phase 10）。 */
export interface ArtifactInfo {
  id: number;
  sessionId: number;
  role: "SOURCE" | "ARTIFACT";
  storage: "MINIO" | "DB";
  fileName: string;
  mime: string | null;
  sizeBytes: number;
  sha256: string;
  status: "PENDING" | "READY" | "FAILED";
  errorMessage: string | null;
  /** 文本类产物可在线预览（正文就在 content 里） */
  previewable: boolean;
  content: string | null;
  /** 产出它的那一轮 run id（Agent 主动交付的产物才有）→ 用于挂到对应气泡下方 */
  runId: string | null;
  createdAt: string;
}

export function listArtifacts(sessionId: number) {
  return apiFetch<ArtifactInfo[]>(`/api/artifacts?sessionId=${sessionId}`);
}

/**
 * 上传文件：走 multipart，不能带 JSON 的 Content-Type。
 *
 * <p>上传成功后后端会把它落一份到 Agent 工作区的 uploads/，
 * 因此 Agent 用常规文件工具就能读到（D20）。
 */
export function uploadArtifact(sessionId: number, file: File) {
  const form = new FormData();
  form.append("sessionId", String(sessionId));
  form.append("file", file);
  return apiFetch<ArtifactInfo>("/api/artifacts/upload", { method: "POST", body: form });
}

export function deleteArtifact(id: number) {
  return apiFetch<void>(`/api/artifacts/${id}`, { method: "DELETE" });
}

/**
 * 下载：MINIO 记录后端会 302 到预签名直链；DB 记录直接回字节流。
 *
 * <p>两种都必须带 Authorization，所以用 fetch 拿 blob 再触发保存，不能直接给 <a href>。
 */
export async function downloadArtifact(id: number, fileName: string) {
  const token = useAuthStore.getState().token;
  const response = await fetch(`/api/artifacts/${id}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    throw new Error(`下载失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

/**
 * 取产物**原始字节**（PDF / Excel / Word 这类必须自己解的类型用）。
 *
 * <p>下载接口要带 Authorization，所以不能直接把 URL 交给 `<iframe src>` / 解压库。
 * 图片另走 {@link fetchArtifactBlobUrl}（同样从这份字节转 blob URL）。
 */
export async function fetchArtifactBytes(id: number): Promise<Uint8Array> {
  const token = useAuthStore.getState().token;
  const response = await fetch(`/api/artifacts/${id}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    throw new Error(`加载产物失败（HTTP ${response.status}）`);
  }
  return new Uint8Array(await response.arrayBuffer());
}

/**
 * 取产物字节并转成 blob URL（图片/PDF 等二进制预览用）。
 *
 * <p>不能直接把 `/api/artifacts/{id}/download` 塞进 `<img src>`：它要带 Authorization 头。
 * 用完记得 `URL.revokeObjectURL`。
 */
export async function fetchArtifactBlobUrl(id: number, mime?: string | null): Promise<string> {
  const bytes = await fetchArtifactBytes(id);
  // 必须带上 MIME：否则 PDF 在 iframe 里不会走浏览器内置阅读器（"没有正确的 Content-Type 就下载"）
  return URL.createObjectURL(new Blob([bytes], { type: mime || "application/octet-stream" }));
}

/** 人类可读大小，用于产物清单与预览面板展示。 */
export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 图片产物：**只认 mime**（不另立扩展名清单 —— 后端已统一按 MIME 归类）。 */
export function isImageArtifact(
  item: { mime?: string | null } | null | undefined,
): boolean {
  return (item?.mime ?? "").startsWith("image/");
}

/*
 * 「能不能预览」不再在这里判断：那是 `@/lib/artifacts/previewKind` 的 `canPreviewArtifact()` 的事
 * （以前这里有 `canPreview`，与预览面板/气泡产物框各写一套，已经删掉 —— 只允许一处）。
 */

/**
 * 气泡内**内联**渲染图片的大小上限。
 *
 * <p>产物最大可达 50MB（`myagent.minio.max-file-size-mb`），无上限内联会把页面拖死；
 * 超限不在气泡内渲染，给一个可点开右侧预览面板的占位。
 */
export const INLINE_IMAGE_MAX_BYTES = 8 * 1024 * 1024;
