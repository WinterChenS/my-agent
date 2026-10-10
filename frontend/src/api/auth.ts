import { apiFetch } from "./client";
import type { LoginResponse, UserInfo } from "./types";

export function login(username: string, password: string) {
  return apiFetch<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export function fetchMe() {
  return apiFetch<UserInfo>("/api/auth/me");
}
