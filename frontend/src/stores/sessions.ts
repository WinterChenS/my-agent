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
  /** 当前打开的会话 id（侧栏高亮用） */
  selectedId: number | null;
  /**
   * 跨页面的「打开这条会话」请求。
   *
   * <p>带自增序号而不是只存 id：用户可能连点同一条（或删除后重新点），
   * 只有 id 的话状态不变、订阅方不会收到通知。
   */
  openRequest: { id: number; seq: number } | null;
  /**
   * 跨页面的「新建会话」请求。
   *
   * <p>用**一次性标记**而不是自增序号：ChatPage 每次进入 /chat 都会重新挂载，
   * 序号留在 store 里会被下次挂载当成"又有新请求"而误清空刚打开的会话
   * （实测踩到：从管理页点历史会话回来，会话被冲掉）。标记由 ChatPage 显式消费掉。
   */
  newRequest: boolean;
  /** 正在跑的 threadId 集合（侧栏转圈标记） */
  runningThreads: Set<string>;

  refresh: () => Promise<ChatSession[]>;
  requestOpen: (id: number) => void;
  consumeOpen: () => void;
  requestNew: () => void;
  consumeNew: () => void;
  setSelectedId: (id: number | null) => void;
  setRunningThreads: (ids: Set<string>) => void;
  /** 退出登录时清空，避免下一个账号看到上一个账号的会话列表 */
  reset: () => void;
}

let seq = 0;

export const useSessionStore = create<SessionStore>((set) => ({
  sessions: [],
  loading: false,
  error: null,
  selectedId: null,
  openRequest: null,
  newRequest: false,
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

  requestOpen: (id) => {
    seq += 1;
    set({ selectedId: id, openRequest: { id, seq } });
  },

  consumeOpen: () => set({ openRequest: null }),

  requestNew: () => {
    seq += 1;
    set({ selectedId: null, openRequest: null, newRequest: true });
  },

  consumeNew: () => set({ newRequest: false }),

  setSelectedId: (id) => set({ selectedId: id }),

  setRunningThreads: (ids) => set({ runningThreads: ids }),

  reset: () =>
    set({
      sessions: [],
      loading: false,
      error: null,
      selectedId: null,
      openRequest: null,
      newRequest: false,
      runningThreads: new Set<string>(),
    }),
}));
