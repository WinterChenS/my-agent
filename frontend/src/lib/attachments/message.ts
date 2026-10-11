/**
 * 用户消息里的「附件块」约定（Phase 11 修复）。
 *
 * <p>背景（用户实测反馈）：上传文件后，① 气泡里看不到自己发了什么；② 模型完全不知道这条消息带了文件
 * ——文件只被物化到工作区 `uploads/`，模型只能去列目录猜，最后反过来问用户「您指的是哪个文件」。
 *
 * <p>做法：把本轮附件清单作为一段**结构化文本**附在用户消息末尾一起发出。这样：
 * <ul>
 *   <li>模型能直接看到「有哪些文件、在哪」（工作区相对路径），不需要猜；
 *   <li>消息照旧存在 `chat_message` 里，刷新回放时同一段文本还在，因此气泡也能稳定渲染出附件；
 *   <li>不需要改 AG-UI 契约或数据库结构。
 * </ul>
 */

export const ATTACHMENT_HEADER = "[已上传附件]";

/** 生成附件块（追加到用户文本之后）。 */
export function buildAttachmentBlock(relativePaths: string[]): string {
  if (relativePaths.length === 0) {
    return "";
  }
  return [
    "",
    ATTACHMENT_HEADER,
    "以下文件已放在你的工作区 uploads/ 目录，请直接用文件工具读取：",
    ...relativePaths.map((path) => `- ${path}`),
  ].join("\n");
}

/** 解析出「用户正文」与「附件路径列表」；没有附件块时原样返回。 */
export function parseAttachmentBlock(content: string): {
  text: string;
  attachments: string[];
} {
  const marker = "\n" + ATTACHMENT_HEADER;
  let start = content.indexOf(marker);
  if (start === -1 && content.startsWith(ATTACHMENT_HEADER)) {
    start = 0;
  }
  if (start === -1) {
    return { text: content, attachments: [] };
  }
  const text = content.slice(0, start).replace(/\s+$/, "");
  const attachments = content
    .slice(start)
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("- "))
    .map((line) => line.slice(2).trim())
    // 只认工作区里的上传路径，避免把正文里的普通列表误当成附件
    .filter((path) => path.startsWith("uploads/"));
  return { text, attachments };
}

/** 从相对路径取文件名（气泡上只显示文件名）。 */
export function attachmentBaseName(relativePath: string): string {
  const parts = relativePath.split("/");
  return parts[parts.length - 1] || relativePath;
}
