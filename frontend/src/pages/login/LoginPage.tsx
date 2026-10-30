import { useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { login } from "@/api/auth";
import { SliderCaptcha } from "@/components/auth/SliderCaptcha";
import { useAuthStore } from "@/stores/auth";

export default function LoginPage() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [captchaTicket, setCaptchaTicket] = useState<string | null>(null);
  /** 登录失败时换一张验证图：用计数而不是用错误文案当 key（同样的错误文案连续出现时也能复位） */
  const [captchaRound, setCaptchaRound] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const { token, user } = await login(username, password, captchaTicket);
      setAuth(token, user);
      navigate("/chat", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
      // 票据一次一用：提交过就作废，失败后必须重新验证
      setCaptchaTicket(null);
      setCaptchaRound((round) => round + 1);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>登录 my-agent</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={onSubmit}>
            <div className="space-y-1">
              <label className="text-sm" htmlFor="username">
                用户名
              </label>
              <Input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm" htmlFor="password">
                密码
              </label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </div>
            <SliderCaptcha key={`captcha-${captchaRound}`} onVerified={setCaptchaTicket} />
            {error && <p className="text-destructive text-sm">{error}</p>}
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? "登录中…" : "登录"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
