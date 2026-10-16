import { useCallback, useEffect, useRef, useState } from "react";
import type { HttpAgent } from "@ag-ui/client";
import { Bot, ChevronDown, Clock, Plus, Send, Square, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { MessageBubble } from "@/components/chat/MessageBubble";
import { SourceArtifactPanel } from "@/components/chat/SourceArtifactPanel";
import { AttachmentStaging } from "@/components/chat/AttachmentStaging";
import {
  SkillMentionInput,
  type SkillMentionInputHandle,
} from "@/components/chat/SkillMentionInput";
import { ContextRing } from "@/components/chat/ContextRing";
import type { ContextUsage } from "@/api/sessions";
import { getMyQuota, type QuotaStatus } from "@/api/quota";
import { SubagentCard } from "@/components/chat/SubagentCard";
import { ToolCallCard, ToolCallGroup } from "@/components/cards/ToolCallCard";
import { JsonCard } from "@/components/cards/JsonCard";
import { ApprovalCard } from "@/components/cards/ApprovalCard";
import { ConfirmCard } from "@/components/cards/ConfirmCard";
import { MappingCard } from "@/components/cards/MappingCard";
import { abortCurrentRun, createChatAgent } from "@/lib/agui/agent";
import { initialChatState, markApproval, reduceEvent, replayMessages } from "@/lib/agui/reducer";
import type { ChatSession } from "@/api/types";
import type { AgentSummary } from "@/api/types";
import type { ChatItem, ChatState, ConfirmCardData, MappingCardData } from "@/lib/agui/types";

type ToolItem = Extract<ChatItem, { kind: "tool" }>;
import {
  clearContext,
  compactContext,
  createSession,
  deleteSession,
  getContextUsage,
  getRunState,
  guideSession,
  interruptSession,
  listAgents,
  listMessages,
  listRunningThreads,
  listSessions,
} from "@/api/sessions";
import { decideApproval } from "@/api/approvals";
import { uploadArtifact } from "@/api/artifacts";
import {
  removeAttachment,
  stageFiles,
  type StagedAttachment,
} from "@/lib/attachments/staging";
import { buildAttachmentBlock } from "@/lib/attachments/message";
import { filesFromDataTransfer, normalizeIncomingFiles } from "@/lib/attachments/sources";
import { groupSessionsByTime } from "@/lib/chat/sessionGroups";
import { listSkills, type SkillInfo } from "@/api/skills";
import { partsAreEmpty, partsSkillIds, partsToText, type MentionPart } from "@/lib/skills/selection";

/** 图片文件 → AG-UI 的 image 内容块（base64 内联；官方适配器会转成 ImageBlock）。 */
async function fileToImagePart(file: File) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) {
    binary += String.fromCharCode(bytes[i]);
  }
  return {
    type: "image" as const,
    source: {
      type: "data" as const,
      value: btoa(binary),
      mimeType: file.type || "image/png",
    },
  };
}

/** 单轮图片数量上限（与后端守卫一致）。 */
const MAX_IMAGES_PER_RUN = 5;

export default function ChatPage() {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [agentId, setAgentId] = useState<number | null>(null);
  const [session, setSession] = useState<ChatSession | null>(null);
  const [chat, setChat] = useState<ChatState>(initialChatState);
  /** 输入内容 = 文本段 + 技能块（技能是行内不可编辑的块，位置随用户输入保留）。 */
  const [parts, setParts] = useState<MentionPart[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  const [contextNotice, setContextNotice] = useState<string | null>(null);
  const [contextBusy, setContextBusy] = useState(false);
  /** 编辑器里有没有内容（**以 DOM 为准**，见 SkillMentionInput.onContentChange）。 */
  const [hasInput, setHasInput] = useState(false);
  /** 输入框句柄：发送前 flush 一次，确保发出的是屏幕上看到的内容。 */
  const inputRef = useRef<SkillMentionInputHandle | null>(null);
  /**
   * 待发送队列：**执行中**用户也能输入并发送，消息先排在这里，当前任务跑完自动发出去。
   * 每项都能「撤回」（内容回到输入框）或「引导」（见 guideQueued）。
   */
  const [queued, setQueued] = useState<
    { id: string; parts: MentionPart[]; text: string; label: string }[]
  >([]);
  /**
   * 队列的 ref 镜像。
   *
   * 为什么需要：自动发送的判断发生在 `runTurn` 收尾时，而那次调用的闭包是它**启动时**建立的 ——
   * 里面读到的 `queued` 永远是"这一轮开始时"的旧值（通常是空的），于是排队消息永远不会被发出。
   * 实测踩过：队列里明明有一条，第一轮跑完后毫无动静。所以这里用 ref 读**当前**队列。
   */
  const queuedRef = useRef<typeof queued>([]);
  useEffect(() => {
    queuedRef.current = queued;
  }, [queued]);
  /** 正在跑的 threadId 集合（侧栏标记「正在跑」，本进程运行态）。 */
  const [runningThreads, setRunningThreads] = useState<Set<string>>(new Set());
  /** 支出额度状态（tier1-upgrades ④）。 */
  const [quota, setQuota] = useState<QuotaStatus | null>(null);
  /** 正在后台跑、需要盯着结束的会话（id → 标题）。 */
  const watchingRuns = useRef<Map<number, string>>(new Map());
  // Phase 10：`/` 选技能（D21）、上传中状态、右侧栏刷新信号
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [artifactRefresh, setArtifactRefresh] = useState(0);
  const [uploading, setUploading] = useState(false);
  // Phase 13：拖拽进聊天区时高亮提示
  const [dragging, setDragging] = useState(false);
  // Phase 11 / D24：附件先暂存在浏览器，发送时才上传
  const [staged, setStaged] = useState<StagedAttachment[]>([]);

  const agentRef = useRef<HttpAgent | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
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

  useEffect(() => {
    listSkills(1, 100, "ENABLED")
      .then((page) => setSkills(page.records))
      .catch(() => {
        /* 技能拉取失败不影响聊天 */
      });
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [chat.items]);

  /**
   * 接续正在跑的 run（Phase 14）。
   *
   * <p>用户刷新页面或切到别的页面再回来后，SSE 已经断了，但服务端那一轮**仍在跑**（断开后有宽限期）。
   * 这里查出"已生成多少文本"，先把它接到气泡上继续显示，再轮询到这一轮结束，最后重新拉一次消息
   * 拿到落库后的完整内容。
   */
  const resumeRunningRun = useCallback(
    async (target: ChatSession) => {
      if (!target.threadId) {
        return;
      }
      const applyPartial = (text: string) => {
        if (!text) {
          return;
        }
        setChat((prev) => {
          const items = [...prev.items];
          for (let i = items.length - 1; i >= 0; i -= 1) {
            const item = items[i];
            if (item.kind === "text" && item.role === "assistant") {
              items[i] = { ...item, content: text, streaming: true, stopped: false };
              return { ...prev, items, status: "running" };
            }
          }
          return {
            items: [
              ...items,
              {
                kind: "text",
                id: `resumed-${target.id}`,
                role: "assistant",
                content: text,
                streaming: true,
              },
            ],
            status: "running",
            runId: null,
          };
        });
        setBusy(true);
      };

      const state = await getRunState(target.id).catch(() => null);
      if (!state?.running) {
        return false;
      }
      applyPartial(state.text);

      // 轮询到这一轮结束（结束/宽限期超时后服务端会落库）
      const deadline = Date.now() + 10 * 60 * 1000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 1200));
        const next = await getRunState(target.id).catch(() => null);
        if (!next) {
          break;
        }
        applyPartial(next.text);
        if (!next.running) {
          break;
        }
      }
      // 拉回落库后的最终内容（含「已停止」标记）
      try {
        setChat(replayMessages(await listMessages(target.id)));
      } catch {
        /* 拉取失败就保留轮询到的内容 */
      }
      setBusy(false);
      return true;
    },
    [],
  );

  // 打开会话后、以及从别的页面/标签页回来时，都尝试接续
  useEffect(() => {
    if (!session) {
      return;
    }
    void resumeRunningRun(session);
  }, [session, resumeRunningRun]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible" && session) {
        void resumeRunningRun(session);
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [session, resumeRunningRun]);




  /** 轮询「正在跑」的会话：在侧栏给它们画一个转圈标记（C2）。 */
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
  }, []);

  function startNewSession() {
    setSession(null);
    setChat(initialChatState);
    agentRef.current = createChatAgent();
    setError(null);
    // 复位 busy：上一个会话的 run 可能还在后台跑，但“正在生成”不该绑住新会话的输入框
    // （Phase 14 的设计就是“切走后台继续跑”，所以切过来必须能接着打字）
    setBusy(false);
    // 新会话把上下文用量也清掉，避免残留到空白新会话里
    setContextUsage(null);
    setContextNotice(null);
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
  /** 刷新上下文用量（打开会话、每轮结束后调用）。 */
  const refreshContext = useCallback(async (active: ChatSession | null) => {
    if (!active) {
      setContextUsage(null);
      return;
    }
    try {
      setContextUsage(await getContextUsage(active.id));
    } catch {
      setContextUsage(null);
    }
  }, []);

  /** 刷新额度（≥80% 提示、超限说明）。 */
  const refreshQuota = useCallback(async (agentDbId: number | null) => {
    try {
      setQuota(await getMyQuota(agentDbId));
    } catch {
      setQuota(null);
    }
  }, []);

  const openSession = useCallback(
    async (target: ChatSession) => {
    setError(null);
    // 先复位 busy：切过来时上个会话的 run 可能还在后台跑，它不该让当前会话的输入框也用不了。
    // 如果**目标会话自己**确实在跑，下面的 resumeRunningRun 会再置回 true。
    setBusy(false);
    setSession(target);
    agentRef.current = createChatAgent();
    try {
      const messages = await listMessages(target.id);
      // aguiMeta 里带 stopped 标记，回放时据此标注「已停止」
      setChat(replayMessages(messages));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载历史消息失败");
    }
      void refreshContext(target);
      void refreshQuota(target.agentId ?? null);
    },
    // 三个 refresh 已用 useCallback 稳定（依赖为空），所以 openSession 也是稳定的
    [refreshContext, refreshQuota],
  );

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
    // openSession 已用 useCallback 稳定；列出来既消除 exhaustive-deps 警告，
    // 也避免 effect 捕获旧闭包（bootstrapped 守卫保证只跑一次）
  }, [openSession]);

  /** 压缩上下文：拿到压缩后的用量并反馈。 */
  async function handleCompact() {
    if (!session) return;
    setContextBusy(true);
    setContextNotice(null);
    try {
      const before = contextUsage;
      const after = await compactContext(session.id);
      setContextUsage(after);
      const dropped = before ? before.estimatedTokens - after.estimatedTokens : 0;
      setContextNotice(
        dropped > 0
          ? `已压缩上下文，用量下降 ${dropped.toLocaleString()} tokens`
          : "已压缩上下文（框架判断当前无需精简）",
      );
    } catch (e) {
      setContextNotice(`压缩失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setContextBusy(false);
    }
  }

  /** 清空上下文：删框架状态，聊天记录保留。 */
  async function handleClearContext() {
    if (!session) return;
    setContextBusy(true);
    setContextNotice(null);
    try {
      await clearContext(session.id);
      setContextUsage(await getContextUsage(session.id));
      setContextNotice("已清空上下文（聊天记录保留），模型将从头开始");
    } catch (e) {
      setContextNotice(`清空失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setContextBusy(false);
    }
  }

  async function runTurn(
    active: ChatSession,
    options: {
      text?: string;
      /** 图片内容块（多模态）；有值时消息 content 用「文本 + 图片」结构 */
      imageParts?: unknown[];
      /** 发送失败时把输入（含技能块）还回输入框，方便直接重试 */
      restoreParts?: MentionPart[];
      /** 本轮用到的技能 id（并入会话技能集，交给官方 SkillFilter 生效） */
      skillIds?: number[];
      resume?: { interruptId: string; status: string; payload?: unknown }[];
    },
  ) {
    const agent = agentRef.current ?? (agentRef.current = createChatAgent());
    agent.threadId = active.threadId!;
    if (options.text) {
      agent.messages = [
        {
          id: crypto.randomUUID(),
          role: "user",
          // 有图片时按 AG-UI 内容块发送：{type:"image", source:{type:"data", value:base64, mimeType}}
          content: options.imageParts?.length
            ? [{ type: "text", text: options.text }, ...options.imageParts]
            : options.text,
        },
      ] as typeof agent.messages;
    } else {
      agent.messages = [] as typeof agent.messages;
    }

    setChat((prev) => ({ ...prev, status: "running" }));
    setBusy(true);
    // tier1-upgrades ③：登记为「后台盯梢」，用户切走后跑完会收到站内提示
    watchingRuns.current.set(active.id, active.title ?? `会话 ${active.id}`);
    try {
      await agent.runAgent(
        {
          forwardedProps: {
            agentId: active.agentId ?? agentId,
            // D21：本轮选中的技能并入该会话技能集，由后端走官方 SkillFilter
            skillIds: options.skillIds ?? [],
          },
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
      // 排队：这一轮跑完，自动把队首那条接着发出去（一次发一条，避免连发把上下文打乱）
      // 注意用 queuedRef（当前值），不能用闭包里的 queued（那是这一轮启动时的旧值）
      if (queuedRef.current.length > 0) {
        const next = queuedRef.current[0];
        setQueued((prev) => prev.slice(1));
        // 与手动发送一致：先补一条**本地回显气泡**，否则消息发到后端了、界面上却看不到
        setChat((prev) => ({
          items: [
            ...prev.items,
            {
              kind: "text" as const,
              id: `local-user-${Date.now()}`,
              role: "user" as const,
              content: next.text,
              streaming: false,
            },
          ],
          status: "running",
          runId: prev.runId,
        }));
        void runTurn(active, {
          text: next.text,
          restoreParts: next.parts,
          skillIds: partsSkillIds(next.parts),
        });
      }
      // tier1-upgrades ③：这一轮结束后刷新「上一轮」提示
      await refreshSessions();
      // Phase 17：会话标题是异步生成的（首条消息后），稍等一下再刷一次列表，标题就能显示出来
      window.setTimeout(() => void refreshSessions(), 1500);
      // 跑完一轮刷新右侧「产物」区（D22 自动登记）
      setArtifactRefresh((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败，可重试");
      setChat((prev) => ({ ...prev, status: "error" }));
      if (options.restoreParts) {
        // 发送失败：把刚才那一条（含技能块）放回输入框，用户可以直接重试
        setParts(options.restoreParts);
      }
    } finally {
      setBusy(false);
      void refreshContext(session);
    }
  }

  /**
   * 撤回一条待发送消息：**内容原样回到输入框**（可以改完再发），从队列移除。
   */
  function withdrawQueued(id: string) {
    const item = queued.find((q) => q.id === id);
    if (!item) {
      return;
    }
    setQueued((prev) => prev.filter((q) => q.id !== id));
    // 回到输入框：文本段 + 技能块一起还原（技能块也一并回来）
    setParts(item.parts);
    setHasInput(true);
  }

  /**
   * 引导：把这条内容**立刻**作为补充信息交给正在执行的 Agent（官方 interrupt(Msg)）。
   *
   * 与「排队」的区别：排队是等这一轮跑完再发（新的一轮）；引导是插进**当前这一轮**，
   * Agent 会中断当前步骤来处理这条消息 —— 适合"方向不对，赶紧纠偏"。
   */
  async function guideQueued(id: string) {
    const item = queued.find((q) => q.id === id);
    if (!item || !session) {
      return;
    }
    try {
      await guideSession(session.id, item.text);
      setQueued((prev) => prev.filter((q) => q.id !== id));
      setError(null);
      setChat((prev) => ({
        items: [
          ...prev.items,
          {
            kind: "text",
            id: `local-guide-${item.id}`,
            role: "user",
            content: `（引导）${item.text}`,
            streaming: false,
          },
        ],
        status: prev.status,
        runId: prev.runId,
      }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "引导失败");
    }
  }

  async function send() {
    // 只有技能块、没文字也允许发送（用户可能就是想「用这个技能做…」，随手补一句也行）
    // 执行中也能发：先把编辑器内容 flush 出来，然后**入队**，等这一轮跑完自动发出去
    const flushed = inputRef.current?.flush() ?? parts;
    if (busy) {
      if (partsAreEmpty(flushed)) {
        return;
      }
      const text = partsToText(flushed).trim();
      setQueued((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          parts: flushed,
          text,
          label: text.length > 40 ? `${text.slice(0, 40)}…` : text,
        },
      ]);
      inputRef.current?.clear();
      setParts([]);
      setHasInput(false);
      return;
    }
    if (partsAreEmpty(flushed)) {
      return;
    }
    // 发送前把编辑器 DOM 的现状回灌一次：中文输入法组字期间 parts 会滞后，
    // 不回灌就可能「明明屏幕上写着 导出excel，发出去只有 导出」（用户实测反馈）。
    const fresh = flushed;
    const sentParts = fresh;
    const text = partsToText(fresh).trim();
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

    // 先上传暂存附件：失败就中止本轮，输入与附件都保留
    let outgoing = text;
    if (staged.length > 0) {
      setUploading(true);
      let fileNames: string[] = [];
      try {
        fileNames = await uploadStaged(active.id);
      } catch (err) {
        setError(err instanceof Error ? err.message : "附件上传失败");
        return;
      } finally {
        setUploading(false);
      }
      // 把附件清单（工作区相对路径）随消息一起发给模型：
      // 否则模型不知道这条消息带了文件，只能去列目录猜（用户实测反馈）。
      outgoing = text + buildAttachmentBlock(fileNames.map((name) => `uploads/${name}`));
    }

    // tier1-upgrades ①：把暂存里的图片作为内容块随消息发送（模型是否支持由后端守卫判定）
    const imageFiles = staged.map((item) => item.file).filter((f) => f.type.startsWith("image/"));
    if (imageFiles.length > MAX_IMAGES_PER_RUN) {
      setError(`单轮最多发送 ${MAX_IMAGES_PER_RUN} 张图片（当前 ${imageFiles.length} 张）`);
      return;
    }
    const imageParts = imageFiles.length > 0 ? await Promise.all(imageFiles.map(fileToImagePart)) : [];

    setStaged([]);
    // 用显式 clear：同一轮里连续 setParts 会被批处理，靠 effect 回写会漏掉（实测残留文字）
    inputRef.current?.clear();
    setParts([]);
    setHasInput(false);
    setChat((prev) => ({
      items: [
        ...prev.items,
        {
          kind: "text",
          id: `local-user-${Date.now()}`,
          role: "user",
          content: outgoing,
          streaming: false,
        },
      ],
      status: "running",
      runId: null,
    }));
    await runTurn(active, {
      text: outgoing,
      imageParts,
      restoreParts: sentParts,
      // 技能要真正生效：把 id 交给后端并入该会话的技能集（官方 SkillFilter 按它筛选可用技能）
      skillIds: partsSkillIds(sentParts),
    });
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

  /** 拖拽/粘贴进来的文件：补好名字后并入暂存区（与点选走同一条路）。 */
  function stageIncoming(files: File[]) {
    if (files.length === 0) {
      return;
    }
    setError(null);
    const normalized = normalizeIncomingFiles(files);
    setStaged((prev) => stageFiles(prev, normalized));
  }

  /** 选中文件 → 暂存（多选/追加/同名去重）；真正上传发生在 send()。 */
  function stageSelectedFiles(files: FileList | null) {
    if (!files || files.length === 0) {
      return;
    }
    setError(null);
    // 先把 FileList 固化：它是「活」的，一旦清空 input.value 就变空；
    // 而状态更新函数是在事件处理之后才执行的，放在里面读会拿到空列表。
    const selected = Array.from(files);
    setStaged((prev) => stageFiles(prev, normalizeIncomingFiles(selected)));
    if (fileRef.current) {
      // 允许再次选择同一个文件（否则浏览器的 change 事件不会再触发）
      fileRef.current.value = "";
    }
  }

  /** 停止当前生成（Phase 12 / D25）：先断前端 SSE，再让服务端中断该 run。 */
  async function stopRun() {
    if (!session) {
      return;
    }
    abortCurrentRun();
    try {
      await interruptSession(session.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "停止失败");
    } finally {
      // 已生成的内容保留，只在最后一条助手气泡上标注「已停止」
      setChat((prev) => {
        const items = [...prev.items];
        for (let i = items.length - 1; i >= 0; i -= 1) {
          const item = items[i];
          if (item.kind === "text" && item.role === "assistant") {
            items[i] = { ...item, streaming: false, stopped: true };
            break;
          }
        }
        return { ...prev, items, status: "finished" };
      });
      setBusy(false);
    }
  }

  /**
   * 发送时上传暂存附件（后端会按 D18 存储、并物化到 Agent 工作区，D20）。
   *
   * <p>失败时抛出，让调用方保留输入与附件，避免用户白填一遍。
   */
  async function uploadStaged(sessionId: number): Promise<string[]> {
    const uploaded: string[] = [];
    for (const item of staged) {
      const artifact = await uploadArtifact(sessionId, item.file);
      uploaded.push(artifact.fileName);
    }
    setArtifactRefresh((value) => value + 1);
    return uploaded;
  }

  /**
   * 把消息树渲染成节点。
   *
   * <p>连续的「工具调用」会合成一组（默认折叠）——一次任务可能产生十几个工具调用，
   * 逐个铺开会把聊天记录淹没，而且每个都单独成卡时又高又胖（用户反馈“太大”）。
   */
  function renderItemNodes() {
    const nodes: React.ReactNode[] = [];
    const items = chat.items;
    let i = 0;
    while (i < items.length) {
      const item = items[i];

      if (item.kind === "tool") {
        const group: ToolItem[] = [];
        while (i < items.length && items[i].kind === "tool") {
          group.push(items[i] as ToolItem);
          i += 1;
        }
        nodes.push(
          group.length === 1 ? (
            <ToolCallCard key={group[0].id} item={group[0]} />
          ) : (
            <ToolCallGroup key={group[0].id} items={group} />
          ),
        );
        continue;
      }

      if (item.kind === "subagent") {
        nodes.push(<SubagentCard key={item.id} item={item} />);
      } else if (item.kind === "text") {
        nodes.push(
          <MessageBubble
            key={item.id}
            role={item.role}
            content={item.content}
            streaming={item.streaming}
            stopped={item.stopped}
          />,
        );
      } else if (item.kind === "approval") {
        nodes.push(
          <ApprovalCard
            key={item.id}
            toolName={item.toolName}
            args={item.args}
            message={item.message}
            status={item.status}
            busy={busy}
            onDecide={(decision) => void decide(item.interruptId, decision)}
          />,
        );
      } else if (item.kind === "card") {
        if (item.card === "confirm") {
          nodes.push(
            <ConfirmCard
              key={item.id}
              data={item.data as ConfirmCardData}
              busy={busy}
              onChoose={(value) => void chooseOption(value)}
            />,
          );
        } else if (item.card === "mapping") {
          nodes.push(<MappingCard key={item.id} data={item.data as MappingCardData} />);
        } else {
          nodes.push(
            <JsonCard key={item.id} name={`${item.name}（${item.card}）`} data={item.data} />,
          );
        }
      } else {
        nodes.push(
          <Card key={item.id} className="border-destructive/50 p-3 text-sm">
            <span className="text-destructive">运行出错：</span> {item.message}
            {item.code && <span className="text-muted-foreground"> ({item.code})</span>}
          </Card>,
        );
      }
      i += 1;
    }
    return nodes;
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
              className={`group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm ${
                session?.id === item.id ? "bg-accent" : "hover:bg-accent/60"
              }`}
            >
              <button
                data-testid="session-item"
                className="flex min-w-0 flex-1 items-center gap-1.5 truncate text-left"
                onClick={() => void openSession(item)}
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
                onClick={() => void removeSession(item)}
                title="删除会话"
              >
                <Trash2 className="size-3" />
              </Button>
            </div>
              ))}
            </div>
          ))}
        </div>
      </aside>

      <section
        className="relative flex min-w-0 flex-1 flex-col"
        onDragOver={(event) => {
          // 必须 preventDefault，否则浏览器会当作打开文件
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          stageIncoming(filesFromDataTransfer(event.dataTransfer));
        }}
      >
        {dragging && (
          <div
            data-testid="drop-hint"
            className="bg-primary/10 border-primary/40 text-primary pointer-events-none absolute inset-2 z-10 flex items-center justify-center rounded-xl border-2 border-dashed text-sm font-medium"
          >
            松手即可添加为附件（发送时才上传）
          </div>
        )}
        <header className="border-b p-3">
          <div className="mx-auto flex w-full max-w-3xl items-center gap-2">
            <span className="text-sm font-medium">聊天</span>
            {chat.status === "running" && <Badge variant="secondary">生成中…</Badge>}
            {chat.status === "error" && <Badge variant="destructive">出错</Badge>}
          </div>
        </header>

        <div className="flex-1 overflow-auto">
          <div data-testid="chat-column" className="mx-auto w-full max-w-3xl space-y-4 p-4">
            {chat.items.length === 0 && (
              <Card className="text-muted-foreground p-6 text-center text-sm">
                输入一句话开始对话。回复会以流式气泡出现。
              </Card>
            )}
            {renderItemNodes()}
            <div ref={bottomRef} />
          </div>
        </div>

        {error && (
          <div className="mx-auto w-full max-w-3xl px-4 pb-2">
            <div className="border-destructive/40 bg-destructive/10 rounded-md border p-2 text-sm">
              {error}
              <Button variant="link" size="sm" onClick={() => void send()}>
                重试
              </Button>
            </div>
          </div>
        )}

        {/* 与消息区同宽同位置：外层不加水平 padding，内层与 chat-column 用同一套
            （max-w-3xl + px-4），这样输入框的边框正好对齐气泡列的盒子 */}
        <div className="py-3" data-testid="composer">
          <div className="mx-auto w-full max-w-3xl px-4">
            {quota && (quota.exceeded || quota.nearLimit) && (
              <div
                className={`mb-2 rounded-md border px-3 py-1.5 text-xs ${
                  quota.exceeded
                    ? "border-destructive/40 bg-destructive/10"
                    : "border-amber-300 bg-amber-50"
                }`}
                data-testid="quota-warning"
              >
                {quota.exceeded
                  ? `已超出支出额度：${quota.message ?? ""}${
                      quota.mode === "BLOCK" ? "（发起将被拒绝）" : "（仅告警，仍可发起）"
                    }`
                  : `支出额度接近上限：今日已用 ${quota.usedToday} / ${quota.dailyQuota ?? "不限"} ${quota.currency}`}
              </div>
            )}

            {/* 输入框：圆角外框 + 自动高度（内容多了自己长高，到上限内部滚动）；
                底部控制行也放在**框内**（附件 / Agent / 上下文圆环 / 停止 / 发送）。 */}
            <div className="focus-within:border-ring focus-within:ring-ring/30 rounded-2xl border bg-background shadow-sm transition focus-within:ring-[3px]">
              {/* 待发送队列（处理中时发的消息会排在这里，可撤回） */}
              {queued.length > 0 && (
                <div className="space-y-1 px-2 py-1.5" data-testid="queued-list">
                  {queued.map((item, index) => (
                    <div
                      key={item.id}
                      data-testid="queued-item"
                      className="bg-muted/50 flex items-center gap-2 rounded-md px-2 py-1 text-xs"
                    >
                      <Clock className="text-muted-foreground size-3 shrink-0" />
                      <span className="min-w-0 flex-1 truncate">
                        {item.label}
                      </span>
                      <button
                        type="button"
                        data-testid="queued-withdraw"
                        title="撤回（内容回到输入框）"
                        className="text-muted-foreground hover:text-foreground shrink-0"
                        onClick={() => withdrawQueued(item.id)}
                      >
                        撤回
                      </button>
                      <button
                        type="button"
                        data-testid="queued-guide"
                        title="引导：立刻作为补充信息交给正在执行的 Agent"
                        className="text-muted-foreground hover:text-foreground shrink-0 disabled:opacity-40"
                        disabled={!busy}
                        onClick={() => void guideQueued(item.id)}
                      >
                        引导
                      </button>
                      <span className="text-muted-foreground shrink-0">#{index + 1}</span>
                    </div>
                  ))}
                </div>
              )}

              <div className="px-2 pt-2 empty:hidden">
                <AttachmentStaging
                  items={staged}
                  disabled={uploading}
                  onRemove={(key) => setStaged((prev) => removeAttachment(prev, key))}
                />
              </div>
              <SkillMentionInput
                ref={inputRef}
                parts={parts}
                onPartsChange={setParts}
                skills={skills}
                disabled={false}
                placeholder="输入消息，回车发送（输入 / 可选择技能）"
                onSubmit={() => void send()}
                onPasteFiles={stageIncoming}
                onContentChange={setHasInput}
                bare
              />

              <div className="flex items-center gap-2 px-2 py-1.5">
                <input
                  ref={fileRef}
                  type="file"
                  multiple
                  className="hidden"
                  data-testid="file-input"
                  onChange={(e) => stageSelectedFiles(e.target.files)}
                />
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="text-muted-foreground hover:text-foreground shrink-0 rounded-full"
                  title="上传文件"
                  disabled={uploading}
                  onClick={() => fileRef.current?.click()}
                >
                  <Plus className="size-4" />
                </Button>

                <label
                  className="text-muted-foreground hover:text-foreground flex shrink-0 items-center gap-1 rounded-full px-2 py-1 text-xs"
                  title={session ? "已有会话时不可切换 Agent" : "选择 Agent"}
                >
                  <Bot className="size-3.5" />
                  <select
                    className="max-w-[9rem] cursor-pointer appearance-none bg-transparent pr-3 text-xs outline-none disabled:cursor-not-allowed"
                    value={agentId ?? ""}
                    onChange={(e) => setAgentId(e.target.value ? Number(e.target.value) : null)}
                    disabled={!!session}
                    data-testid="composer-agent"
                  >
                    {agents.length === 0 && <option value="">（暂无 Agent）</option>}
                    {agents.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDown className="-ml-3 size-3.5 pointer-events-none" />
                </label>

                <ContextRing
                  usage={contextUsage}
                  busy={contextBusy || busy}
                  notice={contextNotice}
                  onCompact={() => void handleCompact()}
                  onClear={() => void handleClearContext()}
                />

                <div className="ml-auto flex items-center gap-2">
                  {/* 按钮规则（用户要求）：
                      不执行 → 一个"发送"键；
                      执行中且输入框为空 → 这个键变成"停止"；
                      执行中且输入框有内容 → 变成"停止 + 发送"两个键（发送=排队）。 */}
                  {busy && !hasInput && (
                    <Button
                      variant="secondary"
                      size="icon-sm"
                      onClick={() => void stopRun()}
                      title="停止生成"
                      data-testid="stop-button"
                    >
                      <Square className="size-4" />
                    </Button>
                  )}
                  {busy && hasInput && (
                    <>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void stopRun()}
                        title="停止生成"
                        data-testid="stop-button"
                      >
                        <Square className="size-3.5" />
                        <span className="ml-1">停止</span>
                      </Button>
                      <Button
                        size="icon-sm"
                        onClick={() => void send()}
                        disabled={uploading}
                        title="当前任务执行完后自动发送（排队）"
                        data-testid="send-button"
                      >
                        <Clock className="size-4" />
                      </Button>
                    </>
                  )}
                  {!busy && (
                    <Button
                      size="icon-sm"
                      onClick={() => void send()}
                      disabled={!hasInput || uploading}
                      title="发送"
                      data-testid="send-button"
                    >
                      <Send className="size-4" />
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <SourceArtifactPanel
        sessionId={session?.id ?? null}
        refreshKey={artifactRefresh}
        onError={setError}
      />
    </div>
  );
}
