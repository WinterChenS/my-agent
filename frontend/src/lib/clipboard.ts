/**
 * 复制文本到剪贴板。
 *
 * 注意：`navigator.clipboard` 和 `crypto.randomUUID` 一样，**只在安全上下文**（HTTPS / localhost）
 * 里存在。内网用 http://192.168.x.x 打开时它是 undefined，直接调用会抛异常、按钮"点了没反应"。
 * 这里用 `document.execCommand("copy")` 兜底（虽然已废弃，但在非安全上下文里是唯一可用手段）。
 */
export async function copyText(text: string): Promise<boolean> {
  const clipboard = navigator.clipboard;
  if (clipboard && typeof clipboard.writeText === "function") {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // 权限被拒 / 非安全上下文 → 走兜底
    }
  }

  try {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "");
    textarea.style.position = "fixed";
    textarea.style.top = "-1000px";
    textarea.style.opacity = "0";
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}
