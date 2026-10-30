import { useCallback, useEffect, useRef, useState } from "react";
import { Check, RotateCw, X } from "lucide-react";
import { fetchCaptcha, verifyCaptcha, type CaptchaChallenge } from "@/api/auth";
import { cn } from "@/lib/utils";

/**
 * 登录滑动验证（拼图滑块）。
 *
 * <p>为什么不用现成的 npm 组件：内网是 http 非安全上下文，canvas 指纹/加密类库全都不可用；
 * 而且多数开源滑块把答案算在前端（等于没防护）。这里**答案只在后端**，前端只负责把拖到的 x 发过去。
 *
 * <p>交互（按产品要求）：
 * <ul>
 *   <li>平时只占一条滑轨的宽度 —— 拼图**不占位**，不会把密码框和登录按钮挤开
 *   <li>按下滑块的瞬间，拼图面板**浮**在滑轨上方出现；拖动时拼图块跟着横移
 *   <li>松手即校验（前后端联动）：通过变绿、失败变红，短暂显示结果后面板自动收起
 * </ul>
 */
export function SliderCaptcha({
  onVerified,
  className,
}: {
  /** 验证通过给 ticket；失败/换图给 null */
  onVerified: (ticket: string | null) => void;
  className?: string;
}) {
  const [challenge, setChallenge] = useState<CaptchaChallenge | null>(null);
  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState<"idle" | "dragging" | "verifying" | "ok" | "fail">("idle");
  /** 拼图面板是否浮出（只在拖动/显示结果时出现） */
  const [panelOpen, setPanelOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trackRef = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);
  // 拖动/松手发生在 window 上的监听里，拿不到最新 state，用 ref 同步一份
  const progressRef = useRef(0);
  const closeTimer = useRef<number | null>(null);

  const hideSoon = useCallback((delay: number) => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
    }
    closeTimer.current = window.setTimeout(() => setPanelOpen(false), delay);
  }, []);

  const load = useCallback(async () => {
    setStatus("idle");
    setProgress(0);
    progressRef.current = 0;
    setPanelOpen(false);
    setError(null);
    try {
      const next = await fetchCaptcha();
      setChallenge(next);
      if (!next.required) {
        onVerified(null);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "验证码加载失败");
    }
  }, [onVerified]);

  useEffect(() => {
    void load();
    return () => {
      if (closeTimer.current !== null) {
        window.clearTimeout(closeTimer.current);
      }
    };
  }, [load]);

  const submit = useCallback(
    async (ratio: number) => {
      if (!challenge?.id) return;
      setStatus("verifying");
      try {
        const x = Math.round(ratio * challenge.width);
        const { ticket } = await verifyCaptcha(challenge.id, x);
        setStatus("ok");
        setError(null);
        onVerified(ticket);
        hideSoon(900);
      } catch (err) {
        setStatus("fail");
        setError(err instanceof Error ? err.message : "验证失败");
        onVerified(null);
        hideSoon(900);
        // 失败就换一张：题目已作废（或机会-1），留着没意义
        window.setTimeout(() => void load(), 1000);
      }
    },
    [challenge, hideSoon, load, onVerified],
  );

  const updateFromClientX = useCallback((clientX: number) => {
    const track = trackRef.current;
    if (!track) return;
    const rect = track.getBoundingClientRect();
    const width = rect.width || 1;
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / width));
    progressRef.current = ratio;
    setProgress(ratio);
    return ratio;
  }, []);

  const startDrag = useCallback(() => {
    if (status === "ok" || status === "verifying") return;
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
    dragging.current = true;
    setPanelOpen(true); // 按下才浮出拼图
    setStatus("dragging");
  }, [status]);

  useEffect(() => {
    const onMove = (event: MouseEvent) => {
      if (!dragging.current) return;
      updateFromClientX(event.clientX);
    };
    const onUp = () => {
      if (!dragging.current) return;
      dragging.current = false;
      void submit(progressRef.current);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [submit, updateFromClientX]);

  // 服务端关了验证：整块不渲染，登录也就不用带 ticket
  if (challenge && !challenge.required) {
    return null;
  }

  const locked = status === "ok" || status === "verifying";
  const percent = Math.round(progress * 100);

  return (
    // 外层只占一条滑轨的高度：拼图面板是 absolute，不参与排版
    <div
      data-testid="slider-captcha"
      className={cn("relative select-none", className)}
      aria-label="滑动验证"
    >
      {challenge ? (
        <div
          data-testid="captcha-panel"
          data-open={panelOpen ? "true" : "false"}
          className={cn(
            "pointer-events-none absolute inset-x-0 bottom-full z-20 mb-1.5 overflow-hidden rounded-lg border bg-card shadow-lg",
            "transition-all duration-150",
            panelOpen
              ? "translate-y-0 scale-100 opacity-100"
              : "invisible translate-y-1 scale-95 opacity-0",
          )}
          style={{ aspectRatio: `${challenge.width} / ${challenge.height}` }}
        >
          <img
            src={challenge.background ?? ""}
            alt="验证背景"
            draggable={false}
            className="absolute inset-0 h-full w-full object-fill"
          />
          <img
            data-testid="captcha-piece"
            src={challenge.piece ?? ""}
            alt="拼图块"
            draggable={false}
            className="absolute drop-shadow-md"
            style={{
              width: `${(challenge.pieceSize / challenge.width) * 100}%`,
              top: `${((challenge.pieceY ?? 0) / challenge.height) * 100}%`,
              left: `${percent}%`,
            }}
          />
          {status === "ok" ? (
            <div className="absolute inset-0 flex items-center justify-center bg-emerald-500/20">
              <Check className="size-8 text-emerald-600" />
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="flex items-center gap-2">
        <div
          ref={trackRef}
          data-testid="captcha-track"
          className={cn(
            "relative h-9 flex-1 rounded-full border",
            status === "ok" ? "bg-emerald-500/15" : "bg-muted",
          )}
        >
          <div
            className={cn(
              "absolute inset-y-0 left-0 rounded-full transition-[width] duration-75",
              status === "ok" ? "bg-emerald-500/25" : "bg-primary/15",
              status === "fail" && "bg-destructive/20",
            )}
            style={{ width: `${percent}%` }}
          />
          <div
            data-testid="captcha-handle"
            role="slider"
            tabIndex={0}
            aria-label="按住拖动完成拼图"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            onMouseDown={startDrag}
            onTouchStart={startDrag}
            onTouchMove={(event) => {
              if (!dragging.current) return;
              const touch = event.touches[0];
              if (touch) updateFromClientX(touch.clientX);
            }}
            onTouchEnd={() => {
              if (!dragging.current) return;
              dragging.current = false;
              void submit(progressRef.current);
            }}
            className={cn(
              "absolute inset-y-0.5 left-0 flex w-11 cursor-grab items-center justify-center rounded-full border bg-card shadow transition-[left] duration-75",
              status === "ok" && "border-emerald-500 text-emerald-600",
              status === "fail" && "border-destructive text-destructive",
              locked && "cursor-default",
            )}
            style={{ left: `calc(${percent}% - ${(percent / 100) * 44}px)` }}
          >
            {status === "ok" ? (
              <Check className="size-4" />
            ) : status === "fail" ? (
              <X className="size-4" />
            ) : (
              <span className="text-muted-foreground text-xs">→</span>
            )}
          </div>
        </div>
        <button
          type="button"
          title="换一张"
          onClick={() => void load()}
          className="text-muted-foreground hover:text-foreground rounded-full p-1"
        >
          <RotateCw className="size-4" />
        </button>
      </div>

      <p
        className={cn(
          "mt-1 text-xs",
          status === "ok" ? "text-emerald-600" : error ? "text-destructive" : "text-muted-foreground",
        )}
      >
        {status === "ok"
          ? "验证通过"
          : status === "verifying"
            ? "验证中…"
            : (error ?? "按住滑块拖到缺口位置")}
      </p>
    </div>
  );
}
