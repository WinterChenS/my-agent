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
  /** 后端总数（用于判断还有没有下一页） */
  total: number;
  /** 已加载到第几页（从 1 开始） */
  page: number;
  pageSize: number;
  hasMore: boolean;
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  /** 正在跑的 threadId 集合（侧栏转圈标记） */
  runningThreads: Set<string>;

  refresh: () => Promise<ChatSession[]>;
  /** 侧栏滚到底时加载下一页 */
  loadMore: () => Promise<void>;
  setRunningThreads: (ids: Set<string>) => void;
  /** 退出登录时清空，避免下一个账号看到上一个账号的会话列表 */
  reset: () => void;
}

export const useSessionStore = create<SessionStore>((set, get) => ({
  sessions: [],
  total: 0,
  page: 0,
  pageSize: 20,
  hasMore: false,
  loading: false,
  loadingMore: false,
  error: null,
  runningThreads: new Set<string>(),

  /**
   * 拉第一页。
   *
   * <p>如果用户已经往下滚过（page > 1），**保留已加载的后续页**：
   * 会话标题是异步生成的，聊天页会在跑完后 refresh 一次；若直接把列表重置成第一页，
   * 用户正看到的历史会突然全部消失、滚动位置也跳回顶部。
   */
  refresh: async () => {
    set({ loading: true });
    try {
      const first = await listSessions(1, get().pageSize);
      const firstIds = new Set(first.records.map((item) => item.id));
      const merged = [
        ...first.records,
        ...get().sessions.filter((item) => !firstIds.has(item.id)),
      ];
      set({
        sessions: merged,
        total: first.total,
        page: Math.max(1, get().page),
        hasMore: merged.length < first.total,
        loading: false,
        error: null,
      });
      return merged;
    } catch (err) {
      set({
        loading: false,
        error: err instanceof Error ? err.message : "加载会话失败",
      });
      return [];
    }
  },

  loadMore: async () => {
    const { page, pageSize, hasMore, loading, loadingMore } = get();
    if (!hasMore || loading || loadingMore) {
      return;
    }
    set({ loadingMore: true });
    try {
      const next = await listSessions(page + 1, pageSize);
      const seen = new Set(get().sessions.map((item) => item.id));
      const added = next.records.filter((item) => !seen.has(item.id));
      const merged = [...get().sessions, ...added];
      set({
        sessions: merged,
        page: page + 1,
        total: next.total,
        hasMore: merged.length < next.total,
        loadingMore: false,
      });
    } catch (err) {
      set({
        loadingMore: false,
        error: err instanceof Error ? err.message : "加载更多会话失败",
      });
    }
  },

  setRunningThreads: (ids) => set({ runningThreads: ids }),

  reset: () =>
    set({
      sessions: [],
      total: 0,
      page: 0,
      hasMore: false,
      loading: false,
      loadingMore: false,
      error: null,
      runningThreads: new Set<string>(),
    }),
}));
