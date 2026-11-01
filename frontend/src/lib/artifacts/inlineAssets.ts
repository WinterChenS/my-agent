/**
 * HTML 产物预览的「同会话资源内联」工具。
 *
 * <p>为什么需要：预览走的是 `iframe srcdoc`（`about:srcdoc`），里面任何**相对路径**引用
 * （`assets/vendor/gsap.min.js`、`url(assets/fonts/x.woff2)`）都会去平台自己的域名下找 → 404，
 * 于是整页渲染失败（用户实测：dashi-ppt 导出的 deck 打开就是「该页渲染失败，内容已跳过」）。
 * 平台又不允许 HTML 产物带目录结构（产物表只存文件名），所以相对引用一定对不上。
 *
 * <p>做法：把 HTML 里所有相对引用，按**文件名**匹配同会话的其它产物，读出来转成 data URI 内联进
 * `srcdoc`。这样多文件 deck 在预览里也能正常显示，不必逼用户重新导出单文件。
 *
 * <p>限制（安全/性能）：最多 {@link MAX_INLINE_FILES} 个文件、总大小 {@link MAX_INLINE_BYTES}；
 * 只处理相对引用，`http(s)://` / `data:` / `#anchor` 一律不动。
 */

export const MAX_INLINE_FILES = 40;
export const MAX_INLINE_BYTES = 8 * 1024 * 1024;

/** 已经是"自带来源"的引用，不需要内联。 */
export function isRelativeRef(ref: string): boolean {
  const value = ref.trim();
  if (!value) return false;
  if (value.startsWith("#")) return false;
  if (value.startsWith("//")) return false;
  if (/^(https?:|data:|blob:|mailto:|tel:|javascript:)/i.test(value)) return false;
  return true;
}

/**
 * 从 HTML 里找出所有相对引用的**原始字符串**（保持原样，便于精确替换）。
 *
 * 覆盖三种写法：
 * - `<script src="...">` / `<link href="...">` / `<img src="...">` 等属性
 * - CSS 的 `url(...)`（字体、背景图都走这个）
 * - `srcset` 里的多值（逗号分隔）——只取第一段常见形式，够用即可
 */
export function collectRelativeRefs(html: string): string[] {
  const found = new Set<string>();
  const attr = /(?:src|href)\s*=\s*["']([^"']+)["']/gi;
  for (const match of html.matchAll(attr)) {
    const ref = match[1];
    if (isRelativeRef(ref)) found.add(ref);
  }
  const cssUrl = /url\(\s*['"]?([^'")]+)['"]?\s*\)/gi;
  for (const match of html.matchAll(cssUrl)) {
    const ref = match[1];
    if (isRelativeRef(ref)) found.add(ref);
  }
  return [...found];
}

/** 引用 → 产物文件名：去掉 query/hash 后取最后一段（产物只存文件名，没有目录）。 */
export function basenameOf(ref: string): string {
  const cleaned = ref.split(/[?#]/)[0];
  const parts = cleaned.split(/[\\/]/);
  return parts[parts.length - 1] ?? "";
}

const MIME_BY_EXT: Record<string, string> = {
  js: "text/javascript",
  mjs: "text/javascript",
  css: "text/css",
  json: "application/json",
  woff2: "font/woff2",
  woff: "font/woff",
  ttf: "font/ttf",
  otf: "font/otf",
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  webp: "image/webp",
  gif: "image/gif",
  svg: "image/svg+xml",
  mp4: "video/mp4",
  webm: "video/webm",
  ico: "image/x-icon",
};

/** 按扩展名猜 MIME（data URI 里要用；猜不到给 octet-stream）。 */
export function guessMime(name: string): string {
  const dot = name.lastIndexOf(".");
  const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : "";
  return MIME_BY_EXT[ext] ?? "application/octet-stream";
}

/**
 * 把引用替换成 data URI。
 *
 * <p>只替换**引用位置**（属性值与 `url(...)`），不能拿字符串做全局 replace ——
 * 否则正文里出现的同名文字也会被替换（实测踩过）。
 */
export function replaceRefs(html: string, replacements: Map<string, string>): string {
  const pattern = /((?:src|href)\s*=\s*["'])([^"']+)(["'])|url\(\s*["']?([^'")]+)["']?\s*\)/gi;
  return html.replace(pattern, (match, attrPrefix, attrRef, attrSuffix, cssRef) => {
    const ref: string | undefined = attrRef ?? cssRef;
    if (!ref) return match;
    const data = replacements.get(ref);
    if (!data) return match;
    return attrRef ? `${attrPrefix}${data}${attrSuffix}` : `url(${data})`;
  });
}

/** 字节 → data URI。分段 btoa，避免大文件调用栈溢出。 */
export function toDataUri(bytes: Uint8Array, mime: string): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return `data:${mime};base64,${btoa(binary)}`;
}
