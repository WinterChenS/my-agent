/**
 * 附件的「拖拽 / 粘贴」来源处理（Phase 13 优化）。
 *
 * <p>用户要求：输入框除了点按钮选文件，还要支持**拖进来**和**直接粘贴**（含截图）。
 * 这两个来源拿到的是 `DataTransfer`/`ClipboardEvent.clipboardData`，浏览器差异较多
 * （比如截图的名字常常是空的或 `image.png`），这里统一成 `File[]` 再交给暂存区（D24：发送时才上传）。
 */

/** 粘贴/拖拽进来的通用文件名（浏览器对截图常给这些名字）。 */
const GENERIC_NAMES = new Set(["", "image.png", "image.jpg", "blob", "unnamed"]);

function isGenericName(name: string | undefined): boolean {
  const lower = (name ?? "").trim().toLowerCase();
  return GENERIC_NAMES.has(lower) || lower.startsWith("blob:");
}

/** 从拖拽的 DataTransfer 取文件（优先 files，其次 items）。 */
export function filesFromDataTransfer(dataTransfer: DataTransfer | null | undefined): File[] {
  if (!dataTransfer) {
    return [];
  }
  const fromFiles = Array.from(dataTransfer.files ?? []);
  if (fromFiles.length > 0) {
    return fromFiles;
  }
  return Array.from(dataTransfer.items ?? [])
    .filter((item) => item.kind === "file")
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
}

/** 从剪贴板取文件（粘贴截图/复制的文件）。纯文本粘贴不在这里处理，交给输入框本身。 */
export function filesFromClipboard(clipboardData: DataTransfer | null | undefined): File[] {
  return filesFromDataTransfer(clipboardData);
}

/**
 * 给来源文件一个可读且唯一的名字。
 *
 * <p>截图粘贴进来通常是空的或 `image.png`；多个截图会重名，而暂存区按「文件名+大小」去重，
 * 重名会让用户分不清。这里按时间戳补一个名字（保留原始扩展名）。
 */
export function normalizeDroppedFile(file: File, index: number, now: number = Date.now()): File {
  if (!isGenericName(file.name)) {
    return file;
  }
  const ext = extensionOf(file.type, file.name);
  const name = `粘贴文件-${now}-${index + 1}${ext}`;
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

/** 由 MIME/原名推断扩展名（拿不到就留空）。 */
export function extensionOf(mime: string | undefined, originalName: string | undefined): string {
  const fromName = (originalName ?? "").match(/\.[A-Za-z0-9]{1,8}$/)?.[0];
  if (fromName) {
    return fromName.toLowerCase();
  }
  const map: Record<string, string> = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/gif": ".gif",
    "image/webp": ".webp",
    "text/plain": ".txt",
    "text/markdown": ".md",
    "application/pdf": ".pdf",
    "application/json": ".json",
    "application/zip": ".zip",
  };
  return map[(mime ?? "").toLowerCase()] ?? "";
}

/** 把一次拖拽/粘贴得到的文件规范化（补名字），并保持顺序。 */
export function normalizeIncomingFiles(files: File[], now: number = Date.now()): File[] {
  return files.map((file, index) => normalizeDroppedFile(file, index, now));
}
