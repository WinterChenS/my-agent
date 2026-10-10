import { useCallback, useEffect, useRef, useState } from "react";
import type { HttpAgent } from "@ag-ui/client";
import { Plus, Send, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { MessageBubble } from "@/components/chat/MessageBubble";
import { ToolCallCard } from "@/components/cards/ToolCallCard";
import { JsonCard } from "@/components/cards/JsonCard";
import { ApprovalCard } from "@/components/cards/ApprovalCard";
import { ConfirmCard } from "@/components/cards/ConfirmCard";
import { MappingCard } from "@/components/cards/MappingCard";
import { createChatAgent } from "@/lib/agui/agent";
import { initialChatState, markApproval, reduceEvent, replayMessages } from "@/lib/agui/reducer";
import type { ChatSession } from "@/api/types";
import type { AgentSummary } from "@/api/types";
import type { ChatState, ConfirmCardData, MappingCardData } from "@/lib/agui/types";
import {
  createSession,
  deleteSession,
  listAgents,
  listMessages,
  listSessions,
} from "@/api/sessions";
import { decideApproval } from "@/api/approvals";

export default function ChatPage() {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [agentId, setAgentId] = useState<number | null>(null);
  const [session, setSession] = useState<ChatSession | null>(null);
  const [chat, setChat] = useState<ChatState>(initialChatState);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const agentRef = useRef<HttpAgent | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const bootstrapped = useRef(false);

  const refreshSessions = useCallback(async () => {
    try {
      setSessions(await listSessions());
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载会话失败");
    }
  }, []);

  useEffect(() => {
    listAgents()
      .then((page) => {
        setAgents(page.records);
        setAgentId((current) => current ?? page.records[0]?.id ?? null);
      })
      .catch(() => {
        /* 无 Agent 时由下方提示引导 */
      });
  }, []);

  // 首次加载：恢复最近一次会话，让刷新后历史仍在（Task 4.3 门禁）。
  useEffect(() => {
    if (bootstrapped.current) {
      return;
    }
    bootstrapped.current = true;
    void (async () => {
      try {
        const list = await listSessions();
        setSessions(list);
        if (list.length > 0) {
          await openSession(list[0]);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "加载会话失败");
      }
    })();
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [chat.items]);

  async function openSession(target: ChatSession) {
    setError(null);
    setSession(target);
    agentRef.current = createChatAgent();
    try {
      const messages = await listMessages(target.id);
      setChat(replayMessages(messages));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载历史消息失败");
    }
  }

  function startNewSession() {
    setSession(null);
    setChat(initialChatState);
    agentRef.current = createChatAgent();
    setError(null);
  }

  async function removeSession(target: ChatSession) {
    try {
      await deleteSession(target.id);
      if (session?.id === target.id) {
        startNewSession();
      }
      await refreshSessions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除会话失败");
    }
  }

  /** 发起一轮 /agui/run；text 与 resume 二选一。 */
  async function runTurn(
    active: ChatSession,
    options: { text?: string; resume?: { interruptId: string; status: string; payload?: unknown }[] },
  ) {
    const agent = agentRef.current ?? (agentRef.current = createChatAgent());
    agent.threadId = active.threadId!;
    if (options.text) {
      agent.messages = [
        { id: crypto.randomUUID(), role: "user", content: options.text },
      ] as typeof agent.messages;
    } else {
      agent.messages = [] as typeof agent.messages;
    }

    setChat((prev) => ({ ...prev, status: "running" }));
    setBusy(true);
    try {
      await agent.runAgent(
        {
          forwardedProps: { agentId: active.agentId ?? agentId },
          resume: options.resume as never,
        },
        {
          onEvent: ({ event }) => {
            setChat((prev) => reduceEvent(prev, event as never));
          },
          onRunFailed: ({ error: runError }) => {
            setError(runError.message);
            setChat((prev) => ({ ...prev, status: "error" }));
          },
        },
      );
      setChat((prev) => (prev.status === "running" ? { ...prev, status: "finished" } : prev));
      await refreshSessions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败，可重试");
      setChat((prev) => ({ ...prev, status: "error" }));
      if (options.text) {
        setInput(options.text);
      }
    } finally {
      setBusy(false);
    }
  }

  async function send() {
    const text = input.trim();
    if (!text || busy) {
      return;
    }
    setError(null);

    let active = session;
    if (!active) {
      if (agentId == null) {
        setError("请先在「Agent」页创建一个 Agent，然后在这里选择它");
        return;
      }
      try {
        active = await createSession(agentId);
        setSession(active);
        await refreshSessions();
        agentRef.current = createChatAgent();
      } catch (err) {
        setError(err instanceof Error ? err.message : "创建会话失败");
        return;
      }
    }
    if (!active?.threadId) {
      setError("会话缺少 threadId");
      return;
    }

    setInput("");
    setChat((prev) => ({
      items: [
        ...prev.items,
        {
          kind: "text",
          id: `local-user-${Date.now()}`,
          role: "user",
          content: text,
          streaming: false,
        },
      ],
      status: "running",
      runId: null,
    }));
    await runTurn(active, { text });
  }

  /** 审批决定 -> 记录结果 -> 用 resume 唤醒被拦截的执行。 */
  async function decide(interruptId: string, decision: "APPROVED" | "DENIED") {
    if (busy || !session) {
      return;
    }
    setError(null);
    try {
      const result = await decideApproval(interruptId, decision);
      setChat((prev) =>
        markApproval(prev, interruptId, decision === "APPROVED" ? "approved" : "denied"),
      );
      await runTurn(session, {
        resume: [
          {
            interruptId,
            status: result.resumeStatus,
            payload: decision === "APPROVED" ? { approved: true } : { approved: false },
          },
        ],
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "审批失败");
    }
  }

  /** 确认卡回传选项：作为一条用户消息继续该 run。 */
  async function chooseOption(value: string) {
    if (busy || !session) {
      return;
    }
    setChat((prev) => ({
      items: [
        ...prev.items,
        {
          kind: "text",
          id: `local-user-${Date.now()}`,
          role: "user",
          content: value,
          streaming: false,
        },
      ],
      status: "running",
      runId: null,
    }));
    await runTurn(session, { text: value });
  }

  return (
    <div className="flex h-screen">
      <aside className="flex w-64 shrink-0 flex-col border-r">
        <div className="flex items-center justify-between border-b p-2">
          <span className="text-sm font-medium">会话</span>
          <Button variant="ghost" size="icon" onClick={startNewSession} title="新建会话">
            <Plus className="size-4" />
          </Button>
        </div>
        <div className="flex-1 overflow-auto p-2">
          {sessions.length === 0 && (
            <p className="text-muted-foreground p-2 text-xs">还没有会话，直接在右侧输入即可新建。</p>
          )}
          {sessions.map((item) => (
            <div
              key={item.id}
              className={`group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm ${
                session?.id === item.id ? "bg-accent" : "hover:bg-accent/60"
              }`}
            >
              <button
                className="flex-1 truncate text-left"
                onClick={() => void openSession(item)}
                title={item.threadId ?? ""}
              >
                {item.title ?? `会话 ${item.id}`}
              </button>
              <Button
                variant="ghost"
                size="icon"
                className="size-6 opacity-0 group-hover:opacity-100"
                onClick={() => void removeSession(item)}
                title="删除会话"
              >
                <Trash2 className="size-3" />
              </Button>
            </div>
          ))}
        </div>
      </aside>

      <section className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-2 border-b p-3">
          <span className="text-sm font-medium">聊天</span>
          {chat.status === "running" && <Badge variant="secondary">生成中…</Badge>}
          {chat.status === "error" && <Badge variant="destructive">出错</Badge>}
          <select
            className="border-input ml-auto h-8 rounded-md border bg-transparent px-2 text-sm"
            value={agentId ?? ""}
            onChange={(e) => setAgentId(e.target.value ? Number(e.target.value) : null)}
            disabled={!!session}
            title={session ? "已有会话时不可切换 Agent" : "选择 Agent"}
          >
            {agents.length === 0 && <option value="">（暂无 Agent）</option>}
            {agents.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </header>

        <div className="flex-1 space-y-4 overflow-auto p-4">
          {chat.items.length === 0 && (
            <Card className="text-muted-foreground p-6 text-center text-sm">
              输入一句话开始对话。回复会以流式气泡出现。
            </Card>
          )}
          {chat.items.map((item) => {
            if (item.kind === "text") {
              return (
                <MessageBubble
                  key={item.id}
                  role={item.role}
                  content={item.content}
                  streaming={item.streaming}
                />
              );
            }
            if (item.kind === "tool") {
              return <ToolCallCard key={item.id} item={item} />;
            }
            if (item.kind === "approval") {
              return (
                <ApprovalCard
                  key={item.id}
                  toolName={item.toolName}
                  args={item.args}
                  message={item.message}
                  status={item.status}
                  busy={busy}
                  onDecide={(decision) => void decide(item.interruptId, decision)}
                />
              );
            }
            if (item.kind === "card") {
              if (item.card === "confirm") {
                return (
                  <ConfirmCard
                    key={item.id}
                    data={item.data as ConfirmCardData}
                    busy={busy}
                    onChoose={(value) => void chooseOption(value)}
                  />
                );
              }
              if (item.card === "mapping") {
                return <MappingCard key={item.id} data={item.data as MappingCardData} />;
              }
              return <JsonCard key={item.id} name={`${item.name}（${item.card}）`} data={item.data} />;
            }
            return (
              <Card key={item.id} className="border-destructive/50 p-3 text-sm">
                <span className="text-destructive">运行出错：</span> {item.message}
                {item.code && <span className="text-muted-foreground"> ({item.code})</span>}
              </Card>
            );
          })}
          <div ref={bottomRef} />
        </div>

        {error && (
          <div className="border-destructive/40 bg-destructive/10 mx-4 mb-2 rounded-md border p-2 text-sm">
            {error}
            <Button variant="link" size="sm" onClick={() => void send()}>
              重试
            </Button>
          </div>
        )}

        <div className="flex items-center gap-2 border-t p-3">
          <Input
            value={input}
            placeholder="输入消息，回车发送"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void send();
              }
            }}
            disabled={busy}
          />
          <Button onClick={() => void send()} disabled={busy || !input.trim()}>
            <Send className="size-4" />
          </Button>
        </div>
      </section>
    </div>
  );
}
