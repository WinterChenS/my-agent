import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { type ArtifactInfo } from "@/api/artifacts";
import { previewKind } from "@/lib/artifacts/previewKind";
import { useArtifactBlobUrl } from "./useArtifactBlobUrl";

/**
 * 产物预览的**唯一**渲染体：按 {@link previewKind} 决定怎么画。
 *
 * <p>三个入口共用它（预览面板的 tab、气泡下方产物框、右侧窄栏列表），
 * 这样"同一种文件在哪看都一样"，也不会出现三份渲染逻辑。
 */
export function ArtifactPreviewBody({ item }: { item: ArtifactInfo }) {
  const kind = previewKind(item);
  // 图片与 PDF 需要字节（走鉴权下载转 blob）；文本类正文已在 item.content 里
  const needsBlob = kind === "image" || kind === "pdf";
  const { url, failed } = useArtifactBlobUrl(needsBlob ? item : null);

  if (kind === "html") {
    return (
      <iframe
        data-testid="preview-html"
        title={item.fileName}
        // 沙箱：允许脚本（能跑图表）但不给 allow-same-origin → 拿不到本站 token/存储
        sandbox="allow-scripts"
        srcDoc={item.content ?? ""}
        className="h-[70vh] w-full rounded-md border bg-white"
      />
    );
  }

  if (kind === "markdown") {
    return (
      <div
        data-testid="artifact-preview-full"
        className="prose prose-sm prose-neutral dark:prose-invert max-w-none break-words prose-pre:overflow-x-auto"
      >
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{item.content ?? ""}</ReactMarkdown>
      </div>
    );
  }

  if (kind === "text") {
    return (
      <pre
        data-testid="artifact-preview-full"
        className="bg-muted/40 rounded-md border p-3 text-xs whitespace-pre-wrap"
      >
        {item.content || "（空内容）"}
      </pre>
    );
  }

  if (kind === "pdf") {
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
        className="h-[70vh] w-full rounded-md border"
      />
    );
  }

  if (kind === "image") {
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
        className="mx-auto max-h-[70vh] max-w-full rounded"
      />
    );
  }

  if (kind === "sheet" || kind === "docx") {
    return (
      <p className="text-muted-foreground text-sm" data-testid="preview-pending">
        {kind === "sheet" ? "Excel" : "Word"} 预览即将支持（正在接入），当前可点右上角下载。
      </p>
    );
  }

  return (
    <p className="text-muted-foreground text-sm">
      这种类型不支持在线预览，可点右上角下载后查看。
    </p>
  );
}
