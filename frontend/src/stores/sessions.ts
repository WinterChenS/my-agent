import { create } from "zustand";
import { listSessions } from "@/api/sessions";
import type { ChatSession } from "@/api/types";

/**
 * 会话列表的共享状态。
 *
 * <p>为什么从 ChatPage 提到 store：会话历史现在挂在**全局菜单栏的下半部分**，
 * 在模型 / Agent / Skill / 审计等任意页面都要能看到、能点开、能刷新 ——
 * 数据与「打开意图」必须脱离 ChatPage 的生命周期（ChatPage 只在 /chat 路由下挂载）。
 *
 * <p>分工：这个 store 只负责**列表数据 + 选择意图**；真正「打开会话」（拉历史消息、
 * 接续在跑的 run、刷新上下文用量）仍然由 ChatPage 负责，它订阅 `openRequest` 后执行。
 */
interface SessionStore {
  sessions: ChatSession[];
  loading: boolean;
  error: string | null;
  /** 正在跑的 threadId 集合（侧栏转圈标记） */
  runningThreads: Set<string>;

  refresh: () => Promise<ChatSession[]>;
  setRunningThreads: (ids: Set<string>) => void;
  /** 退出登录时清空，避免下一个账号看到上一个账号的会话列表 */
  reset: () => void;
}

export const useSessionStore = create<SessionStore>((set) => ({
  sessions: [],
  loading: false,
  error: null,
  runningThreads: new Set<string>(),

  refresh: async () => {
    set({ loading: true });
    try {
      const list = await listSessions();
      set({ sessions: list, loading: false, error: null });
      return list;
    } catch (err) {
      set({
        loading: false,
        error: err instanceof Error ? err.message : "加载会话失败",
      });
      return [];
    }
  },

  setRunningThreads: (ids) => set({ runningThreads: ids }),

  reset: () =>
    set({
      sessions: [],
      loading: false,
      error: null,
      runningThreads: new Set<string>(),
    }),
}));
