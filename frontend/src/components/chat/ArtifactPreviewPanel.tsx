import { useState, type ReactNode } from "react";
import { Download, FileText, Globe, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { downloadArtifact, formatSize, type ArtifactInfo } from "@/api/artifacts";
import { clampPreviewWidth, PREVIEW_PANEL_DEFAULT } from "@/lib/chat/previewPanelWidth";
import { ArtifactPreviewBody } from "./ArtifactPreviewBody";

/**
 * 右侧预览面板：**内联挤压**聊天区（不是遮罩抽屉），可整体关闭；多产物用上方 tab 分开。
 *
 * <p>内容交给 {@link ArtifactPreviewBody} 渲染 —— 同一种文件在哪儿看都一样。
 *
 * <p>两条与布局相关的能力：
 * <ul>
 *   <li>左上角放「来源与产物」入口（`browserTrigger`，由 ChatPage 以弹窗组件包好后传进来 ——
 *       弹窗要贴着这个按钮弹出，所以触发按钮必须由弹窗自己包裹）；</li>
 *   <li>**左边界可拖拽**调整宽度：它把聊天区与预览区之间的分隔线做成可拖的（用户要求）。</li>
 * </ul>
 */
export function ArtifactPreviewPanel({
  tabs,
  activeId,
  onSelect,
  onCloseTab,
  onClose,
  width,
  onWidthChange,
  browserTrigger,
  mode = "files",
  onModeChange,
  browserActive = false,
  browserContent,
  open = true,
}: {
  tabs: ArtifactInfo[];
  activeId: number | null;
  onSelect: (id: number) => void;
  onCloseTab: (id: number) => void;
  onClose: () => void;
  /** 面板宽度（px），由父组件持有 —— 拖拽时逐帧更新 */
  width: number;
  onWidthChange: (width: number) => void;
  /** 「来源文件与产物」弹窗的触发按钮（已包好弹窗逻辑） */
  browserTrigger: ReactNode;
  /** 当前看的是「文件（产物）」还是「浏览器（agent 实时画面）」 */
  mode?: "files" | "browser";
  onModeChange?: (mode: "files" | "browser") => void;
  /** 是否已有活跃的浏览器会话（没有时按钮仍可点，进去给空状态提示） */
  browserActive?: boolean;
  /** 浏览器视图的内容（由 ChatPage 传进来，避免面板耦合浏览器逻辑） */
  browserContent?: ReactNode;
  /**
   * 是否展开。**组件常驻渲染**（不随开关卸载），这样收起/展开时宽度能做过渡动画，
   * 视觉上就是「面板滑进滑出、聊天区自动让位」；收起时宽度 0 + overflow hidden。
   */
  open?: boolean;
}) {
  const [resizing, setResizing] = useState(false);
  const active = tabs.find((item) => item.id === activeId) ?? tabs[0] ?? null;
  const showBrowser = mode === "browser";
  // 只有在"看文件"时才需要产物；看浏览器时没有产物也应该能打开面板。
  // 注意：这里不再 return null —— 常驻一个 0 宽容器，收起动画才有落点。
  const hasContent = showBrowser || Boolean(active);
  const visible = open && hasContent;
  const effectiveWidth = visible ? width : 0;
  const widthClass = resizing ? "" : "transition-[width] duration-200 ease-out";

  // 没有内容：收起时留 0 宽占位（收起动画要落点）；**展开时显示空状态**
  // （用户反馈：没有产物时点「文件」会把侧栏关掉，应该打开并提示"还没有产物"）
  if (!hasContent) {
    if (!open) {
      return (
        <aside
          data-testid="preview-panel"
          aria-hidden
          className={`shrink-0 overflow-hidden ${widthClass}`}
          style={{ width: 0 }}
        />
      );
    }
    return (
      <aside
        data-testid="preview-panel"
        className={`relative flex shrink-0 flex-col overflow-hidden border-l ${widthClass}`}
        style={{ width: `${width}px` }}
      >
        <div className="flex items-center gap-1 border-b px-2 py-1.5">
          <div className="bg-muted flex shrink-0 items-center gap-0.5 rounded-md p-0.5">
            <button
              type="button"
              data-testid="preview-mode-files"
              aria-pressed
              className="bg-background flex items-center gap-1 rounded px-1.5 py-1 text-xs shadow-sm"
            >
              <FileText className="size-3.5" />
              <span className="hidden sm:inline">文件</span>
            </button>
            <button
              type="button"
              data-testid="preview-mode-browser"
              title="浏览器（agent 正在操作的页面）"
              aria-pressed={false}
              onClick={() => onModeChange?.("browser")}
              className="text-muted-foreground hover:text-foreground flex items-center gap-1 rounded px-1.5 py-1 text-xs transition-colors"
            >
              <Globe className="size-3.5" />
              <span className="hidden sm:inline">浏览器</span>
            </button>
          </div>
          <button
            type="button"
            aria-label="关闭面板"
            title="关闭面板"
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground ml-auto rounded p-1 transition-colors"
          >
            <X className="size-3.5" />
          </button>
        </div>
        <div
          data-testid="preview-empty"
          className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center"
        >
          <FileText className="text-muted-foreground/40 size-8" />
          <p className="text-sm font-medium">还没有产物</p>
          <p className="text-muted-foreground max-w-[16rem] text-xs leading-relaxed">
            Agent 交付的文件、图片、表格会出现在这里。也可以先切到「浏览器」，看它正在操作哪个网页。
          </p>
          <Button variant="outline" size="sm" onClick={() => onModeChange?.("browser")}>
            切换到浏览器
          </Button>
        </div>
      </aside>
    );
  }

  /**
   * 开始拖拽分隔线。
   *
   * <p>用 pointer capture 把后续 move/up 都收在这个小把手上：否则鼠标一快就"跑出"把手，
   * 事件落到聊天区里，拖拽会中途断掉。向左拖 = 面板变宽（`startX - e.clientX`）。
   */
  function startResize(event: React.PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const handle = event.currentTarget;
    const startX = event.clientX;
    const startWidth = width;
    handle.setPointerCapture(event.pointerId);
    setResizing(true);

    const onMove = (moveEvent: PointerEvent) => {
      // 上限 = 聊天区域的可用宽度（铺满），不是 window.innerWidth：
      // 左边还有导航栏，用窗口宽度会把面板推到屏幕外面。
      const container = handle.closest("[data-chat-root]") as HTMLElement | null;
      const containerWidth = container ? container.clientWidth : window.innerWidth;
      onWidthChange(clampPreviewWidth(startWidth + (startX - moveEvent.clientX), containerWidth));
    };
    const onEnd = () => {
      setResizing(false);
      handle.removeEventListener("pointermove", onMove);
      handle.removeEventListener("pointerup", onEnd);
      handle.removeEventListener("pointercancel", onEnd);
    };
    handle.addEventListener("pointermove", onMove);
    handle.addEventListener("pointerup", onEnd);
    handle.addEventListener("pointercancel", onEnd);
  }

  return (
    <aside
      data-testid="preview-panel"
      aria-hidden={!visible}
      className={`relative flex shrink-0 flex-col overflow-hidden ${widthClass} ${
        visible ? "border-l" : "border-l-0"
      }`}
      style={{ width: `${effectiveWidth}px` }}
    >
      {/* 分隔线：可左右拖拽调整宽度（双击回到默认宽度） */}
      <div
        data-testid="preview-resize-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label="拖动调整预览面板宽度"
        title="拖动调整宽度（双击恢复默认）"
        className="hover:bg-primary/30 active:bg-primary/40 absolute inset-y-0 -left-1 z-10 w-2 cursor-col-resize transition-colors"
        onPointerDown={startResize}
        onDoubleClick={() => onWidthChange(PREVIEW_PANEL_DEFAULT)}
      />

      {/* tab 行：左上角是清单入口，中间一个产物一个 tab（可逐个关闭），右边整体关闭 */}
      <div className="flex items-center gap-1 border-b px-2 py-1.5">
        {/* 视图切换：文件（产物）/ 浏览器（agent 实时画面） */}
        <div className="bg-muted flex shrink-0 items-center gap-0.5 rounded-md p-0.5">
          <button
            type="button"
            data-testid="preview-mode-files"
            title="文件（产物）"
            aria-pressed={!showBrowser}
            onClick={() => onModeChange?.("files")}
            className={`flex items-center gap-1 rounded px-1.5 py-1 text-xs transition-colors ${
              showBrowser ? "text-muted-foreground hover:text-foreground" : "bg-background shadow-sm"
            }`}
          >
            <FileText className="size-3.5" />
            <span className="hidden sm:inline">文件</span>
          </button>
          <button
            type="button"
            data-testid="preview-mode-browser"
            title="浏览器（agent 正在操作的页面）"
            aria-pressed={showBrowser}
            onClick={() => onModeChange?.("browser")}
            className={`relative flex items-center gap-1 rounded px-1.5 py-1 text-xs transition-colors ${
              showBrowser ? "bg-background shadow-sm" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            <Globe className="size-3.5" />
            <span className="hidden sm:inline">浏览器</span>
            {browserActive ? (
              <span className="absolute top-0.5 right-0.5 size-1.5 rounded-full bg-emerald-500" />
            ) : null}
          </button>
        </div>
        {!showBrowser && browserTrigger}
        <div className={`flex min-w-0 flex-1 items-center gap-1 overflow-x-auto ${showBrowser ? "invisible" : ""}`}>
          {tabs.map((item) => (
            <span
              key={item.id}
              data-testid="preview-tab"
              className={`flex max-w-[12rem] shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs ${
                item.id === active.id ? "bg-accent text-accent-foreground" : "text-muted-foreground"
              }`}
            >
              <button
                type="button"
                className="min-w-0 truncate"
                title={item.fileName}
                onClick={() => onSelect(item.id)}
              >
                {item.fileName}
              </button>
              <button
                type="button"
                title="关闭这个预览"
                data-testid="preview-tab-close"
                className="hover:bg-background/60 rounded p-0.5"
                onClick={() => onCloseTab(item.id)}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
        <Button
          variant="ghost"
          size="icon"
          className="size-7 shrink-0"
          title="关闭预览"
          data-testid="preview-panel-close"
          onClick={onClose}
        >
          <X className="size-4" />
        </Button>
      </div>

      {showBrowser ? (
        <div className="min-h-0 flex-1" data-testid="preview-browser-body">
          {browserContent}
        </div>
      ) : (
      /* 内容区：文件名/大小/下载一行，下面占满**剩余全高**（用户要求：预览窗口高度要够） */
      <div className="flex min-h-0 flex-1 flex-col gap-2 p-3">
        <div className="flex items-center gap-2">
          <span
            className="text-muted-foreground min-w-0 flex-1 truncate text-xs"
            title={active.fileName}
          >
            {active.fileName} · {formatSize(active.sizeBytes)}
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="size-7 shrink-0"
            title="下载"
            data-testid="preview-download"
            onClick={() => void downloadArtifact(active.id, active.fileName)}
          >
            <Download className="size-4" />
          </Button>
        </div>
        {/* 这一层才是滚动容器：HTML/PDF 的 iframe 用 h-full 正好填满它 */}
        <div className="min-h-0 flex-1 overflow-auto">
          {active ? <ArtifactPreviewBody item={active} /> : null}
        </div>
      </div>
      )}
    </aside>
  );
}
