import { useCallback, useEffect, useRef, useState } from "react";
import { Check, RotateCw, X } from "lucide-react";
import {
  fetchCaptcha,
  verifyCaptcha,
  type CaptchaChallenge,
  type TrackPoint,
} from "@/api/auth";
import { captchaSessionKey } from "@/lib/captchaSession";
import { cn } from "@/lib/utils";

/**
 * 登录验证码（滑块拼图 / 无障碍文字题）。
 *
 * <p><b>前端只负责"采集输入"，不负责"下结论"</b>：这里没有任何"验证通过"的本地判定，
 * 唯一的结论来自 `POST /api/auth/captcha/verify`（服务端比对答案并签发一次性 ticket）。
 * 改这里的变量、跳过这里的判断，都拿不到 ticket —— 登录接口会拒绝。
 *
 * <p>交互（产品要求）：平时只占一条滑轨；按下滑块拼图面板才浮出；松手即校验；
 * 通过变绿短暂展示后自动收起；失败变红并换一张新题。
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
  const [panelOpen, setPanelOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<"slider" | "text">("slider");
  const [textAnswer, setTextAnswer] = useState("");
  const trackRef = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);
  const progressRef = useRef(0);
  const closeTimer = useRef<number | null>(null);
  /** 轨迹采样（可伪造，所以只作辅助信号） */
  const track = useRef<TrackPoint[]>([]);
  const dragStartedAt = useRef(0);

  const sessionKey = captchaSessionKey();

  const hideSoon = useCallback((delay: number) => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
    }
    closeTimer.current = window.setTimeout(() => setPanelOpen(false), delay);
  }, []);

  const load = useCallback(
    async (nextMode: "slider" | "text" = mode) => {
      setStatus("idle");
      setProgress(0);
      progressRef.current = 0;
      track.current = [];
      setPanelOpen(false);
      setTextAnswer("");
      setError(null);
      try {
        const next = await fetchCaptcha(sessionKey, nextMode);
        setChallenge(next);
        setMode(next.mode === "TEXT" ? "text" : "slider");
        if (!next.required) {
          onVerified(null);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "验证码加载失败");
      }
    },
    [mode, onVerified, sessionKey],
  );

  useEffect(() => {
    void load("slider");
    return () => {
      if (closeTimer.current !== null) {
        window.clearTimeout(closeTimer.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submit = useCallback(
    async (payload: { x?: number; answer?: string }) => {
      if (!challenge?.id) return;
      setStatus("verifying");
      try {
        const { ticket } = await verifyCaptcha({
          id: challenge.id,
          sessionKey,
          ...payload,
          track: track.current,
        });
        setStatus("ok");
        setError(null);
        onVerified(ticket);
        hideSoon(900);
      } catch (err) {
        setStatus("fail");
        setError(err instanceof Error ? err.message : "验证失败");
        onVerified(null);
        hideSoon(900);
        window.setTimeout(() => void load(mode), 1000);
      }
    },
    [challenge, hideSoon, load, mode, onVerified, sessionKey],
  );

  const updateFromClientX = useCallback((clientX: number) => {
    const trackEl = trackRef.current;
    if (!trackEl) return;
    const rect = trackEl.getBoundingClientRect();
    const width = rect.width || 1;
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / width));
    progressRef.current = ratio;
    setProgress(ratio);
    // 采样：位置比例 + 相对时间；超过 200 点就抽稀（避免请求体过大）
    const point: TrackPoint = [Number(ratio.toFixed(4)), Math.round(performance.now() - dragStartedAt.current)];
    if (track.current.length < 200) {
      track.current.push(point);
    }
    return ratio;
  }, []);

  const startDrag = useCallback(() => {
    if (status === "ok" || status === "verifying") return;
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
    dragging.current = true;
    dragStartedAt.current = performance.now();
    track.current = [[Number(progressRef.current.toFixed(4)), 0]];
    setPanelOpen(true);
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
      void submit({ x: Math.round(progressRef.current * (challenge?.width ?? 320)) });
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [challenge?.width, submit, updateFromClientX]);

  if (challenge && !challenge.required) {
    return null;
  }

  const locked = status === "ok" || status === "verifying";
  const percent = Math.round(progress * 100);
  const isText = mode === "text";

  return (
    <div
      data-testid="slider-captcha"
      className={cn("relative select-none", className)}
      aria-label="验证码"
    >
      {!isText && challenge ? (
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
              // 拼图图片比缺口多了 pad 的边距，这里要减掉才对齐
              left: `calc(${percent}% - ${((challenge.piecePad ?? 0) / challenge.width) * 100}%)`,
              top: `calc(${((challenge.pieceY ?? 0) / challenge.height) * 100}% - ${((challenge.piecePad ?? 0) / challenge.height) * 100}%)`,
            }}
          />
          {status === "ok" ? (
            <div className="absolute inset-0 flex items-center justify-center bg-emerald-500/20">
              <Check className="size-8 text-emerald-600" />
            </div>
          ) : null}
        </div>
      ) : null}

      {isText ? (
        <div className="flex items-center gap-2">
          <span data-testid="captcha-question" className="text-sm font-medium">
            {challenge?.question ?? "题目加载中…"}
          </span>
          <input
            data-testid="captcha-answer"
            inputMode="numeric"
            value={textAnswer}
            onChange={(event) => setTextAnswer(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void submit({ answer: textAnswer });
              }
            }}
            className="border-input h-9 w-20 rounded-md border px-2 text-sm"
            aria-label="请输入计算结果"
          />
          <button
            type="button"
            disabled={status === "verifying" || !textAnswer.trim()}
            onClick={() => void submit({ answer: textAnswer })}
            className="bg-primary text-primary-foreground h-9 rounded-md px-3 text-sm disabled:opacity-50"
          >
            验证
          </button>
        </div>
      ) : (
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
                void submit({ x: Math.round(progressRef.current * (challenge?.width ?? 320)) });
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
            onClick={() => void load(mode)}
            className="text-muted-foreground hover:text-foreground rounded-full p-1"
          >
            <RotateCw className="size-4" />
          </button>
        </div>
      )}

      <div className="mt-1 flex items-center justify-between gap-2">
        <p
          className={cn(
            "text-xs",
            status === "ok"
              ? "text-emerald-600"
              : error
                ? "text-destructive"
                : "text-muted-foreground",
          )}
        >
          {status === "ok"
            ? "验证通过"
            : status === "verifying"
              ? "验证中…"
              : (error ?? (isText ? "请输入计算结果" : "按住滑块拖到缺口位置"))}
        </p>
        {/* 无障碍替代方案：看不清拼图/只用键盘操作的场景可以切文字题 */}
        <button
          type="button"
          data-testid="captcha-switch-mode"
          onClick={() => void load(isText ? "slider" : "text")}
          className="text-muted-foreground hover:text-foreground shrink-0 text-xs underline underline-offset-2"
        >
          {isText ? "改用拼图验证" : "看不清？换成算术题"}
        </button>
      </div>
    </div>
  );
}
