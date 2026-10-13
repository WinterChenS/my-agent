import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { HttpAgent } from "@ag-ui/client";
import { Paperclip, Plus, Send, Square, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { MessageBubble } from "@/components/chat/MessageBubble";
import { SourceArtifactPanel } from "@/components/chat/SourceArtifactPanel";
import { SkillPicker } from "@/components/chat/SkillPicker";
import { AttachmentStaging } from "@/components/chat/AttachmentStaging";
import { ContextRing } from "@/components/chat/ContextRing";
import type { ContextUsage, RunHistoryItem } from "@/api/sessions";
import { isAnswerVisible, lastRunHint } from "@/lib/chat/runStatus";
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
  getRunHistory,
  getRunState,
  interruptSession,
  listAgents,
  listMessages,
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
import { filesFromClipboard, filesFromDataTransfer, normalizeIncomingFiles } from "@/lib/attachments/sources";
import { groupSessionsByTime } from "@/lib/chat/sessionGroups";
import { listSkills, type SkillInfo } from "@/api/skills";
import {
  addSkill,
  filterSkills,
  removeSkill,
  skillsQuery,
  stripQuery,
  type SelectedSkill,
  type SkillOption,
} from "@/lib/skills/selection";

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
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  const [contextNotice, setContextNotice] = useState<string | null>(null);
  const [contextBusy, setContextBusy] = useState(false);
  /** 该会话上一轮的状态提示（tier1-upgrades ③）。 */
  const [lastRun, setLastRun] = useState<RunHistoryItem | null>(null);
  /** 聊天记录里助手消息的时间戳（用来判断那一轮的答案是否已经看得见）。 */
  const [assistantTimes, setAssistantTimes] = useState<string[]>([]);
  /** 后台跑完的站内提示。 */
  const [runNotice, setRunNotice] = useState<string | null>(null);
  /** 支出额度状态（tier1-upgrades ④）。 */
  const [quota, setQuota] = useState<QuotaStatus | null>(null);
  /** 正在后台跑、需要盯着结束的会话（id → 标题）。 */
  const watchingRuns = useRef<Map<number, string>>(new Map());
  // Phase 10：`/` 选技能（D21）、上传中状态、右侧栏刷新信号
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [selectedSkills, setSelectedSkills] = useState<SelectedSkill[]>([]);
  const [skillHighlight, setSkillHighlight] = useState(0);
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

  // `/` 后的候选技能：已选中的不再出现
  const skillQuery = skillsQuery(input);
  const skillOptions = useMemo(
    () => filterSkills(skills, skillQuery ?? "", selectedSkills),
    [skills, skillQuery, selectedSkills],
  );

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

  async function openSession(target: ChatSession) {
    setError(null);
    setSession(target);
    agentRef.current = createChatAgent();
    try {
      const messages = await listMessages(target.id);
      // aguiMeta 里带 stopped 标记，回放时据此标注「已停止」
      setChat(replayMessages(messages));
      setAssistantTimes(
        messages.filter((m) => m.role === "ASSISTANT" && m.createdAt).map((m) => m.createdAt),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载历史消息失败");
    }
    void refreshContext(target);
    void refreshLastRun(target);
    void refreshQuota(target.agentId ?? null);
  }

  /** 后台 run 完成检测：用户切到别的会话时，原会话跑完给一条站内提示（tier1-upgrades ③）。 */
  useEffect(() => {
    const timer = window.setInterval(() => {
      const watching = Array.from(watchingRuns.current.entries());
      if (watching.length === 0) {
        return;
      }
      for (const [sessionId, title] of watching) {
        void (async () => {
          try {
            const state = await getRunState(sessionId);
            if (!state.running) {
              watchingRuns.current.delete(sessionId);
              setRunNotice(`会话「${title}」已完成`);
            }
          } catch {
            watchingRuns.current.delete(sessionId);
          }
        })();
      }
    }, 3000);
    return () => window.clearInterval(timer);
  }, []);

  /** 刷新额度（≥80% 提示，超限说明）。 */
  async function refreshQuota(agentDbId: number | null) {
    try {
      setQuota(await getMyQuota(agentDbId));
    } catch {
      setQuota(null);
    }
  }

  /** 拉该会话最近一轮状态（后端重启后据此显示「上一轮：已完成/已中断」）。 */
  async function refreshLastRun(target: ChatSession | null) {
    if (!target) {
      setLastRun(null);
      return;
    }
    try {
      const runs = await getRunHistory(target.id, 5);
      setLastRun(runs[0] ?? null);
    } catch {
      setLastRun(null);
    }
  }

  function startNewSession() {
    setSession(null);
    setChat(initialChatState);
    agentRef.current = createChatAgent();
    setError(null);
    // 新会话必须把「上一轮状态 / 后台完成提示 / 上下文用量」一并清掉，
    // 否则它们会残留到空白的新会话里（用户实测：新开对话仍看到上一轮的提示）。
    setLastRun(null);
    setAssistantTimes([]);
    setRunNotice(null);
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
  async function refreshContext(active: ChatSession | null) {
    if (!active) {
      setContextUsage(null);
      return;
    }
    try {
      setContextUsage(await getContextUsage(active.id));
    } catch {
      setContextUsage(null);
    }
  }

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
      parts?: unknown[];
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
          content: options.parts?.length
            ? [{ type: "text", text: options.text }, ...options.parts]
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
            skillIds: selectedSkills.map((skill) => skill.id),
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
      // tier1-upgrades ③：这一轮结束后刷新「上一轮」提示
      void refreshLastRun(active);
      await refreshSessions();
      // Phase 17：会话标题是异步生成的（首条消息后），稍等一下再刷一次列表，标题就能显示出来
      window.setTimeout(() => void refreshSessions(), 1500);
      // 跑完一轮刷新右侧「产物」区（D22 自动登记）
      setArtifactRefresh((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败，可重试");
      setChat((prev) => ({ ...prev, status: "error" }));
      if (options.text) {
        setInput(options.text);
      }
    } finally {
      setBusy(false);
      void refreshContext(session);
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
    setInput("");
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
    await runTurn(active, { text: outgoing, parts: imageParts });
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

  function pickSkill(skill: SkillOption) {
    setSelectedSkills((prev) => addSkill(prev, skill));
    setInput((prev) => stripQuery(prev));
    setSkillHighlight(0);
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

        <div className="border-t p-3">
          <div className="relative mx-auto w-full max-w-3xl">
            {quota && (quota.exceeded || quota.nearLimit) && (
              <div
                className={`mb-2 rounded-md border px-3 py-1.5 text-xs ${
                  quota.exceeded ? "border-destructive/40 bg-destructive/10" : "border-amber-300 bg-amber-50"
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
            {lastRunHint(lastRun, {
              answerVisible: isAnswerVisible(lastRun, assistantTimes),
            }) && (
              <div
                className="mb-2 text-xs text-muted-foreground"
                data-testid="last-run-hint"
              >
                {lastRunHint(lastRun, { answerVisible: isAnswerVisible(lastRun, assistantTimes) })}
              </div>
            )}
            {runNotice && (
              <div
                className="mb-2 flex items-center gap-2 rounded-md border px-3 py-1.5 text-xs"
                data-testid="run-notice"
              >
                <span>{runNotice}</span>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto p-0"
                  data-testid="run-notice-jump"
                  onClick={() => {
                    const target = sessions.find((item) =>
                      runNotice.includes(item.title ?? `会话 ${item.id}`),
                    );
                    if (target) {
                      void openSession(target);
                    }
                    setRunNotice(null);
                  }}
                >
                  跳到该会话
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-auto p-0 text-muted-foreground"
                  onClick={() => setRunNotice(null)}
                >
                  知道了
                </Button>
              </div>
            )}
            <SkillPicker
              selected={selectedSkills}
              query={skillQuery}
              options={skillOptions}
              highlight={skillHighlight}
              onSelect={pickSkill}
              onRemove={(id) => setSelectedSkills((prev) => removeSkill(prev, id))}
              onHighlight={setSkillHighlight}
            />
            <AttachmentStaging
              items={staged}
              disabled={busy || uploading}
              onRemove={(key) => setStaged((prev) => removeAttachment(prev, key))}
            />
            <div className="flex items-center gap-2">
              <input
                ref={fileRef}
                type="file"
                multiple
                className="hidden"
                data-testid="file-input"
                onChange={(e) => stageSelectedFiles(e.target.files)}
              />
              <Button
                variant="outline"
                size="icon"
                title="上传文件"
                disabled={busy || uploading}
                onClick={() => fileRef.current?.click()}
              >
                <Paperclip className="size-4" />
              </Button>
              <Input
                value={input}
                placeholder="输入消息，回车发送"
                onChange={(e) => {
                  setInput(e.target.value);
                  setSkillHighlight(0);
                }}
                onPaste={(event) => {
                  // Phase 13：粘贴截图/文件直接进暂存区；纯文本粘贴保持原行为
                  const files = filesFromClipboard(event.clipboardData);
                  if (files.length > 0) {
                    event.preventDefault();
                    stageIncoming(files);
                  }
                }}
                onKeyDown={(e) => {
                  // 菜单打开时优先走菜单按键
                  if (skillQuery !== null && skillOptions.length > 0) {
                    if (e.key === "ArrowDown") {
                      e.preventDefault();
                      setSkillHighlight((prev) => (prev + 1) % skillOptions.length);
                      return;
                    }
                    if (e.key === "ArrowUp") {
                      e.preventDefault();
                      setSkillHighlight(
                        (prev) => (prev - 1 + skillOptions.length) % skillOptions.length,
                      );
                      return;
                    }
                    if (e.key === "Enter") {
                      e.preventDefault();
                      pickSkill(skillOptions[Math.min(skillHighlight, skillOptions.length - 1)]);
                      return;
                    }
                    if (e.key === "Escape") {
                      e.preventDefault();
                      setInput((prev) => stripQuery(prev));
                      return;
                    }
                  }
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    void send();
                  }
                }}
                disabled={busy}
              />
              <ContextRing
                usage={contextUsage}
                busy={contextBusy || busy}
                notice={contextNotice}
                onCompact={() => void handleCompact()}
                onClear={() => void handleClearContext()}
              />
              {busy ? (
                <Button
                  variant="secondary"
                  onClick={() => void stopRun()}
                  title="停止生成"
                  data-testid="stop-button"
                >
                  <Square className="size-4" />
                  <span className="ml-1">停止</span>
                </Button>
              ) : (
                <Button onClick={() => void send()} disabled={!input.trim()}>
                  <Send className="size-4" />
                </Button>
              )}
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
