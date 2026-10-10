import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";

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
}: {
  role: "user" | "assistant";
  content: string;
  streaming?: boolean;
}) {
  const isUser = role === "user";

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
          <p
            data-testid="user-text"
            className="leading-relaxed whitespace-pre-wrap break-words"
          >
            {content}
          </p>
        ) : (
          <div
            data-testid="assistant-text"
            className={cn(
              "prose prose-sm prose-neutral dark:prose-invert max-w-none break-words",
              "prose-p:my-1.5 prose-p:leading-relaxed",
              "prose-headings:mt-3 prose-headings:mb-1.5 prose-headings:font-semibold",
              "prose-ul:my-1.5 prose-ol:my-1.5 prose-li:my-0.5",
              "prose-pre:my-2 prose-pre:rounded-lg prose-pre:bg-muted prose-pre:p-3 prose-pre:text-xs",
              "prose-code:rounded prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:text-[0.85em] prose-code:before:content-none prose-code:after:content-none",
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
              }}
            >
              {content}
            </ReactMarkdown>
            {streaming && (
              <span className="bg-foreground/70 ml-0.5 inline-block h-3.5 w-[2px] animate-pulse align-middle" />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
