import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { INLINE_IMAGE_MAX_BYTES, formatSize, type ArtifactInfo } from "@/api/artifacts";
import { CSV_MAX_ROWS, delimiterLabel, parseCsv } from "@/lib/artifacts/csv";
import { extractDocxText } from "@/lib/artifacts/docxText";
import { previewKind } from "@/lib/artifacts/previewKind";
import { listArtifacts } from "@/api/artifacts";
import {
  MAX_INLINE_BYTES,
  MAX_INLINE_FILES,
  basenameOf,
  collectRelativeRefs,
  guessMime,
  replaceRefs,
  toDataUri,
} from "@/lib/artifacts/inlineAssets";
import { useAuthStore } from "@/stores/auth";
import { useArtifactBlobUrl } from "./useArtifactBlobUrl";
import { useArtifactBytes } from "./useArtifactBytes";

/**
 * 产物预览的**唯一**渲染体：按 {@link previewKind} 决定怎么画。
 *
 * <p>三个入口共用它（预览面板的 tab、气泡下方产物框、清单弹窗），
 * 这样"同一种文件在哪看都一样"，也不会出现三份渲染逻辑。
 *
 * <p>各类格式的实现代价（都是刻意选择的结果，见 `docs/待办-产物预览重构.md`）：
 * <ul>
 *   <li>HTML：沙箱 iframe（`allow-scripts`、**不给** `allow-same-origin`）—— 能跑图表，
 *       读不到本站 token/存储；但它仍能自行外发请求（残余风险，已与用户确认）；
 *   <li>高度：HTML/PDF/图片都**占满面板可用高度**（`h-full`）—— 以前写死 `70vh`，
 *       窗口下方白白空出一块、内容还被截断（用户实测指出）；</li>
 *   <li>Markdown / 纯文本：正文已在 `item.content` 里，直接渲染；</li>
 *   <li>CSV / TSV：同样拿 `item.content`，按表格画（不再是一坨逗号分隔的纯文本）；</li>
 *   <li>PDF / 图片：走鉴权下载 → blob URL；</li>
 *   <li>Excel：`xlsx`（SheetJS）**动态 import**，按 sheet 出表格；</li>
 *   <li>Word（`.docx`）：**零依赖**自己解 zip 抽文字（无版式/无表格结构/无图片）。</li>
 * </ul>
 */
export function ArtifactPreviewBody({ item }: { item: ArtifactInfo }) {
  switch (previewKind(item)) {
    case "html":
      return <HtmlPreview item={item} />;
    case "markdown":
      return <MarkdownPreview item={item} />;
    case "text":
      return <TextPreview item={item} />;
    case "csv":
      return <CsvPreview item={item} />;
    case "pdf":
      return <PdfPreview item={item} />;
    case "image":
      return <ImagePreview item={item} />;
    case "sheet":
      return <SheetPreview item={item} />;
    case "docx":
      return <DocxPreview item={item} />;
    default:
      return (
        <p className="text-muted-foreground text-sm">
          这种类型不支持在线预览，可点右上角下载后查看。
        </p>
      );
  }
}

/**
 * HTML：沙箱 iframe + `srcDoc`（见组件注释里的安全策略）。
 *
 * <p>多文件 deck（dashi-ppt 这类）的 `assets/xxx.js`、`url(assets/fonts/x.woff2)` 在 `srcdoc`
 * 里全部 404，整页会渲染失败。这里按**文件名**匹配同会话的其它产物，把资源读出来内联成
 * data URI 再喂给 iframe —— 产物表只存文件名（没有目录），所以匹配只能按 basename。
 */
function HtmlPreview({ item }: { item: ArtifactInfo }) {
  const [html, setHtml] = useState(item.content ?? "");
  const [inlining, setInlining] = useState(false);

  useEffect(() => {
    const source = item.content ?? "";
    setHtml(source);
    const refs = collectRelativeRefs(source);
    if (refs.length === 0) {
      return;
    }
    let cancelled = false;
    (async () => {
      setInlining(true);
      try {
        const artifacts = await listArtifacts(item.sessionId);
        const byName = new Map(artifacts.map((a) => [a.fileName, a]));
        const token = useAuthStore.getState().token;
        const replacements = new Map<string, string>();
        let total = 0;
        for (const ref of refs) {
          if (replacements.size >= MAX_INLINE_FILES || total >= MAX_INLINE_BYTES) break;
          const name = basenameOf(ref);
          const target = name ? byName.get(name) : undefined;
          if (!target || target.id === item.id) continue;
          const response = await fetch(`/api/artifacts/${target.id}/download`, {
            headers: token ? { Authorization: `Bearer ${token}` } : undefined,
          });
          if (!response.ok) continue;
          const bytes = new Uint8Array(await response.arrayBuffer());
          total += bytes.length;
          if (total > MAX_INLINE_BYTES) break;
          replacements.set(ref, toDataUri(bytes, guessMime(name)));
        }
        if (!cancelled && replacements.size > 0) {
          setHtml(replaceRefs(source, replacements));
        }
      } catch {
        // 内联失败就保持原样（至少 HTML 本身的文字还能看）
      } finally {
        if (!cancelled) setInlining(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [item.id, item.sessionId, item.content]);

  return (
    <div className="relative h-full">
      <iframe
        data-testid="preview-html"
        title={item.fileName}
        // 允许脚本（能跑图表）但不给 allow-same-origin → 拿不到本站 token/存储
        sandbox="allow-scripts"
        srcDoc={html}
        className="h-full min-h-[20rem] w-full rounded-md border bg-white"
      />
      {inlining ? (
        <span className="bg-background/90 text-muted-foreground absolute top-2 right-2 rounded px-2 py-0.5 text-[11px] shadow-sm">
          正在内联本地资源…
        </span>
      ) : null}
    </div>
  );
}

function MarkdownPreview({ item }: { item: ArtifactInfo }) {
  return (
    <div
      data-testid="artifact-preview-full"
      className="prose prose-sm prose-neutral dark:prose-invert max-w-none break-words prose-pre:overflow-x-auto"
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{item.content ?? ""}</ReactMarkdown>
    </div>
  );
}

function TextPreview({ item }: { item: ArtifactInfo }) {
  return (
    <pre
      data-testid="artifact-preview-full"
      className="bg-muted/40 rounded-md border p-3 text-xs whitespace-pre-wrap"
    >
      {item.content || "（空内容）"}
    </pre>
  );
}

/**
 * CSV / TSV：按表格画（用户要求「CSV 格式需要支持显示」）。
 *
 * <p>正文一般已经在 `item.content` 里（文本类存 DB）；万一没有（历史数据/存储被标成 MINIO），
 * 再回退到取字节按 UTF-8 解码 —— 预览宁可多下一次，也不要空白。
 *
 * <p>解析规则见 `lib/artifacts/csv.ts`：引号内逗号/换行算内容，`""` 是转义，自动认分隔符。
 */
function CsvPreview({ item }: { item: ArtifactInfo }) {
  const needsBytes = item.content == null;
  const tooLarge = needsBytes && item.sizeBytes > INLINE_IMAGE_MAX_BYTES;
  const { bytes, failed } = useArtifactBytes(tooLarge ? null : needsBytes ? item : null);
  const [decoded, setDecoded] = useState<string | null>(null);

  useEffect(() => {
    if (!bytes) {
      return;
    }
    let cancelled = false;
    try {
      const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
      if (!cancelled) {
        setDecoded(text);
      }
    } catch {
      if (!cancelled) {
        setDecoded("");
      }
    }
    return () => {
      cancelled = true;
    };
  }, [bytes]);

  if (tooLarge) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-too-large">
        文件较大（{formatSize(item.sizeBytes)}），不在这里解析，请点右上角下载后查看。
      </p>
    );
  }
  const text = needsBytes ? decoded : (item.content ?? "");
  if (failed) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-csv-failed">
        表格内容加载失败，可点右上角下载。
      </p>
    );
  }
  if (text === null) {
    return <p className="text-muted-foreground text-sm">加载中…</p>;
  }
  if (text.trim() === "") {
    return <p className="text-muted-foreground text-sm">（空文件）</p>;
  }

  const table = parseCsv(text);
  if (table.header.length === 0 && table.rows.length === 0) {
    return <p className="text-muted-foreground text-sm">（没有可显示的行）</p>;
  }

  return (
    <div data-testid="preview-csv" className="space-y-2">
      <div className="overflow-auto rounded-md border">
        <table data-testid="preview-csv-table" className="w-full text-left text-xs">
          <thead>
            <tr className="bg-muted/60 border-b">
              {table.header.map((cell, index) => (
                <th
                  key={index}
                  className="border-r px-2 py-1 font-medium whitespace-nowrap last:border-r-0"
                >
                  {cell}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {table.rows.map((row, rowIndex) => (
              <tr key={rowIndex} className="border-b last:border-b-0">
                {row.map((cell, cellIndex) => (
                  <td
                    key={cellIndex}
                    className="border-r px-2 py-1 whitespace-pre-wrap last:border-r-0"
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-muted-foreground text-[11px]">
        共 {table.rows.length} 行（表头之外），按「{delimiterLabel(table.delimiter)}」分列。
        {table.truncated && `内容较大，只显示前 ${CSV_MAX_ROWS} 行 / ${table.header.length} 列，完整内容请下载后查看。`}
      </p>
    </div>
  );
}

function PdfPreview({ item }: { item: ArtifactInfo }) {
  // PDF 要字节：交给浏览器内置阅读器（`anyMime` 让它别按图片规则短路）
  const { url, failed } = useArtifactBlobUrl(item, { anyMime: true });
  if (failed) {
    return <p className="text-muted-foreground text-sm">PDF 加载失败，可点右上角下载。</p>;
  }
  if (!url) {
    return <p className="text-muted-foreground text-sm">加载中…</p>;
  }
  return (
    <iframe
      data-testid="preview-pdf"
      title={item.fileName}
      src={url}
      className="h-full min-h-[20rem] w-full rounded-md border"
    />
  );
}

function ImagePreview({ item }: { item: ArtifactInfo }) {
  const { url, failed } = useArtifactBlobUrl(item);
  if (failed) {
    return <p className="text-muted-foreground text-sm">图片加载失败，可点右上角下载。</p>;
  }
  if (!url) {
    return <p className="text-muted-foreground text-sm">加载中…</p>;
  }
  return (
    <img
      data-testid="artifact-preview-image"
      src={url}
      alt={item.fileName}
      className="mx-auto max-h-full max-w-full rounded"
    />
  );
}

/** 表格里最多画多少行（SheetJS 能把十万行读进来，全画进 DOM 会卡死） */
const SHEET_MAX_ROWS = 200;

interface SheetTable {
  name: string;
  rows: string[][];
  truncated: boolean;
}

/**
 * Excel：动态 `import("xlsx")` 后按 sheet 出表格。
 *
 * <p>为什么不用 `sheet_to_html`：那是一段 HTML 字符串，要 `dangerouslySetInnerHTML` 塞进页面
 * —— 单元格内容就成了注入面。这里改成 `sheet_to_json` 拿二维数组，交给 React 按文本渲染。
 */
function SheetPreview({ item }: { item: ArtifactInfo }) {
  const tooLarge = item.sizeBytes > INLINE_IMAGE_MAX_BYTES;
  const { bytes, failed } = useArtifactBytes(tooLarge ? null : item);
  const [sheets, setSheets] = useState<SheetTable[] | null>(null);
  const [active, setActive] = useState(0);
  const [parseFailed, setParseFailed] = useState(false);

  useEffect(() => {
    if (!bytes) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        // 动态 import：xlsx 只在这个分支需要，别塞进首屏包
        const XLSX = await import("xlsx");
        const book = XLSX.read(bytes, { type: "array" });
        const parsed = book.SheetNames.map((name) => {
          const sheet = book.Sheets[name];
          const all = sheet
            ? (XLSX.utils.sheet_to_json<string[]>(sheet, {
                header: 1,
                raw: false,
                defval: "",
                blankrows: false,
              }) as string[][])
            : [];
          const rows = all.slice(0, SHEET_MAX_ROWS).map((row) => row.map((cell) => String(cell ?? "")));
          return { name, rows, truncated: all.length > rows.length };
        });
        if (!cancelled) {
          setSheets(parsed);
        }
      } catch {
        if (!cancelled) {
          setParseFailed(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [bytes]);

  if (tooLarge) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-too-large">
        文件较大（{formatSize(item.sizeBytes)}），不在这里解析，请点右上角下载后查看。
      </p>
    );
  }
  if (failed || parseFailed) {
    return <p className="text-muted-foreground text-sm">表格解析失败，可点右上角下载。</p>;
  }
  if (!sheets) {
    return <p className="text-muted-foreground text-sm">加载中…</p>;
  }
  if (sheets.length === 0) {
    return <p className="text-muted-foreground text-sm">这个工作簿里没有工作表。</p>;
  }

  const current = sheets[Math.min(active, sheets.length - 1)];
  return (
    <div data-testid="preview-sheet" className="space-y-2">
      {sheets.length > 1 && (
        <div className="flex flex-wrap gap-1">
          {sheets.map((sheet, index) => (
            <button
              key={sheet.name}
              type="button"
              data-testid="preview-sheet-tab"
              onClick={() => setActive(index)}
              className={`rounded-md px-2 py-1 text-xs ${
                index === active
                  ? "bg-accent text-accent-foreground"
                  : "text-muted-foreground hover:bg-accent/40"
              }`}
            >
              {sheet.name}
            </button>
          ))}
        </div>
      )}
      <div className="overflow-auto rounded-md border">
        <table data-testid="preview-sheet-table" className="w-full text-left text-xs">
          <tbody>
            {current.rows.map((row, rowIndex) => (
              <tr key={rowIndex} className="border-b last:border-b-0">
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex} className="border-r px-2 py-1 whitespace-pre-wrap last:border-r-0">
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {current.truncated && (
        <p className="text-muted-foreground text-[11px]">
          只显示前 {SHEET_MAX_ROWS} 行，完整内容请下载后查看。
        </p>
      )}
      <p className="text-muted-foreground text-[11px]">预览只保证读到单元格文本，不含样式与公式结果格式。</p>
    </div>
  );
}

/** Word：零依赖解 zip 抽文字（见 `lib/artifacts/docxText.ts`）。 */
function DocxPreview({ item }: { item: ArtifactInfo }) {
  const tooLarge = item.sizeBytes > INLINE_IMAGE_MAX_BYTES;
  const { bytes, failed } = useArtifactBytes(tooLarge ? null : item);
  const [text, setText] = useState<string | null>(null);
  const [parseFailed, setParseFailed] = useState(false);

  useEffect(() => {
    if (!bytes) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const extracted = await extractDocxText(bytes);
        if (!cancelled) {
          setText(extracted);
        }
      } catch {
        if (!cancelled) {
          setParseFailed(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [bytes]);

  if (tooLarge) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-too-large">
        文件较大（{formatSize(item.sizeBytes)}），不在这里解析，请点右上角下载后查看。
      </p>
    );
  }
  if (failed || parseFailed) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-docx-failed">
        无法解析这个 Word 文件，请点右上角下载后查看。
      </p>
    );
  }
  if (text === null) {
    return <p className="text-muted-foreground text-sm">加载中…</p>;
  }
  return (
    <div className="space-y-2">
      <pre
        data-testid="artifact-preview-full"
        className="bg-muted/40 rounded-md border p-3 text-xs whitespace-pre-wrap"
      >
        {text || "（这个 Word 文件里没有可提取的文字）"}
      </pre>
      <p className="text-muted-foreground text-[11px]">
        只提取文字：没有版式、表格结构与图片，请下载原文件查看完整效果。
      </p>
    </div>
  );
}
