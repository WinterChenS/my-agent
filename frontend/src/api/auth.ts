import { apiFetch } from "./client";
import type { LoginResponse, UserInfo } from "./types";

/** 验证码题目。注意：**里面没有任何正确答案**，判定只发生在服务端。 */
export interface CaptchaChallenge {
  required: boolean;
  mode: "SLIDER" | "TEXT";
  id: string | null;
  background: string | null;
  piece: string | null;
  pieceY: number | null;
  /** 拼图块图片边长（含边距） */
  pieceSize: number;
  /** 拼图块图片相对缺口的向外边距（原图像素） */
  piecePad: number;
  width: number;
  height: number;
  ttlSeconds: number;
  /** TEXT（无障碍替代题）的题面 */
  question: string | null;
}

/** 轨迹采样点：[槽位比例 0~1, 相对时间毫秒] */
export type TrackPoint = [number, number];

export interface CaptchaVerifyBody {
  id: string;
  sessionKey: string;
  x?: number;
  answer?: string;
  track?: TrackPoint[];
}

export function fetchCaptcha(sessionKey: string, mode: "slider" | "text" = "slider") {
  const query = new URLSearchParams({ sessionKey, mode });
  return apiFetch<CaptchaChallenge>(`/api/auth/captcha?${query.toString()}`);
}

/** 提交验证：位置/答案由服务端比对；轨迹只作辅助风险信号。 */
export function verifyCaptcha(body: CaptchaVerifyBody) {
  return apiFetch<{ ticket: string }>("/api/auth/captcha/verify", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function login(
  username: string,
  password: string,
  captchaTicket?: string | null,
  sessionKey?: string | null,
) {
  return apiFetch<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({
      username,
      password,
      captchaTicket: captchaTicket ?? null,
      sessionKey: sessionKey ?? null,
    }),
  });
}

export function fetchMe() {
  return apiFetch<UserInfo>("/api/auth/me");
}
