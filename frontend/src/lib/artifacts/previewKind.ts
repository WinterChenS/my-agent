/**
 * 产物预览类型的**唯一**判定。
 *
 * <p>以前是“能预览 = 文本/图片”两处分散判断；现在按 MIME 归成一个函数，
 * 预览面板、气泡产物框、清单弹窗都问它，避免三处各写一套 if。
 */
export type PreviewKind =
  | "image"
  | "pdf"
  | "html"
  | "markdown"
  | "text"
  | "sheet"
  | "docx"
  | "none";

const SHEET_MIMES = new Set([
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-excel",
]);
const DOCX_MIMES = new Set([
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
]);

export function previewKind(
  item: { mime?: string | null; fileName?: string | null; previewable?: boolean } | null | undefined,
): PreviewKind {
  if (!item) {
    return "none";
  }
  const mime = (item.mime ?? "").toLowerCase();
  const name = (item.fileName ?? "").toLowerCase();
  const ext = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1) : "";

  if (mime.startsWith("image/")) {
    return "image";
  }
  if (mime === "application/pdf" || ext === "pdf") {
    return "pdf";
  }
  if (SHEET_MIMES.has(mime) || ext === "xlsx" || ext === "xls") {
    return "sheet";
  }
  if (DOCX_MIMES.has(mime) || ext === "docx") {
    return "docx";
  }
  if (mime === "text/html" || ext === "html" || ext === "htm") {
    return "html";
  }
  if (mime === "text/markdown" || ext === "md" || ext === "markdown") {
    return "markdown";
  }
  // 其余「后端说正文已在手」的文本类（txt/csv/json/xml/yaml/代码…）走纯文本
  if (
    item.previewable ||
    mime.startsWith("text/") ||
    mime.includes("json") ||
    mime.includes("xml") ||
    mime.includes("yaml") ||
    mime.includes("csv")
  ) {
    return "text";
  }
  return "none";
}

/** 这个产物能不能在线预览（三个入口共用这一个判断）。 */
export function canPreviewArtifact(
  item: { mime?: string | null; fileName?: string | null; previewable?: boolean } | null | undefined,
): boolean {
  return previewKind(item) !== "none";
}
