import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  ExternalLink,
  Globe,
  Loader2,
  Monitor,
  Pause,
  Play,
  RefreshCw,
  ShieldCheck,
  X,
} from "lucide-react";
import {
  approvePairing,
  createControlSession,
  extensionPackageUrl,
  fetchExtensionConfig,
  fetchExtensionStatus,
  pauseControlSession,
  resumeControlSession,
  revokeControlSession,
  revokeDevice,
  type BrowserDevice,
  type BrowserExtensionConfig,
  type BrowserExtensionStatus,
} from "@/api/browserExtension";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * 本机浏览器连接面板（方案 5.2 的引导状态机）。
 *
 * <p>和旧的 {@code BrowserPreview} 的区别：**这里没有实时画面**。真实网页就在用户自己的标签页里，
 * 这里展示的是「装没装 / 连没连 / 授权了哪个网页 / 现在谁在控制」。
 *
 * <p>为什么不做"页面内嵌预览"：X-Frame-Options/CSP 挡住了绝大多数站点，而且把登录态页面塞进 iframe
 * 也说服不了安全评审。产品的取舍写在这里，避免以后有人再试一遍。
 */
type GuideState =
  | "CHECKING"
  | "NOT_DETECTED"
  | "PAIRED_OFFLINE"
  | "UPDATE_REQUIRED"
  | "READY_NO_TAB"
  | "TAB_AUTHORIZED"
  | "HUMAN_CONTROL"
  | "SUSPENDED";

export function BrowserExtensionPanel({
  conversationId,
  onClose,
}: {
  conversationId?: string | null;
  onClose?: () => void;
}) {
  const [config, setConfig] = useState<BrowserExtensionConfig | null>(null);
  const [status, setStatus] = useState<BrowserExtensionStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [showInstall, setShowInstall] = useState(false);
  const [pairCode, setPairCode] = useState("");
  const [pairMessage, setPairMessage] = useState<string | null>(null);
  const timer = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await fetchExtensionStatus(conversationId ?? undefined);
      setStatus(next);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "读取浏览器状态失败");
    }
  }, [conversationId]);

  useEffect(() => {
    fetchExtensionConfig().then(setConfig).catch(() => undefined);
    refresh();
    timer.current = window.setInterval(refresh, 3000);
    return () => {
      if (timer.current) window.clearInterval(timer.current);
    };
  }, [refresh]);

  const devices: BrowserDevice[] = status?.devices ?? [];
  const online = devices.filter((d) => d.online && d.status === "ACTIVE");
  const state = status?.state;
  const sessionId = status?.controlSessionId;

  const guide: GuideState = useMemo(() => {
    if (!status) return "CHECKING";
    if (state === "SUSPENDED") return "SUSPENDED";
    if (state === "HUMAN_CONTROL") return "HUMAN_CONTROL";
    if (state && status.tabHandle) return "TAB_AUTHORIZED";
    if (online.length > 0) return "READY_NO_TAB";
    if (devices.length > 0) return "PAIRED_OFFLINE";
    return "NOT_DETECTED";
  }, [status, state, online.length, devices.length]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败");
    } finally {
      setBusy(false);
    }
  };

  const startTask = () => {
    if (!conversationId) return;
    return run(() => createControlSession(conversationId, online[0]?.id));
  };

  const packageUrl = extensionPackageUrl(config);

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      <div className="flex shrink-0 items-center gap-2 border-b px-3 py-2">
        <Globe className="size-4 text-muted-foreground" />
        <span className="text-sm font-medium">浏览器</span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">
          本机扩展
        </span>
        <div className="ml-auto flex items-center gap-1">
          <Button variant="ghost" size="icon" className="size-7" onClick={() => refresh()} title="重新检测">
            <RefreshCw className="size-3.5" />
          </Button>
          {onClose ? (
            <Button variant="ghost" size="icon" className="size-7" onClick={onClose} title="关闭">
              <X className="size-3.5" />
            </Button>
          ) : null}
        </div>
      </div>

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        {error ? (
          <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
            {error}
          </div>
        ) : null}

        <StatusCard guide={guide} status={status} online={online} />

        {guide === "NOT_DETECTED" ? (
          <div className="space-y-2 rounded-lg border p-3">
            <div className="text-sm font-medium">第一步：安装扩展</div>
            <p className="text-xs text-muted-foreground">
              扩展负责在你授权的标签页里执行操作，网页端只负责显示状态和下发命令。
              安装器会把设备绑定到你的账号，令牌与网页登录完全分开。
            </p>
            <div className="flex flex-wrap gap-2">
              {config?.chromeStoreUrl ? (
                <Button size="sm" onClick={() => window.open(config.chromeStoreUrl!, "_blank")}>
                  <Download className="mr-1.5 size-3.5" />
                  安装 Chrome 扩展
                </Button>
              ) : null}
              {config?.edgeStoreUrl ? (
                <Button size="sm" variant="outline" onClick={() => window.open(config.edgeStoreUrl!, "_blank")}>
                  <Download className="mr-1.5 size-3.5" />
                  安装 Edge 扩展
                </Button>
              ) : null}
              {packageUrl ? (
                <Button size="sm" variant="outline" onClick={() => window.open(packageUrl, "_blank")}>
                  <Download className="mr-1.5 size-3.5" />
                  下载内测包
                </Button>
              ) : null}
              <Button size="sm" variant="ghost" onClick={() => setShowInstall((v) => !v)}>
                {showInstall ? "收起安装步骤" : "查看安装步骤"}
              </Button>
            </div>
            {showInstall ? (
              <ol className="ml-4 list-decimal space-y-1 text-xs text-muted-foreground">
                <li>
                  打开 <code className="rounded bg-muted px-1">chrome://extensions</code>（Edge 是{" "}
                  <code className="rounded bg-muted px-1">edge://extensions</code>），打开「开发者模式」。
                </li>
                <li>「加载已解压的扩展程序」，选择扩展目录（或解压后的内测包）。</li>
                <li>扩展会自动打开「连接 my-agent」，填平台地址后点「开始配对」。</li>
                <li>回到这里点「重新检测」。</li>
              </ol>
            ) : null}
            <p className="text-[11px] text-muted-foreground">
              说明：浏览器不允许网页读取「装了哪些扩展」。所以这里只能显示「有没有已连接的设备」；
              如果扩展装好了但没配对，同样会显示成未检测到。
            </p>
          </div>
        ) : null}

        {guide === "PAIRED_OFFLINE" ? (
          <div className="space-y-2 rounded-lg border p-3">
            <div className="text-sm font-medium">扩展离线</div>
            <p className="text-xs text-muted-foreground">
              设备已绑定到你的账号，但浏览器现在没有连上平台（电脑休眠、浏览器关闭或扩展被停用）。
              打开浏览器、确认扩展启用后会自动重连；如果一直不回来，重新配对即可。
            </p>
          </div>
        ) : null}

        {guide === "READY_NO_TAB" ? (
          <div className="space-y-2 rounded-lg border p-3">
            <div className="text-sm font-medium">第二步：授权要操作的网页</div>
            <p className="text-xs text-muted-foreground">
              请切到目标网页，点浏览器工具栏上的扩展图标，在侧边面板点「授权当前标签页」。
              一次只授权一个标签页，换网站要重新授权。
            </p>
            <p className="text-[11px] text-muted-foreground">
              也可以直接在对话里让 Agent 打开网页：它会请求你授权，你再在扩展里确认。
            </p>
          </div>
        ) : null}

        {guide === "TAB_AUTHORIZED" || guide === "HUMAN_CONTROL" || guide === "SUSPENDED" ? (
          <div className="space-y-2 rounded-lg border p-3">
            <div className="flex items-center gap-2 text-sm font-medium">
              <ShieldCheck className="size-4 text-emerald-500" />
              已授权的网页
            </div>
            <div className="text-xs">
              <div className="font-medium">{status?.title || "（无标题）"}</div>
              <div className="break-all text-muted-foreground">{status?.origin || "—"}</div>
            </div>
            <div className="flex flex-wrap gap-2 pt-1">
              {guide === "HUMAN_CONTROL" ? (
                <Button
                  size="sm"
                  disabled={busy || !sessionId}
                  onClick={() => run(() => resumeControlSession(sessionId!))}
                >
                  <Play className="mr-1.5 size-3.5" />
                  交还给 Agent
                </Button>
              ) : (
                <Button
                  size="sm"
                  disabled={busy || !sessionId}
                  onClick={() => run(() => pauseControlSession(sessionId!))}
                >
                  <Pause className="mr-1.5 size-3.5" />
                  我要自己操作
                </Button>
              )}
              <Button
                size="sm"
                variant="outline"
                disabled={busy || !sessionId}
                onClick={() => run(() => revokeControlSession(sessionId!))}
              >
                撤销授权
              </Button>
            </div>
            {status?.lastError ? (
              <p className="text-[11px] text-amber-600">{status.lastError}</p>
            ) : null}
          </div>
        ) : null}

        <div className="space-y-2 rounded-lg border p-3">
          <div className="flex items-center justify-between">
            <div className="text-sm font-medium">设备</div>
            <span className="text-[11px] text-muted-foreground">
              {devices.length} 台 · {online.length} 台在线
            </span>
          </div>
          {devices.length === 0 ? (
            <p className="text-xs text-muted-foreground">还没有绑定任何浏览器。</p>
          ) : (
            <ul className="space-y-1.5">
              {devices.map((device) => (
                <li key={device.id} className="flex items-center gap-2 text-xs">
                  <Monitor className="size-3.5 text-muted-foreground" />
                  <span className="font-medium">{device.displayName || device.browser || "浏览器"}</span>
                  <span
                    className={cn(
                      "rounded-full px-1.5 py-0.5 text-[10px]",
                      device.online
                        ? "bg-emerald-500/15 text-emerald-600"
                        : "bg-muted text-muted-foreground",
                    )}
                  >
                    {device.online ? "在线" : "离线"}
                  </span>
                  <span className="text-muted-foreground">v{device.extensionVersion || "?"}</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="ml-auto h-6 px-2 text-[11px] text-destructive"
                    onClick={() => run(() => revokeDevice(device.id))}
                  >
                    撤销
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {conversationId && devices.length > 0 && !status?.controlSessionId ? (
          <Button className="w-full" size="sm" disabled={busy} onClick={startTask}>
            为当前对话创建浏览器任务
          </Button>
        ) : null}

        <div className="space-y-1.5 rounded-lg border p-3">
          <div className="text-sm font-medium">配对码（在扩展里生成）</div>
          <p className="text-[11px] text-muted-foreground">
            通常是扩展自动打开配对页并带上来。如果页面丢了，可以在这里手动确认扩展显示的 8 位配对码。
          </p>
          <div className="flex gap-2">
            <input
              value={pairCode}
              onChange={(event) => setPairCode(event.target.value.toUpperCase())}
              placeholder="例如 7QK2M9XA"
              className="h-8 flex-1 rounded-md border bg-background px-2 font-mono text-xs"
              maxLength={8}
            />
            <Button
              size="sm"
              disabled={busy || pairCode.trim().length < 6}
              onClick={() =>
                run(async () => {
                  await approvePairing(pairCode.trim());
                  setPairMessage("已批准，回到浏览器扩展即可完成连接");
                  setPairCode("");
                })
              }
            >
              确认
            </Button>
          </div>
          {pairMessage ? <p className="text-[11px] text-emerald-600">{pairMessage}</p> : null}
        </div>

        <p className="text-[11px] leading-relaxed text-muted-foreground">
          安全边界：只操作你显式授权的单个标签页；页面内容视为不可信，网页里的文字不能扩大权限；
          你随时可以暂停或撤销；已提交给网站的请求无法撤回。使用扩展时浏览器会显示「正在调试」提示，
          点取消等于撤销控制。
        </p>
        {config?.chromeStoreUrl ? (
          <a
            className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:underline"
            href="https://developer.chrome.com/docs/extensions/reference/api/debugger"
            target="_blank"
            rel="noreferrer"
          >
            了解扩展为什么需要调试权限
            <ExternalLink className="size-3" />
          </a>
        ) : null}
      </div>
    </div>
  );
}

function StatusCard({
  guide,
  status,
  online,
}: {
  guide: GuideState;
  status: BrowserExtensionStatus | null;
  online: BrowserDevice[];
}) {
  const text: Record<GuideState, { title: string; hint: string; tone: string }> = {
    CHECKING: { title: "正在检查浏览器连接", hint: "稍等…", tone: "text-muted-foreground" },
    NOT_DETECTED: {
      title: "还没有连接你的浏览器",
      hint: "安装扩展后，Agent 可以在你授权的网页里操作，继续使用该浏览器已有的登录状态。",
      tone: "text-amber-600",
    },
    PAIRED_OFFLINE: {
      title: "扩展当前离线",
      hint: "设备已绑定，但浏览器没有连上平台。",
      tone: "text-amber-600",
    },
    UPDATE_REQUIRED: {
      title: "扩展版本过旧",
      hint: "请更新扩展后重试。",
      tone: "text-destructive",
    },
    READY_NO_TAB: {
      title: "扩展已连接",
      hint: "还没有授权网页。请切到目标页面，点扩展图标授权。",
      tone: "text-emerald-600",
    },
    TAB_AUTHORIZED: {
      title: "已连接到授权网页",
      hint: "Agent 可以在你交还控制权后操作这个页面。",
      tone: "text-emerald-600",
    },
    HUMAN_CONTROL: {
      title: "你正在操作",
      hint: "Agent 已暂停。点「交还给 Agent」它才会继续。",
      tone: "text-amber-600",
    },
    SUSPENDED: {
      title: "浏览器控制已暂停",
      hint: status?.lastError || "连接断开或调试会话结束，需要重新授权。",
      tone: "text-amber-600",
    },
  };
  const item = text[guide];
  const Icon =
    guide === "CHECKING"
      ? Loader2
      : guide === "NOT_DETECTED" || guide === "PAIRED_OFFLINE" || guide === "UPDATE_REQUIRED"
        ? AlertTriangle
        : CheckCircle2;
  return (
    <div className="rounded-lg border p-3">
      <div className="flex items-center gap-2">
        <Icon
          className={cn(
            "size-4",
            item.tone,
            guide === "CHECKING" ? "animate-spin" : "",
          )}
        />
        <span className="text-sm font-medium">{item.title}</span>
        {online.length > 0 ? (
          <span className="ml-auto text-[11px] text-muted-foreground">
            在线设备：{online[0].displayName || online[0].browser || online[0].id.slice(0, 8)}
          </span>
        ) : null}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{item.hint}</p>
    </div>
  );
}
