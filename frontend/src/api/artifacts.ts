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

/** 人类可读大小，用于右侧栏展示。 */
export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
