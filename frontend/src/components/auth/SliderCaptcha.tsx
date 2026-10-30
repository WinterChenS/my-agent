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
 * <p>交互：
 * <ul>
 *   <li>拖动下面的滑块，上面的拼图块跟着横移，滑到缺口里松手
 *   <li>松手即校验（前后端联动）：对了变绿、锁住，把 ticket 交给登录表单；错了变红并自动换一张
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
  const [error, setError] = useState<string | null>(null);
  const trackRef = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);
  // 拖动/松手发生在 window 上的监听里，拿不到最新 state，用 ref 同步一份
  const progressRef = useRef(0);

  const load = useCallback(async () => {
    setStatus("idle");
    setProgress(0);
    progressRef.current = 0;
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
      } catch (err) {
        setStatus("fail");
        setError(err instanceof Error ? err.message : "验证失败");
        onVerified(null);
        // 失败就换一张：题目已作废（或机会-1），留着没意义
        window.setTimeout(() => void load(), 700);
      }
    },
    [challenge, load, onVerified],
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
    <div
      data-testid="slider-captcha"
      className={cn("space-y-2 select-none", className)}
      aria-label="滑动验证"
    >
      <div
        data-testid="captcha-canvas"
        className="bg-muted relative w-full overflow-hidden rounded-lg border"
        style={{ aspectRatio: challenge ? `${challenge.width} / ${challenge.height}` : "2 / 1" }}
      >
        {challenge?.background ? (
          <img
            src={challenge.background}
            alt="验证背景"
            draggable={false}
            className="block h-full w-full object-fill"
          />
        ) : null}
        {challenge?.piece ? (
          <img
            data-testid="captcha-piece"
            src={challenge.piece}
            alt="拼图块"
            draggable={false}
            className="absolute drop-shadow-md"
            style={{
              width: `${(challenge.pieceSize / challenge.width) * 100}%`,
              top: `${((challenge.pieceY ?? 0) / challenge.height) * 100}%`,
              left: `${percent}%`,
            }}
          />
        ) : null}
        {status === "ok" ? (
          <div className="absolute inset-0 flex items-center justify-center bg-emerald-500/15">
            <Check className="size-8 text-emerald-600" />
          </div>
        ) : null}
        {!challenge && !error ? (
          <div className="text-muted-foreground absolute inset-0 grid place-items-center text-xs">
            验证图加载中…
          </div>
        ) : null}
      </div>

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
            aria-label="拖动滑块完成拼图"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            onMouseDown={() => {
              if (locked) return;
              dragging.current = true;
              setStatus("dragging");
            }}
            onTouchStart={() => {
              if (locked) return;
              dragging.current = true;
              setStatus("dragging");
            }}
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
          "text-xs",
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
