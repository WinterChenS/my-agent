import { useEffect } from "react";
import { useNavigate } from "react-router";
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
  const selectedId = useSessionStore((s) => s.selectedId);
  const loading = useSessionStore((s) => s.loading);
  const error = useSessionStore((s) => s.error);
  const runningThreads = useSessionStore((s) => s.runningThreads);
  const refresh = useSessionStore((s) => s.refresh);
  const requestOpen = useSessionStore((s) => s.requestOpen);
  const requestNew = useSessionStore((s) => s.requestNew);
  const setRunningThreads = useSessionStore((s) => s.setRunningThreads);

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

  /** 点历史会话：请求打开 + 跳到聊天页（已经在 /chat 时也会触发，因为 seq 递增） */
  function openSession(item: ChatSession) {
    requestOpen(item.id);
    void navigate("/chat");
  }

  async function remove(target: ChatSession) {
    try {
      await deleteSession(target.id);
      // 删掉的正是当前打开的：让 ChatPage 回到欢迎页（空白新会话）
      if (useSessionStore.getState().selectedId === target.id) {
        useSessionStore.getState().requestNew();
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
            onClick={() => {
              requestNew();
              void navigate("/chat");
            }}
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
