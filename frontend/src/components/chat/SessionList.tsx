import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router";
import { Plus, RefreshCw, Trash2 } from "lucide-react";
import { deleteSession, listRunningThreads } from "@/api/sessions";
import { Button } from "@/components/ui/button";
import { groupSessionsByTime } from "@/lib/chat/sessionGroups";
import { useSessionStore } from "@/stores/sessions";
import type { ChatSession } from "@/api/types";

/**
 * 会话历史列表（挂在菜单栏下半部分）。
 *
 * <p>它属于**全局布局**而不是 ChatPage：这样在任意页面都能看到历史、点开某条会话
 * （点击后跳 /chat，由 ChatPage 订阅 `openRequest` 真正打开），也能随时刷新列表。
 */
export function SessionList() {
  const navigate = useNavigate();
  const sessions = useSessionStore((s) => s.sessions);
  const loading = useSessionStore((s) => s.loading);
  const error = useSessionStore((s) => s.error);
  const runningThreads = useSessionStore((s) => s.runningThreads);
  const refresh = useSessionStore((s) => s.refresh);
  const setRunningThreads = useSessionStore((s) => s.setRunningThreads);
  /*
   * 当前打开的是哪条，从 URL 路径推导（URL 是唯一来源）。
   * 用 useLocation 而不是 useParams：侧栏挂在布局路由下，拿不到子路由的路径参数。
   */
  const { pathname } = useLocation();
  const matched = pathname.match(/^\/chat\/(\d+)/);
  const selectedId = matched ? Number(matched[1]) : null;

  // 首次挂载拉一次：侧边栏在任何页面都挂着，因此"任意页面都能看到历史"
  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 「正在跑」的转圈标记：每 3 秒轮询（原先在 ChatPage，随列表一起搬过来）
  useEffect(() => {
    let stopped = false;
    const tick = async () => {
      try {
        const ids = await listRunningThreads();
        if (!stopped) {
          setRunningThreads(new Set(ids));
        }
      } catch {
        // 后端不可用时不打扰用户：保留上一次结果
      }
    };
    void tick();
    const timer = window.setInterval(tick, 3000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [setRunningThreads]);

  /** 点历史会话：把 id 带进 URL 并跳转 —— 链接即可复制粘贴分享/刷新还原 */
  function openSession(item: ChatSession) {
    void navigate(`/chat/${item.id}`);
  }

  async function remove(target: ChatSession) {
    try {
      await deleteSession(target.id);
      // 删掉的正是当前打开的：回到欢迎页（URL 去掉参数）
      if (selectedId === target.id) {
        void navigate("/chat");
      }
      await refresh();
    } catch {
      // 删除失败不阻塞：列表刷新会反映真实状态
      await refresh();
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col border-t" data-testid="session-list">
      <div className="flex shrink-0 items-center justify-between px-2 py-1.5">
        <span className="text-muted-foreground px-1 text-xs font-medium">会话历史</span>
        <div className="flex items-center">
          <Button
            variant="ghost"
            size="icon"
            className="size-6"
            onClick={() => void refresh()}
            title="刷新会话"
            data-testid="session-refresh"
          >
            <RefreshCw className={loading ? "size-3.5 animate-spin" : "size-3.5"} />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-6"
            onClick={() => void navigate("/chat")}
            title="新建会话"
          >
            <Plus className="size-3.5" />
          </Button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-2 pt-0">
        {error && <p className="text-destructive p-2 text-xs">{error}</p>}
        {!error && sessions.length === 0 && (
          <p className="text-muted-foreground p-2 text-xs">
            还没有会话，去聊天页输入即可新建。
          </p>
        )}
        {groupSessionsByTime(sessions).map((group) => (
          <div key={group.label} className="mb-2">
            {/* Phase 13：按时间分组，避免一长条散列 */}
            <p
              data-testid="session-group"
              className="text-muted-foreground px-2 py-1 text-[11px] font-medium"
            >
              {group.label}
            </p>
            {group.items.map((item) => (
              <div
                key={item.id}
                data-testid="session-item-row"
                data-selected={selectedId === item.id}
                className={`group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm ${
                  selectedId === item.id ? "bg-accent" : "hover:bg-accent/60"
                }`}
              >
                <button
                  data-testid="session-item"
                  className="flex min-w-0 flex-1 items-center gap-1.5 truncate text-left"
                  onClick={() => openSession(item)}
                  title={item.threadId ?? ""}
                >
                  {item.threadId && runningThreads.has(item.threadId) && (
                    <span
                      data-testid="session-running"
                      title="这一轮正在后台跑"
                      aria-label="正在跑"
                      className="border-primary size-3 shrink-0 animate-spin rounded-full border-2 border-t-transparent"
                    />
                  )}
                  <span className="truncate">{item.title ?? `会话 ${item.id}`}</span>
                </button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-6 opacity-0 group-hover:opacity-100"
                  onClick={() => void remove(item)}
                  title="删除会话"
                >
                  <Trash2 className="size-3" />
                </Button>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
