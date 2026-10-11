/**
 * 输入框上方「附件暂存区」的纯逻辑（Phase 11 / D24）。
 *
 * <p>用户确认的交互：附件先**暂存**在浏览器里、显示在输入框上方、可移除、可多选/追加，
 * **点发送时才真正上传**（暂存期间不写库、不产生记录）。抽成纯函数以便单测。
 */

export interface StagedAttachment {
  /** 去重用的稳定标识：文件名 + 大小 */
  key: string;
  file: File;
}

/** 同名同大小视为同一个附件（避免重复选择时出现两条）。 */
export function attachmentKey(name: string, size: number): string {
  return `${name}::${size}`;
}

export function toStaged(file: File): StagedAttachment {
  return { key: attachmentKey(file.name, file.size), file };
}

/**
 * 追加暂存：多选与分多次选择都支持；已存在的（同名同大小）不重复加入。
 *
 * @returns 新的暂存列表
 */
export function stageFiles(current: StagedAttachment[], incoming: File[]): StagedAttachment[] {
  if (!incoming || incoming.length === 0) {
    return current;
  }
  const seen = new Set(current.map((item) => item.key));
  const added: StagedAttachment[] = [];
  for (const file of incoming) {
    const staged = toStaged(file);
    if (seen.has(staged.key)) {
      continue;
    }
    seen.add(staged.key);
    added.push(staged);
  }
  return added.length === 0 ? current : [...current, ...added];
}

export function removeAttachment(current: StagedAttachment[], key: string): StagedAttachment[] {
  return current.filter((item) => item.key !== key);
}

/** 是否所有暂存附件都是空的（用于禁用发送按钮）。 */
export function hasStaged(current: StagedAttachment[]): boolean {
  return current.length > 0;
}

/** 人类可读大小（与右侧栏展示保持一致）。 */
export function formatAttachmentSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
