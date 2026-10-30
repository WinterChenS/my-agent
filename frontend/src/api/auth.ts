import { apiFetch } from "./client";
import type { LoginResponse, UserInfo } from "./types";

/** 滑动验证题目（required=false 表示服务端关掉了验证）。 */
export interface CaptchaChallenge {
  required: boolean;
  id: string | null;
  background: string | null;
  piece: string | null;
  pieceY: number | null;
  pieceSize: number;
  width: number;
  height: number;
  ttlSeconds: number;
}

export function fetchCaptcha() {
  return apiFetch<CaptchaChallenge>("/api/auth/captcha");
}

/** 提交拼图左侧位置（原图像素），对了返回一次性 ticket。 */
export function verifyCaptcha(id: string, x: number) {
  return apiFetch<{ ticket: string }>("/api/auth/captcha/verify", {
    method: "POST",
    body: JSON.stringify({ id, x }),
  });
}

export function login(username: string, password: string, captchaTicket?: string | null) {
  return apiFetch<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password, captchaTicket: captchaTicket ?? null }),
  });
}

export function fetchMe() {
  return apiFetch<UserInfo>("/api/auth/me");
}
