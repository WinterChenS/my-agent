import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { UserInfo } from "@/api/types";

interface AuthState {
  token: string | null;
  user: UserInfo | null;
  setAuth: (token: string, user: UserInfo) => void;
  clear: () => void;
}

/** 登录态：持久化到 localStorage，供 fetch 注入 Bearer。 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      clear: () => set({ token: null, user: null }),
    }),
    { name: "myagent-auth" },
  ),
);
