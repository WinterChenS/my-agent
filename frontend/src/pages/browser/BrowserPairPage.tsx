import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { CheckCircle2, Loader2, Monitor, ShieldAlert } from "lucide-react";
import {
  approvePairing,
  fetchPairingPreview,
  type PairingPreview,
} from "@/api/browserExtension";
import { Button } from "@/components/ui/button";

/**
 * 浏览器扩展配对确认页（`/browser/pair?code=XXXXXXXX`）。
 *
 * <p>由扩展自己打开（带 userCode）。用户在**已登录的网页**里确认，确认后扩展用
 * deviceCode + PKCE verifier 去换设备凭证。网页端不接触扩展的 verifier，也不下发明文令牌。
 */
export function BrowserPairPage() {
  const [params] = useSearchParams();
  const code = (params.get("code") || "").trim().toUpperCase();
  const [preview, setPreview] = useState<PairingPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [approved, setApproved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!code) {
      setError("配对链接缺少配对码，请回到浏览器扩展重新开始配对。");
      return;
    }
    fetchPairingPreview(code)
      .then(setPreview)
      .catch((err) => setError(err instanceof Error ? err.message : "配对码无效或已过期"));
  }, [code]);

  const approve = async () => {
    setBusy(true);
    setError(null);
    try {
      await approvePairing(code);
      setApproved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "批准失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-4 p-6">
      <div className="flex items-center gap-2">
        <Monitor className="size-5 text-muted-foreground" />
        <h1 className="text-lg font-semibold">连接浏览器扩展</h1>
      </div>

      {error ? (
        <div className="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <ShieldAlert className="mt-0.5 size-4" />
          <span>{error}</span>
        </div>
      ) : null}

      {approved ? (
        <div className="space-y-3 rounded-lg border p-4">
          <div className="flex items-center gap-2 text-sm font-medium text-emerald-600">
            <CheckCircle2 className="size-4" />
            已批准
          </div>
          <p className="text-sm text-muted-foreground">
            回到浏览器里的扩展页面，它会自动完成连接。之后在要操作的网页上点扩展图标，选择「授权当前标签页」即可。
          </p>
          <Button asChild variant="outline" size="sm">
            <Link to="/chat">回到对话</Link>
          </Button>
        </div>
      ) : preview ? (
        <div className="space-y-3 rounded-lg border p-4">
          <div className="text-sm">
            <div className="text-muted-foreground">请求连接的设备</div>
            <div className="mt-0.5 font-medium">
              {preview.displayName || "未知设备"}（{preview.browser || "浏览器"}）
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <div>
              <div className="text-muted-foreground">配对码</div>
              <div className="font-mono text-base tracking-wider">{preview.userCode}</div>
            </div>
            <div>
              <div className="text-muted-foreground">剩余时间</div>
              <div>{preview.expiresIn} 秒</div>
            </div>
          </div>
          <p className="text-xs text-muted-foreground">
            批准后，这个浏览器会成为你的「执行设备」：Agent 只会在你**逐个标签页授权**后操作网页，
            不会读取 Cookie，也不会持续上传截图。设备令牌与网页登录完全分离，你可以随时在
            「浏览器」面板里撤销。
          </p>
          <div className="flex gap-2">
            <Button disabled={busy || preview.status !== "PENDING"} onClick={approve}>
              {busy ? <Loader2 className="mr-1.5 size-4 animate-spin" /> : null}
              批准这台设备
            </Button>
            <Button asChild variant="ghost">
              <Link to="/chat">取消</Link>
            </Button>
          </div>
          {preview.status !== "PENDING" ? (
            <p className="text-xs text-amber-600">这个配对码已经使用过或已失效，请在扩展里重新开始配对。</p>
          ) : null}
        </div>
      ) : (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" />
          正在读取配对信息…
        </div>
      )}
    </div>
  );
}
