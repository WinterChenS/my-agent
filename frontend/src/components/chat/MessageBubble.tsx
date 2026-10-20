import { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Paperclip, Sparkles } from "lucide-react";
import {
  formatSize,
  INLINE_IMAGE_MAX_BYTES,
  isImageArtifact,
  type ArtifactInfo,
} from "@/api/artifacts";
import { resolveMarkdownImage } from "@/lib/chat/artifactImage";
import { useArtifactBlobUrl } from "./useArtifactBlobUrl";
import { cn } from "@/lib/utils";
import { attachmentBaseName, parseAttachmentBlock } from "@/lib/attachments/message";
import { textToParts } from "@/lib/skills/selection";

/**
 * 聊天气泡。
 *
 * <p>排版要点：
 * <ul>
 *   <li>用户气泡靠右、主色底；助手气泡靠左、卡片底 + 细边框，两者都用大圆角（贴近主流闲聊应用）
 *   <li>助手内容走 Markdown：**依赖 @tailwindcss/typography 的 prose**（v1 没装这个插件，
 *       prose 系列类全是空操作，所以 Markdown 按浏览器默认渲染、非常丑——已修）
 *   <li>流式过程中尾部显示光标，明确“还在生成”
 * </ul>
 */
export function MessageBubble({
  role,
  content,
  streaming,
  stopped,
  artifacts,
  onOpenArtifact,
}: {
  role: "user" | "assistant";
  content: string;
  streaming?: boolean;
  /** 这一轮被用户停止（Phase 12 / D25） */
  stopped?: boolean;
  /** 本轮的产物：图片进气泡内，正文 Markdown 图片也按文件名在这里找 */
  artifacts?: ArtifactInfo[];
  /** 点击图片 → 在右侧预览面板里打开（由 ChatPage 统一管理 tab） */
  onOpenArtifact?: (item: ArtifactInfo) => void;
}) {
  const isUser = role === "user";
  const roundArtifacts = useMemo(() => artifacts ?? [], [artifacts]);
  const roundImages = useMemo(() => roundArtifacts.filter(isImageArtifact), [roundArtifacts]);
  // 用户气泡里要把「本轮附件」显示出来（否则用户看不到自己发了什么文件）；
  // 附件块是随消息一起存库的，所以刷新回放同样能渲染。
  const parsed = isUser ? parseAttachmentBlock(content) : { text: content, attachments: [] };
  // 用户消息里的技能是行内块：正文里存的是 `[[skill:名字]]` 标记，这里按**原位置**还原成块
  // （用户要求：气泡里要看出「技能出现在当时输入的位置」）。
  const mentionParts = isUser ? textToParts(parsed.text) : [];

  return (
    <div className={cn("flex w-full", isUser ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "max-w-[min(48rem,85%)] rounded-2xl px-4 py-2.5 text-sm shadow-sm",
          isUser
            ? "bg-primary text-primary-foreground rounded-br-md"
            : "bg-card text-card-foreground rounded-bl-md border",
        )}
      >
        {isUser ? (
          <div className="space-y-1.5">
            {parsed.attachments.length > 0 && (
              <div className="flex flex-wrap gap-1" data-testid="message-attachments">
                {parsed.attachments.map((path) => (
                  <span
                    key={path}
                    data-testid="message-attachment"
                    title={path}
                    className="bg-primary-foreground/20 inline-flex max-w-full items-center gap-1 rounded-md px-2 py-0.5 text-xs"
                  >
                    <Paperclip className="size-3 shrink-0" />
                    <span className="max-w-[16rem] truncate">{attachmentBaseName(path)}</span>
                  </span>
                ))}
              </div>
            )}
            {parsed.text && (
              <p
                data-testid="user-text"
                className="leading-relaxed break-words whitespace-pre-wrap"
              >
                {mentionParts.map((part, index) =>
                  part.type === "skill" ? (
                    <span
                      key={`skill-${index}-${part.name}`}
                      data-testid="message-skill"
                      title="本轮使用的技能"
                      className="bg-primary-foreground/20 mx-0.5 inline-flex items-center gap-1 rounded px-1.5 py-0.5 align-middle text-xs font-medium"
                    >
                      <Sparkles className="size-3 shrink-0" />
                      /{part.name}
                    </span>
                  ) : (
                    <span key={`text-${index}`}>{part.text}</span>
                  ),
                )}
              </p>
            )}
          </div>
        ) : (
          <div
            data-testid="assistant-text"
            className={cn(
              "prose prose-sm prose-neutral dark:prose-invert max-w-none break-words",
              "prose-p:my-1.5 prose-p:leading-relaxed",
              "prose-headings:mt-3 prose-headings:mb-1.5 prose-headings:font-semibold",
              "prose-ul:my-1.5 prose-ol:my-1.5 prose-li:my-0.5",
              // 代码块：背景改成浅色后，必须把**文字颜色**也显式改回来。
              // 否则 prose 默认给 pre 配的是「深色底 + 浅色字」的那套颜色 → 浅底浅字，根本看不见。
              "prose-pre:my-2 prose-pre:rounded-lg prose-pre:border prose-pre:bg-muted prose-pre:p-3 prose-pre:text-xs prose-pre:text-foreground",
              "prose-code:rounded prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:text-[0.85em] prose-code:text-foreground prose-code:before:content-none prose-code:after:content-none",
              // 代码块内部的 code 不要再叠一层内联样式（会双重背景、字号错乱），直接继承外层
              "[&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-xs [&_pre_code]:text-inherit",
              "prose-blockquote:my-2 prose-blockquote:border-l-2 prose-blockquote:pl-3 prose-blockquote:not-italic",
              "prose-table:my-2 prose-th:px-2 prose-td:px-2",
              "prose-a:underline prose-a:underline-offset-2",
            )}
          >
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                // 代码块横向滚动，避免长行把气泡撑破
                pre: ({ children }) => <pre className="overflow-x-auto">{children}</pre>,
                a: ({ children, ...props }) => (
                  <a {...props} target="_blank" rel="noreferrer noopener">
                    {children}
                  </a>
                ),
                // 正文里的图片：data:/blob:/http(s) 原样；否则按文件名找本会话产物；找不到给提示
                img: ({ src, alt }) => (
                  <MarkdownImage
                    src={typeof src === "string" ? src : ""}
                    alt={alt ?? ""}
                    artifacts={roundArtifacts}
                    onOpenArtifact={onOpenArtifact}
                  />
                ),
              }}
            >
              {content}
            </ReactMarkdown>
            {streaming && (
              <span className="bg-foreground/70 ml-0.5 inline-block h-3.5 w-[2px] animate-pulse align-middle" />
            )}
            {stopped && !streaming && (
              <p
                data-testid="stopped-marker"
                className="text-muted-foreground mt-1.5 text-[11px] italic"
              >
                已停止
              </p>
            )}
            {/* 本轮的图片产物：直接显示在气泡内（点击看大图） */}
            {roundImages.length > 0 && (
              <div data-testid="bubble-images" className="mt-2 space-y-1.5">
                {roundImages.map((item) => (
                  <BubbleImage key={item.id} item={item} onOpen={onOpenArtifact} />
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** 气泡内的一张图片：超过上限的只给可点开的占位（避免大图把页面拖死）。 */
function BubbleImage({
  item,
  onOpen,
}: {
  item: ArtifactInfo;
  onOpen?: (item: ArtifactInfo) => void;
}) {
  const { url, failed } = useArtifactBlobUrl(item);

  if (item.sizeBytes > INLINE_IMAGE_MAX_BYTES) {
    return (
      <button
        type="button"
        data-testid="bubble-image"
        onClick={() => onOpen?.(item)}
        className="text-muted-foreground text-xs underline-offset-2 hover:underline"
      >
        图片较大（{formatSize(item.sizeBytes)}），点击预览
      </button>
    );
  }
  if (failed) {
    return <p className="text-muted-foreground text-xs">图片加载失败，可在右侧「产物」里下载</p>;
  }
  if (!url) {
    return <p className="text-muted-foreground text-xs">图片加载中…</p>;
  }
  return (
    <button
      type="button"
      data-testid="bubble-image"
      title="点击查看大图"
      onClick={() => onOpen?.(item)}
      className="block"
    >
      <img
        src={url}
        alt={item.fileName}
        loading="lazy"
        className="max-h-64 rounded-lg border object-contain"
      />
    </button>
  );
}

/** 正文里的 Markdown 图片：直接渲染 / 按文件名找产物 / 给提示（不静默留破图）。 */
function MarkdownImage({
  src,
  alt,
  artifacts,
  onOpenArtifact,
}: {
  src: string;
  alt: string;
  artifacts: ArtifactInfo[];
  onOpenArtifact?: (item: ArtifactInfo) => void;
}) {
  const resolved = useMemo(() => resolveMarkdownImage(src, artifacts), [src, artifacts]);
  const artifactItem = resolved?.kind === "artifact" ? resolved.item : null;
  const { url, failed } = useArtifactBlobUrl(artifactItem);

  if (!resolved) {
    return (
      <span data-testid="markdown-image-missing" className="text-muted-foreground text-xs">
        图片未找到：{src}
      </span>
    );
  }
  if (resolved.kind === "direct") {
    return (
      <img
        data-testid="markdown-image"
        src={resolved.url}
        alt={alt}
        loading="lazy"
        className="max-h-64 rounded-lg border object-contain"
      />
    );
  }
  if (failed || !url) {
    return (
      <span className="text-muted-foreground text-xs">
        {failed ? "图片加载失败" : "图片加载中…"}
      </span>
    );
  }
  return (
    <button
      type="button"
      title="点击查看大图"
      onClick={() => onOpenArtifact?.(resolved.item)}
      className="block"
    >
      <img
        data-testid="markdown-image"
        src={url}
        alt={alt || resolved.item.fileName}
        loading="lazy"
        className="max-h-64 rounded-lg border object-contain"
      />
    </button>
  );
}
