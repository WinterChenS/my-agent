import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Expand,
  Loader2,
  MousePointerClick,
  Plus,
  RotateCw,
  X,
} from "lucide-react";
import {
  closeBrowser,
  fetchBrowserStatus,
  navigateBrowser,
  openBrowserFrames,
  sendBrowserInput,
  setBrowserViewport,
  takeoverBrowser,
  type BrowserStatus,
} from "@/api/browser";
import { cn } from "@/lib/utils";

/**
 * 产物面板里的**浏览器视图**（仿 Codex 的右侧浏览器）。
 *
 * <p>三条关键设计：
 * <ol>
 *   <li><b>铺满、无黑边</b>：canvas 的内部像素尺寸 = 面板可用尺寸，同时用
 *       {@code Emulation.setDeviceMetricsOverride} 把**真实视口**改成同样尺寸 —— 页面会像真浏览器
 *       窗口那样 reflow，而不是把一张固定分辨率截图缩放留黑边。
 *   <li><b>拖拽面板宽度即改页面宽度</b>：ResizeObserver 监听尺寸变化（防抖 250ms）后同步视口，
 *       于是"挤压/拖放"真的会改变页面布局（响应式页面会跟着变）。
 *   <li><b>默认只看不动</b>：点「我要操作」才把点击/键盘回传给服务端；此时服务端会把 agent 的操作挡住。
 * </ol>
 */
export function BrowserPreview({ onClose }: { onClose: () => void }) {
  const [status, setStatus] = useState<BrowserStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [takingOver, setTakingOver] = useState(false);
  const [addr, setAddr] = useState("");
  const [loading, setLoading] = useState(true);
  const [hasFrame, setHasFrame] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const lastViewport = useRef({ width: 0, height: 0 });
  /** 最后一帧解码后的图：改 canvas 尺寸会清空画布，必须立刻用这张重绘，否则会白屏 */
  const lastImage = useRef<HTMLImageElement | null>(null);
  const viewportTimer = useRef<number | null>(null);

  // 1) 状态轮询（URL / 标题 / 是否还活着 / 是否在被用户接管）
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const next = await fetchBrowserStatus();
        if (!alive) return;
        setStatus(next);
        setLoading(false);
        if (next.url && document.activeElement?.id !== "browser-addr") {
          setAddr(next.url);
        }
        if (next.userControlled !== undefined) {
          setTakingOver(Boolean(next.userControlled) && Boolean(next.allowUserInput));
        }
      } catch (err) {
        if (alive) {
          setLoading(false);
          setError(err instanceof Error ? err.message : "状态获取失败");
        }
      }
    };
    void load();
    const timer = window.setInterval(load, 3000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  // 2) 画面订阅（SSE 帧流）
  useEffect(() => {
    if (!status?.active) {
      return;
    }
    const stop = openBrowserFrames(
      (frame) => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const image = new Image();
        image.onload = () => {
          const ctx = canvas.getContext("2d");
          if (!ctx) return;
          // 帧尺寸应该等于视口尺寸；万一对不上就拉伸填满（永远不留黑边）
          if (canvas.width !== frame.width || canvas.height !== frame.height) {
            canvas.width = frame.width;
            canvas.height = frame.height;
          }
          ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
          lastImage.current = image;
          setHasFrame(true);
        };
        image.src = `data:image/jpeg;base64,${frame.image}`;
      },
      (message) => setError(message),
    );
    return stop;
  }, [status?.active]);

  /**
   * 3) 铺满 + 同步真实视口。
   *
   * <p>面板被拖宽/拖窄 → 容器尺寸变化 → 防抖后把**真实视口**改成同样尺寸，
   * 页面重新排版，新帧正好铺满，没有上下黑边。
   */
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage || !status?.active) {
      return;
    }
    const sync = () => {
      const rect = stage.getBoundingClientRect();
      const width = Math.max(320, Math.round(rect.width));
      const height = Math.max(240, Math.round(rect.height));
      const canvas = canvasRef.current;
      if (canvas) {
        // canvas 内部像素 = 显示尺寸（1:1，不做缩放，画面才清晰且无黑边）
        const changed = canvas.width !== width || canvas.height !== height;
        if (canvas.width !== width) canvas.width = width;
        if (canvas.height !== height) canvas.height = height;
        // 改尺寸会清空画布 → 立刻把最后一帧重绘上去（否则拖拽/缩放后面板会变成白板）
        if (changed && lastImage.current) {
          canvas.getContext("2d")?.drawImage(lastImage.current, 0, 0, canvas.width, canvas.height);
        }
      }
      if (lastViewport.current.width === width && lastViewport.current.height === height) {
        return;
      }
      lastViewport.current = { width, height };
      if (viewportTimer.current !== null) {
        window.clearTimeout(viewportTimer.current);
      }
      viewportTimer.current = window.setTimeout(() => {
        void setBrowserViewport(width, height).catch(() => {
          // 视口同步失败不打扰用户（下一帧还会再试）
        });
      }, 250);
    };
    sync();
    if (typeof ResizeObserver === "undefined") {
      // 老浏览器/测试环境没有 ResizeObserver：至少按当前尺寸同步一次
      return;
    }
    const observer = new ResizeObserver(sync);
    observer.observe(stage);
    return () => {
      observer.disconnect();
      if (viewportTimer.current !== null) {
        window.clearTimeout(viewportTimer.current);
      }
    };
  }, [status?.active]);

  const toViewport = (event: { clientX: number; clientY: number }) => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    return {
      x: Math.round(((event.clientX - rect.left) / rect.width) * canvas.width),
      y: Math.round(((event.clientY - rect.top) / rect.height) * canvas.height),
    };
  };

  /**
   * 点击画面：**默认就能点**（不再要求先点「我要操作」）。
   *
   * <p>第一次点击会自动把控制权判给用户（服务端收到 input 也会这样标），
   * 于是 Agent 的下一步操作会让路 60 秒 —— 用户反馈"我来操作也没反应"就是之前那段门槛造成的。
   */
  const onClick = useCallback(
    async (event: React.MouseEvent<HTMLCanvasElement>) => {
      const { x, y } = toViewport(event);
      try {
        if (!takingOver) {
          setTakingOver(true);
          await takeoverBrowser(true).catch(() => undefined);
        }
        await sendBrowserInput({ type: "click", x, y });
      } catch (err) {
        setError(err instanceof Error ? err.message : "操作失败");
      }
    },
    [takingOver],
  );

  const onWheel = useCallback(
    async (event: React.WheelEvent<HTMLCanvasElement>) => {
      event.preventDefault();
      const { x, y } = toViewport(event);
      try {
        await sendBrowserInput({ type: "scroll", x, y, deltaY: Math.round(event.deltaY) });
      } catch {
        /* 忽略 */
      }
    },
    [takingOver],
  );

  const onKeyDown = useCallback(
    async (event: React.KeyboardEvent<HTMLCanvasElement>) => {
      if (!takingOver) return; // 键盘只有主动接管后才转发（避免误触抢走 Agent 的输入）
      if (event.key.length === 1) {
        await sendBrowserInput({ type: "text", text: event.key });
      } else if (["Enter", "Tab", "Backspace", "Escape", "ArrowDown", "ArrowUp"].includes(event.key)) {
        await sendBrowserInput({ type: "key", key: event.key });
      }
    },
    [takingOver],
  );

  const toggleTakeover = async () => {
    const next = !takingOver;
    setTakingOver(next);
    try {
      await takeoverBrowser(next);
      if (next) canvasRef.current?.focus();
    } catch (err) {
      setTakingOver(!next);
      setError(err instanceof Error ? err.message : "切换接管失败");
    }
  };

  const go = async (url: string) => {
    const target = url.trim();
    if (!target) return;
    const withScheme = /^https?:\/\//i.test(target) ? target : `https://${target}`;
    try {
      setError(null);
      await navigateBrowser(withScheme);
      setHasFrame(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "打开失败");
    }
  };

  const shuttingDown = async () => {
    try {
      await closeBrowser();
    } finally {
      onClose();
    }
  };

  if (status && !status.active) {
    return (
      <div className="text-muted-foreground flex h-full flex-col items-center justify-center gap-3 p-8 text-center text-sm">
        <MousePointerClick className="size-8 opacity-40" />
        <p>
          还没有打开浏览器。
          <br />
          Agent 调用 <code className="bg-muted rounded px-1">browser_open</code> 打开页面后，
          这里会自动显示实时画面；你也可以直接在上面的地址栏输入网址。
        </p>
      </div>
    );
  }

  const host = (() => {
    try {
      return new URL(addr).host;
    } catch {
      return addr || "新标签页";
    }
  })();

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      {/* ① 标签条（仿浏览器：一个标签 + 新建） */}
      <div className="flex shrink-0 items-end gap-1 border-b px-2 pt-1.5">
        <div className="bg-muted flex min-w-0 max-w-[14rem] items-center gap-1.5 rounded-t-md px-2 py-1 text-xs">
          <span className="bg-emerald-500/80 inline-block size-2 shrink-0 rounded-full" />
          <span className="min-w-0 truncate" title={status?.title || host}>
            {status?.title || host}
          </span>
          <button
            type="button"
            title="关闭浏览器"
            onClick={() => void shuttingDown()}
            className="hover:bg-background/70 ml-0.5 rounded p-0.5"
          >
            <X className="size-3" />
          </button>
        </div>
        <button
          type="button"
          title="新标签（先关闭当前会话）"
          onClick={() => void shuttingDown()}
          className="text-muted-foreground hover:text-foreground mb-1 rounded p-1"
        >
          <Plus className="size-3.5" />
        </button>
      </div>

      {/* ② 工具行：前进后退/刷新 + 地址栏 + 接管 + 全宽 */}
      <div className="flex shrink-0 items-center gap-1 border-b px-2 py-1.5">
        <button
          type="button"
          title="后退（重新打开上一个地址）"
          className="text-muted-foreground hover:text-foreground rounded p-1 disabled:opacity-40"
          onClick={() => void go(addr)}
        >
          <ArrowLeft className="size-3.5" />
        </button>
        <button
          type="button"
          title="前进"
          className="text-muted-foreground hover:text-foreground rounded p-1 disabled:opacity-40"
          onClick={() => void go(addr)}
        >
          <ArrowRight className="size-3.5" />
        </button>
        <button
          type="button"
          title="刷新画面"
          className="text-muted-foreground hover:text-foreground rounded p-1"
          onClick={() => void go(addr)}
        >
          <RotateCw className="size-3.5" />
        </button>
        <div className="relative min-w-0 flex-1">
          <input
            id="browser-addr"
            value={addr}
            onChange={(event) => setAddr(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") void go(addr);
            }}
            placeholder="输入网址后回车"
            className="border-input bg-muted/40 h-7 w-full rounded-full border px-3 text-center text-xs focus:text-left"
          />
        </div>
        <button
          type="button"
          title={takingOver ? "交还控制权给 Agent" : "我要自己操作页面"}
          onClick={() => void toggleTakeover()}
          className={cn(
            "shrink-0 rounded-full px-2.5 py-1 text-xs transition-colors",
            takingOver
              ? "bg-amber-500 text-white"
              : "bg-primary text-primary-foreground hover:opacity-90",
          )}
        >
          {takingOver ? "操作中 · 交还" : "我要操作"}
        </button>
        <button
          type="button"
          title="铺满面板（按面板尺寸重排页面）"
          className="text-muted-foreground hover:text-foreground shrink-0 rounded p-1"
          onClick={() => {
            lastViewport.current = { width: 0, height: 0 };
            const stage = stageRef.current;
            if (stage) {
              const rect = stage.getBoundingClientRect();
              void setBrowserViewport(Math.round(rect.width), Math.round(rect.height));
            }
          }}
        >
          <Expand className="size-3.5" />
        </button>
      </div>

      {error ? <p className="text-destructive shrink-0 px-2 py-1 text-xs">{error}</p> : null}

      {/* ③ 画面：1:1 铺满（canvas 像素 = 面板尺寸；视口同步改了，所以没有黑边） */}
      <div ref={stageRef} className="relative min-h-0 flex-1 overflow-hidden bg-white">
        <canvas
          ref={canvasRef}
          data-testid="browser-canvas"
          tabIndex={0}
          onClick={onClick}
          onWheel={onWheel}
          onKeyDown={onKeyDown}
          className={cn("absolute inset-0 block h-full w-full cursor-crosshair")}
        />
        {!hasFrame ? (
          <div className="text-muted-foreground absolute inset-0 grid place-items-center gap-2 text-xs">
            <span className="flex items-center gap-2">
              {loading ? <Loader2 className="size-3.5 animate-spin" /> : null}
              等待画面…
            </span>
          </div>
        ) : null}
        {takingOver ? (
          <div className="pointer-events-none absolute top-2 left-2 rounded bg-amber-500/90 px-2 py-0.5 text-[11px] font-medium text-white">
            你正在操作（Agent 暂停，60 秒无操作自动交还）
          </div>
        ) : null}
      </div>
    </div>
  );
}
